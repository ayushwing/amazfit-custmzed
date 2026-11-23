package com.ayushwing.pace.companion

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.telecom.TelecomManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import org.json.JSONObject

/**
 * Mirrors the phone's calls to the watch.
 *
 * Samsung's dialer marks its ringing notification as an ongoing call, so the
 * notification can't tell ringing from in-call. The phone's call state can:
 *   RINGING            -> CALL_START  (watch rings)
 *   OFFHOOK            -> CALL_ACTIVE (answered, or an outgoing call; watch shows a timer)
 *   IDLE               -> CALL_END    (declined, missed or hung up)
 * The caller's name comes from the dialer's notification and may arrive a
 * moment later; the current state is re-sent when it does.
 */
object CallMonitor {
    private const val TAG = "PaceCall"

    private enum class Phase { RINGING, ACTIVE }

    @Volatile private var key: String? = null
    @Volatile private var phase: Phase? = null
    @Volatile private var activeSince = 0L        // SystemClock.elapsedRealtime() when answered
    @Volatile private var callerName: String? = null
    private var callback: TelephonyCallback? = null

    val PERMISSIONS = arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS)

    fun hasPermissions(c: Context) = PERMISSIONS.all {
        c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun start(c: Context) {
        if (callback != null || !hasPermissions(c)) return
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) = onState(state)
        }
        c.getSystemService(TelephonyManager::class.java).registerTelephonyCallback(c.mainExecutor, cb)
        callback = cb
        Log.i(TAG, "watching call state")
    }

    fun stop(c: Context) {
        callback?.let { c.getSystemService(TelephonyManager::class.java).unregisterTelephonyCallback(it) }
        callback = null
    }

    /** Called by NotificationMirror when the dialer posts or updates its call notification. */
    fun onCallerName(name: String?) {
        if (name.isNullOrBlank() || name == callerName) return
        callerName = name
        sendCurrent()
    }

    /** Re-sends the current call, e.g. after the watch reconnects mid-call. */
    fun sendCurrent() {
        when (phase) {
            Phase.RINGING -> send(Frame.CALL_START)
            Phase.ACTIVE -> send(Frame.CALL_ACTIVE)
            null -> Unit
        }
    }

    private fun onState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                key = "call-${System.currentTimeMillis()}"
                phase = Phase.RINGING
                Log.i(TAG, "ringing")
                send(Frame.CALL_START)
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (key == null) key = "call-${System.currentTimeMillis()}" // outgoing call
                phase = Phase.ACTIVE
                activeSince = SystemClock.elapsedRealtime()
                Log.i(TAG, "call active")
                send(Frame.CALL_ACTIVE)
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                val k = key ?: return
                Log.i(TAG, "call ended")
                WatchLink.send(Frame(Frame.CALL_END, JSONObject().put("key", k).put("reason", "ended").toString()))
                key = null
                phase = null
                callerName = null
            }
        }
    }

    private fun send(type: Int) {
        val k = key ?: return
        val o = JSONObject()
            .put("key", k)
            .put("caller", callerName ?: if (type == Frame.CALL_START) "Incoming call" else "Call")
            .put("app", "Phone")
        // Elapsed time rather than a timestamp, so the watch's clock doesn't matter.
        if (type == Frame.CALL_ACTIVE) o.put("elapsed_ms", SystemClock.elapsedRealtime() - activeSince)
        WatchLink.send(Frame(type, o.toString()))
    }

    /** Answer, decline or hang up, from the watch. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun act(c: Context, k: String, action: String) {
        if (k != key) return
        val telecom = c.getSystemService(TelecomManager::class.java)
        val ok = runCatching {
            if (action == "answer") { telecom.acceptRingingCall(); true } else telecom.endCall()
        }.onFailure { Log.w(TAG, "$action failed", it) }.getOrDefault(false)
        Log.i(TAG, "$action from watch: telecom=${if (ok) "ok" else "refused"}")
        if (!ok) NotificationMirror.pressCallAction(if (action == "answer") "answer" else "decline")
    }
}
