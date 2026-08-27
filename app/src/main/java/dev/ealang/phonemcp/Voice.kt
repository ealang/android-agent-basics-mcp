package dev.ealang.phonemcp

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log

/**
 * Drives agent-voice-gateway — the user's own Telegram voice client — through
 * the automation ContentProvider it exports for exactly this purpose.
 *
 * Commands answer synchronously with what is knowable on arrival (does the
 * conversation resolve, is there a session to stop) and hand the actual work
 * to the app. Whether a session then comes up is asynchronous and can be
 * refused by Android without an error, so after an accepted [start] this
 * object polls status until the gateway itself says the microphone service
 * really started, and reports one outcome.
 *
 * Every provider access goes through [withProvider], which retries: the
 * gateway's process is routinely dead between sessions, and the first request
 * to reach a cold one can find the provider published before the app object
 * behind it is ready. That is indistinguishable here from "not installed",
 * so it is retried rather than reported.
 */
object Voice {
    private const val TAG = "phone-mcp"

    const val PACKAGE = "com.agentvoicegateway.app"
    const val AUTHORITY = "com.agentvoicegateway.app.automation"

    // Cold-start retries. The gateway process is being started by our own
    // request, so the wait is short and the first retry usually succeeds —
    // measured at ~200 ms on a Pixel.
    private const val PROVIDER_ATTEMPTS = 4
    private const val PROVIDER_RETRY_INTERVAL_MS = 350L

    // How long to wait for the gateway to confirm a live session: it brings
    // its own screen up to earn the microphone, so this covers an app launch
    // on a cold process plus the foreground-service grant.
    private const val CONFIRM_TIMEOUT_MS = 15_000L
    private const val CONFIRM_POLL_INTERVAL_MS = 1_000L

    private val STATUS: Uri = Uri.parse("content://$AUTHORITY/status")
    private val CONVERSATIONS: Uri = Uri.parse("content://$AUTHORITY/conversations")

    data class Conversation(val name: String, val unread: Int, val active: Boolean)

    sealed interface Result {
        /** The gateway confirmed the session: microphone service running and listening. */
        data class Confirmed(val conversation: String?) : Result
        /** The app took the request, but never confirmed a live session. */
        data class Accepted(val conversation: String?) : Result
        /** The app answered, and said no — the message is theirs, and specific. */
        data class Refused(val message: String) : Result
        data class Status(
            /** Presence of a session is decided by the id, never by the name:
             *  the name is a lookup that can come back null. */
            val activeConversationId: String?,
            val activeConversation: String?,
            val listening: Boolean,
            /** The gateway's own "startForeground(MICROPHONE) succeeded"
             *  signal. [listening] without this means a start is still in
             *  flight or Android refused the microphone. */
            val micSecured: Boolean,
            val paused: Boolean,
            val recordingState: String,
            val playbackState: String,
            val queueDepth: Int,
            val conversations: List<Conversation>,
        ) : Result
        /** Not installed, or its process refused to start. */
        data object NotAvailable : Result
    }

    /**
     * Ask the gateway to start a session, then see it through: poll until the
     * gateway confirms the microphone service actually started (or the
     * timeout passes — [Result.Accepted] then honestly claims nothing beyond
     * acceptance).
     */
    fun start(context: Context, conversation: String): Result {
        // The authority overload, not the Uri one: the latter is API 29+.
        val reply = withProvider { context.contentResolver.call(AUTHORITY, "start", conversation, null) }
            ?: return Result.NotAvailable

        if (!reply.getBoolean("ok")) {
            return Result.Refused(reply.getString("error") ?: "no reason given")
        }
        val name = reply.getString("conversation_name")
        Log.i(TAG, "Voice gateway accepted start for ${name ?: conversation}")
        return if (awaitSessionUp(context)) Result.Confirmed(name) else Result.Accepted(name)
    }

    fun stop(context: Context): Result {
        val reply = withProvider { context.contentResolver.call(AUTHORITY, "stop", null, null) }
            ?: return Result.NotAvailable

        return if (reply.getBoolean("ok")) {
            Result.Accepted(reply.getString("conversation_name"))
        } else {
            Result.Refused(reply.getString("error") ?: "no reason given")
        }
    }

