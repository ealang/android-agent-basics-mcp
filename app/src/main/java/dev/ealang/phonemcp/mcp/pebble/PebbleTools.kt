package dev.ealang.phonemcp.mcp.pebble

import android.content.Context
import dev.ealang.phonemcp.mcp.Preflight
import dev.ealang.phonemcp.mcp.ToolSpec
import dev.ealang.phonemcp.mcp.apps.AppTools
import dev.ealang.phonemcp.mcp.apps.Clock
import dev.ealang.phonemcp.mcp.apps.TimeOfDay
import dev.ealang.phonemcp.mcp.blocked
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime

/**
 * The tools that reach the Pebble on the user's wrist, through CoreApp. The
 * polished ones (launch, stop, set_watch_timer) follow the app-specific
 * doctrine; send_watch_message is the watch's member of the generic tier —
 * raw AppMessage tuples, the caller's judgment.
 */
object PebbleTools {

    fun specs(context: Context): List<ToolSpec> {
        val appArgument = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject().put(
                    "app",
                    JSONObject().put("type", "string")
                        .put(
                            "description",
                            "The watchapp, either by UUID or by one of these names: " +
                                Watch.KNOWN_APPS.keys.joinToString(", "),
                        ),
                ),
            )
            .put("required", JSONArray().put("app"))

        val launchWatchApp = ToolSpec(
            name = "launch_watch_app",
            featureKey = "watch_control",
            definition = JSONObject()
                .put("name", "launch_watch_app")
                .put(
                    "description",
                    "Open an app on the user's Pebble watch. The app takes over the " +
                        "screen on their wrist. It starts in its default state — this " +
                        "cannot set a duration or any other parameter, so to start a " +
                        "countdown use set_watch_timer instead.",
                )
                .put("inputSchema", appArgument),
        ) { args ->
            blocked(Preflight.watch(context))
                ?: describe(Watch.launch(context, args.optString("app")), "opened")
        }

        val stopWatchApp = ToolSpec(
            name = "stop_watch_app",
            featureKey = "watch_control",
            definition = JSONObject()
                .put("name", "stop_watch_app")
                .put(
                    "description",
                    "Close an app on the user's Pebble watch, returning it to the watchface.",
                )
                .put("inputSchema", appArgument),
        ) { args ->
            blocked(Preflight.watch(context))
                ?: describe(Watch.stop(context, args.optString("app")), "closed")
        }

