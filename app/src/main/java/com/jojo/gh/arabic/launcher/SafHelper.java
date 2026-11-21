package com.jojo.gh.arabic.launcher;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * محرك الوصول عبر إطار التخزين (SAF) لمعرّب JoJo's Bizarre Adventure: Golden Hymn.
 */
public final class SafHelper {

    public static final String AUTHORITY = "com.android.externalstorage.documents";

    private SafHelper() {
    }

    public static String resolveInstalledGamePkg(Context context) {
        if (context != null) {
            PackageManager pm = context.getPackageManager();
            for (String pkg : LauncherConfig.GAME_PACKAGES) {
                try {
                    pm.getPackageInfo(pkg, 0);
                    return pkg;
                } catch (Throwable ignored) {
                }
            }
        }
        return LauncherConfig.GAME_PACKAGE;
    }

    public static boolean isValidGameTreeDocId(String docId) {
        if (docId == null || docId.isEmpty()) {
            return false;
        }
        int colon = docId.indexOf(':');
        if (colon <= 0 || colon + 1 >= docId.length()) {
            return false;
        }
        String rel = docId.substring(colon + 1);
        if ("Android".equals(rel) || "Android/data".equals(rel)) {
            return true;
        }
        for (String pkg : LauncherConfig.GAME_PACKAGES) {
            String base = "Android/data/" + pkg;
            if (base.equals(rel) || rel.startsWith(base + "/")) {
                return true;
            }
        }
        return false;
    }

    public static String resolveFilesDocId(Context context, Uri treeUri) {
        try {
            String treeId = DocumentsContract.getTreeDocumentId(treeUri);
            int colon = treeId != null ? treeId.indexOf(':') : -1;
            String prefix = colon > 0 ? treeId.substring(0, colon + 1) : "primary:";
            String rel = (colon > 0 && colon + 1 < treeId.length()) ? treeId.substring(colon + 1) : "";

            for (String pkg : LauncherConfig.GAME_PACKAGES) {
                String base = "Android/data/" + pkg;
                if (base.equals(rel) || rel.startsWith(base + "/")) {
                    return prefix + base + "/files";
                }
            }
            for (String pkg : LauncherConfig.GAME_PACKAGES) {
                String candidate = prefix + "Android/data/" + pkg + "/files";
                if (docExists(context, treeUri, candidate + "/" + LauncherConfig.DEFAULT_FORCE_SUBDIR)
                        || docExists(context, treeUri, candidate + "/" + LauncherConfig.DEFAULT_CONFIG_SUBDIR)) {
                    return candidate;
                }
            }
            return prefix + "Android/data/" + resolveInstalledGamePkg(context) + "/files";
        } catch (Throwable t) {
            return "primary:Android/data/" + LauncherConfig.GAME_PACKAGE + "/files";
        }
    }

