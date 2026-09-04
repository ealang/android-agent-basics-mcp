package dev.ealang.phonemcp

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.ealang.phonemcp.telegram.TelegramEngine

/**
 * One screen: is it running, where, and with what token.
 *
 * Built in code rather than XML — the whole UI is a column of six widgets, and
 * this way the app needs no resources, no theme, and no AppCompat.
 */
class MainActivity : Activity() {

    private lateinit var config: Config
    private lateinit var status: TextView
    private lateinit var portField: EditText
    private lateinit var bindField: EditText
    private lateinit var tokenView: TextView
    private lateinit var toggle: Button
    private lateinit var overlayStatus: TextView
    private lateinit var requestsView: TextView
    private lateinit var webhookStatus: TextView
    private lateinit var failedContainer: LinearLayout
    private lateinit var pipelineLog: PipelineLog
    private lateinit var openAiField: EditText
    private lateinit var telegramStatus: TextView
    private lateinit var tgApiIdField: EditText
    private lateinit var tgApiHashField: EditText
    private lateinit var tgLoginField: EditText
    private lateinit var tgChatField: EditText

    /** Keeps the request log current while the screen is open, and only then. */
    private val ticker = object : Runnable {
        override fun run() {
            refreshRequests()
            requestsView.postDelayed(this, 2_000)
        }
    }

    private val stateChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = Config(this)

        // First contact with the Keystore can take seconds (it generates the
        // wrapping key on the very first launch), so it goes to a worker
        // thread now; until then refresh() shows placeholders for the secrets
        // and the callback repaints them.
        SecretStore.warm(this) {
            // The engine reads the db encryption key from the SecretStore, so
            // it can only come up after the warm — same reason refresh() waits.
            TelegramEngine.ensureStarted(this)
            refresh()
        }

