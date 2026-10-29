package dev.ealang.phonemcp.ui

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
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
            Text("Permission requests", style = MaterialTheme.typography.titleMedium)
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

/**
 * Every ask below has one shape — who needs the grant, what it is, and
 * why — so the five read as one voice whether they appear here or on a
 * tool row. The button names the grant in the words the user will see
 * on the next screen.
 */
val BATTERY_GRANT = Grant(
    ask = "The server needs to be exempt from battery optimization, or power " +
        "management can stop it and close the listening socket. On the next " +
        "screen, " + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "open Battery and choose Unrestricted."
        } else {
            // Before 12 there is no Unrestricted: the exemption is a list of
            // its own, one screen further down (Battery sits under Advanced
            // on 10).
            "open Battery, then Battery optimization, find Phone MCP under " +
                "All apps and choose Don't optimize."
        },
    button = "Battery settings",
    granted = {
        it.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(it.packageName)
    },
    open = settingsFor(Settings.ACTION_APPLICATION_DETAILS_SETTINGS),
)

/** The one background-activity-start exemption; every launch rides on it. */
val OVERLAY_GRANT = Grant(
    ask = "The intent tools need \"Display over other apps\" to open another " +
        "app while Phone MCP is in the background.",
    button = "Allow opening other apps",
    granted = { Clock.canStartFromBackground(it) },
    open = settingsFor(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
)

val MEDIA_GRANT = Grant(
    ask = "The media tools need notification access, which is what exposes " +
        "other apps' media sessions. No notifications are read.",
    button = "Allow media control",
    granted = {
        it.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(
                ComponentName(it, MediaListenerService::class.java),
            )
    },
    open = { it.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
)

/** Posting to the shade — the notification tools' one grant, asked for on their row. */
val NOTIFICATIONS_GRANT = Grant(
    ask = "The notification tools need permission to post notifications.",
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
    ask = "get_location needs location access, set to \"Allow all the time\" " +
        "so it works while the server runs in the background.",
    button = "Allow location",
    granted = { Locator.hasPermission(it) && Locator.hasBackgroundPermission(it) },
    open = {
        if (Locator.hasPermission(it)) it.requestBackgroundLocation() else it.requestLocation()
    },
)

/**
 * The setup card's list: grants that serve the server itself rather than
 * any one tool. A tool's grant is asked for on its own row in the MCP
 * Tools card, and only while that row is on.
 */
val MCP_GRANTS = listOf(
    BATTERY_GRANT,
)

/**
 * The HTTP counters line the Server logs card opens with, counting since app start.
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
