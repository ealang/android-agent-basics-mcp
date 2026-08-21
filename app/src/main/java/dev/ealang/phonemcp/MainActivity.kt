package dev.ealang.phonemcp

import android.Manifest
import android.app.Activity
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
                    val clipboard = getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("token", config.token))
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

        root.addView(label("Permissions"))
        overlayStatus = TextView(this).apply { textSize = 13f }
        root.addView(overlayStatus)

        root.addView(
            Button(this).apply {
                text = "Allow starting the clock app"
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

        setContentView(ScrollView(this).apply { addView(root, MATCH_PARENT, WRAP_CONTENT) })

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
        refresh()
        ticker.run()
    }

    override fun onStop() {
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
    private fun showLog(title: String, lines: List<String>) {
        val content = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(32, 16, 32, 16)
            text = if (lines.isEmpty()) "(nothing yet)" else lines.joinToString("\n")
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Close", null)
            .show()
    }

    private fun refresh() {
        val running = McpService.running
        toggle.text = if (running) "Stop server" else "Start server"
        tokenView.text = config.token

        val overlay = if (Clock.canStartFromBackground(this)) {
            "Can hand timers to the clock app."
        } else {
            "\"Display over other apps\" is off, so timer requests will be " +
                "dropped. Grant it below — Android needs it before a background " +
                "app may open the clock."
        }
        val location = if (Locator.hasPermission(this)) {
            "Can report the phone's location."
        } else {
            "Location is not granted, so get_location will fail."
        }
        overlayStatus.text = "$overlay\n\n$location"

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

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}

/** Matches the ring buffers' capacity — "everything we still have". */
private const val LOG_DIALOG_LINES = 50

private val LITERAL_IPV4 =
    Regex("""^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$""")
