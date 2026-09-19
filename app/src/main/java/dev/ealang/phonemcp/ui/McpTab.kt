package dev.ealang.phonemcp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.ealang.phonemcp.Config
import dev.ealang.phonemcp.LOG_DIALOG_LINES
import dev.ealang.phonemcp.Logs
import dev.ealang.phonemcp.MainActivity
import dev.ealang.phonemcp.McpService
import dev.ealang.phonemcp.mcp.McpServer
import kotlinx.coroutines.delay

private data class Feature(val key: String, val title: String, val description: String)

// One row per switch-off-able slice of the tool surface. Only the generic
// tier is shown; the app-specific switches are hidden, not removed.
private val GENERIC = listOf(
    Feature("intents", "Intents", "send_intent, intent_scan, intent_resolve — raw Android intents, sent exactly as given."),
    Feature("content_providers", "Content providers", "Reading other apps' automation surfaces, like the recorder's file list."),
    Feature("media_control", "Media control", "The media_* family — sessions, playback, browse and search in any media app."),
    Feature("watch_control", "Pebble control", "launch_watch_app, stop_watch_app, send_watch_message — raw watchapp messaging."),
)

/**
 * The other half of the app on one page: the MCP server's switch,
 * whatever setup is missing, its logs, its settings, and which tools it
 * offers. Tool switches take effect immediately — a switched-off tool
 * disappears from tools/list and is refused if called anyway.
 */
@Composable
fun McpTab(activity: MainActivity) {
    activity.tick.intValue // subscribe
    val config = activity.config

    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            activity.bump()
        }
    }

    var logDialog by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val on = config.featureEnabled(Config.FEATURE_MCP)
        ServerHeader(
            icon = Icons.Filled.Smartphone,
            title = "Phone MCP",
            on = on,
            bound = McpService.running,
            offText = "Off — MCP clients get connection refused.",
            boundText = "Listening on " +
                "${config.bindAddress.ifBlank { "every interface" }}:${config.port}.",
            failingText = "On but not listening — see the server log.",
            onChange = { activity.setMcpEnabled(it) },
        )

        SetupCard(activity, MCP_GRANTS)

        SectionCard("Logs") {
            // "Tool calls failed", not "failed": these are tools that ran
            // and returned an error, a different thing from a request
            // rejected at the door — those are in the requests line.
            StatusText(
                requestsLine(Logs.mcp) + "\n" +
                    "Tool calls failed: ${Logs.mcp.count("failures")}",
            )
            OutlinedButton(onClick = { logDialog = true }) { Text("View server log") }
        }

        CollapsibleCard("Server settings") {
            TokenBlock(
                activity,
                token = { activity.config.mcpToken },
                regenerate = { activity.regenerateMcpToken() },
            )

            HorizontalDivider()

            StatusText(
                if (config.bindAddress.isNotEmpty()) {
                    "Reachable only on ${config.bindAddress}:\n\n" +
                        "http://${config.bindAddress}:${config.port}${McpServer.MCP_PATH}"
                } else {
                    val urls = McpService.localAddresses().joinToString("\n") {
                        "http://${it.substringBefore(' ')}:${config.port}" +
                            "${McpServer.MCP_PATH}  ${it.substringAfter(' ')}"
                    }
                    "Bound to every interface, this phone's LAN included:\n\n" +
                        urls.ifEmpty { "(no network interface found)" }
                },
            )
            OutlinedButton(onClick = {
                val url = activity.mcpUrl()
                if (url == null) {
                    activity.toast("No address to build a URL from — is the phone on a network?")
                } else {
                    activity.copyToClipboard("MCP URL", url)
                }
            }) { Text("Copy MCP URL") }

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
            OutlinedButton(onClick = { activity.saveNetwork(portText, bindText) }) {
                Text("Save and apply")
            }
        }

        CollapsibleCard("Tools") {
            GENERIC.forEachIndexed { i, feature ->
                if (i > 0) HorizontalDivider()
                SwitchRow(
                    title = feature.title,
                    supporting = feature.description,
                    checked = config.featureEnabled(feature.key),
                    onChange = {
                        config.setFeatureEnabled(feature.key, it)
                        activity.bump()
                    },
                )
            }
        }
    }

    if (logDialog) {
        LogDialog(
            title = "MCP server log",
            lines = Logs.mcp.tail(LOG_DIALOG_LINES),
            onDismiss = { logDialog = false },
            onClear = {
                Logs.mcp.clear()
                activity.toast("Log cleared")
            },
        )
    }
}