        val setWatchTimer = ToolSpec(
            name = "set_watch_timer",
            featureKey = "timers",
            definition = JSONObject()
                .put("name", "set_watch_timer")
                .put(
                    "description",
                    "Start a countdown on the user's Pebble watch. The watch buzzes " +
                        "on their wrist when it finishes, and keeps counting even if " +
                        "the phone is elsewhere. Prefer set_timer when they are with " +
                        "their phone and want the usual alarm sound; use this when " +
                        "they say watch, wrist, or Pebble, or are away from the phone. " +
                        "The watch holds several timers at once, so call this again to " +
                        "add another rather than moving it to the phone. The timer app " +
                        "is brought to the front of the watch, so this interrupts " +
                        "whatever is on screen.",
                )
                .put(
                    "inputSchema",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "seconds",
                                    JSONObject().put("type", "integer")
                                        .put(
                                            "description",
                                            "How long the countdown runs, in seconds. Give " +
                                                "this or at. Between " +
                                                "${Watch.TIMER_MIN_SECONDS} and " +
                                                "${Watch.TIMER_MAX_SECONDS} (just under a day).",
                                        ),
                                )
                                .put(
                                    "at",
                                    JSONObject().put("type", "string")
                                        .put(
                                            "description",
                                            "Finish at a clock time instead: 15:30, 3pm, " +
                                                "3:30pm, noon, or midnight. Means the next " +
                                                "time that comes round.",
                                        ),
                                ),
                        ),
                ),
        ) { args ->
            blocked(Preflight.watch(context)) ?: run {
                val at = args.optString("at").trim()
                val now = ZonedDateTime.now()
                val clockTime = if (at.isEmpty()) null else TimeOfDay.resolve(at, now)
                val seconds = clockTime?.seconds
                    ?.coerceIn(Watch.TIMER_MIN_SECONDS, Watch.TIMER_MAX_SECONDS)
                    ?: args.optLong("seconds", -1)
                when {
                    at.isNotEmpty() && clockTime == null ->
                        "FAILED: \"$at\" is not a clock time. Use something like 15:30, " +
                            "3pm, or noon." to true
                    seconds <= 0 ->
                        "FAILED: give either seconds or at." to true
                    seconds < Watch.TIMER_MIN_SECONDS ->
                        "FAILED: the watch's timer app will not accept anything under " +
                            "${Watch.TIMER_MIN_SECONDS} seconds." to true
                    seconds > Watch.TIMER_MAX_SECONDS ->
                        "FAILED: the watch's timer app tops out just under 24 hours. Set " +
                            "an alarm or a calendar reminder instead." to true
                    else -> when (Watch.setTimer(context, seconds)) {
                        Watch.TimerResult.Confirmed ->
                            "The watch is now counting down ${Clock.humanize(seconds)} and " +
                                "will buzz when it finishes" + AppTools.dueAt(clockTime) + ". The " +
                                "timer app is on screen on their wrist." to false
                        Watch.TimerResult.NoAcknowledgement ->
                            "FAILED: the watch did not answer in time, so nothing is " +
                                "counting down. Ask the user to check the watch is in " +
                                "range, then try again." to true
                        Watch.TimerResult.Rejected ->
                            "FAILED: the watch received the request and refused it. Nothing " +
                                "is counting down." to true
                    }
                }
            }
        }

        val sendWatchMessage = ToolSpec(
            name = "send_watch_message",
            featureKey = "watch_control",
            definition = JSONObject()
                .put("name", "send_watch_message")
                .put(
                    "description",
                    "EXPERIMENTAL: send a raw AppMessage to any app on the user's Pebble watch — the watch equivalent of send_intent. Tuples go to the watchapp exactly as given; the watch's own ACK or NACK comes back. An ACK proves delivery to the running app, not that the keys meant anything: a key the app ignores is still acknowledged. Key meanings per app are skill-file knowledge. The watchapp must already be running or the message vanishes silently — launch_watch_app first, and any reply (ACK or NACK) proves the app is up.",
                )
                .put(
                    "inputSchema",
                    JSONObject().put("type", "object").put(
                        "properties",
                        JSONObject()
                            .put("app", JSONObject().put("type", "string").put("description", "Watchapp UUID, or a known name (\"timer\")."))
                            .put(
                                "tuples",
                                JSONObject().put("type", "array").put(
                                    "items",
                                    JSONObject().put("type", "object").put(
                                        "properties",
                                        JSONObject()
                                            .put("key", JSONObject().put("type", "integer"))
                                            .put("type", JSONObject().put("type", "string").put("description", "int, uint, string, or bytes."))
                                            .put("length", JSONObject().put("type", "integer").put("description", "Required by the wire format. Byte width 1, 2, or 4 for int/uint; string length for string; byte count for bytes."))
                                            .put("value", JSONObject().put("description", "Number for int/uint, text for string, base64 for bytes.")),
                                    ).put("required", JSONArray().put("key").put("type").put("length").put("value")),
                                ).put("description", "AppMessage tuples, passed to the watch as-is."),
                            ),
                    ).put("required", JSONArray().put("app").put("tuples")),
                ),
        ) { args ->
            blocked(Preflight.watch(context)) ?: run {
                val tuples = args.optJSONArray("tuples")
                if (tuples == null || tuples.length() == 0) {
                    "FAILED: give tuples, an array of {key, type, length, value}." to true
                } else {
                    when (val result = Watch.sendMessage(context, args.optString("app"), tuples)) {
                        Watch.SendResult.Acknowledged ->
                            "The watch acknowledged delivery. That proves the message " +
                                "reached the running watchapp — not that the keys meant " +
                                "anything to it." to false
                        Watch.SendResult.Refused ->
                            "FAILED: NACK — the watch received the message and refused it." to true
                        Watch.SendResult.NoReply ->
                            "FAILED: no reply within 5s. Usually the watchapp is not " +
                                "running — the phone only delivers to the running app and a " +
                                "send to a closed one vanishes. launch_watch_app first; any " +
                                "reply to a later send, ACK or NACK, proves it is up." to true
                        is Watch.SendResult.UnknownApp ->
                            "FAILED: \"${args.optString("app")}\" is not a UUID or a known " +
                                "app name. Known names: ${Watch.KNOWN_APPS.keys.joinToString(", ")}." to true
                        is Watch.SendResult.Malformed ->
                            "FAILED: not sent — ${result.why}. Every tuple needs " +
                                "key, type, length and value." to true
                    }
                }
            }
        }

        return listOf(launchWatchApp, stopWatchApp, setWatchTimer, sendWatchMessage)
    }

    private fun describe(result: Watch.Result, verb: String): Pair<String, Boolean> = when (result) {
        Watch.Result.Sent -> "The app is $verb on the watch." to false
        is Watch.Result.UnknownApp ->
            "FAILED: \"${result.given}\" is not a UUID and not a known app name. " +
                "Known names: ${Watch.KNOWN_APPS.keys.joinToString(", ")}. Nothing " +
                "was sent." to true
    }
}
