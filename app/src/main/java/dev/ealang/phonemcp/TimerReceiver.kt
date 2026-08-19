package dev.ealang.phonemcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.util.Log

/** Fires when a timer elapses: notification with sound and vibration. */
class TimerReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_LABEL = "label"
        private const val CHANNEL_ID = "phone-mcp-timers"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, 0)
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "Timer"
        Log.i("PhoneMcpTimer", "Timer #$id fired: $label")

        Timers.forget(context, id)

        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_HIGH so it heads-up and rings — the whole point of a timer.
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Timers",
                NotificationManager.IMPORTANCE_HIGH,
            )
            channel.enableVibration(true)
            channel.setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                Notification.AUDIO_ATTRIBUTES_DEFAULT,
            )
            manager.createNotificationChannel(channel)
        }

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(label)
            .setContentText("Timer finished")
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .build()

        manager.notify(id, notification)
    }
}
