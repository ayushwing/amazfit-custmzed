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
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.List;
import java.util.Locale;

/**
 * Full-screen card for notifications mirrored from the phone. Newest first.
 * Tap the card for the next one; the left bubble dismisses it on the phone
 * too, the right bubble just closes the card.
 */
public class NotifyActivity extends Activity {
    private CardView card;
    private int index;

    private final BroadcastReceiver changed = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            refresh();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        card = new CardView(this);
        setContentView(card);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        index = 0;
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        registerReceiver(changed, new IntentFilter(LinkService.ACTION_CHANGED));
        refresh();
    }

    @Override protected void onPause() {
        super.onPause();
        unregisterReceiver(changed);
    }

    private boolean hadNotes;

    private void refresh() {
        List<LinkService.Note> notes = LinkService.notes();
        if (notes.isEmpty()) {
            // Dismissed the last one: close. Opened with none: say so.
            if (hadNotes) finish(); else card.show(null, 0, 0);
            return;
        }
        hadNotes = true;
        if (index >= notes.size()) index = 0;
        card.show(notes.get(index), index, notes.size());
    }

    private class CardView extends View {
        private static final float V = 320f, C = 160f;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint type = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
        private LinkService.Note note;
        private int pos, count;
        private float sy = 1f;

        CardView(Context c) {
            super(c);
            setBackgroundColor(0xFF0E0E0D);
        }

        void show(LinkService.Note n, int pos, int count) {
            this.note = n;
            this.pos = pos;
            this.count = count;
            invalidate();
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            sy = h / (float) w;
        }

        @Override protected void onDraw(Canvas c) {
            float k = getWidth() / V;
            c.save();
            c.scale(k, k * sy);
            if (note == null) {
                paint.setColor(0xFF5DCAA5);
                Icons.draw(c, Icons.Kind.CHECK, C, 120f, 56f, paint);
                type.setTextAlign(Paint.Align.CENTER);
                type.setTypeface(medium);
                type.setTextSize(26f);
                type.setColor(0xFFFFFFFF);
                c.drawText("All caught up", C, 190f, type);
                type.setTypeface(regular);
                type.setTextSize(20f);
                type.setColor(0xFF9C9A92);
                c.drawText(LinkService.isConnected() ? "Phone connected" : "Phone not connected", C, 222f, type);
                c.restore();
                return;
            }

            type.setTextAlign(Paint.Align.CENTER);
            type.setTypeface(medium);
            type.setTextSize(20f);
            type.setColor(0xFF5DCAA5);
            String head = note.app.toUpperCase(Locale.US);
            if (count > 1) head += "  " + (pos + 1) + "/" + count;
            type.setLetterSpacing(0.08f);
            c.drawText(head, C, 64f, type);
            type.setLetterSpacing(0f);

            type.setColor(0xFFFFFFFF);
            type.setTextSize(28f);
            c.drawText(ellipsize(note.title, 230f), C, 102f, type);

            type.setTypeface(regular);
            type.setTextSize(22f);
            type.setColor(0xFFD3D1C7);
            type.setTextAlign(Paint.Align.LEFT);
            StaticLayout body = new StaticLayout(note.text, type, 240,
                    Layout.Alignment.ALIGN_CENTER, 1.05f, 0f, false);
            c.save();
            c.translate(C - 120f, 116f);
            c.clipRect(0, 0, 240, 104);
            body.draw(c);
            c.restore();

            bubble(c, C - 44f, 262f, 0xFFD85A30, Icons.Kind.CLOSE);
            bubble(c, C + 44f, 262f, 0xFF444441, Icons.Kind.CHECK);
            c.restore();
        }

        private void bubble(Canvas c, float x, float y, int color, Icons.Kind icon) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            c.drawCircle(x, y, 32f, paint);
            paint.setColor(0xFFFFFFFF);
            Icons.draw(c, icon, x, y, 30f, paint);
        }

        private String ellipsize(String s, float width) {
            return TextUtils.ellipsize(s, type, width, TextUtils.TruncateAt.END).toString();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() != MotionEvent.ACTION_UP) return true;
            if (note == null) {
                finish();
                return true;
            }
            float k = getWidth() / V;
            float x = e.getX() / k, y = e.getY() / (k * sy);
            if (Math.hypot(x - (C - 44f), y - 262f) < 40f) {
                LinkService.dismiss(getContext(), note.key);
            } else if (Math.hypot(x - (C + 44f), y - 262f) < 40f) {
                finish();
            } else {
                index++;
                refresh();
            }
            return true;
        }
    }
}
