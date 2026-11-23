package com.ayushwing.pace.companion

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.os.Build
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
        if (sbn.notification.category == Notification.CATEGORY_CALL) {
            onCallNotification(sbn)
            return
        }
        if (forwardable(sbn)) WatchLink.send(Frame(Frame.NOTIF_PUSH, payload(sbn).toString()))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key == callNotification?.key) {
            callNotification = null
            return
        }
        if (forwardable(sbn)) {
            WatchLink.send(Frame(Frame.NOTIF_REMOVE, JSONObject().put("key", sbn.key).toString()))
        }
    }

    // ------------------------------------------------------------------ calls

    /**
     * Call notifications are ongoing, so the normal filter drops them. Ringing
     * itself is detected by CallMonitor from the phone's call state; this only
     * supplies the caller's name and keeps the notification as a fallback for
     * Answer/Decline.
     */
    private fun onCallNotification(sbn: StatusBarNotification) {
        val n = sbn.notification
        Log.d(TAG, "call notification from ${sbn.packageName}: callType=${n.extras.getInt(EXTRA_CALL_TYPE, 0)} " +
            "fullScreen=${n.fullScreenIntent != null} actions=${n.actions?.joinToString { it.title }}")
        callNotification = sbn
        val caller = n.extras.getParcelable(Notification.EXTRA_CALL_PERSON, android.app.Person::class.java)?.name
            ?: n.extras.getCharSequence(Notification.EXTRA_TITLE)
        CallMonitor.onCallerName(caller?.toString())
    }

    /** Fallback when telephony refuses: press the dialer notification's own button. */
    private fun callAction(action: String) {
        val n = callNotification?.notification ?: return
        val extra = if (action == "answer") EXTRA_ANSWER_INTENT else EXTRA_DECLINE_INTENT
        val words = if (action == "answer") listOf("answer", "accept") else listOf("decline", "reject", "hang up", "end")
        val intent = n.extras.getParcelable(extra, PendingIntent::class.java)
            ?: n.actions?.firstOrNull { a -> words.any { a.title.toString().lowercase().contains(it) } }?.actionIntent
        if (intent == null) {
            Log.w(TAG, "no $action action on call notification")
            return
        }
        Log.i(TAG, "call $action via notification")
        runCatching {
            val opts = ActivityOptions.makeBasic()
            if (Build.VERSION.SDK_INT >= 34) {
                opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }
            intent.send(this, 0, null, null, null, null, opts.toBundle())
        }.onFailure { Log.w(TAG, "call $action failed", it) }
    }

    private fun appName(pkg: String) = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

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
        val app = appName(sbn.packageName)
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

        // Notification.EXTRA_CALL_TYPE and friends (API 31), by name so they read plainly.
        private const val EXTRA_CALL_TYPE = "android.callType"
        private const val EXTRA_ANSWER_INTENT = "android.answerIntent"
        private const val EXTRA_DECLINE_INTENT = "android.declineIntent"

        @Volatile private var instance: NotificationMirror? = null
        @Volatile private var callNotification: StatusBarNotification? = null

        val isRunning: Boolean get() = instance != null

        /** Answer or decline from the watch; the test call is handled locally. */
        fun onCallAction(c: android.content.Context, key: String, action: String) {
            if (key == TEST_CALL_KEY) {
                // Fake call for test-e2e.sh: answer turns it into an active call,
                // decline or hang up ends it.
                Log.i(TAG, "test call $action from watch")
                if (action == "answer") {
                    WatchLink.send(Frame(Frame.CALL_ACTIVE, JSONObject().put("key", key)
                        .put("caller", "Test caller").put("app", "Phone").put("elapsed_ms", 0).toString()))
                } else {
                    WatchLink.send(Frame(Frame.CALL_END, JSONObject().put("key", key).put("reason", action).toString()))
                }
                return
            }
            CallMonitor.act(c, key, action)
        }

        fun pressCallAction(action: String) {
            instance?.callAction(action)
        }

        const val TEST_CALL_KEY = "pace-test-call"

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
