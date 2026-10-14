package com.feiniu.carmusic.player;

import org.junit.Test;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class ProgressiveAudioFileTest {
    @Test public void bufferEndWaitsForRealBytesAndOnlyCompletedEndIsEof() throws Exception {
        File file = File.createTempFile("growing-audio", ".mp3");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ProgressiveAudioFile audio = new ProgressiveAudioFile(new AudioFile(file, "mp3", 44100, 0, 2, true))) {
            byte[] output = new byte[4];
            Future<Integer> read = executor.submit(() -> audio.readAt(0, output, 0, 4));
            try { read.get(100, TimeUnit.MILLISECONDS); fail("Temporary buffer end must wait"); }
            catch (TimeoutException expected) { }
            try (FileOutputStream writer = new FileOutputStream(file)) { writer.write(new byte[]{1, 2, 3, 4}); }
            audio.publish(4, -1);
            assertEquals(4, (int) read.get(1, TimeUnit.SECONDS));
            assertArrayEquals(new byte[]{1, 2, 3, 4}, output);
            assertEquals(-1, audio.size());
            Future<Integer> tail = executor.submit(() -> audio.readAt(4, output, 0, 4));
            try { tail.get(100, TimeUnit.MILLISECONDS); fail("No premature EOF"); }
            catch (TimeoutException expected) { }
            audio.finish();
            assertEquals(-1, (int) tail.get(1, TimeUnit.SECONDS));
            assertEquals(4, audio.size());
        } finally { executor.shutdownNow(); file.delete(); }
    }

    @Test public void cancellationUnblocksReaderWithoutWaitingForNetwork() throws Exception {
        File file = File.createTempFile("cancel-audio", ".mp3");
        ProgressiveAudioFile audio = new ProgressiveAudioFile(new AudioFile(file, "mp3", 44100, 0, 2, true));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> read = executor.submit(() -> audio.readAt(99, new byte[4], 0, 4));
            audio.close();
            try { read.get(1, TimeUnit.SECONDS); fail("A cancelled source must fail"); }
            catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof IOException); }
        } finally { audio.close(); executor.shutdownNow(); file.delete(); }
    }

    @Test public void downloadFailureDoesNotPretendToBeSuccessfulEof() throws Exception {
        File file = File.createTempFile("failed-audio", ".mp3");
        try (ProgressiveAudioFile audio = new ProgressiveAudioFile(new AudioFile(file, "mp3", 44100, 0, 2, true))) {
            audio.publish(0, 1000);
            assertEquals(1000, audio.size());
            audio.fail(new IOException("Disconnected"));
            assertTrue(audio.hasFailure());
            try { audio.readAt(0, new byte[4], 0, 4); fail("Failed download must fail its reader"); }
            catch (IOException expected) { assertEquals("Disconnected", expected.getMessage()); }
        } finally { file.delete(); }
    }
}
