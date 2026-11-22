package io.github.ealang.agentbasicsmcp.mcp.pebble

import android.content.Context
import io.github.ealang.agentbasicsmcp.mcp.ClientConnection
import io.github.ealang.agentbasicsmcp.mcp.Preflight
import io.github.ealang.agentbasicsmcp.mcp.ToolSpec
import io.github.ealang.agentbasicsmcp.mcp.blocked
import org.json.JSONArray
import org.json.JSONObject

/**
 * The tools that reach the Pebble on the user's wrist, through CoreApp.
 *
 * Two families, named for the CoreApp API each rides. `pebblekit2_` is
 * PebbleKit 2: launching, stopping and reading state for any watchapp, and
 * messaging in both directions with a watchapp that names this app as its
 * Android companion. `pebblekit_` is the classic broadcast surface, kept for
 * the one thing PebbleKit 2 will not do: send to a watchapp that names no
 * companion.
 *
 * The watch is a platform, not an app: these are generic over *watchapps*
 * the way `send_intent` is generic over apps, and what any particular
 * watchapp's keys mean is skill-file knowledge.
 */
object PebbleTools {

    /** The one switch every tool here and the inbound listener sit behind. */
    const val FEATURE = "pebblekit"

    private const val DEFAULT_MAX_AGE_S = 3600L
    /**
     * The longest one read is held open. A caller that vanished without
     * closing its connection cannot be told from one waiting, so this is
     * what bounds the thread it holds. A longer wait is shortened, not
     * refused: the caller asks again and the queue has kept what came.
     */
    private const val MAX_WAIT_S = 600L

    /** A send's whole allowance when the caller names none. */
    private const val DEFAULT_SEND_TIMEOUT_S = 60L

    private const val MAX_AGE_CEILING_S = 10L * 365 * 24 * 3600

