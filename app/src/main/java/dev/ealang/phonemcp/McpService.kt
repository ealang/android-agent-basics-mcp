package dev.ealang.phonemcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.ealang.phonemcp.mcp.McpServer
import dev.ealang.phonemcp.mcp.apps.Sound
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Holds the HTTP server for as long as the user wants it up.
 *
 * A foreground service is the only way to keep a listening socket alive on a
 * modern Android — a plain background service is killed within minutes, and
 * the socket dies with it.
 */
class McpService : Service() {

    companion object {
        private const val TAG = "PhoneMcpService"
        private const val LEGACY_CHANNEL_ID = "phone-mcp-server"
        /** The routine "server is up" notification. Users may block this one. */
        const val QUIET_CHANNEL_ID = "phone-mcp-server-quiet"
        /** Where the notification moves while the server cannot listen. */
        const val TROUBLE_CHANNEL_ID = "phone-mcp-server-trouble"
        private const val QUIET_NOTIFICATION_ID = 1
        private const val TROUBLE_NOTIFICATION_ID = 2
        /**
         * How long binding may keep failing before it counts as trouble.
         * Shorter than this is routine: the usual cause is Tailscale still
         * coming up after a boot or a reconnect, which takes seconds.
         */
        private const val TROUBLE_AFTER_MS = 120_000L

        const val ACTION_STOP = "dev.ealang.phonemcp.STOP"

        private const val INITIAL_RETRY_MS = 5_000L
        /** A capped once-a-minute retry costs nothing and never wakes a sleeping phone. */
        private const val MAX_RETRY_MS = 60_000L
        /** Broadcast whenever the server starts or stops, so the UI can refresh. */
        const val ACTION_STATE_CHANGED = "dev.ealang.phonemcp.STATE_CHANGED"

        /**
         * Creates the notification channels if needed. Idempotent, and safe
         * before the service has ever run — the activity calls it before
         * opening a channel's settings screen, which needs the channel to
         * exist.
         */
        fun ensureChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            // IMPORTANCE_MIN: the notification is a requirement of running in
            // the foreground, not information — MIN keeps it out of the status
            // bar entirely and collapses it into the shade's minimized section.
            // Importance cannot be changed on an existing channel (it belongs
            // to the user once created), so the move from IMPORTANCE_LOW was a
            // new channel id; the old one is deleted so Settings shows one.
            manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
            manager.createNotificationChannel(
                NotificationChannel(
                    QUIET_CHANNEL_ID,
                    "MCP server",
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    description = "Shown while the local MCP server is running. " +
                        "Safe to turn off: trouble is reported on its own channel."
                },
            )
            // IMPORTANCE_DEFAULT so it sounds the way the phone is set to; the
            // user can tune this channel without touching the routine one.
            manager.createNotificationChannel(
                NotificationChannel(
                    TROUBLE_CHANNEL_ID,
                    "Server trouble",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "The server has been unable to listen for more than " +
                        "a couple of minutes — usually because its bind address " +
                        "is gone, such as when the VPN is down."
                },
            )
        }

        /** The service is up. Whether the socket is bound yet is [running]. */
        @Volatile
        var serviceRunning: Boolean = false
            private set

        /** The listener is bound. */
        @Volatile
        var running: Boolean = false
            private set

        /**
         * Running, but without the `location` foreground type — see
         * [goForeground]. The server is fine; `get_location` may not be.
         * Cleared by a retry made while the app is on screen.
         */
        @Volatile
        var degraded: Boolean = false
            private set

