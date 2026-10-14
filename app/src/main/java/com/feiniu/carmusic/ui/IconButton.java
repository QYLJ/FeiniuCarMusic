package com.feiniu.carmusic.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.widget.Button;
import com.feiniu.carmusic.R;

/** Retains Button text for accessibility while drawing a font-independent icon. */
public final class IconButton extends Button {
    private final MusicIcons icons = new MusicIcons();
    private final String icon;

    public IconButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        String tag = attrs == null ? null : attrs.getAttributeValue("http://schemas.android.com/apk/res/android", "tag");
        icon = tag == null ? "play" : tag;
    }

    @Override protected void onDraw(Canvas canvas) {
        boolean toggle = "toggle".equals(icon);
        boolean playing = "暂停".contentEquals(getText());
        boolean favorite = "favorite".equals(icon);
        int color = getResources().getColor(favorite && isSelected() ? R.color.primary : R.color.text_primary);
        if (!isEnabled()) color = (color & 0x00ffffff) | 0x66000000;
        float size = Math.min(22f * getResources().getDisplayMetrics().density, Math.min(getWidth(), getHeight()) * .55f);
        icons.draw(canvas, toggle ? (playing ? "pause" : "play") : icon,
                getWidth() / 2f, getHeight() / 2f, size, color, favorite && isSelected());
    }
}
