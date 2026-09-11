package dev.ealang.phonemcp.webhook

import android.util.Log
import dev.ealang.phonemcp.AuthedHttpServer
import org.json.JSONObject
import java.io.File

/**
 * The Pebble Index webhook receiver — not an MCP server, and deliberately no
 * longer named like one. Its one caller is CoreApp on this same phone, so it
 * listens on loopback only and its whole job is to get the recording onto
 * flash and answer fast: 200 means "durably queued", never "delivered".
 * See `docs/webhook-pipeline.md` §2.
 */
class PebbleWebhookServer(
    token: String,
    onDied: () -> Unit,
    /**
     * Durably admit one recording — the orchestrator's `store.enqueue` +
     * `worker.wake()`, handed in as a lambda so the server knows nothing
     * about the pipeline. Throwing means "could not queue", answered with a
     * 500 so the watch retries.
     */
    private val enqueue: (audio: File?, meta: Meta) -> Unit,
) : AuthedHttpServer(BIND, PORT, token, onDied) {

    companion object {
        private const val TAG = "PebbleWebhookServer"
        const val PATH = "/webhook"

        /**
         * Hardcoded to loopback: the caller is on this same phone, so the
         * listener needs no route from anywhere else and must not inherit
         * whatever the user typed into the MCP bind field — a blanked field
         * means "every interface", and a recording inbox open to the network
         * is exactly the mistake this removes.
         */
        const val BIND = "127.0.0.1"
        const val PORT = 8911
    }

    override fun serves(uri: String): Boolean = uri == PATH

    override fun route(uri: String, session: IHTTPSession): Response {
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
            enqueue(audio, meta)
            json(Response.Status.OK, JSONObject().put("status", "queued"))
        } catch (e: Exception) {
            Log.e(TAG, "Could not enqueue a webhook recording", e)
            json(
                Response.Status.INTERNAL_ERROR,
                JSONObject().put("error", "could not queue the recording"),
            )
        }
    }
}
