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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ealang.phonemcp.EventLog
import dev.ealang.phonemcp.LOG_DIALOG_LINES
import dev.ealang.phonemcp.MainActivity
import dev.ealang.phonemcp.mcp.apps.Clock
import dev.ealang.phonemcp.mcp.apps.Locator
import dev.ealang.phonemcp.MediaListenerService
import dev.ealang.phonemcp.RequestLog
import kotlinx.coroutines.delay

/**
 * How things are going: the logs, and the permission grants that decide
 * whether the tools work. Server settings live on the Servers tab. A missing
 * grant paints its status line in the error color, so a fresh install shows
 * at a glance what still needs setting up.
 */
@Composable
fun StatusTab(activity: MainActivity) {
    activity.tick.intValue // subscribe: any bump recomposes this tab

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
            val overlayOk = Clock.canStartFromBackground(activity)
            StatusText(
                if (overlayOk) {
                    "Can open other apps: timers, podcasts, voice sessions."
                } else {
                    "\"Display over other apps\" is off, so timers, podcasts and " +
                        "voice sessions will be dropped in silence. Grant it below — " +
                        "Android needs it before a background app may open another one."
                },
                warn = !overlayOk,
            )
            Button(onClick = {
                // Confusingly named: we draw nothing over anything. It is just
                // the one exemption that lets a background app start an activity.
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        .setData(Uri.parse("package:${activity.packageName}")),
                )
            }) { Text("Allow opening other apps") }

            HorizontalDivider()

            val locationOk = Locator.hasPermission(activity)
            val backgroundOk = locationOk && Locator.hasBackgroundPermission(activity)
            StatusText(
                if (!locationOk) {
                    "Location is not granted, so get_location will fail."
                } else if (!backgroundOk) {
                    "Can report location while the app is in use. Set it to \"all " +
                        "the time\", or the phone withholds location whenever the " +
                        "server started on its own — after a reboot or an update."
                } else {
                    "Can report the phone's location, including after a reboot."
                },
                warn = !backgroundOk,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { activity.requestLocation() }) { Text("Allow location") }
                OutlinedButton(onClick = { activity.requestBackgroundLocation() }) {
                    Text("All the time")
                }
            }

            HorizontalDivider()

            val mediaOk = activity.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(
                    ComponentName(activity, MediaListenerService::class.java),
                )
            StatusText(
                if (mediaOk) {
                    "Can control the podcast app."
                } else {
                    "Notification access is not granted, so the podcast tools will " +
                        "fail. Grant it below — it is what authorizes reading media " +
                        "sessions; no notifications are read."
                },
                warn = !mediaOk,
            )
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
