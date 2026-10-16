package dev.ealang.phonemcp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import dev.ealang.phonemcp.LOG_DIALOG_LINES
import dev.ealang.phonemcp.Logs
import dev.ealang.phonemcp.MainActivity
import dev.ealang.phonemcp.McpService
import dev.ealang.phonemcp.mcp.McpServer
import kotlinx.coroutines.delay

private data class Feature(
    val key: String,
    val title: String,
    val description: String,
    /** What these tools need granted; a missing one is flagged on the row. */
    val grants: List<Grant> = emptyList(),
)

// One row per switch-off-able slice of the tool surface. Every tool the
// server offers is on one of these rows — nothing is hidden.
private val GENERIC = listOf(
    Feature("intents", "Intents", "send_intent, intent_scan, intent_resolve — raw Android intents, sent exactly as given.", listOf(OVERLAY_GRANT)),
    Feature("content_providers", "Content providers", "provider_query, provider_call — reading other apps' automation surfaces and invoking the commands they expose."),
    Feature("media_control", "Media control", "The media_* family — sessions, playback, browse and search in any media app.", listOf(MEDIA_GRANT)),
    Feature("watch_control", "Pebble control", "The pebblekit_* family — launching watchapps and raw AppMessage delivery."),
    Feature("notifications", "Notifications", "post_notification, dismiss_notification — text notifications in the phone's shade.", listOf(NOTIFICATIONS_GRANT)),
    Feature("location", "Location", "get_location — the phone's position.", listOf(LOCATION_GRANT)),
    Feature("find_my_phone", "Find my phone", "find_my_phone_play, find_my_phone_stop — ring the phone audibly, even on silent or Do Not Disturb."),
    Feature("volume", "Volume", "get_volume, set_volume — the phone's volume sliders: media, call, ring, notification, alarm."),
    Feature("flashlight", "Flashlight", "set_flashlight — the torch on the back of the phone."),
)

/**
 * The whole app on one page: the server's switch, whatever setup is
 * missing, its logs, its settings, and which tools it offers. Tool switches
 * take effect immediately — a switched-off tool disappears from tools/list
 * and is refused if called anyway. The activity is passed down because it
 * owns the non-UI glue (config, service calls, permission requests) and the
 * refresh tick every card subscribes to.
 */
@Composable
fun PhoneMcpApp(activity: MainActivity) {
    activity.tick.intValue // subscribe
    val config = activity.config

    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            activity.bump()
        }
    }

    var logDialog by remember { mutableStateOf(false) }

    // The Scaffold is here for what it does with no bars at all: it paints
    // the theme's background and hands back the system-bar insets, so the
    // page is not drawn under the status bar.
    Scaffold { insets ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ServerHeader(
                title = "Phone MCP",
                on = config.enabled,
                bound = McpService.running,
                offText = "Off — MCP clients get connection refused.",
                boundText = "Listening on " +
                    "${config.bindAddress.ifBlank { "every interface" }}:${config.port}.",
                failingText = "On but not listening — see the server log.",
                onChange = { activity.setServerEnabled(it) },
            )

            // A grant already flagged on an enabled tool row below is not
            // repeated up here — one visible ask per grant, and the row's is
            // the more precisely placed; the card carries a pointer at the
            // Tools card instead. A grant reappears here in full if its tool
            // is switched off, because the hidden app-specific tools may
            // still want it.
            val onRows = GENERIC
                .filter { config.featureEnabled(it.key) }
                .flatMap { it.grants }
            val toolNeedsGrant = GENERIC.any { f ->
                config.featureEnabled(f.key) && f.grants.any { !it.granted(activity) }
            }
            SetupCard(
                activity,
                MCP_GRANTS.filterNot { it in onRows },
                pointers = if (toolNeedsGrant) {
                    listOf("A tool is missing a permission — see the Tools card.")
                } else {
                    emptyList()
                },
            )

            SectionCard("Logs") {
                // "Tool calls — failed", not plain "Failed": these are tools
                // that ran and returned an error, a different thing from a
                // request rejected at the door — those are in the line above.
                StatusText(
                    requestsLine(Logs.mcp) + "\n" +
                        "Tool calls — failed: ${Logs.mcp.count("failures")}",
                )
                OutlinedButton(onClick = { logDialog = true }) { Text("View server log") }
            }

            CollapsibleCard("Server settings") {
                TokenBlock(
                    activity,
                    token = { activity.config.token },
                    regenerate = { activity.regenerateToken() },
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

                HorizontalDivider()

                StatusText(
                    "The persistent \"Phone MCP\" notification can be switched off. " +
                        "If the server stays unable to listen, a separate " +
                        "\"Server trouble\" notification still appears.",
                )
                OutlinedButton(onClick = { activity.openServiceNotificationSettings() }) {
                    Text("Hide status notification")
                }
            }

            // The card-level icon repeats the row-level one so the signal
            // survives the card being folded shut.
            CollapsibleCard("Tools", warn = toolNeedsGrant) {
                GENERIC.forEachIndexed { i, feature ->
                    if (i > 0) HorizontalDivider()
                    val checked = config.featureEnabled(feature.key)
                    // A tool that is on but cannot work is flagged right here,
                    // on the row that was switched; grants for tools that are
                    // off nag nobody.
                    val missing = feature.grants.filterNot { it.granted(activity) }
                    SwitchRow(
                        title = feature.title,
                        supporting = feature.description,
                        checked = checked,
                        warn = checked && missing.isNotEmpty(),
                        onChange = {
                            config.setFeatureEnabled(feature.key, it)
                            activity.bump()
                        },
                    )
                    if (checked) {
                        missing.forEach { grant ->
                            StatusText(grant.ask, warn = true)
                            OutlinedButton(onClick = { grant.open(activity) }) {
                                Text(grant.button)
                            }
                        }
                    }
                }
            }
        }
    }

    if (logDialog) {
        LogDialog(
            title = "Server log",
            lines = Logs.mcp.tail(LOG_DIALOG_LINES),
            onDismiss = { logDialog = false },
            onClear = {
                Logs.mcp.clear()
                activity.toast("Log cleared")
            },
        )
    }
}
