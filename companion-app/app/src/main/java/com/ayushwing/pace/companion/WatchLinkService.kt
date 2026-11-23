package com.ayushwing.pace.companion

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Keeps an RFCOMM connection to the watch open, reconnecting with backoff.
 * Runs as a connectedDevice foreground service so One UI does not kill it.
 */
class WatchLinkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var socket: BluetoothSocket? = null
    private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTIFICATION_ID, ongoing("Connecting to watch"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        if (!started) {
            started = true
            scope.launch { connectLoop() }
            scope.launch { keepAlive() }
        }
        CallMonitor.start(this)
        return START_STICKY
    }

    override fun onDestroy() {
        CallMonitor.stop(this)
        scope.cancel()
        runCatching { socket?.close() }
        WatchLink.attach(null)
        WatchLink.setState(WatchLink.State.NotSetUp)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun connectLoop() {
        var attempt = 0
        while (scope.isActive) {
            val address = WatchLink.address(this)
            if (address == null || !hasConnectPermission()) {
                WatchLink.setState(WatchLink.State.NotSetUp)
                delay(5_000)
                continue
            }
            attempt++
            WatchLink.setState(WatchLink.State.Connecting(attempt))
            try {
                val adapter = getSystemService(BluetoothManager::class.java).adapter
                val device = adapter.getRemoteDevice(address)
                val s = device.createInsecureRfcommSocketToServiceRecord(WatchLink.SERVICE_UUID)
                socket = s
                s.connect()
                attempt = 0
                serve(s)
            } catch (e: IOException) {
                Log.i(TAG, "link down: ${e.message}")
            } catch (e: SecurityException) {
                Log.w(TAG, "bluetooth permission missing", e)
            } finally {
                runCatching { socket?.close() }
                socket = null
                WatchLink.attach(null)
            }
            val wait = backoff(attempt)
            WatchLink.setState(WatchLink.State.Waiting("Watch not reachable", wait))
            updateOngoing("Watch not reachable, retrying")
            delay(wait * 1_000L)
        }
    }

    private fun serve(s: BluetoothSocket) {
        val input = DataInputStream(BufferedInputStream(s.inputStream))
        WatchLink.attach(DataOutputStream(BufferedOutputStream(s.outputStream)))
        Log.i(TAG, "connected")
        NotificationMirror.pushAll()
        CallMonitor.sendCurrent()
        while (true) {
            val f = Frame.read(input)
            when (f.type) {
                Frame.HELLO -> {
                    val o = JSONObject(f.json)
                    WatchLink.setState(
                        WatchLink.State.Connected(o.optString("model", "watch"), o.optInt("battery", -1)),
                    )
                    updateOngoing("Connected to ${o.optString("model", "watch")}")
                }
                Frame.NOTIF_ACTION -> {
                    val o = JSONObject(f.json)
                    if (o.optString("action") == "dismiss") NotificationMirror.dismiss(o.optString("key"))
                }
                Frame.CALL_ACTION -> {
                    val o = JSONObject(f.json)
                    NotificationMirror.onCallAction(this, o.optString("key"), o.optString("action"))
                }
                Frame.PONG -> Unit
                else -> Log.d(TAG, "ignored frame ${f.type}")
            }
        }
    }

    /** A ping every 60 s so a dead link is noticed and the reconnect loop runs. */
    private suspend fun keepAlive() {
        while (scope.isActive) {
            delay(60_000)
            if (WatchLink.state.value is WatchLink.State.Connected && !WatchLink.send(Frame(Frame.PING, "{}"))) {
                runCatching { socket?.close() }
            }
        }
    }

    private fun backoff(attempt: Int): Int = when {
        attempt <= 1 -> 5
        attempt <= 3 -> 15
        else -> 60
    }

    private fun hasConnectPermission() =
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun ongoing(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Watch connection", NotificationManager.IMPORTANCE_MIN),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_watch)
            .setContentTitle("Pace")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateOngoing(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, ongoing(text))
    }

    companion object {
        private const val TAG = "PaceLink"
        private const val CHANNEL = "link"
        private const val NOTIFICATION_ID = 1

        fun start(c: Context) {
            c.startForegroundService(Intent(c, WatchLinkService::class.java))
        }
    }
}
