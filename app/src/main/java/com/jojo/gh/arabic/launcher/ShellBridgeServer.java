package com.jojo.gh.arabic.launcher;

import android.os.Build;
import android.os.Process;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * خادم الجسر المحلي لمعرّب JoJo's Bizarre Adventure: Golden Hymn.
 * يعمل بصلاحية Shizuku أو Root عبر app_process، ويُستخدم مباشرة في وضع Direct FS.
 * يدعم:
 * - التثبيت الآمن والنسخ الاحتياطي داخل .jojo_gh_arabic_backup
 * - حارس الأمان (CHECK_SAFETY): فحص حجم وبصمة MD5 لملف force/t_languageBean.bbytes وفهرس config/force.conf
 * - الاستعادة التلقائية الآمنة عند اكتشاف تحديث جديد للعبة
 * - الفحص التشخيصي السريع لمسارات اللعبة وسجلات Unity
 */
public class ShellBridgeServer {

    private static final Object MUTATE_LOCK = new Object();
    private static volatile boolean shellStorageGranted = false;
    private static volatile String expectedAuthToken = "";

    public interface ProgressListener {
        void onProgress(int percent, String message);
    }

    public static final class BackupEntry {
        public final String relPath;
        public final long patchedSize;
        public final boolean hadOriginal;
        public final String patchedSha256;

        public BackupEntry(String relPath, long patchedSize, boolean hadOriginal) {
            this(relPath, patchedSize, hadOriginal, "");
        }

        public BackupEntry(String relPath, long patchedSize, boolean hadOriginal, String patchedSha256) {
            this.relPath = relPath;
            this.patchedSize = patchedSize;
            this.hadOriginal = hadOriginal;
            this.patchedSha256 = patchedSha256 != null ? patchedSha256.trim().toLowerCase(Locale.US) : "";
        }
    }

    public static final class BackupManifest {
        public String patchVersion = "";
        public String targetSubpath = "";
        public long installedAt = 0L;
        public final List<BackupEntry> entries = new ArrayList<>();
        public final Map<String, BackupEntry> byPath = new HashMap<>();
    }

