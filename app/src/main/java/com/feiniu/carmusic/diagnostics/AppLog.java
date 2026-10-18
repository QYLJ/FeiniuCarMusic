package com.feiniu.carmusic.diagnostics;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

/** Small private rolling log used to build user-approved feedback evidence. */
public final class AppLog {
    private static final String TAG = "FeiniuCarMusic";
    private static final long MAX_LOG_BYTES = 512L * 1024L;
    private static final int MAX_CRASH_BYTES = 96 * 1024;
    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "(?i)(password|passwd|pwd|token|authorization|cookie|secret|session|sign)\\s*([:=])\\s*([^\\s,;&]+)");
    private static final Pattern URL_SECRET = Pattern.compile(
            "(?i)([?&](?:password|passwd|pwd|token|authorization|cookie|secret|session|sign)=)[^&\\s]+");
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[a-z0-9._~+\\-/=]+");

    private static File directory;
    private static Thread.UncaughtExceptionHandler previousHandler;
    private static boolean initialized;

    private AppLog() { }

    public static synchronized void initialize(Context context) {
        if (initialized) return;
        directory = new File(context.getFilesDir(), "diagnostics");
        if (!directory.exists()) directory.mkdirs();
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread thread, Throwable error) {
                crash(thread, error);
                if (previousHandler != null) previousHandler.uncaughtException(thread, error);
            }
        });
        initialized = true;
        info("log.ready", "private rolling log initialized");
    }

    public static void info(String event, String detail) {
        write("I", event, detail, null);
    }

    public static void warn(String event, String detail) {
        write("W", event, detail, null);
    }

    public static void error(String event, Throwable error) {
        write("E", event, error == null ? "unknown error" : safeMessage(error), error);
    }

    private static synchronized void write(String level, String event, String detail, Throwable error) {
        String safe = redact(detail == null ? "" : detail);
        String line = timestamp() + " +" + SystemClock.elapsedRealtime() + "ms " + level + "/"
                + cleanEvent(event) + " " + safe + "\n";
        if ("E".equals(level)) Log.e(TAG, cleanEvent(event) + ": " + safe, error);
        else if ("W".equals(level)) Log.w(TAG, cleanEvent(event) + ": " + safe);
        else Log.i(TAG, cleanEvent(event) + ": " + safe);
        if (directory == null) return;
        File current = new File(directory, "runtime.log");
        if (current.length() + line.length() * 2L > MAX_LOG_BYTES) rotate(current);
        append(current, line);
    }

    private static synchronized void crash(Thread thread, Throwable error) {
        if (directory == null) return;
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        String content = timestamp() + " fatal thread=" + redact(thread == null ? "unknown" : thread.getName())
                + "\n" + redact(trace.toString());
        if (content.length() > MAX_CRASH_BYTES) content = content.substring(0, MAX_CRASH_BYTES) + "\n<truncated>";
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(new File(directory, "last-crash.log"), false);
            output.write(content.getBytes("UTF-8"));
            output.getFD().sync();
        } catch (Exception ignored) {
        } finally {
            if (output != null) try { output.close(); } catch (Exception ignored) { }
        }
        write("E", "app.crash", safeMessage(error), null);
    }

    public static synchronized String recent(int maxBytes) {
        if (directory == null || maxBytes <= 0) return "";
        byte[] previous = readTail(new File(directory, "runtime-previous.log"), maxBytes / 3);
        byte[] current = readTail(new File(directory, "runtime.log"), maxBytes - previous.length);
        try {
            StringBuilder result = new StringBuilder();
            if (previous.length > 0) result.append("--- previous ---\n").append(new String(previous, "UTF-8"));
            if (current.length > 0) result.append("--- current ---\n").append(new String(current, "UTF-8"));
            return redact(result.toString());
        } catch (Exception ignored) {
            return "";
        }
    }

    public static synchronized String lastCrash() {
        if (directory == null) return "";
        byte[] bytes = readTail(new File(directory, "last-crash.log"), MAX_CRASH_BYTES);
        try { return redact(new String(bytes, "UTF-8")); }
        catch (Exception ignored) { return ""; }
    }

    public static synchronized long size() {
        if (directory == null) return 0L;
        return new File(directory, "runtime.log").length()
                + new File(directory, "runtime-previous.log").length()
                + new File(directory, "last-crash.log").length();
    }

    static String redact(String text) {
        if (text == null || text.length() == 0) return "";
        String value = KEY_VALUE_SECRET.matcher(text).replaceAll("$1$2<redacted>");
        value = URL_SECRET.matcher(value).replaceAll("$1<redacted>");
        return BEARER.matcher(value).replaceAll("Bearer <redacted>");
    }

    private static String cleanEvent(String event) {
        if (event == null) return "event";
        return event.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return redact(error.getClass().getSimpleName() + (message == null ? "" : ": " + message));
    }

    private static void rotate(File current) {
        File previous = new File(directory, "runtime-previous.log");
        if (previous.exists()) previous.delete();
        if (current.exists()) current.renameTo(previous);
    }

    private static void append(File file, String value) {
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(file, true);
            output.write(value.getBytes("UTF-8"));
            output.flush();
        } catch (Exception ignored) {
        } finally {
            if (output != null) try { output.close(); } catch (Exception ignored) { }
        }
    }

    private static byte[] readTail(File file, int limit) {
        if (!file.isFile() || limit <= 0) return new byte[0];
        FileInputStream input = null;
        try {
            long skip = Math.max(0L, file.length() - limit);
            input = new FileInputStream(file);
            while (skip > 0) {
                long amount = input.skip(skip);
                if (amount <= 0) break;
                skip -= amount;
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            return output.toByteArray();
        } catch (Exception ignored) {
            return new byte[0];
        } finally {
            if (input != null) try { input.close(); } catch (Exception ignored) { }
        }
    }

    private static String timestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }
}
