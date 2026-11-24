package io.github.ealang.agentbasicsmcp.ui

import android.net.Uri
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
import io.github.ealang.agentbasicsmcp.Networks
import io.github.ealang.agentbasicsmcp.mcp.McpServer
import io.github.ealang.agentbasicsmcp.mcp.files.FileTools
import kotlinx.coroutines.delay

internal data class Feature(
    val key: String,
    val title: String,
    val description: String,
    /** What these tools need granted; a missing one is flagged on the row. */
    val grants: List<Grant> = emptyList(),
)

// One row per switch-off-able slice of the tool surface. Every tool the
// server offers is on one of these rows — nothing is hidden.
internal val FEATURES = listOf(
    Feature("intents", "Intents", "inspect, intent_resolve, send_intent — raw Android intents, sent exactly as given.", listOf(OVERLAY_GRANT)),
    Feature("content_providers", "Content providers", "provider_query, provider_call — reading other apps' automation surfaces and invoking the commands they expose."),
    Feature("media_control", "Media control", "The media_* family — sessions, playback, browse and search in any media app.", listOf(MEDIA_GRANT)),
    Feature("pebblekit", "PebbleKit", "The pebblekit_* and pebblekit2_* families — launching watchapps, watch state, and AppMessages to and from them."),
    Feature("notifications", "Notifications", "post_notification, dismiss_notification — text notifications in the phone's shade.", listOf(NOTIFICATIONS_GRANT)),
    Feature("location", "Location", "get_location — the phone's position.", listOf(LOCATION_GRANT)),
    Feature("find_my_phone", "Find my phone", "find_my_phone_play, find_my_phone_stop — ring the phone audibly, even on silent or Do Not Disturb."),
    Feature("volume", "Volume", "get_volume, set_volume — the phone's volume sliders: media, call, ring, notification, alarm."),
    Feature("files", "Files", "The file_* family, and upload and download of file content — in a private tmp folder and in the folders you add here.", emptyList()),
    Feature("flashlight", "Flashlight", "set_flashlight — the torch on the back of the phone."),
)

/**
 * The whole app on one page: the server's switch, whatever setup is
 * missing, its logs, its settings, and which tools it offers. Tool switches
 * take effect immediately — a switched-off tool disappears from tools/list
 * and is refused if called anyway.
 *
 * Drawn from [state] alone; [picked] is a folder back from the picker and
 * waiting for its name. Whatever the page does goes through [actions].
 */
@Composable
fun AgentBasicsMcpApp(state: UiState, picked: Uri?, actions: PageActions) {
    // Grants and counters change with no event to hear, so look again.
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            actions.refresh()
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
                on = state.enabled,
                bound = state.listening,
                offText = "Off — the server is disabled.",
                boundText = "Listening on ${listeningOn(state.bindAddress)}:${state.port}.",
                failingText = "On but not listening — see the server log.",
                onChange = { actions.setServerEnabled(it) },
            )

            // Tool grants are asked for on their rows in the MCP Tools card,
            // one visible ask per grant; this card carries the server's own
            // grants and a pointer down when a row is flagged.
            val toolNeedsGrant = FEATURES.any { f ->
                f.key in state.featuresOn && f.grants.any { it in state.missingGrants }
            }
            SetupCard(
                missing = MCP_GRANTS.filter { it in state.missingGrants },
                pointers = if (toolNeedsGrant) {
                    listOf("A tool is missing a permission — see the MCP Tools card.")
                } else {
                    emptyList()
                },
                onOpen = { actions.openGrant(it) },
            )

            SectionCard("Server logs") {
                // "Tool calls — failed", not plain "Failed": these are tools
                // that ran and returned an error, a different thing from a
                // request rejected at the door — those are in the line above.
                StatusText(
                    requestsLine(state.requests, state.rejected) + "\n" +
                        "Tool calls — failed: ${state.failures}",
                )
                OutlinedButton(onClick = { logDialog = true }) { Text("View server log") }
            }

            CollapsibleCard("Server settings") {
                TokenBlock(
                    token = state.token,
                    onCopy = { actions.copyToken() },
                    onRegenerate = { actions.regenerateToken() },
                )

                HorizontalDivider()

                val bind = state.bindAddress.ifBlank { Networks.ALL_IPV4 }
                StatusText(
                    if (bind != Networks.ALL_IPV4) {
                        "Reachable only on $bind:\n\n" +
                            "http://${Networks.hostForUrl(bind)}:${state.port}${McpServer.MCP_PATH}"
                    } else {
                        val urls = state.endpoints.joinToString("\n") {
                            "http://${it.address}:${state.port}${McpServer.MCP_PATH}  ${it.kind}"
                        }
                        "Bound to every IPv4 interface, this phone's LAN included:\n\n" +
                            urls.ifEmpty { "(no network interface found)" }
                    },
                )
                OutlinedButton(onClick = { actions.copyMcpUrl() }) { Text("Copy MCP URL") }

                var portText by remember { mutableStateOf(state.port.toString()) }
                var bindText by remember { mutableStateOf(state.bindAddress) }
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
                    endpoints = state.endpoints,
                )
                OutlinedButton(onClick = { actions.saveNetwork(portText, bindText) }) {
                    Text("Save and apply")
                }

                HorizontalDivider()

                StatusText(
                    "Hide the ever-present server notification. Errors are " +
                        "reported in a separate notification.",
                )
                OutlinedButton(onClick = { actions.openServiceNotificationSettings() }) {
                    Text("Hide status notification")
                }
            }

            // The card-level icon repeats the row-level one so the signal
            // survives the card being folded shut.
            CollapsibleCard("MCP Tools", warn = toolNeedsGrant) {
                FEATURES.forEachIndexed { i, feature ->
                    if (i > 0) HorizontalDivider()
                    val checked = feature.key in state.featuresOn
                    // A tool that is on but cannot work is flagged right here,
                    // on the row that was switched; grants for tools that are
                    // off nag nobody.
                    val missing = feature.grants.filter { it in state.missingGrants }
                    SwitchRow(
                        title = feature.title,
                        supporting = feature.description,
                        checked = checked,
                        warn = checked && missing.isNotEmpty(),
                        onChange = { actions.setFeatureEnabled(feature.key, it) },
                    )
                    if (checked && feature.key == FileTools.FEATURE) {
                        FilesBlock(state.folders, picked, actions)
                    }
                    if (checked) {
                        missing.forEach { grant ->
                            StatusText(grant.ask, warn = true)
                            OutlinedButton(onClick = { actions.openGrant(grant) }) {
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
            lines = state.logTail,
            onDismiss = { logDialog = false },
            onClear = { actions.clearLog() },
            onCopy = { text -> actions.copyLog(text) },
        )
    }
}

/** The header's spelling of the bind address: the word for the wildcard, brackets for IPv6. */
private fun listeningOn(bind: String): String =
    bind.ifBlank { Networks.ALL_IPV4 }
        .let { if (it == Networks.ALL_IPV4) "every interface" else Networks.hostForUrl(it) }
