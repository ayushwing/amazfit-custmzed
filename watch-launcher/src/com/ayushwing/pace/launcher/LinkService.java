package com.ayushwing.pace.launcher;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Vibrator;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Bluetooth RFCOMM server that the phone companion connects to.
 *
 * The socket is "insecure" (no bonding required) so the phone can connect by
 * address without going through Huami's pairing UI. One phone at a time; when
 * it drops, the service goes back to listening.
 */
public class LinkService extends Service {
    static final String TAG = "PaceLink";
    static final UUID SERVICE_UUID = UUID.fromString("6b1d6a1e-6f8e-4c55-9f0a-5ac0c9a7e001");
    static final String ACTION_CHANGED = "com.ayushwing.pace.launcher.NOTIFICATIONS_CHANGED";

    /** A notification mirrored from the phone. */
    static final class Note {
        final String key, app, title, text;
        final long when;

        Note(JSONObject o) {
            key = o.optString("key");
            app = o.optString("app");
            title = o.optString("title");
            text = o.optString("text");
            when = o.optLong("when");
        }
    }

    private static final Object LOCK = new Object();
    private static final List<Note> NOTES = new ArrayList<>();
    private static volatile DataOutputStream out;
    private static volatile boolean connected;

    private Thread acceptThread;
    private volatile BluetoothServerSocket server;

    static boolean isConnected() {
        return connected;
    }

    static List<Note> notes() {
        synchronized (LOCK) {
            return new ArrayList<>(NOTES);
        }
    }

    static void start(Context c) {
        c.startService(new Intent(c, LinkService.class));
    }

    /** Asks the phone to dismiss a notification, and drops it locally. */
    static void dismiss(Context c, String key) {
        removeLocal(c, key);
        try {
            JSONObject o = new JSONObject().put("key", key).put("action", "dismiss");
            send(new Frame(Frame.NOTIF_ACTION, o.toString()));
        } catch (JSONException e) {
            Log.w(TAG, "dismiss", e);
        }
    }

    private static void send(final Frame f) {
        final DataOutputStream o = out;
        if (o == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                synchronized (o) {
                    try {
                        f.write(o);
                    } catch (IOException e) {
                        Log.w(TAG, "send failed", e);
                    }
                }
            }
        }, "PaceLink-send").start();
    }

    private static void removeLocal(Context c, String key) {
        synchronized (LOCK) {
            for (int i = NOTES.size() - 1; i >= 0; i--) {
                if (NOTES.get(i).key.equals(key)) NOTES.remove(i);
            }
        }
        c.sendBroadcast(new Intent(ACTION_CHANGED).setPackage(c.getPackageName()));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (acceptThread == null || !acceptThread.isAlive()) {
            acceptThread = new Thread(new Runnable() {
                @Override public void run() { acceptLoop(); }
            }, "PaceLink-accept");
            acceptThread.start();
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        closeServer();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private void acceptLoop() {
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (bt == null || !bt.isEnabled()) {
                    Thread.sleep(10_000);
                    continue;
                }
                server = bt.listenUsingInsecureRfcommWithServiceRecord("PaceLink", SERVICE_UUID);
                Log.i(TAG, "listening");
                BluetoothSocket s = server.accept();
                closeServer();
                serve(s);
            } catch (IOException e) {
                Log.w(TAG, "accept failed: " + e.getMessage());
                closeServer();
                sleepQuietly(5_000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void serve(BluetoothSocket s) {
        Log.i(TAG, "phone connected");
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            connected = true;
            broadcastChanged();
            send(new Frame(Frame.HELLO, hello()));
            while (true) {
                handle(Frame.read(in));
            }
        } catch (IOException e) {
            Log.i(TAG, "phone disconnected: " + e.getMessage());
        } finally {
            connected = false;
            out = null;
            try { s.close(); } catch (IOException ignored) { }
            broadcastChanged();
        }
    }

    private void handle(Frame f) {
        try {
            switch (f.type) {
                case Frame.NOTIF_PUSH: {
                    JSONObject o = new JSONObject(f.json);
                    Note n = new Note(o);
                    synchronized (LOCK) {
                        for (int i = NOTES.size() - 1; i >= 0; i--) {
                            if (NOTES.get(i).key.equals(n.key)) NOTES.remove(i);
                        }
                        NOTES.add(0, n);
                        while (NOTES.size() > 20) NOTES.remove(NOTES.size() - 1);
                    }
                    Log.i(TAG, "notification from " + n.app + (o.optBoolean("silent") ? " (silent)" : ""));
                    // Initial sync after connecting is silent: file it, don't buzz.
                    if (!o.optBoolean("silent")) alert();
                    broadcastChanged();
                    break;
                }
                case Frame.NOTIF_REMOVE:
                    removeLocal(this, new JSONObject(f.json).optString("key"));
                    break;
                case Frame.PING:
                    send(new Frame(Frame.PONG, f.json));
                    break;
                default:
                    Log.d(TAG, "ignored frame type " + f.type);
            }
        } catch (JSONException e) {
            Log.w(TAG, "bad payload", e);
        }
    }

    /** Buzzes, lights the screen and opens the notification card. */
    private void alert() {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) v.vibrate(new long[] {0, 120, 80, 120}, -1);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        @SuppressWarnings("deprecation")
        PowerManager.WakeLock wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "PaceLink:alert");
        wl.acquire(3_000);
        startActivity(new Intent(this, NotifyActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    private String hello() {
        try {
            Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int level = b == null ? -1 : b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            return new JSONObject()
                    .put("v", 1)
                    .put("model", Build.MODEL)
                    .put("firmware", Build.DISPLAY)
                    .put("battery", level)
                    .toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    private void broadcastChanged() {
        sendBroadcast(new Intent(ACTION_CHANGED).setPackage(getPackageName()));
    }

    private void closeServer() {
        BluetoothServerSocket s = server;
        server = null;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
