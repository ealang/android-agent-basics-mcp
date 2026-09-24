package dev.ealang.phonemcp

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import dev.ealang.phonemcp.mcp.McpServer
import dev.ealang.phonemcp.mcp.apps.Locator
import dev.ealang.phonemcp.ui.PhoneMcpApp
import dev.ealang.phonemcp.webhook.PebbleWebhookServer
import dev.ealang.phonemcp.webhook.telegram.TelegramEngine
import dev.ealang.phonemcp.ui.PhoneMcpTheme

/**
 * Two tabs on a bottom bar: Webhook (the pipeline and its Telegram delivery) and
 * MCP (the tool server) — one tab per server, like one log per server.
 *
 * The activity owns the non-UI glue — config, service calls, permission
 * requests — and one piece of state the whole UI hangs off: [tick], a counter
 * bumped whenever anything on screen may have changed. Every tab reads it, so
 * a bump recomposes everything; the tabs re-read live state (McpService,
 * TelegramEngine, grants) directly in composition. Cheap reads, tiny UI —
 * simpler than plumbing each fact through its own observable.
 */
class MainActivity : ComponentActivity() {

    lateinit var config: Config
        private set

    /** Bumped on any state change; every tab subscribes by reading it. */
    val tick = mutableIntStateOf(0)

    private val handler = Handler(Looper.getMainLooper())

    fun bump() {
        tick.intValue++
    }

    /** For actions whose effect lands a beat later — a service start, a retry. */
    fun bumpSoon(delayMs: Long = 400) {
        handler.postDelayed({ bump() }, delayMs)
    }

    private val stateChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = bump()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = Config(this)
        Logs.init(filesDir)

        // First contact with the Keystore can take seconds (it generates the
        // wrapping key on the very first launch), so it goes to a worker
        // thread now; until then the UI shows placeholders for the secrets
        // and the callback repaints them.
        SecretStore.warm(this) {
            // The engine reads the db encryption key from the SecretStore, so
            // it can only come up after the warm — same reason the UI waits.
            TelegramEngine.ensureStarted(this)
            bump()
        }

        // A start that failed may have taken the process down before the logs
        // could say so, so replay the surviving reason — otherwise the only
        // symptom is a server that is silently not running.
        config.lastStartFailure?.let { why -> Logs.both { it.warn("earlier", why) } }

        setContent {
            PhoneMcpTheme {
                PhoneMcpApp(this)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(McpService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateChanged, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateChanged, filter)
        }
        TelegramEngine.onChanged = { bump() }
        bump()

        // A service started by boot or by an app update cannot hold the
        // location foreground type — that needs an app the system considers in
        // use. Being on screen is exactly that, so this is the moment it can
        // be claimed. Harmless when it isn't needed; the service treats a
        // repeat start as a no-op once the socket is up.
        if (McpService.serviceRunning && McpService.degraded) McpService.start(this)
    }

    override fun onStop() {
        TelegramEngine.onChanged = {}
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(stateChanged)
        super.onStop()
    }

    fun startServer() {
        McpService.start(this)
        // The service broadcasts when it is actually up; this is just so the
        // switch doesn't sit stale for the moment in between.
        bumpSoon()
    }

    fun saveNetwork(portText: String, bindText: String) {
        val port = portText.toIntOrNull()
        if (port == null || port !in 1024..65535) {
            toast("Port must be between 1024 and 65535")
            return
        }

        val bind = bindText.trim()
        if (bind.isNotEmpty() && !LITERAL_IPV4.matches(bind)) {
            // Only literal addresses: NanoHTTPD would otherwise resolve the
            // string, and a name that resolves off-device binds nothing.
            toast("Bind address must be a literal IPv4 address, or blank")
            return
        }

        config.port = port
        config.bindAddress = bind

        if (McpService.serviceRunning) {
            McpService.stop(this)
            handler.postDelayed({ startServer() }, 300)
        }

        // Saving an address the phone does not currently hold is allowed — the
        // tailnet address is gone whenever Tailscale is down, and refusing it
        // would make the field unsettable exactly when it needs setting.
        val present = McpService.localAddresses().any { it.substringBefore(' ') == bind }
        if (bind.isNotEmpty() && !present) {
            toast("Saved, but $bind is not on this phone right now — is Tailscale up?")
        } else {
            toast("Saved")
        }
        bump()
    }

    fun regenerateMcpToken() = tokenRegenerated { config.regenerateMcpToken() }

    fun regenerateWebhookToken() = tokenRegenerated { config.regenerateWebhookToken() }

    private fun tokenRegenerated(regenerate: () -> Unit) {
        regenerate()
        bump()
        // No restart: the servers read the token per request, so the new one
        // is live from this moment — unlike the port, which Save applies.
        toast("New token, effective now — update the client")
    }

    /**
     * The URL a client should be given, mirroring what the status text shows:
     * the bind address when one is set, else the first interface the phone has
     * (in every-interface mode any of them reaches the server), else null.
     */
    fun mcpUrl(): String? {
        val host = config.bindAddress.ifBlank {
            McpService.localAddresses().firstOrNull()?.substringBefore(' ')
        } ?: return null
        return "http://$host:${config.port}${McpServer.MCP_PATH}"
    }

