package com.ayushwing.pace.launcher;

import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Draws the whole dial: the watch face and the bubble app grid.
 *
 * The Pace panel is 320x300 pixels, but its pixels are taller than they are
 * wide (262 x 246 dpi), so the framebuffer is physically a circle. Everything
 * is drawn in a 320x320 virtual square and squashed vertically, which keeps
 * circles round and type in proportion on the glass.
 *
 * Face -> swipe up -> grid. Grid -> swipe down at the top, back, or the button -> face.
 */
class DialView extends View {
    private static final float V = 320f;
    private static final float C = V / 2f;
    private static final int GOAL = 8000;

    // Palette tuned for a transflective panel: saturated fills, no gradients.
    private static final int BG = 0xFF0E0E0D;
    private static final int TICK = 0xFF2E2E2C;
    private static final int TICK_HOUR = 0xFF6B6A65;
    private static final int TEAL = 0xFF5DCAA5;
    private static final int MUTED = 0xFF9C9A92;
    private static final int WHITE = 0xFFFFFFFF;

    // Grid geometry, virtual px.
    private static final float D = 108f;              // bubble pitch
    private static final float ROW = D * 0.86f;       // honeycomb row spacing
    private static final int[] ROWS = {2, 3, 2, 3, 2};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint type = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Typeface condensed = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
    private final SimpleDateFormat hourFmt = new SimpleDateFormat("HH", Locale.US);
    private final SimpleDateFormat minFmt = new SimpleDateFormat("mm", Locale.US);
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEE d", Locale.US);
    private final GestureDetector gestures;
    private final List<App> apps = new ArrayList<>();

    private float sy = 1f;        // real px per virtual px, vertical (x is 1:1 at 320 wide)
    private float page = 0f;      // 0 = face, 1 = grid
    private float scroll = 0f;    // grid scroll, virtual px
    private int steps = -1;
    private int battery = -1;
    private App pressed;
    private App focused;
    private ValueAnimator pageAnim;

    /** One launchable app. */
    private static final class App {
        final ComponentName cn;
        final String label;
        final Icons.Kind icon;
        final int color;
        float x, y, r;

        App(String pkg, String cls, String label, Icons.Kind icon, int color) {
            this.cn = new ComponentName(pkg, cls);
            this.label = label;
            this.icon = icon;
            this.color = color;
        }
    }

    /**
     * Curated app list, in grid order (rows of 2 and 3). Every entry was opened
     * on the watch without crashing. Weather is left out because its activity
     * crashes without extras that only Huami's launcher and phone sync provide.
     */
    private static App[] catalogue() {
        return new App[] {
            new App("com.huami.watch.deskclock", "com.huami.watch.deskclock.AlarmClockActivity", "Alarm", Icons.Kind.ALARM, 0xFF378ADD),
            new App("watch.huami.com.mediaplayer", "watch.huami.com.mediaplayer.ui.NowPlayingActivity", "Music", Icons.Kind.MUSIC, 0xFF7F77DD),
            new App("com.huami.watch.newsport", "com.huami.watch.newsport.ui.history.SportHistoryActivity", "History", Icons.Kind.HISTORY, 0xFFBA7517),
            new App("com.ayushwing.pace.launcher", "com.ayushwing.pace.launcher.NotifyActivity", "Alerts", Icons.Kind.BELL, 0xFF1D9E75),
            new App("com.huami.watch.newsport", "com.huami.watch.newsport.ui.SportLauncherActivity", "Workout", Icons.Kind.WORKOUT, 0xFFD85A30),
            new App("com.huami.watch.compass", "com.huami.watch.compass.GeographicInfoActivity", "Compass", Icons.Kind.COMPASS, 0xFF185FA5),
            new App("com.amazmod.service", "com.amazmod.service.springboard.LauncherWearGridActivity", "Files", Icons.Kind.FILES, 0xFFD4537E),
            new App("com.huami.mobile.watchsettings", "com.huami.mobile.watchsettings.MainActivity", "Settings", Icons.Kind.SETTINGS, 0xFF5F5E5A),
            new App("com.huami.watch.launcher", "com.huami.watch.launcher.Launcher", "Huami", Icons.Kind.APPS, 0xFF444441),
        };
    }

    DialView(Context context) {
        super(context);
        setBackgroundColor(BG);
        type.setTextAlign(Paint.Align.CENTER);
        gestures = new GestureDetector(context, new Gestures());
    }

    // ------------------------------------------------------------------ state

    void setSteps(int s) {
        if (s >= 0 && s != steps) { steps = s; invalidate(); }
    }

    void setBattery(int pct) {
        if (pct != battery) { battery = pct; invalidate(); }
    }

    boolean onGrid() {
        return page > 0.5f;
    }

    void showFace() {
        animatePage(0f);
    }

