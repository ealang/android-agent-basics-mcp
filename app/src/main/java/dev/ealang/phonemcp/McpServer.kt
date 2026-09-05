package dev.ealang.phonemcp

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.ZonedDateTime

/**
 * MCP over plain HTTP POST.
 *
 * The Streamable HTTP transport allows a POST to be answered with an SSE
 * stream, but that is optional and tools which return a string in a
 * millisecond have nothing to push — so every exchange here is ordinary
 * request/response JSON.
 */
class McpServer(
    private val context: Context,
    bindAddress: String,
    port: Int,
    private val token: String,
    onDied: () -> Unit,
    /**
     * Durably admit one webhook recording — the orchestrator's
     * `store.enqueue` + `worker.wake()`, handed in as a lambda so the server
     * knows nothing about the pipeline. Throwing means "could not queue",
     * answered with a 500 so the watch retries. Null disables the route.
     */
    private val webhookEnqueue: ((audio: File?, meta: Meta) -> Unit)? = null,
    /**
     * False for the loopback webhook listener, which must not expose the MCP
     * tools on an address every app on the phone can reach.
     */
    private val mcpEnabled: Boolean = true,
    // A null hostname makes NanoHTTPD bind the wildcard address; anything else
    // binds that one address, which is how the server is kept off the LAN.
) : NanoHTTPD(bindAddress.ifBlank { null }, port) {

    companion object {
        private const val TAG = "PhoneMcpServer"
        private const val FALLBACK_PROTOCOL = "2025-11-25"
        const val MCP_PATH = "/mcp"
        const val WEBHOOK_PATH = "/webhook"

        /**
         * The webhook's own listener is hardcoded to loopback: its one caller
         * is CoreApp on this same phone, so it needs no route from anywhere
         * else and must not inherit whatever the user typed into the bind
         * field — a blanked field means "every interface", and a recording
         * inbox open to the network is exactly the mistake this removes.
         */
        const val WEBHOOK_BIND = "127.0.0.1"
        const val WEBHOOK_PORT = 8911
    }

    init {
        serverSocketFactory = ServerSocketFactory { ResilientServerSocket(onDied) }
    }

    /**
     * Set by [dispatch] on the handling thread, so the log line can name the
     * MCP method (and tool) instead of just "POST /mcp". NanoHTTPD keeps one
     * session on one thread, which is what makes a ThreadLocal sound here.
     */
    private val detail = ThreadLocal<String?>()

    override fun serve(session: IHTTPSession): Response {
        detail.remove()
        val response = handle(session)
        val what = detail.get()?.let { " $it" } ?: ""
        RequestLog.record(
            session.remoteIpAddress,
            "${session.method} ${session.uri}$what → ${response.status.description}",
        )
        return response
    }

    private fun handle(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/').ifEmpty { "/" }

        // Open on purpose: something has to be probe-able to tell "wrong port"
        // apart from "wrong token".
        if (uri == "/health") {
            return json(Response.Status.OK, JSONObject().put("ok", true).put("service", "phone-mcp"))
        }

        val served = (uri == MCP_PATH && mcpEnabled) ||
            (uri == WEBHOOK_PATH && webhookEnqueue != null)
        if (!served) {
            return json(Response.Status.NOT_FOUND, JSONObject().put("error", "Not found: $uri"))
        }

        if (!authorized(session)) {
            Log.w(TAG, "Rejected an unauthorized request from ${session.remoteIpAddress}")
            return json(Response.Status.UNAUTHORIZED, JSONObject().put("error", "Unauthorized"))
        }

        if (uri == WEBHOOK_PATH) {
            return webhook(session)
        }

        if (session.method != Method.POST) {
            // The server-to-client notification stream is optional; we have
            // nothing to push, so GET is refused by design.
            return json(
                Response.Status.METHOD_NOT_ALLOWED,
                JSONObject().put("error", "streaming not supported"),
            )
        }

        val body = readBody(session) ?: return json(
            Response.Status.BAD_REQUEST, rpcError(null, -32700, "Parse error"),
        )

        val request = try {
            JSONObject(body)
        } catch (e: Exception) {
            return json(Response.Status.BAD_REQUEST, rpcError(null, -32700, "Parse error"))
        }

        val response = dispatch(request)
            ?: return newFixedLengthResponse(Response.Status.ACCEPTED, "text/plain", "")

        return json(Response.Status.OK, response)
    }

    private fun authorized(session: IHTTPSession): Boolean {
        // NanoHTTPD lowercases header names.
        val header = session.headers["authorization"] ?: return false
        return constantTimeEquals(header, "Bearer $token")
    }

    /**
     * Compares without an early return, so the time taken doesn't reveal how
     * many leading characters of a guess were right.
     */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.toByteArray()
        val y = b.toByteArray()
        if (x.size != y.size) return false
        var diff = 0
        for (i in x.indices) {
            diff = diff or (x[i].toInt() xor y[i].toInt())
        }
        return diff == 0
    }

    /**
     * The Pebble Index webhook — see `docs/webhook-pipeline.md` §2. The only
     * job here is to get the recording onto flash and answer fast: 200 means
     * "durably queued", never "delivered". Parsing failures are 400 (the
     * watch's payload won't improve on retry); a queue failure is 500 (ours,
     * and retrying may well fix it).
     */
    private fun webhook(session: IHTTPSession): Response {
        detail.set("webhook ${session.headers["x-index-trigger"] ?: "no-trigger"}")

        if (session.method != Method.POST) {
            return json(
                Response.Status.METHOD_NOT_ALLOWED,
                JSONObject().put("error", "POST only"),
            )
        }

        val length = session.headers["content-length"]?.toLongOrNull() ?: 0
        if (length > WebhookConfig.MAX_BODY_BYTES) {
            return json(
                Response.Status.PAYLOAD_TOO_LARGE,
                JSONObject().put("error", "body too large"),
            )
        }

        // For multipart, parseBody spills file parts to NanoHTTPD temp files
        // (deleted when this request ends — the enqueue must copy or rename)
        // and leaves text fields in session.parameters.
        val files = HashMap<String, String>()
        try {
            session.parseBody(files)
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable webhook body", e)
            return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "unreadable body"))
        }

        val transcription = session.parameters["transcription"]?.firstOrNull()?.trim()
        val audio = files["audio"]?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 }

        if (transcription.isNullOrBlank() && audio == null) {
            return json(
                Response.Status.BAD_REQUEST,
                JSONObject().put("error", "neither transcription nor audio present"),
            )
        }

        val meta = Meta(
            trigger = Trigger.fromHeader(session.headers["x-index-trigger"]),
            // CoreApp sends an ISO-8601 instant ("2026-08-31T22:14:03Z");
            // epoch milliseconds are accepted too for hand-rolled senders.
            recordedAtMs = session.parameters["recordedAt"]?.firstOrNull()?.trim()?.let { raw ->
                raw.toLongOrNull()
                    ?: runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrNull()
            } ?: 0,
            client = session.parameters["client"]?.firstOrNull(),
            pebbleTranscript = transcription?.ifBlank { null },
            receivedAtMs = System.currentTimeMillis(),
        )

        return try {
            webhookEnqueue!!.invoke(audio, meta)
            json(Response.Status.OK, JSONObject().put("status", "queued"))
        } catch (e: Exception) {
            Log.e(TAG, "Could not enqueue a webhook recording", e)
            json(
                Response.Status.INTERNAL_ERROR,
                JSONObject().put("error", "could not queue the recording"),
            )
        }
    }

    private fun readBody(session: IHTTPSession): String? = try {
        val map = HashMap<String, String>()
        session.parseBody(map)
        map["postData"] ?: ""
    } catch (e: Exception) {
        Log.w(TAG, "Could not read the request body", e)
        null
    }

    // ------------------------------ protocol ------------------------------

    /** Returns null for a notification, which the caller answers with 202. */
    private fun dispatch(msg: JSONObject): JSONObject? {
        val method = msg.optString("method")
        val id = if (msg.isNull("id")) null else msg.opt("id")
        val params = msg.optJSONObject("params") ?: JSONObject()

        detail.set(
            if (method == "tools/call") "tools/call ${params.optString("name")}" else method,
        )

        return when {
            method == "initialize" -> rpcOk(
                id,
                JSONObject()
                    // Echo the client's dialect — clients reject a version they
                    // don't recognise, and nothing here depends on which it is.
                    .put("protocolVersion", params.optString("protocolVersion", FALLBACK_PROTOCOL))
                    .put("capabilities", JSONObject().put("tools", JSONObject()))
                    .put(
                        "serverInfo",
                        JSONObject().put("name", "phone-mcp").put("version", "0.1"),
                    )
                    .put("instructions", INSTRUCTIONS),
            )

            method.startsWith("notifications/") -> null

            method == "ping" -> rpcOk(id, JSONObject())

            method == "tools/list" -> rpcOk(id, JSONObject().put("tools", toolDefinitions()))

            // Not declared in capabilities, but clients probe for them anyway,
            // and some treat a "Method not found" as a reason to give up on the
            // server entirely. An empty list is the truthful answer.
            method == "resources/list" -> rpcOk(id, JSONObject().put("resources", JSONArray()))
            method == "prompts/list" -> rpcOk(id, JSONObject().put("prompts", JSONArray()))

            method == "tools/call" -> {
                val name = params.optString("name")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                val (text, isError) = callTool(name, args)
                rpcOk(
                    id,
                    JSONObject()
                        .put(
                            "content",
                            JSONArray().put(JSONObject().put("type", "text").put("text", text)),
                        )
                        .put("isError", isError),
                )
            }

            else -> rpcError(id, -32601, "Method not found: $method")
        }
    }

    // -------------------------------- tools --------------------------------

    private fun toolDefinitions(): JSONArray {
        val setTimer = JSONObject()
            .put("name", "set_timer")
            .put(
                "description",
                "Start a timer in the clock app on the user's phone. It rings " +
                    "with their usual alarm sound and appears alongside any timers " +
                    "they set by hand. Use for 'remind me in N minutes' and " +
                    "kitchen-timer style requests.",
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
                                        "How long from now, in seconds. Give this or at.",
                                    ),
                            )
                            .put(
                                "at",
                                JSONObject().put("type", "string")
                                    .put(
                                        "description",
                                        "Ring at a clock time instead: 15:30, 3pm, " +
                                            "3:30pm, noon, or midnight. Means the next " +
                                            "time that comes round.",
                                    ),
                            )
                            .put(
                                "label",
                                JSONObject().put("type", "string")
                                    .put(
                                        "description",
                                        "What the timer is for. Shown on the phone, and " +
                                            "the only way to cancel this specific timer later.",
                                    ),
                            ),
                    ),
            )

        val cancelTimer = JSONObject()
            .put("name", "cancel_timer")
            .put(
                "description",
                "Cancel a running timer. Give the label it was set with; with no " +
                    "label, cancels whichever timer is due to ring next. The phone's " +
                    "timers cannot be listed, so a label that matches nothing cancels " +
                    "nothing.",
            )
            .put(
                "inputSchema",
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject().put(
                            "label",
                            JSONObject().put("type", "string")
                                .put("description", "The label the timer was set with."),
                        ),
                    ),
            )

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

        val launchWatchApp = JSONObject()
            .put("name", "launch_watch_app")
            .put(
                "description",
                "Open an app on the user's Pebble watch. The app takes over the " +
                    "screen on their wrist. It starts in its default state — this " +
                    "cannot set a duration or any other parameter, so to start a " +
                    "countdown use set_watch_timer instead.",
            )
            .put("inputSchema", appArgument)

        val stopWatchApp = JSONObject()
            .put("name", "stop_watch_app")
            .put(
                "description",
                "Close an app on the user's Pebble watch, returning it to the watchface.",
            )
            .put("inputSchema", appArgument)

        val setWatchTimer = JSONObject()
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
            )

        val getLocation = JSONObject()
            .put("name", "get_location")
            .put(
                "description",
                "Where the user's phone is right now: coordinates, how accurate " +
                    "the fix is, and a street address when one can be resolved. Use " +
                    "for anything that depends on where they are — nearby places, " +
                    "local weather, travel times, which timezone they are in.",
            )
            .put(
                "inputSchema",
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject().put(
                            "max_age_seconds",
                            JSONObject().put("type", "integer")
                                .put(
                                    "description",
                                    "Accept a cached fix this old before waiting for a " +
                                        "new one. Defaults to 120. Raise it when a rough " +
                                        "area will do, since a fresh fix can take " +
                                        "several seconds and wakes the GPS.",
                                ),
                        ),
                    ),
            )

        val playPodcast = JSONObject()
            .put("name", "play_podcast")
            .put(
                "description",
                "Resume podcast playback in Pocket Casts on the phone, picking up " +
                    "the episode the user was last listening to. Works whether or " +
                    "not the phone is locked, and whether or not Pocket Casts is " +
                    "already running. Cannot choose a specific show or episode.",
            )
            .put(
                "inputSchema",
                JSONObject().put("type", "object").put("properties", JSONObject()),
            )

        val nowPlaying = JSONObject()
            .put("name", "podcast_now_playing")
            .put(
                "description",
                "What Pocket Casts on the phone is playing or paused on: podcast, " +
                    "episode title, position, and whether audio is running. Useful " +
                    "before answering questions about \"this episode\" — the title " +
                    "is enough to find its transcript or show notes on the web.",
            )
            .put(
                "inputSchema",
                JSONObject().put("type", "object").put("properties", JSONObject()),
            )

        val findMyPhone = JSONObject()
            .put("name", "find_my_phone")
            .put(
                "description",
                "Make the phone ring loudly so the user can find it. Plays as an " +
                    "alarm, so it sounds even if the phone is on silent or in Do " +
                    "Not Disturb. Stops on its own. Use when the user has lost the " +
                    "phone, not to get their attention for something else.",
            )
            .put(
                "inputSchema",
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject().put(
                            "seconds",
                            JSONObject().put("type", "integer")
                                .put(
                                    "description",
                                    "How long to ring for. Defaults to " +
                                        "${Sound.DEFAULT_SECONDS}, at most ${Sound.MAX_SECONDS}.",
                                ),
                        ).put(
                            "volume",
                            JSONObject().put("type", "integer")
                                .put(
                                    "description",
                                    "Loudness, 1 to 100. Overrides the phone's alarm " +
                                        "volume for the length of the ring and puts it " +
                                        "back afterwards, so it works even when the " +
                                        "alarm is turned all the way down. Omit to ring " +
                                        "at whatever the user has it set to.",
                                ),
                        ),
                    ),
            )

        val silencePhone = JSONObject()
            .put("name", "silence_phone")
            .put(
                "description",
                "Stop the find_my_phone ringing early, once the user says they have " +
                    "found it.",
            )
            .put(
                "inputSchema",
                JSONObject().put("type", "object").put("properties", JSONObject()),
            )

        val startVoice = JSONObject()
            .put("name", "start_voice_session")
            .put(
                "description",
                "Open a live voice session with one of the user's Telegram " +
                    "conversations, on their phone: their unread messages are read " +
                    "aloud, and anything they say is transcribed and sent back. The " +
                    "session runs until stopped.",
            )
            .put(
                "inputSchema",
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject().put(
                            "conversation",
                            JSONObject().put("type", "string")
                                .put(
                                    "description",
                                    "Who to talk to: a conversation name, or enough of " +
                                        "one to be unambiguous. voice_status lists them. " +
                                        "Omit it to resume the most recent conversation.",
                                ),
                        ).put(
                            "catch_up",
                            JSONObject().put("type", "boolean")
                                .put(
                                    "description",
                                    "Only when the user explicitly asks to hear messages " +
                                        "again: true rewinds to their own last sent message " +
                                        "and replays everything after it, even if already " +
                                        "read or heard. Works on a running session too — no " +
                                        "need to stop it first. Otherwise omit it.",
                                ),
                        ),
                    ),
            )

        val stopVoice = JSONObject()
            .put("name", "stop_voice_session")
            .put(
                "description",
                "End the live voice session, releasing the phone's microphone. " +
                    "Messages already being transcribed still get sent.",
            )
            .put(
                "inputSchema",
                JSONObject().put("type", "object").put("properties", JSONObject()),
            )

        val voiceStatus = JSONObject()
            .put("name", "voice_status")
            .put(
                "description",
                "Whether a Telegram voice session is running on the phone and who " +
                    "with, plus every conversation available to start one with and " +
                    "how many unread messages each has.",
            )
            .put(
                "inputSchema",
                JSONObject().put("type", "object").put("properties", JSONObject()),
            )

        val startRecording = JSONObject()
            .put("name", "start_recording")
            .put(
                "description",
                "Record audio on the user's phone, saving it to their notes. Picks " +
                    "up the car's microphone when they are driving. Recording runs " +
                    "until stopped.",
            )
            .put(
                "inputSchema",
                JSONObject().put("type", "object").put("properties", JSONObject()),
            )

        val stopRecording = JSONObject()
            .put("name", "stop_recording")
            .put(
                "description",
                "Stop recording audio on the phone and save the file.",
            )
            .put(
                "inputSchema",
                JSONObject().put("type", "object").put("properties", JSONObject()),
            )

        // Experimental media-exploration prototype — no polish, remove or
        // promote after the survey.
        val mediaBrowse = JSONObject()
            .put("name", "media_browse")
            .put("description", "EXPERIMENTAL: list one node of a media app's browse tree (root if no node given).")
            .put(
                "inputSchema",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject()
                        .put("app", JSONObject().put("type", "string").put("description", "App name or package, fuzzy matched."))
                        .put("node", JSONObject().put("type", "string").put("description", "Media id of the node to list."))
                        .put("offset", JSONObject().put("type", "integer").put("description", "Skip this many children — for paging long listings.")),
                ).put("required", JSONArray().put("app")),
            )
        val mediaPlay = JSONObject()
            .put("name", "media_play")
            .put("description", "EXPERIMENTAL: try to start playback in a media app — by media_id, by search query, or bare resume. Reads back the session state after a beat. Audible on the phone.")
            .put(
                "inputSchema",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject()
                        .put("app", JSONObject().put("type", "string").put("description", "App name or package, fuzzy matched."))
                        .put("media_id", JSONObject().put("type", "string").put("description", "A playable id from media_browse."))
                        .put("query", JSONObject().put("type", "string").put("description", "Free-text search the app resolves itself.")),
                ).put("required", JSONArray().put("app")),
            )
        val mediaApps = JSONObject()
            .put("name", "media_apps")
            .put("description", "EXPERIMENTAL: list installed apps declaring a MediaBrowserService.")
            .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()))
        val mediaCommand = JSONObject()
            .put("name", "media_command")
            .put("description", "EXPERIMENTAL: send a media app a transport command: next, previous, pause, play, stop, queue, queue:<id>, seek:<seconds> (absolute, or +N/-N relative), speed:<multiplier>, rate:up/down/clear/<number>, uri:<uri>, queue_add:<id>, queue_add:<pos>:<id>, queue_remove:<id>, or a custom action id verbatim. Reads back the session state, position, and advertised actions after a beat.")
            .put(
                "inputSchema",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject()
                        .put("app", JSONObject().put("type", "string").put("description", "App name or package, fuzzy matched."))
                        .put("action", JSONObject().put("type", "string").put("description", "next, previous, pause, play, stop, queue, queue:<id>, seek:<seconds>, seek:+N, seek:-N, speed:<multiplier>, rate:up/down/clear/<number>, uri:<uri>, queue_add:<id>, queue_add:<pos>:<id>, queue_remove:<id>, or a custom action id.")),
                ).put("required", JSONArray().put("app").put("action")),
            )
        val mediaSearch = JSONObject()
            .put("name", "media_search")
            .put("description", "EXPERIMENTAL: search a media app's catalog and list the results without playing anything. Optional per app — some never implemented it.")
            .put(
                "inputSchema",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject()
                        .put("app", JSONObject().put("type", "string").put("description", "App name or package, fuzzy matched."))
                        .put("query", JSONObject().put("type", "string").put("description", "Free-text search.")),
                ).put("required", JSONArray().put("app").put("query")),
            )

        val navigate = JSONObject()
            .put("name", "navigate")
            .put(
                "description",
                "Start turn-by-turn navigation on the user's phone, in Google Maps. " +
                    "The destination can be a place name, an address, \"lat,long\", or " +
                    "the user's saved \"home\" or \"work\". Only works while the phone " +
                    "is unlocked.",
            )
            .put(
                "inputSchema",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject()
                        .put("destination", JSONObject().put("type", "string").put("description", "Place name, address, lat,long, or home/work."))
                        .put("mode", JSONObject().put("type", "string").put("description", "drive (default), walk, or bike.")),
                ).put("required", JSONArray().put("destination")),
            )

        // Experimental generic-intent prototype — same status as the media
        // tools above: findings decide what gets promoted.
        val intentSpecProperties = JSONObject()
            .put("action", JSONObject().put("type", "string").put("description", "Intent action, e.g. android.intent.action.VIEW."))
            .put("data", JSONObject().put("type", "string").put("description", "Data URI, e.g. https://... or geo:..."))
            .put("mime_type", JSONObject().put("type", "string").put("description", "MIME type of the data."))
            .put("categories", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "Intent categories."))
            .put("package", JSONObject().put("type", "string").put("description", "Limit to this package without naming a component."))
            .put("component", JSONObject().put("type", "string").put("description", "Explicit target, \"package/ClassName\" (leading-dot class ok)."))
            .put(
                "extras",
                JSONObject().put("type", "array").put(
                    "items",
                    JSONObject().put("type", "object").put(
                        "properties",
                        JSONObject()
                            .put("key", JSONObject().put("type", "string"))
                            .put("type", JSONObject().put("type", "string").put("description", "string, int, long, boolean, float, double, uri, string_array, int_array, or long_array."))
                            .put("value", JSONObject().put("description", "The value, matching the type.")),
                    ).put("required", JSONArray().put("key").put("value")),
                ).put("description", "Typed extras. The type must match what the receiving app reads."),
            )
        val intentScan = JSONObject()
            .put("name", "intent_scan")
            .put("description", "EXPERIMENTAL: scan an app's automation surface — its exported activities, receivers, and services with their intent filters and guarding permissions. With no package, lists every installed package.")
            .put(
                "inputSchema",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject().put("package", JSONObject().put("type", "string").put("description", "Exact package name to scan; omit to list all packages.")),
                ),
            )
        val intentResolve = JSONObject()
            .put("name", "intent_resolve")
            .put("description", "EXPERIMENTAL: dry run — list which activities, receivers, and services would handle an intent, without sending it.")
            .put(
                "inputSchema",
                JSONObject().put("type", "object").put("properties", intentSpecProperties),
            )
        val sendIntent = JSONObject()
            .put("name", "send_intent")
            .put("description", "EXPERIMENTAL: build and fire an Android intent — start an activity (opens on screen), send a broadcast, or start a service. Resolution is checked first; an intent nothing handles is not sent.")
            .put(
                "inputSchema",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject(intentSpecProperties.toString())
                        .put("mode", JSONObject().put("type", "string").put("description", "activity (default), broadcast, service, or foreground_service.")),
                ),
            )

        return JSONArray().put(setTimer).put(cancelTimer)
            .put(mediaApps).put(mediaBrowse).put(mediaPlay).put(mediaCommand).put(mediaSearch)
            .put(intentScan).put(intentResolve).put(sendIntent)
            .put(navigate)
            .put(launchWatchApp).put(stopWatchApp).put(setWatchTimer).put(getLocation)
            .put(playPodcast).put(nowPlaying).put(findMyPhone).put(silencePhone)
            .put(startVoice).put(stopVoice).put(voiceStatus)
            .put(startRecording).put(stopRecording)
    }

    /** Returns (text, isError). */
    private fun callTool(name: String, args: JSONObject): Pair<String, Boolean> = when (name) {
        "set_timer" -> blocked(Preflight.clock(context)) ?: run {
            val label = args.optString("label").ifBlank { "Timer" }
            val at = args.optString("at").trim()
            val now = ZonedDateTime.now()
            val clockTime = if (at.isEmpty()) null else TimeOfDay.resolve(at, now)
            // Clamped rather than rejected: a clock time can only ever land a
            // little outside the range, and refusing "at 15:31" one second past
            // 15:30 would be a failure the user cannot make sense of.
            val seconds = clockTime?.seconds?.coerceIn(1L, Clock.MAX_SECONDS)
                ?: args.optLong("seconds", -1)
            when {
                at.isNotEmpty() && clockTime == null ->
                    "FAILED: \"$at\" is not a clock time. Use something like 15:30, " +
                        "3pm, or noon." to true
                seconds <= 0 ->
                    "FAILED: give either seconds or at." to true
                seconds > Clock.MAX_SECONDS ->
                    "FAILED: that is longer than 24 hours, which is the most a timer " +
                        "can be. Set an alarm or a calendar reminder instead." to true
                !Clock.setTimer(context, seconds, label) -> clockRefused()
                else -> {
                    Log.i(TAG, "set_timer ${Clock.humanize(seconds)} \"$label\"")
                    "The phone's clock app is now running a ${Clock.humanize(seconds)} " +
                        "timer labelled \"$label\"" + dueAt(clockTime) + ". It will ring " +
                        "even if this connection closes." to false
                }
            }
        }

        "cancel_timer" -> blocked(Preflight.clock(context)) ?: run {
            val label = args.optString("label").ifBlank { null }
            when {
                !Clock.dismissTimer(context, label) -> clockRefused()
                label == null -> "Cancelled the timer that was due to ring next." to false
                else -> "Cancelled the timer labelled \"$label\"." to false
            }
        }

        "get_location" -> blocked(Preflight.location(context)) ?: run {
            val maxAge = args.optLong("max_age_seconds", 120).coerceAtLeast(0)
            when (val result = Locator.locate(context, maxAge)) {
                is Locator.Result.Fix -> {
                    val here = result.location
                    val age = Locator.ageSeconds(here)
                    Log.i(TAG, "get_location -> ${here.provider} ±${here.accuracy}m ${age}s old")
                    buildString {
                        append("%.5f, %.5f".format(here.latitude, here.longitude))
                        result.address?.let { append(" — ").append(it) }
                        append("\nAccurate to about ${here.accuracy.toInt()} m")
                        append(", fix taken ${Clock.humanize(age)} ago")
                        append(" (${here.provider}).")
                        if (age > 600) {
                            append(
                                "\nThat fix is old — the phone may have moved since. " +
                                    "Call again with max_age_seconds set low if it matters.",
                            )
                        }
                    } to false
                }
                // Degraded means the service never got the location foreground
                // type, and without "all the time" Android then withholds fixes
                // silently — which looks exactly like being indoors. Say which
                // it is rather than sending the user to a window for a
                // permissions problem.
                Locator.Result.NoFix ->
                    if (McpService.degraded && !Locator.hasBackgroundPermission(context)) {
                        "FAILED: the phone is withholding location from this app. The " +
                            "server started on its own (a reboot or an app update), and " +
                            "location is only allowed while the app is in use. Ask the " +
                            "user to open Phone MCP and set location to \"all the time\" " +
                            "— that fixes it for good. Opening the app also works once." to true
                    } else {
                        "FAILED: location is on and permitted, but no fix came back in " +
                            "time and nothing was cached. This happens indoors. Ask the " +
                            "user to try near a window, or to say where they are." to true
                    }
            }
        }

        "find_my_phone" -> {
            val seconds = args.optLong("seconds", Sound.DEFAULT_SECONDS)
                .coerceIn(1, Sound.MAX_SECONDS)
            val volume = if (args.has("volume")) args.optInt("volume", 100).coerceIn(1, 100) else null
            when (val result = Sound.play(context, seconds, volume)) {
                is Sound.Result.Playing ->
                    "The phone is ringing now for ${Clock.humanize(result.seconds)} at " +
                        "${result.percent}% volume. It stops by itself; call " +
                        "silence_phone to stop it sooner." to false
                Sound.Result.Silent ->
                    "FAILED: the phone's alarm volume is turned all the way down, so " +
                        "ringing it would make no sound. Nothing was played. Pass a " +
                        "volume to override it, or try get_location instead." to true
                is Sound.Result.Failed ->
                    "FAILED: could not play a sound (${result.reason})." to true
            }
        }

        "silence_phone" ->
            if (Sound.stop()) {
                "Stopped the ringing." to false
            } else {
                "The phone was not ringing." to false
            }

        "play_podcast" -> blocked(Preflight.podcastPlayback(context)) ?: when (Podcast.play(context)) {
            Podcast.Result.AlreadyPlaying ->
                "It was already playing — nothing to do." to false
            Podcast.Result.NotInstalled ->
                "FAILED: Pocket Casts is not installed on the phone." to true
            else -> "Told Pocket Casts to play." to false
        }

        "podcast_now_playing" -> blocked(Preflight.podcastState(context)) ?: when (val r = Podcast.nowPlaying(context)) {
            // Idle is a normal answer to "what's playing?", not a failure —
            // reporting it as an error makes callers apologise for the phone.
            Podcast.Result.NoSession ->
                "Nothing is playing — Pocket Casts is idle. Call play_podcast " +
                    "to start it." to false
            is Podcast.Result.NowPlaying -> {
                val position = if (r.positionMs >= 0 && r.durationMs > 0) {
                    " at ${Clock.humanize(r.positionMs / 1000)} of " +
                        Clock.humanize(r.durationMs / 1000)
                } else ""
                val state = if (r.playing) "Playing" else "Paused"
                "$state: \"${r.episode ?: "unknown episode"}\" from " +
                    "\"${r.podcast ?: "unknown podcast"}\"$position." to false
            }
            else -> "FAILED: Pocket Casts did not give a usable answer." to true
        }

        "start_voice_session" -> {
            // Absent means "resume the most recent conversation" — the gateway
            // reads a missing extra that way, so the blank is passed through as
            // null rather than refused.
            val conversation = args.optString("conversation").trim().ifBlank { null }
            val catchUp = args.optBoolean("catch_up", false)
            blocked(Preflight.voice(context))
                ?: conversation?.let { blocked(Preflight.voiceConversation(context, it)) }
                ?: when (val r = Voice.start(context, conversation, catchUp)) {
                    is Voice.Result.Accepted ->
                        "The voice session with ${r.conversation ?: "the most recent " +
                            "conversation"} is open on the phone" +
                            (if (catchUp) ", replaying from their last sent message" else "") +
                            "." to false
                    else -> voiceFailure(r)
                }
        }

        "stop_voice_session" -> when (val r = Voice.stop(context)) {
            is Voice.Result.Accepted ->
                "The phone accepted the request to end the session with " +
                    "${r.conversation ?: "the current conversation"}." to false
            else -> voiceFailure(r)
        }

        "voice_status" -> when (val r = Voice.status(context)) {
            is Voice.Result.Status -> describe(r) to false
            else -> voiceFailure(r)
        }

        "start_recording" -> blocked(Preflight.recorderStart(context))
            ?: when (Recorder.start(context)) {
                Recorder.Result.Accepted -> "The phone is recording." to false
                Recorder.Result.NotAvailable -> RECORDER_UNAVAILABLE to true
            }

        "stop_recording" -> blocked(Preflight.recorderStop(context))
            ?: when (Recorder.stop(context)) {
                Recorder.Result.Accepted -> "The recording is saved." to false
                Recorder.Result.NotAvailable -> RECORDER_UNAVAILABLE to true
            }

        "launch_watch_app" -> blocked(Preflight.watch(context))
            ?: describe(Watch.launch(context, args.optString("app")), "opened")

        "stop_watch_app" -> blocked(Preflight.watch(context))
            ?: describe(Watch.stop(context, args.optString("app")), "closed")

        "set_watch_timer" -> blocked(Preflight.watch(context)) ?: run {
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
                            "will buzz when it finishes" + dueAt(clockTime) + ". The " +
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

        // Experimental prototype tools; findings over polish.
        "media_apps" -> {
            val apps = MediaExplorer.mediaApps(context)
            if (apps.isEmpty()) "No installed app declares a MediaBrowserService." to false
            else apps.joinToString("\n") { "${it.label} (${it.pkg})" } to false
        }

        "media_browse" -> {
            val (app, problem) = MediaExplorer.resolve(context, args.optString("app"))
            if (app == null) "FAILED: $problem" to true
            else MediaExplorer.browse(
                context,
                app,
                args.optString("node").ifBlank { null },
                args.optInt("offset", 0).coerceAtLeast(0),
            ) to false
        }

        "media_play" -> {
            val (app, problem) = MediaExplorer.resolve(context, args.optString("app"))
            if (app == null) {
                "FAILED: $problem" to true
            } else {
                MediaExplorer.play(
                    context,
                    app,
                    args.optString("media_id").ifBlank { null },
                    args.optString("query").ifBlank { null },
                ) to false
            }
        }

        "media_command" -> {
            val (app, problem) = MediaExplorer.resolve(context, args.optString("app"))
            val action = args.optString("action")
            if (app == null) "FAILED: $problem" to true
            // Queue edits ride the compat protocol, so they take a different
            // connection path from the rest of the commands.
            else if (action.trim().startsWith("queue_add:") || action.trim().startsWith("queue_remove:")) {
                MediaExplorer.queueEdit(context, app, action.trim()) to false
            } else {
                MediaExplorer.command(context, app, action) to false
            }
        }

        "media_search" -> {
            val (app, problem) = MediaExplorer.resolve(context, args.optString("app"))
            if (app == null) "FAILED: $problem" to true
            else MediaExplorer.search(context, app, args.optString("query")) to false
        }

        "navigate" -> blocked(Preflight.navigation(context)) ?: run {
            val destination = args.optString("destination").trim()
            if (destination.isEmpty()) {
                "FAILED: give a destination — a place name, address, or the user's saved home or work." to true
            } else {
                val text = Navigation.navigate(context, destination, args.optString("mode").ifBlank { null })
                text to text.startsWith("FAILED:")
            }
        }

        "intent_scan" -> {
            val pkg = args.optString("package").trim()
            val text = if (pkg.isEmpty()) IntentExplorer.listPackages(context) else IntentExplorer.scan(context, pkg)
            text to text.startsWith("FAILED:")
        }

        "intent_resolve" -> {
            val text = IntentExplorer.resolve(context, args)
            text to text.startsWith("FAILED:")
        }

        "send_intent" -> {
            val text = IntentExplorer.send(context, args)
            text to text.startsWith("FAILED:")
        }

        else -> "No such tool: $name" to true
    }

    /**
     * A preflight reason turned into a tool failure, or null to carry on.
     *
     * Written as `blocked(...) ?: run { ... }` at the top of a handler, so the
     * check and the work it guards read in the order they happen and no handler
     * can quietly skip one.
     */
    private fun blocked(reason: String?): Pair<String, Boolean>? =
        reason?.let { "FAILED: $it" to true }

    /**
     * The recorder is installed — preflight has just said so — but would not
     * take the request. Nothing the user can do beyond opening it once.
     */
    private val RECORDER_UNAVAILABLE =
        "FAILED: the voice recorder app did not take the request. Ask the user to " +
            "open it once on the phone."

    /**
     * The clock app refusing an intent that preflight said it handles. Rare
     * enough to have one wording for all three timer tools.
     */
    private fun clockRefused(): Pair<String, Boolean> =
        "FAILED: the phone's clock app would not take the request. Nothing was " +
            "changed. Ask the user to check their timers by hand." to true

    /**
     * ", due at 15:30" when the caller asked for a clock time, empty otherwise.
     *
     * Echoing the resolved time is the only way an am/pm guess that went the
     * wrong way becomes visible before the timer rings. It reports the time
     * that was asked for rather than one worked back from the interval, so a
     * clamp cannot make it disagree with the request.
     */
    private fun dueAt(clockTime: TimeOfDay.Resolved?): String =
        if (clockTime == null) "" else ", due at " + TimeOfDay.format(clockTime.time)

    private fun describe(status: Voice.Result.Status): String {
        // The id decides whether a session exists; the name is only how we
        // refer to it, and can be missing for a session that is very much live.
        val name = status.activeConversation ?: status.activeConversationId
        val session = when {
            status.activeConversationId == null -> "No voice session is running."
            status.paused -> "Paused mid-session with $name."
            status.listening && status.micSecured -> "Listening to $name — the microphone is live."
            // Everything else is a session that exists but cannot hear the
            // user: a start still in flight, one Android refused, or a
            // microphone taken away mid-session. Which of those it is changes
            // nothing the caller can do about it — the recording state is in
            // the log for a human. Kept as one outcome on purpose.
            else -> "A session with $name is not listening yet. Ask the user to check the phone."
        }
        val audio = if (status.queueDepth > 0) {
            " ${status.queueDepth} message(s) still to be read aloud."
        } else {
            ""
        }
        val conversations = if (status.conversations.isEmpty()) {
            "No conversations are configured in the app."
        } else {
            "Conversations:\n" + status.conversations.joinToString("\n") {
                val unread = if (it.unread > 0) " — ${it.unread} unread" else ""
                "  ${it.name}$unread${if (it.active) " (active)" else ""}"
            }
        }
        return "$session$audio\n\n$conversations"
    }

    private fun voiceFailure(result: Voice.Result): Pair<String, Boolean> = when (result) {
        is Voice.Result.Refused -> "FAILED: ${result.message}" to true
        Voice.Result.NotAvailable ->
            "FAILED: the voice gateway app did not answer. Ask the user to open it " +
                "once on the phone." to true
        // Not reachable — callers handle every success shape first — but it
        // must not hand the agent a Kotlin toString it can do nothing with.
        else -> "FAILED: the voice gateway did not give a usable answer." to true
    }

    private fun describe(result: Watch.Result, verb: String): Pair<String, Boolean> = when (result) {
        Watch.Result.Sent -> "The app is $verb on the watch." to false
        is Watch.Result.UnknownApp ->
            "FAILED: \"${result.given}\" is not a UUID and not a known app name. " +
                "Known names: ${Watch.KNOWN_APPS.keys.joinToString(", ")}. Nothing " +
                "was sent." to true
    }

    // ------------------------------- helpers -------------------------------

    private fun rpcOk(id: Any?, result: JSONObject) = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("result", result)

    private fun rpcError(id: Any?, code: Int, message: String) = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", message))

    private fun json(status: Response.Status, body: JSONObject): Response =
        newFixedLengthResponse(status, "application/json", body.toString())
}

private const val INSTRUCTIONS = """Tools that run on the user's Android phone.

These act on the physical device the user is carrying, so effects are real and
immediate: a timer set here rings on that phone, in their own clock app. Prefer
them over anything that merely records an intention when the user asks for
something to happen at a specific time.

Both timer tools take either a duration in seconds or a clock time in `at`. Pass
the clock time the user said and let the phone work out the interval; it knows
what time it is and you do not.

Every tool checks what it needs before it acts, and either does the job or
answers FAILED with the reason. There is no separate confirmation step: if a
tool did not say FAILED, tell the user it is done. When it does say FAILED, pass
on the reason — it names what the user has to fix.

The phone's timers cannot be listed. If the user asks what is running, say so
and suggest they look at the clock app.

The watch tools reach a Pebble paired to that phone, by way of its companion
app. Launching a watchapp cannot parameterise it — it opens in its default
state, and the user drives it from there.

get_location is the phone's own position, so it is where the user is. Call it
rather than asking them where they are, and rather than assuming a city from
earlier in the conversation.

find_my_phone is loud and overrides silent mode, so use it only for a phone the
user has actually lost. If they are reading the reply, they have their phone."""
