package io.github.ealang.agentbasicsmcp

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import io.github.ealang.agentbasicsmcp.mcp.McpServer
import io.github.ealang.agentbasicsmcp.mcp.apps.Locator
import io.github.ealang.agentbasicsmcp.mcp.files.FileFolder
import io.github.ealang.agentbasicsmcp.ui.AgentBasicsMcpApp
import io.github.ealang.agentbasicsmcp.ui.AgentBasicsMcpTheme
import io.github.ealang.agentbasicsmcp.ui.Grant
import io.github.ealang.agentbasicsmcp.ui.PageActions
import io.github.ealang.agentbasicsmcp.ui.UiState
import io.github.ealang.agentbasicsmcp.ui.readUiState

/**
 * One page: the server switch, its address and token, the tool switches and
 * the grants they need.
 *
 * The activity owns the non-UI glue — config, service calls, permission
 * requests — and the one piece of state the page is drawn from: [ui], read
 * afresh by [bump] whenever anything on screen may have changed. Compose
 * redraws only what shows a value that differs from the last read.
 */
class MainActivity : ComponentActivity(), PageActions {

    lateinit var config: Config
        private set

    /** What the page shows. Null until the first read, in onCreate. */
    private val ui = mutableStateOf<UiState?>(null)

    private val handler = Handler(Looper.getMainLooper())

    /** Something on screen may have changed: read it all again. */
    fun bump() {
        ui.value = readUiState(this)
    }

    override fun refresh() = bump()

    /** For actions whose effect lands a beat later — a service start, a retry. */
    fun bumpSoon(delayMs: Long = 400) {
        handler.postDelayed({ bump() }, delayMs)
    }

