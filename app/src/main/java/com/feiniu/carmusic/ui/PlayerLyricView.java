package com.feiniu.carmusic.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public final class PlayerLyricView extends View {
    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<TimedLyricLine> lines = new ArrayList<TimedLyricLine>();
    private final ArrayList<StaticLayout> normalLayouts = new ArrayList<StaticLayout>();
    private final ArrayList<StaticLayout> activeLayouts = new ArrayList<StaticLayout>();
    private float lineHeight;
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
        normalLayouts.clear();
        activeLayouts.clear();
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
        if (width != oldWidth || height != oldHeight) calculateTextSizes();
    }

    void refreshTextSizes() { calculateTextSizes(); invalidate(); }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (lines.isEmpty() || activeLayouts.size() != lines.size()) {
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

        // Keep both lines of the active lyric visible when there is only room for one lyric block.
        if (getHeight() < lineHeight * 2) progress = 0f;
        float center = getHeight() / 2f - progress * lineHeight;
        int first = Math.max(0, active - 6);
        int last = Math.min(lines.size() - 1, active + 7);
        for (int i = first; i <= last; i++) {
            float y = center + (i - active) * lineHeight;
            if (y < -lineHeight || y > getHeight() + lineHeight) continue;
            boolean current = i == active;
            StaticLayout layout = current ? activeLayouts.get(i) : normalLayouts.get(i);
            layout.getPaint().setColor(current ? Color.rgb(242, 238, 245) : Color.rgb(181, 175, 191));
            int save = canvas.save();
            canvas.translate((getWidth() - layout.getWidth()) / 2f, y - layout.getHeight() / 2f);
            layout.draw(canvas);
            canvas.restoreToCount(save);
        }
        paint.setFakeBoldText(false);
        if (playing && getWindowToken() != null) postInvalidateDelayed(16L);
    }

    private void drawEmptyText(Canvas canvas) {
        paint.setColor(Color.rgb(170, 165, 180));
        paint.setTextSize(sp(getHeight() < dp(200) ? 15f : 18f));
        paint.setFakeBoldText(false);
        StaticLayout layout = layout(emptyText, new TextPaint(paint), Math.max(1, getWidth() - (int) dp(24)));
        int save = canvas.save();
        canvas.translate((getWidth() - layout.getWidth()) / 2f, (getHeight() - layout.getHeight()) / 2f);
        layout.draw(canvas);
        canvas.restoreToCount(save);
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
        normalLayouts.clear();
        activeLayouts.clear();
        if (getWidth() <= 0) return;
        int available = Math.max(1, getWidth() - (int) dp(24f));
        boolean compact = getHeight() < dp(200);
        lineHeight = Math.max(dp(36f), Math.min(dp(60f), getHeight() / 4f));
        for (TimedLyricLine line : lines) {
            TextPaint normal = new TextPaint(paint);
            normal.setTextSize(sp(compact ? 16f : 20f));
            normal.setFakeBoldText(false);
            TextPaint active = new TextPaint(paint);
            active.setTextSize(sp(compact ? 21f : 26f));
            active.setFakeBoldText(true);
            StaticLayout normalLayout = layout(line.text, normal, available);
            StaticLayout activeLayout = layout(line.text, active, available);
            normalLayouts.add(normalLayout);
            activeLayouts.add(activeLayout);
            lineHeight = Math.max(lineHeight, Math.max(normalLayout.getHeight(), activeLayout.getHeight()) + dp(14));
        }
    }

    /** Keep long lyrics readable, wrapping up to two lines instead of shrinking them to tiny text. */
    private StaticLayout layout(String text, TextPaint textPaint, int available) {
        StaticLayout result = new StaticLayout(text, textPaint, available, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false);
        if (result.getLineCount() > 2) {
            int firstEnd = result.getLineEnd(0);
            String first = text.substring(0, firstEnd).trim();
            String rest = text.substring(firstEnd).replace('\n', ' ').trim();
            CharSequence second = TextUtils.ellipsize(rest, textPaint, available, TextUtils.TruncateAt.END);
            result = new StaticLayout(first + "\n" + second, textPaint, available,
                    Layout.Alignment.ALIGN_CENTER, 1f, 0f, false);
        }
        return result;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity * UiAppearance.multiplier(getContext());
    }
}
