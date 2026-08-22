package dev.ealang.phonemcp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Drives Pocket Casts through its media session, the way Bluetooth controls
 * and Android Auto do. Its session accepts unknown callers with transport-only
 * access (their code names Tasker as the audience), so connecting yields real
 * play/pause control plus now-playing metadata — unlike its broadcast
 * receivers, which a real phone dropped silently.
 */
object Podcast {
    private const val TAG = "phone-mcp"

    const val POCKET_CASTS_PACKAGE = "au.com.shiftyjelly.pocketcasts"

    private const val CONNECT_TIMEOUT_MS = 5_000L

    sealed interface Result {
        data object Played : Result
        data class NowPlaying(
            val podcast: String?,
            val episode: String?,
            val positionMs: Long,
            val durationMs: Long,
            val playing: Boolean,
        ) : Result
        /** Connected, but no episode is loaded to describe. */
        data object NothingLoaded : Result
        data object NotInstalled : Result
        /** The service refused the connection or never answered. */
        data object NoConnection : Result
    }

    fun play(context: Context): Result =
        withController(context) { controller ->
            controller.transportControls.play()
            Result.Played
        }

    fun nowPlaying(context: Context): Result =
        withController(context) { controller ->
            val metadata = controller.metadata ?: return@withController Result.NothingLoaded
            Result.NowPlaying(
                podcast = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM)
                    ?: metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST),
                episode = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE),
                positionMs = controller.playbackState?.position ?: -1,
                durationMs = metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION),
                playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING,
            )
        }

    /**
     * Connects to whichever of Pocket Casts' two browser services is enabled,
     * runs [block] with a controller for its session, and disconnects.
     *
     * MediaBrowser insists on a looper thread for its callbacks, and the
     * server calls this from a plain request thread, so the connection races
     * a latch against a timeout.
     */
    private fun withController(
        context: Context,
        block: (MediaController) -> Result,
    ): Result {
        val service = browserService(context) ?: return Result.NotInstalled

        val connected = CountDownLatch(1)
        var browser: MediaBrowser? = null
        val handler = Handler(Looper.getMainLooper())

        val callback = object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() = connected.countDown()
            override fun onConnectionFailed() = connected.countDown()
        }

        handler.post {
            browser = MediaBrowser(context, service, callback, null).also { it.connect() }
        }

        try {
            if (!connected.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Pocket Casts session: no answer from $service")
                return Result.NoConnection
            }
            val live = browser?.takeIf { it.isConnected } ?: run {
                Log.w(TAG, "Pocket Casts session: connection refused by $service")
                return Result.NoConnection
            }
            return block(MediaController(context, live.sessionToken))
        } finally {
            handler.post { browser?.disconnect() }
        }
    }

    /**
     * Pocket Casts declares two playback services and enables exactly one at
     * startup (a feature-flag toggle), so resolve rather than hardcode.
     */
    private fun browserService(context: Context): ComponentName? {
        val intent = Intent("android.media.browse.MediaBrowserService")
            .setPackage(POCKET_CASTS_PACKAGE)
        val info = context.packageManager.resolveService(intent, 0)?.serviceInfo ?: return null
        return ComponentName(info.packageName, info.name)
    }
}
