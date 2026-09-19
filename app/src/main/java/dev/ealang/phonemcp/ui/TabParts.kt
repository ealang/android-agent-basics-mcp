package dev.ealang.phonemcp.ui

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
 * Shared pieces of the two-tab layout: the server's own control at
 * the top of its page, the missing-setup card that appears directly under
 * it, and the token block each server settings card carries.
 */

/**
 * The page's headline control: the server this tab is about, its state
 * light, and its switch. Not in a card — it is the subject of the page,
 * not one item on it.
 */
@Composable
fun ServerHeader(
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
            modifier = Modifier.size(40.dp),
            tint = when {
                !on -> MaterialTheme.colorScheme.outline
                bound -> Color(0xFF2E7D32)
                else -> MaterialTheme.colorScheme.error
            },
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
 * disappears entirely rather than reporting success. Each page asks only
 * for the grants its own half of the app needs.
 */
@Composable
fun SetupCard(activity: MainActivity, needs: List<Grant>) {
    val missing = needs.filterNot { it.granted(activity) }
    if (missing.isEmpty()) return

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

val MCP_GRANTS = listOf(
    Grant(
        ask = "Grant \"Display over other apps\" so timers, podcasts and voice " +
            "sessions can open their apps from the background.",
        button = "Allow opening other apps",
        granted = { Clock.canStartFromBackground(it) },
        open = settingsFor(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
    ),
    Grant(
        ask = "Grant location, all the time, so get_location answers even when " +
            "the server started on its own after a reboot.",
        button = "Allow location",
        granted = { Locator.hasPermission(it) && Locator.hasBackgroundPermission(it) },
        open = {
            if (Locator.hasPermission(it)) it.requestBackgroundLocation() else it.requestLocation()
        },
    ),
    Grant(
        ask = "Grant notification access so the podcast tools can control " +
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
    ),
    BATTERY_GRANT,
)

/**
 * The HTTP counters line both tabs open with. Accepted and rejected are kept
 * apart because an auth rejection still counts as a request — folding them
 * together made a 401 look like a message that vanished. Rejected appears
 * only once it has happened; zero would just be noise.
 */
fun requestsLine(log: ServerLog): String {
    val rejected = log.count("rejected")
    val accepted = log.count("requests") - rejected
    return "Requests since app start: $accepted accepted" +
        (if (rejected > 0) ", $rejected rejected" else "")
}

/**
 * A server's own bearer token, shown inside its settings card. Each server
 * has its own (split 2026-09-18): which one this block shows and regenerates
 * is the caller's choice, and regenerating one leaves the other alone.
 */
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