        /**
         * Asking for the service is allowed to fail. Since Android 12 a
         * background app may be refused a foreground-service start outright,
         * and the refusal is an exception thrown at the caller — so an
         * unguarded call here takes down whatever was making it, which
         * includes [BootReceiver] running on a broadcast the app cannot
         * decline. The reason is kept where it can outlive the process.
         */
        fun start(context: Context) {
            val intent = Intent(context, McpService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                val reason = "${e::class.java.simpleName}: ${e.message ?: "no detail"}"
                Log.w(TAG, "Android refused to start the service", e)
                Logs.init(context.filesDir)
                Logs.mcp.error("service start refused", reason)
                Config(context).lastStartFailure = "start refused — $reason"
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, McpService::class.java).setAction(ACTION_STOP))
        }

        /** The phone's address on the LAN/tailnet, or null if it isn't on a network. */
        fun localAddresses(): List<String> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { iface -> iface.inetAddresses.toList().map { iface.name to it } }
                .filter { (_, addr) -> addr is Inet4Address }
                .map { (name, addr) -> "${addr.hostAddress} ($name)" }
        } catch (e: Exception) {
            Log.w(TAG, "Could not enumerate network interfaces", e)
            emptyList()
        }
    }

    private var server: McpServer? = null

    private val main = Handler(Looper.getMainLooper())
    private val retry = Runnable { attemptStart() }
    private var retryDelayMs = INITIAL_RETRY_MS

    /** elapsedRealtime of the first failed bind in the current run of failures, 0 when bound. */
    private var failingSince = 0L
    /** Which notification is the foreground one right now — see [show]. */
    private var showingTrouble = false
    /**
     * The foreground types [goForeground] actually got, or null below API 34
     * where none are passed. A later `startForeground` must ask for exactly
     * these: asking for the full set again from the background could be
     * refused, and falling back would strip `location` off a healthy service.
     */
    private var foregroundTypes: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Idempotent; the activity does the same. Whoever runs first wins.
        Logs.init(filesDir)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Config(this).enabled = false
            Logs.mcp.info("stopped by user")
            stopSelf()
            return START_NOT_STICKY
        }

        Config(this).enabled = true
        serviceRunning = true

        // Started before the first bind attempt: the five-second foreground
        // deadline must be met even if binding waits on Tailscale.
        if (!goForeground()) {
            // Being refused foreground status is survivable; crashing is not,
            // because START_STICKY would bring us straight back to the same
            // refusal — the loop the system reports as the app repeatedly
            // stopping. Stop cleanly and stay stopped.
            stopSelf()
            return START_NOT_STICKY
        }

        Config(this).lastStartFailure = null
        retryDelayMs = INITIAL_RETRY_MS
        // The bind needs the bearer token, and reading a secret before the
        // Keystore is warm does its slow first contact right here on the main
        // thread. warm() runs that on a worker and calls back (immediately,
        // once it has happened once); attemptStart is idempotent, so a repeat
        // start queuing another callback is harmless.
        SecretStore.warm(this) { attemptStart() }

        // START_STICKY: if Android reclaims us under memory pressure, come back.
        return START_STICKY
    }

    /**
     * Takes foreground status, giving up `location` rather than the service.
     *
     * Left to itself, `startForeground` claims every type the manifest
     * declares — and `location` is a *while-in-use* type: Android grants it
     * only to an app in an eligible state, which an app being started by a
     * background broadcast is not. So an update or a reboot, where
     * [BootReceiver] is the caller, was refused outright and the whole server
     * went down with it. The user saw the app repeatedly crashing.
     *
     * `specialUse` carries no such condition, so it is the fallback: the HTTP
     * listener — the point of the service — has no use for `location` anyway.
     * Only `get_location` does, and losing that is much better than losing
     * everything. [degraded] records which we got, so it can be retried from
     * the foreground later.
     *
     * Returns false only if even the bare start was refused.
     */
    private fun goForeground(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Types are not enforced before 34; asking for one is what breaks.
            return try {
                startForeground(QUIET_NOTIFICATION_ID, notification("Starting…", trouble = false))
                foregroundTypes = null
                showingTrouble = false
                degraded = false
                true
            } catch (e: Exception) {
                recordForegroundRefusal(e)
                false
            }
        }

        val full = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        try {
            startForeground(QUIET_NOTIFICATION_ID, notification("Starting…", trouble = false), full)
            foregroundTypes = full
            showingTrouble = false
            if (degraded) Logs.mcp.info("location foreground type regained")
            degraded = false
            return true
        } catch (e: Exception) {
            Log.i(TAG, "Refused the full foreground type set; falling back", e)
        }

        return try {
            startForeground(
                QUIET_NOTIFICATION_ID,
                notification("Starting…", trouble = false),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
            foregroundTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            showingTrouble = false
            degraded = true
            Logs.mcp.warn("started without location type; get_location may be limited")
            true
        } catch (e: Exception) {
            recordForegroundRefusal(e)
            false
        }
    }

    private fun recordForegroundRefusal(e: Exception) {
        val reason = "${e::class.java.simpleName}: ${e.message ?: "no detail"}"
        Log.w(TAG, "Refused foreground status; not retrying", e)
        Logs.mcp.error("foreground start refused", reason)
        Config(this).lastStartFailure = "foreground start refused — $reason"
    }

    /**
     * Binds now if possible, otherwise leaves a retry scheduled. The service
     * stays up and keeps trying rather than stopping: binding one address
     * fails whenever that interface is missing — the usual case being a boot
     * or a reconnect that got here before Tailscale — and a service that gave
     * up turned that into a server off until someone noticed.
     */
    private fun attemptStart() {
        main.removeCallbacks(retry)
        if (server != null) return

        val config = Config(this)
        val listeningOn = config.bindAddress.ifBlank { "every interface" }
        val instance = McpServer(
            applicationContext,
            config.bindAddress,
            config.port,
            { config.token },
            onDied = ::onServerDied,
            log = Logs.mcp,
        )
        try {
            // daemon=false: the socket should outlive the thread that made it.
            instance.start(NanoTimeouts.SOCKET_READ_MS, false)
            server = instance
            running = true
            retryDelayMs = INITIAL_RETRY_MS
            if (failingSince != 0L) Logs.mcp.info("bound after failing for ${sinceFailing()}")
            failingSince = 0L
            Logs.mcp.info("listening on $listeningOn:${config.port}")
            show("Listening on $listeningOn:${config.port}", trouble = false)
            Log.i(TAG, "Listening on $listeningOn port ${config.port}")
        } catch (e: Exception) {
            val reason = e.message ?: e::class.java.simpleName
            Log.w(TAG, "Could not bind $listeningOn port ${config.port}; will retry", e)
            Logs.mcp.warn(
                "bind $listeningOn:${config.port} failed",
                "$reason; retry in ${retryDelayMs / 1000}s",
            )
            if (failingSince == 0L) failingSince = SystemClock.elapsedRealtime()
            val trouble = SystemClock.elapsedRealtime() - failingSince >= TROUBLE_AFTER_MS
            show(
                if (trouble) {
                    "Not listening for ${sinceFailing()}: cannot bind " +
                        "$listeningOn:${config.port} ($reason). Retrying every minute."
                } else {
                    "Waiting to bind $listeningOn:${config.port}"
                },
                trouble,
            )
            main.postDelayed(retry, retryDelayMs)
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
        }
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    /**
     * Called from the listener thread when the socket has failed repeatedly
     * and shut itself. Tears the server down and goes back to binding.
     */
    private fun onServerDied() {
        main.post {
            Log.w(TAG, "Server died underneath us; rebinding")
            Logs.mcp.warn("restarting after socket death")
            server?.stop()
            server = null
            running = false
            attemptStart()
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(retry)
        // Nothing would be left able to answer find_my_phone_stop, so a ring that
        // outlived the server would have to be waited out.
        Sound.stop()
        server?.stop()
        server = null
        running = false
        serviceRunning = false
        degraded = false
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
        Log.i(TAG, "Stopped")
        super.onDestroy()
    }

    private fun sinceFailing(): String {
        val minutes = (SystemClock.elapsedRealtime() - failingSince) / 60_000
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
    }

    /**
     * Updates the one service notification, moving it between the quiet and
     * trouble channels as needed.
     *
     * There is deliberately a single notification. Routine status lives on a
     * channel the user is welcome to block; trouble has to be able to reach
     * them anyway, so it is posted on a second channel — but as the *same*
     * notification moved there, never as an extra one beside it. A posted
     * notification cannot change channel in place, and the foreground
     * notification cannot be cancelled while the service holds it, so the
     * move is another `startForeground` under a different id: the system
     * drops the previous foreground notification when the id changes.
     *
     * `setOnlyAlertOnce` on the builder means the trouble channel sounds once
     * per outage, not once per retry; moving back to quiet cancels the
     * trouble notification, so the next outage alerts afresh.
     */
    private fun show(text: String, trouble: Boolean) {
        val n = notification(text, trouble)
        val id = if (trouble) TROUBLE_NOTIFICATION_ID else QUIET_NOTIFICATION_ID
        if (trouble == showingTrouble) {
            getSystemService(NotificationManager::class.java).notify(id, n)
            return
        }
        try {
            val types = foregroundTypes
            if (types == null) startForeground(id, n) else startForeground(id, n, types)
            showingTrouble = trouble
            Log.i(TAG, "Service notification moved to ${if (trouble) "trouble" else "quiet"} channel")
        } catch (e: Exception) {
            // Keep whatever is showing rather than lose the notification.
            Log.w(TAG, "Could not move the service notification; updating in place", e)
            getSystemService(NotificationManager::class.java).notify(
                if (showingTrouble) TROUBLE_NOTIFICATION_ID else QUIET_NOTIFICATION_ID,
                notification(text, showingTrouble),
            )
        }
    }

    private fun notification(text: String, trouble: Boolean): Notification {
        ensureChannels(this)

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, McpService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, if (trouble) TROUBLE_CHANNEL_ID else QUIET_CHANNEL_ID)
            .setContentTitle(if (trouble) "Phone MCP server is down" else "Phone MCP")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.ic_stat_pyramid)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            // Its own group keeps the system's auto-bundling from stacking
            // agent notifications underneath this permanent one.
            .setGroup("phone-mcp-service")
            .build()
    }
}

private object NanoTimeouts {
    /** NanoHTTPD's default; long enough for a keep-alive client to idle. */
    const val SOCKET_READ_MS = 5000
}
