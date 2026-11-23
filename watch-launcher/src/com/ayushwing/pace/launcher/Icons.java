package com.ayushwing.pace.launcher;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Line icons drawn in code on a 24x24 grid with a 2-unit round stroke, so they
 * stay sharp at any bubble size and need no image assets.
 */
final class Icons {
    enum Kind { WORKOUT, ALARM, MUSIC, HISTORY, COMPASS, FILES, SETTINGS, APPS, STEPS, BATTERY, CLOSE, CHECK, PHONE, BELL }

    private Icons() { }

    /** Draws {@code kind} centred on (cx, cy), {@code size} virtual px wide. */
    static void draw(Canvas c, Kind kind, float cx, float cy, float size, Paint p) {
        float k = size / 24f;
        c.save();
        c.translate(cx - size / 2f, cy - size / 2f);
        c.scale(k, k);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        Path path = new Path();
        switch (kind) {
            case WORKOUT:
                c.drawCircle(15f, 4.5f, 2f, p);
                path.moveTo(8f, 10.5f); path.lineTo(12f, 8.5f); path.lineTo(15.5f, 11.5f); path.lineTo(18f, 12f);
                path.moveTo(12f, 8.5f); path.lineTo(10.5f, 14.5f); path.lineTo(14f, 17f); path.lineTo(13f, 21.5f);
                path.moveTo(10.5f, 14.5f); path.lineTo(8.5f, 18.5f); path.lineTo(5f, 19.5f);
                break;
            case ALARM:
                c.drawCircle(12f, 13f, 7.5f, p);
                path.moveTo(12f, 9f); path.lineTo(12f, 13f); path.lineTo(14.5f, 15f);
                path.moveTo(3.5f, 6.5f); path.lineTo(6.5f, 3.5f);
                path.moveTo(20.5f, 6.5f); path.lineTo(17.5f, 3.5f);
                break;
            case MUSIC:
                c.drawCircle(6.5f, 17.5f, 2.5f, p);
                c.drawCircle(17.5f, 15.5f, 2.5f, p);
                path.moveTo(9f, 17.5f); path.lineTo(9f, 5.5f); path.lineTo(20f, 3.5f); path.lineTo(20f, 15.5f);
                path.moveTo(9f, 9.5f); path.lineTo(20f, 7.5f);
                break;
            case HISTORY:
                c.drawArc(new RectF(4f, 4f, 20f, 20f), 200f, 290f, false, p);
                path.moveTo(3f, 7f); path.lineTo(4.6f, 10.6f); path.lineTo(8.2f, 9.2f);
                path.moveTo(12f, 8f); path.lineTo(12f, 12f); path.lineTo(15f, 14f);
                break;
            case COMPASS:
                c.drawCircle(12f, 12f, 9f, p);
                path.moveTo(15.5f, 8.5f); path.lineTo(13.5f, 13.5f); path.lineTo(8.5f, 15.5f);
                path.lineTo(10.5f, 10.5f); path.close();
                break;
            case FILES:
                path.moveTo(3f, 6.5f); path.lineTo(9.5f, 6.5f); path.lineTo(11.5f, 8.5f);
                path.lineTo(21f, 8.5f); path.lineTo(21f, 18.5f); path.lineTo(3f, 18.5f); path.close();
                break;
            case SETTINGS:
                c.drawCircle(12f, 12f, 3f, p);
                c.drawCircle(12f, 12f, 6.5f, p);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
                    path.moveTo(12f + 6.5f * cos, 12f + 6.5f * sin);
                    path.lineTo(12f + 9.5f * cos, 12f + 9.5f * sin);
                }
                break;
            case APPS:
                c.drawRoundRect(new RectF(4f, 4f, 10.5f, 10.5f), 2f, 2f, p);
                c.drawRoundRect(new RectF(13.5f, 4f, 20f, 10.5f), 2f, 2f, p);
                c.drawRoundRect(new RectF(4f, 13.5f, 10.5f, 20f), 2f, 2f, p);
                c.drawRoundRect(new RectF(13.5f, 13.5f, 20f, 20f), 2f, 2f, p);
                break;
            case STEPS:
                path.moveTo(8f, 3.5f); path.cubicTo(5f, 4f, 4.5f, 9f, 6f, 12f); path.lineTo(9.5f, 11f);
                path.cubicTo(10.5f, 8f, 10.5f, 3.5f, 8f, 3.5f);
                path.moveTo(6.5f, 15f); path.lineTo(9.8f, 14f); path.cubicTo(10.5f, 17f, 7.5f, 18f, 6.5f, 15f);
                path.moveTo(16f, 7.5f); path.cubicTo(19f, 8f, 19.5f, 13f, 18f, 16f); path.lineTo(14.5f, 15f);
                path.cubicTo(13.5f, 12f, 13.5f, 7.5f, 16f, 7.5f);
                path.moveTo(17.5f, 19f); path.lineTo(14.2f, 18f); path.cubicTo(13.5f, 21f, 16.5f, 22f, 17.5f, 19f);
                break;
            case BATTERY:
                c.drawRoundRect(new RectF(2.5f, 7f, 19.5f, 17f), 2.5f, 2.5f, p);
                path.moveTo(22f, 10.5f); path.lineTo(22f, 13.5f);
                break;
            case CLOSE:
                path.moveTo(6f, 6f); path.lineTo(18f, 18f);
                path.moveTo(18f, 6f); path.lineTo(6f, 18f);
                break;
            case CHECK:
                path.moveTo(5f, 12.5f); path.lineTo(10f, 17.5f); path.lineTo(19f, 7f);
                break;
            case BELL:
                path.moveTo(6f, 16.5f); path.lineTo(6f, 11f);
                path.cubicTo(6f, 7.5f, 8.5f, 5f, 12f, 5f);
                path.cubicTo(15.5f, 5f, 18f, 7.5f, 18f, 11f);
                path.lineTo(18f, 16.5f); path.lineTo(19.5f, 18f); path.lineTo(4.5f, 18f); path.close();
                path.moveTo(10f, 20.5f); path.lineTo(14f, 20.5f);
                path.moveTo(12f, 3f); path.lineTo(12f, 5f);
                break;
            case PHONE:
                c.drawRoundRect(new RectF(7f, 2.5f, 17f, 21.5f), 2.5f, 2.5f, p);
                path.moveTo(11f, 18f); path.lineTo(13f, 18f);
                break;
        }
        c.drawPath(path, p);
        c.restore();
    }
}
