package dev.ealang.phonemcp

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent

/**
 * Drives Pocket Casts through its exported MediaButtonReceiver — a synthetic
 * headset play button, the surface its manifest documents for external apps.
 * (Its Tasker-style PLAY broadcast receiver exists too, but proved to be
 * silently dropped on a real phone even with the app in the foreground.)
 * Fire-and-forget: nothing comes back, so the only failure this can detect is
 * the app not being installed.
 */
object Podcast {
    private const val TAG = "phone-mcp"

    const val POCKET_CASTS_PACKAGE = "au.com.shiftyjelly.pocketcasts"

    sealed interface Result {
        /** The button events were sent. Pocket Casts does not report back. */
        data object Sent : Result
        data object NotInstalled : Result
    }

    /** Resumes whatever is at the head of the Up Next queue. */
    fun play(context: Context): Result {
        if (!installed(context)) return Result.NotInstalled
        // A button press is a down/up pair; MediaButtonReceiver forwards each
        // to the playback service, starting it if it is not running.
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            context.sendBroadcast(
                Intent(Intent.ACTION_MEDIA_BUTTON)
                    .setPackage(POCKET_CASTS_PACKAGE)
                    .putExtra(
                        Intent.EXTRA_KEY_EVENT,
                        KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY),
                    ),
            )
        }
        Log.i(TAG, "media-button PLAY -> $POCKET_CASTS_PACKAGE")
        return Result.Sent
    }

    private fun installed(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(POCKET_CASTS_PACKAGE, 0)
        true
    } catch (e: Exception) {
        false
    }
}
