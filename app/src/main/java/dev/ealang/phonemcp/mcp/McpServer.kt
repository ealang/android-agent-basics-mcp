package dev.ealang.phonemcp.mcp

import android.content.Context
import android.util.Log
import dev.ealang.phonemcp.AuthedHttpServer
import dev.ealang.phonemcp.Config
import dev.ealang.phonemcp.mcp.apps.AppTools
import dev.ealang.phonemcp.mcp.generic.GenericTools
import dev.ealang.phonemcp.mcp.pebble.PebbleTools
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Android MCP server: MCP over plain HTTP POST.
 *
 * The Streamable HTTP transport allows a POST to be answered with an SSE
 * stream, but that is optional and tools which return a string in a
 * millisecond have nothing to push — so every exchange here is ordinary
 * request/response JSON.
 *
 * The Pebble webhook receiver used to live in this class behind flags; it is
 * its own listener now ([dev.ealang.phonemcp.webhook.PebbleWebhookServer]),
 * because only this one is an MCP server and the names should say so.
 */
class McpServer(
    private val context: Context,
    bindAddress: String,
    port: Int,
    token: String,
    onDied: () -> Unit,
) : AuthedHttpServer(bindAddress, port, token, onDied) {

    /**
     * The whole tool surface, one [ToolSpec] per tool, in the order tools/list
     * shows them. Built once — the specs close over the Context, which lives
     * as long as the app does.
     */
    private val tools: List<ToolSpec> =
        AppTools.specs(context) + PebbleTools.specs(context) + GenericTools.specs(context)

    private val byName: Map<String, ToolSpec> = tools.associateBy { it.name }

    private val config = Config(context)

    /**
     * The MCP tab's feature switches, honoured per request — SharedPreferences
     * is an in-memory map after first load, so reading it fresh each time *is*
     * the real-time toggle, no restart involved. A null featureKey means
     * always on: a capability of the phone itself, with no switch to check.
     */
    private fun enabled(spec: ToolSpec): Boolean =
        spec.featureKey == null || config.featureEnabled(spec.featureKey)

    companion object {
        private const val TAG = "PhoneMcpServer"
        private const val FALLBACK_PROTOCOL = "2025-11-25"
        const val MCP_PATH = "/mcp"
    }

    override fun serves(uri: String): Boolean = uri == MCP_PATH

    override fun route(uri: String, session: IHTTPSession): Response {
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

            method == "tools/list" -> rpcOk(
                id,
                JSONObject().put(
                    "tools",
                    JSONArray().also { list ->
                        tools.filter(::enabled).forEach { list.put(it.definition) }
                    },
                ),
            )

            // Not declared in capabilities, but clients probe for them anyway,
            // and some treat a "Method not found" as a reason to give up on the
            // server entirely. An empty list is the truthful answer.
            method == "resources/list" -> rpcOk(id, JSONObject().put("resources", JSONArray()))
            method == "prompts/list" -> rpcOk(id, JSONObject().put("prompts", JSONArray()))

            method == "tools/call" -> {
                val name = params.optString("name")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                // A tool that throws used to take the HTTP worker thread with
                // it: NanoHTTPD lets anything unchecked out of serve() close
                // the socket, so the client saw the connection drop rather
                // than a failure — the server looked dead for that request.
                // A generic tool firing an intent at a permission-guarded
                // component is exactly how that happens, and the exception
                // text is a finding worth returning.
                val spec = byName[name]
                val (text, isError) = try {
                    when {
                        spec == null -> "No such tool: $name" to true
                        // Checked at dispatch too, not just in tools/list:
                        // clients cache the list, so a tool switched off after
                        // the handshake still arrives here by name.
                        !enabled(spec) ->
                            "FAILED: the user has switched this tool off in the " +
                                "Phone MCP app." to true
                        else -> spec.handler(args)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Tool $name threw", t)
                    "FAILED: ${t.javaClass.simpleName}: ${t.message ?: "no detail"}" to true
                }
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

    // ------------------------------- helpers -------------------------------

    private fun rpcOk(id: Any?, result: JSONObject) = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("result", result)

    private fun rpcError(id: Any?, code: Int, message: String) = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", message))
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
