package com.feiniu.carmusic.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public final class PlayerLyricView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<TimedLyricLine> lines = new ArrayList<TimedLyricLine>();
    private final ArrayList<Float> normalSizes = new ArrayList<Float>();
    private final ArrayList<Float> activeSizes = new ArrayList<Float>();
    private long basePositionMs;
    private long baseRealtimeMs;
    private boolean playing;
    private String emptyText = "正在加载歌词…";

    public PlayerLyricView(Context context) {
        super(context);
        init();
    }

    public PlayerLyricView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    void setLines(List<TimedLyricLine> values) {
        lines.clear();
        if (values != null) lines.addAll(values);
        emptyText = lines.isEmpty() ? "此歌曲暂无歌词" : "";
        calculateTextSizes();
        invalidate();
    }

    void setError(String message) {
        lines.clear();
        normalSizes.clear();
        activeSizes.clear();
        emptyText = message == null ? "" : message;
        invalidate();
    }

    void setPlaybackPosition(long positionMs, boolean isPlaying) {
        basePositionMs = Math.max(0L, positionMs);
        baseRealtimeMs = SystemClock.elapsedRealtime();
        playing = isPlaying;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width != oldWidth) calculateTextSizes();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (lines.isEmpty()) {
            drawEmptyText(canvas);
            return;
        }

        long positionMs = basePositionMs;
        if (playing) {
            positionMs += Math.max(0L, SystemClock.elapsedRealtime() - baseRealtimeMs);
        }
        int active = findActiveLine(positionMs);
        float progress = 0f;
        if (active + 1 < lines.size()) {
            long span = lines.get(active + 1).timeMs - lines.get(active).timeMs;
            if (span > 0L) {
                progress = Math.max(0f, Math.min(1f,
                        (positionMs - lines.get(active).timeMs) / (float) span));
            }
        }

        float lineHeight = Math.max(dp(34f), Math.min(dp(58f), getHeight() / 4f));
        float center = getHeight() / 2f - progress * lineHeight;
        int first = Math.max(0, active - 6);
        int last = Math.min(lines.size() - 1, active + 7);
        for (int i = first; i <= last; i++) {
            float y = center + (i - active) * lineHeight;
            if (y < -lineHeight || y > getHeight() + lineHeight) continue;
            boolean current = i == active;
            paint.setColor(current ? Color.rgb(242, 238, 245) : Color.rgb(135, 130, 145));
            paint.setTextSize(current ? activeSizes.get(i) : normalSizes.get(i));
            paint.setFakeBoldText(current);
            canvas.drawText(lines.get(i).text, getWidth() / 2f, y, paint);
        }
        paint.setFakeBoldText(false);
        if (playing && getWindowToken() != null) postInvalidateDelayed(16L);
    }

    private void drawEmptyText(Canvas canvas) {
        paint.setColor(Color.rgb(170, 165, 180));
        paint.setTextSize(sp(18f));
        paint.setFakeBoldText(false);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = getHeight() / 2f - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(emptyText, getWidth() / 2f, baseline, paint);
    }

    private int findActiveLine(long positionMs) {
        int low = 0;
        int high = lines.size() - 1;
        int result = 0;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (lines.get(middle).timeMs <= positionMs) {
                result = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return result;
    }

    private void calculateTextSizes() {
        normalSizes.clear();
        activeSizes.clear();
        if (getWidth() <= 0) return;
        float available = Math.max(1f, getWidth() - dp(32f));
        for (TimedLyricLine line : lines) {
            normalSizes.add(fittedSize(line.text, 20f, false, available));
            activeSizes.add(fittedSize(line.text, 26f, true, available));
        }
        paint.setFakeBoldText(false);
    }

    private float fittedSize(String text, float startSp, boolean bold, float available) {
        float size = startSp;
        paint.setFakeBoldText(bold);
        paint.setTextSize(sp(size));
        while (paint.measureText(text) > available && size > 14f) {
            size -= 1f;
            paint.setTextSize(sp(size));
        }
        return paint.getTextSize();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }
}
