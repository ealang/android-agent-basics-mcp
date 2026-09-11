package dev.ealang.phonemcp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ealang.phonemcp.Config
import dev.ealang.phonemcp.MainActivity
import dev.ealang.phonemcp.McpServer
import dev.ealang.phonemcp.McpService
import dev.ealang.phonemcp.SecretStore

/**
 * The two servers and the token they share — everything a client needs to be
 * pointed at this phone, and nothing else; logs and permissions live on the
 * Status tab. Token first because both server cards refer to it; then the
 * cards in the tabs' own order, Pebble before MCP.
 */
@Composable
fun ServersTab(activity: MainActivity) {
    activity.tick.intValue // subscribe
    val config = activity.config
    val running = McpService.running

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard("Bearer token") {
            StatusText("Both servers require it, in an Authorization: Bearer header.")
            Text(
                if (SecretStore.ready) config.token else "unlocking…",
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    activity.copyToClipboard("token", config.token)
                }) { Text("Copy") }
                OutlinedButton(onClick = { activity.regenerateToken() }) { Text("Regenerate") }
            }
        }

        SectionCard("Pebble webhook server") {
            SwitchRow(
                title = "Server",
                supporting = "Runs with the MCP service; not separately switchable yet.",
                checked = running,
                enabled = false,
                onChange = {},
            )
            StatusText(
                "Receives at ${activity.webhookUrl()} — this phone only.",
                warn = !running,
            )
            OutlinedButton(onClick = {
                activity.copyToClipboard("webhook URL", activity.webhookUrl())
            }) { Text("Copy webhook URL") }

            HorizontalDivider()

            var portText by remember { mutableStateOf(config.webhookPort.toString()) }
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it },
                label = { Text("Port") },
                supportingText = {
                    Text(
                        "Saved, but not enforced yet — the listener still binds " +
                            "${McpServer.WEBHOOK_PORT}.",
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = {
                val port = portText.toIntOrNull()
                if (port == null || port !in 1024..65535) {
                    activity.toast("Port must be between 1024 and 65535")
                    return@OutlinedButton
                }
                config.webhookPort = port
                activity.toast("Saved")
            }) { Text("Save") }
        }

        SectionCard("MCP server") {
            SwitchRow(
                title = "Server",
                supporting = if (running) "Running" else "Stopped",
                checked = running,
                onChange = { on ->
                    if (on) activity.startServer() else {
                        McpService.stop(activity)
                        activity.bumpSoon()
                    }
                },
            )
            StatusText(serverStatusText(config, running), warn = !running)
            OutlinedButton(onClick = {
                val url = activity.mcpUrl()
                if (url == null) {
                    activity.toast("No address to build a URL from — is the phone on a network?")
                } else {
                    activity.copyToClipboard("MCP URL", url)
                }
            }) { Text("Copy MCP URL") }

            HorizontalDivider()

            var portText by remember { mutableStateOf(config.port.toString()) }
            var bindText by remember { mutableStateOf(config.bindAddress) }
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it },
                label = { Text("Port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = bindText,
                onValueChange = { bindText = it },
                label = { Text("Bind address (blank = every interface)") },
                placeholder = { Text("e.g. 100.x.y.z") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { activity.saveNetwork(portText, bindText) }) {
                Text("Save and restart")
            }
        }
    }
}

internal fun serverStatusText(config: Config, running: Boolean): String {
    if (!running) return "Stopped."
    val bind = config.bindAddress
    return if (bind.isNotEmpty()) {
        // Bound to one address, so the other interfaces are not reachable and
        // listing them would misrepresent the server.
        "Running, reachable only on $bind.\n\n" +
            "http://$bind:${config.port}${McpServer.MCP_PATH}"
    } else {
        val urls = McpService.localAddresses().joinToString("\n") {
            "http://${it.substringBefore(' ')}:${config.port}${McpServer.MCP_PATH}  ${it.substringAfter(' ')}"
        }
        "Running on every interface, this phone's LAN included.\n\n" +
            urls.ifEmpty { "(no network interface found)" }
    }
}
