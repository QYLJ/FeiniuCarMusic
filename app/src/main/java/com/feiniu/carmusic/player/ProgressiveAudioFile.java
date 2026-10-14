package com.feiniu.carmusic.player;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;

/** Only published, genuinely written bytes are readable; temporary buffer end is not EOF. */
final class ProgressiveAudioFile implements Closeable {
    final AudioFile audio;
    private final RandomAccessFile input;
    private long available;
    private long expected = -1L;
    private boolean complete, closed;
    private IOException failure;

    ProgressiveAudioFile(AudioFile audio) throws IOException {
        this.audio = audio;
        input = new RandomAccessFile(audio.file, "r");
    }

    synchronized void publish(long bytes, long total) {
        if (closed) return;
        available = bytes;
        if (total > 0L) expected = total;
        notifyAll();
    }

    synchronized void finish() {
        if (closed) return;
        available = audio.file.length();
        expected = available;
        complete = true;
        notifyAll();
    }

    synchronized void fail(Exception error) {
        failure = error instanceof IOException ? (IOException) error
                : new IOException(error.getMessage(), error);
        notifyAll();
    }

    synchronized boolean isComplete() { return complete; }
    synchronized boolean hasFailure() { return failure != null; }
    synchronized long availableBytes() { return available; }
    synchronized long size() { return expected; }

    synchronized int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
        if (position < 0 || offset < 0 || size < 0 || offset > buffer.length - size)
            throw new IOException("音频读取范围无效");
        if (size == 0) return 0;
        while (!closed && failure == null && position >= available && !complete) {
            try { wait(); }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("音频缓冲已取消");
            }
        }
        if (closed) throw new IOException("音频缓冲已关闭");
        if (failure != null) throw failure;
        if (position >= available) return -1;
        int count = (int) Math.min(Math.min(available - position, size), 64 * 1024L);
        input.seek(position);
        input.readFully(buffer, offset, count);
        return count;
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        notifyAll();
        input.close();
    }
}
