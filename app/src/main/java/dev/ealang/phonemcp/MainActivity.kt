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
    private lateinit var tokenView: TextView
    private lateinit var toggle: Button

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

        root.addView(
            Button(this).apply {
                text = "Save port and restart"
                setOnClickListener { savePort() }
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
    }

    override fun onStop() {
        unregisterReceiver(stateChanged)
        super.onStop()
    }

    private fun startServer() {
        McpService.start(this)
        // The service broadcasts when it is actually up; this is just so the
        // button doesn't sit stale for the moment in between.
        status.postDelayed({ refresh() }, 400)
    }

    private fun savePort() {
        val value = portField.text.toString().toIntOrNull()
        if (value == null || value !in 1024..65535) {
            toast("Port must be between 1024 and 65535")
            return
        }
        config.port = value
        if (McpService.running) {
            McpService.stop(this)
            status.postDelayed({ startServer() }, 300)
        }
        toast("Port set to $value")
    }

    private fun refresh() {
        val running = McpService.running
        toggle.text = if (running) "Stop server" else "Start server"
        tokenView.text = config.token

        status.text = if (running) {
            val urls = McpService.localAddresses()
                .joinToString("\n") { "  http://${it.substringBefore(' ')}:${config.port}${McpServer.MCP_PATH}  ${it.substringAfter(' ')}" }
            "Running.\n\n" + (urls.ifEmpty { "  (no network interface found)" })
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
