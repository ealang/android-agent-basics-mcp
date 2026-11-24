package io.github.ealang.agentbasicsmcp.mcp

import io.github.ealang.agentbasicsmcp.ServerLog
import io.github.ealang.agentbasicsmcp.mcp.files.FileProblem
import io.github.ealang.agentbasicsmcp.mcp.files.FileTools
import io.github.ealang.agentbasicsmcp.mcp.files.ServedFile
import io.github.ealang.agentbasicsmcp.mcp.files.ServedFiles
import io.github.ealang.agentbasicsmcp.mcp.pebble.PebbleTools
import io.github.ealang.agentbasicsmcp.mcp.pebble.WatchStream
import io.github.ealang.agentbasicsmcp.mcp.pebble.WatchStreams
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.nio.file.Files
import java.util.Optional
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The real server on a loopback port, asked real HTTP requests. Only the
 * edges are stand-ins: two made-up tools, a set of switched-off features,
 * and a folder on disk in place of the phone's file system.
 */
class McpServerTest {
    private val root: File = Files.createTempDirectory("mcp-test").toFile()
    private val log = ServerLog(root, "test")
    private val off = mutableSetOf<String>()
    private var token = "first-token"
    private val port = ServerSocket(0).use { it.localPort }
    private val http = HttpClient.newHttpClient()

    private val watch = FakeWatch()

    private val tools = listOf(
        tool("echo", featureKey = null) { args -> args.optString("text") to false },
        tool("guarded", featureKey = "guarded_feature") { "ran" to false },
        tool("broken", featureKey = null) { throw IllegalStateException("boom") },
    )

    private val server = McpServer(
        "127.0.0.1", port, { token }, onDied = {}, log = log,
        tools = tools,
        featureEnabled = { it !in off },
        files = FolderFiles(File(root, "files")),
        watch = watch,
    )

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() {
        server.stop()
        root.deleteRecursively()
    }

    // ---------------------------------------------------------------- auth

    @Test
    fun `a request with no token is refused and counted`() {
        val reply = send(request("/mcp", bearer = null).POST(body(rpc("ping"))))
        assertEquals(401, reply.statusCode())
        assertEquals("Unauthorized", JSONObject(reply.body()).getString("error"))
        assertEquals(1, log.count("rejected"))
    }

    @Test
    fun `a wrong token is refused`() {
        assertEquals(401, send(request("/mcp", bearer = "nope").POST(body(rpc("ping")))).statusCode())
    }

    @Test
    fun `a changed token applies to the next request`() {
        assertEquals(200, post(rpc("ping")).statusCode())
        token = "second-token"
        assertEquals(401, send(request("/mcp", bearer = "first-token").POST(body(rpc("ping")))).statusCode())
        assertEquals(200, post(rpc("ping")).statusCode())
    }

    // ------------------------------------------------------------- routing

    @Test
    fun `an unknown path is a 404 naming the path`() {
        val reply = send(request("/nowhere").GET())
        assertEquals(404, reply.statusCode())
        assertEquals("Not found: /nowhere", JSONObject(reply.body()).getString("error"))
    }

    @Test
    fun `GET on the MCP path is a 405`() {
        assertEquals(405, send(request("/mcp").GET()).statusCode())
    }

    @Test
    fun `every reply closes the connection`() {
        assertEquals("close", post(rpc("ping")).headers().firstValue("Connection").orElse(null))
    }

    // ----------------------------------------------------------------- MCP

