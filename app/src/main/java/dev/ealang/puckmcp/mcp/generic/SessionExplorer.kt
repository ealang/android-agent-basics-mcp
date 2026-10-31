package dev.ealang.puckmcp.mcp.generic

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import dev.ealang.puckmcp.MediaListenerService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Experimental: the two media avenues that do not go through a
 * MediaBrowserService, added because the browser route's survey
 * found apps it cannot reach.
 *
 * **Active sessions** — [MediaSessionManager.getActiveSessions], the
 * notification-listener API: every live session on the phone,
 * observable and drivable, including apps whose browser service refuses
 * strangers (YouTube Music, Audible). The limit is in the name — a session
 * exists while the app is playing or paused-but-alive, so this route can
 * steer and resume but never cold-start.
 *
 * **Media-button events** — [AudioManager.dispatchMediaKeyEvent], the
 * mechanism a car head unit or headset rides: the system converts AVRCP
 * into KEYCODE_MEDIA_* events and routes them to the most recent media
 * session, cold-starting that app's media-button receiver if it died. Apps
 * honor these because they arrive from the system rather than a stranger's
 * controller. The cost is the same as the car's: no target can be named —
 * it is "resume whatever was last", and which app that is cannot be chosen.
 * Whether it cold-starts in practice, per app, is exactly the experiment.
 *
 * Threading matches [MediaExplorer]: work on the main handler, the server
 * thread blocks on a latch, commands settle [MediaExplorer.PLAY_SETTLE_MS]
 * before the state is read back.
 */
object SessionExplorer {

    private const val TAG = "puck-mcp"

    private val main = Handler(Looper.getMainLooper())

    private const val NO_ACCESS =
        "FAILED: this app does not have notification access, which is what " +
            "exposes media sessions. Ask the user to open Puck MCP and tap " +
            "\"Allow notification access\"."

    /** Null means notification access has not been granted. */
    private fun controllers(context: Context): List<MediaController>? = try {
        context.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(ComponentName(context, MediaListenerService::class.java))
    } catch (e: SecurityException) {
        Log.w(TAG, "sessions: notification access not granted", e)
        null
    }

    fun list(context: Context): String {
        val sessions = controllers(context) ?: return NO_ACCESS
        if (sessions.isEmpty()) {
            return "No active media sessions. A session exists while its app is " +
                "playing or paused-but-alive; an app that was swiped away or " +
                "never started has none."
        }
        return describeAll(context, sessions)
    }

    /**
     * A transport command against a live session, matched by app name or
     * package among whatever sessions exist right now. Same grammar as the
     * browser route ([MediaExplorer.applyAction]); the queue-edit specs are
     * the one part that cannot ride this route (they need the compat
     * protocol, which only a browser connection provides).
     */
    fun command(context: Context, given: String, action: String): String {
        val sessions = controllers(context) ?: return NO_ACCESS
        if (sessions.isEmpty()) {
            return "FAILED: no active media sessions to command — this route can " +
                "only drive an app whose session is already alive."
        }
        val needle = given.trim().lowercase()
        val matches = sessions.filter {
            it.packageName.lowercase().contains(needle) ||
                label(context, it.packageName).lowercase().contains(needle)
        }
        val controller = when {
            needle.isEmpty() -> return "FAILED: give an app name. Active sessions: ${names(context, sessions)}"
            matches.size == 1 -> matches.first()
            matches.isEmpty() ->
                return "FAILED: no active session matches \"$given\". Active sessions: ${names(context, sessions)}"
            else -> return "FAILED: \"$given\" is ambiguous among active sessions: ${names(context, matches)}"
        }
        val appLabel = label(context, controller.packageName)

        var answer = "no answer within ${MediaExplorer.PLAY_SETTLE_MS / 1000 + 3}s"
        val latch = CountDownLatch(1)
        main.post {
            var finished = false
            fun done(text: String) {
                if (finished) return
                finished = true
                answer = text
                latch.countDown()
            }
            try {
                if (!MediaExplorer.applyAction(appLabel, controller, action, ::done)) {
                    Log.i(TAG, "session: ${controller.packageName} $action")
                    main.postDelayed(
                        { done(MediaExplorer.readBack(appLabel, controller, "$action (session route)")) },
                        MediaExplorer.PLAY_SETTLE_MS,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "session: ${controller.packageName} $action", e)
                done("$appLabel's session threw: ${e.message}")
            }
        }
        latch.await(MediaExplorer.PLAY_SETTLE_MS + 3_000, TimeUnit.MILLISECONDS)
        return answer
    }

    /**
     * The friendly names for the media KEYCODEs, shared with send_intent's
     * key_event extra so the two spellings never diverge.
     */
    internal fun mediaKeyCode(name: String): Int? = when (name.trim().lowercase()) {
        "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
        "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
        "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
        "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
        "fast_forward" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
        "rewind" -> KeyEvent.KEYCODE_MEDIA_REWIND
        else -> null
    }

    /**
     * Dispatch one media-button press as a down/up pair, then read back
     * whatever sessions exist a beat later — the routing happened inside the
     * system, so the read-back is the only visibility. Works without the
     * notification grant (the dispatch needs nothing); the read-back is what
     * the grant buys.
     */
    fun key(context: Context, name: String): String {
        val code = mediaKeyCode(name)
            ?: return "FAILED: unknown key \"$name\" — use play, pause, play_pause, " +
                "next, previous, stop, fast_forward, or rewind."
        val audio = context.getSystemService(AudioManager::class.java)

        var answer = "no answer within ${MediaExplorer.PLAY_SETTLE_MS / 1000 + 3}s"
        val latch = CountDownLatch(1)
        main.post {
            try {
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            } catch (e: Exception) {
                Log.w(TAG, "media key: $name", e)
                answer = "FAILED: dispatching the key event threw."
                latch.countDown()
                return@post
            }
            Log.i(TAG, "media key: $name dispatched")
            main.postDelayed({
                val sessions = controllers(context)
                answer = "Sent the $name media-button event. " + when {
                    sessions == null ->
                        "Without notification access the outcome cannot be read " +
                            "back — grant it to see which session reacted."
                    sessions.isEmpty() ->
                        "${MediaExplorer.PLAY_SETTLE_MS / 1000}s later there is " +
                            "still no active media session — nothing picked it up."
                    else ->
                        "${MediaExplorer.PLAY_SETTLE_MS / 1000}s later the active " +
                            "sessions:\n\n" + describeAll(context, sessions)
                }
                latch.countDown()
            }, MediaExplorer.PLAY_SETTLE_MS)
        }
        latch.await(MediaExplorer.PLAY_SETTLE_MS + 3_000, TimeUnit.MILLISECONDS)
        return answer
    }

    private fun describeAll(context: Context, sessions: List<MediaController>): String =
        sessions.joinToString("\n\n") { c ->
            "${label(context, c.packageName)} (${c.packageName}): " +
                MediaExplorer.sessionState(c)
        }

    private fun names(context: Context, sessions: List<MediaController>): String =
        sessions.joinToString(", ") { label(context, it.packageName) }

    private fun label(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
    } catch (e: Exception) {
        pkg
    }
}
