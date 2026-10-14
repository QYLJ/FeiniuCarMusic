package com.feiniu.carmusic.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.widget.TextView;
import com.feiniu.carmusic.R;

public final class NavItemView extends TextView {
    private final MusicIcons icons = new MusicIcons();
    private final String icon;

    public NavItemView(Context context) { this(context, null); }

    public NavItemView(Context context, AttributeSet attrs) {
        super(context, attrs);
        String tag = attrs == null ? null : attrs.getAttributeValue("http://schemas.android.com/apk/res/android", "tag");
        icon = tag == null ? "" : tag;
    }

    @Override protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        boolean brand = "brand_music".equals(icon);
        float density = getResources().getDisplayMetrics().density;
        int color = brand ? 0xffffffff : getResources().getColor(isSelected() ? R.color.primary : R.color.nav_icon);
        icons.draw(canvas, icon, brand ? getWidth() / 2f : 20 * density,
                getHeight() / 2f, (brand ? 20 : 18) * density, color, false);
        super.onDraw(canvas);
    }
}