    @Test
    fun `a notification is accepted with no body`() {
        val reply = post(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"))
        assertEquals(202, reply.statusCode())
        assertEquals("", reply.body())
    }

    @Test
    fun `a body that is not JSON is a parse error`() {
        val reply = send(request("/mcp").POST(body("{not json")))
        assertEquals(400, reply.statusCode())
        assertEquals(-32700, JSONObject(reply.body()).getJSONObject("error").getInt("code"))
    }

    @Test
    fun `an unknown method is a JSON-RPC error`() {
        val reply = JSONObject(post(rpc("no/such")).body())
        assertEquals(-32601, reply.getJSONObject("error").getInt("code"))
    }

    @Test
    fun `tools list leaves out a switched-off tool`() {
        assertEquals(listOf("echo", "guarded", "broken"), listedTools())
        off += "guarded_feature"
        assertEquals(listOf("echo", "broken"), listedTools())
    }

    @Test
    fun `a tool call returns the handler's text`() {
        val result = call("echo", JSONObject().put("text", "hello"))
        assertEquals("hello", text(result))
        assertFalse(result.getBoolean("isError"))
    }

    @Test
    fun `calling a switched-off tool fails without running it`() {
        off += "guarded_feature"
        val result = call("guarded")
        assertTrue(result.getBoolean("isError"))
        assertTrue(text(result).startsWith("FAILED: the user has switched this tool off"))
    }

    @Test
    fun `a tool that throws is a failed call, counted`() {
        val result = call("broken")
        assertTrue(result.getBoolean("isError"))
        assertEquals("FAILED: IllegalStateException: boom", text(result))
        assertEquals(1, log.count("failures"))
    }

    @Test
    fun `an unknown tool is a failed call`() {
        val result = call("missing")
        assertTrue(result.getBoolean("isError"))
        assertEquals("No such tool: missing", text(result))
    }

    // --------------------------------------------------------------- files

    @Test
    fun `a file put can be got back byte for byte`() {
        val bytes = ByteArray(300_000) { (it * 31).toByte() }
        val put = send(request("/files/docs/a.bin").PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)))
        assertEquals(200, put.statusCode())
        assertEquals(bytes.size, JSONObject(put.body()).getInt("size"))

        val got = http.send(request("/files/docs/a.bin").GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        assertEquals(200, got.statusCode())
        assertContentEquals(bytes, got.body())
    }

    @Test
    fun `getting a missing file is a 404`() {
        assertEquals(404, send(request("/files/docs/none.txt").GET()).statusCode())
    }

    @Test
    fun `files are refused when the file tools are off`() {
        off += FileTools.FEATURE
        assertEquals(403, send(request("/files/docs/a.txt").PUT(body("x"))).statusCode())
        assertEquals(403, send(request("/files/docs/a.txt").GET()).statusCode())
    }

    @Test
    fun `a put with no Content-Length is refused`() {
        val reply = raw(
            "PUT /files/docs/a.txt HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer $token\r\n" +
                "Transfer-Encoding: chunked\r\n\r\n1\r\nx\r\n0\r\n\r\n",
        )
        assertTrue(reply.startsWith("HTTP/1.1 400"), reply)
    }

    @Test
    fun `an upload cut short leaves no file`() {
        // Ten bytes promised, four sent, then the client hangs up.
        raw(
            "PUT /files/docs/cut.txt HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer $token\r\n" +
                "Content-Length: 10\r\n\r\nfour",
        )
        assertFalse(File(root, "files/docs/cut.txt").exists())
        assertEquals(404, send(request("/files/docs/cut.txt").GET()).statusCode())
    }

    // -------------------------------------------------------------- pebble

    @Test
    fun `a stream needs the token`() {
        val refused = assertFailsWith<ExecutionException> { Client(bearer = null) }
        assertEquals(401, (refused.cause as WebSocketHandshakeException).response.statusCode())
        assertTrue(watch.opened.isEmpty())
    }

    @Test
    fun `connecting opens the watchapp and says ready`() {
        val client = Client()
        assertEquals("ready", client.next().getString("type"))
        assertEquals(listOf(APP to 0L), watch.opened)
    }

    @Test
    fun `max_age_seconds is passed on in milliseconds`() {
        Client("&max_age_seconds=30").next()
        assertEquals(listOf(APP to 30_000L), watch.opened)
    }