    public static void cleanupInvalidTreePermissions(Context context) {
        try {
            ContentResolver cr = context.getContentResolver();
            for (UriPermission perm : cr.getPersistedUriPermissions()) {
                Uri uri = perm.getUri();
                if (!isValidGameTreeDocId(DocumentsContract.getTreeDocumentId(uri))) {
                    int flags = (perm.isReadPermission() ? Intent.FLAG_GRANT_READ_URI_PERMISSION : 0)
                            | (perm.isWritePermission() ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0);
                    if (flags != 0) {
                        cr.releasePersistableUriPermission(uri, flags);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static Uri findGrantedTreeUri(Context context) {
        try {
            for (UriPermission perm : context.getContentResolver().getPersistedUriPermissions()) {
                if (perm.isReadPermission() && perm.isWritePermission()) {
                    Uri uri = perm.getUri();
                    if (isValidGameTreeDocId(DocumentsContract.getTreeDocumentId(uri))) {
                        return uri;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static boolean isDocumentsUiPackageBlocking(PackageInfo info) {
        if (info == null) {
            return Build.VERSION.SDK_INT >= 34;
        }
        long vc = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        if (vc >= 340000000L || (vc >= 34 && vc < 1000)) {
            return true;
        }
        return !(vc >= 29 && vc <= 33)
                && !(vc >= 290000000L && vc < 340000000L)
                && Build.VERSION.SDK_INT >= 34;
    }

    public static String findNonBlockingDocumentsUiPackage(Context context) {
        if (Build.VERSION.SDK_INT < 30) {
            return "";
        }
        try {
            PackageManager pm = context.getPackageManager();
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            ResolveInfo resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (resolved != null && resolved.activityInfo != null && resolved.activityInfo.packageName != null) {
                String pkg = resolved.activityInfo.packageName;
                if (!"android".equals(pkg) && !isDocumentsUiPackageBlocking(pm.getPackageInfo(pkg, 0))) {
                    return pkg;
                }
            }
            List<ResolveInfo> list = pm.queryIntentActivities(intent, 0);
            if (list != null) {
                for (ResolveInfo ri : list) {
                    if (ri.activityInfo != null && ri.activityInfo.packageName != null) {
                        String pkg = ri.activityInfo.packageName;
                        if (pkg.contains("documentsui") && !isDocumentsUiPackageBlocking(pm.getPackageInfo(pkg, 0))) {
                            return pkg;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static boolean canAutoJumpToGameFolder(Context context) {
        return Build.VERSION.SDK_INT < 30 || findNonBlockingDocumentsUiPackage(context) != null;
    }

    public static Intent buildOpenTreeIntent(Context context) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        );
        intent.putExtra("android.content.extra.SHOW_ADVANCED", true);
        intent.putExtra("android.provider.extra.SHOW_ADVANCED", true);
        if (Build.VERSION.SDK_INT >= 26) {
            String initialDocId = "primary:Android/data/" + resolveInstalledGamePkg(context);
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                    DocumentsContract.buildDocumentUri(AUTHORITY, initialDocId));
        }
        String nonBlockingPkg = findNonBlockingDocumentsUiPackage(context);
        if (nonBlockingPkg != null && !nonBlockingPkg.isEmpty()) {
            intent.setPackage(nonBlockingPkg);
        }
        return intent;
    }

    public static boolean docExists(Context context, Uri treeUri, String docId) {
        try (Cursor c = context.getContentResolver().query(
                DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID},
                null, null, null)) {
            return c != null && c.moveToFirst();
        } catch (Throwable t) {
            return false;
        }
    }

    public static long docLength(Context context, Uri treeUri, String docId) {
        Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd != null) {
                long statSize = pfd.getStatSize();
                if (statSize >= 0) {
                    return statSize;
                }
            }
        } catch (Throwable ignored) {
        }
        try (Cursor c = context.getContentResolver().query(
                uri, new String[]{DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return c.getLong(0);
            }
        } catch (Throwable ignored) {
        }
        return 0L;
    }

    public static void ensureDirExists(Context context, Uri treeUri, String dirDocId) throws Exception {
        String treeId = DocumentsContract.getTreeDocumentId(treeUri);
        if (dirDocId == null || dirDocId.isEmpty() || dirDocId.equals(treeId) || docExists(context, treeUri, dirDocId)) {
            return;
        }
        int lastSlash = dirDocId.lastIndexOf('/');
        if (lastSlash <= 0) {
            return;
        }
        String parentId = dirDocId.substring(0, lastSlash);
        String name = dirDocId.substring(lastSlash + 1);
        ensureDirExists(context, treeUri, parentId);
        if (!docExists(context, treeUri, dirDocId)) {
            DocumentsContract.createDocument(
                    context.getContentResolver(),
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId),
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    name
            );
        }
    }

    private static boolean isGameInitializedSaf(Context context, Uri treeUri, String filesDocId) {
        if (!docExists(context, treeUri, filesDocId)) {
            return false;
        }
        for (String rel : LauncherConfig.FIRST_LAUNCH_ANY_OF_PATHS) {
            String norm = LauncherConfig.normalizeSubpath(rel);
            if (!norm.isEmpty() && docExists(context, treeUri, filesDocId + "/" + norm)) {
                return true;
            }
        }
        return false;
    }

    private static String resolveTargetRelativePathSaf(String normSubpath, String relInStage) {
        if (!normSubpath.isEmpty()) {
            return normSubpath + "/" + relInStage;
        }
        if (!relInStage.contains("/")) {
            String low = relInStage.toLowerCase(Locale.US);
            if (low.endsWith(".bbytes") || low.endsWith(".lbytes") || low.endsWith(".dbytes") || low.endsWith(".bytes")) {
                return LauncherConfig.DEFAULT_FORCE_SUBDIR + "/" + relInStage;
            }
            if (low.endsWith(".conf") || "important.json".equals(low)) {
                return LauncherConfig.DEFAULT_CONFIG_SUBDIR + "/" + relInStage;
            }
        }
        return relInStage;
    }

    public static String checkDataSafety(
            Context context,
            Uri treeUri,
            long expectedForceConfSize,
            long expectedLangSize,
            String expectedLangMd5
    ) {
        try {
            String filesDocId = resolveFilesDocId(context, treeUri);
            if (!isGameInitializedSaf(context, treeUri, filesDocId)) {
                return "OK|NOT_READY|false|0||0";
            }
            String manifestDocId = filesDocId + "/" + LauncherConfig.BACKUP_DIR_NAME + "/" + LauncherConfig.BACKUP_MANIFEST_FILE;
            boolean hasBackup = docExists(context, treeUri, manifestDocId);

            String langDocId = filesDocId + "/" + LauncherConfig.TARGET_LANG_REL_PATH;
            String confDocId = filesDocId + "/" + LauncherConfig.TARGET_INDEX_REL_PATH;
            boolean langExists = docExists(context, treeUri, langDocId);
            boolean confExists = docExists(context, treeUri, confDocId);
            long curLangSize = langExists ? docLength(context, treeUri, langDocId) : 0L;
            long curConfSize = confExists ? docLength(context, treeUri, confDocId) : 0L;
            String curLangMd5 = langExists ? md5UpperSaf(context, treeUri, langDocId) : "";

            if (hasBackup) {
                ShellBridgeServer.BackupManifest m =
                        ShellBridgeServer.parseManifest(readTextFromSaf(context, treeUri, manifestDocId));
                ShellBridgeServer.BackupEntry langEntry = m.byPath.get(LauncherConfig.TARGET_LANG_REL_PATH);
                if (langEntry != null && langExists && curLangSize != langEntry.patchedSize) {
                    return "MISMATCH|HOTUPDATE_OVERWROTE_LANG|true|تغير حجم t_languageBean.bbytes";
                }
                ShellBridgeServer.BackupEntry confEntry = m.byPath.get(LauncherConfig.TARGET_INDEX_REL_PATH);
                if (confEntry != null && confExists && curConfSize != confEntry.patchedSize) {
                    return "MISMATCH|HOTUPDATE_OVERWROTE_CONF|true|تغير حجم config/force.conf";
                }
                return "OK|SAFE|true|" + curLangSize + "|" + curLangMd5 + "|" + curConfSize;
            } else {
                if (expectedLangSize > 0 && langExists && curLangSize != expectedLangSize) {
                    return "MISMATCH|LANG_SIZE_CHANGED|false|حجم ملف اللغة تغير (" + curLangSize + ")";
                }
                if (expectedLangMd5 != null && !expectedLangMd5.isEmpty() && langExists
                        && !expectedLangMd5.equalsIgnoreCase(curLangMd5)) {
                    return "MISMATCH|LANG_MD5_CHANGED|false|بصمة MD5 لملف اللغة تغيرت";
                }
                if (expectedForceConfSize > 0 && confExists && curConfSize != expectedForceConfSize) {
                    return "MISMATCH|FORCE_CONF_CHANGED|false|حجم config/force.conf تغير (" + curConfSize + ")";
                }
                return "OK|SAFE|false|" + curLangSize + "|" + curLangMd5 + "|" + curConfSize;
            }
        } catch (Throwable t) {
            return "ERR|" + t.getMessage();
        }
    }

    public static String getStatus(Context context, Uri treeUri) {
        try {
            String filesDocId = resolveFilesDocId(context, treeUri);
            if (!isGameInitializedSaf(context, treeUri, filesDocId)) {
                return "OK|false|false||0";
            }
            String manifestDocId = filesDocId + "/" + LauncherConfig.BACKUP_DIR_NAME + "/" + LauncherConfig.BACKUP_MANIFEST_FILE;
            if (!docExists(context, treeUri, manifestDocId)) {
                return "OK|true|false||0";
            }
            ShellBridgeServer.BackupManifest manifest =
                    ShellBridgeServer.parseManifest(readTextFromSaf(context, treeUri, manifestDocId));
            if (manifest.entries.isEmpty()) {
                return "OK|true|false||0";
            }
            boolean allMatch = true;
            for (ShellBridgeServer.BackupEntry entry : manifest.entries) {
                String targetDocId = filesDocId + "/" + entry.relPath;
                if (!docExists(context, treeUri, targetDocId)
                        || docLength(context, treeUri, targetDocId) != entry.patchedSize) {
                    allMatch = false;
                    break;
                }
            }
            return "OK|true|" + allMatch + "|" + manifest.patchVersion + "|" + manifest.entries.size();
        } catch (Throwable t) {
            return "ERR|" + t.getMessage();
        }
    }

    public static String applyInstall(
            Context context,
            Uri treeUri,
            File stagingDir,
            String patchVersion,
            String targetSubpath,
            ShellBridgeServer.ProgressListener listener
    ) {
        try {
            if (listener != null) {
                listener.onProgress(60, "جارٍ فحص مجلد اللعبة عبر SAF...");
            }
            String filesDocId = resolveFilesDocId(context, treeUri);
            if (!isGameInitializedSaf(context, treeUri, filesDocId)) {
                return "ERR|NEEDS_FIRST_LAUNCH";
            }
            if (stagingDir == null || !stagingDir.isDirectory()) {
                return "ERR|MISSING_STAGE_DIR";
            }

            List<File> stagedFiles = new ArrayList<>();
            ShellBridgeServer.collectFilesRecursively(stagingDir, stagedFiles);
            if (stagedFiles.isEmpty()) {
                return "ERR|EMPTY_STAGE";
            }
            Collections.sort(stagedFiles, (a, b) -> a.getAbsolutePath().compareTo(b.getAbsolutePath()));

            String normSubpath = LauncherConfig.normalizeSubpath(targetSubpath);
            String stageBase = stagingDir.getCanonicalPath() + File.separator;

            String backupDirId = filesDocId + "/" + LauncherConfig.BACKUP_DIR_NAME;
            String origDirId = backupDirId + "/orig";
            String manifestDocId = backupDirId + "/" + LauncherConfig.BACKUP_MANIFEST_FILE;
            ensureDirExists(context, treeUri, origDirId);

            ShellBridgeServer.BackupManifest prevManifest = docExists(context, treeUri, manifestDocId)
                    ? ShellBridgeServer.parseManifest(readTextFromSaf(context, treeUri, manifestDocId))
                    : new ShellBridgeServer.BackupManifest();

            Set<String> newRelPaths = new HashSet<>();
            List<String> orderedRelPaths = new ArrayList<>(stagedFiles.size());
            for (File f : stagedFiles) {
                String canonical = f.getCanonicalPath();
                if (!canonical.startsWith(stageBase)) {
                    throw new SecurityException("ملف خارج مجلد التجهيز: " + canonical);
                }
                String rel = canonical.substring(stageBase.length()).replace('\\', '/');
                String fullRel = resolveTargetRelativePathSaf(normSubpath, rel);
                newRelPaths.add(fullRel);
                orderedRelPaths.add(fullRel);
            }

            for (ShellBridgeServer.BackupEntry oldEntry : prevManifest.entries) {
                if (!newRelPaths.contains(oldEntry.relPath)) {
                    String oldTargetId = filesDocId + "/" + oldEntry.relPath;
                    String oldBackupId = origDirId + "/" + oldEntry.relPath;
                    if (oldEntry.hadOriginal && docExists(context, treeUri, oldBackupId)) {
                        copySafToSaf(context, treeUri, oldBackupId, oldTargetId);
                        deleteDocIfExists(context, treeUri, oldBackupId);
                    } else if (!oldEntry.hadOriginal) {
                        deleteDocIfExists(context, treeUri, oldTargetId);
                    }
                }
            }

            ShellBridgeServer.BackupManifest nextManifest = new ShellBridgeServer.BackupManifest();
            nextManifest.patchVersion = (patchVersion != null && !patchVersion.isEmpty()) ? patchVersion : "1.0.0";
            nextManifest.targetSubpath = normSubpath;
            nextManifest.installedAt = System.currentTimeMillis();

            int total = stagedFiles.size();
            for (int i = 0; i < total; i++) {
                File src = stagedFiles.get(i);
                String relFromFiles = orderedRelPaths.get(i);
                String targetDocId = filesDocId + "/" + relFromFiles;
                String backupDocId = origDirId + "/" + relFromFiles;

                ShellBridgeServer.BackupEntry prev = prevManifest.byPath.get(relFromFiles);
                boolean targetExists = docExists(context, treeUri, targetDocId);
                boolean backupExists = docExists(context, treeUri, backupDocId);
                boolean hadOriginal;

                if (prev != null) {
                    if (prev.hadOriginal && backupExists) {
                        if (targetExists) {
                            long tLen = docLength(context, treeUri, targetDocId);
                            long bLen = docLength(context, treeUri, backupDocId);
                            if (tLen != prev.patchedSize && tLen != bLen) {
                                copySafToSaf(context, treeUri, targetDocId, backupDocId);
                            }
                        }
                        hadOriginal = true;
                    } else if (!prev.hadOriginal) {
                        if (targetExists && docLength(context, treeUri, targetDocId) != prev.patchedSize) {
                            copySafToSaf(context, treeUri, targetDocId, backupDocId);
                            hadOriginal = true;
                        } else {
                            hadOriginal = false;
                        }
                    } else {
                        hadOriginal = targetExists;
                        if (hadOriginal) {
                            copySafToSaf(context, treeUri, targetDocId, backupDocId);
                        }
                    }
                } else {
                    if (targetExists) {
                        if (!backupExists) {
                            copySafToSaf(context, treeUri, targetDocId, backupDocId);
                        }
                        hadOriginal = true;
                    } else {
                        hadOriginal = false;
                    }
                }

                copyLocalFileToSaf(context, treeUri, src, targetDocId);

                ShellBridgeServer.BackupEntry newEntry =
                        new ShellBridgeServer.BackupEntry(relFromFiles, src.length(), hadOriginal);
                nextManifest.entries.add(newEntry);
                nextManifest.byPath.put(relFromFiles, newEntry);

                if (listener != null && (i % 2 == 0 || i == total - 1)) {
                    int pct = 64 + ((i + 1) * 32) / total;
                    listener.onProgress(pct, "جارٍ تثبيت الملفات عبر SAF (" + (i + 1) + "/" + total + ")...");
                }
            }

            if (listener != null) {
                listener.onProgress(98, "جارٍ حفظ سجل النسخ الاحتياطي...");
            }
            writeBytesToSaf(context, treeUri, manifestDocId,
                    ShellBridgeServer.serializeManifest(nextManifest).getBytes(StandardCharsets.UTF_8));

            return "OK|INSTALLED|" + nextManifest.patchVersion + "|" + total;
        } catch (Throwable t) {
            return "ERR|" + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    public static String applyRestore(
            Context context,
            Uri treeUri,
            ShellBridgeServer.ProgressListener listener
    ) {
        try {
            String filesDocId = resolveFilesDocId(context, treeUri);
            String backupDirId = filesDocId + "/" + LauncherConfig.BACKUP_DIR_NAME;
            if (!docExists(context, treeUri, backupDirId)) {
                return "OK|RESTORED|0";
            }

            String origDirId = backupDirId + "/orig";
            String manifestDocId = backupDirId + "/" + LauncherConfig.BACKUP_MANIFEST_FILE;
            int restoredCount = 0;

            if (docExists(context, treeUri, manifestDocId)) {
                ShellBridgeServer.BackupManifest manifest =
                        ShellBridgeServer.parseManifest(readTextFromSaf(context, treeUri, manifestDocId));
                int total = Math.max(1, manifest.entries.size());
                for (int i = 0; i < manifest.entries.size(); i++) {
                    ShellBridgeServer.BackupEntry entry = manifest.entries.get(i);
                    String targetDocId = filesDocId + "/" + entry.relPath;
                    String backupDocId = origDirId + "/" + entry.relPath;

                    if (entry.hadOriginal && docExists(context, treeUri, backupDocId)) {
                        copySafToSaf(context, treeUri, backupDocId, targetDocId);
                        restoredCount++;
                    } else if (!entry.hadOriginal) {
                        deleteDocIfExists(context, treeUri, targetDocId);
                        restoredCount++;
                    }

                    if (listener != null && (i % 2 == 0 || i == manifest.entries.size() - 1)) {
                        int pct = 30 + ((i + 1) * 65) / total;
                        listener.onProgress(pct, "جارٍ استعادة الملفات الأصلية عبر SAF (" + (i + 1) + "/" + total + ")...");
                    }
                }
            }

            deleteDocIfExists(context, treeUri, backupDirId);
            return "OK|RESTORED|" + restoredCount;
        } catch (Throwable t) {
            return "ERR|" + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    private static String md5UpperSaf(Context context, Uri treeUri, String docId) {
        Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return "";
            }
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[65536];
            int read;
            while ((read = in.read(buf)) > 0) {
                md.update(buf, 0, read);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(String.format(Locale.US, "%02X", b & 0xFF));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static Uri ensureFileUri(Context context, Uri treeUri, String docId) throws Exception {
        int lastSlash = docId.lastIndexOf('/');
        if (lastSlash <= 0) {
            throw new IllegalArgumentException("معرّف مستند SAF غير صالح: " + docId);
        }
        String parentId = docId.substring(0, lastSlash);
        String name = docId.substring(lastSlash + 1);
        ensureDirExists(context, treeUri, parentId);
        if (docExists(context, treeUri, docId)) {
            return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
        }
        String mimeType = name.contains(".") ? "application/octet-stream" : "application/x-jojo-raw";
        Uri created = DocumentsContract.createDocument(
                context.getContentResolver(),
                DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId),
                mimeType,
                name
        );
        if (created == null) {
            throw new IllegalStateException("تعذر إنشاء ملف عبر SAF: " + name);
        }
        return created;
    }

    private static void copyLocalFileToSaf(Context context, Uri treeUri, File localFile, String dstDocId) throws Exception {
        Uri dstUri = ensureFileUri(context, treeUri, dstDocId);
        try (FileInputStream in = new FileInputStream(localFile);
             OutputStream out = context.getContentResolver().openOutputStream(dstUri, "wt")) {
            if (out == null) {
                throw new IllegalStateException("تعذر فتح مجرى الكتابة عبر SAF: " + dstDocId);
            }
            byte[] buf = new byte[65536];
            int read;
            while ((read = in.read(buf)) > 0) {
                out.write(buf, 0, read);
            }
            out.flush();
        }
    }

    private static void copySafToSaf(Context context, Uri treeUri, String srcDocId, String dstDocId) throws Exception {
        Uri srcUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, srcDocId);
        Uri dstUri = ensureFileUri(context, treeUri, dstDocId);
        ContentResolver cr = context.getContentResolver();
        try (InputStream in = cr.openInputStream(srcUri);
             OutputStream out = cr.openOutputStream(dstUri, "wt")) {
            if (in == null || out == null) {
                throw new IllegalStateException("تعذر نسخ الملف عبر SAF: " + srcDocId);
            }
            byte[] buf = new byte[65536];
            int read;
            while ((read = in.read(buf)) > 0) {
                out.write(buf, 0, read);
            }
            out.flush();
        }
    }

    private static void writeBytesToSaf(Context context, Uri treeUri, String dstDocId, byte[] data) throws Exception {
        Uri dstUri = ensureFileUri(context, treeUri, dstDocId);
        try (OutputStream out = context.getContentResolver().openOutputStream(dstUri, "wt")) {
            if (out == null) {
                throw new IllegalStateException("تعذر الكتابة إلى SAF: " + dstDocId);
            }
            out.write(data);
            out.flush();
        }
    }

    private static String readTextFromSaf(Context context, Uri treeUri, String docId) throws Exception {
        Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) {
                throw new IllegalStateException("تعذر القراءة من SAF: " + docId);
            }
            byte[] buf = new byte[32768];
            int read;
            while ((read = in.read(buf)) > 0) {
                out.write(buf, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void deleteDocIfExists(Context context, Uri treeUri, String docId) {
        try {
            if (docExists(context, treeUri, docId)) {
                DocumentsContract.deleteDocument(
                        context.getContentResolver(),
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                );
            }
        } catch (Throwable ignored) {
        }
    }
}
