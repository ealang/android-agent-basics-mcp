package dev.ealang.agentbasicsmcp.mcp.generic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.ealang.agentbasicsmcp.MainActivity
import dev.ealang.agentbasicsmcp.R

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
                "Agent Basics MCP. They can re-enable it under Settings > Notifications." to true
        }

        // A posted notification cannot move channels: notify() with the same
        // tag but a different channel keeps the old channel and only logs a
        // warning in logcat. So a level change is cancel-then-repost — which
        // is also what lets an info raised to alert actually make a sound,
        // since an in-place update never re-alerts.
        val existing = manager.activeNotifications
            .firstOrNull { it.id == ID && it.tag == key }
        if (existing != null && existing.notification.channelId != channelId) {
            manager.cancel(key, ID)
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
            // The status bar icon carries the level: info circle or warning
            // triangle. The pyramid stays the service's alone, so agent
            // notifications are tellable from it at a glance.
            .setSmallIcon(
                if (alert) R.drawable.ic_stat_warning else R.drawable.ic_stat_claw,
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            // The agent pile and the service's own notification are two fixed
            // groups, so the shade never stacks one under the other.
            .setGroup("agent-basics-mcp-agent")
            // Updates replace the text without sounding again; only the first
            // post of a key alerts.
            .setOnlyAlertOnce(true)
            .build()

        manager.notify(key, ID, notification)
        return "The notification is in the phone's shade. Posting the same key " +
            "again replaces it: silently at the same alert level, audibly if " +
            "raised to alert." to false
    }

    fun dismiss(context: Context, key: String): Pair<String, Boolean> {
        context.getSystemService(NotificationManager::class.java).cancel(key, ID)
        return "Dismissed. A key with nothing showing is a no-op, so this " +
            "cannot fail." to false
    }

    /**
     * The silent channel's id, for the app's own housekeeping notifications
     * (the find-my-phone stop button). Ensures the channels exist first.
     */
    fun infoChannel(context: Context): String {
        ensureChannels(context.getSystemService(NotificationManager::class.java))
        return INFO_CHANNEL
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
