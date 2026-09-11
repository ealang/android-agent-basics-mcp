package dev.ealang.phonemcp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ealang.phonemcp.MainActivity

private data class Feature(val key: String, val title: String, val description: String)

// One row per switch-off-able slice of the tool surface, split the way the
// server is: the generic tier and the app-specific tier. Descriptions name
// what the caller loses, not how it works.
private val GENERIC = listOf(
    Feature("intents", "Intents", "send_intent, intent_scan, intent_resolve — raw Android intents, sent exactly as given."),
    Feature("content_providers", "Content providers", "Reading other apps' automation surfaces, like the recorder's file list."),
    Feature("media_control", "Media control", "The media_* family — sessions, playback, browse and search in any media app."),
    Feature("watch_control", "Pebble control", "launch_watch_app, stop_watch_app, send_watch_message — raw watchapp messaging."),
)

private val APP_SPECIFIC = listOf(
    Feature("maps", "Google Maps", "navigate — turn-by-turn to a destination."),
    Feature("timers", "Timers and alarms", "set_timer and set_watch_timer — the clock app and the watch."),
    Feature("pocket_casts", "Pocket Casts", "play_podcast and podcast_now_playing."),
    Feature("voice_gateway", "Agent voice", "Starting and ending voice sessions."),
    Feature("voice_recorder", "Voice recorder", "The recorder's file tools."),
)

/**
 * What the MCP server offers, as switches. Live: tools/list hides a switched-
 * off tool and dispatch refuses it by name, per request — no restart. The
 * three phone-capability tools (get_location, find_my_phone, silence_phone)
 * have no switch on purpose; they have a null featureKey in the registry.
 */
@Composable
fun McpTab(activity: MainActivity) {
    activity.tick.intValue // subscribe
    val config = activity.config

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatusText(
            "Which tools the MCP server offers. Takes effect immediately: a " +
                "switched-off tool disappears from tools/list and is refused " +
                "if called anyway.",
        )

        SectionCard("Generic") {
            GENERIC.forEachIndexed { i, feature ->
                if (i > 0) HorizontalDivider()
                FeatureRow(activity, feature)
            }
        }

        SectionCard("App-specific") {
            APP_SPECIFIC.forEachIndexed { i, feature ->
                if (i > 0) HorizontalDivider()
                FeatureRow(activity, feature)
            }
        }
    }
}

@Composable
private fun FeatureRow(activity: MainActivity, feature: Feature) {
    SwitchRow(
        title = feature.title,
        supporting = feature.description,
        checked = activity.config.featureEnabled(feature.key),
        onChange = {
            activity.config.setFeatureEnabled(feature.key, it)
            activity.bump()
        },
    )
}
