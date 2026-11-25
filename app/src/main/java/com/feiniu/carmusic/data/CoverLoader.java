package com.feiniu.carmusic.data;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CoverLoader {
    private static volatile CoverLoader instance;

    private final FnApiClient api;
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>((int)
            Math.max(256L, Math.min(8 * 1024L, Runtime.getRuntime().maxMemory() / 1024L / 32L))) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getRowBytes() * value.getHeight() / 1024);
        }
    };
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, List<WeakReference<ImageView>>> pending = new HashMap<String, List<WeakReference<ImageView>>>();
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
        final String key = coverId == null || coverId.length() == 0 ? "" : api.coverUrl(coverId, size);
        for (ImageView target : targets) {
            if (target == null) continue;
            target.setTag(key);
            target.setImageDrawable(null);
        }
        if (coverId == null || coverId.length() == 0) return;
        Bitmap existing = cache.get(key);
        if (existing != null) {
            setBitmap(key, targets, existing);
            return;
        }
        final List<WeakReference<ImageView>> requestTargets;
        synchronized (pending) {
            List<WeakReference<ImageView>> waiting = pending.get(key);
            boolean alreadyQueued = waiting != null;
            if (!alreadyQueued) {
                if (pending.size() >= 64) return;
                waiting = new ArrayList<WeakReference<ImageView>>();
                pending.put(key, waiting);
            }
            for (ImageView target : targets) if (target != null) waiting.add(new WeakReference<ImageView>(target));
            if (alreadyQueued) return;
            requestTargets = waiting;
        }
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    synchronized (pending) {
                        List<WeakReference<ImageView>> waiting = pending.get(key);
                        boolean needed = false;
                        if (waiting != null) for (WeakReference<ImageView> ref : waiting) {
                            ImageView view = ref.get();
                            if (view != null && key.equals(view.getTag())) { needed = true; break; }
                        }
                        if (!needed) return;
                    }
                    final Bitmap bitmap = api.downloadCover(coverId, size);
                    if (bitmap == null) return;
                    cache.put(key, bitmap);
                    final List<WeakReference<ImageView>> waiting;
                    synchronized (pending) { waiting = pending.remove(key); }
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (waiting != null) for (WeakReference<ImageView> ref : waiting) {
                                ImageView view = ref.get();
                                if (view != null) setBitmap(key, new ImageView[] {view}, bitmap);
                            }
                        }
                    });
                } catch (Exception ignored) {
                } catch (OutOfMemoryError exhausted) {
                    cache.evictAll();
                } finally {
                    synchronized (pending) { if (pending.get(key) == requestTargets) pending.remove(key); }
                }
            }
        });
    }

    public static void trimMemory(int level) {
        CoverLoader loader = instance;
        if (loader == null) return;
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) loader.cache.evictAll();
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
