package dev.ealang.puckmcp

import android.service.notification.NotificationListenerService

/**
 * Deliberately empty. Holding a user-enabled notification listener is what
 * authorizes [android.media.session.MediaSessionManager.getActiveSessions];
 * we never read any notifications.
 */
class MediaListenerService : NotificationListenerService()
