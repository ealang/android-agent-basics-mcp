package dev.ealang.phonemcp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log

/**
 * Drives agent-voice-gateway — the user's own Telegram voice client — over the
 * standard companion-app interface (`docs/companion-apps.md`): a launch intent
 * starts a session, the automation ContentProvider answers status and stops.
 *
 * [start] is fire-and-forget by design. We hold the background-launch
 * privilege, the gateway does not, so opening its entry activity is our job —
 * and an activity launch has no return path. The gateway resolves the
 * conversation, shows itself over the keyguard to earn the microphone, and
 * logs anything it refuses. Nothing is claimed here beyond "asked"; whether a
 * session came up is read from [status] afterwards.
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

    /** The gateway's exported trampoline, and the extra it reads. */
    private const val START_ACTIVITY = "$PACKAGE.AutomationStartActivity"
    private const val EXTRA_CONVERSATION = "conversation"

    // Cold-start retries. The gateway process is being started by our own
    // request, so the wait is short and the first retry usually succeeds —
    // measured at ~200 ms on a Pixel.
    private const val PROVIDER_ATTEMPTS = 4
    private const val PROVIDER_RETRY_INTERVAL_MS = 350L

    private val STATUS: Uri = Uri.parse("content://$AUTHORITY/status")
    private val CONVERSATIONS: Uri = Uri.parse("content://$AUTHORITY/conversations")

    data class Conversation(val name: String, val unread: Int, val active: Boolean)

    sealed interface Result {
        /** The request was handed over. Not a claim that it took effect. */
        data class Accepted(val conversation: String?) : Result
        /** Starting an activity from the background needs the overlay grant. */
        data object NeedsOverlayPermission : Result
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
     * Open the gateway on [conversation] — a name, an id, or a unique
     * substring, resolved on their side against what the user actually says.
     *
     * The launch needs [Clock.canStartFromBackground]: without the overlay
     * grant Android drops a background activity start in silence, which is
     * worth naming rather than reporting as a session that never appeared.
     * Everything past that point is theirs.
     */
    fun start(context: Context, conversation: String): Result {
        if (!Clock.canStartFromBackground(context)) return Result.NeedsOverlayPermission

        return try {
            context.startActivity(
                Intent()
                    .setClassName(PACKAGE, START_ACTIVITY)
                    .putExtra(EXTRA_CONVERSATION, conversation)
                    // CLEAR_TASK for the same reason as Pocket Casts: with the
                    // gateway already running, its activity would take the
                    // intent through onNewIntent, which arrives on the resume
                    // path the keyguard blocks. Clearing the task forces the
                    // cold-start path, which is the one proven to work locked.
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            Log.i(TAG, "Voice gateway: launched a session for \"$conversation\"")
            Result.Accepted(conversation)
        } catch (e: Exception) {
            // The component is missing or blocked — the app is not there in
            // any usable sense, which is the one thing worth reporting.
            Log.w(TAG, "Voice gateway: could not launch $START_ACTIVITY", e)
            Result.NotAvailable
        }
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

    fun status(context: Context): Result {
        val row = withProvider { context.contentResolver.query(STATUS, null, null, null, null) }
            ?: return Result.NotAvailable

        return row.use { cursor ->
            if (!cursor.moveToFirst()) return Result.NotAvailable
            // recording_state and playback_state are deliberately not in the
            // answer the agent reads — they name internal states it cannot act
            // on differently. They are the first thing a human wants when a
            // session misbehaves, so they go here instead.
            Log.i(
                TAG,
                "Voice gateway status: listening=${cursor.int("listening")} " +
                    "mic_secured=${cursor.int("mic_secured")} " +
                    "paused=${cursor.int("paused")} " +
                    "recording=${cursor.string("recording_state")} " +
                    "playback=${cursor.string("playback_state")} " +
                    "queue=${cursor.int("queue_depth")}",
            )
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
