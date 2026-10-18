package com.feiniu.carmusic.player;

import org.junit.Test;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class KeyedLocksTest {
    @Test public void hashCollisionDoesNotBlockAnotherSong() throws Exception {
        assertEquals("Aa".hashCode(), "BB".hashCode());
        KeyedLocks locks = new KeyedLocks();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (KeyedLocks.Guard held = locks.acquire("Aa")) {
            assertTrue(worker.submit(() -> { try (KeyedLocks.Guard other = locks.acquire("BB")) { return true; } })
                    .get(1, TimeUnit.SECONDS));
        } finally { worker.shutdownNow(); }
    }

    @Test public void sameSongWaitsAndInterruptedWaiterDoesNotRemoveActiveLock() throws Exception {
        KeyedLocks locks = new KeyedLocks();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch waiting = new CountDownLatch(1), cancelled = new CountDownLatch(1);
        try (KeyedLocks.Guard held = locks.acquire("song")) {
            Future<?> first = worker.submit(() -> {
                waiting.countDown();
                try (KeyedLocks.Guard ignored = locks.acquire("song")) { fail(); }
                catch (InterruptedException expected) { cancelled.countDown(); }
            });
            assertTrue(waiting.await(1, TimeUnit.SECONDS));
            first.cancel(true);
            assertTrue(cancelled.await(1, TimeUnit.SECONDS));
            Future<Boolean> second = worker.submit(() -> { try (KeyedLocks.Guard ignored = locks.acquire("song")) { return true; } });
            try { second.get(100, TimeUnit.MILLISECONDS); fail("The original owner still holds the song"); }
            catch (TimeoutException expected) { }
            held.close();
            assertTrue(second.get(1, TimeUnit.SECONDS));
        } finally { worker.shutdownNow(); }
        try (KeyedLocks.Guard acquiredAgain = locks.acquire("song")) { assertNotNull(acquiredAgain); }
    }
}
