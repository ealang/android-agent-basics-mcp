package dev.ealang.phonemcp

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Drives Pocket Casts through its exported PlayerBroadcastReceiver, the same
 * broadcast surface its Tasker integration uses. Fire-and-forget: the receiver
 * takes no parameters and reports nothing back, so the only failure this can
 * detect is the app not being installed.
 */
object Podcast {
    private const val TAG = "phone-mcp"

    const val POCKET_CASTS_PACKAGE = "au.com.shiftyjelly.pocketcasts"

    private const val ACTION_PLAY = "au.com.shiftyjelly.pocketcasts.action.PLAY"

    sealed interface Result {
        /** The broadcast was sent. Pocket Casts does not report back. */
        data object Sent : Result
        data object NotInstalled : Result
    }

    /** Resumes whatever is at the head of the Up Next queue. */
    fun play(context: Context): Result {
        if (!installed(context)) return Result.NotInstalled
        context.sendBroadcast(Intent(ACTION_PLAY).setPackage(POCKET_CASTS_PACKAGE))
        Log.i(TAG, "$ACTION_PLAY -> $POCKET_CASTS_PACKAGE")
        return Result.Sent
    }

    private fun installed(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(POCKET_CASTS_PACKAGE, 0)
        true
    } catch (e: Exception) {
        false
    }
}
