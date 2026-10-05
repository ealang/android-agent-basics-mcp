package dev.ealang.phonemcp.ui

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding
import dev.ealang.phonemcp.MainActivity
import dev.ealang.phonemcp.MediaListenerService
import dev.ealang.phonemcp.SecretStore
import dev.ealang.phonemcp.ServerLog
import dev.ealang.phonemcp.mcp.apps.Clock
import dev.ealang.phonemcp.mcp.apps.Locator

/**
 * The page's larger pieces: the server's own control at the top, the
 * missing-setup card that appears directly under it, the token block in the
 * settings card, and the grants the cards ask for.
 */

/**
 * The page's headline control: the server, its state light, and its switch.
 * Not in a card — it is the subject of the page, not one item on it.
 */
@Composable
fun ServerHeader(
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
        Box(
            Modifier.size(16.dp).background(
                when {
                    !on -> MaterialTheme.colorScheme.outline
                    bound -> okGreen
                    else -> MaterialTheme.colorScheme.error
                },
                CircleShape,
            ),
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
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

/**
 * Whatever still needs granting, and nothing when nothing does — the card
 * disappears entirely rather than reporting success.
 *
 * [pointers] are one-line referrals for problems whose ask-and-button
 * live on another card lower on the page (a tool row's grant). The top
 * card still announces them — it is the page's
 * one "something needs you" summary — but it points
 * instead of duplicating, so each fix has exactly one button.
 */
@Composable
fun SetupCard(
    activity: MainActivity,
    needs: List<Grant>,
    pointers: List<String> = emptyList(),
) {
    val missing = needs.filterNot { it.granted(activity) }
    if (missing.isEmpty() && pointers.isEmpty()) return

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Setup needed", style = MaterialTheme.typography.titleMedium)
            missing.forEachIndexed { i, grant ->
                if (i > 0) HorizontalDivider()
                Text(grant.ask, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { grant.open(activity) }) { Text(grant.button) }
            }
            pointers.forEachIndexed { i, line ->
                if (i > 0 || missing.isNotEmpty()) HorizontalDivider()
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** One permission the page cares about, and how to ask for it. */
class Grant(
    val ask: String,
    val button: String,
    val granted: (MainActivity) -> Boolean,
    val open: (MainActivity) -> Unit,
)

private fun settingsFor(action: String): (MainActivity) -> Unit = { activity ->
    activity.startActivity(
        Intent(action).setData(Uri.parse("package:${activity.packageName}")),
    )
}

val BATTERY_GRANT = Grant(
    ask = "Exempt the app from battery optimization, or aggressive power " +
        "management can kill the service and with it the listening socket.",
    button = "Battery settings",
    granted = {
        it.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(it.packageName)
    },
    open = settingsFor(Settings.ACTION_APPLICATION_DETAILS_SETTINGS),
)

/** The one background-activity-start exemption; every launch rides on it. */
val OVERLAY_GRANT = Grant(
    ask = "Grant \"Display over other apps\" so tools can open other apps " +
        "from the background.",
    button = "Allow opening other apps",
    granted = { Clock.canStartFromBackground(it) },
    open = settingsFor(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
)

val MEDIA_GRANT = Grant(
    ask = "Grant notification access so the media tools can control " +
        "playback. It authorizes reading media sessions; no notifications " +
        "are read.",
    button = "Allow media control",
    granted = {
        it.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(
                ComponentName(it, MediaListenerService::class.java),
            )
    },
    open = { it.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
)

/**
 * Posting to the shade — the notification tools' one grant. Not in
 * [MCP_GRANTS]: it surfaces on the Notifications tool row instead, the
 * first of the per-tool warnings.
 */
val NOTIFICATIONS_GRANT = Grant(
    ask = "Notification permission missing.",
    button = "Allow notifications",
    granted = {
        it.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    },
    open = { it.requestNotifications() },
)

/**
 * Location, all the time: the "all the time" half matters because a server
 * started by boot or app update is background from the system's point of
 * view, and without it get_location silently returns nothing.
 */
val LOCATION_GRANT = Grant(
    ask = "Location permission missing (needs \"allow all the time\").",
    button = "Allow location",
    granted = { Locator.hasPermission(it) && Locator.hasBackgroundPermission(it) },
    open = {
        if (Locator.hasPermission(it)) it.requestBackgroundLocation() else it.requestLocation()
    },
)

/**
 * The setup card's list: the grants that serve the surface as a
 * whole (launching rides on overlay, the media_* family on media, the
 * service on battery) rather than any one tool's row.
 */
val MCP_GRANTS = listOf(
    OVERLAY_GRANT,
    MEDIA_GRANT,
    BATTERY_GRANT,
)

/**
 * The HTTP counters line the Logs card opens with, counting since app start.
 * Accepted and rejected are kept apart because an auth rejection still
 * counts as a request — folding them together made a 401 look like a
 * message that vanished. Every counter always shows, zeros included:
 * hiding-until-nonzero was tried (2026-09-18) and a fixed shape whose
 * numbers move won readability over lines that reword themselves.
 */
fun requestsLine(log: ServerLog): String {
    val rejected = log.count("rejected")
    val accepted = log.count("requests") - rejected
    return "Server requests — accepted: $accepted   rejected: $rejected"
}

/** The bearer token, shown inside the settings card. */
@Composable
fun TokenBlock(activity: MainActivity, token: () -> String, regenerate: () -> Unit) {
    StatusText("Clients send it in an Authorization: Bearer header.")
    Text(
        if (SecretStore.ready) token() else "unlocking…",
        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            activity.copyToClipboard("token", token())
        }) { Text("Copy") }
        OutlinedButton(onClick = regenerate) { Text("Regenerate") }
    }
}
