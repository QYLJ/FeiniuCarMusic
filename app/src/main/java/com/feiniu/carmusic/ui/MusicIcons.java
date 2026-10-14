package com.feiniu.carmusic.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** One 24-unit icon grid for every screen; works on Android 4 without vector resources. */
final class MusicIcons {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF bounds = new RectF();

    void draw(Canvas canvas, String icon, float x, float y, float size, int color, boolean filled) {
        int save = canvas.save();
        canvas.translate(x - size / 2f, y - size / 2f);
        canvas.scale(size / 24f, size / 24f);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        path.reset();
        if ("home".equals(icon)) {
            path.moveTo(3, 10); path.lineTo(12, 3); path.lineTo(21, 10);
            path.moveTo(5, 9); path.lineTo(5, 21); path.lineTo(10, 21);
            path.lineTo(10, 14); path.lineTo(14, 14); path.lineTo(14, 21);
            path.lineTo(19, 21); path.lineTo(19, 9);
        } else if ("favorite".equals(icon) || "heart".equals(icon)) {
            path.moveTo(12, 21);
            path.cubicTo(9, 18.5f, 3, 14.5f, 3, 9);
            path.cubicTo(3, 3.5f, 9, 2.5f, 12, 7);
            path.cubicTo(15, 2.5f, 21, 3.5f, 21, 9);
            path.cubicTo(21, 14.5f, 15, 18.5f, 12, 21); path.close();
            if (filled) paint.setStyle(Paint.Style.FILL);
        } else if ("recent".equals(icon)) {
            canvas.drawCircle(12, 12, 9, paint);
            path.moveTo(12, 6.5f); path.lineTo(12, 12); path.lineTo(16, 14.5f);
        } else if ("album".equals(icon)) {
            canvas.drawCircle(12, 12, 9, paint); canvas.drawCircle(12, 12, 2.5f, paint);
            bounds.set(6, 6, 18, 18); canvas.drawArc(bounds, 205, 45, false, paint);
        } else if ("artist".equals(icon)) {
            canvas.drawCircle(12, 7.5f, 4, paint);
            path.moveTo(4, 21); path.lineTo(4, 19);
            path.cubicTo(4, 12, 20, 12, 20, 19); path.lineTo(20, 21);
        } else if ("genre".equals(icon)) {
            line(canvas, 4, 9, 4, 15); line(canvas, 8, 5, 8, 19);
            line(canvas, 12, 3, 12, 21); line(canvas, 16, 7, 16, 17); line(canvas, 20, 9, 20, 15);
        } else if ("library".equals(icon) || "brand_music".equals(icon)) {
            path.moveTo(9, 17); path.lineTo(9, 5); path.lineTo(20, 3); path.lineTo(20, 15);
            path.moveTo(9, 9); path.lineTo(20, 7);
            canvas.drawPath(path, paint); path.reset();
            paint.setStyle(Paint.Style.FILL);
            bounds.set(3, 16, 9, 21); canvas.drawOval(bounds, paint);
            bounds.set(14, 14, 20, 19); canvas.drawOval(bounds, paint);
        } else if ("playlist".equals(icon) || "queue".equals(icon)) {
            for (int i = 0; i < 3; i++) {
                float row = 6 + i * 6;
                line(canvas, 8, row, 21, row);
                paint.setStyle(Paint.Style.FILL); canvas.drawCircle(3.5f, row, 1.1f, paint);
                paint.setStyle(Paint.Style.STROKE);
            }
        } else if ("roam".equals(icon)) {
            canvas.drawCircle(12, 12, 9, paint);
            path.moveTo(16, 8); path.lineTo(14, 14); path.lineTo(8, 16);
            path.lineTo(10, 10); path.close();
        } else if ("search".equals(icon)) {
            canvas.drawCircle(10.5f, 10.5f, 6.5f, paint); line(canvas, 15.5f, 15.5f, 21, 21);
        } else if ("refresh".equals(icon)) {
            bounds.set(4, 4, 20, 20); canvas.drawArc(bounds, 40, 280, false, paint);
            path.moveTo(21, 4); path.lineTo(20, 10); path.lineTo(14, 9);
        } else if ("logout".equals(icon)) {
            path.moveTo(10, 4); path.lineTo(4, 4); path.lineTo(4, 20); path.lineTo(10, 20);
            path.moveTo(9, 12); path.lineTo(21, 12);
            path.moveTo(17, 8); path.lineTo(21, 12); path.lineTo(17, 16);
        } else if ("back".equals(icon)) {
            path.moveTo(15, 5); path.lineTo(8, 12); path.lineTo(15, 19);
        } else if ("play".equals(icon) || "previous".equals(icon) || "next".equals(icon)) {
            paint.setStyle(Paint.Style.FILL);
            if ("previous".equals(icon)) {
                bounds.set(4, 4, 6.5f, 20); canvas.drawRoundRect(bounds, 1, 1, paint);
                path.moveTo(19, 4.5f); path.lineTo(8, 12); path.lineTo(19, 19.5f);
            } else {
                if ("next".equals(icon)) {
                    bounds.set(17.5f, 4, 20, 20); canvas.drawRoundRect(bounds, 1, 1, paint);
                    path.moveTo(5, 4.5f); path.lineTo(16, 12); path.lineTo(5, 19.5f);
                } else {
                    path.moveTo(7, 4); path.lineTo(20, 12); path.lineTo(7, 20);
                }
            }
            path.close();
        } else if ("pause".equals(icon)) {
            paint.setStyle(Paint.Style.FILL);
            bounds.set(6, 4, 10, 20); canvas.drawRoundRect(bounds, 1, 1, paint);
            bounds.set(14, 4, 18, 20); canvas.drawRoundRect(bounds, 1, 1, paint);
        } else if ("shuffle".equals(icon)) {
            path.moveTo(3, 6); path.lineTo(6, 6); path.lineTo(17, 18); path.lineTo(21, 18);
            path.moveTo(3, 18); path.lineTo(6, 18); path.lineTo(10, 14);
            path.moveTo(14, 10); path.lineTo(17, 6); path.lineTo(21, 6);
            path.moveTo(18, 3); path.lineTo(21, 6); path.lineTo(18, 9);
            path.moveTo(18, 15); path.lineTo(21, 18); path.lineTo(18, 21);
        } else if ("sequence".equals(icon)) {
            line(canvas, 3, 5, 16, 5); line(canvas, 3, 11, 16, 11);
            path.moveTo(3, 17); path.lineTo(21, 17);
            path.moveTo(18, 14); path.lineTo(21, 17); path.lineTo(18, 20);
        } else if ("repeat".equals(icon) || "repeat_one".equals(icon)) {
            path.moveTo(6, 5); path.lineTo(18, 5); path.quadTo(21, 5, 21, 9); path.lineTo(21, 12);
            path.moveTo(15, 2); path.lineTo(18, 5); path.lineTo(15, 8);
            path.moveTo(18, 19); path.lineTo(6, 19); path.quadTo(3, 19, 3, 15); path.lineTo(3, 12);
            path.moveTo(9, 16); path.lineTo(6, 19); path.lineTo(9, 22);
            if ("repeat_one".equals(icon)) {
                path.moveTo(10, 10); path.lineTo(12, 9); path.lineTo(12, 15);
            }
        }
        canvas.drawPath(path, paint);
        canvas.restoreToCount(save);
    }

    private void line(Canvas canvas, float x1, float y1, float x2, float y2) {
        canvas.drawLine(x1, y1, x2, y2, paint);
    }
}
