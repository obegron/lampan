package com.egron.lampan

import android.service.notification.NotificationListenerService

/**
 * Grants Lampan access to Android's active media sessions when the user
 * explicitly enables Notification Access in system settings.
 */
class NowPlayingNotificationListener : NotificationListenerService()
