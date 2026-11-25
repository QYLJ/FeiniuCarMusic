package com.feiniu.carmusic.player;

import com.feiniu.carmusic.data.FnApiClient;
import com.feiniu.carmusic.model.Track;
import java.io.*;
import java.net.*;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

/** Gives Android 4/5 a seekable HTTP source while Java handles NAS authentication and TLS. */
final class OriginalAudioStream implements Closeable {
    private static final ExecutorService CLEANUP = Executors.newFixedThreadPool(2, new ThreadFactory() {
        @Override public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "OriginalAudioCleanup");
            thread.setDaemon(true);
            return thread;
        }
    });
    private final ServerSocket server;
    private final ExecutorService workers = new ThreadPoolExecutor(3, 3, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(3));
    private final Set<Socket> clients = new HashSet<Socket>();
    private final Set<HttpURLConnection> upstreams = new HashSet<HttpURLConnection>();
    private final String path = "/" + UUID.randomUUID().toString() + "/audio";
    private final FnApiClient api;
    private final String guid;
    private final AudioFile localFile;
    private volatile boolean closed;
    private final TrackFileCache cache;
    private final Track track;

    OriginalAudioStream(FnApiClient api, Track track, TrackFileCache cache) throws IOException {
        this(api, track.guid, track, cache, null);
    }

    OriginalAudioStream(FnApiClient api, String guid) throws IOException {
        this(api, guid, null, null, null);
    }

    OriginalAudioStream(AudioFile localFile) throws IOException {
        this(null, null, null, null, localFile);
    }

    private OriginalAudioStream(FnApiClient api, String guid, Track track, TrackFileCache cache, AudioFile localFile) throws IOException {
        this.localFile = localFile;
        this.api = api;
        this.guid = guid;
        this.track = track;
        this.cache = cache;
        server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        workers.execute(new Runnable() {
            @Override public void run() {
                while (!closed) {
                    Socket accepted = null;
                    try {
                        accepted = server.accept();
                        final Socket client = accepted;
                        synchronized (OriginalAudioStream.this) {
                            if (closed) { client.close(); return; }
                            clients.add(client);
                        }
                        workers.execute(new Runnable() {
                            @Override public void run() { serve(client); }
                        });
                    } catch (IOException | RejectedExecutionException error) {
                        if (accepted != null) {
                            try { accepted.close(); } catch (IOException ignored) { }
                            synchronized (OriginalAudioStream.this) { clients.remove(accepted); }
                        }
                    }
                }
            }
        });
    }

    String url() { return "http://127.0.0.1:" + server.getLocalPort() + path; }

    private void serve(Socket client) {
        HttpURLConnection upstream = null;
        TrackFileCache.StreamCache saved = null;
        long cacheLength = -2L;
        try {
            client.setSoTimeout(10000);
            InputStream request = client.getInputStream();
            String first = readLine(request);
            String[] parts = first.split(" ");
            if (parts.length != 3 || !path.equals(parts[1]) || !("GET".equals(parts[0]) || "HEAD".equals(parts[0]))) {
                client.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"));
                return;
            }
            String range = null;
            int headerBytes = first.length();
            while (true) {
                String line = readLine(request);
                headerBytes += line.length() + 2;
                if (headerBytes > 16384) throw new IOException("请求头过长");
                if (line.length() == 0) break;
                if (line.regionMatches(true, 0, "Range:", 0, 6)) range = line.substring(6).trim();
            }
            if (closed) return;
            if (localFile != null) {
                serveFile(client.getOutputStream(), range, "HEAD".equals(parts[0]));
                return;
            }
            upstream = api.openTrackStream(guid, range);
            synchronized (this) {
                if (closed) return;
                upstreams.add(upstream);
            }
            int status = upstream.getResponseCode();
            OutputStream output = client.getOutputStream();
            StringBuilder headers = new StringBuilder("HTTP/1.1 " + status
                    + (status == 206 ? " Partial Content" : status == 416 ? " Range Not Satisfiable" : " OK") + "\r\nConnection: close\r\n");
            for (String name : new String[] {"Content-Type", "Content-Length", "Content-Range", "Accept-Ranges"}) {
                String value = upstream.getHeaderField(name);
                if (value != null && value.indexOf('\r') < 0 && value.indexOf('\n') < 0) {
                    headers.append(name).append(": ").append(value).append("\r\n");
                }
            }
            headers.append("\r\n");
            output.write(headers.toString().getBytes("US-ASCII"));
            output.flush();
            if (status == 416 || "HEAD".equals(parts[0])) return;
            cacheLength = completeResponseLength(status, upstream.getHeaderField("Content-Range"), upstream.getHeaderField("Content-Length"));
            if (cacheLength >= -1L && cache != null) {
                try { saved = cache.cacheOriginalStream(track, api); } catch (Exception ignored) { }
            }
            InputStream input = upstream.getInputStream();
            try {
                byte[] buffer = new byte[16384];
                int count;
                while (!closed && (count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    if (saved != null) {
                        try { saved.write(buffer, count); }
                        catch (Exception ignored) { saved.close(); saved = null; }
                    }
                }
                output.flush();
                if (!closed && saved != null) {
                    saved.finish(cacheLength);
                }
            } finally { input.close(); }
        } catch (Exception ignored) {
            // Closing a truncated response makes MediaPlayer report failure and use file fallback.
        } finally {
            if (saved != null) try { saved.close(); } catch (IOException ignored) { }
            synchronized (this) {
                clients.remove(client);
                if (upstream != null) upstreams.remove(upstream);
            }
            if (upstream != null) try { upstream.disconnect(); } catch (RuntimeException ignored) { }
            try { client.close(); } catch (IOException ignored) { }
        }
    }

    private void serveFile(OutputStream output, String range, boolean head) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(localFile.file, "r")) {
            long total = file.length(), first = 0, last = total - 1;
            if (range != null) {
                try {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("bytes=(\\d*)-(\\d*)").matcher(range);
                    if (!m.matches()) throw new NumberFormatException();
                    if (m.group(1).isEmpty()) {
                        long suffix = Long.parseLong(m.group(2));
                        first = Math.max(0, total - suffix);
                    } else {
                        first = Long.parseLong(m.group(1));
                        if (!m.group(2).isEmpty()) last = Math.min(last, Long.parseLong(m.group(2)));
                    }
                    if (first > last) throw new NumberFormatException();
                } catch (NumberFormatException invalid) {
                    output.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + total
                            + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
                    return;
                }
            }
            String mime = "application/octet-stream";
            if ("mp3".equalsIgnoreCase(localFile.codec)) mime = "audio/mpeg";
            if ("flac".equalsIgnoreCase(localFile.codec)) mime = "audio/flac";
            if ("aac".equalsIgnoreCase(localFile.codec) && localFile.transcoded) mime = "audio/aac";
            if ("opus".equalsIgnoreCase(localFile.codec) && localFile.transcoded) mime = "audio/ogg";
            long remaining = last - first + 1;
            String status = range == null ? "200 OK" : "206 Partial Content";
            String contentRange = range == null ? "" : "Content-Range: bytes " + first + "-" + last + "/" + total + "\r\n";
            output.write(("HTTP/1.1 " + status + "\r\n" + contentRange + "Accept-Ranges: bytes\r\nContent-Type: "
                    + mime + "\r\nContent-Length: " + remaining + "\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
            if (head) return;
            file.seek(first);
            byte[] buffer = new byte[16384];
            while (!closed && remaining > 0) {
                int count = file.read(buffer, 0, (int) Math.min(remaining, buffer.length));
                if (count < 0) throw new EOFException();
                output.write(buffer, 0, count);
                remaining -= count;
            }
        }
    }

    private static long completeResponseLength(int status, String range, String length) {
        long bytes = -1L;
        try { bytes = Long.parseLong(length); } catch (Exception ignored) { }
        if (status == 200) return bytes;
        if (status != 206 || range == null) return -2L;
        java.util.regex.Matcher full = java.util.regex.Pattern.compile("bytes 0-(\\d+)/(\\d+)").matcher(range);
        if (!full.matches()) return -2L;
        try {
            long end = Long.parseLong(full.group(1));
            long total = Long.parseLong(full.group(2));
            // Stagefright commonly requests bytes=0-; a full 206 response is still cacheable.
            return total > 0 && end == total - 1 && (bytes < 0 || bytes == total) ? total : -2L;
        } catch (NumberFormatException ignored) { return -2L; }
    }

    private static String readLine(InputStream input) throws IOException {
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = input.read()) != -1 && value != '\n') {
            if (value != '\r') line.append((char) value);
            if (line.length() > 8192) throw new IOException("请求行过长");
        }
        if (value == -1) throw new EOFException();
        return line.toString();
    }

    @Override public void close() {
        final HttpURLConnection[] connections;
        final Socket[] sockets;
        synchronized (this) {
            if (closed) return;
            closed = true;
            connections = upstreams.toArray(new HttpURLConnection[0]);
            sockets = clients.toArray(new Socket[0]);
        }
        try { server.close(); } catch (IOException ignored) { }
        workers.shutdownNow();
        // Android 4's disconnect() may drain the response body. Never do this on the UI thread,
        // or while holding the lock needed by a serving worker's finally block.
        CLEANUP.execute(new Runnable() {
            @Override public void run() {
                for (Socket client : sockets) try { client.close(); } catch (IOException ignored) { }
                for (HttpURLConnection connection : connections) {
                    try { connection.disconnect(); } catch (RuntimeException ignored) { }
                }
            }
        });
    }
}
