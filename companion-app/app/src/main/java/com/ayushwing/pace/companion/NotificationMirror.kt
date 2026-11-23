package com.ayushwing.pace.companion

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject

/**
 * Mirrors phone notifications to the watch, and applies dismissals that come
 * back from the watch. Ongoing, silent-group-summary and system notifications
 * are not forwarded.
 */
class NotificationMirror : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        pushAll()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (forwardable(sbn)) WatchLink.send(Frame(Frame.NOTIF_PUSH, payload(sbn).toString()))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (forwardable(sbn)) {
            WatchLink.send(Frame(Frame.NOTIF_REMOVE, JSONObject().put("key", sbn.key).toString()))
        }
    }

    private fun forwardable(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (sbn.packageName == packageName || sbn.packageName in SKIP) return false
        if (sbn.isOngoing || !sbn.isClearable) return false
        if (n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_FOREGROUND_SERVICE) != 0) return false
        if (n.category in SKIP_CATEGORIES) return false
        val e = n.extras
        return !e.getCharSequence(Notification.EXTRA_TITLE).isNullOrBlank() ||
            !e.getCharSequence(Notification.EXTRA_TEXT).isNullOrBlank()
    }

    private fun payload(sbn: StatusBarNotification, silent: Boolean = false): JSONObject {
        val e = sbn.notification.extras
        val app = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        val text = e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT)
        return JSONObject()
            .put("key", sbn.key)
            .put("app", app)
            .put("title", e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty())
            .put("text", text?.toString().orEmpty().take(600))
            .put("when", sbn.postTime)
            .put("silent", silent)
    }

    companion object {
        private const val TAG = "PaceMirror"
        private val SKIP = setOf(
            "android", "com.android.systemui", "com.samsung.android.app.routines",
            "com.samsung.android.app.aodservice",
        )
        private val SKIP_CATEGORIES = setOf(
            Notification.CATEGORY_SERVICE, Notification.CATEGORY_SYSTEM,
            Notification.CATEGORY_STATUS, Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_PROGRESS,
        )

        @Volatile private var instance: NotificationMirror? = null

        val isRunning: Boolean get() = instance != null

        /**
         * Sends every current notification, oldest first, right after connecting.
         * Marked silent so the watch files them without buzzing for each one.
         */
        fun pushAll() {
            val m = instance ?: return
            val active = runCatching { m.activeNotifications }.getOrNull() ?: return
            active.filter(m::forwardable).sortedBy { it.postTime }.forEach {
                WatchLink.send(Frame(Frame.NOTIF_PUSH, m.payload(it, silent = true).toString()))
            }
        }

        /** Clears a notification on the phone because it was dismissed on the watch. */
        fun dismiss(key: String) {
            val m = instance ?: return
            Log.i(TAG, "dismiss from watch")
            runCatching { m.cancelNotification(key) }
        }
    }
}