    void reloadApps() {
        PackageManager pm = getContext().getPackageManager();
        apps.clear();
        for (App a : catalogue()) {
            try {
                ActivityInfo info = pm.getActivityInfo(a.cn, 0);
                if (info.enabled && info.applicationInfo.enabled) apps.add(a);
            } catch (PackageManager.NameNotFoundException e) {
                // Hidden or not installed.
            }
        }
        invalidate();
    }

    // ---------------------------------------------------------------- drawing

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        sy = h / (float) w;
    }

    @Override protected void onDraw(Canvas canvas) {
        float k = getWidth() / V;
        canvas.save();
        canvas.scale(k, k * sy);
        // Face zooms back and fades; grid zooms in from slightly large.
        if (page < 1f) layer(canvas, 1f - page * 0.15f, 1f - page, true);
        if (page > 0f) layer(canvas, 1.15f - page * 0.15f, page, false);
        canvas.restore();
    }

    private void layer(Canvas c, float scale, float alpha, boolean face) {
        int a = Math.round(alpha * 255);
        if (a <= 0) return;
        int save = a < 255 ? c.saveLayerAlpha(0, 0, V, V, a, Canvas.ALL_SAVE_FLAG) : c.save();
        c.scale(scale, scale, C, C);
        if (face) drawFace(c); else drawGrid(c);
        c.restoreToCount(save);
    }

    private void drawFace(Canvas c) {
        // 60 ticks round the rim; the step goal fills them in teal.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        int lit = steps > 0 ? Math.round(Math.min(1f, steps / (float) GOAL) * 60f) : 0;
        for (int i = 0; i < 60; i++) {
            boolean hour = i % 5 == 0;
            double ang = Math.toRadians(i * 6 - 90);
            float cos = (float) Math.cos(ang), sin = (float) Math.sin(ang);
            float outer = 154f, inner = hour ? 138f : 145f;
            paint.setStrokeWidth(hour ? 4f : 2.5f);
            paint.setColor(i < lit ? TEAL : (hour ? TICK_HOUR : TICK));
            c.drawLine(C + outer * cos, C + outer * sin, C + inner * cos, C + inner * sin, paint);
        }

        // Phone link: teal when the companion is connected, dim when not.
        paint.setColor(LinkService.isConnected() ? TEAL : TICK_HOUR);
        Icons.draw(c, Icons.Kind.PHONE, C, 52f, 18f, paint);

        Date now = new Date();
        type.setColor(MUTED);
        type.setTypeface(condensed);
        type.setTextSize(24f);
        type.setLetterSpacing(0.12f);
        c.drawText(dateFmt.format(now).toUpperCase(Locale.US), C, 92f, type);
        type.setLetterSpacing(0f);

        // Hours in white, minutes in teal, light weight.
        type.setTypeface(light);
        type.setTextSize(94f);
        String hh = hourFmt.format(now), mm = minFmt.format(now);
        float colon = type.measureText(":");
        float wh = type.measureText(hh), wm = type.measureText(mm);
        float left = C - (wh + colon + wm) / 2f;
        type.setTextAlign(Paint.Align.LEFT);
        type.setColor(WHITE);
        c.drawText(hh, left, 190f, type);
        type.setColor(MUTED);
        c.drawText(":", left + wh, 185f, type);
        type.setColor(TEAL);
        c.drawText(mm, left + wh + colon, 190f, type);
        type.setTextAlign(Paint.Align.CENTER);

        // Steps and battery under the time.
        type.setTypeface(medium);
        type.setTextSize(22f);
        String st = steps >= 0 ? String.format(Locale.US, "%,d", steps) : "--";
        String bt = battery >= 0 ? battery + "%" : "--";
        complication(c, Icons.Kind.STEPS, st, C - 50f, 234f, TEAL);
        complication(c, Icons.Kind.BATTERY, bt, C + 50f, 234f, MUTED);
    }

    private void complication(Canvas c, Icons.Kind icon, String value, float cx, float cy, int color) {
        float tw = type.measureText(value);
        float iw = 20f, gap = 6f;
        float start = cx - (iw + gap + tw) / 2f;
        paint.setColor(color);
        Icons.draw(c, icon, start + iw / 2f, cy - 7f, iw, paint);
        type.setColor(color);
        type.setTextAlign(Paint.Align.LEFT);
        c.drawText(value, start + iw + gap, cy, type);
        type.setTextAlign(Paint.Align.CENTER);
    }

    /** Honeycomb with a fisheye: bubbles shrink towards the rim. */
    private void drawGrid(Canvas c) {
        layoutGrid();
        focused = null;
        float best = Float.MAX_VALUE;
        for (App a : apps) {
            if (a.r < 4f) continue;
            float s = a == pressed ? 0.92f : 1f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(a.color);
            c.drawCircle(a.x, a.y, a.r * s, paint);
            paint.setColor(WHITE);
            Icons.draw(c, a.icon, a.x, a.y, a.r * s * 0.95f, paint);
            float dist = (float) Math.hypot(a.x - C, a.y - C);
            if (dist < best) { best = dist; focused = a; }
        }
        // Name of the bubble nearest the centre, at the bottom of the dial.
        if (focused != null && best < 60f) {
            type.setTypeface(medium);
            type.setTextSize(22f);
            type.setColor(WHITE);
            c.drawText(focused.label, C, 300f, type);
        }
    }

    private void layoutGrid() {
        int i = 0;
        for (int row = 0; i < apps.size(); row++) {
            int n = ROWS[row % ROWS.length];
            float y = C + (row - 1) * ROW - scroll;
            float x0 = C - (n - 1) * D / 2f;
            for (int k = 0; k < n && i < apps.size(); k++, i++) {
                App a = apps.get(i);
                a.x = x0 + k * D;
                a.y = y;
                float dist = (float) Math.hypot(a.x - C, a.y - C) / 150f;
                float scale = clamp(1f - dist * dist * 0.6f, 0f, 1f);
                a.r = D / 2f * 0.92f * scale;
            }
        }
    }

    private float maxScroll() {
        int rows = 0;
        for (int n = 0; n < apps.size(); rows++) n += ROWS[rows % ROWS.length];
        return Math.max(0f, (rows - 3) * ROW) + ROW * 0.5f;
    }

    /** Logs where each bubble sits in real screen pixels, for the flow tests. */
    private void logBubbles() {
        layoutGrid();
        float k = getWidth() / V;
        StringBuilder sb = new StringBuilder("grid");
        for (App a : apps) {
            if (a.r < 12f) continue;
            sb.append(' ').append(a.label).append('@')
              .append(Math.round(a.x * k)).append(',').append(Math.round(a.y * k * sy));
        }
        Log.d(HomeActivity.TAG, sb.toString());
    }

    // ----------------------------------------------------------------- input

    @Override public boolean onTouchEvent(MotionEvent e) {
        gestures.onTouchEvent(e);
        int act = e.getActionMasked();
        if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
            if (pressed != null) { pressed = null; invalidate(); }
            if (page == 1f && scroll < 0f) springBack();
            else if (page == 1f) logBubbles();
        }
        return true;
    }

    /** Eases an over-pulled grid back to its resting position. */
    private void springBack() {
        ValueAnimator va = ValueAnimator.ofFloat(scroll, 0f);
        va.setDuration(180);
        va.setInterpolator(new DecelerateInterpolator());
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator v) {
                if (page != 1f) { v.cancel(); return; }
                scroll = (Float) v.getAnimatedValue();
                invalidate();
                if (scroll == 0f) logBubbles();
            }
        });
        va.start();
    }

    private void animatePage(final float target) {
        if (pageAnim != null) pageAnim.cancel();
        if (target == 0f) scroll = 0f;
        if (page == target) return;
        pageAnim = ValueAnimator.ofFloat(page, target);
        pageAnim.setDuration(240);
        pageAnim.setInterpolator(new DecelerateInterpolator(1.6f));
        pageAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator va) {
                page = (Float) va.getAnimatedValue();
                invalidate();
                if (page == target) {
                    Log.d(HomeActivity.TAG, "page=" + (int) target);
                    if (target == 1f) logBubbles();
                }
            }
        });
        pageAnim.start();
    }

    private App hit(MotionEvent e) {
        float k = getWidth() / V;
        float x = e.getX() / k, y = e.getY() / (k * sy);
        for (App a : apps) {
            if (a.r > 12f && Math.hypot(x - a.x, y - a.y) <= a.r + 4f) return a;
        }
        return null;
    }

    private void launch(App a) {
        Log.d(HomeActivity.TAG, "launch " + a.label);
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.setComponent(a.cn);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                // Leaving an app returns to this launcher, not to an older app underneath.
                | Intent.FLAG_ACTIVITY_TASK_ON_HOME);
        try {
            getContext().startActivity(i);
        } catch (Exception ex) {
            Log.w(HomeActivity.TAG, "launch failed " + a.cn, ex);
        }
    }

    private class Gestures extends GestureDetector.SimpleOnGestureListener {
        @Override public boolean onDown(MotionEvent e) {
            if (page == 1f) {
                pressed = hit(e);
                invalidate();
            }
            return true;
        }

        @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
            pressed = null;
            if (page == 1f) {
                scroll = clamp(scroll + dy / (getWidth() / V * sy), -ROW * 0.5f, maxScroll());
                invalidate();
            }
            return true;
        }

        @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
            if (Math.abs(vy) < Math.abs(vx)) return false;
            if (page == 0f && vy < 0) animatePage(1f);
            else if (page == 1f && vy > 0 && scroll <= -ROW * 0.25f) animatePage(0f);
            return true;
        }

        @Override public boolean onSingleTapUp(MotionEvent e) {
            if (page != 1f) return false;
            App a = hit(e);
            if (a != null) launch(a);
            return a != null;
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
