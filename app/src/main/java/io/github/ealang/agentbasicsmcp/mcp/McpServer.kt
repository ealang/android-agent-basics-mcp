package io.github.ealang.agentbasicsmcp.mcp

import android.content.Context
import android.util.Log
import io.github.ealang.agentbasicsmcp.Config
import io.github.ealang.agentbasicsmcp.Networks
import io.github.ealang.agentbasicsmcp.ServerLog
import io.github.ealang.agentbasicsmcp.mcp.apps.AppTools
import io.github.ealang.agentbasicsmcp.mcp.files.FileProblem
import io.github.ealang.agentbasicsmcp.mcp.files.FileStore
import io.github.ealang.agentbasicsmcp.mcp.files.FileTools
import io.github.ealang.agentbasicsmcp.mcp.files.ServedFiles
import io.github.ealang.agentbasicsmcp.mcp.files.UploadCut
import io.github.ealang.agentbasicsmcp.mcp.generic.GenericTools
import io.github.ealang.agentbasicsmcp.mcp.pebble.PebbleStreams
import io.github.ealang.agentbasicsmcp.mcp.pebble.PebbleTools
import io.github.ealang.agentbasicsmcp.mcp.pebble.WatchStreams
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.application.install
import io.ktor.server.application.serverConfig
import io.ktor.server.cio.CIO
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.contentLength
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.request.receiveStream
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.IgnoreTrailingSlash
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.util.AttributeKey
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The Android MCP server: MCP over plain HTTP POST.
 *
 * The Streamable HTTP transport allows a POST to be answered with an SSE
 * stream, but that is optional and tools which return a string in a
 * millisecond have nothing to push — so every exchange here is ordinary
 * request/response JSON.
 *
 * The HTTP layer is here too: bearer-token auth and request logging, with
 * the MCP dispatch past them. Every path requires the token; nothing is
 * answered without it.
 *
 * Beside /mcp sit the /files routes, PUT and GET of one file's raw bytes.
 * They exist because MCP is JSON: a file sent as a tool argument would be
 * base64 text the calling model has to write out, which stops being
 * practical well under a megabyte.
 *
 * And /pebblekit2/send_receive, a WebSocket to one watchapp, for a script exchanging
 * messages with it faster than a tool call apiece allows.
 */
