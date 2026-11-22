package com.jojo.gh.arabic.launcher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * مزوّد محتوى آمن وخفيف (بدون مكتبات خارجية) لمشاركة صور البلاغات المخزّنة حصرياً
 * داخل مجلد cacheDir/feedback_attachments مع تطبيقات المشاركة مثل Instagram بدون أخطاء صلاحيات.
 */
public final class FeedbackAttachmentProvider extends ContentProvider {

    public static final String ATTACHMENTS_SUBDIR = "feedback_attachments";

    public static String getAuthority(Context context) {
        return context.getPackageName() + ".feedbackfiles";
    }

    public static File getAttachmentsDir(Context context) {
        File dir = new File(context.getCacheDir(), ATTACHMENTS_SUBDIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static Uri buildUriForFile(Context context, File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(getAuthority(context))
                .appendPath(file.getName())
                .build();
    }

    private File resolveSafeFile(Uri uri) throws FileNotFoundException {
        Context ctx = getContext();
        if (ctx == null || uri == null) {
            throw new FileNotFoundException("Invalid context or URI");
        }
        String last = uri.getLastPathSegment();
        if (last == null || last.trim().isEmpty()
                || last.contains("..") || last.contains("/") || last.contains("\\")) {
            throw new FileNotFoundException("Invalid attachment name");
        }
        File dir = getAttachmentsDir(ctx);
        File target = new File(dir, last);
        try {
            String dirCanon = dir.getCanonicalPath();
            String targetCanon = target.getCanonicalPath();
            if (!targetCanon.startsWith(dirCanon + File.separator)) {
                throw new FileNotFoundException("Path traversal blocked");
            }
        } catch (FileNotFoundException fnf) {
            throw fnf;
        } catch (Throwable t) {
            throw new FileNotFoundException("Canonical check failed");
        }
        if (!target.isFile()) {
            throw new FileNotFoundException("Attachment not found");
        }
        return target;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File f = resolveSafeFile(uri);
            String[] cols = (projection != null && projection.length > 0)
                    ? projection
                    : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            MatrixCursor cursor = new MatrixCursor(cols, 1);
            Object[] row = new Object[cols.length];
            for (int i = 0; i < cols.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) {
                    row[i] = f.getName();
                } else if (OpenableColumns.SIZE.equals(cols[i])) {
                    row[i] = f.length();
                } else {
                    row[i] = null;
                }
            }
            cursor.addRow(row);
            return cursor;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public String getType(Uri uri) {
        String name = uri != null ? uri.getLastPathSegment() : "";
        if (name != null && name.toLowerCase().endsWith(".png")) {
            return "image/png";
        }
        if (name != null && name.toLowerCase().endsWith(".json")) {
            return "application/json";
        }
        return "image/jpeg";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolveSafeFile(uri);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }
}
