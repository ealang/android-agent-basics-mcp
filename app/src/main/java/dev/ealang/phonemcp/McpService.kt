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
import android.util.Log
import dev.ealang.phonemcp.mcp.McpServer
import dev.ealang.phonemcp.mcp.apps.Sound
import dev.ealang.phonemcp.webhook.DrainReport
import dev.ealang.phonemcp.webhook.PebbleWebhookServer
import dev.ealang.phonemcp.webhook.PipelineWorker
import dev.ealang.phonemcp.webhook.RecordingStore
import dev.ealang.phonemcp.webhook.RetryGate
import dev.ealang.phonemcp.webhook.TelegramDelivery
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
        private const val CHANNEL_ID = "phone-mcp-server-quiet"
        private const val NOTIFICATION_ID = 1

        const val ACTION_STOP = "dev.ealang.phonemcp.STOP"

        /** Re-read the two listener feature keys and bind or stop each to match. */
        private const val ACTION_APPLY_TOGGLES = "dev.ealang.phonemcp.APPLY_TOGGLES"

        private const val INITIAL_RETRY_MS = 5_000L
        /** A capped once-a-minute retry costs nothing and never wakes a sleeping phone. */
        private const val MAX_RETRY_MS = 60_000L
        /** Broadcast whenever the server starts or stops, so the UI can refresh. */
        const val ACTION_STATE_CHANGED = "dev.ealang.phonemcp.STATE_CHANGED"

        /** The MCP listener is bound. Narrower than [serviceRunning] since the
         * listeners became independently switchable. */
        @Volatile
        var running: Boolean = false
            private set

        /** The webhook listener is bound; the webhook twin of [running]. */
        @Volatile
        var webhookRunning: Boolean = false
            private set

        /** The service itself is up — pipeline worker included — regardless
         * of which listeners are. */
        @Volatile
        var serviceRunning: Boolean = false
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
                // Container-level: neither server alone — both logs get it.
                Logs.init(context.filesDir)
                Logs.both { it.error("service start refused", reason) }
                Config(context).lastStartFailure = "start refused — $reason"
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, McpService::class.java).setAction(ACTION_STOP))
        }

        /**
         * Tell a running service a listener switch flipped. Intake only:
         * off stops that listener's socket — the webhook's watch sees
         * connection-refused and retries on its side — while the pipeline
         * worker finishes draining whatever was already accepted and then
         * idles. Callers guard with [serviceRunning]: on a stopped service
         * the config values alone are enough, the next start reads them.
         */
        fun applyServerToggles(context: Context) {
            context.startService(
                Intent(context, McpService::class.java).setAction(ACTION_APPLY_TOGGLES),
            )
        }

        /**
         * The running service's pipeline, for MainActivity's Retry/Discard
         * buttons. Set on the main thread by [startPipeline]/[stopPipeline];
         * read from the UI, which is the same thread. Routing UI actions
         * through the worker keeps `failed/` single-writer (§10).
         */
        @Volatile
        private var activeWorker: PipelineWorker? = null

        @Volatile
        private var activeStore: RecordingStore? = null

        /** Queue depth and failed items for the UI, or null when stopped. */
        fun pipelineSnapshot(): Pair<Int, List<RecordingStore.Item>>? {
            val store = activeStore ?: return null
            return store.pending().size to store.failed()
        }

        /**
         * Everything in failed/ back into the queue. All-or-nothing on
         * purpose (2026-09-18): per-item Retry/Discard buttons existed and
         * were overkill — with unprocessable items now dropped instead of
         * parked, what fails is delivery, and a fixed delivery problem is
         * fixed for every item at once.
         */
        fun retryAllFailed(context: Context) {
            onPipeline(context) { store -> store.failed().forEach(store::retry) }
        }

        /**
         * Everything out, queued and failed alike — the escape hatch for a
         * backlog nobody wants delivered (a logged-out Telegram quietly
         * queues every send). Runs on the worker thread like the per-item
         * buttons, so a drain in progress finishes its current item first
         * and never sees a directory vanish under it.
         */
        fun clearMessages(context: Context) {
            onPipeline(context) { store ->
                store.pending().forEach(store::discard)
                store.failed().forEach(store::discard)
            }
        }

        /**
         * Runs the action on the worker thread when the service is up, else
         * directly on a fresh store — safe, because no worker means no other
         * writer exists.
         */
        private fun onPipeline(context: Context, action: (RecordingStore) -> Unit) {
            val worker = activeWorker
            val store = activeStore
            if (worker != null && store != null) {
                worker.post { action(store) }
                worker.wake()
            } else {
                action(RecordingStore(context.filesDir))
            }
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

    private var mcpServer: McpServer? = null

    /**
     * The Pebble webhook's own listener, hardcoded to loopback (see
     * [PebbleWebhookServer.BIND]). Separate from [mcpServer] so the recording
     * inbox neither depends on the configurable bind address nor is exposed
     * by it.
     */
    private var webhookServer: PebbleWebhookServer? = null

    private val main = Handler(Looper.getMainLooper())
    private val retry = Runnable { attemptStart() }
    private var retryDelayMs = INITIAL_RETRY_MS

    // ------------------------- webhook pipeline -------------------------
    // The service is the orchestrator (docs/webhook-pipeline.md §10): it
    // builds the components and owns every edge between them; none of them
    // holds a reference to another. All of this state is main-thread
    // confined except where noted.

    private var store: RecordingStore? = null
    private var worker: PipelineWorker? = null
    private var gate: RetryGate? = null

    /** What the notification says apart from failures, e.g. "Listening on …". */
    private var baseText = "Starting…"

    private val connectivity = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            // Connectivity is the real retry signal; the gate's timer is the
            // fallback. Safe from this binder thread: wake() is thread-safe.
            worker?.wake()
        }
    }

    private fun startPipeline() {
        if (worker != null) return

        val store = RecordingStore(filesDir).also { store = it }
        val log = Logs.webhook
        gate = RetryGate(main) { worker?.wake() }

        val delivery = TelegramDelivery(this, store, log)

        worker = PipelineWorker(
            store = store,
            log = log,
            telegramSender = delivery,
            onDrainFinished = { report -> main.post { onDrainFinished(report) } },
        ).also {
            it.start()
            it.wake() // drain anything left from before the restart
        }

        // Every engine event — a delivery confirmed, a send abandoned, the
        // connection back — is the same parameter-free wake; the drain
        // discovers what changed by reconciling. The event path stays
        // ignorant of any particular messenger on purpose.
        dev.ealang.phonemcp.webhook.telegram.TelegramEngine.onDeliveryEvent = { worker?.wake() }

        getSystemService(android.net.ConnectivityManager::class.java)
            .registerDefaultNetworkCallback(connectivity)

        activeStore = store
        activeWorker = worker
    }

    private fun onDrainFinished(report: DrainReport) {
        if (report.completed > 0) gate?.reset()
        if (report.blockedOn != null) gate?.arm()
        // Success is silent; the notification only ever gains a failure
        // notice, and re-rendering also clears one whose failed/ emptied.
        notify(baseText)
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    private fun stopPipeline() {
        try {
            getSystemService(android.net.ConnectivityManager::class.java)
                .unregisterNetworkCallback(connectivity)
        } catch (e: Exception) {
            // Never registered; nothing to do.
        }
        dev.ealang.phonemcp.webhook.telegram.TelegramEngine.onDeliveryEvent = {}
        gate?.cancel()
        worker?.quit()
        activeWorker = null
        activeStore = null
        worker = null
        gate = null
        store = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Idempotent; the activity does the same. Whoever runs first wins.
        Logs.init(filesDir)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_APPLY_TOGGLES) {
            applyServerToggles()
            return START_STICKY
        }

        if (intent?.action == ACTION_STOP) {
            Config(this).enabled = false
            Logs.both { it.info("stopped by user") }
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
        startPipeline()
        // The bind needs the bearer token, and reading a secret before the
        // Keystore is warm does its slow first contact right here on the main
        // thread. warm() runs that on a worker and calls back (immediately,
        // once it has happened once); attemptStart is idempotent, so a repeat
        // start queuing another callback is harmless.
        SecretStore.warm(this) {
            // Telegram rides the same warm-up: its db encryption key is in the
            // SecretStore too. Starting it here (not only in the activity)
            // keeps the connection alive when the service came up on its own —
            // after a reboot, with no screen ever opened.
            dev.ealang.phonemcp.webhook.telegram.TelegramEngine.ensureStarted(this)
            attemptStart()
        }

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
                startForeground(NOTIFICATION_ID, notification("Starting…"))
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
            startForeground(NOTIFICATION_ID, notification("Starting…"), full)
            if (degraded) Logs.both { it.info("location foreground type regained") }
            degraded = false
            return true
        } catch (e: Exception) {
            Log.i(TAG, "Refused the full foreground type set; falling back", e)
        }

        return try {
            startForeground(
                NOTIFICATION_ID,
                notification("Starting…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
            degraded = true
            Logs.both { it.warn("started without location type; get_location may be limited") }
            true
        } catch (e: Exception) {
            recordForegroundRefusal(e)
            false
        }
    }

    private fun recordForegroundRefusal(e: Exception) {
        val reason = "${e::class.java.simpleName}: ${e.message ?: "no detail"}"
        Log.w(TAG, "Refused foreground status; not retrying", e)
        Logs.both { it.error("foreground start refused", reason) }
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

        val config = Config(this)

        // The webhook listener first: loopback always exists, so this bind
        // owes nothing to Tailscale being up and should not wait behind it.
        // Switched off means don't bind — and, below, don't retry: a listener
        // that is off by choice is not a bind that failed.
        val webhookWanted = config.featureEnabled(Config.FEATURE_WEBHOOK)
        if (webhookServer == null && webhookWanted) {
            // Read here, not in the constructor call, so the log lines below
            // name the port that was actually tried.
            val webhookPort = config.webhookPort
            val instance = PebbleWebhookServer(
                webhookPort,
                { config.webhookToken },
                onDied = ::onWebhookServerDied,
                log = Logs.webhook,
                // Runs on NanoHTTPD's request thread. enqueue is thread-safe
                // (it only ever creates), wake is thread-safe by design; a
                // throw here becomes the 500 that makes the watch retry.
                enqueue = { audio, meta ->
                    val item = store?.enqueue(audio, meta)
                        ?: throw IllegalStateException("pipeline not started")
                    Logs.webhook.info("received", meta.trigger.name, item.name)
                    worker?.wake()
                },
            )
            try {
                instance.start(NanoTimeouts.SOCKET_READ_MS, false)
                webhookServer = instance
                webhookRunning = true
                Logs.webhook.info(
                    "listening on ${PebbleWebhookServer.BIND}:$webhookPort",
                )
            } catch (e: Exception) {
                // Only a port squatter can make loopback fail; the shared
                // retry below picks it up.
                Log.w(TAG, "Could not bind the webhook listener; will retry", e)
                Logs.webhook.warn(
                    "bind ${PebbleWebhookServer.BIND}:$webhookPort failed",
                    e.message ?: e::class.java.simpleName,
                )
            }
        }

        val listeningOn = config.bindAddress.ifBlank { "every interface" }
        val mcpWanted = config.featureEnabled(Config.FEATURE_MCP)
        if (mcpServer == null && mcpWanted) {
            val instance = McpServer(
                applicationContext,
                config.bindAddress,
                config.port,
                { config.mcpToken },
                onDied = ::onServerDied,
                log = Logs.mcp,
            )
            try {
                // daemon=false: the socket should outlive the thread that made it.
                instance.start(NanoTimeouts.SOCKET_READ_MS, false)
                mcpServer = instance
                running = true
                retryDelayMs = INITIAL_RETRY_MS
                Logs.mcp.info("listening on $listeningOn:${config.port}")
                baseText = "Listening on $listeningOn:${config.port}"
                Log.i(TAG, "Listening on $listeningOn port ${config.port}")
            } catch (e: Exception) {
                Log.w(TAG, "Could not bind $listeningOn port ${config.port}; will retry", e)
                Logs.mcp.warn(
                    "bind $listeningOn:${config.port} failed",
                    "${e.message ?: e::class.java.simpleName}; retry in ${retryDelayMs / 1000}s",
                )
                baseText = "Waiting to bind $listeningOn:${config.port}"
            }
        } else if (mcpServer == null) {
            // Off by choice; the notification should not read as a failure.
            baseText = "MCP server off" +
                if (webhookServer != null) "; webhook listening" else ""
        }
        notify(baseText)

        // One retry serves both listeners: each pass skips whatever is
        // already up, so an armed retry is just "something wanted is still
        // missing". A listener that is off by choice never counts.
        if ((webhookWanted && webhookServer == null) || (mcpWanted && mcpServer == null)) {
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
            mcpServer?.stop()
            mcpServer = null
            running = false
            attemptStart()
        }
    }

    /**
     * A listener switch flipped while we were running. Off: stop that
     * socket and nothing else — the worker keeps draining what was
     * accepted. On: the closing attemptStart binds whatever is newly
     * wanted, idempotently, and re-renders the notification either way.
     * A retry already scheduled needs no cancelling here: its next pass
     * re-reads the switches and skips what is off.
     */
    private fun applyServerToggles() {
        val config = Config(this)
        if (!config.featureEnabled(Config.FEATURE_WEBHOOK) && webhookServer != null) {
            webhookServer?.stop()
            webhookServer = null
            webhookRunning = false
            Logs.webhook.info("listener switched off")
        }
        if (!config.featureEnabled(Config.FEATURE_MCP) && mcpServer != null) {
            mcpServer?.stop()
            mcpServer = null
            running = false
            Logs.mcp.info("listener switched off")
        }
        attemptStart()
    }

    private fun onWebhookServerDied() {
        main.post {
            Log.w(TAG, "Webhook listener died underneath us; rebinding")
            Logs.webhook.warn("restarting after socket death")
            webhookServer?.stop()
            webhookServer = null
            webhookRunning = false
            attemptStart()
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(retry)
        stopPipeline()
        // Nothing would be left able to answer find_my_phone_stop, so a ring that
        // outlived the server would have to be waited out.
        Sound.stop()
        mcpServer?.stop()
        mcpServer = null
        webhookServer?.stop()
        webhookServer = null
        running = false
        webhookRunning = false
        serviceRunning = false
        degraded = false
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
        Log.i(TAG, "Stopped")
        super.onDestroy()
    }

    private fun notify(text: String) {
        // The one place a pipeline failure is announced (§7): a suffix on the
        // notification the service must show anyway. Never a separate,
        // buzzing notification.
        val failed = store?.failed()?.size ?: 0
        val full = if (failed > 0) "$text — $failed failed, open to review" else text
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(full))
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_MIN: the notification is a requirement of running in
            // the foreground, not information — MIN keeps it out of the status
            // bar entirely and collapses it into the shade's minimized section.
            // Importance cannot be changed on an existing channel (it belongs
            // to the user once created), so the move from IMPORTANCE_LOW was a
            // new channel id; the old one is deleted so Settings shows one.
            manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MCP server",
                NotificationManager.IMPORTANCE_MIN,
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