class McpServer(
    bindAddress: String,
    private val port: Int,
    /**
     * Read per request, not captured at bind: pressing Regenerate must take
     * effect on the very next request, without a server restart. The provider
     * reads SecretStore's in-process cache, so this costs nothing on the
     * request thread.
     */
    private val token: () -> String,
    /** Called when the server fails underneath a caller that had started it. */
    private val onDied: () -> Unit,
    /** The server's log; every request line goes here too. */
    private val log: ServerLog,
    /** The whole tool surface, in the order tools/list shows it. */
    private val tools: List<ToolSpec>,
    /**
     * The MCP Tools card's feature switches, asked per request, so a switch
     * takes effect on the next call with no restart.
     */
    private val featureEnabled: (String) -> Boolean,
    /** What the /files routes read and write. */
    private val files: ServedFiles,
    /** What the watch stream route opens. */
    private val watch: WatchStreams,
) {
    // One literal address, or 0.0.0.0 for every IPv4 interface. The IPv4
    // wildcard rather than the dual-stack one: a carrier hands the phone a
    // public IPv6 address, and "everything" is meant to mean the LAN, not
    // the open internet.
    private val host = bindAddress.ifBlank { Networks.ALL_IPV4 }

    @Volatile
    private var started = false

    /**
     * What happens to every request whatever its path: it is counted, held
     * to the token, told its connection will close, and logged once
     * answered.
     */
    private val everyRequest = createApplicationPlugin("EveryRequest") {
        onCall { call ->
            log.bump("requests")
            call.attributes.put(EXCHANGE, Exchange())
            // One request per connection. A connection left open is closed
            // by the server after a spell of silence, and a client that
            // pools connections (an HTTP proxy, a keep-alive agent) can
            // reuse one at the moment it is being closed and get an empty
            // reply.
            // A WebSocket upgrade is the exception: its connection is the
            // point.
            if (!call.request.headers[HttpHeaders.Upgrade].equals("websocket", ignoreCase = true)) {
                call.response.header(HttpHeaders.Connection, "close")
            }
            // Auth before routing: an unauthenticated caller learns nothing,
            // not even which paths exist.
            if (!authorized(call)) {
                log.bump("rejected")
                log.warn(
                    "rejected an unauthorized request",
                    "from ${call.request.local.remoteAddress}",
                )
                json(call, HttpStatusCode.Unauthorized, JSONObject().put("error", "Unauthorized"))
            }
        }
        // Every request, at debug: the wire-level record underneath whatever
        // higher-level line the handler wrote about the same exchange.
        on(ResponseSent) { call ->
            val what = call.attributes.getOrNull(EXCHANGE)?.detail?.let { " $it" } ?: ""
            log.debug(
                "${call.request.httpMethod.value} ${call.request.path()}$what → ${call.response.status()}",
                "from ${call.request.local.remoteAddress}",
            )
        }
    }

    private val engine = embeddedServer(
        CIO,
        rootConfig = serverConfig {
            // Without a handler here, a failure in the server's own
            // coroutines, a refused bind among them, is an uncaught
            // exception, which on Android ends the process.
            //
            // This does not see a listener that has gone dead, as when the
            // bound address is taken away: the engine retries a failing
            // accept quietly and for good, and nothing arrives here.
            parentCoroutineContext = CoroutineExceptionHandler { _, e ->
                Log.w(TAG, "The server failed", e)
                if (started) {
                    started = false
                    log.error("server failed", e.message ?: e::class.java.simpleName)
                    onDied()
                }
            }
            module {
                install(everyRequest)
                install(IgnoreTrailingSlash)
                install(WebSockets) {
                    // A client that vanishes without closing is noticed by
                    // its missing pong.
                    pingPeriod = PING
                    timeout = PING
                }
                routing {
                    route(MCP_PATH) {
                        post { mcp(call) }
                        // The server-to-client notification stream is
                        // optional; we have nothing to push, so GET is
                        // refused by design.
                        handle {
                            json(
                                call,
                                HttpStatusCode.MethodNotAllowed,
                                JSONObject().put("error", "streaming not supported"),
                            )
                        }
                    }
                    route("$FILES_PATH/{...}") { handle { files(call) } }
                    webSocket(STREAM_PATH) { pebble(this) }
                    route("{...}") {
                        handle {
                            json(
                                call,
                                HttpStatusCode.NotFound,
                                JSONObject().put("error", "Not found: ${decodedPath(call)}"),
                            )
                        }
                    }
                }
            }
        },
        configure = {
            connector {
                host = this@McpServer.host
                port = this@McpServer.port
            }
            // The service rebinds the same port moments after a teardown;
            // without this a lingering connection can hold the port hostage.
            reuseAddress = true
        },
    )

    /** Throws when the address cannot be bound. */
    fun start() {
        // The engine binds on a coroutine and reports a failure as a
        // cancellation with no reason in it. Binding here first gets the
        // reason, the one thing the caller shows the user.
        ServerSocket().use {
            it.reuseAddress = true
            it.bind(InetSocketAddress(host, port))
        }
        try {
            engine.start(wait = false)
        } catch (e: Exception) {
            runCatching { engine.stop(0, 0) }
            throw e
        }
        started = true
    }

    /**
     * Stops at once: requests being answered are dropped, not waited for.
     * A tool still running is left to finish, and its answer goes nowhere.
     */
    fun stop() {
        started = false
        engine.stop(0, 0)
    }

    private val byName: Map<String, ToolSpec> = tools.associateBy { it.name }

    /**
     * A null featureKey means always on: a capability of the phone itself,
     * with no switch to check.
     */
    private fun enabled(spec: ToolSpec): Boolean =
        spec.featureKey == null || featureEnabled(spec.featureKey)

    companion object {
        private const val TAG = "AgentBasicsMcpServer"
        private const val FALLBACK_PROTOCOL = "2025-11-25"
        const val MCP_PATH = "/mcp"
        const val FILES_PATH = "/files"
        const val STREAM_PATH = "/pebblekit2/send_receive"
        private val PING = 15.seconds

        /**
         * The server as the app runs it: every tool, the user's switches,
         * the real file system. The tool specs close over the Context, which
         * lives as long as the app does.
         */
        fun forApp(
            context: Context,
            bindAddress: String,
            port: Int,
            token: () -> String,
            onDied: () -> Unit,
            log: ServerLog,
        ): McpServer {
            // SharedPreferences is an in-memory map after first load, so
            // reading a switch fresh on each request costs nothing.
            val config = Config(context)
            return McpServer(
                bindAddress, port, token, onDied, log,
                tools = AppTools.specs(context) + PebbleTools.specs(context) +
                    GenericTools.specs(context) + FileTools.specs(context),
                featureEnabled = config::featureEnabled,
                files = FileStore(context),
                watch = PebbleStreams(context),
            )
        }

        private val EXCHANGE = AttributeKey<Exchange>("Exchange")

        /** Argument names that identify a call's subject, most specific first. */
        private val SUBJECT_KEYS =
            listOf("action", "uri", "app", "package", "destination", "query")
    }

    /**
     * What one request meant (the MCP method and tool, the file path), so the
     * log line can say more than "POST /mcp".
     */
    private class Exchange {
        var detail: String? = null
    }

    /** The request path, percent-decoding undone, without a trailing slash. */
    private fun decodedPath(call: ApplicationCall): String =
        call.request.path().decodeURLPart().trimEnd('/').ifEmpty { "/" }

    /** One JSON-RPC message in, one answer out. */
    private suspend fun mcp(call: ApplicationCall) {
        val request = try {
            // JSON is UTF-8 by definition (RFC 8259), so the body is decoded
            // as such whatever charset the Content-Type header names or omits.
            JSONObject(String(call.receive<ByteArray>(), Charsets.UTF_8))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            json(call, HttpStatusCode.BadRequest, rpcError(null, -32700, "Parse error"))
            return
        }

        // Tools block, some for minutes; not on the server's own threads.
        val response = withContext(Dispatchers.IO) { dispatch(request, call.attributes[EXCHANGE]) }
        if (response == null) {
            call.respondText("", ContentType.Text.Plain, HttpStatusCode.Accepted)
        } else {
            json(call, HttpStatusCode.OK, response)
        }
    }

    // ------------------------------- files -------------------------------

    /**
     * PUT writes the request body to the path, GET returns the file. The
     * path is the same `<folder>/<sub/path>` the file tools take, and the
     * same switch turns both off.
     */
    private suspend fun files(call: ApplicationCall): HttpStatusCode {
        val exchange = call.attributes[EXCHANGE]
        val path = decodedPath(call).removePrefix(FILES_PATH)
        val method = call.request.httpMethod
        if (method != HttpMethod.Put && method != HttpMethod.Get) {
            return json(
                call,
                HttpStatusCode.MethodNotAllowed,
                JSONObject().put("error", "PUT uploads a file and GET downloads one."),
            )
        }
        val upload = if (method == HttpMethod.Put) {
            val length = call.request.contentLength()
                ?: return json(
                    call,
                    HttpStatusCode.BadRequest,
                    JSONObject().put("error", "A PUT needs a Content-Length header."),
                )
            BodyStream(call.receiveStream(), length)
        } else {
            null
        }
        return try {
            if (!featureEnabled(FileTools.FEATURE)) {
                throw FileProblem(
                    FileProblem.Kind.FORBIDDEN,
                    "The user has switched the file tools off in the Agent Basics MCP app.",
                )
            }
            if (upload != null) {
                val written = withContext(Dispatchers.IO) { files.put(path, upload) }
                exchange.detail = "put $path"
                log.info("file uploaded", path)
                json(call, HttpStatusCode.OK, written)
            } else {
                val file = withContext(Dispatchers.IO) { files.serve(path) }
                exchange.detail = "get $path"
                log.info("file downloaded", path)
                call.respondOutputStream(ContentType.parse(file.mime), HttpStatusCode.OK, file.size) {
                    file.stream.use { it.copyTo(this) }
                }
                HttpStatusCode.OK
            }
        } catch (e: FileProblem) {
            log.warn("file request refused", e.message)
            json(
                call,
                when (e.kind) {
                    FileProblem.Kind.BAD_REQUEST -> HttpStatusCode.BadRequest
                    FileProblem.Kind.NOT_FOUND -> HttpStatusCode.NotFound
                    FileProblem.Kind.FORBIDDEN -> HttpStatusCode.Forbidden
                    FileProblem.Kind.CONFLICT -> HttpStatusCode.Conflict
                    FileProblem.Kind.FAILED -> HttpStatusCode.InternalServerError
                },
                JSONObject().put("error", e.message),
            )
        }
    }

    /**
     * The request body, checked against its declared length. A client that
     * goes away mid-upload looks like the end of the body, so a body that
     * stops short is made an error here and half an upload is never kept
     * as a file. The same goes for a read ended by the server stopping.
     */
    private class BodyStream(private val source: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = try {
                source.read(b, off, minOf(len.toLong(), left).toInt())
            } catch (e: CancellationException) {
                throw UploadCut("the server stopped with $left bytes to come")
            }
            if (n < 0) throw UploadCut("the upload ended $left bytes early")
            left -= n
            return n
        }

        override fun close() = source.close()
    }

    // ------------------------------- pebble -------------------------------

    /**
     * A WebSocket to one watchapp, for a script that talks with it faster
     * than a tool call per message allows. The query carries app, the
     * watchapp's UUID, and optionally max_age_seconds, as the receive tool
     * takes it but defaulting to 0. Connecting opens the app on the watch.
     * Text frames, one JSON object each:
     *
     *  - in: {"id": any, "tuples": [...]}, one AppMessage, sent in the order
     *    received and answered {"type": "ack", "id"} or
     *    {"type": "failed", "id", "error"}
     *  - out: {"type": "ready"} once the app is open
     *  - out: {"type": "events", "events": [...], "dropped": n} as the
     *    watchapp speaks, in pebblekit2_receive_messages' form
     *  - out: {"type": "closed", "error"} before the server ends the stream
     *
     * The same switch as the PebbleKit tools turns it off.
     */
    private suspend fun pebble(socket: DefaultWebSocketServerSession) {
        val call = socket.call
        val app = call.request.queryParameters["app"].orEmpty()
        call.attributes[EXCHANGE].detail = "stream"

        suspend fun say(type: String, fill: JSONObject.() -> Unit = {}) =
            socket.send(Frame.Text(JSONObject().put("type", type).apply(fill).toString()))

        suspend fun end(why: String) {
            say("closed") { put("error", why) }
            socket.close()
        }

        if (!featureEnabled(PebbleTools.FEATURE)) {
            return end("the user has switched the PebbleKit tools off in the Agent Basics MCP app.")
        }
        val maxAge = call.request.queryParameters["max_age_seconds"].let { given ->
            if (given == null) {
                0
            } else {
                given.toLongOrNull()?.takeIf { it >= 0 }
                    ?: return end("max_age_seconds must be a whole number, 0 or more.")
            }
        }
        val opening = withContext(Dispatchers.IO) {
            watch.open(app, maxAge.coerceAtMost(PebbleTools.MAX_AGE_CEILING_S) * 1000)
        }
        val stream = when (opening) {
            is WatchStreams.Opening.Ready -> opening.stream
            is WatchStreams.Opening.Refused -> {
                log.warn("watch stream refused", opening.why)
                return end(opening.why)
            }
        }
        log.info("watch stream opened", app)
        val events = socket.launch(Dispatchers.IO) {
            while (true) {
                val batch = runInterruptible { stream.next() } ?: break
                say("events") {
                    put("events", batch.getJSONArray("events"))
                    put("dropped", batch.getInt("dropped"))
                }
            }
            end("a newer caller took over.")
        }
        try {
            say("ready")
            for (frame in socket.incoming) {
                if (frame !is Frame.Text) continue
                val message = try {
                    JSONObject(frame.readText())
                } catch (e: JSONException) {
                    say("failed") { put("error", "that frame is not a JSON object. Nothing was sent.") }
                    continue
                }
                val tuples = message.optJSONArray("tuples")
                val why = if (tuples == null) {
                    "give tuples, an array of {key, type, value}. Nothing was sent."
                } else {
                    withContext(Dispatchers.IO) { stream.send(tuples) }
                }
                say(if (why == null) "ack" else "failed") {
                    message.opt("id")?.let { put("id", it) }
                    why?.let { put("error", it) }
                }
            }
        } finally {
            events.cancel()
            stream.close()
            log.info("watch stream closed", app)
        }
    }

    private fun authorized(call: ApplicationCall): Boolean {
        val header = call.request.headers[HttpHeaders.Authorization] ?: return false
        return constantTimeEquals(header, "Bearer ${token()}")
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

    private suspend fun json(
        call: ApplicationCall,
        status: HttpStatusCode,
        body: JSONObject,
    ): HttpStatusCode {
        call.respondText(body.toString(), ContentType.Application.Json, status)
        return status
    }

    // ------------------------------ protocol ------------------------------

    /** Returns null for a notification, which the caller answers with 202. */
    private fun dispatch(msg: JSONObject, exchange: Exchange): JSONObject? {
        val method = msg.optString("method")
        val id = if (msg.isNull("id")) null else msg.opt("id")
        val params = msg.optJSONObject("params") ?: JSONObject()

        exchange.detail =
            if (method == "tools/call") "tools/call ${params.optString("name")}" else method

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
                        JSONObject().put("name", "android-agent-basics-mcp").put("version", "0.1"),
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
                // A tool that throws is answered as a failed call, not as a
                // server error. A generic tool firing an intent at a
                // permission-guarded component is exactly how that happens,
                // and the exception text is a finding worth returning.
                val spec = byName[name]
                val (text, isError) = try {
                    when {
                        spec == null -> "No such tool: $name" to true
                        // Checked at dispatch too, not just in tools/list:
                        // clients cache the list, so a tool switched off after
                        // the handshake still arrives here by name.
                        !enabled(spec) ->
                            "FAILED: the user has switched this tool off in the " +
                                "Agent Basics MCP app." to true
                        else -> spec.handler(args)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Tool $name threw", t)
                    "FAILED: ${t.javaClass.simpleName}: ${t.message ?: "no detail"}" to true
                }
                // The high-level line: which tool, on what — never the full
                // payload. The subject is whichever argument names the thing
                // acted on, and most tools have exactly one of these keys.
                val subject = SUBJECT_KEYS.firstNotNullOfOrNull {
                    args.optString(it).ifBlank { null }
                }
                if (isError) {
                    log.bump("failures")
                    log.warn("tool $name failed", text.take(120))
                } else {
                    log.info("tool $name", subject)
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

private const val INSTRUCTIONS = """Tools that run on the user's Android phone, acting on the device they are carrying.

The tools are generic Android mechanisms (intents, content providers, media
sessions, Pebble AppMessages), not commands for particular apps. How to drive a
specific app through them is skill-file knowledge: check the skill files the user
has provided before experimenting. The android-agent-basics-mcp repository ships a
set, indexed app by app in its README with a link to each file:
https://raw.githubusercontent.com/ealang/android-agent-basics-mcp/main/README.md
The links are relative paths; fetch them under the same raw base."""
