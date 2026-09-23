package dev.ealang.phonemcp.mcp.generic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.ealang.phonemcp.MainActivity
import dev.ealang.phonemcp.R

/**
 * Agent-posted notifications in the phone's shade.
 *
 * The caller's key becomes the notification tag, which is how the platform
 * already thinks about identity: notify() with the same tag replaces the
 * previous notification in place, cancel() with it takes it down. No state
 * is kept here — the shade is the state.
 *
 * Severity is a channel property on Android, not a per-notification one, so
 * the two levels are two channels: info at IMPORTANCE_LOW (appears silently)
 * and alert at IMPORTANCE_DEFAULT (makes a sound). There is deliberately no
 * heads-up level — a popup over whatever the user is doing is too loud a
 * thing to hand an agent. Once created, the channels
 * belong to the user: Settings can retune or block them, and this code
 * cannot override that, which is the right way round.
 */
object Notifier {

    private const val INFO_CHANNEL = "agent_info"
    private const val ALERT_CHANNEL = "agent_alert"

    /**
     * One fixed id for every agent notification; the tag carries identity.
     * The service's own foreground notification uses a different id and no
     * tag, so the namespaces cannot collide.
     */
    private const val ID = 2000

    fun post(
        context: Context,
        key: String,
        title: String,
        text: String,
        alert: Boolean,
    ): Pair<String, Boolean> {
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannels(manager)

        val channelId = if (alert) ALERT_CHANNEL else INFO_CHANNEL
        // The user can block one channel while leaving the app enabled, and a
        // post to a blocked channel is silently dropped — worth a real answer.
        if (manager.getNotificationChannel(channelId)?.importance ==
            NotificationManager.IMPORTANCE_NONE
        ) {
            return "FAILED: the user has blocked this notification category for " +
                "Phone MCP. They can re-enable it under Settings > Notifications." to true
        }

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = Notification.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(text)
            // Long text wraps into an expandable block instead of truncating
            // to one line.
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_pyramid)
            .setContentIntent(open)
            .setAutoCancel(true)
            // Updates replace the text without sounding again; only the first
            // post of a key alerts.
            .setOnlyAlertOnce(true)
            .build()

        manager.notify(key, ID, notification)
        return "The notification is in the phone's shade. Posting the same key " +
            "again replaces it in place, silently." to false
    }

    fun dismiss(context: Context, key: String): Pair<String, Boolean> {
        context.getSystemService(NotificationManager::class.java).cancel(key, ID)
        return "Dismissed. A key with nothing showing is a no-op, so this " +
            "cannot fail." to false
    }

    private fun ensureChannels(manager: NotificationManager) {
        // createNotificationChannel is idempotent, and importance is only a
        // default: after creation the channel is the user's to retune.
        manager.createNotificationChannel(
            NotificationChannel(
                INFO_CHANNEL,
                "Agent notifications",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Informational notifications posted by an agent." },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL,
                "Agent alerts",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Notifications an agent marks as alerts. These make a sound." },
        )
    }
}