    fun webhookUrl(): String =
        "http://${PebbleWebhookServer.BIND}:${config.webhookPort}${PebbleWebhookServer.PATH}"

    fun setWebhookEnabled(on: Boolean) = setServerEnabled(Config.FEATURE_WEBHOOK, on)

    fun setMcpEnabled(on: Boolean) = setServerEnabled(Config.FEATURE_MCP, on)

    /**
     * The two listener switches share one shape: save the preference, then
     * reconcile the service with it. Either switch on keeps the service (and
     * the pipeline worker) alive; a running service adjusts its sockets in
     * place; both off is the one case that stops the service itself.
     */
    private fun setServerEnabled(key: String, on: Boolean) {
        config.setFeatureEnabled(key, on)
        when {
            !config.anyServerEnabled() ->
                if (McpService.serviceRunning) McpService.stop(this)
            McpService.serviceRunning -> McpService.applyServerToggles(this)
            else -> startServer()
        }
        // The switch itself reads the preference, which changed just now — an
        // immediate bump moves it; the delayed one catches the service's
        // reaction (bind, teardown) landing a beat later.
        bump()
        bumpSoon()
    }

    fun saveWebhookPort(portText: String) {
        val port = portText.toIntOrNull()
        if (port == null || port !in 1024..65535) {
            toast("Port must be between 1024 and 65535")
            return
        }
        config.webhookPort = port
        // Same restart shape as saveNetwork: the listener binds its port at
        // service start, so a running service is bounced to pick it up.
        if (McpService.serviceRunning) {
            McpService.stop(this)
            handler.postDelayed({ startServer() }, 300)
        }
        toast("Saved")
        bump()
    }

    fun copyToClipboard(label: String, text: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(label, text))
        // Echo what actually landed on the clipboard, so a copy made with a
        // stale field or no network is caught at a glance.
        toast("Copied $text")
    }

    fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    /**
     * The one routing rule behind every permission button whose grant has
     * a system dialog (same mechanism for all of them):
     * show the dialog while it can still appear, deep-link to [settings]
     * once it cannot. "Cannot" means permanently denied, which the
     * platform reports identically to never-asked — rationale false for
     * both — so a per-permission asked-before flag supplies the missing
     * bit. Grants that live only in Settings (overlay, media access,
     * battery) have no dialog and no flag; their buttons deep-link
     * unconditionally.
     */
    private fun requestPermissionOrSettings(
        askedKey: String,
        permissions: Array<String>,
        requestCode: Int,
        settings: Intent,
    ) {
        val dialogCanShow = !config.permissionAsked(askedKey) ||
            permissions.any { shouldShowRequestPermissionRationale(it) }
        if (dialogCanShow) {
            config.setPermissionAsked(askedKey)
            requestPermissions(permissions, requestCode)
        } else {
            startActivity(settings)
        }
    }

    private fun appDetailsSettings(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$packageName"))

    fun requestLocation() {
        if (Locator.hasPermission(this)) {
            // Granted then switched off some other way — the dialog would
            // do nothing visible, so Settings is the only lever left.
            startActivity(appDetailsSettings())
        } else {
            requestPermissionOrSettings("location", Locator.PERMISSIONS, 2, appDetailsSettings())
        }
    }

    /**
     * "Allow all the time" is not grantable from a dialog: since Android 11 the
     * system refuses to prompt for it and the request returns denied without
     * showing anything. The only route is the app's own permission screen, so
     * this explains where to go and then goes there.
     *
     * It also cannot be asked for before the foreground pair is held — the
     * setting is a widening of that grant and the option is absent without it —
     * so that comes first when it is missing.
     */
    fun requestBackgroundLocation() {
        if (!Locator.hasPermission(this)) {
            toast("Grant location first, then allow it all the time.")
            requestPermissions(Locator.PERMISSIONS, 2)
            return
        }
        if (Locator.hasBackgroundPermission(this)) {
            toast("Already allowed all the time.")
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Allow location all the time")
            .setMessage(
                "Android only lets this be changed in Settings.\n\n" +
                    "On the next screen: Permissions > Location > " +
                    "\"Allow all the time\".\n\n" +
                    "Without it the phone withholds location whenever the server " +
                    "started on its own — after a reboot or an app update — and " +
                    "get_location silently returns nothing.",
            )
            .setPositiveButton("Open settings") { _, _ ->
                startActivity(appDetailsSettings())
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    /**
     * The button path, same shape as [requestLocation]: the system dialog
     * while it can still appear, the app's notification Settings screen
     * otherwise — which also covers notifications switched off there after
     * the grant, and phones older than the runtime permission. This button
     * is the only place the app asks; the launch-time auto-prompt is gone
     * because an unprompted dialog beside per-tool
     * warnings read as two systems disagreeing.
     */
    fun requestNotifications() {
        val notifSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            // No runtime permission on this Android, or held but
            // notifications switched off in Settings afterwards — either
            // way the dialog has nothing to offer.
            startActivity(notifSettings)
        } else {
            requestPermissionOrSettings(
                "notifications",
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                1,
                notifSettings,
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        bump()
    }
}

/** How much of a server log one dialog shows; the files keep more. */
const val LOG_DIALOG_LINES = 50

private val LITERAL_IPV4 =
    Regex("""^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$""")