    @Test
    fun `a refused opening is explained and the stream ends`() {
        watch.refusal = "no watch is connected."
        val client = Client()
        val frame = client.next()
        assertEquals("closed", frame.getString("type"))
        assertEquals("no watch is connected.", frame.getString("error"))
        client.awaitClose()
    }

    @Test
    fun `a stream is refused when the PebbleKit tools are off`() {
        off += PebbleTools.FEATURE
        val client = Client()
        assertEquals("closed", client.next().getString("type"))
        client.awaitClose()
        assertTrue(watch.opened.isEmpty())
    }

    @Test
    fun `sends are answered in order with the caller's ids`() {
        val client = Client()
        client.next()
        watch.answers += listOf(null, "NACK")
        client.say(JSONObject().put("id", 7).put("tuples", JSONArray().put(tuple(1))))
        client.say(JSONObject().put("id", "b").put("tuples", JSONArray().put(tuple(2))))

        val first = client.next()
        assertEquals("ack", first.getString("type"))
        assertEquals(7, first.getInt("id"))
        val second = client.next()
        assertEquals("failed", second.getString("type"))
        assertEquals("b", second.getString("id"))
        assertEquals("NACK", second.getString("error"))
        assertEquals(listOf(1, 2), watch.sent.map { it.getJSONObject(0).getInt("key") })
    }

    @Test
    fun `a frame with no tuples fails without a send`() {
        val client = Client()
        client.next()
        client.say(JSONObject().put("id", 1))
        assertEquals("failed", client.next().getString("type"))
        client.say("not json")
        assertEquals("failed", client.next().getString("type"))
        assertTrue(watch.sent.isEmpty())
    }

    @Test
    fun `events are pushed in the receive tool's form`() {
        val client = Client()
        client.next()
        val events = JSONArray().put(JSONObject().put("type", "message").put("tuples", JSONArray().put(tuple(5))))
        watch.events.put(JSONObject().put("events", events).put("dropped", 2))

        val frame = client.next()
        assertEquals("events", frame.getString("type"))
        assertEquals(2, frame.getInt("dropped"))
        assertEquals(events.toString(), frame.getJSONArray("events").toString())
    }

