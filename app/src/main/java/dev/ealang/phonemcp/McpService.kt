package dev.ealang.phonemcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
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
        private const val CHANNEL_ID = "phone-mcp-server"
        private const val NOTIFICATION_ID = 1

        const val ACTION_STOP = "dev.ealang.phonemcp.STOP"

        private const val INITIAL_RETRY_MS = 5_000L
        /** A capped once-a-minute retry costs nothing and never wakes a sleeping phone. */
        private const val MAX_RETRY_MS = 60_000L
        /** Broadcast whenever the server starts or stops, so the UI can refresh. */
        const val ACTION_STATE_CHANGED = "dev.ealang.phonemcp.STATE_CHANGED"

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, McpService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Config(this).enabled = false
            EventLog.record("stopped by user")
            stopSelf()
            return START_NOT_STICKY
        }

        Config(this).enabled = true

        // Started before the first bind attempt: the five-second foreground
        // deadline must be met even if binding waits on Tailscale.
        startForeground(NOTIFICATION_ID, notification("Starting…"))
        retryDelayMs = INITIAL_RETRY_MS
        attemptStart()

        // START_STICKY: if Android reclaims us under memory pressure, come back.
        return START_STICKY
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
            config.token,
            onDied = ::onServerDied,
        )
        try {
            // daemon=false: the socket should outlive the thread that made it.
            instance.start(NanoTimeouts.SOCKET_READ_MS, false)
            server = instance
            running = true
            retryDelayMs = INITIAL_RETRY_MS
            EventLog.record("listening on $listeningOn:${config.port}")
            notify("Listening on $listeningOn:${config.port}")
            Log.i(TAG, "Listening on $listeningOn port ${config.port}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not bind $listeningOn port ${config.port}; will retry", e)
            EventLog.record(
                "bind $listeningOn:${config.port} failed " +
                    "(${e.message ?: e::class.java.simpleName}); retry in ${retryDelayMs / 1000}s",
            )
            notify("Waiting to bind $listeningOn:${config.port}")
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
            EventLog.record("server restarting after socket death")
            server?.stop()
            server = null
            running = false
            attemptStart()
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(retry)
        // Nothing would be left able to answer silence_phone, so a ring that
        // outlived the server would have to be waited out.
        Sound.stop()
        server?.stop()
        server = null
        running = false
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
        Log.i(TAG, "Stopped")
        super.onDestroy()
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_LOW: the notification is a requirement of running in the
            // foreground, not something the user needs to be alerted about.
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MCP server",
                NotificationManager.IMPORTANCE_LOW,
            )
            channel.description = "Shown while the local MCP server is running."
            manager.createNotificationChannel(channel)
        }

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

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Phone MCP")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }
}

private object NanoTimeouts {
    /** NanoHTTPD's default; long enough for a keep-alive client to idle. */
    const val SOCKET_READ_MS = 5000
}
