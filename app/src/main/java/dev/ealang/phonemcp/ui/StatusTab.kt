package dev.ealang.phonemcp.ui

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ealang.phonemcp.Clock
import dev.ealang.phonemcp.Config
import dev.ealang.phonemcp.EventLog
import dev.ealang.phonemcp.LOG_DIALOG_LINES
import dev.ealang.phonemcp.Locator
import dev.ealang.phonemcp.MainActivity
import dev.ealang.phonemcp.McpServer
import dev.ealang.phonemcp.McpService
import dev.ealang.phonemcp.MediaListenerService
import dev.ealang.phonemcp.RequestLog
import dev.ealang.phonemcp.SecretStore
import kotlinx.coroutines.delay

/**
 * Is it running, where, with what token — plus the logs and the permission
 * grants that decide whether the tools work. Everything here re-reads live
 * state on every bump of the activity's tick.
 */
@Composable
fun StatusTab(activity: MainActivity) {
    activity.tick.intValue // subscribe: any bump recomposes this tab
    val config = activity.config
    val running = McpService.running

    // Keeps the request counters current while the screen is open, and only then.
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            activity.bump()
        }
    }

    var logDialog by remember { mutableStateOf<Pair<String, List<String>>?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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
            StatusText(serverStatusText(config, running))
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

            HorizontalDivider()

            Text("Bearer token", style = MaterialTheme.typography.bodyLarge)
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

        SectionCard("Webhook server") {
            SwitchRow(
                title = "Server",
                supporting = "Runs with the MCP service; not separately switchable yet.",
                checked = running,
                enabled = false,
                onChange = {},
            )
            StatusText(
                "Receives at ${activity.webhookUrl()} — this phone only, " +
                    "same Bearer token as the MCP server.",
            )
            OutlinedButton(onClick = {
                activity.copyToClipboard("webhook URL", activity.webhookUrl())
            }) { Text("Copy webhook URL") }
        }

        SectionCard("Logs") {
            StatusText(
                "Requests since app start: ${RequestLog.count()}\n" +
                    "Accept failures since app start: ${EventLog.acceptFailureCount()}",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    logDialog = "Requests" to RequestLog.tail(LOG_DIALOG_LINES)
                }) { Text("Requests") }
                OutlinedButton(onClick = {
                    logDialog = "Server events" to EventLog.tail(LOG_DIALOG_LINES)
                }) { Text("Server events") }
            }
        }

        SectionCard("Permissions") {
            StatusText(overlayText(activity))
            Button(onClick = {
                // Confusingly named: we draw nothing over anything. It is just
                // the one exemption that lets a background app start an activity.
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        .setData(Uri.parse("package:${activity.packageName}")),
                )
            }) { Text("Allow opening other apps") }

            HorizontalDivider()

            StatusText(locationText(activity))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { activity.requestLocation() }) { Text("Allow location") }
                OutlinedButton(onClick = { activity.requestBackgroundLocation() }) {
                    Text("All the time")
                }
            }

            HorizontalDivider()

            StatusText(mediaText(activity))
            Button(onClick = {
                activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }) { Text("Allow media control") }

            HorizontalDivider()

            StatusText(
                "Without a battery exemption, aggressive OEM power management " +
                    "can kill the service and with it the listening socket.",
            )
            OutlinedButton(onClick = {
                activity.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:${activity.packageName}")),
                )
            }) { Text("Battery settings") }
        }
    }

    logDialog?.let { (title, lines) ->
        LogDialog(title = title, lines = lines, onDismiss = { logDialog = null })
    }
}

private fun serverStatusText(config: Config, running: Boolean): String {
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

private fun overlayText(activity: MainActivity): String =
    if (Clock.canStartFromBackground(activity)) {
        "Can open other apps: timers, podcasts, voice sessions."
    } else {
        "\"Display over other apps\" is off, so timers, podcasts and voice " +
            "sessions will be dropped in silence. Grant it below — Android " +
            "needs it before a background app may open another one."
    }

private fun locationText(activity: MainActivity): String =
    if (!Locator.hasPermission(activity)) {
        "Location is not granted, so get_location will fail."
    } else if (!Locator.hasBackgroundPermission(activity)) {
        "Can report location while the app is in use. Set it to \"all the " +
            "time\", or the phone withholds location whenever the server " +
            "started on its own — after a reboot or an update."
    } else {
        "Can report the phone's location, including after a reboot."
    }

private fun mediaText(activity: MainActivity): String =
    if (
        activity.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(
                ComponentName(activity, MediaListenerService::class.java),
            )
    ) {
        "Can control the podcast app."
    } else {
        "Notification access is not granted, so the podcast tools will fail. " +
            "Grant it below — it is what authorizes reading media sessions; " +
            "no notifications are read."
    }