    public static void main(String[] args) {
        if (args != null && args.length > 0 && args[0] != null) {
            expectedAuthToken = args[0].trim();
        }
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), LauncherConfig.BRIDGE_PORT), 12);

            new Thread(ShellBridgeServer::grantShellStorageAccess).start();
            System.out.println("JOJO_GH_BRIDGE_READY:" + LauncherConfig.BRIDGE_PORT);

            while (true) {
                Socket client = server.accept();
                new Thread(() -> handleClient(client)).start();
            }
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }

    public static void grantShellStorageAccess() {
        if (shellStorageGranted) {
            return;
        }
        shellStorageGranted = true;
        try {
            Runtime.getRuntime().exec(new String[]{
                    "sh", "-c",
                    "appops set com.android.shell MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1; " +
                            "appops set --uid 2000 MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1"
            }).waitFor();
        } catch (Throwable ignored) {
        }
    }

    public static void stopGameSafely() {
        stopGameSafely(null);
    }

    public static void stopGameSafely(String targetPackage) {
        if (targetPackage != null && !targetPackage.trim().isEmpty()) {
            try {
                Runtime.getRuntime().exec(new String[]{"am", "force-stop", targetPackage.trim()}).waitFor();
            } catch (Throwable ignored) {
            }
            return;
        }
        for (String pkg : LauncherConfig.GAME_PACKAGES) {
            try {
                Runtime.getRuntime().exec(new String[]{"am", "force-stop", pkg}).waitFor();
            } catch (Throwable ignored) {
            }
        }
    }

    static boolean isJoJoPackage(String pkg) {
        return pkg == null || pkg.trim().isEmpty() || LauncherConfig.GAME_PACKAGE.equals(pkg.trim());
    }

    static String resolveBackupDirName(String targetPackage) {
        if (targetPackage != null && "com.bandainamcoent.dblegends_ww".equals(targetPackage.trim())) {
            return ".dbl_arabic_backup";
        }
        if (targetPackage != null && "com.neowizgames.game.browndust2".equals(targetPackage.trim())) {
            return ".bd2_arabic_backup";
        }
        if (isJoJoPackage(targetPackage)) {
            return LauncherConfig.BACKUP_DIR_NAME;
        }
        String safe = targetPackage.trim().replaceAll("[^a-zA-Z0-9_]", "_");
        return "." + safe + "_arabic_backup";
    }

    private static boolean canReadDir(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        try {
            return dir.list() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public static File resolveGameFilesDir(File hintDir) {
        return resolveGameFilesDir(hintDir, null);
    }

    public static File resolveGameFilesDir(File hintDir, String explicitPackage) {
        String userId = "0";
        String hintBase = null;

        if (hintDir != null) {
            String normalized = hintDir.getAbsolutePath().replace('\\', '/');
            int idx = normalized.indexOf("/Android/data/");
            if (idx > 0) {
                hintBase = normalized.substring(0, idx);
                int lastSlash = hintBase.lastIndexOf('/');
                if (lastSlash >= 0 && lastSlash + 1 < hintBase.length()) {
                    String maybeUid = hintBase.substring(lastSlash + 1);
                    if (maybeUid.matches("\\d+")) {
                        userId = maybeUid;
                    }
                }
            }
        }

        String[] packagesToCheck = (explicitPackage != null && !explicitPackage.trim().isEmpty())
                ? new String[]{explicitPackage.trim()}
                : LauncherConfig.GAME_PACKAGES;

        List<String> storageRoots = new ArrayList<>();
        storageRoots.add("/mnt/pass_through/" + userId + "/emulated/" + userId);
        storageRoots.add("/mnt/user/" + userId + "/emulated/" + userId);
        if (hintBase != null) {
            storageRoots.add(hintBase);
        }
        storageRoots.add("/storage/emulated/" + userId);
        if (!"0".equals(userId)) {
            storageRoots.add("/storage/emulated/0");
        }
        storageRoots.add("/sdcard");
        storageRoots.add("/mnt/sdcard");

        for (String root : storageRoots) {
            for (String pkg : packagesToCheck) {
                File f = new File(root + "/Android/data/" + pkg + "/files");
                boolean hasKnown = isJoJoPackage(pkg)
                        ? (new File(f, LauncherConfig.DEFAULT_FORCE_SUBDIR).isDirectory()
                        || new File(f, LauncherConfig.DEFAULT_CONFIG_SUBDIR).isDirectory()
                        || new File(f, LauncherConfig.DEFAULT_AB_SUBDIR).isDirectory())
                        : (new File(f, "streamingassetbundles").isDirectory()
                        || new File(f, "as").isDirectory()
                        || new File(f, "il2cpp").isDirectory());
                if (hasKnown && canReadDir(f)) {
                    return f;
                }
            }
        }
        for (String root : storageRoots) {
            for (String pkg : packagesToCheck) {
                File f = new File(root + "/Android/data/" + pkg + "/files");
                if (canReadDir(f)) {
                    String[] list = f.list();
                    if (list != null && list.length > 0) {
                        return f;
                    }
                }
            }
        }
        for (String root : storageRoots) {
            for (String pkg : packagesToCheck) {
                File f = new File(root + "/Android/data/" + pkg + "/files");
                if (f.isDirectory()) {
                    return f;
                }
            }
        }

        String fallbackRoot = hintBase != null ? hintBase : "/storage/emulated/0";
        String fallbackPkg = packagesToCheck[0];
        return new File(fallbackRoot + "/Android/data/" + fallbackPkg + "/files");
    }

    public static boolean canDirectReadWriteGameDir(File hintDir) {
        return canDirectReadWriteGameDir(hintDir, null);
    }

    public static boolean canDirectReadWriteGameDir(File hintDir, String targetPackage) {
        try {
            int myUid = Process.myUid();
            if (Build.VERSION.SDK_INT >= 30 && (myUid % 100000) >= 10000) {
                return false;
            }
            File filesDir = resolveGameFilesDir(hintDir, targetPackage);
            if (!filesDir.isDirectory()) {
                return false;
            }
            File probe = new File(filesDir, ".jojo_rw_probe");
            try (FileOutputStream fos = new FileOutputStream(probe)) {
                fos.write(1);
                fos.flush();
            }
            boolean ok = probe.isFile() && probe.length() == 1;
            probe.delete();
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void handleClient(Socket socket) {
        try (Socket s = socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {

            String line = reader.readLine();
            if (line == null) {
                return;
            }
            String[] parts = line.trim().split("\\t", -1);
            if (parts.length < 2) {
                writer.println("ERR|BAD_PROTOCOL");
                return;
            }

            String token = parts[0];
            if (!expectedAuthToken.isEmpty() && !expectedAuthToken.equals(token)) {
                writer.println("ERR|UNAUTHORIZED");
                return;
            }

            String cmd = parts[1];
            if ("PING".equals(cmd)) {
                writer.println("OK|PONG|uid=" + Process.myUid()
                        + "|ver=" + LauncherConfig.APP_VERSION
                        + "|rev=" + LauncherConfig.BRIDGE_REV);
            } else if ("STATUS".equals(cmd)) {
                File hint = (parts.length > 2 && !parts[2].isEmpty()) ? new File(parts[2]) : null;
                String pkg = (parts.length > 3 && !parts[3].isEmpty()) ? parts[3].trim() : null;
                writer.println(getStatus(hint, pkg));
            } else if ("CHECK_SAFETY".equals(cmd)) {
                File hint = (parts.length > 2 && !parts[2].isEmpty()) ? new File(parts[2]) : null;
                long expForceConf = (parts.length > 3 && !parts[3].isEmpty()) ? Long.parseLong(parts[3]) : LauncherConfig.TARGET_INDEX_ORIG_SIZE;
                long expLangSize = (parts.length > 4 && !parts[4].isEmpty()) ? Long.parseLong(parts[4]) : LauncherConfig.TARGET_LANG_ORIG_SIZE;
                String expLangMd5 = (parts.length > 5 && !parts[5].isEmpty()) ? parts[5] : LauncherConfig.TARGET_LANG_ORIG_MD5;
                String pkg = (parts.length > 6 && !parts[6].isEmpty()) ? parts[6].trim() : null;
                writer.println(checkDataSafety(hint, expForceConf, expLangSize, expLangMd5, pkg));
            } else if ("APPLY_INSTALL".equals(cmd)) {
                if (parts.length < 4) {
                    writer.println("ERR|BAD_ARGS");
                    return;
                }
                File stageDir = new File(parts[2]);
                String patchVer = parts[3];
                String subpath = parts.length > 4 ? parts[4] : "";
                String pkg = (parts.length > 5 && !parts[5].isEmpty()) ? parts[5].trim() : null;
                synchronized (MUTATE_LOCK) {
                    stopGameSafely(pkg);
                    String result = applyInstall(stageDir, patchVer, subpath, pkg,
                            (pct, msg) -> writer.println("PROG|" + pct + "|" + msg));
                    writer.println(result);
                }
            } else if ("APPLY_RESTORE".equals(cmd)) {
                File hint = (parts.length > 2 && !parts[2].isEmpty()) ? new File(parts[2]) : null;
                String pkg = (parts.length > 3 && !parts[3].isEmpty()) ? parts[3].trim() : null;
                synchronized (MUTATE_LOCK) {
                    stopGameSafely(pkg);
                    String result = applyRestore(hint, pkg,
                            (pct, msg) -> writer.println("PROG|" + pct + "|" + msg));
                    writer.println(result);
                }
            } else if ("SCAN_DIAG".equals(cmd)) {
                File hint = (parts.length > 2 && !parts[2].isEmpty()) ? new File(parts[2]) : null;
                String pkg = (parts.length > 3 && !parts[3].isEmpty()) ? parts[3].trim() : null;
                writer.println(runDiagnosticScan(hint, pkg));
            } else if ("STOP".equals(cmd)) {
                writer.println("OK|BYE");
                System.exit(0);
            } else {
                writer.println("ERR|UNKNOWN_CMD:" + cmd);
            }
        } catch (Throwable t) {
            t.printStackTrace(System.err);
        }
    }

    private static boolean isGameInitialized(File filesDir) {
        if (filesDir == null || !filesDir.isDirectory()) {
            return false;
        }
        for (String rel : LauncherConfig.FIRST_LAUNCH_ANY_OF_PATHS) {
            String norm = LauncherConfig.normalizeSubpath(rel);
            if (!norm.isEmpty() && new File(filesDir, norm).exists()) {
                return true;
            }
        }
        String[] children = filesDir.list();
        return children != null && children.length > 0;
    }

    /**
     * يوجّه الملفات تلقائياً إذا جاءت مسطّحة في جذر الـ ZIP:
     * - .bbytes / .bytes / .dbytes -> force/
     * - force.conf / local.conf / important.json -> config/
     */
    static String normalizeStageRelativePath(String relInStage) {
        String cleanRel = relInStage != null ? relInStage.replace('\\', '/') : "";
        while (cleanRel.startsWith("/")) {
            cleanRel = cleanRel.substring(1);
        }
        // تطبيع مسارات حزم BrownDust II (مثل android_data/files/Data/t/... أو textures/UnityCache/...)
        String lowRel = cleanRel.toLowerCase(Locale.US);
        if (lowRel.startsWith("android_data/files/")) {
            cleanRel = cleanRel.substring("android_data/files/".length());
        } else if (lowRel.startsWith("files/data/") || lowRel.startsWith("files/unitycache/")) {
            cleanRel = cleanRel.substring("files/".length());
        } else if (lowRel.startsWith("textures/unitycache/")) {
            cleanRel = cleanRel.substring("textures/".length());
        }
        return cleanRel;
    }

    private static String resolveTargetRelativePath(String normSubpath, String relInStage) {
        String cleanRel = normalizeStageRelativePath(relInStage);
        if (!normSubpath.isEmpty()) {
            return normSubpath + "/" + cleanRel;
        }
        if (!cleanRel.contains("/")) {
            String low = cleanRel.toLowerCase(Locale.US);
            if (low.endsWith(".bbytes") || low.endsWith(".lbytes") || low.endsWith(".dbytes") || low.endsWith(".bytes")) {
                return LauncherConfig.DEFAULT_FORCE_SUBDIR + "/" + cleanRel;
            }
            if (low.endsWith(".conf") || "important.json".equals(low)) {
                return LauncherConfig.DEFAULT_CONFIG_SUBDIR + "/" + cleanRel;
            }
        }
        return cleanRel;
    }

    /**
     * حارس الأمان على مستوى ملفات البيانات (Version Safety Guard):
     * يتحقق من تطابق force/t_languageBean.bbytes و config/force.conf مع الإصدار المستهدف.
     * يعيد:
     * - OK|SAFE|<hasBackup>|<langSize>|<langMd5>|<forceConfSize>
     * - MISMATCH|<reason>|<hasBackup>|<detail>
     */
    public static String checkDataSafety(File hintDir, long expectedForceConfSize, long expectedLangSize, String expectedLangMd5) {
        return checkDataSafety(hintDir, expectedForceConfSize, expectedLangSize, expectedLangMd5, null);
    }

    public static String checkDataSafety(File hintDir, long expectedForceConfSize, long expectedLangSize, String expectedLangMd5, String targetPackage) {
        try {
            File filesDir = resolveGameFilesDir(hintDir, targetPackage);
            if (!isGameInitialized(filesDir)) {
                return "OK|NOT_READY|false|0||0";
            }

            String backupName = resolveBackupDirName(targetPackage);
            File backupDir = new File(filesDir, backupName);
            File manifestFile = new File(backupDir, LauncherConfig.BACKUP_MANIFEST_FILE);
            boolean hasBackup = manifestFile.isFile();

            // فحص التحديثات السحابية الداخلية (OTA Hotfix Detection) بشكل عام لجميع الألعاب:
            // لا يُعيد حظراً صارماً (MISMATCH) بل يُعيد OTA_CHANGED ليقوم اللانشر بالمزامنة الذكية والنسخ الاحتياطي المحدّث تلقائياً.
            if (!isJoJoPackage(targetPackage)) {
                if (hasBackup) {
                    BackupManifest m = parseManifest(readText(manifestFile));
                    for (BackupEntry entry : m.entries) {
                        File target = new File(filesDir, entry.relPath);
                        if (!target.isFile() || target.length() != entry.patchedSize) {
                            return "OTA_CHANGED|HOTUPDATE_OVERWROTE_DATA|true|تغيّر ملف " + entry.relPath + " بعد تحديث داخلي (OTA)";
                        }
                        if (!entry.patchedSha256.isEmpty() && !entry.patchedSha256.equalsIgnoreCase(sha256Lower(target))) {
                            return "OTA_CHANGED|HOTUPDATE_HASH_CHANGED|true|تغيّرت بصمة " + entry.relPath + " بعد تحديث داخلي (OTA)";
                        }
                    }
                    return "OK|SAFE|true|0||0";
                }
                return "OK|SAFE|false|0||0";
            }

            File langFile = new File(filesDir, LauncherConfig.TARGET_LANG_REL_PATH);
            File forceConfFile = new File(filesDir, LauncherConfig.TARGET_INDEX_REL_PATH);

            long curLangSize = langFile.isFile() ? langFile.length() : 0L;
            long curForceConfSize = forceConfFile.isFile() ? forceConfFile.length() : 0L;
            String curLangMd5 = langFile.isFile() ? md5Upper(langFile) : "";

            if (hasBackup) {
                BackupManifest m = parseManifest(readText(manifestFile));
                BackupEntry langEntry = m.byPath.get(LauncherConfig.TARGET_LANG_REL_PATH);
                if (langEntry != null && langFile.isFile()) {
                    if (curLangSize != langEntry.patchedSize) {
                        return "OTA_CHANGED|HOTUPDATE_OVERWROTE_LANG|true|تغير حجم t_languageBean.bbytes من "
                                + langEntry.patchedSize + " إلى " + curLangSize;
                    }
                    if (!langEntry.patchedSha256.isEmpty() && !langEntry.patchedSha256.equalsIgnoreCase(sha256Lower(langFile))) {
                        return "OTA_CHANGED|HOTUPDATE_HASH_CHANGED|true|تغيرت بصمة t_languageBean.bbytes بعد تحديث داخلي (OTA)";
                    }
                }
                BackupEntry confEntry = m.byPath.get(LauncherConfig.TARGET_INDEX_REL_PATH);
                if (confEntry != null && forceConfFile.isFile() && curForceConfSize != confEntry.patchedSize) {
                    return "OTA_CHANGED|HOTUPDATE_OVERWROTE_CONF|true|تغير حجم config/force.conf من "
                            + confEntry.patchedSize + " إلى " + curForceConfSize;
                }
                return "OK|SAFE|true|" + curLangSize + "|" + curLangMd5 + "|" + curForceConfSize;
            } else {
                // اللعبة غير معرّبة حالياً — تغيّر ملفات Android/data مع ثبات الـ APK يُصنّف كـ OTA داخلي وليس حظراً صارماً
                return "OK|SAFE|false|" + curLangSize + "|" + curLangMd5 + "|" + curForceConfSize;
            }
        } catch (Throwable t) {
            return "ERR|" + t.getMessage();
        }
    }

    public static String getStatus(File hintDir) {
        return getStatus(hintDir, null);
    }

    public static String getStatus(File hintDir, String targetPackage) {
        try {
            File filesDir = resolveGameFilesDir(hintDir, targetPackage);
            boolean gameReady = isGameInitialized(filesDir);
            if (!gameReady) {
                return "OK|false|false||0";
            }

            File manifestFile = new File(new File(filesDir, resolveBackupDirName(targetPackage)), LauncherConfig.BACKUP_MANIFEST_FILE);
            if (!manifestFile.isFile()) {
                return "OK|true|false||0";
            }

            BackupManifest manifest = parseManifest(readText(manifestFile));
            if (manifest.entries.isEmpty()) {
                return "OK|true|false||0";
            }

            boolean allMatch = true;
            for (BackupEntry entry : manifest.entries) {
                File target = new File(filesDir, entry.relPath);
                if (!target.isFile() || target.length() != entry.patchedSize) {
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
            File stagingDir,
            String patchVersion,
            String targetSubpath,
            ProgressListener listener
    ) {
        return applyInstall(stagingDir, patchVersion, targetSubpath, null, listener);
    }

    public static String applyInstall(
            File stagingDir,
            String patchVersion,
            String targetSubpath,
            String targetPackage,
            ProgressListener listener
    ) {
        try {
            if (listener != null) {
                listener.onProgress(60, "جارٍ فحص مجلد بيانات اللعبة...");
            }
            grantShellStorageAccess();

            File filesDir = resolveGameFilesDir(stagingDir, targetPackage);
            if (!isGameInitialized(filesDir)) {
                return "ERR|NEEDS_FIRST_LAUNCH";
            }
            if (stagingDir == null || !stagingDir.isDirectory()) {
                return "ERR|MISSING_STAGE_DIR";
            }

            List<File> rawStagedFiles = new ArrayList<>();
            collectFilesRecursively(stagingDir, rawStagedFiles);
            if (rawStagedFiles.isEmpty()) {
                return "ERR|EMPTY_STAGE";
            }
            Collections.sort(rawStagedFiles, (a, b) -> a.getAbsolutePath().compareTo(b.getAbsolutePath()));

            // فصل ملفات الفهرسة الوصفية (catalog_patch.json / addressables_patch_manifest.json) عن ملفات اللعبة الفعلية
            File catalogPatchJsonFile = null;
            List<File> stagedFiles = new ArrayList<>(rawStagedFiles.size());
            for (File f : rawStagedFiles) {
                if ("catalog_patch.json".equalsIgnoreCase(f.getName())) {
                    catalogPatchJsonFile = f;
                } else if ("addressables_patch_manifest.json".equalsIgnoreCase(f.getName())
                        || "extra_textures_manifest.json".equalsIgnoreCase(f.getName())) {
                    continue;
                } else {
                    stagedFiles.add(f);
                }
            }
            if (stagedFiles.isEmpty()) {
                return "ERR|EMPTY_STAGE";
            }

            // تحديد ملفات حزم UnityFS غير الكتالوج لاستخدامها في التعديل الموضعي (In-Place Catalog Patching)
            List<File> stagedNonCatalogUnityBundles = new ArrayList<>();
            Set<File> stagedCatalogFiles = new HashSet<>();
            for (File f : stagedFiles) {
                if (UnityCatalogPatcher.isUnityFsFile(f)) {
                    if (UnityCatalogPatcher.isAssetBundleCatalog(f)) {
                        stagedCatalogFiles.add(f);
                    } else {
                        stagedNonCatalogUnityBundles.add(f);
                    }
                }
            }

            stopGameSafely(targetPackage);

            String normSubpath = LauncherConfig.normalizeSubpath(targetSubpath);
            String stageBase = stagingDir.getCanonicalPath() + File.separator;

            File backupDir = new File(filesDir, resolveBackupDirName(targetPackage));
            File origDir = new File(backupDir, "orig");
            File manifestFile = new File(backupDir, LauncherConfig.BACKUP_MANIFEST_FILE);
            if (!origDir.exists()) {
                origDir.mkdirs();
            }

            BackupManifest prevManifest = manifestFile.isFile()
                    ? parseManifest(readText(manifestFile))
                    : new BackupManifest();

            Set<String> newRelPaths = new HashSet<>();
            List<String> orderedRelPaths = new ArrayList<>(stagedFiles.size());
            for (File f : stagedFiles) {
                String canonical = f.getCanonicalPath();
                if (!canonical.startsWith(stageBase)) {
                    throw new SecurityException("ملف خارج مجلد التجهيز: " + canonical);
                }
                String rel = canonical.substring(stageBase.length()).replace('\\', '/');
                String fullRel = resolveTargetRelativePath(normSubpath, rel);
                newRelPaths.add(fullRel);
                orderedRelPaths.add(fullRel);
            }

            for (BackupEntry oldEntry : prevManifest.entries) {
                if (!newRelPaths.contains(oldEntry.relPath)) {
                    File oldTarget = new File(filesDir, oldEntry.relPath);
                    File oldBackup = new File(origDir, oldEntry.relPath);
                    if (oldEntry.hadOriginal && oldBackup.isFile()) {
                        copyFileAtomically(oldBackup, oldTarget);
                        oldBackup.delete();
                    } else if (!oldEntry.hadOriginal && oldTarget.isFile()) {
                        oldTarget.delete();
                    }
                }
            }

            BackupManifest nextManifest = new BackupManifest();
            nextManifest.patchVersion = (patchVersion != null && !patchVersion.isEmpty()) ? patchVersion : "1.0.0";
            nextManifest.targetSubpath = normSubpath;
            nextManifest.installedAt = System.currentTimeMillis();

            int total = stagedFiles.size();
            int inPlaceCatalogCount = 0;
            for (int i = 0; i < total; i++) {
                File src = stagedFiles.get(i);
                String relFromFiles = orderedRelPaths.get(i);
                File targetFile = new File(filesDir, relFromFiles);
                File backupFile = new File(origDir, relFromFiles);

                BackupEntry prev = prevManifest.byPath.get(relFromFiles);
                boolean hadOriginal;

                // النسخ الاحتياطي المحدّث تلقائياً (Fresh Backup on OTA):
                // إذا تغيّر الملف الحي على الجهاز عن آخر نسخة معرّبة (بالحجم أو SHA-256)، نحدّث النسخة الأصلية في orig/ فوراً.
                if (prev != null) {
                    if (prev.hadOriginal && backupFile.isFile()) {
                        if (targetFile.isFile()) {
                            boolean changedFromPatched = targetFile.length() != prev.patchedSize
                                    || (!prev.patchedSha256.isEmpty() && !prev.patchedSha256.equalsIgnoreCase(sha256Lower(targetFile)));
                            if (changedFromPatched) {
                                boolean differsFromBackup = targetFile.length() != backupFile.length()
                                        || !sha256Lower(targetFile).equalsIgnoreCase(sha256Lower(backupFile));
                                if (differsFromBackup) {
                                    copyFileAtomically(targetFile, backupFile);
                                }
                            }
                        }
                        hadOriginal = true;
                    } else if (!prev.hadOriginal) {
                        if (targetFile.isFile() && (targetFile.length() != prev.patchedSize
                                || (!prev.patchedSha256.isEmpty() && !prev.patchedSha256.equalsIgnoreCase(sha256Lower(targetFile))))) {
                            copyFileAtomically(targetFile, backupFile);
                            hadOriginal = true;
                        } else {
                            hadOriginal = false;
                        }
                    } else {
                        hadOriginal = targetFile.isFile();
                        if (hadOriginal) {
                            copyFileAtomically(targetFile, backupFile);
                        }
                    }
                } else {
                    if (targetFile.isFile()) {
                        if (!backupFile.isFile()) {
                            copyFileAtomically(targetFile, backupFile);
                        }
                        hadOriginal = true;
                    } else {
                        hadOriginal = false;
                    }
                }

                // التعديل الموضعي لكتالوج الأصول الحي (In-Place Catalog Patching):
                // بدلاً من استبدال assetbundlecatalog.asset كاملاً، نحدّث فقط size و CRC32 للحزم المعرّبة داخل الكتالوج الحي على الجهاز!
                boolean appliedInPlace = false;
                if (stagedCatalogFiles.contains(src) && targetFile.isFile()) {
                    try {
                        if (listener != null) {
                            listener.onProgress(86, "جارٍ التعديل الموضعي لكتالوج الأصول الحي (In-Place Catalog Patch)...");
                        }
                        // اختيار أحدث إصدار كتالوج متاح على الجهاز (بين targetFile و backupFile) لضمان عدم الرجوع لإصدار OTA أقدم أبداً
                        File liveBase = targetFile;
                        if (hadOriginal && backupFile.isFile()) {
                            int targetCatVer = UnityCatalogPatcher.readCatalogVersion(targetFile);
                            int backupCatVer = UnityCatalogPatcher.readCatalogVersion(backupFile);
                            if (targetCatVer > backupCatVer) {
                                // الكتالوج الحي أحدث من النسخة الاحتياطية القديمة -> حدّث النسخة الاحتياطية أولاً!
                                copyFileAtomically(targetFile, backupFile);
                                liveBase = backupFile;
                            } else if (backupCatVer > 0) {
                                liveBase = backupFile;
                            }
                        }
                        Map<String, long[]> overrides = UnityCatalogPatcher.extractOverrides(
                                src, stagedNonCatalogUnityBundles, catalogPatchJsonFile
                        );
                        if (!overrides.isEmpty()) {
                            UnityCatalogPatcher.CatalogPatchResult res = UnityCatalogPatcher.patchLiveCatalogInPlace(
                                    liveBase, targetFile, overrides
                            );
                            if (res.patchedInPlace) {
                                appliedInPlace = true;
                                inPlaceCatalogCount = res.updatedEntries;
                            }
                        }
                    } catch (Throwable ignored) {
                        appliedInPlace = false;
                    }
                }

                if (!appliedInPlace) {
                    long origMtime = targetFile.isFile() ? targetFile.lastModified() : 0L;
                    copyFileAtomically(src, targetFile);
                    if (origMtime > 0L) {
                        try {
                            targetFile.setLastModified(origMtime);
                        } catch (Throwable ignored) {
                        }
                    }
                }

                String finalSha256 = sha256Lower(targetFile);
                BackupEntry newEntry = new BackupEntry(relFromFiles, targetFile.length(), hadOriginal, finalSha256);
                nextManifest.entries.add(newEntry);
                nextManifest.byPath.put(relFromFiles, newEntry);

                if (listener != null && (i % 2 == 0 || i == total - 1)) {
                    int pct = 64 + ((i + 1) * 32) / total;
                    listener.onProgress(pct, "جارٍ تثبيت ملفات التعريب والنسخ الاحتياطي (" + (i + 1) + "/" + total + ")...");
                }
            }

            if (listener != null) {
                listener.onProgress(98, "جارٍ حفظ سجل النسخ الاحتياطي والتثبيت...");
            }
            writeTextAtomically(manifestFile, serializeManifest(nextManifest));

            return "OK|INSTALLED|" + nextManifest.patchVersion + "|" + total + "|in_place=" + inPlaceCatalogCount;
        } catch (Throwable t) {
            return "ERR|" + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    public static String applyRestore(File hintDir, ProgressListener listener) {
        return applyRestore(hintDir, null, listener);
    }

    public static String applyRestore(File hintDir, String targetPackage, ProgressListener listener) {
        try {
            stopGameSafely(targetPackage);
            File filesDir = resolveGameFilesDir(hintDir, targetPackage);
            File backupDir = new File(filesDir, resolveBackupDirName(targetPackage));
            if (!backupDir.isDirectory()) {
                return "OK|RESTORED|0";
            }

            File origDir = new File(backupDir, "orig");
            File manifestFile = new File(backupDir, LauncherConfig.BACKUP_MANIFEST_FILE);
            int restoredCount = 0;

            if (manifestFile.isFile()) {
                BackupManifest manifest = parseManifest(readText(manifestFile));
                int total = Math.max(1, manifest.entries.size());
                for (int i = 0; i < manifest.entries.size(); i++) {
                    BackupEntry entry = manifest.entries.get(i);
                    File target = new File(filesDir, entry.relPath);
                    File backup = new File(origDir, entry.relPath);

                    if (entry.hadOriginal && backup.isFile()) {
                        copyFileAtomically(backup, target);
                        restoredCount++;
                    } else if (!entry.hadOriginal && target.isFile()) {
                        target.delete();
                        restoredCount++;
                    }

                    if (listener != null && (i % 2 == 0 || i == manifest.entries.size() - 1)) {
                        int pct = 30 + ((i + 1) * 65) / total;
                        listener.onProgress(pct, "جارٍ استعادة الملفات الأصلية (" + (i + 1) + "/" + total + ")...");
                    }
                }
            }

            deleteRecursive(backupDir);
            return "OK|RESTORED|" + restoredCount;
        } catch (Throwable t) {
            return "ERR|" + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    public static String runDiagnosticScan(File hintDir) {
        return runDiagnosticScan(hintDir, null);
    }

    public static String runDiagnosticScan(File hintDir, String targetPackage) {
        try {
            grantShellStorageAccess();
            File filesDir = resolveGameFilesDir(hintDir, targetPackage);
            StringBuilder sb = new StringBuilder();
            sb.append("UID=").append(Process.myUid())
                    .append(Process.myUid() == 0 ? " (Root)" : (Process.myUid() == 2000 ? " (Shizuku Shell)" : " (App)"))
                    .append('\n');
            sb.append("filesDir=").append(filesDir.getAbsolutePath()).append('\n');

            sb.append("\n[المجلدات داخل files/]\n");
            File[] children = filesDir.listFiles();
            if (children != null) {
                for (File f : children) {
                    if (f.isDirectory()) {
                        String[] sub = f.list();
                        sb.append(" • files/").append(f.getName()).append("/ (").append(sub != null ? sub.length : -1).append(" عنصر)\n");
                    }
                }
            }

            File langFile = new File(filesDir, LauncherConfig.TARGET_LANG_REL_PATH);
            File forceConf = new File(filesDir, LauncherConfig.TARGET_INDEX_REL_PATH);
            sb.append("\n[فحص ملفات التعريب والفهرس]\n");
            if (langFile.isFile()) {
                sb.append(" • ").append(LauncherConfig.TARGET_LANG_REL_PATH)
                        .append(" (").append(langFile.length()).append(" B) MD5=").append(md5Upper(langFile)).append('\n');
            } else {
                sb.append(" • ").append(LauncherConfig.TARGET_LANG_REL_PATH).append(": غير موجود\n");
            }
            if (forceConf.isFile()) {
                sb.append(" • ").append(LauncherConfig.TARGET_INDEX_REL_PATH)
                        .append(" (").append(forceConf.length()).append(" B)\n");
            } else {
                sb.append(" • ").append(LauncherConfig.TARGET_INDEX_REL_PATH).append(": غير موجود\n");
            }

            File manifestFile = new File(new File(filesDir, LauncherConfig.BACKUP_DIR_NAME), LauncherConfig.BACKUP_MANIFEST_FILE);
            if (manifestFile.isFile()) {
                sb.append("\n[سجل النسخ الاحتياطي .jojo_gh_arabic_backup/manifest.txt]\n");
                sb.append(readText(manifestFile).trim()).append('\n');
            }

            sb.append("\n[مسارات APK اللعبة]\n");
            String pmOut = runShellCommand("pm path " + LauncherConfig.GAME_PACKAGE, 10);
            sb.append(pmOut.isEmpty() ? " • غير متاح\n" : pmOut);

            sb.append("\n[آخر سجلات Unity في logcat]\n");
            String logOut = runShellCommand("logcat -d -s Unity:V | tail -n 25", 25);
            sb.append(logOut.isEmpty() ? " • لا توجد سجلات Unity حالياً\n" : logOut);

            return "OK|" + sb.toString().replace("\r", "").replace("\n", "\\n");
        } catch (Throwable t) {
            return "ERR|" + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    private static String runShellCommand(String cmd, int maxLines) {
        StringBuilder out = new StringBuilder();
        try {
            java.lang.Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int count = 0;
                while ((line = br.readLine()) != null && count < maxLines) {
                    out.append(" • ").append(line).append('\n');
                    count++;
                }
            }
            p.waitFor();
        } catch (Throwable ignored) {
        }
        return out.toString();
    }

    public static String md5Upper(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
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

    public static String sha256Lower(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int read;
            while ((read = in.read(buf)) > 0) {
                md.update(buf, 0, read);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format(Locale.US, "%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    public static BackupManifest parseManifest(String text) {
        BackupManifest m = new BackupManifest();
        if (text == null || text.isEmpty()) {
            return m;
        }
        for (String rawLine : text.split("\r?\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("patch_version=")) {
                m.patchVersion = line.substring("patch_version=".length()).trim();
            } else if (line.startsWith("target_subpath=")) {
                m.targetSubpath = line.substring("target_subpath=".length()).trim();
            } else if (line.startsWith("installed_at=")) {
                try {
                    m.installedAt = Long.parseLong(line.substring("installed_at=".length()).trim());
                } catch (Throwable ignored) {
                }
            } else if (line.startsWith("entry=")) {
                String[] parts = line.substring("entry=".length()).split("\\|", -1);
                if (parts.length >= 3) {
                    try {
                        String sha = parts.length >= 4 ? parts[3] : "";
                        BackupEntry entry = new BackupEntry(parts[0], Long.parseLong(parts[1]), Boolean.parseBoolean(parts[2]), sha);
                        m.entries.add(entry);
                        m.byPath.put(entry.relPath, entry);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return m;
    }

    public static String serializeManifest(BackupManifest m) {
        StringBuilder sb = new StringBuilder();
        sb.append("patch_version=").append(m.patchVersion).append('\n');
        sb.append("target_subpath=").append(m.targetSubpath).append('\n');
        sb.append("installed_at=").append(m.installedAt).append('\n');
        for (BackupEntry e : m.entries) {
            sb.append("entry=").append(e.relPath)
                    .append('|').append(e.patchedSize)
                    .append('|').append(e.hadOriginal)
                    .append('|').append(e.patchedSha256)
                    .append('\n');
        }
        return sb.toString();
    }

    public static void collectFilesRecursively(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectFilesRecursively(child, out);
            } else if (child.isFile()) {
                out.add(child);
            }
        }
    }

    public static void copyFileAtomically(File src, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File tmp = new File(parent, dst.getName() + ".jojo_tmp");
        if (tmp.exists()) {
            tmp.delete();
        }
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int read;
            while ((read = in.read(buf)) > 0) {
                out.write(buf, 0, read);
            }
            out.flush();
            out.getFD().sync();
        }
        try {
            tmp.setReadable(true, false);
            tmp.setWritable(true, false);
        } catch (Throwable ignored) {
        }
        if (dst.exists()) {
            try {
                dst.setWritable(true, false);
            } catch (Throwable ignored) {
            }
            dst.delete();
        }
        if (!tmp.renameTo(dst)) {
            try (FileInputStream in = new FileInputStream(tmp);
                 FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[65536];
                int read;
                while ((read = in.read(buf)) > 0) {
                    out.write(buf, 0, read);
                }
                out.flush();
                out.getFD().sync();
            } finally {
                tmp.delete();
            }
        }
        try {
            dst.setReadable(true, false);
            dst.setWritable(true, false);
        } catch (Throwable ignored) {
        }
    }

    private static String readText(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[32768];
            int read;
            while ((read = in.read(buf)) > 0) {
                out.write(buf, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void writeTextAtomically(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File tmp = new File(parent, file.getName() + ".jojo_tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        }
        if (file.exists()) {
            file.delete();
        }
        if (!tmp.renameTo(file)) {
            copyFileAtomically(tmp, file);
            tmp.delete();
        }
    }

    public static void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }
}
