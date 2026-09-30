package dev.ealang.phonemcp

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * The plumbing both listeners share: bearer-token auth, the resilient
 * socket, request logging, and the open /health probe. Subclasses implement
 * [route] for everything past that. This is deliberately not a framework —
 * there are exactly two servers, and this class exists so their security
 * behaviour cannot drift apart, not to make a third one easy.
 */
abstract class AuthedHttpServer(
    bindAddress: String,
    port: Int,
    /**
     * Read per request, not captured at bind: pressing Regenerate must take
     * effect on the very next request, without a server restart. The provider
     * reads SecretStore's in-process cache, so this costs nothing on the
     * request thread.
     */
    private val token: () -> String,
    onDied: () -> Unit,
    /** This server's log; the socket and every request line go here too. */
    protected val log: ServerLog,
    // A null hostname makes NanoHTTPD bind the wildcard address; anything else
    // binds that one address, which is how the server is kept off the LAN.
) : NanoHTTPD(bindAddress.ifBlank { null }, port) {

    init {
        serverSocketFactory = ServerSocketFactory { ResilientServerSocket(log, onDied) }
    }

    /**
     * Set by the subclass on the handling thread, so the log line can name
     * what a request meant (the MCP method, the webhook trigger) instead of
     * just "POST /mcp". NanoHTTPD keeps one session on one thread, which is
     * what makes a ThreadLocal sound here.
     */
    protected val detail = ThreadLocal<String?>()

    final override fun serve(session: IHTTPSession): Response {
        detail.remove()
        log.bump("requests")
        defaultToUtf8(session)
        val response = handle(session)
        val what = detail.get()?.let { " $it" } ?: ""
        // Every request, at debug: the wire-level record underneath whatever
        // higher-level line the handler wrote about the same exchange.
        log.debug(
            "${session.method} ${session.uri}$what → ${response.status.description}",
            "from ${session.remoteIpAddress}",
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

        // Unknown paths 404 before auth is checked — a probe of the wrong
        // port learns "not here" rather than "token needed", same as before
        // the split.
        if (!serves(uri)) {
            return json(Response.Status.NOT_FOUND, JSONObject().put("error", "Not found: $uri"))
        }

        if (!authorized(session)) {
            log.bump("rejected")
            log.warn("rejected an unauthorized request", "from ${session.remoteIpAddress}")
            return json(Response.Status.UNAUTHORIZED, JSONObject().put("error", "Unauthorized"))
        }

        return route(uri, session)
    }

    /** Whether [route] has anything at this path. */
    protected abstract fun serves(uri: String): Boolean

    /** The server's own paths. Auth has passed; [serves] said yes; uri is normalized. */
    protected abstract fun route(uri: String, session: IHTTPSession): Response

    /**
     * NanoHTTPD decodes request bodies — postData and multipart text fields
     * alike — with the charset parameter of the Content-Type header, and
     * falls back to ASCII when there is none, which mangles every multi-byte
     * character into one '?' per byte. Nothing talking to these servers
     * sends anything but UTF-8 (JSON is UTF-8 by definition, RFC 8259), so
     * a missing charset is declared to be UTF-8 here, once, for every
     * endpoint present and future. An explicit charset is left alone.
     */
    private fun defaultToUtf8(session: IHTTPSession) {
        val contentType = session.headers["content-type"] ?: return
        if (!contentType.contains("charset", ignoreCase = true)) {
            session.headers["content-type"] = "$contentType; charset=utf-8"
        }
    }

    private fun authorized(session: IHTTPSession): Boolean {
        // NanoHTTPD lowercases header names.
        val header = session.headers["authorization"] ?: return false
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

    protected fun json(status: Response.Status, body: JSONObject): Response =
        newFixedLengthResponse(status, "application/json", body.toString())
}
