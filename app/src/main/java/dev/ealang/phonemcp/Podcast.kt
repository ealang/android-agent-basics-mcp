package dev.ealang.phonemcp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log

/**
 * Drives Pocket Casts through its media session via
 * [MediaSessionManager.getActiveSessions] — the notification-listener API.
 *
 * Every other route is dead on a real phone: its broadcast receivers are
 * silently dropped, and its legacy browser service rejects callers not on a
 * signature whitelist (Android Auto, Assistant, ...) in release builds. Active
 * sessions bypass all of that, but require the user to grant our
 * [MediaListenerService] notification access once in Settings.
 */
object Podcast {
    private const val TAG = "phone-mcp"

    const val POCKET_CASTS_PACKAGE = "au.com.shiftyjelly.pocketcasts"

    /**
     * Pocket Casts reads this as "an assistant opened us with no other
     * instruction", which it takes to mean the user wants to play.
     */
    private const val ASSISTANT_EXTRA = "extra_accl_intent"

    sealed interface Result {
        data object Started : Result
        data object AlreadyPlaying : Result
        data class NowPlaying(
            val podcast: String?,
            val episode: String?,
            val positionMs: Long,
            val durationMs: Long,
            val playing: Boolean,
        ) : Result
        /** The package went missing between the preflight and the launch. */
        data object NotInstalled : Result
        /**
         * No media session is active. Only [nowPlaying] reports this — it is
         * the ordinary idle state, not a fault: Pocket Casts publishes a
         * session only once playback has been prepared, and the system
         * reclaims it when the app sits idle.
         */
        data object NoSession : Result
    }

    /**
     * Ask Pocket Casts to start playback itself, rather than pressing play at
     * it. Its MainActivity reads `extra_accl_intent` as "an assistant opened us
     * with no further instruction" and calls playQueue().
     *
     * This is strictly better than dispatching a media button. A press needs a
     * session to already exist (Pocket Casts only mints one once playback is
     * prepared — merely launching the app does not) and needs the app visibly
     * on screen, which the keyguard prevents. Playback started by Pocket Casts
     * from its own foreground is subject to neither: cold and locked both work.
     *
     * Nothing is waited for afterwards. Playback starting is something the user
     * hears within a second or two, so a poll here would buy the caller a
     * confirmation of something already obvious, at the cost of every failed
     * call blocking for the length of the timeout.
     */
    fun play(context: Context): Result {
        // A best-effort peek, not a precondition: without notification access
        // there is no session to read, and relaunching an app that is already
        // playing is harmless enough not to demand the grant for it.
        val existing = sessions(context)?.firstOrNull { it.packageName == POCKET_CASTS_PACKAGE }
        if (existing != null && isPlaying(existing)) return Result.AlreadyPlaying

        // Preflight has already found the package, so this is only ever null if
        // it went away in between. Guarded rather than forced, because the
        // alternative to a null here is the server thread dying.
        val launch = context.packageManager.getLaunchIntentForPackage(POCKET_CASTS_PACKAGE)
            ?: return Result.NotInstalled
        context.startActivity(
            launch.addFlags(
                // CLEAR_TASK is what makes this work when Pocket Casts is
                // already running. Their MainActivity reads our extra from
                // handleIntent, which runs from onCreate and from onNewIntent —
                // and onNewIntent arrives on the resume path, which the
                // keyguard blocks. Tearing the task down forces a fresh
                // onCreate, the same path a cold start takes, and that one is
                // proven to work from lock.
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            ).putExtra(ASSISTANT_EXTRA, true),
        )

        Log.i(TAG, "Pocket Casts: asked to play")
        return Result.Started
    }

    private fun isPlaying(controller: MediaController): Boolean =
        controller.playbackState?.state == PlaybackState.STATE_PLAYING

    fun nowPlaying(context: Context): Result =
        withController(context) { controller ->
            val metadata = controller.metadata
            Result.NowPlaying(
                podcast = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM)
                    ?: metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST),
                episode = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE),
                positionMs = controller.playbackState?.position ?: -1,
                durationMs = metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: -1,
                playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING,
            )
        }

    private fun withController(
        context: Context,
        block: (MediaController) -> Result,
    ): Result {
        // Preflight has already established both the app and the grant, so an
        // empty list here means nothing is playing rather than nothing is set up.
        val sessions = sessions(context) ?: emptyList()
        val controller = sessions.firstOrNull { it.packageName == POCKET_CASTS_PACKAGE }
            ?: run {
                Log.w(
                    TAG,
                    "Pocket Casts session: not among active sessions " +
                        sessions.map { it.packageName },
                )
                return Result.NoSession
            }
        return block(controller)
    }

    /** Null means notification access has not been granted. */
    private fun sessions(context: Context): List<MediaController>? {
        val manager = context.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(context, MediaListenerService::class.java)
        return try {
            manager.getActiveSessions(listener)
        } catch (e: SecurityException) {
            Log.w(TAG, "Media sessions: notification access not granted", e)
            null
        }
    }

    /** Whether notification access is in place, which is what exposes sessions. */
    fun hasSessionAccess(context: Context): Boolean = sessions(context) != null
}
