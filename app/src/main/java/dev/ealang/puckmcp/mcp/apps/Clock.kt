package dev.ealang.puckmcp.mcp.apps

import android.content.Context
import android.provider.Settings

/**
 * Time-adjacent helpers shared across the tools: the background-launch
 * check every activity start rides on, and duration formatting.
 */
object Clock {

    /**
     * True when this app may start an activity from the background.
     *
     * Forbidden by default, and a foreground service does not earn the
     * right — but SYSTEM_ALERT_WINDOW does, and it is the only exemption a
     * normal app can actually obtain.
     */
    fun canStartFromBackground(context: Context): Boolean =
        Settings.canDrawOverlays(context)

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