        // A start that failed took the event log down with the process, so
        // replay the surviving reason into it — otherwise the only symptom is
        // a server that is silently not running.
        config.lastStartFailure?.let { EventLog.record("earlier: $it") }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        root.addView(header("Phone MCP"))

        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, 32)
        }
        root.addView(status)

        toggle = Button(this).apply {
            setOnClickListener {
                if (McpService.running) McpService.stop(this@MainActivity)
                else startServer()
            }
        }
        root.addView(toggle)

        root.addView(
            Button(this).apply {
                text = "Copy MCP URL"
                setOnClickListener {
                    val url = mcpUrl()
                    if (url == null) {
                        toast("No address to build a URL from — is the phone on a network?")
                    } else {
                        copyToClipboard("MCP URL", url)
                    }
                }
            },
        )

        root.addView(label("Port"))
        portField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(config.port.toString())
        }
        root.addView(portField)

        root.addView(label("Bind address (blank = every interface)"))
        bindField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "e.g. 100.x.y.z"
            setText(config.bindAddress)
        }
        root.addView(bindField)

        root.addView(
            Button(this).apply {
                text = "Save and restart"
                setOnClickListener { saveNetwork() }
            },
        )

        root.addView(label("Bearer token"))
        tokenView = TextView(this).apply {
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(tokenView)

        val tokenButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tokenButtons.addView(
            Button(this).apply {
                text = "Copy"
                setOnClickListener {
                    getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("token", config.token))
                    toast("Token copied")
                }
            },
        )
        tokenButtons.addView(
            Button(this).apply {
                text = "Regenerate"
                setOnClickListener {
                    config.regenerateToken()
                    refresh()
                    if (McpService.running) startServer()
                    toast("New token — update the client")
                }
            },
        )
        root.addView(tokenButtons)

        root.addView(label("Logs"))
        requestsView = TextView(this).apply { textSize = 13f }
        root.addView(requestsView)

        root.addView(
            Button(this).apply {
                text = "View requests"
                setOnClickListener { showLog("Requests", RequestLog.tail(LOG_DIALOG_LINES)) }
            },
        )
        root.addView(
            Button(this).apply {
                text = "View server events"
                setOnClickListener { showLog("Server events", EventLog.tail(LOG_DIALOG_LINES)) }
            },
        )

        root.addView(label("Webhook pipeline"))
        pipelineLog = PipelineLog(filesDir)
        webhookStatus = TextView(this).apply { textSize = 13f }
        root.addView(webhookStatus)
        root.addView(
            Button(this).apply {
                text = "Copy webhook URL"
                setOnClickListener { copyToClipboard("webhook URL", webhookUrl()) }
            },
        )
        failedContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(failedContainer)
        root.addView(
            Button(this).apply {
                text = "View pipeline log"
                setOnClickListener {
                    showLog(
                        "Pipeline log",
                        pipelineLog.tail(LOG_DIALOG_LINES),
                        onClear = { pipelineLog.clear() },
                    )
                }
            },
        )
        root.addView(label("OpenAI key for transcription (stored encrypted)"))
        openAiField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "sk-…"
        }
        root.addView(openAiField)
        root.addView(
            Button(this).apply {
                text = "Save key"
                setOnClickListener {
                    config.openAiKey = openAiField.text.toString()
                    openAiField.setText("")
                    // Recordings queued behind a missing key are waiting on
                    // exactly this; let the worker try again now.
                    McpService.wakePipeline()
                    toast(if (config.openAiKey != null) "Key saved" else "Key cleared")
                    refresh()
                }
            },
        )

        root.addView(label("Telegram"))
        telegramStatus = TextView(this).apply { textSize = 13f }
        root.addView(telegramStatus)

        root.addView(label("API ID and hash, from my.telegram.org"))
        tgApiIdField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "API ID"
            setText(config.telegramApiId?.toString() ?: "")
        }
        root.addView(tgApiIdField)
        tgApiHashField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "API hash"
            setText(config.telegramApiHash ?: "")
        }
        root.addView(tgApiHashField)
        root.addView(
            Button(this).apply {
                text = "Save Telegram credentials"
                setOnClickListener {
                    val id = tgApiIdField.text.toString().trim().toIntOrNull()
                    if (id == null || id <= 0) {
                        toast("API ID must be a positive number")
                        return@setOnClickListener
                    }
                    config.telegramApiId = id
                    config.telegramApiHash = tgApiHashField.text.toString()
                    if (config.telegramApiHash == null) {
                        toast("API hash is empty")
                        return@setOnClickListener
                    }
                    // Takes effect now on first save; after that TDLib holds
                    // its parameters until the process restarts.
                    TelegramEngine.ensureStarted(this@MainActivity)
                    toast("Saved")
                    refresh()
                }
            },
        )

        root.addView(label("Login (the field follows the step shown above)"))
        tgLoginField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(tgLoginField)
        root.addView(
            Button(this).apply {
                text = "Submit"
                setOnClickListener {
                    TelegramEngine.submitAuthInput(tgLoginField.text.toString())
                    tgLoginField.setText("")
                }
            },
        )

        root.addView(label("Destination chat (numeric id)"))
        tgChatField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            hint = "e.g. -1001234567890"
            setText(config.telegramChatId?.toString() ?: "")
        }
        root.addView(tgChatField)
        val chatButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chatButtons.addView(
            Button(this).apply {
                text = "Save chat"
                setOnClickListener {
                    val id = tgChatField.text.toString().trim().toLongOrNull()
                    if (id == null || id == 0L) {
                        toast("Chat id must be a non-zero number")
                        return@setOnClickListener
                    }
                    config.telegramChatId = id
                    toast("Saved")
                }
            },
        )
        chatButtons.addView(
            Button(this).apply {
                text = "Test"
                setOnClickListener {
                    val id = tgChatField.text.toString().trim().toLongOrNull()
                    if (id == null || id == 0L) {
                        toast("Enter a chat id first")
                        return@setOnClickListener
                    }
                    toast("Checking…")
                    TelegramEngine.checkChat(id) { toast(it) }
                }
            },
        )
        root.addView(chatButtons)

        root.addView(
            Button(this).apply {
                text = "Allow all files access"
                // For writing notes into a top-level folder, which scoped
                // storage cannot address. Settings-screen grant, like overlay.
                setOnClickListener {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            .setData(Uri.parse("package:$packageName")),
                    )
                }
            },
        )

        root.addView(label("Permissions"))
        overlayStatus = TextView(this).apply { textSize = 13f }
        root.addView(overlayStatus)

        root.addView(
            Button(this).apply {
                text = "Allow opening other apps"
                // Confusingly named: we draw nothing over anything. It is just
                // the one exemption that lets a background app start an activity.
                setOnClickListener {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                            .setData(Uri.parse("package:$packageName")),
                    )
                }
            },
        )

        root.addView(
            Button(this).apply {
                text = "Allow location"
                setOnClickListener {
                    if (Locator.hasPermission(this@MainActivity)) {
                        // Already granted, or granted then revoked in Settings —
                        // requestPermissions would do nothing visible either way.
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.parse("package:$packageName")),
                        )
                    } else {
                        requestPermissions(Locator.PERMISSIONS, 2)
                    }
                }
            },
        )

        root.addView(
            Button(this).apply {
                text = "Allow location all the time"
                setOnClickListener { requestBackgroundLocation() }
            },
        )

        root.addView(
            Button(this).apply {
                text = "Battery settings"
                // Without an exemption, aggressive OEM power management can kill
                // the service and with it the listening socket.
                setOnClickListener {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.parse("package:$packageName")),
                    )
                }
            },
        )

        root.addView(
            Button(this).apply {
                text = "Allow media control"
                // Holding notification access is what authorizes reading media
                // sessions (the podcast tools). We never read notifications.
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            },
        )

        setContentView(
            ScrollView(this).apply {
                // Targeting SDK 35 forces edge-to-edge, which slid the last
                // buttons under the gesture nav bar; this pads them back out.
                fitsSystemWindows = true
                addView(root, MATCH_PARENT, WRAP_CONTENT)
            },
        )

        requestNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(McpService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateChanged, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateChanged, filter)
        }
        TelegramEngine.onChanged = { refresh() }
        refresh()
        ticker.run()

        // A service started by boot or by an app update cannot hold the
        // location foreground type — that needs an app the system considers in
        // use. Being on screen is exactly that, so this is the moment it can
        // be claimed. Harmless when it isn't needed; the service treats a
        // repeat start as a no-op once the socket is up.
        if (McpService.running && McpService.degraded) McpService.start(this)
    }

    override fun onStop() {
        TelegramEngine.onChanged = {}
        requestsView.removeCallbacks(ticker)
        unregisterReceiver(stateChanged)
        super.onStop()
    }

    private fun startServer() {
        McpService.start(this)
        // The service broadcasts when it is actually up; this is just so the
        // button doesn't sit stale for the moment in between.
        status.postDelayed({ refresh() }, 400)
    }

    private fun saveNetwork() {
        val port = portField.text.toString().toIntOrNull()
        if (port == null || port !in 1024..65535) {
            toast("Port must be between 1024 and 65535")
            return
        }

        val bind = bindField.text.toString().trim()
        if (bind.isNotEmpty() && !LITERAL_IPV4.matches(bind)) {
            // Only literal addresses: NanoHTTPD would otherwise resolve the
            // string, and a name that resolves off-device binds nothing.
            toast("Bind address must be a literal IPv4 address, or blank")
            return
        }

        config.port = port
        config.bindAddress = bind

        if (McpService.running) {
            McpService.stop(this)
            status.postDelayed({ startServer() }, 300)
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
    }

    private fun refreshRequests() {
        requestsView.text =
            "Requests since app start: ${RequestLog.count()}\n" +
                "Accept failures since app start: ${EventLog.acceptFailureCount()}"
    }

    /** The full ring buffer in a dialog of its own, so it can scroll freely. */
    private fun showLog(title: String, lines: List<String>, onClear: (() -> Unit)? = null) {
        val content = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(32, 16, 32, 16)
            text = if (lines.isEmpty()) "(nothing yet)" else lines.joinToString("\n")
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Close", null)
        onClear?.let { clear ->
            dialog.setNegativeButton("Clear") { _, _ ->
                clear()
                toast("Log cleared")
            }
        }
        dialog.show()
    }

    private fun refresh() {
        val running = McpService.running
        toggle.text = if (running) "Stop server" else "Start server"
        // Secrets are only read once the Keystore warm-up is done — a read
        // before that would do the slow first contact right here on the UI
        // thread, which is the lock-up this dance exists to prevent.
        tokenView.text = if (SecretStore.ready) config.token else "unlocking…"

        val overlay = if (Clock.canStartFromBackground(this)) {
            "Can open other apps: timers, podcasts, voice sessions."
        } else {
            "\"Display over other apps\" is off, so timers, podcasts and voice " +
                "sessions will be dropped in silence. Grant it below — Android " +
                "needs it before a background app may open another one."
        }
        val location = if (!Locator.hasPermission(this)) {
            "Location is not granted, so get_location will fail."
        } else if (!Locator.hasBackgroundPermission(this)) {
            "Can report location while the app is in use. Set it to \"all the " +
                "time\" below, or the phone withholds location whenever the " +
                "server started on its own — after a reboot or an update."
        } else {
            "Can report the phone's location, including after a reboot."
        }
        val media = if (
            getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(
                    ComponentName(this, MediaListenerService::class.java),
                )
        ) {
            "Can control the podcast app."
        } else {
            "Notification access is not granted, so the podcast tools will " +
                "fail. Grant it below — it is what authorizes reading media " +
                "sessions; no notifications are read."
        }
        overlayStatus.text = "$overlay\n\n$location\n\n$media"

        refreshWebhook()
        refreshTelegram()

        status.text = if (running) {
            val bind = config.bindAddress
            if (bind.isNotEmpty()) {
                // Bound to one address, so the other interfaces are not
                // reachable and listing them would misrepresent the server.
                "Running, reachable only on $bind.\n\n" +
                    "  http://$bind:${config.port}${McpServer.MCP_PATH}"
            } else {
                val urls = McpService.localAddresses()
                    .joinToString("\n") { "  http://${it.substringBefore(' ')}:${config.port}${McpServer.MCP_PATH}  ${it.substringAfter(' ')}" }
                "Running on every interface, this phone's LAN included.\n\n" +
                    (urls.ifEmpty { "  (no network interface found)" })
            }
        } else {
            "Stopped."
        }
    }

    /** Status line from the engine, and a login hint matching its stage. */
    private fun refreshTelegram() {
        telegramStatus.text = TelegramEngine.statusLine()
        tgLoginField.hint = when (TelegramEngine.stage) {
            TelegramEngine.Stage.PHONE -> "+1…"
            TelegramEngine.Stage.CODE -> "login code"
            TelegramEngine.Stage.PASSWORD -> "two-step password"
            else -> "(no input needed right now)"
        }
        // The code and password should not echo; the phone number is fine.
        tgLoginField.inputType = when (TelegramEngine.stage) {
            TelegramEngine.Stage.PASSWORD ->
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else -> InputType.TYPE_CLASS_TEXT
        }
    }

    /**
     * Queue depth, note-permission state, and one Retry/Discard row per
     * failed item. Reads the queue directly when the service is down — safe,
     * because a stopped service means no worker is writing.
     */
    private fun refreshWebhook() {
        val (pending, failed) = McpService.pipelineSnapshot()
            ?: RecordingStore(filesDir).let { it.pending().size to it.failed() }

        val notes = if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            android.os.Environment.isExternalStorageManager()
        ) {
            "Can write notes to ${WebhookConfig.NOTES_DIR}."
        } else {
            "All files access is not granted, so note recordings will fail. " +
                "Grant it below."
        }
        val transcription = if (!SecretStore.ready) {
            "Checking for an OpenAI key…"
        } else if (config.openAiKey != null) {
            "OpenAI key is set; audio-only recordings will be transcribed."
        } else {
            "No OpenAI key, so audio-only recordings will wait in the queue " +
                "until one is saved below."
        }
        val endpoint = "Receives at ${webhookUrl()} — this phone only, " +
            "same Bearer token as the MCP server."
        webhookStatus.text =
            "Queued: $pending   Failed: ${failed.size}\n\n$endpoint\n\n$notes\n\n$transcription"

        failedContainer.removeAllViews()
        for (item in failed) {
            failedContainer.addView(
                TextView(this).apply {
                    textSize = 13f
                    setPadding(0, 16, 0, 0)
                    text = "${item.name}\n${item.failureReason() ?: "(no reason recorded)"}"
                },
            )
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(
                Button(this).apply {
                    text = "Retry"
                    setOnClickListener {
                        McpService.retryFailed(this@MainActivity, item.name)
                        webhookStatus.postDelayed({ refresh() }, 400)
                    }
                },
            )
            row.addView(
                Button(this).apply {
                    text = "Discard"
                    setOnClickListener {
                        McpService.discardFailed(this@MainActivity, item.name)
                        webhookStatus.postDelayed({ refresh() }, 400)
                    }
                },
            )
            failedContainer.addView(row)
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
    private fun requestBackgroundLocation() {
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
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$packageName")),
                )
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 24f
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, 0, 0, 32)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setPadding(0, 32, 0, 8)
    }

    /**
     * The URL a client should be given, mirroring what the status text shows:
     * the bind address when one is set, else the first interface the phone has
     * (in every-interface mode any of them reaches the server), else null.
     */
    private fun mcpUrl(): String? {
        val host = config.bindAddress.ifBlank {
            McpService.localAddresses().firstOrNull()?.substringBefore(' ')
        } ?: return null
        return "http://$host:${config.port}${McpServer.MCP_PATH}"
    }

    private fun webhookUrl(): String =
        "http://${McpServer.WEBHOOK_BIND}:${McpServer.WEBHOOK_PORT}${McpServer.WEBHOOK_PATH}"

    private fun copyToClipboard(label: String, text: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(label, text))
        // Echo what actually landed on the clipboard, so a copy made with a
        // stale field or no network is caught at a glance.
        toast("Copied $text")
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}

/** Matches the ring buffers' capacity — "everything we still have". */
private const val LOG_DIALOG_LINES = 50

private val LITERAL_IPV4 =
    Regex("""^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$""")
