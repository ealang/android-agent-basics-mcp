package dev.ealang.phonemcp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import android.view.KeyEvent

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

    /** Matches the watch tools' launch-then-send delay. */
    private const val LAUNCH_WAIT_MS = 2_500L

    /** How long to wait for the session to actually report STATE_PLAYING. */
    private const val CONFIRM_WAIT_MS = 6_000L

    /** How long a cold-started Pocket Casts gets to publish a media session. */
    private const val SESSION_WAIT_MS = 8_000L

    sealed interface Result {
        data object Played : Result
        data object AlreadyPlaying : Result
        /** The press was sent but the session never reported STATE_PLAYING. */
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

    fun play(context: Context): Result =
        withController(context, launchIfMissing = true) { controller ->
            if (isPlaying(controller)) return@withController Result.AlreadyPlaying

            // Bring Pocket Casts to the foreground first — the same overlay
            // exemption the watch tools use to open the clock app. A play
            // command reaching a *backgrounded* Pocket Casts is accepted and
            // then dies: One UI has frozen the process, and Android denies its
            // foreground-service start (which also kills its media
            // notification). With the app on screen the press is proven to
            // work. Pocket Casts treats PLAY as a play/pause toggle, so it is
            // only sent when paused — checked above.
            val launch = context.packageManager.getLaunchIntentForPackage(POCKET_CASTS_PACKAGE)
                ?: return@withController Result.NotInstalled
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(LAUNCH_WAIT_MS)

            controller.dispatchMediaButtonEvent(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY),
            )
            controller.dispatchMediaButtonEvent(
                KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY),
            )

            // Their button handler debounces taps for a moment before acting,
            // then playback has to spin up; poll instead of trusting the send.
            val deadline = System.currentTimeMillis() + CONFIRM_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(500)
                if (isPlaying(controller)) return@withController Result.Played
            }
            Result.NotStarted
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

    /**
     * Resolve Pocket Casts' media session and hand it to [block].
     *
     * With [launchIfMissing], a missing session is not the end of the story:
     * Android reclaims the process after a few hours idle and the session goes
     * with it, leaving nothing to drive until the app runs again. Starting it
     * ourselves and waiting for the session to appear turns "open it once
     * yourself" into something we can do. Read-only callers pass false — asking
     * what is playing should never drag an app onto the user's screen.
     */
    private fun withController(
        context: Context,
        launchIfMissing: Boolean = false,
        block: (MediaController) -> Result,
    ): Result {
        if (!installed(context)) return Result.NotInstalled

        val manager = context.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(context, MediaListenerService::class.java)
        val sessions = try {
            manager.getActiveSessions(listener)
        } catch (e: SecurityException) {
            Log.w(TAG, "Pocket Casts session: notification access not granted", e)
            return Result.NoAccess
        }

        sessions.firstOrNull { it.packageName == POCKET_CASTS_PACKAGE }
            ?.let { return block(it) }

        Log.w(
            TAG,
            "Pocket Casts session: not among active sessions " +
                sessions.map { it.packageName },
        )
        if (!launchIfMissing) return Result.NoSession

        val launch = context.packageManager.getLaunchIntentForPackage(POCKET_CASTS_PACKAGE)
            ?: return Result.NotInstalled
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        val deadline = System.currentTimeMillis() + SESSION_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(500)
            val controller = try {
                manager.getActiveSessions(listener)
                    .firstOrNull { it.packageName == POCKET_CASTS_PACKAGE }
            } catch (e: SecurityException) {
                null
            }
            if (controller != null) return block(controller)
        }
        Log.w(TAG, "Pocket Casts session: none appeared ${SESSION_WAIT_MS}ms after launch")
        return Result.NoSession
    }

    private fun installed(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(POCKET_CASTS_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
