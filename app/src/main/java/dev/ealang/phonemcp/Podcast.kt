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

    /** How long to wait for the session to actually report STATE_PLAYING. */
    private const val CONFIRM_WAIT_MS = 6_000L

    /** How long a cold-started Pocket Casts gets to publish a media session. */
    private const val SESSION_WAIT_MS = 8_000L

    /**
     * Pocket Casts reads this as "an assistant opened us with no other
     * instruction", which it takes to mean the user wants to play.
     */
    private const val ASSISTANT_EXTRA = "extra_accl_intent"

    sealed interface Result {
        data object Played : Result
        data object AlreadyPlaying : Result
        /** Pocket Casts was asked to play but never reported STATE_PLAYING. */
        data object NotStarted : Result
        data class NowPlaying(
            val podcast: String?,
            val episode: String?,
            val positionMs: Long,
            val durationMs: Long,
            val playing: Boolean,
        ) : Result
        data object NotInstalled : Result
        /** Notification access has not been granted to MediaListenerService. */
        data object NoAccess : Result
        /** Installed, but no media session is active (app not started or killed). */
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
     */
    fun play(context: Context): Result {
        if (!installed(context)) return Result.NotInstalled
        val sessions = sessions(context) ?: return Result.NoAccess
        val existing = sessions.firstOrNull { it.packageName == POCKET_CASTS_PACKAGE }
        if (existing != null && isPlaying(existing)) return Result.AlreadyPlaying

        val launch = context.packageManager.getLaunchIntentForPackage(POCKET_CASTS_PACKAGE)
            ?: return Result.NotInstalled
        context.startActivity(
            launch.addFlags(
                // CLEAR_TOP|SINGLE_TOP is what makes this work when Pocket Casts
                // is already running. Their MainActivity is singleTop, and a
                // plain launcher intent aimed at an existing task is treated as
                // "the user tapped the icon again": the task is resumed and the
                // intent is never delivered, so onNewIntent — and our extra —
                // are never seen. These flags force the delivery instead.
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ).putExtra(ASSISTANT_EXTRA, true),
        )

        // A cold app has to start before it can publish a session, so allow for
        // both that and the spin-up of playback itself.
        val deadline = System.currentTimeMillis() + SESSION_WAIT_MS + CONFIRM_WAIT_MS
        var appeared = false
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(500)
            val controller = sessions(context)
                ?.firstOrNull { it.packageName == POCKET_CASTS_PACKAGE } ?: continue
            appeared = true
            if (isPlaying(controller)) return Result.Played
        }
        Log.w(TAG, "Pocket Casts play: sessionAppeared=$appeared, never played")
        return if (appeared) Result.NotStarted else Result.NoSession
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
        if (!installed(context)) return Result.NotInstalled
        val sessions = sessions(context) ?: return Result.NoAccess
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

    private fun installed(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(POCKET_CASTS_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
