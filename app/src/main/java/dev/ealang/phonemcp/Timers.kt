package dev.ealang.phonemcp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Timers, backed by AlarmManager.
 *
 * AlarmManager forgets everything across a reboot, so every pending timer is
 * also written to SharedPreferences and re-armed by [BootReceiver]. Without
 * that, a phone restart silently swallows the timer the user set.
 */
object Timers {

    private const val TAG = "PhoneMcpTimers"
    private const val PREFS = "phone-mcp-timers"
    private const val KEY = "pending"

    data class Timer(val id: Int, val label: String, val fireAtMillis: Long)

    fun set(context: Context, label: String, seconds: Long): Timer {
        val fireAt = System.currentTimeMillis() + seconds * 1000
        // Small enough to fit a notification id, unique enough for concurrent timers.
        val id = (System.currentTimeMillis() % 100_000).toInt()
        val timer = Timer(id, label, fireAt)

        save(context, load(context) + timer)
        arm(context, timer)
        return timer
    }

    fun list(context: Context): List<Timer> {
        val now = System.currentTimeMillis()
        // Anything already past is a timer that fired (or was missed); don't
        // report it as pending.
        val live = load(context).filter { it.fireAtMillis > now }
        save(context, live)
        return live.sortedBy { it.fireAtMillis }
    }

    fun cancel(context: Context, id: Int): Boolean {
        val all = load(context)
        val target = all.firstOrNull { it.id == id } ?: return false
        alarmManager(context).cancel(pendingIntent(context, target))
        save(context, all - target)
        return true
    }

    /** Re-arm everything still in the future. Called after a reboot. */
    fun rearmAll(context: Context) {
        val now = System.currentTimeMillis()
        val live = load(context).filter { it.fireAtMillis > now }
        live.forEach { arm(context, it) }
        save(context, live)
        Log.i(TAG, "Re-armed ${live.size} timer(s) after boot")
    }

    fun forget(context: Context, id: Int) {
        save(context, load(context).filterNot { it.id == id })
    }

    fun describe(timer: Timer): String {
        val clock = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timer.fireAtMillis))
        val remaining = (timer.fireAtMillis - System.currentTimeMillis()).coerceAtLeast(0) / 1000
        return "#${timer.id} \"${timer.label}\" at $clock (in ${humanize(remaining)})"
    }

    fun humanize(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 && s > 0 -> "${m}m ${s}s"
            m > 0 -> "${m}m"
            else -> "${s}s"
        }
    }

    private fun arm(context: Context, timer: Timer) {
        val am = alarmManager(context)
        val pi = pendingIntent(context, timer)
        // setExactAndAllowWhileIdle is the only variant that survives Doze,
        // which is exactly the state a phone is in when a timer matters.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timer.fireAtMillis, pi)
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, timer.fireAtMillis, pi)
        }
        Log.i(TAG, "Armed ${describe(timer)}")
    }

    private fun alarmManager(context: Context) =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pendingIntent(context: Context, timer: Timer): PendingIntent {
        val intent = Intent(context, TimerReceiver::class.java).apply {
            putExtra(TimerReceiver.EXTRA_ID, timer.id)
            putExtra(TimerReceiver.EXTRA_LABEL, timer.label)
        }
        // The request code is the timer id, so cancel() rebuilds an equal intent
        // and hits the same alarm.
        return PendingIntent.getBroadcast(
            context,
            timer.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun load(context: Context): List<Timer> {
        val raw = prefs(context).getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Timer(o.getInt("id"), o.getString("label"), o.getLong("fireAt"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read stored timers, starting empty", e)
            emptyList()
        }
    }

    private fun save(context: Context, timers: List<Timer>) {
        val arr = JSONArray()
        timers.forEach {
            arr.put(JSONObject().put("id", it.id).put("label", it.label).put("fireAt", it.fireAtMillis))
        }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