    @Test
    fun `a stream that loses its place is told and ended`() {
        val client = Client()
        client.next()
        watch.events.put(TAKEN_OVER)
        val frame = client.next()
        assertEquals("closed", frame.getString("type"))
        assertEquals("a newer caller took over.", frame.getString("error"))
        client.awaitClose()
        assertTrue(watch.closed.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `the client leaving closes the stream`() {
        val client = Client()
        client.next()
        client.socket.sendClose(WebSocket.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS)
        assertTrue(watch.closed.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `stopping the server closes the stream`() {
        val client = Client()
        client.next()
        server.stop()
        assertTrue(watch.closed.await(5, TimeUnit.SECONDS))
    }

    // ------------------------------------------------------------- helpers

    private fun tuple(key: Int) = JSONObject().put("key", key).put("type", "string").put("value", "x")

    /** A WebSocket client on the stream route, its text frames queued as they arrive. */
    private inner class Client(query: String = "", bearer: String? = token) : WebSocket.Listener {
        private val frames = LinkedBlockingQueue<String>()
        private val closed = CountDownLatch(1)
        private val partial = StringBuilder()

        val socket: WebSocket = http.newWebSocketBuilder()
            .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
            .buildAsync(URI("ws://127.0.0.1:$port/pebblekit2/send_receive?app=$APP$query"), this)
            .get(5, TimeUnit.SECONDS)

        fun say(message: Any) {
            socket.sendText(message.toString(), true).get(5, TimeUnit.SECONDS)
        }

        fun next() = JSONObject(frames.poll(5, TimeUnit.SECONDS) ?: fail("no frame within 5s"))

        fun awaitClose() = assertTrue(closed.await(5, TimeUnit.SECONDS), "the server did not close the stream")

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            partial.append(data)
            if (last) {
                frames.put(partial.toString())
                partial.clear()
            }
            return super.onText(webSocket, data, last)
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            closed.countDown()
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) = closed.countDown()
    }

    /** A watch that does what the test says and remembers what it was asked. */
    private class FakeWatch : WatchStreams, WatchStream {
        val opened = CopyOnWriteArrayList<Pair<String, Long>>()
        val sent = CopyOnWriteArrayList<JSONArray>()
        val closed = CountDownLatch(1)

        /** Set to have [open] refuse. */
        var refusal: String? = null

        /** One per send to come, null for an ACK; with none left a send is acknowledged. */
        val answers = LinkedBlockingQueue<Optional<String>>()

        /** What [next] hands out, or [TAKEN_OVER]. */
        val events = LinkedBlockingQueue<JSONObject>()

        override fun open(app: String, maxAgeMs: Long): WatchStreams.Opening {
            refusal?.let { return WatchStreams.Opening.Refused(it) }
            opened += app to maxAgeMs
            return WatchStreams.Opening.Ready(this)
        }

        override fun send(tuples: JSONArray): String? {
            sent += tuples
            return answers.poll()?.orElse(null)
        }

        override fun next(): JSONObject? = events.take().takeIf { it !== TAKEN_OVER }

        override fun close() = closed.countDown()
    }

    private operator fun LinkedBlockingQueue<Optional<String>>.plusAssign(answers: List<String?>) {
        answers.forEach { put(Optional.ofNullable(it)) }
    }

    private fun tool(name: String, featureKey: String?, handler: (JSONObject) -> Pair<String, Boolean>) =
        ToolSpec(name, featureKey, JSONObject().put("name", name), handler)

    private fun request(path: String, bearer: String? = token): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        return builder
    }

    private fun body(text: Any) = HttpRequest.BodyPublishers.ofString(text.toString())

    private fun send(builder: HttpRequest.Builder): HttpResponse<String> =
        http.send(builder.build(), HttpResponse.BodyHandlers.ofString())

    private fun rpc(method: String, params: JSONObject? = null) =
        JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params)

    private fun post(message: JSONObject) = send(request("/mcp").POST(body(message)))

    private fun listedTools(): List<String> {
        val listed = JSONObject(post(rpc("tools/list")).body()).getJSONObject("result").getJSONArray("tools")
        return (0 until listed.length()).map { listed.getJSONObject(it).getString("name") }
    }

    private fun call(name: String, arguments: JSONObject = JSONObject()): JSONObject {
        val params = JSONObject().put("name", name).put("arguments", arguments)
        return JSONObject(post(rpc("tools/call", params)).body()).getJSONObject("result")
    }

    private fun text(result: JSONObject) = result.getJSONArray("content").getJSONObject(0).getString("text")

    /** Sends [request] as written, closes the sending side, and returns the whole reply. */
    private fun raw(request: String): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 5_000
        socket.getOutputStream().apply { write(request.toByteArray()); flush() }
        socket.shutdownOutput()
        socket.getInputStream().readBytes().decodeToString()
    }

    private companion object {
        const val APP = "00000000-0000-4000-8000-000000000001"

        /** Put among a [FakeWatch]'s events to have its next read end as taken over. */
        val TAKEN_OVER = JSONObject()
    }

    /** A folder standing in for the phone's file system, with FileStore's put contract. */
    private class FolderFiles(private val root: File) : ServedFiles {
        override fun put(path: String, input: InputStream): JSONObject {
            val file = File(root, path)
            file.parentFile!!.mkdirs()
            try {
                file.outputStream().use { input.copyTo(it) }
            } catch (e: java.io.IOException) {
                file.delete()
                throw e
            }
            return JSONObject().put("name", file.name).put("size", file.length())
        }

        override fun serve(path: String): ServedFile {
            val file = File(root, path)
            if (!file.isFile) throw FileProblem(FileProblem.Kind.NOT_FOUND, "No such file.")
            return ServedFile("application/octet-stream", file.length(), file.inputStream())
        }
    }
}
