package com.feiniu.carmusic.feedback;

import android.content.Context;
import android.content.SharedPreferences;

import com.feiniu.carmusic.R;
import com.feiniu.carmusic.diagnostics.AppLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Comparator;

/** Durable local outbox: evidence is saved before upload and removed only after AList acknowledges it. */
public final class FeedbackRepository {
    private static final int MAX_PENDING = 5;
    private static final int MAX_FILE_BYTES = 1024 * 1024;

    private FeedbackRepository() { }

    public static Pending prepare(Context context, String category, String message,
                                  String contact, boolean includeLogs) throws Exception {
        FeedbackEvidence.Report report = FeedbackEvidence.create(context, category, message, contact, includeLogs);
        byte[] encrypted = FeedbackCrypto.encrypt(report.json, context.getString(R.string.feedback_public_key));
        if (encrypted.length > MAX_FILE_BYTES) throw new IllegalStateException("反馈证据超过大小限制，请关闭运行日志后重试");
        File directory = pendingDirectory(context);
        if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("无法创建反馈暂存目录");
        File target = new File(directory, report.id + ".fnfeedback");
        File partial = new File(directory, report.id + ".part");
        write(partial, encrypted);
        if (!partial.renameTo(target)) {
            partial.delete();
            throw new IllegalStateException("无法保存反馈证据");
        }
        trim(directory, target);
        AppLog.info("feedback.prepared", "reportId=" + report.id + " bytes=" + encrypted.length);
        return new Pending(report.id, target);
    }

    public static AlistFeedbackClient.UploadReceipt upload(Context context, Pending pending) throws Exception {
        byte[] encrypted = read(pending.file);
        AlistFeedbackClient client = new AlistFeedbackClient(
                context.getString(R.string.feedback_upload_url), context.getString(R.string.feedback_upload_path));
        try {
            AlistFeedbackClient.UploadReceipt receipt = client.upload(encrypted, pending.id);
            saveReceipt(context, receipt);
            if (!pending.file.delete()) AppLog.warn("feedback.cleanup", "could not delete sent envelope");
            AppLog.info("feedback.uploaded", "reportId=" + receipt.reportId + " sha256=" + receipt.sha256);
            return receipt;
        } catch (Exception error) {
            AppLog.error("feedback.upload_failed", error);
            throw error;
        }
    }

    public static Pending oldestPending(Context context) {
        File[] files = pendingFiles(context);
        if (files.length == 0) return null;
        return new Pending(idFrom(files[0]), files[0]);
    }

    public static int pendingCount(Context context) { return pendingFiles(context).length; }

    public static String lastReceipt(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("feedback_receipts", Context.MODE_PRIVATE);
        String id = prefs.getString("id", "");
        if (id.length() == 0) return "尚未提交反馈";
        String sha = prefs.getString("sha256", "");
        return "最近已提交 " + id + (sha.length() == 0 ? "" : " · " + sha.substring(0, Math.min(12, sha.length())));
    }

    private static void saveReceipt(Context context, AlistFeedbackClient.UploadReceipt receipt) {
        context.getSharedPreferences("feedback_receipts", Context.MODE_PRIVATE).edit()
                .putString("id", receipt.reportId)
                .putString("path", receipt.remotePath)
                .putString("sha256", receipt.sha256)
                .putInt("bytes", receipt.bytes)
                .putLong("time", System.currentTimeMillis())
                .apply();
    }

    private static File pendingDirectory(Context context) {
        return new File(new File(context.getFilesDir(), "feedback"), "pending");
    }

    private static File[] pendingFiles(Context context) {
        File[] files = pendingDirectory(context).listFiles();
        if (files == null) return new File[0];
        int count = 0;
        for (File file : files) if (file.isFile() && file.getName().endsWith(".fnfeedback")) count++;
        File[] result = new File[count];
        int index = 0;
        for (File file : files) if (file.isFile() && file.getName().endsWith(".fnfeedback")) result[index++] = file;
        Arrays.sort(result, new Comparator<File>() {
            @Override public int compare(File a, File b) { return a.lastModified() < b.lastModified() ? -1 : a.lastModified() == b.lastModified() ? 0 : 1; }
        });
        return result;
    }

    private static void trim(File directory, File keep) {
        File[] files = pendingFilesFrom(directory);
        for (int i = 0; i < files.length - MAX_PENDING; i++) if (!files[i].equals(keep)) files[i].delete();
    }

    private static File[] pendingFilesFrom(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return new File[0];
        Arrays.sort(files, new Comparator<File>() {
            @Override public int compare(File a, File b) { return a.lastModified() < b.lastModified() ? -1 : a.lastModified() == b.lastModified() ? 0 : 1; }
        });
        return files;
    }

    private static void write(File file, byte[] bytes) throws Exception {
        FileOutputStream output = new FileOutputStream(file);
        try { output.write(bytes); output.getFD().sync(); }
        finally { output.close(); }
    }

    private static byte[] read(File file) throws Exception {
        if (!file.isFile() || file.length() <= 0 || file.length() > MAX_FILE_BYTES) {
            throw new IllegalStateException("暂存反馈文件无效");
        }
        byte[] bytes = new byte[(int) file.length()];
        FileInputStream input = new FileInputStream(file);
        try {
            int offset = 0;
            while (offset < bytes.length) {
                int read = input.read(bytes, offset, bytes.length - offset);
                if (read < 0) throw new IllegalStateException("暂存反馈文件不完整");
                offset += read;
            }
            return bytes;
        } finally { input.close(); }
    }

    private static String idFrom(File file) {
        String name = file.getName();
        return name.substring(0, name.length() - ".fnfeedback".length());
    }

    public static final class Pending {
        public final String id;
        public final File file;
        Pending(String id, File file) { this.id = id; this.file = file; }
    }
}
