package dev.ealang.phonemcp

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
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

    sealed interface Result {
        data object Played : Result
        data object AlreadyPlaying : Result
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
        withController(context) { controller ->
            if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                return@withController Result.AlreadyPlaying
            }
            // System-level media key dispatch — the same path as a headset
            // button, with the OS handling process unfreezing and the
            // foreground-service-start exemption. Both transportControls.play()
            // and a button dispatched through the session controller were
            // accepted but never started audio. Pocket Casts treats PLAY as a
            // play/pause toggle, so only send it when paused — checked above —
            // or the toggle would mean "pause".
            val audio = context.getSystemService(AudioManager::class.java)
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
            Result.Played
        }

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

        val manager = context.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(context, MediaListenerService::class.java)
        val sessions = try {
            manager.getActiveSessions(listener)
        } catch (e: SecurityException) {
            Log.w(TAG, "Pocket Casts session: notification access not granted", e)
            return Result.NoAccess
        }

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

    private fun installed(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(POCKET_CASTS_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
