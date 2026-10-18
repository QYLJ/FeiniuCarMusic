package com.feiniu.carmusic.player;

import java.io.Closeable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/** Serialize the same resource without making unrelated songs share a hash bucket. */
final class KeyedLocks {
    private final Map<String, Entry> entries = new HashMap<String, Entry>();
    private static final class Entry {
        final ReentrantLock lock = new ReentrantLock();
        int users;
    }
    final class Guard implements Closeable {
        private final String key;
        private final Entry entry;
        private boolean closed;
        Guard(String key, Entry entry) { this.key = key; this.entry = entry; }
        @Override public void close() {
            if (closed) return;
            closed = true;
            entry.lock.unlock();
            release(key, entry);
        }
    }
    Guard acquire(String key) throws InterruptedException {
        Entry entry;
        synchronized (entries) {
            entry = entries.get(key);
            if (entry == null) { entry = new Entry(); entries.put(key, entry); }
            entry.users++;
        }
        try {
            entry.lock.lockInterruptibly();
            return new Guard(key, entry);
        } catch (InterruptedException error) {
            release(key, entry);
            throw error;
        }
    }
    private void release(String key, Entry entry) {
        synchronized (entries) { if (--entry.users == 0) entries.remove(key); }
    }
}
