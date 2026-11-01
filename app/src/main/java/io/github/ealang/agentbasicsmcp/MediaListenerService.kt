package io.github.ealang.agentbasicsmcp

import android.service.notification.NotificationListenerService

/**
 * Deliberately empty. Holding a user-enabled notification listener is what
 * authorizes [android.media.session.MediaSessionManager.getActiveSessions];
 * nothing here reads a notification, and the manifest declares every
 * notification type disabled so that from Android 13 the system delivers
 * none. Keep it that way: no [onNotificationPosted], no
 * [getActiveNotifications].
 */
class MediaListenerService : NotificationListenerService()
