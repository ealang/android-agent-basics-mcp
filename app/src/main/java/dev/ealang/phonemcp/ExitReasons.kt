package dev.ealang.phonemcp

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.ActivityManager.RunningAppProcessInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One log line at process start saying why the previous process ended.
 *
 * Android keeps a short history of each app's process deaths: a reason
 * code, the system's own description, and the process importance at the
 * moment of death. The importance is the telling field — a foreground
 * service taken down is a different story from a cached process trimmed
 * hours after the server was switched off — and without this line a death
 * the service did not survive leaves a gap in the log with nothing to
 * explain it.
 *
 * Logged once per process, by whichever entry point runs first, and only
 * the most recent death: the start before this one logged the one before.
 * Needs API 30; earlier releases keep no history.
 */
object ExitReasons {

    private const val TAG = "PhoneMcpExit"

    private val done = AtomicBoolean(false)

    fun logLast(context: Context, log: ServerLog) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (!done.compareAndSet(false, true)) return
        val info = try {
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 1)
                .firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the exit history", e)
            null
        } ?: return

        val description = info.description?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        val detail = "${reason(info.reason)}$description; was ${importance(info.importance)}; " +
            "at ${STAMP.format(Date(info.timestamp))}"
        // Warn when something we were doing got killed; info when nothing was
        // running and the system merely trimmed a cached process.
        if (info.importance >= RunningAppProcessInfo.IMPORTANCE_CACHED) {
            log.info("previous process ended", detail)
        } else {
            log.warn("previous process ended", detail)
        }
    }

    @SuppressLint("InlinedApi")
    private fun reason(code: Int): String = when (code) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited on its own"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for memory"
        ApplicationExitInfo.REASON_CRASH -> "crashed"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "not responding"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "a permission changed"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed for excessive resource use"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "force stopped by the user or a device manager"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user profile stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "a dependency died"
        ApplicationExitInfo.REASON_OTHER -> "killed by the system"
        ApplicationExitInfo.REASON_FREEZER -> "killed while frozen"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package state changed"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "app updated"
        else -> "unknown reason $code"
    }

    private fun importance(level: Int): String = when {
        level <= RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "in the foreground"
        level <= RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "a foreground service"
        level <= RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
        level <= RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible"
        level <= RunningAppProcessInfo.IMPORTANCE_SERVICE -> "a background service"
        level <= RunningAppProcessInfo.IMPORTANCE_CANT_SAVE_STATE -> "a background app"
        level <= RunningAppProcessInfo.IMPORTANCE_CACHED -> "cached"
        else -> "gone"
    }

    private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
}
