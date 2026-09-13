package dev.ealang.phonemcp.ui

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.ealang.phonemcp.Config
import dev.ealang.phonemcp.EventLog
import dev.ealang.phonemcp.LOG_DIALOG_LINES
import dev.ealang.phonemcp.MainActivity
import dev.ealang.phonemcp.McpService
import dev.ealang.phonemcp.MediaListenerService
import dev.ealang.phonemcp.RequestLog
import dev.ealang.phonemcp.mcp.apps.Clock
import dev.ealang.phonemcp.mcp.apps.Locator
import dev.ealang.phonemcp.webhook.RecordingStore
import kotlinx.coroutines.delay

/**
 * The front page: everything that answers "is it working?" in one place.
 * The two servers with their switches and a colored state icon, the
 * pipeline's queue, the logs, and whatever setup is still missing. The
 * config tabs carry no status; this tab carries no config.
 */
@Composable
fun StatusTab(activity: MainActivity) {
    activity.tick.intValue // subscribe: any bump recomposes this tab
    val config = activity.config

    // Keeps the counters and queue current while the screen is open, and only then.
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            activity.bump()
        }
    }

    var logDialog by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    var pipelineDialog by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard("Servers") {
            ServerRow(
                icon = Icons.Filled.Watch,
                title = "Pebble webhook",
                on = config.featureEnabled(Config.FEATURE_WEBHOOK),
                bound = McpService.webhookRunning,
                offText = "Off — the watch gets connection refused and retries later.",
                boundText = "Listening — this phone only.",
                failingText = "Switched on but not listening — see server events.",
                onChange = { activity.setWebhookEnabled(it) },
            )

            HorizontalDivider()

            ServerRow(
                icon = Icons.Filled.Smartphone,
                title = "Phone MCP",
                on = config.featureEnabled(Config.FEATURE_MCP),
                bound = McpService.running,
                offText = "Off — MCP clients get connection refused.",
                boundText = "Listening on " +
                    "${config.bindAddress.ifBlank { "every interface" }}:${config.port}.",
                failingText = "Switched on but not listening — see server events.",
                onChange = { activity.setMcpEnabled(it) },
            )
        }

        SectionCard("Pipeline") {
            // Reads the queue directly when the service is down — safe,
            // because a stopped service means no worker is writing.
            val (pending, failed) = McpService.pipelineSnapshot()
                ?: RecordingStore(activity.filesDir).let { it.pending().size to it.failed() }

            StatusText("Queued: $pending   Failed: ${failed.size}")

            for (item in failed) {
                HorizontalDivider()
                Text(item.name, style = MaterialTheme.typography.bodyMedium)
                StatusText(item.failureReason() ?: "(no reason recorded)")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        McpService.retryFailed(activity, item.name)
                        activity.bumpSoon()
                    }) { Text("Retry") }
                    OutlinedButton(onClick = {
                        McpService.discardFailed(activity, item.name)
                        activity.bumpSoon()
                    }) { Text("Discard") }
                }
            }

            OutlinedButton(onClick = { pipelineDialog = true }) { Text("View pipeline log") }
        }

        SectionCard("Logs") {
            StatusText(
                "Requests since app start: ${RequestLog.count()}\n" +
                    "Accept failures since app start: ${EventLog.acceptFailureCount()}",
            )
            // Events is what a person checks; the raw request log is plumbing.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    logDialog = "Events" to EventLog.tail(LOG_DIALOG_LINES)
                }) { Text("Events") }
                OutlinedButton(onClick = {
                    logDialog = "Server log" to RequestLog.tail(LOG_DIALOG_LINES)
                }) { Text("Server log") }
            }
        }

        SectionCard("Setup") {
            // A granted permission has nothing to say; only what still needs
            // doing appears, phrased as a to-do rather than an error.
            val overlayOk = Clock.canStartFromBackground(activity)
            val locationOk = Locator.hasPermission(activity)
            val backgroundOk = locationOk && Locator.hasBackgroundPermission(activity)
            val mediaOk = activity.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(
                    ComponentName(activity, MediaListenerService::class.java),
                )
            val filesOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                Environment.isExternalStorageManager()
            val batteryOk = activity.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(activity.packageName)

            val missing = buildList<@Composable () -> Unit> {
                if (!overlayOk) add {
                    StatusText(
                        "Grant \"Display over other apps\" so timers, podcasts and " +
                            "voice sessions can open their apps from the background. " +
                            "Confusingly named — nothing is drawn over anything; it is " +
                            "the one exemption that lets a background app start another.",
                    )
                    OutlinedButton(onClick = {
                        activity.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                .setData(Uri.parse("package:${activity.packageName}")),
                        )
                    }) { Text("Allow opening other apps") }
                }
                if (!locationOk) add {
                    StatusText("Grant location so get_location can answer.")
                    OutlinedButton(onClick = { activity.requestLocation() }) {
                        Text("Allow location")
                    }
                } else if (!backgroundOk) add {
                    StatusText(
                        "Set location to \"all the time\", or the phone withholds it " +
                            "whenever the server started on its own — after a reboot " +
                            "or an update.",
                    )
                    OutlinedButton(onClick = { activity.requestBackgroundLocation() }) {
                        Text("All the time")
                    }
                }
                if (!mediaOk) add {
                    StatusText(
                        "Grant notification access so the podcast tools can control " +
                            "playback. It is what authorizes reading media sessions; " +
                            "no notifications are read.",
                    )
                    OutlinedButton(onClick = {
                        activity.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                        )
                    }) { Text("Allow media control") }
                }
                if (!filesOk) add {
                    StatusText(
                        "Grant all files access so Markdown notes can be written " +
                            "into a top-level folder scoped storage cannot address.",
                    )
                    OutlinedButton(onClick = {
                        activity.startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                .setData(Uri.parse("package:${activity.packageName}")),
                        )
                    }) { Text("Allow all files access") }
                }
                if (!batteryOk) add {
                    StatusText(
                        "Exempt the app from battery optimization, or aggressive " +
                            "power management can kill the service and with it the " +
                            "listening socket.",
                    )
                    OutlinedButton(onClick = {
                        activity.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.parse("package:${activity.packageName}")),
                        )
                    }) { Text("Battery settings") }
                }
            }

            if (missing.isEmpty()) {
                StatusText("Nothing to do — every permission the tools need is granted.")
            } else {
                missing.forEachIndexed { index, block ->
                    if (index > 0) HorizontalDivider()
                    block()
                }
            }
        }
    }

    logDialog?.let { (title, lines) ->
        LogDialog(title = title, lines = lines, onDismiss = { logDialog = null })
    }
    if (pipelineDialog) {
        LogDialog(
            title = "Pipeline log",
            lines = activity.pipelineLog.tail(LOG_DIALOG_LINES),
            onDismiss = { pipelineDialog = false },
            onClear = {
                activity.pipelineLog.clear()
                activity.toast("Log cleared")
            },
        )
    }
}

/**
 * One server on the Status tab: its icon doubling as the state light —
 * green (listening), red (wanted but not listening), gray (switched off) —
 * plus the on/off switch, which lives here rather than on the config tab
 * because whether a server runs is status, not configuration.
 */
@Composable
private fun ServerRow(
    icon: ImageVector,
    title: String,
    on: Boolean,
    bound: Boolean,
    offText: String,
    boundText: String,
    failingText: String,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = when {
                !on -> MaterialTheme.colorScheme.outline
                bound -> Color(0xFF2E7D32) // green: on and listening
                else -> MaterialTheme.colorScheme.error // red: on but not listening
            },
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                when {
                    !on -> offText
                    bound -> boundText
                    else -> failingText
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}