    private val stateChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = bump()
    }

    /**
     * A network coming or going — Tailscale connecting, Wi-Fi dropping — changes
     * the address list and the line under the bind field, so repaint. The
     * callbacks arrive on a binder thread; the repaint must not.
     */
    private val networksChanged = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post { bump() }
        }

        override fun onLost(network: Network) {
            handler.post { bump() }
        }

        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            handler.post { bump() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent system bars with icon contrast taken from the system's
        // dark setting, on every Android rather than only 15 and up. The
        // page's Scaffold pads the insets, so the bars sit over the theme
        // background instead of over a strip of the platform's own color.
        enableEdgeToEdge()
        config = Config(this)
        Logs.init(filesDir)
        ExitReasons.logLast(this, Logs.mcp)

        // First contact with the Keystore can take seconds (it generates the
        // wrapping key on the very first launch), so it goes to a worker
        // thread now; until then the UI shows placeholders for the secrets
        // and the callback repaints them.
        SecretStore.warm(this) { bump() }

        // A start that failed may have taken the process down before the logs
        // could say so, so replay the surviving reason — otherwise the only
        // symptom is a server that is silently not running.
        config.lastStartFailure?.let { why -> Logs.mcp.warn("earlier", why) }

        bump()

        setContent {
            AgentBasicsMcpTheme {
                ui.value?.let { AgentBasicsMcpApp(it, pickedFolder.value, this) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Not exported: the service sends this to our own package, so no
        // other app may deliver it. ContextCompat applies the flag on the
        // Androids whose registerReceiver does not take one.
        ContextCompat.registerReceiver(
            this,
            stateChanged,
            IntentFilter(McpService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        getSystemService(ConnectivityManager::class.java)
            .registerNetworkCallback(NetworkRequest.Builder().build(), networksChanged)
        bump()

        // Two states only the open app can mend, so this is the moment:
        //
        // The switch is on but the service is gone. A force stop (App info,
        // or an OEM battery manager) kills the process without touching the
        // flag, and nothing restarts it — the system drops every broadcast
        // to a force-stopped app until the user opens it. Which they just
        // did, so start it, and say so in the log, where the page points
        // when the header reads "on but not listening".
        //
        // The service is up but without the location foreground type. A
        // start from boot or an app update cannot hold it — that needs an
        // app the system considers in use. Being on screen is exactly that.
        //
        // Harmless when neither applies; the service treats a repeat start
        // as a no-op once the socket is up.
        if (config.enabled && !McpService.serviceRunning) {
            Logs.mcp.info("service not running with the switch on", "restarting")
            McpService.start(this)
        } else if (McpService.serviceRunning && McpService.degraded) {
            McpService.start(this)
        }
    }

    override fun onStop() {
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(stateChanged)
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networksChanged)
        super.onStop()
    }

    fun startServer() {
        McpService.start(this)
        // The service broadcasts when it is actually up; this is just so the
        // switch doesn't sit stale for the moment in between.
        bumpSoon()
    }

    override fun saveNetwork(portText: String, bindText: String) {
        val port = portText.toIntOrNull()
        if (port == null || port !in 1024..65535) {
            toast("Port must be between 1024 and 65535")
            return
        }

        val bind = bindText.trim().ifEmpty { Networks.ALL_IPV4 }
        if (!Networks.isLiteral(bind)) {
            // Only literal addresses: a name would be resolved, and one that
            // resolves off-device binds nothing.
            toast("Bind address must be an IP address, or 0.0.0.0 for every interface")
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
        val present = bind == Networks.ALL_IPV4 || Networks.describe(this, bind) != null
        if (!present) {
            toast("Saved, but $bind is not on this phone right now — is the VPN up?")
        } else {
            toast("Saved")
        }
        bump()
    }

    override fun regenerateToken() {
        config.regenerateToken()
        bump()
        // No restart: the server reads the token per request, so the new one
        // is live from this moment — unlike the port, which Save applies.
        toast("New token, effective now — update the client")
    }

    /**
     * The URL a client should be given, mirroring what the status text shows:
     * the bind address when one is set, else the first interface the phone has
     * (in every-interface mode any of them reaches the server), else null.
     */
    private fun mcpUrl(): String? {
        val bind = config.bindAddress.ifBlank { Networks.ALL_IPV4 }
        val host = if (bind == Networks.ALL_IPV4) {
            Networks.live(this).firstOrNull()?.address ?: return null
        } else {
            bind
        }
        return "http://${Networks.hostForUrl(host)}:${config.port}${McpServer.MCP_PATH}"
    }

    override fun copyMcpUrl() {
        val url = mcpUrl()
        if (url == null) {
            toast("No address to build a URL from — is the phone on a network?")
        } else {
            copyToClipboard("MCP URL", url)
        }
    }

    override fun copyToken() = copyToClipboard("token", config.token)

    override fun copyLog(text: String) = copyToClipboard("server log", text, echo = "Log copied")

    override fun clearLog() {
        Logs.mcp.clear()
        toast("Log cleared")
        bump()
    }

    override fun setFeatureEnabled(key: String, on: Boolean) {
        config.setFeatureEnabled(key, on)
        bump()
    }

    override fun openGrant(grant: Grant) = grant.open(this)

    /** The one switch: on starts the service, off stops it. */
    override fun setServerEnabled(on: Boolean) {
        if (on) startServer() else McpService.stop(this)
        // The switch reads the config flag, which the service sets as it
        // starts and clears as it stops — the delayed bump catches that
        // landing a beat later.
        bump()
        bumpSoon()
    }

    /**
     * [echo] is the toast. By default it repeats the text, so a copy made
     * with a stale field or no network is caught at a glance; a multi-line
     * copy passes something shorter.
     */
    fun copyToClipboard(label: String, text: String, echo: String = "Copied $text") {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(label, text))
        toast(echo)
    }

    // ------------------------------------------------------- file folders

    /** A folder back from the picker, waiting for the name agents will see. */
    private val pickedFolder = mutableStateOf<Uri?>(null)

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                // Persisted, or the grant ends with this activity and the
                // server, which outlives it, is left holding a dead URI.
                contentResolver.takePersistableUriPermission(uri, FOLDER_ACCESS)
                pickedFolder.value = uri
            }
        }

    override fun pickFileFolder() { folderPicker.launch(null) }

    override fun addFileFolder(name: String, tree: Uri) {
        config.fileFolders = config.fileFolders + FileFolder(name, tree.toString(), writable = false)
        pickedFolder.value = null
        bump()
    }

    /** The picker was answered but the folder not named: hand the grant back. */
    override fun dropPickedFolder() {
        pickedFolder.value?.let { releaseUnlessUsed(it) }
        pickedFolder.value = null
    }

    override fun setFolderWritable(folder: FileFolder, on: Boolean) {
        config.fileFolders = config.fileFolders.map {
            if (it.name == folder.name) it.copy(writable = on) else it
        }
        bump()
    }

    override fun removeFileFolder(folder: FileFolder) {
        config.fileFolders = config.fileFolders.filterNot { it.name == folder.name }
        releaseUnlessUsed(Uri.parse(folder.treeUri))
        bump()
    }

    /** One place can be added under two names; the grant goes with the last. */
    private fun releaseUnlessUsed(tree: Uri) {
        if (config.fileFolders.any { it.treeUri == tree.toString() }) return
        try {
            contentResolver.releasePersistableUriPermission(tree, FOLDER_ACCESS)
        } catch (e: SecurityException) {
            // Already gone: the user revoked it in system settings.
        }
    }

    fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    /** The system permission dialog; the page redraws on whatever it answers. */
    private val permissionDialog =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { bump() }

    /**
     * The one routing rule behind every permission button whose grant has
     * a system dialog (the same mechanism for all of them):
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
        settings: Intent,
    ) {
        val dialogCanShow = !config.permissionAsked(askedKey) ||
            permissions.any { shouldShowRequestPermissionRationale(it) }
        if (dialogCanShow) {
            config.setPermissionAsked(askedKey)
            permissionDialog.launch(permissions)
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
            // Android 10 is the one release whose dialog offers "Allow all
            // the time" — when the background permission is in the same
            // request. Asking for all three makes it one dialog there.
            val wanted = if (backgroundIsDialogGrantable) {
                Locator.PERMISSIONS + Locator.BACKGROUND_PERMISSION
            } else {
                Locator.PERMISSIONS
            }
            requestPermissionOrSettings("location", wanted, appDetailsSettings())
        }
    }

    /** Android 10 only: from 11 the system never prompts for background location. */
    private val backgroundIsDialogGrantable
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.R

    /**
     * "Allow all the time" is not grantable from a dialog: since Android 11 the
     * system refuses to prompt for it and the request returns denied without
     * showing anything. The only route is the app's own permission screen, so
     * this explains where to go and then goes there. Android 10 still prompts,
     * so there the request goes through the usual dialog-or-Settings rule.
     *
     * It also cannot be asked for before the foreground pair is held — the
     * setting is a widening of that grant and the option is absent without it —
     * so that comes first when it is missing.
     */
    fun requestBackgroundLocation() {
        if (!Locator.hasPermission(this)) {
            toast("Grant location first, then allow it all the time.")
            permissionDialog.launch(Locator.PERMISSIONS)
            return
        }
        if (Locator.hasBackgroundPermission(this)) {
            toast("Already allowed all the time.")
            return
        }
        if (backgroundIsDialogGrantable) {
            requestPermissionOrSettings(
                "background_location",
                arrayOf(Locator.BACKGROUND_PERMISSION),
                appDetailsSettings(),
            )
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Allow location all the time")
            .setMessage(
                "Android only lets this be changed in Settings.\n\n" +
                    "On the next screen: Permissions > Location > " +
                    "\"Allow all the time\".",
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
     * is the only place the app asks; there is no launch-time auto-prompt,
     * because an unprompted dialog beside per-tool warnings reads as two
     * systems disagreeing.
     */
    /**
     * Opens Android's settings page for the routine service notification's
     * channel, where it can be switched off. Linked from the app because
     * some OEM Settings apps show only the app-level toggle and no way to
     * reach a single channel.
     */
    override fun openServiceNotificationSettings() {
        McpService.ensureChannels(this)
        startActivity(
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, McpService.QUIET_CHANNEL_ID),
        )
    }

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
                notifSettings,
            )
        }
    }

    private companion object {
        const val FOLDER_ACCESS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}

/** How much of a server log one dialog shows; the files keep more. */
const val LOG_DIALOG_LINES = 50