    /**
     * Run a provider access, retrying a null or unresolvable authority.
     *
     * Both mean the same thing to us and neither is reliably "the app is
     * gone": the gateway's process is normally dead between sessions, and our
     * own request is what starts it. Until that process is far enough along,
     * the authority may not resolve (IllegalArgumentException) or the provider
     * may answer before the app object behind it is built (null). Both clear
     * within a few hundred milliseconds. Only a run of failures is reported as
     * unavailable, so a cold gateway does not look uninstalled.
     */
    private fun <T> withProvider(block: () -> T?): T? {
        repeat(PROVIDER_ATTEMPTS) { attempt ->
            val result = try {
                block()
            } catch (e: IllegalArgumentException) {
                null
            }
            if (result != null) {
                if (attempt > 0) Log.i(TAG, "Voice gateway answered on attempt ${attempt + 1} (process was cold)")
                return result
            }
            if (attempt < PROVIDER_ATTEMPTS - 1) SystemClock.sleep(PROVIDER_RETRY_INTERVAL_MS)
        }
        Log.w(TAG, "Voice gateway: no answer from $AUTHORITY after $PROVIDER_ATTEMPTS attempts")
        return null
    }

    /**
     * True once the gateway reports both `listening` and `mic_secured`.
     * `listening` alone is transiently true even for a start Android is about
     * to refuse (the gateway flips it before the OS has ruled on the
     * microphone service); `mic_secured` is the service's own
     * startForeground-succeeded signal.
     */
    private fun awaitSessionUp(context: Context): Boolean {
        val deadline = SystemClock.elapsedRealtime() + CONFIRM_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val row = try {
                context.contentResolver.query(STATUS, null, null, null, null)
            } catch (e: IllegalArgumentException) {
                null
            }
            val up = row?.use { cursor ->
                cursor.moveToFirst() &&
                    cursor.int("listening") == 1 &&
                    cursor.int("mic_secured") == 1
            } ?: false
            if (up) {
                Log.i(TAG, "Voice gateway: session confirmed (listening + mic_secured)")
                return true
            }
            SystemClock.sleep(CONFIRM_POLL_INTERVAL_MS)
        }
        Log.w(TAG, "Voice gateway: session not confirmed within ${CONFIRM_TIMEOUT_MS / 1000}s")
        return false
    }

    fun status(context: Context): Result {
        val row = withProvider { context.contentResolver.query(STATUS, null, null, null, null) }
            ?: return Result.NotAvailable

        return row.use { cursor ->
            if (!cursor.moveToFirst()) return Result.NotAvailable
            Result.Status(
                activeConversationId = cursor.string("active_conversation_id"),
                activeConversation = cursor.string("active_conversation_name"),
                listening = cursor.int("listening") == 1,
                micSecured = cursor.int("mic_secured") == 1,
                paused = cursor.int("paused") == 1,
                recordingState = cursor.string("recording_state") ?: "unknown",
                playbackState = cursor.string("playback_state") ?: "unknown",
                queueDepth = cursor.int("queue_depth"),
                conversations = conversations(context),
            )
        }
    }

    private fun conversations(context: Context): List<Conversation> {
        val cursor = try {
            context.contentResolver.query(CONVERSATIONS, null, null, null, null)
        } catch (e: IllegalArgumentException) {
            return emptyList()
        } ?: return emptyList()

        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(
                        Conversation(
                            name = it.string("name") ?: continue,
                            unread = it.int("unread"),
                            active = it.int("active") == 1,
                        ),
                    )
                }
            }
        }
    }

    private fun android.database.Cursor.string(column: String): String? =
        getColumnIndex(column).takeIf { it >= 0 }?.let { getString(it) }

    private fun android.database.Cursor.int(column: String): Int =
        getColumnIndex(column).takeIf { it >= 0 }?.let { getInt(it) } ?: 0
}
