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
import dev.ealang.phonemcp.McpService
import dev.ealang.phonemcp.SecretStore
import dev.ealang.phonemcp.mcp.McpServer

/**
 * Pure configuration for the two servers: the token they share, ports,
 * addresses — everything a client needs to be pointed at this phone, set
 * once and rarely revisited. Whether a server is on, and whether it is
 * actually listening, lives on the Status tab; this tab carries no status.
 */
@Composable
fun ServersTab(activity: MainActivity) {
    activity.tick.intValue // subscribe
    val config = activity.config

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatusText("The on/off switches live on the Status tab.")

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
            StatusText("Receives at ${activity.webhookUrl()} — this phone only.")
            OutlinedButton(onClick = {
                activity.copyToClipboard("webhook URL", activity.webhookUrl())
            }) { Text("Copy webhook URL") }

            HorizontalDivider()

            var portText by remember { mutableStateOf(config.webhookPort.toString()) }
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it },
                label = { Text("Port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = {
                activity.saveWebhookPort(portText)
            }) { Text("Save and apply") }
        }

        SectionCard("MCP server") {
            StatusText(mcpAddressText(config))
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
                Text("Save and apply")
            }
        }
    }
}

/**
 * Where the MCP server is (or would be) reachable, from config alone — no
 * claim about whether it is listening, which is the Status tab's business.
 */
private fun mcpAddressText(config: Config): String {
    val bind = config.bindAddress
    return if (bind.isNotEmpty()) {
        // Bound to one address, so the other interfaces are not reachable and
        // listing them would misrepresent the server.
        "Reachable only on $bind:\n\n" +
            "http://$bind:${config.port}${McpServer.MCP_PATH}"
    } else {
        val urls = McpService.localAddresses().joinToString("\n") {
            "http://${it.substringBefore(' ')}:${config.port}${McpServer.MCP_PATH}  ${it.substringAfter(' ')}"
        }
        "Bound to every interface, this phone's LAN included:\n\n" +
            urls.ifEmpty { "(no network interface found)" }
    }
}
