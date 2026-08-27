package dev.ealang.phonemcp

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Build
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
 * object does the whole confirmation dance internally — sending the media
 * play command the gateway's headless route waits for, then polling status
 * until the gateway itself says the microphone service really started —
 * and reports one outcome.
 */
object Voice {
    private const val TAG = "phone-mcp"

    const val PACKAGE = "com.agentvoicegateway.app"
    const val AUTHORITY = "com.agentvoicegateway.app.automation"

    /**
     * Tag on the gateway's dedicated automation MediaSession. Sending play()
     * to it (and never to the gateway's ordinary playback session) earns the
     * gateway a ~10 s temp allowlist in which Android will accept a
     * microphone foreground service start from the background — the headless
     * start route. The gateway reports mode "media_window" in the start reply
     * when it is waiting for this.
     */
    private const val AUTOMATION_SESSION_TAG = "automation-start"
    private const val MODE_MEDIA_WINDOW = "media_window"

    // The armed automation session appears asynchronously after the start
    // reply returns, so finding it gets a few retries.
    private const val SESSION_FIND_ATTEMPTS = 6
    private const val SESSION_FIND_INTERVAL_MS = 400L

    // How long to wait for the gateway to confirm a live session. Covers the
    // headless route (a second or two) and the gateway's own fallback of
    // opening itself visibly (its window timeout plus an app launch).
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
     * Ask the gateway to start a session, then see it through: if the gateway
     * armed its headless media-command window, send the play command that
     * opens it, and either way poll until the gateway confirms the microphone
     * service actually started (or the timeout passes — [Result.Accepted]
     * then honestly claims nothing beyond acceptance).
     */
    fun start(context: Context, conversation: String): Result {
        val reply = try {
            // The authority overload, not the Uri one: the latter is API 29+.
            context.contentResolver.call(AUTHORITY, "start", conversation, null)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Voice gateway: nothing answers at $AUTHORITY", e)
            return Result.NotAvailable
        } ?: return Result.NotAvailable

        if (!reply.getBoolean("ok")) {
            return Result.Refused(reply.getString("error") ?: "no reason given")
        }
        val name = reply.getString("conversation_name")
        val mode = reply.getString("mode")
        Log.i(TAG, "Voice gateway accepted start for ${name ?: conversation} (mode=${mode ?: "not reported"})")
        if (mode == MODE_MEDIA_WINDOW) sendAutomationPlay(context)
        return if (awaitSessionUp(context)) Result.Confirmed(name) else Result.Accepted(name)
    }

    fun stop(context: Context): Result {
        val reply = try {
            context.contentResolver.call(AUTHORITY, "stop", null, null)
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

    /**
     * Send play() to the gateway's armed automation media session. Best
     * effort on purpose: if the session never appears (arming is async, or
     * notification access is missing, or the tag can't be read pre-API-29)
     * the gateway falls back to opening itself visibly after its own window
     * timeout, so there is nothing for the caller to do differently — which
     * happened is in the log.
     */
    private fun sendAutomationPlay(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // MediaController.getTag() is API 29+, and without the tag we
            // cannot tell the automation session from the gateway's playback
            // session — poking the wrong one would toggle audio.
            Log.i(TAG, "Voice gateway: cannot identify the automation session pre-API-29; leaving the gateway to fall back visibly")
            return
        }
        repeat(SESSION_FIND_ATTEMPTS) { attempt ->
            val sessions = mediaSessions(context)
            if (sessions == null) {
                Log.w(TAG, "Voice gateway: no notification access, cannot send the media play command")
                return
            }
            val controller = sessions.firstOrNull {
                it.packageName == PACKAGE && it.tag == AUTOMATION_SESSION_TAG
            }
            if (controller != null) {
                controller.transportControls.play()
                Log.i(TAG, "Voice gateway: sent media play to the automation session (attempt ${attempt + 1})")
                return
            }
            SystemClock.sleep(SESSION_FIND_INTERVAL_MS)
        }
        Log.w(TAG, "Voice gateway: automation media session never appeared; gateway will fall back visibly on its own")
    }

    /** Null means notification access has not been granted. */
    private fun mediaSessions(context: Context): List<MediaController>? {
        val manager = context.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(context, MediaListenerService::class.java)
        return try {
            manager.getActiveSessions(listener)
        } catch (e: SecurityException) {
            Log.w(TAG, "Media sessions: notification access not granted", e)
            null
        }
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