    private const val COMPANION_ONLY =
        "Works only for a watchapp that names this app as its Android companion; " +
            "for any other, use the pebblekit_ tool of the same name."

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
                    .put("length", JSONObject().put("type", "integer").put("description", "Integer width in bytes, 1, 2 or 4, default 4; ignored for string and bytes, which are measured."))
                    .put("value", JSONObject().put("description", "Whole number for int/uint, text for string, base64 for bytes.")),
            ).put("required", JSONArray().put("key").put("type").put("value")),
        ).put("description", description)

        val payloadProperty = tuplesProperty(
            "The AppMessage, as tuples, passed to the watch as-is. May be empty.",
        )

        val messageArguments = JSONObject().put("type", "object").put(
            "properties",
            JSONObject()
                .put("app", appProperty)
                .put("tuples", payloadProperty)
                .put(
                    "messages",
                    JSONObject().put("type", "array").put("items", payloadProperty).put(
                        "description",
                        "Several AppMessages instead of tuples: an array of tuple arrays, " +
                            "sent in order, each after the watch acknowledged the one " +
                            "before. All are checked before any is sent. The first that " +
                            "is not acknowledged ends the call and the rest are not sent.",
                    ),
                )
                .put(
                    "timeout_seconds",
                    JSONObject().put("type", "integer").put(
                        "description",
                        "The longest the sending may take as a whole, counted from the " +
                            "first send. A message whose turn comes after that is not " +
                            "sent. Default $DEFAULT_SEND_TIMEOUT_S. Anything over " +
                            "$MAX_WAIT_S is treated as $MAX_WAIT_S.",
                    ),
                ),
        ).put("required", JSONArray().put("app"))

        val launchApp = ToolSpec(
            name = "pebblekit2_launch_app",
            featureKey = FEATURE,
            definition = JSONObject()
                .put("name", "pebblekit2_launch_app")
                .put(
                    "description",
                    "Open an app on the user's Pebble watch and wait, up to 10s, for the " +
                        "watch to report it running. Works for any watchapp. The app starts " +
                        "in its default state; no parameters can be passed.",
                )
                .put("inputSchema", appArgument),
        ) { args ->
            blocked(Preflight.watch(context)) ?: withUuid(args) { uuid ->
                when (val result = PebbleKit2.start(context, uuid)) {
                    PebbleKit2.Launch.Running -> "The watch reports the app running." to false
                    is PebbleKit2.Launch.Failed -> "FAILED: ${result.why}" to true
                }
            }
        }

        val stopApp = ToolSpec(
            name = "pebblekit2_stop_app",
            featureKey = FEATURE,
            definition = JSONObject()
                .put("name", "pebblekit2_stop_app")
                .put(
                    "description",
                    "Close an app on the user's Pebble watch, returning it to the " +
                        "watchface. Works for any watchapp. The watch does not confirm the " +
                        "close; pebblekit2_watch_state shows what is open afterwards.",
                )
                .put("inputSchema", appArgument),
        ) { args ->
            blocked(Preflight.watch(context)) ?: withUuid(args) { uuid ->
                when (val result = PebbleKit2.stop(context, uuid)) {
                    PebbleKit2.Stop.Accepted -> "Stop passed to the watch." to false
                    is PebbleKit2.Stop.Failed -> "FAILED: ${result.why}" to true
                }
            }
        }

        val watchState = ToolSpec(
            name = "pebblekit2_watch_state",
            featureKey = FEATURE,
            definition = JSONObject()
                .put("name", "pebblekit2_watch_state")
                .put(
                    "description",
                    "The Pebble watches connected to the phone and what each has open. " +
                        "Returns {\"watches\": [{name, platform, firmware, running}]}, where " +
                        "running is {app, name, type} — the open app's UUID, its name, and " +
                        "watchface, watchapp or unknown — or null. No watch connected is an " +
                        "empty list, not an error.",
                )
                .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject())),
        ) { _ ->
            blocked(Preflight.pebbleApp(context)) ?: run {
                PebbleKit2.state(context)?.let { it.toString() to false }
                    ?: ("FAILED: the Pebble app did not report its watches." to true)
            }
        }

        val sendMessage2 = ToolSpec(
            name = "pebblekit2_send_message",
            featureKey = FEATURE,
            definition = JSONObject()
                .put("name", "pebblekit2_send_message")
                .put(
                    "description",
                    "Send a raw AppMessage to an app on the user's Pebble watch. Tuples go " +
                        "to the watchapp exactly as given; the watch's own ACK or NACK comes " +
                        "back. An ACK proves delivery to the running app, not that the keys " +
                        "meant anything. The watchapp must be open on the watch, and the " +
                        "failure says so when it is not — use pebblekit2_launch_and_send " +
                        "when it may not be. Give tuples for one message or messages for " +
                        "several. $COMPANION_ONLY",
                )
                .put("inputSchema", messageArguments),
        ) { args ->
            blocked(Preflight.watch(context)) ?: deliver(context, args, launch = false)
        }

        val launchAndSend2 = ToolSpec(
            name = "pebblekit2_launch_and_send",
            featureKey = FEATURE,
            definition = JSONObject()
                .put("name", "pebblekit2_launch_and_send")
                .put(
                    "description",
                    "Deliver an AppMessage to a Pebble watchapp, opening it first unless " +
                        "the watch already has it running. A launch waits (up to 10s) for " +
                        "the watch to report the app running. The message is then sent " +
                        "once, never retried, and the watch's answer is reported. Give " +
                        "tuples for one message or messages for several. Use this " +
                        "when the watchapp may not be open; pebblekit2_send_message alone " +
                        "is enough when it is. " +
                        COMPANION_ONLY,
                )
                .put("inputSchema", messageArguments),
        ) { args ->
            blocked(Preflight.watch(context)) ?: deliver(context, args, launch = true)
        }

        val receiveMessages = ToolSpec(
            name = "pebblekit2_receive_messages",
            featureKey = FEATURE,
            definition = JSONObject()
                .put("name", "pebblekit2_receive_messages")
                .put(
                    "description",
                    "Read what a Pebble watchapp has sent to the phone, and when it was " +
                        "opened and closed. Returns {\"events\": [...], \"dropped\": n}: " +
                        "events in arrival order, each {type, received_at}, type one of " +
                        "message, app_opened, app_closed, and a message also carrying " +
                        "tuples in the form the send tools take. Reading empties the queue, " +
                        "so each event is returned once. Events are held in memory only, at " +
                        "most ${WatchInbox.CAPACITY} per watchapp; dropped counts those lost to that " +
                        "limit since the last read. Nothing queued is an empty list, not " +
                        "an error. The watch was already told each message arrived, " +
                        "whether or not anything reads it. " +
                        "Only a watchapp that names this app as its Android companion " +
                        "is heard from.",
                )
                .put(
                    "inputSchema",
                    JSONObject().put("type", "object").put(
                        "properties",
                        JSONObject()
                            .put("app", appProperty)
                            .put(
                                "max_age_seconds",
                                JSONObject().put("type", "integer").put(
                                    "description",
                                    "Events older than this are discarded instead of " +
                                        "returned. Default $DEFAULT_MAX_AGE_S.",
                                ),
                            )
                            .put(
                                "wait_seconds",
                                JSONObject().put("type", "integer").put(
                                    "description",
                                    "With nothing queued, wait up to this long for the " +
                                        "first event and return when it arrives. Default 0. " +
                                        "Anything over $MAX_WAIT_S is treated as $MAX_WAIT_S: " +
                                        "to keep listening, call again.",
                                ),
                            ),
                    ).put("required", JSONArray().put("app")),
                ),
        ) { args ->
            withUuid(args) { uuid ->
                val maxAge = if (args.has("max_age_seconds")) args.optLong("max_age_seconds", -1) else DEFAULT_MAX_AGE_S
                val wait = args.optLong("wait_seconds", 0)
                when {
                    maxAge <= 0 -> "FAILED: max_age_seconds must be a whole number above 0." to true
                    wait < 0 -> "FAILED: wait_seconds must be 0 or more." to true
                    else -> WatchInbox.take(
                        uuid,
                        // Far past any uptime, and safely short of overflow.
                        maxAge.coerceAtMost(MAX_AGE_CEILING_S) * 1000,
                        wait.coerceAtMost(MAX_WAIT_S) * 1000,
                        ClientConnection::alive,
                    ).toString() to false
                }
            }
        }

        val sendMessage = ToolSpec(
            name = "pebblekit_send_message",
            featureKey = FEATURE,
            definition = JSONObject()
                .put("name", "pebblekit_send_message")
                .put(
                    "description",
                    "Send a raw AppMessage to an app on the user's Pebble watch over the classic PebbleKit broadcasts, which reach any watchapp — the watch equivalent of send_intent. Tuples go to the watchapp exactly as given; the watch's own ACK or NACK comes back. An ACK proves delivery to the running app, not that the keys meant anything: a key the app ignores is still acknowledged. Key meanings per app are skill-file knowledge. The watchapp must already be running or the message vanishes silently — use pebblekit_launch_and_send when it may not be. A watchapp that names this app as its Android companion is better served by pebblekit2_send_message, which says why a send failed.",
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
                    "FAILED: give tuples, an array of {key, type, value}." to true
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
            featureKey = FEATURE,
            definition = JSONObject()
                .put("name", "pebblekit_launch_and_send")
                .put(
                    "description",
                    "Get a Pebble watchapp running, prove it is up, then deliver one AppMessage over the classic PebbleKit broadcasts, which reach any watchapp: launches the app and waits (up to 10s) for the watch to report it running, sends the ping (about once a second, up to 10s) until the watch ACKs one, then sends the tuples exactly once and reports the watch's answer. Any message is acknowledged by the watch and handed to the watchapp, so the ping must be one the app does nothing with — usually an empty message, []. Which ping is safe for an app is skill-file knowledge. Use this when the watchapp may not be running; pebblekit_send_message alone is enough when it is. A watchapp that names this app as its Android companion needs no ping: use pebblekit2_launch_and_send.",
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
                    "FAILED: give tuples, an array of {key, type, value}." to true
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
                        is Watch.HandshakeResult.NotLaunched ->
                            "FAILED: ${result.why} The message was not sent." to true
                        Watch.HandshakeResult.NeverCameUp ->
                            "FAILED: the watch reports the app running, but no ping was " +
                                "acknowledged within 10s, so the message was not sent." to true
                        is Watch.HandshakeResult.NotAUuid -> notAUuid(result.given)
                        is Watch.HandshakeResult.Malformed -> malformed(result.why)
                    }
                }
            }
        }

        return listOf(
            launchApp, stopApp, watchState,
            sendMessage2, launchAndSend2, receiveMessages,
            sendMessage, launchAndSend,
        )
    }

    private inline fun withUuid(
        args: JSONObject,
        then: (String) -> Pair<String, Boolean>,
    ): Pair<String, Boolean> {
        val app = args.optString("app")
        return Watch.resolve(app)?.let(then) ?: notAUuid(app)
    }

    /**
     * A PebbleKit 2 send: one message given as tuples or several given as
     * messages, all checked before anything is sent, then delivered in order.
     */
    private fun deliver(context: Context, args: JSONObject, launch: Boolean): Pair<String, Boolean> =
        withUuid(args) { uuid ->
            val single = args.optJSONArray("tuples")
            val several = args.optJSONArray("messages")
            if ((single == null) == (several == null)) {
                return "FAILED: give either tuples, an array of {key, type, value}, or " +
                    "messages, an array of those arrays. Nothing was sent." to true
            }
            val timeout = if (args.has("timeout_seconds")) args.optLong("timeout_seconds", -1) else DEFAULT_SEND_TIMEOUT_S
            if (timeout <= 0) {
                return "FAILED: timeout_seconds must be a whole number above 0. Nothing was sent." to true
            }
            val given = single?.let { listOf(it) } ?: (0 until several!!.length()).map {
                several.optJSONArray(it)
                    ?: return "FAILED: message ${it + 1} is not an array of tuples. Nothing was sent." to true
            }
            if (given.isEmpty()) return "FAILED: messages is empty. Nothing was sent." to true
            val messages = given.mapIndexed { i, tuples ->
                when (val wire = Watch.toWire(tuples)) {
                    is Watch.Wire.Ready -> wire.tuples
                    is Watch.Wire.Unsendable ->
                        return if (single != null) {
                            malformed(wire.why)
                        } else {
                            "FAILED: nothing was sent — message ${i + 1}: ${wire.why}. " +
                                "Every tuple needs key, type and value." to true
                        }
                }
            }
            val result = PebbleKit2.deliver(
                context, uuid, messages, launch,
                timeout.coerceAtMost(MAX_WAIT_S) * 1000, ClientConnection::alive,
            )
            val count = messages.size
            when (result) {
                PebbleKit2.Delivery.Acknowledged -> when {
                    single == null ->
                        "The watch acknowledged all $count messages, in order. That proves " +
                            "delivery to the running app — not that the keys meant " +
                            "anything to it." to false
                    launch ->
                        "The watchapp is open and the watch acknowledged the message. " +
                            "That proves delivery to the running app — not that the " +
                            "keys meant anything to it." to false
                    else ->
                        "The watch acknowledged delivery. That proves the message " +
                            "reached the running watchapp — not that the keys meant " +
                            "anything to it." to false
                }
                is PebbleKit2.Delivery.NotLaunched ->
                    "FAILED: ${result.why} ${if (single != null) "The message was not sent." else "Nothing was sent."}" to true
                is PebbleKit2.Delivery.Stopped -> {
                    val why = when (val cause = result.cause) {
                        is PebbleKit2.Send.Failed -> cause.why
                        else -> if (launch) {
                            "it is not open to messages from this app. The usual reason is " +
                                "that the watchapp does not name this app as its Android " +
                                "companion. The message was not delivered."
                        } else {
                            "the app is not open on the watch, or it does not name " +
                                "this app as its Android companion. The message was not " +
                                "delivered. pebblekit2_launch_and_send opens it first; " +
                                "pebblekit2_watch_state shows what is open."
                        }
                    }
                    when {
                        single == null ->
                            "FAILED: the watch acknowledged ${result.acknowledged} of $count " +
                                "messages. Message ${result.acknowledged + 1} failed: $why " +
                                "Nothing after it was sent." to true
                        launch -> "FAILED: the watchapp is running, but $why" to true
                        else -> "FAILED: $why" to true
                    }
                }
            }
        }

    private fun notAUuid(given: String): Pair<String, Boolean> =
        "FAILED: \"$given\" is not a UUID. Nothing was sent." to true

    private fun malformed(why: String): Pair<String, Boolean> =
        "FAILED: not sent — $why. Every tuple needs key, type and value." to true
}
