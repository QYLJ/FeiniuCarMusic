package com.feiniu.carmusic.update;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

public final class UpdateFileProvider extends ContentProvider {
    static final String AUTHORITY = "com.feiniu.carmusic.updates";
    static final String FILE_NAME = "feiniu-update.apk";

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File file = resolve(uri);
        MatrixCursor cursor = new MatrixCursor(new String[] {
                OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE
        });
        cursor.addRow(new Object[] { FILE_NAME, file.length() });
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read only");
        File file = resolve(uri);
        if (!file.isFile()) throw new FileNotFoundException(file.getAbsolutePath());
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private File resolve(Uri uri) {
        if (uri == null || !AUTHORITY.equals(uri.getAuthority())
                || !("/" + FILE_NAME).equals(uri.getPath())) {
            throw new IllegalArgumentException("invalid update uri");
        }
        return AppUpdater.downloadedApk(getContext());
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) {
        return 0;
    }
}
