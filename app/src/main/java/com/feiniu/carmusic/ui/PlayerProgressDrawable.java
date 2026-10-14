package com.feiniu.carmusic.ui;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import com.feiniu.carmusic.R;

/** Keeps the progress track thin even when legacy SeekBar expands it to the thumb height. */
final class PlayerProgressDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();
    private final float thickness;
    private final int trackColor, progressColor;

    PlayerProgressDrawable(Resources resources) {
        thickness = 3 * resources.getDisplayMetrics().density;
        trackColor = resources.getColor(R.color.divider);
        progressColor = resources.getColor(R.color.primary);
    }

    @Override public void draw(Canvas canvas) {
        float center = getBounds().exactCenterY();
        track.set(getBounds().left, center - thickness / 2, getBounds().right, center + thickness / 2);
        paint.setColor(trackColor);
        canvas.drawRoundRect(track, thickness / 2, thickness / 2, paint);
        track.right = track.left + track.width() * getLevel() / 10000f;
        paint.setColor(progressColor);
        canvas.drawRoundRect(track, thickness / 2, thickness / 2, paint);
    }

    @Override protected boolean onLevelChange(int level) { invalidateSelf(); return true; }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicHeight() { return Math.round(thickness); }
}
