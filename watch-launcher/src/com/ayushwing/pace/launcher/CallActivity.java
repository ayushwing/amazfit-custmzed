package com.ayushwing.pace.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.os.Vibrator;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.Locale;

/**
 * The phone's call, on the watch. Two modes:
 *
 *  Ringing: buzzes until answered, declined or ended (at most 45 s).
 *           Decline and Answer bubbles; tapping the name stops the buzz.
 *  Active:  caller and a running timer, one Hang up bubble. The screen may
 *           sleep; pressing the button and coming back shows the timer again.
 *
 * Actions go to the phone, which does them through Telecom. The phone's
 * CALL_END closes this screen.
 */
public class CallActivity extends Activity {
    static final String EXTRA_KEY = "key";
    static final String EXTRA_CALLER = "caller";
    static final String EXTRA_APP = "app";
    static final String EXTRA_ACTIVE = "active";
    static final String EXTRA_ELAPSED = "elapsed_ms";

    private static final long[] RING = {0, 600, 500};
    private static final long MAX_RING_MS = 45_000;

    private final Handler handler = new Handler();
    private Vibrator vibrator;
    private CallView view;
    private String key;

    private final BroadcastReceiver ended = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (key != null && key.equals(i.getStringExtra(EXTRA_KEY))) finish();
        }
    };

    private final Runnable timeout = new Runnable() {
        @Override public void run() { stopRinging(); }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            view.invalidate();
            handler.postDelayed(this, 1_000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        view = new CallView(this);
        setContentView(view);
        registerReceiver(ended, new IntentFilter(LinkService.ACTION_CALL_END));
        show(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        show(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        if (view.active) handler.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    @Override protected void onDestroy() {
        stopRinging();
        handler.removeCallbacksAndMessages(null);
        unregisterReceiver(ended);
        super.onDestroy();
    }

    private void show(Intent i) {
        key = i.getStringExtra(EXTRA_KEY);
        view.caller = i.getStringExtra(EXTRA_CALLER);
        view.app = i.getStringExtra(EXTRA_APP);
        view.status = null;
        boolean active = i.getBooleanExtra(EXTRA_ACTIVE, false);
        if (active) {
            view.active = true;
            view.since = SystemClock.elapsedRealtime() - i.getLongExtra(EXTRA_ELAPSED, 0);
            stopRinging();
            // Don't hold the screen on for a long call; let it sleep normally.
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            handler.removeCallbacks(tick);
            handler.post(tick);
        } else {
            view.active = false;
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (vibrator != null) vibrator.vibrate(RING, 0);
            handler.removeCallbacks(timeout);
            handler.postDelayed(timeout, MAX_RING_MS);
        }
        view.invalidate();
    }

    private void stopRinging() {
        handler.removeCallbacks(timeout);
        if (vibrator != null) vibrator.cancel();
    }

    private void act(String action) {
        stopRinging();
        LinkService.callAction(key, action);
        view.status = action.equals("answer") ? "Answering…" : action.equals("hangup") ? "Ending…" : "Declining…";
        view.invalidate();
        if (!action.equals("answer")) {
            // The phone confirms with CALL_END; close anyway if it never comes.
            handler.postDelayed(new Runnable() {
                @Override public void run() { finish(); }
            }, 4_000);
        }
    }

    private class CallView extends View {
        private static final float V = 320f, C = 160f, BY = 250f, BX = 62f, BR = 36f;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint type = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
        String caller, app, status;
        boolean active;
        long since;
        private float sy = 1f;

        CallView(Context c) {
            super(c);
            setBackgroundColor(0xFF0E0E0D);
            type.setTextAlign(Paint.Align.CENTER);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            sy = h / (float) w;
        }

        @Override protected void onDraw(Canvas c) {
            float k = getWidth() / V;
            c.save();
            c.scale(k, k * sy);

            paint.setColor(0xFF5DCAA5);
            Icons.draw(c, Icons.Kind.CALL, C, 62f, 28f, paint);

            type.setTypeface(medium);
            type.setTextSize(18f);
            type.setLetterSpacing(0.1f);
            type.setColor(0xFF9C9A92);
            String label = status != null ? status : active ? "ON CALL" : "INCOMING CALL";
            c.drawText(label.toUpperCase(Locale.US), C, 106f, type);
            type.setLetterSpacing(0f);

            type.setTypeface(light);
            type.setTextSize(active ? 34f : 40f);
            type.setColor(0xFFFFFFFF);
            String name = caller == null || caller.isEmpty() ? "Unknown" : caller;
            c.drawText(TextUtils.ellipsize(name, type, 250f, TextUtils.TruncateAt.END).toString(), C, 152f, type);

            if (active) {
                type.setTypeface(light);
                type.setTextSize(44f);
                type.setColor(0xFF5DCAA5);
                c.drawText(elapsed(), C, 204f, type);
                bubble(c, C, 0xFFE24B4A, 135f);              // hang up
            } else {
                if (app != null && !app.isEmpty()) {
                    type.setTypeface(medium);
                    type.setTextSize(18f);
                    type.setColor(0xFF9C9A92);
                    c.drawText(app, C, 182f, type);
                }
                bubble(c, C - BX, 0xFFE24B4A, 135f);         // decline: handset tipped down
                bubble(c, C + BX, 0xFF1D9E75, 0f);           // answer
            }
            c.restore();
        }

        private String elapsed() {
            long s = Math.max(0, (SystemClock.elapsedRealtime() - since) / 1000);
            return s >= 3600
                    ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                    : String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
        }

        private void bubble(Canvas c, float x, int color, float rotate) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            c.drawCircle(x, BY, BR, paint);
            paint.setColor(0xFFFFFFFF);
            c.save();
            c.rotate(rotate, x, BY);
            Icons.draw(c, Icons.Kind.CALL, x, BY, 34f, paint);
            c.restore();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() != MotionEvent.ACTION_UP) return true;
            float k = getWidth() / V;
            float x = e.getX() / k, y = e.getY() / (k * sy);
            if (active) {
                if (Math.hypot(x - C, y - BY) < BR + 8f) act("hangup");
            } else if (Math.hypot(x - (C - BX), y - BY) < BR + 8f) {
                act("decline");
            } else if (Math.hypot(x - (C + BX), y - BY) < BR + 8f) {
                act("answer");
            } else {
                stopRinging();
            }
            return true;
        }
    }
}
