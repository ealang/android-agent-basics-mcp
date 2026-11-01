package dev.ealang.puckmcp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import dev.ealang.puckmcp.LOG_DIALOG_LINES
import dev.ealang.puckmcp.Logs
import dev.ealang.puckmcp.MainActivity
import dev.ealang.puckmcp.McpService
import dev.ealang.puckmcp.Networks
import dev.ealang.puckmcp.mcp.McpServer
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
    Feature("intents", "Intents", "inspect, intent_resolve, send_intent — raw Android intents, sent exactly as given.", listOf(OVERLAY_GRANT)),
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
fun PuckMcpApp(activity: MainActivity) {
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
            // The page is named once, in full; the header row below is the
            // server and its switch, so it is called what it is.
            Text("Agent Basics MCP", style = MaterialTheme.typography.headlineSmall)

            ServerHeader(
                title = "MCP server",
                on = config.enabled,
                bound = McpService.running,
                offText = "Off — the server is disabled.",
                boundText = "Listening on ${listeningOn(config.bindAddress)}:${config.port}.",
                failingText = "On but not listening — see the server log.",
                onChange = { activity.setServerEnabled(it) },
            )

            // Tool grants are asked for on their rows in the MCP Tools card,
            // one visible ask per grant; this card carries the server's own
            // grants and a pointer down when a row is flagged.
            val toolNeedsGrant = GENERIC.any { f ->
                config.featureEnabled(f.key) && f.grants.any { !it.granted(activity) }
            }
            SetupCard(
                activity,
                MCP_GRANTS,
                pointers = if (toolNeedsGrant) {
                    listOf("A tool is missing a permission — see the MCP Tools card.")
                } else {
                    emptyList()
                },
            )

            SectionCard("Server logs") {
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

                val bind = config.bindAddress.ifBlank { Networks.ALL_IPV4 }
                StatusText(
                    if (bind != Networks.ALL_IPV4) {
                        "Reachable only on $bind:\n\n" +
                            "http://${Networks.hostForUrl(bind)}:${config.port}${McpServer.MCP_PATH}"
                    } else {
                        val urls = Networks.live(activity).joinToString("\n") {
                            "http://${it.address}:${config.port}${McpServer.MCP_PATH}  ${it.kind}"
                        }
                        "Bound to every IPv4 interface, this phone's LAN included:\n\n" +
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
                BindAddressField(
                    value = bindText,
                    onValueChange = { bindText = it },
                    live = { Networks.live(activity) },
                )
                OutlinedButton(onClick = { activity.saveNetwork(portText, bindText) }) {
                    Text("Save and apply")
                }

                HorizontalDivider()

                StatusText(
                    "Hide the ever-present server notification. Errors are " +
                        "reported in a separate notification.",
                )
                OutlinedButton(onClick = { activity.openServiceNotificationSettings() }) {
                    Text("Hide status notification")
                }
            }

            // The card-level icon repeats the row-level one so the signal
            // survives the card being folded shut.
            CollapsibleCard("MCP Tools", warn = toolNeedsGrant) {
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
            onCopy = { text -> activity.copyToClipboard("server log", text, echo = "Log copied") },
        )
    }
}

/** The header's spelling of the bind address: the word for the wildcard, brackets for IPv6. */
private fun listeningOn(bind: String): String =
    bind.ifBlank { Networks.ALL_IPV4 }
        .let { if (it == Networks.ALL_IPV4) "every interface" else Networks.hostForUrl(it) }
