package com.feiniu.carmusic.ui;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.feiniu.carmusic.data.UiPreferences;
import java.util.WeakHashMap;

/** Applies font preferences without recreating an activity or interrupting its playback/list state. */
final class UiAppearance {
    private static final WeakHashMap<TextView, Float> BASE_SIZES = new WeakHashMap<TextView, Float>();

    static float multiplier(Context context) {
        return new UiPreferences(context).fontMultiplier(context.getResources().getDisplayMetrics());
    }

    static float textScale(Context context) {
        return multiplier(context) * context.getResources().getDisplayMetrics().scaledDensity
                / context.getResources().getDisplayMetrics().density;
    }

    static void textSize(TextView view, float baseSp) {
        BASE_SIZES.put(view, baseSp);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSp * multiplier(view.getContext()));
    }

    static void apply(View root) {
        apply(root, multiplier(root.getContext()));
    }

    private static void apply(View view, float multiplier) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            Float base = BASE_SIZES.get(text);
            if (base == null) {
                base = text.getTextSize() / text.getResources().getDisplayMetrics().scaledDensity;
                BASE_SIZES.put(text, base);
            }
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, base * multiplier);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) apply(group.getChildAt(i), multiplier);
        }
        view.invalidate();
    }
}
