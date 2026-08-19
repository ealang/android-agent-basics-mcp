package dev.ealang.phonemcp

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject

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
    port: Int,
    private val token: String,
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "PhoneMcpServer"
        private const val FALLBACK_PROTOCOL = "2025-11-25"
        const val MCP_PATH = "/mcp"
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/').ifEmpty { "/" }

        // Open on purpose: something has to be probe-able to tell "wrong port"
        // apart from "wrong token".
        if (uri == "/health") {
            return json(Response.Status.OK, JSONObject().put("ok", true).put("service", "phone-mcp"))
        }

        if (uri != MCP_PATH) {
            return json(Response.Status.NOT_FOUND, JSONObject().put("error", "Not found: $uri"))
        }

        if (!authorized(session)) {
            Log.w(TAG, "Rejected an unauthorized request from ${session.remoteIpAddress}")
            return json(Response.Status.UNAUTHORIZED, JSONObject().put("error", "Unauthorized"))
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
                                    .put("description", "How long from now, in seconds."),
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
                    )
                    .put("required", JSONArray().put("seconds")),
            )

        val cancelTimer = JSONObject()
            .put("name", "cancel_timer")
            .put(
                "description",
                "Cancel a running timer. Give the label it was set with; with no " +
                    "label, cancels whichever timer is due to ring next. There is no " +
                    "way to read back the phone's timers, so this cannot be checked " +
                    "first — say what was cancelled and let the user confirm.",
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

        val showTimers = JSONObject()
            .put("name", "show_timers")
            .put(
                "description",
                "Open the clock app's timer screen on the phone, so the user can " +
                    "see what is running. Returns nothing about the timers themselves.",
            )
            .put(
                "inputSchema",
                JSONObject().put("type", "object").put("properties", JSONObject()),
            )

        return JSONArray().put(setTimer).put(cancelTimer).put(showTimers)
    }

    /** Returns (text, isError). */
    private fun callTool(name: String, args: JSONObject): Pair<String, Boolean> = when (name) {
        "set_timer" -> {
            val seconds = args.optLong("seconds", -1)
            val label = args.optString("label").ifBlank { "Timer" }
            when {
                seconds <= 0 ->
                    "FAILED: seconds is required and must be greater than zero." to true
                seconds > Clock.MAX_SECONDS ->
                    "FAILED: that is longer than 24 hours, which is the most a timer " +
                        "can be. Set an alarm or a calendar reminder instead." to true
                else -> Clock.setTimer(context, seconds, label).let { result ->
                    Log.i(TAG, "set_timer ${Clock.humanize(seconds)} \"$label\" -> $result")
                    when (result) {
                        Clock.Result.Ok ->
                            "CONFIRMED - the phone's clock app is now running a " +
                                "${Clock.humanize(seconds)} timer labelled \"$label\". It will " +
                                "ring even if this connection closes." to false
                        else -> describe(result)
                    }
                }
            }
        }

        "cancel_timer" -> {
            val label = args.optString("label").ifBlank { null }
            when (val result = Clock.dismissTimer(context, label)) {
                Clock.Result.Ok ->
                    if (label == null) {
                        "Asked the clock app to cancel the next timer due to ring." to false
                    } else {
                        "Asked the clock app to cancel the timer labelled \"$label\"." to false
                    }
                else -> describe(result)
            }
        }

        "show_timers" -> when (val result = Clock.showTimers(context)) {
            Clock.Result.Ok -> "Opened the timer screen on the phone." to false
            else -> describe(result)
        }

        else -> "No such tool: $name" to true
    }

    private fun describe(result: Clock.Result): Pair<String, Boolean> = when (result) {
        Clock.Result.NeedsOverlayPermission ->
            "FAILED: the phone is blocking this app from opening the clock app in the " +
                "background. Tell the user to open Phone MCP and grant \"Display over " +
                "other apps\" — Android allows a background app to start an activity " +
                "only with that permission. Nothing was scheduled." to true
        Clock.Result.NoClockApp ->
            "FAILED: no app on the phone handles timer intents. Nothing was " +
                "scheduled." to true
        Clock.Result.Ok -> "OK" to false
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

set_timer takes a duration in seconds, not a wall-clock time — convert first.

Android offers no way to read back the clock app's timers, so there is nothing
to list and no way to verify. Report what was requested, not what is running."""
