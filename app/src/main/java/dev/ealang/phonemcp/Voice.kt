package dev.ealang.phonemcp

import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * Drives agent-voice-gateway — the user's own Telegram voice client — through
 * the automation ContentProvider it exports for exactly this purpose.
 *
 * Commands answer synchronously with what is knowable on arrival (does the
 * conversation resolve, is there a session to stop) and hand the actual work
 * to the app. Whether a session then comes up is asynchronous and can be
 * refused by Android without an error, so [start] deliberately does not wait
 * around: [status] is how anyone finds out.
 */
object Voice {
    private const val TAG = "phone-mcp"

    const val PACKAGE = "com.agentvoicegateway.app"
    const val AUTHORITY = "com.agentvoicegateway.app.automation"

    private val STATUS: Uri = Uri.parse("content://$AUTHORITY/status")
    private val CONVERSATIONS: Uri = Uri.parse("content://$AUTHORITY/conversations")

    data class Conversation(val name: String, val unread: Int, val active: Boolean)

    sealed interface Result {
        /** The app took the request. Nothing is claimed about the outcome. */
        data class Accepted(val conversation: String?) : Result
        /** The app answered, and said no — the message is theirs, and specific. */
        data class Refused(val message: String) : Result
        data class Status(
            /** Presence of a session is decided by the id, never by the name:
             *  the name is a lookup that can come back null. */
            val activeConversationId: String?,
            val activeConversation: String?,
            val listening: Boolean,
            val paused: Boolean,
            val recordingState: String,
            val playbackState: String,
            val queueDepth: Int,
            val conversations: List<Conversation>,
        ) : Result
        /** Not installed, or its process refused to start. */
        data object NotAvailable : Result
    }

    fun start(context: Context, conversation: String): Result =
        command(context, "start", conversation)

    fun stop(context: Context): Result = command(context, "stop", null)

    private fun command(context: Context, method: String, arg: String?): Result {
        val reply = try {
            // The authority overload, not the Uri one: the latter is API 29+.
            context.contentResolver.call(AUTHORITY, method, arg, null)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Voice gateway: nothing answers at $AUTHORITY", e)
            return Result.NotAvailable
        } ?: return Result.NotAvailable

        return if (reply.getBoolean("ok")) {
            Result.Accepted(reply.getString("conversation_name"))
        } else {
            Result.Refused(reply.getString("error") ?: "no reason given")
        }
    }

    fun status(context: Context): Result {
        val row = try {
            context.contentResolver.query(STATUS, null, null, null, null)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Voice gateway: nothing answers at $AUTHORITY", e)
            return Result.NotAvailable
        } ?: return Result.NotAvailable

        return row.use { cursor ->
            if (!cursor.moveToFirst()) return Result.NotAvailable
            Result.Status(
                activeConversationId = cursor.string("active_conversation_id"),
                activeConversation = cursor.string("active_conversation_name"),
                listening = cursor.int("listening") == 1,
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
