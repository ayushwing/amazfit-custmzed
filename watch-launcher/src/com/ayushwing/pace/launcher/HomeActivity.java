package com.ayushwing.pace.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.util.Log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Home screen for the Amazfit Pace. Hosts a single {@link DialView} that shows
 * the watch face and, on swipe up, the bubble app grid. The hardware button
 * sends HOME, which lands in {@link #onNewIntent} and returns to the face.
 */
public class HomeActivity extends Activity implements SensorEventListener {
    static final String TAG = "PaceLauncher";
    private static final Uri HISTORY = Uri.parse("content://com.huami.watch.health.historydata");
    private static final Pattern TTL = Pattern.compile("\"ttl\":(\\d+)");

    private SensorManager sensors;
    private DialView dial;

    private final BroadcastReceiver clock = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            refreshBattery();
            dial.invalidate();
        }
    };

    private void refreshBattery() {
        Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return;
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level >= 0 && scale > 0) dial.setBattery(level * 100 / scale);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        dial = new DialView(this);
        setContentView(dial);
        sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        dial.showFace();
    }

    @Override public void onBackPressed() {
        dial.showFace();
    }

    @Override protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(clock, f);

        refreshBattery();
        dial.setSteps(readProviderSteps());
        Sensor step = sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (step != null) {
            // Let the sensor FIFO batch for up to 60 s so the CPU can sleep.
            sensors.registerListener(this, step, SensorManager.SENSOR_DELAY_NORMAL, 60_000_000);
        }
        dial.reloadApps();
    }

    @Override protected void onPause() {
        super.onPause();
        unregisterReceiver(clock);
        sensors.unregisterListener(this);
    }

    @Override public void onSensorChanged(SensorEvent e) {
        dial.setSteps((int) e.values[0]);
    }

    @Override public void onAccuracyChanged(Sensor s, int accuracy) { }

    /** Today's step total from Huami's history provider, or -1 if unreadable. */
    private int readProviderSteps() {
        Cursor c = null;
        try {
            c = getContentResolver().query(HISTORY, null, null, null, null);
            if (c == null || !c.moveToLast()) return -1;
            int col = c.getColumnIndex("summary");
            Matcher m = TTL.matcher(col >= 0 ? c.getString(col) : "");
            return m.find() ? Integer.parseInt(m.group(1)) : -1;
        } catch (Exception ex) {
            Log.w(TAG, "provider read failed", ex);
            return -1;
        } finally {
            if (c != null) c.close();
        }
    }
}
