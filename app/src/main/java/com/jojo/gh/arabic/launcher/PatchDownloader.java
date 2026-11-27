package com.jojo.gh.arabic.launcher;

import android.content.Context;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * مسؤول عن جلب manifest.json من GitHub Releases، تحميل حزمة التعريب (ZIP)،
 * التحقق من بصمة SHA-256، وفك الضغط بشكل آمن داخل مجلد مؤقت.
 */
public final class PatchDownloader {

    public interface ProgressCallback {
        void onProgress(int percent, String message);
    }

    private PatchDownloader() {
    }

    private static HttpURLConnection openHttpsConnection(String urlStr) throws IOException {
        return openHttpsConnection(urlStr, false);
    }

    private static HttpURLConnection openHttpsConnection(String urlStr, boolean noCache) throws IOException {
        String current = urlStr;
        for (int redirect = 0; redirect <= LauncherConfig.HTTP_MAX_REDIRECTS; redirect++) {
            URL url = new URL(current);
            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                throw new IOException("يُسمح فقط بروابط HTTPS الآمنة: " + current);
            }
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(LauncherConfig.HTTP_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(LauncherConfig.HTTP_READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "RDAV-MultiGame-Hub/" + LauncherConfig.APP_VERSION);
            conn.setRequestProperty("Accept", "*/*");
            if (noCache) {
                conn.setUseCaches(false);
                conn.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate");
                conn.setRequestProperty("Pragma", "no-cache");
                conn.setRequestProperty("Expires", "0");
            }
            conn.connect();

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER
                    || code == 307
                    || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.trim().isEmpty()) {
                    throw new IOException("إعادة توجيه بدون عنوان Location (HTTP " + code + ")");
                }
                current = new URL(url, location).toString();
                continue;
            }
            if (code < 200 || code >= 300) {
                conn.disconnect();
                throw new IOException("فشل الاتصال بالخادم (HTTP " + code + ")");
            }
            return conn;
        }
        throw new IOException("تجاوز الحد الأقصى لإعادة التوجيه للرابط");
    }

    private static String appendCacheBuster(String url) {
        if (url == null || url.trim().isEmpty()) {
            return "";
        }
        String u = url.trim();
        char sep = u.contains("?") ? '&' : '?';
        return u + sep + "t=" + System.currentTimeMillis();
    }

    private static String downloadUtf8Text(String urlStr, int maxBytes) throws IOException {
        return downloadUtf8Text(urlStr, maxBytes, true);
    }

    private static String downloadUtf8Text(String urlStr, int maxBytes, boolean noCache) throws IOException {
        HttpURLConnection conn = null;
        try {
            String targetUrl = noCache ? appendCacheBuster(urlStr) : urlStr;
            conn = openHttpsConnection(targetUrl, noCache);
            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16384];
                int read;
                int total = 0;
                while ((read = in.read(buf)) > 0) {
                    total += read;
                    if (total > maxBytes) {
                        throw new IOException("حجم الاستجابة أكبر من المتوقع");
                    }
                    out.write(buf, 0, read);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public static PatchManifest fetchManifest() throws Exception {
        return fetchManifestForProfile(GameProfileRegistry.getDefaultProfile());
    }

    public static PatchManifest fetchManifestForProfile(GameProfile profile) throws Exception {
        String manifestUrl = (profile != null && !profile.manifestUrl.isEmpty())
                ? profile.manifestUrl
                : (LauncherConfig.MANIFEST_URL != null ? LauncherConfig.MANIFEST_URL.trim() : "");
        String fallbackZip = LauncherConfig.FALLBACK_ZIP_URL != null ? LauncherConfig.FALLBACK_ZIP_URL.trim() : "";

        if (!manifestUrl.isEmpty() && !manifestUrl.contains("YOUR_USERNAME")) {
            try {
                return PatchManifest.fromJson(downloadUtf8Text(manifestUrl, 1024 * 1024));
            } catch (Exception e) {
                if (profile != null && profile.comingSoon) {
                    try {
                        return fetchBetaManifest(profile);
                    } catch (Exception ignored) {
                    }
                }
                if (fallbackZip.isEmpty() || fallbackZip.contains("YOUR_USERNAME")) {
                    throw e;
                }
            }
        }

        if (!fallbackZip.isEmpty() && !fallbackZip.contains("YOUR_USERNAME")) {
            return new PatchManifest(
                    "direct",
                    LauncherConfig.TARGET_GAME_VERSION_NAME,
                    LauncherConfig.TARGET_GAME_VERSION_CODE,
                    LauncherConfig.TARGET_LANG_ORIG_MD5,
                    LauncherConfig.TARGET_LANG_ORIG_SIZE,
                    LauncherConfig.TARGET_INDEX_ORIG_SIZE,
                    "",
                    "تحميل مباشر لحزمة التعريب",
                    fallbackZip,
                    "",
                    0L,
                    LauncherConfig.GAME_TARGET_SUBPATH,
                    "public"
            );
        }

        throw new IllegalStateException("لم يتم ضبط رابط GitHub Releases لهذا البروفايل بعد.");
    }

    /**
     * يجلب أحدث نسخة اختبار سرية (Beta Pre-release + manifest_beta.json) للعبة المحددة.
     * يفحص أولاً GitHub Releases API لاكتشاف أحدث Pre-release منشور ديناميكياً،
     * وفي حال تعذّر الـ API (مثل Rate Limit) يعود فوراً إلى رابط betaManifestUrl المباشر في البروفايل.
     */
    public static PatchManifest fetchBetaManifest(GameProfile profile) throws Exception {
        if (profile == null) {
            throw new IllegalStateException("بروفايل اللعبة غير محدد.");
        }

        Exception apiFailure = null;

        // 1) Live GitHub Releases API discovery FIRST (always picks newest non-draft prerelease / manifest_beta.json)
        if (!profile.githubRepo.isEmpty()) {
            try {
                String apiUrl = "https://api.github.com/repos/" + profile.githubRepo + "/releases?per_page=10";
                String body = downloadUtf8Text(apiUrl, 2 * 1024 * 1024);
                JSONArray releases = new JSONArray(body);

                for (int i = 0; i < releases.length(); i++) {
                    JSONObject rel = releases.optJSONObject(i);
                    if (rel == null || rel.optBoolean("draft", false)) {
                        continue;
                    }
                    boolean isPrerelease = rel.optBoolean("prerelease", false);
                    String tagName = rel.optString("tag_name", "beta");
                    JSONArray assets = rel.optJSONArray("assets");
                    if (assets == null) {
                        continue;
                    }
                    String betaManifestDownloadUrl = "";
                    String fallbackBetaZipUrl = "";
                    long fallbackZipSize = 0L;

                    for (int j = 0; j < assets.length(); j++) {
                        JSONObject asset = assets.optJSONObject(j);
                        if (asset == null) {
                            continue;
                        }
                        String name = asset.optString("name", "").trim().toLowerCase(Locale.US);
                        String dlUrl = asset.optString("browser_download_url", "").trim();
                        if ("manifest_beta.json".equals(name) && !dlUrl.isEmpty()) {
                            betaManifestDownloadUrl = dlUrl;
                        } else if (name.endsWith(".zip") && fallbackBetaZipUrl.isEmpty() && !dlUrl.isEmpty()) {
                            fallbackBetaZipUrl = dlUrl;
                            fallbackZipSize = asset.optLong("size", 0L);
                        }
                    }

                    if (!betaManifestDownloadUrl.isEmpty()) {
                        PatchManifest m = PatchManifest.fromJson(downloadUtf8Text(betaManifestDownloadUrl, 1024 * 1024, true));
                        return m.withChannel("beta");
                    }
                    if (isPrerelease && !fallbackBetaZipUrl.isEmpty()) {
                        return new PatchManifest(
                                tagName,
                                profile.targetGameVersionName,
                                profile.targetGameVersionCode,
                                LauncherConfig.TARGET_LANG_ORIG_MD5,
                                LauncherConfig.TARGET_LANG_ORIG_SIZE,
                                LauncherConfig.TARGET_INDEX_ORIG_SIZE,
                                rel.optString("published_at", ""),
                                "نسخة اختبار (Pre-release): " + tagName,
                                fallbackBetaZipUrl,
                                "",
                                fallbackZipSize,
                                profile.patchStrategy.resolveDefaultTargetSubpath(""),
                                "beta"
                        );
                    }
                }
            } catch (Exception e) {
                apiFailure = e;
            }
        }

        // 2) Direct betaManifestUrl fallback (immune to GitHub API rate limits; synced automatically by RDAV_Updater)
        if (!profile.betaManifestUrl.isEmpty()) {
            try {
                PatchManifest m = PatchManifest.fromJson(downloadUtf8Text(profile.betaManifestUrl, 1024 * 1024, true));
                return m.withChannel("beta");
            } catch (Exception directErr) {
                if (apiFailure != null) {
                    throw directErr;
                }
                throw directErr;
            }
        }

        if (apiFailure != null) {
            throw apiFailure;
        }
        throw new IllegalStateException("لا توجد نسخة اختبار (Beta Pre-release) مرفوعة حالياً على GitHub. ارفع نسخة اختبار من أداة الـ PC أولاً.");
    }

    public static void downloadToFile(
            String urlStr,
            File destFile,
            long expectedSize,
            ProgressCallback callback,
            int startPct,
            int endPct
    ) throws Exception {
        File parent = destFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File tempFile = new File(destFile.getParentFile(), destFile.getName() + ".part");
        if (tempFile.exists()) {
            tempFile.delete();
        }

        HttpURLConnection conn = null;
        try {
            conn = openHttpsConnection(urlStr);
            long contentLength = Build.VERSION.SDK_INT >= 24
                    ? conn.getContentLengthLong()
                    : conn.getContentLength();
            long totalSize = contentLength > 0 ? contentLength : expectedSize;

            try (InputStream in = new BufferedInputStream(conn.getInputStream(), 65536);
                 BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(tempFile), 65536)) {
                byte[] buf = new byte[65536];
                long downloaded = 0L;
                long lastUiUpdate = 0L;
                int read;
                while ((read = in.read(buf)) > 0) {
                    out.write(buf, 0, read);
                    downloaded += read;
                    if (callback != null) {
                        long now = System.currentTimeMillis();
                        if (now - lastUiUpdate >= 100) {
                            lastUiUpdate = now;
                            if (totalSize > 0) {
                                int pct = startPct + (int) Math.min(endPct - startPct,
                                        ((endPct - startPct) * downloaded) / totalSize);
                                String msg = String.format(
                                        Locale.US,
                                        "جارٍ تحميل حزمة التعريب... (%.1f / %.1f م.ب)",
                                        downloaded / 1048576.0,
                                        totalSize / 1048576.0
                                );
                                callback.onProgress(pct, msg);
                            } else {
                                String msg = String.format(
                                        Locale.US,
                                        "جارٍ تحميل حزمة التعريب... (%.1f م.ب)",
                                        downloaded / 1048576.0
                                );
                                callback.onProgress(startPct + (endPct - startPct) / 2, msg);
                            }
                        }
                    }
                }
                out.flush();
            }

            if (destFile.exists()) {
                destFile.delete();
            }
            if (!tempFile.renameTo(destFile)) {
                throw new IOException("تعذر حفظ ملف الحزمة المحمّل مؤقتاً");
            }
        } catch (Exception e) {
            if (tempFile.exists()) {
                tempFile.delete();
            }
            throw e;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public static void verifySha256IfProvided(File file, String expectedSha256) throws Exception {
        if (expectedSha256 == null || expectedSha256.trim().isEmpty()) {
            return;
        }
        String expected = expectedSha256.trim().toLowerCase(Locale.US);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), 65536)) {
            byte[] buf = new byte[65536];
            int read;
            while ((read = in.read(buf)) > 0) {
                md.update(buf, 0, read);
            }
        }
        byte[] digest = md.digest();
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(String.format(Locale.US, "%02x", b & 0xFF));
        }
        if (!expected.equals(hex.toString())) {
            throw new SecurityException("بصمة SHA-256 غير مطابقة! قد يكون الملف تالفاً أو ناقصاً.");
        }
    }

    public static int extractZipFile(
            File zipFile,
            File stagingDir,
            ProgressCallback callback,
            int startPct,
            int endPct
    ) throws Exception {
        try (InputStream in = new BufferedInputStream(new FileInputStream(zipFile), 65536)) {
            return extractZipStream(in, stagingDir, callback, startPct, endPct);
        }
    }

    public static int extractZipUri(
            Context context,
            Uri zipUri,
            File stagingDir,
            ProgressCallback callback,
            int startPct,
            int endPct
    ) throws Exception {
        InputStream raw = context.getContentResolver().openInputStream(zipUri);
        if (raw == null) {
            throw new IOException("تعذر فتح ملف الـ ZIP المختار");
        }
        try (InputStream in = new BufferedInputStream(raw, 65536)) {
            return extractZipStream(in, stagingDir, callback, startPct, endPct);
        }
    }

    private static int extractZipStream(
            InputStream rawInput,
            File stagingDir,
            ProgressCallback callback,
            int startPct,
            int endPct
    ) throws Exception {
        if (!stagingDir.exists() && !stagingDir.mkdirs()) {
            throw new IOException("تعذر إنشاء مجلد التجهيز المؤقت");
        }
        String canonicalBase = stagingDir.getCanonicalPath() + File.separator;
        int extractedCount = 0;

        try (ZipInputStream zis = new ZipInputStream(rawInput)) {
            byte[] buf = new byte[65536];
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                while (name.startsWith("/")) {
                    name = name.substring(1);
                }

                String lowerName = name.toLowerCase(Locale.US);
                if (name.isEmpty()
                        || entry.isDirectory()
                        || name.endsWith("/")
                        || name.startsWith("__MACOSX/")
                        || lowerName.endsWith("/.ds_store")
                        || ".ds_store".equals(lowerName)
                        || lowerName.endsWith("/thumbs.db")
                        || "thumbs.db".equals(lowerName)
                        || lowerName.endsWith("/desktop.ini")
                        || "desktop.ini".equals(lowerName)) {
                    zis.closeEntry();
                    continue;
                }

                File outFile = new File(stagingDir, name);
                String canonicalOut = outFile.getCanonicalPath();
                if (!canonicalOut.startsWith(canonicalBase)) {
                    throw new SecurityException("مسار غير آمن داخل ملف الـ ZIP: " + name);
                }

                File parent = outFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }

                try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(outFile), 65536)) {
                    int read;
                    while ((read = zis.read(buf)) > 0) {
                        bos.write(buf, 0, read);
                    }
                    bos.flush();
                }
                zis.closeEntry();
                extractedCount++;

                if (callback != null && (extractedCount % 5 == 0 || extractedCount == 1)) {
                    int pct = Math.min(endPct, startPct + Math.min(endPct - startPct, extractedCount));
                    callback.onProgress(pct, "جارٍ فك ضغط ملفات التعريب... (" + extractedCount + " ملف)");
                }
            }
        }

        if (extractedCount == 0) {
            throw new IllegalStateException("ملف الـ ZIP فارغ ولا يحتوي على ملفات تعريب صالحة.");
        }
        if (callback != null) {
            callback.onProgress(endPct, "تم تجهيز " + extractedCount + " ملف تعريب بنجاح.");
        }
        return extractedCount;
    }

    public static File resolveEffectiveStageRoot(File stagingDir) {
        File current = stagingDir;
        for (int depth = 0; depth < 3; depth++) {
            File[] children = current.listFiles();
            if (children == null || children.length != 1 || !children[0].isDirectory()) {
                break;
            }
            String dirName = children[0].getName();
            if (isKnownGameSubdir(dirName)) {
                break;
            }
            current = children[0];
        }
        return current;
    }

    private static boolean isKnownGameSubdir(String dirName) {
        if (dirName == null) {
            return false;
        }
        for (String known : LauncherConfig.KNOWN_GAME_SUBDIRS) {
            if (known.equalsIgnoreCase(dirName)) {
                return true;
            }
        }
        return false;
    }
}
