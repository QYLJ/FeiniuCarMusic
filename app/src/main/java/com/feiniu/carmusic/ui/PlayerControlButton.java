package com.feiniu.carmusic.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import com.feiniu.carmusic.R;

public final class PlayerControlButton extends View {
    static final int BACK = 1, MODE = 2, PREVIOUS = 3, PLAY = 4, NEXT = 5, HEART = 6, QUEUE = 7;
    private final MusicIcons icons = new MusicIcons();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
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
        float cy = getHeight() / 2f;
        float iconSize = dp(compact ? 22 : 26);
        if (type == MODE || type == HEART || type == QUEUE) iconSize = dp(compact ? 26 : 28);
        if (type == PREVIOUS || type == NEXT) iconSize = dp(compact ? 30 : 32);
        int color = getResources().getColor(type == HEART && active ? R.color.primary : R.color.text_primary);
        if (type == PLAY) {
            paint.setColor(getResources().getColor(isPressed() ? R.color.primary_dark : R.color.primary));
            float radius = Math.min(dp(compact ? 28 : 33), Math.min(getWidth(), getHeight()) * .44f);
            canvas.drawCircle(cx, cy, radius, paint);
            if (isFocused()) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1));
                paint.setColor(getResources().getColor(R.color.text_primary));
                canvas.drawCircle(cx, cy, radius, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            iconSize = dp(compact ? 26 : 30);
            color = 0xffffffff;
        } else if (isPressed() || isFocused()) {
            paint.setColor(getResources().getColor(R.color.surface_pressed));
            canvas.drawCircle(cx, cy, Math.min(getWidth(), getHeight()) * .42f, paint);
        }
        String icon = type == BACK ? "back" : type == PREVIOUS ? "previous" : type == NEXT ? "next"
                : type == PLAY ? (active ? "pause" : "play") : type == HEART ? "heart"
                : type == QUEUE ? "queue" : "sequence";
        if (type == MODE) icon = "随机".equals(label) ? "shuffle" : "单曲".equals(label) ? "repeat_one"
                : "列表循环".equals(label) ? "repeat" : "sequence";
        icons.draw(canvas, icon, cx, cy, iconSize, color, type == HEART && active);
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
}
