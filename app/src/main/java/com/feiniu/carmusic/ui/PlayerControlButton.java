package com.feiniu.carmusic.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import com.feiniu.carmusic.R;

public final class PlayerControlButton extends View {
    static final int BACK = 1, MODE = 2, PREVIOUS = 3, PLAY = 4, NEXT = 5, HEART = 6, QUEUE = 7;
    private final MusicIcons icons = new MusicIcons();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private int type;
    private String label = "";
    private boolean active;

    public PlayerControlButton(Context context) { this(context, null); }
    public PlayerControlButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClickable(true);
        setFocusable(true);
    }

    PlayerControlButton configure(int iconType, String text) {
        type = iconType;
        label = text == null ? "" : text;
        setContentDescription(label);
        invalidate();
        return this;
    }

    void setActive(boolean value) {
        active = value;
        if (type == HEART) {
            setSelected(value);
            setContentDescription(value ? "取消收藏" : "收藏");
        }
        invalidate();
    }

    @Override protected void drawableStateChanged() { super.drawableStateChanged(); invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        boolean compact = getHeight() < dp(76);
        float cx = getWidth() / 2f;
        float cy = label.length() == 0 ? getHeight() / 2f : getHeight() * .38f;
        float iconSize = dp(compact ? 22 : 26);
        int color = getResources().getColor(type == HEART && active ? R.color.primary : R.color.text_primary);
        if (type == PLAY) {
            paint.setColor(getResources().getColor(isPressed() ? R.color.primary_dark : R.color.primary));
            float radius = dp(compact ? 21 : 27);
            canvas.drawCircle(cx, cy, radius, paint);
            if (isFocused()) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1));
                paint.setColor(getResources().getColor(R.color.text_primary));
                canvas.drawCircle(cx, cy, radius, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            iconSize = dp(compact ? 21 : 25);
            color = 0xffffffff;
        } else if (isPressed() || isFocused()) {
            paint.setColor(getResources().getColor(R.color.surface_pressed));
            bounds.set(dp(4), dp(4), getWidth() - dp(4), getHeight() - dp(4));
            canvas.drawRoundRect(bounds, dp(10), dp(10), paint);
        }
        String icon = type == BACK ? "back" : type == PREVIOUS ? "previous" : type == NEXT ? "next"
                : type == PLAY ? (active ? "pause" : "play") : type == HEART ? "heart"
                : type == QUEUE ? "queue" : "sequence";
        if (type == MODE) icon = "随机".equals(label) ? "shuffle" : "单曲".equals(label) ? "repeat_one"
                : "列表循环".equals(label) ? "repeat" : "sequence";
        icons.draw(canvas, icon, cx, cy, iconSize, color, type == HEART && active);
        if (label.length() > 0) {
            paint.setColor(getResources().getColor(R.color.text_secondary));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize((compact ? 11 : 13) * getResources().getDisplayMetrics().scaledDensity);
            canvas.drawText(label, cx, getHeight() - dp(compact ? 5 : 9), paint);
        }
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
}
