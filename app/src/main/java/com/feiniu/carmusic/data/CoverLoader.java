package com.feiniu.carmusic.data;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CoverLoader {
    private static volatile CoverLoader instance;

    private final FnApiClient api;
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getRowBytes() * value.getHeight() / 1024;
        }
    };
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());

    private CoverLoader(Context context) {
        api = FnApiClient.get(context);
    }

    public static CoverLoader get(Context context) {
        if (instance == null) {
            synchronized (CoverLoader.class) {
                if (instance == null) instance = new CoverLoader(context.getApplicationContext());
            }
        }
        return instance;
    }

    public void load(final String coverId, final ImageView target, final int size) {
        load(coverId, size, target);
    }

    public void load(final String coverId, final int size, final ImageView... targets) {
        if (targets == null || targets.length == 0) return;
        for (ImageView target : targets) {
            if (target == null) continue;
            target.setTag(coverId);
            target.setImageDrawable(null);
        }
        if (coverId == null || coverId.length() == 0) return;
        Bitmap existing = cache.get(coverId);
        if (existing != null) {
            setBitmap(coverId, targets, existing);
            return;
        }
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Bitmap bitmap = api.downloadCover(coverId, size);
                    if (bitmap == null) return;
                    cache.put(coverId, bitmap);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            setBitmap(coverId, targets, bitmap);
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        });
    }

    private static void setBitmap(String coverId, ImageView[] targets, Bitmap bitmap) {
        for (ImageView target : targets) {
            if (target == null) continue;
            Object tag = target.getTag();
            if (tag != null && coverId.equals(String.valueOf(tag))) {
                target.setImageBitmap(bitmap);
            }
        }
    }
}
