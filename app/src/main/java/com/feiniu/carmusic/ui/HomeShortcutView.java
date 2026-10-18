package com.feiniu.carmusic.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextUtils;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.widget.TextView;
import com.feiniu.carmusic.R;

public final class HomeShortcutView extends TextView {
    private final MusicIcons icons = new MusicIcons();
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final String icon;

    public HomeShortcutView(Context context, AttributeSet attrs) {
        super(context, attrs);
        icon = attrs.getAttributeValue("http://schemas.android.com/apk/res/android", "tag");
        setClickable(true);
        setFocusable(true);
    }

    @Override protected void onDraw(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        float fontScale = getResources().getDisplayMetrics().scaledDensity * UiAppearance.multiplier(getContext());
        boolean compact = getHeight() < 90 * density * Math.max(1, UiAppearance.textScale(getContext()));
        float inset = (compact ? 11 : 16) * density;
        int tint = getResources().getColor(R.color.text_primary);
        boolean titleOnly = getHeight() < 60 * density * Math.max(1, UiAppearance.textScale(getContext()));
        if (!titleOnly) icons.draw(canvas, icon, inset + (compact ? 8 : 10) * density,
                inset + (compact ? 7 : 10) * density, (compact ? 16 : 22) * density, tint, false);
        if (getWidth() > 140 * density) {
            icons.draw(canvas, icon, getWidth() - 30 * density, getHeight() / 2f,
                    68 * density, 0x18ffffff, false);
        }
        String[] lines = getText().toString().split("\n", 2);
        textPaint.setColor(tint);
        textPaint.setTextSize((compact ? 14 : 16) * fontScale);
        textPaint.setFakeBoldText(true);
        float available = Math.max(0, getWidth() - inset * 2);
        float baseline = titleOnly ? getHeight() / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
                : getHeight() - 10 * density - (!compact && lines.length > 1 ? 18 * fontScale : 0);
        canvas.drawText(TextUtils.ellipsize(lines[0], textPaint, available, TextUtils.TruncateAt.END).toString(),
                inset, baseline, textPaint);
        if (!compact && lines.length > 1) {
            textPaint.setFakeBoldText(false);
            textPaint.setTextSize((compact ? 11 : 12) * fontScale);
            textPaint.setColor(0xffd0c6d0);
            canvas.drawText(TextUtils.ellipsize(lines[1], textPaint, available, TextUtils.TruncateAt.END).toString(),
                    inset, getHeight() - 10 * density, textPaint);
        }
        if (isPressed() || isFocused()) canvas.drawColor(0x14ffffff);
    }
}
