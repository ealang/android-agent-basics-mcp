package dev.ealang.phonemcp

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.util.Log

/**
 * Timers, handed to whichever app owns ACTION_SET_TIMER — normally the phone's
 * stock clock.
 *
 * Delegating rather than ringing them ourselves means the timer shows up where
 * the user already looks for timers, uses their own alarm sound, can be
 * snoozed or edited by hand, and keeps running even if this app is killed.
 *
 * The cost is that there is no read API: nothing can enumerate the clock app's
 * timers, so there is no list operation, and cancelling is a search by label or
 * "the next one" rather than by id.
 */
object Clock {

    private const val TAG = "PhoneMcpClock"

    /** Clock apps reject anything outside this; documented on EXTRA_LENGTH. */
    const val MAX_SECONDS = 86_400L

    /**
     * True when this app may start an activity from the background.
     *
     * Since Android 10 that is forbidden by default, and a foreground service
     * does not earn the right — but SYSTEM_ALERT_WINDOW does, and it is the
     * only exemption a normal app can actually obtain.
     */
    fun canStartFromBackground(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(context)

    fun setTimer(context: Context, seconds: Long, label: String): Boolean =
        launch(
            context,
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds.toInt())
                .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                // A request, not a guarantee: the stock clock honours it and
                // starts the timer without coming to the front, but a
                // third-party clock may show its UI anyway.
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
        )

    /** Cancels the timer with this label, or the next one to fire if null. */
    fun dismissTimer(context: Context, label: String?): Boolean {
        val intent = Intent(AlarmClock.ACTION_DISMISS_TIMER)
        if (label == null) {
            intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
        } else {
            intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        }
        return launch(context, intent)
    }

    /**
     * True once the clock app has taken the intent. Both reasons it might not —
     * no overlay grant, no app that handles timers — are caught by [Preflight]
     * before this runs, so a false here is the rare leftover.
     */
    private fun launch(context: Context, intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "No handler for ${intent.action}", e)
            false
        }
    }

    fun humanize(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            m > 0 && s > 0 -> "${m}m ${s}s"
            m > 0 -> "${m}m"
            else -> "${s}s"
        }
    }
}
