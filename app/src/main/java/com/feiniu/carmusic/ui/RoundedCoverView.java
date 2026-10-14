package com.feiniu.carmusic.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.ImageView;

/** Rounded center-crop artwork using a shader supported by the API 14 renderer. */
public final class RoundedCoverView extends ImageView {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF bounds = new RectF();
    private final Matrix matrix = new Matrix();
    private final MusicIcons icons = new MusicIcons();
    private Bitmap bitmap;
    private BitmapShader shader;
    private int shaderWidth, shaderHeight;

    public RoundedCoverView(Context context, AttributeSet attrs) { super(context, attrs); }

    @Override protected void onDraw(Canvas canvas) {
        Drawable drawable = getDrawable();
        Bitmap current = drawable instanceof BitmapDrawable ? ((BitmapDrawable) drawable).getBitmap() : null;
        float radius = 8 * getResources().getDisplayMetrics().density;
        bounds.set(0, 0, getWidth(), getHeight());
        if (current == null) {
            bitmap = null;
            shader = null;
            paint.setShader(null);
            paint.setColor(0xff292733);
            canvas.drawRoundRect(bounds, radius, radius, paint);
            icons.draw(canvas, "library", getWidth() / 2f, getHeight() / 2f,
                    Math.min(getWidth(), getHeight()) * .38f, 0xff777280, false);
            return;
        }
        if (bitmap != current || shaderWidth != getWidth() || shaderHeight != getHeight()) {
            bitmap = current;
            shaderWidth = getWidth(); shaderHeight = getHeight();
            shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float scale = Math.max((float) getWidth() / bitmap.getWidth(), (float) getHeight() / bitmap.getHeight());
            matrix.setScale(scale, scale);
            matrix.postTranslate((getWidth() - bitmap.getWidth() * scale) / 2f,
                    (getHeight() - bitmap.getHeight() * scale) / 2f);
            shader.setLocalMatrix(matrix);
        }
        paint.setShader(shader);
        canvas.drawRoundRect(bounds, radius, radius, paint);
    }
}
