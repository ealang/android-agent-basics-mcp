package dev.ealang.phonemcp.mcp.pebble

import android.content.Context
import dev.ealang.phonemcp.mcp.Preflight
import dev.ealang.phonemcp.mcp.ToolSpec
import dev.ealang.phonemcp.mcp.blocked
import org.json.JSONArray
import org.json.JSONObject

/**
 * The tools that reach the Pebble on the user's wrist, through CoreApp. All
 * four ride the classic PebbleKit intent surface — hence the prefix, which
 * leaves room for a `pebblekit2_` family if the PebbleKit 2 channel is ever
 * built. The watch is a platform, not an app: these are generic over
 * *watchapps* the way `send_intent` is generic over apps, and what any
 * particular watchapp's keys mean is skill-file knowledge.
 */
object PebbleTools {

    fun specs(context: Context): List<ToolSpec> {
        val appProperty = JSONObject().put("type", "string")
            .put("description", "The watchapp's UUID.")

        val appArgument = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("app", appProperty))
            .put("required", JSONArray().put("app"))

        fun tuplesProperty(description: String) = JSONObject().put("type", "array").put(
            "items",
            JSONObject().put("type", "object").put(
                "properties",
                JSONObject()
                    .put("key", JSONObject().put("type", "integer"))
                    .put("type", JSONObject().put("type", "string").put("enum", JSONArray().put("int").put("uint").put("string").put("bytes")))
                    .put("length", JSONObject().put("type", "integer").put("description", "Required by the wire format. Byte width 1, 2, or 4 for int/uint; string length for string; byte count for bytes."))
                    .put("value", JSONObject().put("description", "Number for int/uint, text for string, base64 for bytes.")),
            ).put("required", JSONArray().put("key").put("type").put("length").put("value")),
        ).put("description", description)

        val payloadProperty = tuplesProperty(
            "The AppMessage, as tuples, passed to the watch as-is. May be empty.",
        )

        val launchApp = ToolSpec(
            name = "pebblekit_launch_app",
            featureKey = "watch_control",
            definition = JSONObject()
                .put("name", "pebblekit_launch_app")
                .put(
                    "description",
                    "Open an app on the user's Pebble watch. The app starts in its " +
                        "default state; no parameters can be passed.",
                )
                .put("inputSchema", appArgument),
        ) { args ->
            blocked(Preflight.watch(context))
                ?: describe(Watch.launch(context, args.optString("app")), "opened")
        }

        val stopApp = ToolSpec(
            name = "pebblekit_stop_app",
            featureKey = "watch_control",
            definition = JSONObject()
                .put("name", "pebblekit_stop_app")
                .put(
                    "description",
                    "Close an app on the user's Pebble watch, returning it to the watchface.",
                )
                .put("inputSchema", appArgument),
        ) { args ->
            blocked(Preflight.watch(context))
                ?: describe(Watch.stop(context, args.optString("app")), "closed")
        }

        val sendMessage = ToolSpec(
            name = "pebblekit_send_message",
            featureKey = "watch_control",
            definition = JSONObject()
                .put("name", "pebblekit_send_message")
                .put(
                    "description",
                    "Send a raw AppMessage to an app on the user's Pebble watch — the watch equivalent of send_intent. Tuples go to the watchapp exactly as given; the watch's own ACK or NACK comes back. An ACK proves delivery to the running app, not that the keys meant anything: a key the app ignores is still acknowledged. Key meanings per app are skill-file knowledge. The watchapp must already be running or the message vanishes silently — use pebblekit_launch_and_send when it may not be.",
                )
                .put(
                    "inputSchema",
                    JSONObject().put("type", "object").put(
                        "properties",
                        JSONObject()
                            .put("app", appProperty)
                            .put("tuples", payloadProperty),
                    ).put("required", JSONArray().put("app").put("tuples")),
                ),
        ) { args ->
            blocked(Preflight.watch(context)) ?: run {
                val tuples = args.optJSONArray("tuples")
                if (tuples == null) {
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
                                "send to a closed one vanishes. pebblekit_launch_and_send " +
                                "handles that case." to true
                        is Watch.SendResult.NotAUuid -> notAUuid(result.given)
                        is Watch.SendResult.Malformed -> malformed(result.why)
                    }
                }
            }
        }

        val launchAndSend = ToolSpec(
            name = "pebblekit_launch_and_send",
            featureKey = "watch_control",
            definition = JSONObject()
                .put("name", "pebblekit_launch_and_send")
                .put(
                    "description",
                    "Get a Pebble watchapp running, prove it is up, then deliver one AppMessage: launches the app and sends the ping (about once a second, up to 10s) until the watch ACKs one, then sends the tuples exactly once and reports the watch's answer. Any message is acknowledged by the watch and handed to the watchapp, so the ping must be one the app does nothing with — usually an empty message, []. Which ping is safe for an app is skill-file knowledge. Use this when the watchapp may not be running; pebblekit_send_message alone is enough when it is.",
                )
                .put(
                    "inputSchema",
                    JSONObject().put("type", "object").put(
                        "properties",
                        JSONObject()
                            .put("app", appProperty)
                            .put(
                                "ping",
                                tuplesProperty(
                                    "The message sent repeatedly until the watchapp is up, " +
                                        "so it must be one the app does nothing with. " +
                                        "Usually empty: [].",
                                ),
                            )
                            .put("tuples", payloadProperty),
                    ).put("required", JSONArray().put("app").put("ping").put("tuples")),
                ),
        ) { args ->
            blocked(Preflight.watch(context)) ?: run {
                val ping = args.optJSONArray("ping")
                val tuples = args.optJSONArray("tuples")
                if (ping == null) {
                    "FAILED: give ping, the message to repeat until the watchapp is up — usually []." to true
                } else if (tuples == null) {
                    "FAILED: give tuples, an array of {key, type, length, value}." to true
                } else {
                    val result = Watch.launchAndSend(context, args.optString("app"), ping, tuples)
                    when (result) {
                        Watch.HandshakeResult.Acknowledged ->
                            "The watchapp came up and the watch acknowledged the message. " +
                                "That proves delivery to the running app — not that the " +
                                "keys meant anything to it." to false
                        Watch.HandshakeResult.Refused ->
                            "FAILED: the watchapp came up but the watch answered NACK to " +
                                "the message." to true
                        Watch.HandshakeResult.NoReply ->
                            "FAILED: the watchapp came up and the message was sent, but " +
                                "no ACK or NACK came back within 5s. It may or may not " +
                                "have arrived." to true
                        Watch.HandshakeResult.NeverCameUp ->
                            "FAILED: the watchapp never answered a ping within 10s, so " +
                                "the message was not sent. Ask the user to check the " +
                                "watch is in range." to true
                        is Watch.HandshakeResult.NotAUuid -> notAUuid(result.given)
                        is Watch.HandshakeResult.Malformed -> malformed(result.why)
                    }
                }
            }
        }

        return listOf(launchApp, stopApp, sendMessage, launchAndSend)
    }

    private fun describe(result: Watch.Result, verb: String): Pair<String, Boolean> = when (result) {
        Watch.Result.Sent -> "The app is $verb on the watch." to false
        is Watch.Result.NotAUuid -> notAUuid(result.given)
    }

    private fun notAUuid(given: String): Pair<String, Boolean> =
        "FAILED: \"$given\" is not a UUID. Nothing was sent." to true

    private fun malformed(why: String): Pair<String, Boolean> =
        "FAILED: not sent — $why. Every tuple needs key, type, length and value." to true
}
