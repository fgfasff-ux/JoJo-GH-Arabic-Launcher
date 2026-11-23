package com.jojo.gh.arabic.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * مدير التخزين الذكي بالبصمة (Smart Hash-Based Caching — Disk + Memory Cache):
 * يقارن البصمات الرقمية (icon_hash, banner_hash, hero_media_hash) القادمة من manifest.json
 * بالبصمات المحفوظة محلياً في SharedPreferences:
 * - إذا لم تتغير البصمة وكان الملف مخزناً محلياً: يُعاد فوراً دون أي اتصال بالإنترنت.
 * - إذا تغيّرت البصمة (أو لم يكن مخزناً): يُحمّل الملف المحدّث فقط وتُحدَّث البصمة والكاش المحلي.
 */
public final class GameIconCache {

    private static final String PREFS_NAME = "rdav_icon_cache_meta";
    private static final String DIR_NAME = "icon_cache";
    private static final int MAX_ICON_BYTES = 4 * 1024 * 1024;    // 4 MB
    private static final int MAX_BANNER_BYTES = 8 * 1024 * 1024;  // 8 MB
    private static final int MAX_VIDEO_BYTES = 24 * 1024 * 1024;  // 24 MB
    private static final long MAX_DISK_CACHE_BYTES = 100L * 1024 * 1024; // 100 MB سعة الكاش الدائم على القرص
    private static final String STANDARD_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";
    private static final Map<String, Bitmap> MEMORY_CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService SHOT_DOWNLOAD_POOL = Executors.newFixedThreadPool(4);
    private static final LruCache<String, Bitmap> THUMB_MEMORY_CACHE;

    static {
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024L);
        int cacheKb = Math.max(12 * 1024, Math.min(48 * 1024, maxKb / 8));
        THUMB_MEMORY_CACHE = new LruCache<String, Bitmap>(cacheKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value == null ? 0 : Math.max(1, value.getByteCount() / 1024);
            }
        };
    }

    public interface Callback {
        void onIconReady(Bitmap bitmap);
    }

    public interface FileCallback {
        void onFileReady(File file);
    }

    public interface ScreenshotsCallback {
        void onScreenshotsReady(List<File> files);
    }

    private GameIconCache() {
    }

    public static boolean isVideoMediaUrl(String url) {
        if (url == null) {
            return false;
        }
        String clean = url.trim().toLowerCase(Locale.US);
        int q = clean.indexOf('?');
        if (q >= 0) {
            clean = clean.substring(0, q);
        }
        return clean.endsWith(".mp4") || clean.endsWith(".webm") || clean.endsWith(".m4v");
    }

    private static File getCacheFile(Context context, String cacheKey, String ext) {
        File dir = new File(context.getFilesDir(), DIR_NAME);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String safeKey = (cacheKey == null ? "default" : cacheKey).replaceAll("[^a-zA-Z0-9._-]", "_");
        return new File(dir, safeKey + ext);
    }

    private static String buildCacheToken(String expectedHash, String cleanUrl) {
        if (expectedHash != null && !expectedHash.trim().isEmpty()) {
            return "hash:" + expectedHash.trim().toLowerCase(Locale.US);
        }
        return "url:" + cleanUrl;
    }

    private static boolean isCacheFresh(SharedPreferences prefs, String cacheKey, String expectedToken, String cleanUrl, File outFile, long minBytes) {
        if (!outFile.isFile() || outFile.length() < minBytes) {
            return false;
        }
        String savedToken = prefs.getString("token_" + cacheKey, "");
        if (!savedToken.isEmpty()) {
            return expectedToken.equals(savedToken);
        }
        // توافق تلقائي مع النسخ الأقدم التي كانت تحفظ url_ فقط
        String legacyUrl = prefs.getString("url_" + cacheKey, "");
        return !legacyUrl.isEmpty() && legacyUrl.equals(cleanUrl) && expectedToken.startsWith("url:");
    }

    private static void saveCacheToken(SharedPreferences prefs, String cacheKey, String token, String cleanUrl) {
        prefs.edit()
                .putString("token_" + cacheKey, token)
                .putString("url_" + cacheKey, cleanUrl)
                .apply();
    }

    // -------------------------------------------------------------------------
    // 1) أيقونة اللعبة (icon_url + icon_hash)
    // -------------------------------------------------------------------------

    public static Bitmap loadCachedBitmap(Context context, String gameId) {
        return loadCachedImageByKey(context, "icon_" + gameId, ".png");
    }

    public static Bitmap syncIconSync(Context context, String gameId, String iconUrl, String iconHash) {
        return syncImageSync(context, "icon_" + gameId, ".png", iconUrl, iconHash, MAX_ICON_BYTES);
    }

    public static void syncIconAsync(
            Context context,
            Executor executor,
            Handler mainHandler,
            String gameId,
            String iconUrl,
            String iconHash,
            Callback callback
    ) {
        syncImageAsync(context, executor, mainHandler, "icon_" + gameId, ".png", iconUrl, iconHash, MAX_ICON_BYTES, callback);
    }

    // -------------------------------------------------------------------------
    // 2) صورة البانر الخلفية العلوية (banner_url + banner_hash)
    // -------------------------------------------------------------------------

    public static Bitmap loadCachedBannerBitmap(Context context, String gameId) {
        return loadCachedImageByKey(context, "banner_" + gameId, ".jpg");
    }

    public static Bitmap syncBannerSync(Context context, String gameId, String bannerUrl, String bannerHash) {
        return syncImageSync(context, "banner_" + gameId, ".jpg", bannerUrl, bannerHash, MAX_BANNER_BYTES);
    }

    public static void syncBannerAsync(
            Context context,
            Executor executor,
            Handler mainHandler,
            String gameId,
            String bannerUrl,
            String bannerHash,
            Callback callback
    ) {
        syncImageAsync(context, executor, mainHandler, "banner_" + gameId, ".jpg", bannerUrl, bannerHash, MAX_BANNER_BYTES, callback);
    }

    // -------------------------------------------------------------------------
    // 3) مقطع الفيديو الترويجي المحدّث عن بُعد (hero_media_url + hero_media_hash)
    // -------------------------------------------------------------------------

    public static File loadCachedHeroVideoFile(Context context, String gameId) {
        if (context == null || gameId == null || gameId.trim().isEmpty()) {
            return null;
        }
        File f = getCacheFile(context, "hero_video_" + gameId, ".mp4");
        return (f.isFile() && f.length() > 1024) ? f : null;
    }

    public static void syncHeroVideoAsync(
            Context context,
            Executor executor,
            Handler mainHandler,
            String gameId,
            String videoUrl,
            String videoHash,
            FileCallback callback
    ) {
        if (context == null || gameId == null || !isVideoMediaUrl(videoUrl)) {
            return;
        }
        final String cleanUrl = videoUrl.trim();
        if (!cleanUrl.startsWith("https://")) {
            return;
        }
        final Context appCtx = context.getApplicationContext();
        final String expectedToken = buildCacheToken(videoHash, cleanUrl);
        executor.execute(() -> {
            try {
                String key = "hero_video_" + gameId;
                SharedPreferences prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                File outFile = getCacheFile(appCtx, key, ".mp4");

                // إذا لم تتغير البصمة، استخدم الفيديو المحلي المخزّن فوراً بدون أي تحميل!
                if (isCacheFresh(prefs, key, expectedToken, cleanUrl, outFile, 1024)) {
                    if (callback != null && mainHandler != null) {
                        mainHandler.post(() -> callback.onFileReady(outFile));
                    }
                    return;
                }

                byte[] bytes = downloadBytesFollowingRedirects(cleanUrl, MAX_VIDEO_BYTES);
                if (bytes == null || bytes.length < 1024) {
                    return;
                }
                File tmp = new File(outFile.getParentFile(), outFile.getName() + ".tmp");
                try (FileOutputStream fos = new FileOutputStream(tmp)) {
                    fos.write(bytes);
                    fos.flush();
                }
                if (outFile.exists()) {
                    outFile.delete();
                }
                if (!tmp.renameTo(outFile)) {
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        fos.write(bytes);
                        fos.flush();
                    }
                    tmp.delete();
                }
                saveCacheToken(prefs, key, expectedToken, cleanUrl);
                if (callback != null && mainHandler != null) {
                    mainHandler.post(() -> callback.onFileReady(outFile));
                }
            } catch (Throwable ignored) {
            }
        });
    }

    // -------------------------------------------------------------------------
    // 4) معرض لقطات الشاشة من متجر Google Play (screenshots_urls + screenshots_hash)
    // -------------------------------------------------------------------------

    public static Bitmap getMemoryCachedThumbnail(File file, int maxDimPx) {
        if (file == null) {
            return null;
        }
        String key = file.getAbsolutePath() + "@" + maxDimPx + ":" + file.lastModified();
        Bitmap mem = THUMB_MEMORY_CACHE.get(key);
        return (mem != null && !mem.isRecycled()) ? mem : null;
    }

    public static Bitmap decodeScaledBitmap(File file, int maxDimPx) {
        if (file == null || !file.isFile() || file.length() < 16) {
            return null;
        }
        String cacheKey = file.getAbsolutePath() + "@" + maxDimPx + ":" + file.lastModified();
        if (maxDimPx <= 600) {
            Bitmap mem = THUMB_MEMORY_CACHE.get(cacheKey);
            if (mem != null && !mem.isRecycled()) {
                return mem;
            }
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            int w = bounds.outWidth;
            int h = bounds.outHeight;
            if (w <= 0 || h <= 0) {
                return null;
            }
            int sample = 1;
            int maxSide = Math.max(w, h);
            while (maxSide / (sample * 2) >= maxDimPx) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (decoded != null && maxDimPx <= 600) {
                THUMB_MEMORY_CACHE.put(cacheKey, decoded);
            }
            return decoded;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void enforceDiskCacheBudget(Context context) {
        try {
            File dir = new File(context.getFilesDir(), DIR_NAME);
            File[] files = dir.listFiles();
            if (files == null || files.length == 0) {
                return;
            }
            long total = 0L;
            for (File f : files) {
                if (f.isFile()) {
                    total += f.length();
                }
            }
            if (total <= MAX_DISK_CACHE_BYTES) {
                return;
            }
            Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (File f : files) {
                if (total <= MAX_DISK_CACHE_BYTES * 85 / 100) {
                    break;
                }
                if (f.isFile() && f.getName().endsWith(".tmp")) {
                    total -= f.length();
                    f.delete();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static List<File> loadCachedScreenshotFiles(Context context, String gameId) {
        List<File> out = new ArrayList<>();
        if (context == null || gameId == null || gameId.trim().isEmpty()) {
            return out;
        }
        try {
            Context appCtx = context.getApplicationContext();
            SharedPreferences prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            int count = prefs.getInt("shots_count_" + gameId, 0);
            for (int i = 0; i < count && i < 12; i++) {
                File f = getCacheFile(appCtx, "shot_" + gameId + "_" + i, ".jpg");
                if (f.isFile() && f.length() > 64) {
                    out.add(f);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static void syncScreenshotsAsync(
            Context context,
            Executor executor,
            Handler mainHandler,
            String gameId,
            List<String> urls,
            String expectedHash,
            ScreenshotsCallback callback
    ) {
        if (context == null || gameId == null || gameId.trim().isEmpty() || urls == null || urls.isEmpty()) {
            return;
        }
        final List<String> cleanUrls = new ArrayList<>();
        StringBuilder joined = new StringBuilder();
        for (String u : urls) {
            if (u != null && u.trim().startsWith("https://")) {
                String c = u.trim();
                cleanUrls.add(c);
                if (joined.length() > 0) joined.append('\n');
                joined.append(c);
                if (cleanUrls.size() >= 12) break;
            }
        }
        if (cleanUrls.isEmpty()) {
            return;
        }
        final Context appCtx = context.getApplicationContext();
        final String expectedToken = buildCacheToken(expectedHash, joined.toString());
        final String metaKey = "shots_" + gameId;

        executor.execute(() -> {
            try {
                SharedPreferences prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                String savedToken = prefs.getString("token_" + metaKey, "");
                int savedCount = prefs.getInt("shots_count_" + gameId, 0);

                // إذا لم تتغير بصمة screenshots_hash وكانت جميع الصور محفوظة محلياً، أعدها فوراً من الكاش دون أي اتصال!
                if (expectedToken.equals(savedToken) && savedCount == cleanUrls.size()) {
                    List<File> cached = loadCachedScreenshotFiles(appCtx, gameId);
                    if (cached.size() == cleanUrls.size()) {
                        for (File cf : cached) {
                            decodeScaledBitmap(cf, 520); // تسخين كاش الذاكرة فوراً
                        }
                        if (callback != null && mainHandler != null) {
                            mainHandler.post(() -> callback.onScreenshotsReady(cached));
                        }
                        return;
                    }
                }

                // تحميل متوازٍ فائق السرعة (4 خيوط معاً) وكتابة مباشرة للقرص بدون إعادة ضغط بطيئة!
                final File[] results = new File[cleanUrls.size()];
                final CountDownLatch latch = new CountDownLatch(cleanUrls.size());
                for (int i = 0; i < cleanUrls.size(); i++) {
                    final int idx = i;
                    final String shotUrl = cleanUrls.get(i);
                    SHOT_DOWNLOAD_POOL.execute(() -> {
                        try {
                            File outFile = getCacheFile(appCtx, "shot_" + gameId + "_" + idx, ".jpg");
                            byte[] bytes = downloadBytesFollowingRedirects(shotUrl, MAX_BANNER_BYTES);
                            if (bytes != null && bytes.length >= 64) {
                                BitmapFactory.Options chk = new BitmapFactory.Options();
                                chk.inJustDecodeBounds = true;
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, chk);
                                if (chk.outWidth > 0 && chk.outHeight > 0) {
                                    File tmp = new File(outFile.getParentFile(), outFile.getName() + ".tmp");
                                    try (FileOutputStream fos = new FileOutputStream(tmp)) {
                                        fos.write(bytes);
                                        fos.flush();
                                    }
                                    if (outFile.exists()) {
                                        outFile.delete();
                                    }
                                    if (!tmp.renameTo(outFile)) {
                                        try (FileOutputStream fos = new FileOutputStream(outFile)) {
                                            fos.write(bytes);
                                            fos.flush();
                                        }
                                        tmp.delete();
                                    }
                                    if (outFile.isFile() && outFile.length() > 64) {
                                        decodeScaledBitmap(outFile, 520);
                                        results[idx] = outFile;
                                    }
                                }
                            }
                        } catch (Throwable ignored) {
                        } finally {
                            latch.countDown();
                        }
                    });
                }

                latch.await(25, TimeUnit.SECONDS);

                List<File> freshFiles = new ArrayList<>();
                for (File rf : results) {
                    if (rf != null && rf.isFile() && rf.length() > 64) {
                        freshFiles.add(rf);
                    }
                }

                if (!freshFiles.isEmpty()) {
                    // تنظيف أي صور قديمة زائدة فقط إذا تغيّر عدد اللقطات بعد تحديث البصمة
                    for (int j = freshFiles.size(); j < Math.max(savedCount, 12); j++) {
                        File stale = getCacheFile(appCtx, "shot_" + gameId + "_" + j, ".jpg");
                        if (stale.exists()) {
                            stale.delete();
                        }
                    }
                    prefs.edit()
                            .putString("token_" + metaKey, expectedToken)
                            .putInt("shots_count_" + gameId, freshFiles.size())
                            .apply();
                    enforceDiskCacheBudget(appCtx);
                    if (callback != null && mainHandler != null) {
                        final List<File> ready = new ArrayList<>(freshFiles);
                        mainHandler.post(() -> callback.onScreenshotsReady(ready));
                    }
                }
            } catch (Throwable ignored) {
            }
        });
    }

    // -------------------------------------------------------------------------
    // نواة التخزين الذكي بالبصمة للصور
    // -------------------------------------------------------------------------

    private static Bitmap loadCachedImageByKey(Context context, String cacheKey, String ext) {
        if (context == null || cacheKey == null || cacheKey.trim().isEmpty()) {
            return null;
        }
        Bitmap mem = MEMORY_CACHE.get(cacheKey);
        if (mem != null && !mem.isRecycled()) {
            return mem;
        }
        try {
            File f = getCacheFile(context, cacheKey, ext);
            if (!f.isFile() || f.length() <= 0) {
                return null;
            }
            Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (bmp != null) {
                MEMORY_CACHE.put(cacheKey, bmp);
            }
            return bmp;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void syncImageAsync(
            Context context,
            Executor executor,
            Handler mainHandler,
            String cacheKey,
            String ext,
            String imageUrl,
            String expectedHash,
            int maxBytes,
            Callback callback
    ) {
        if (context == null || imageUrl == null || !imageUrl.trim().startsWith("https://")) {
            return;
        }
        final Context appCtx = context.getApplicationContext();
        executor.execute(() -> {
            Bitmap bmp = syncImageSync(appCtx, cacheKey, ext, imageUrl, expectedHash, maxBytes);
            if (bmp != null && callback != null && mainHandler != null) {
                mainHandler.post(() -> callback.onIconReady(bmp));
            }
        });
    }

    private static Bitmap syncImageSync(
            Context context,
            String cacheKey,
            String ext,
            String imageUrl,
            String expectedHash,
            int maxBytes
    ) {
        if (context == null || cacheKey == null || imageUrl == null) {
            return null;
        }
        String cleanUrl = imageUrl.trim();
        if (!cleanUrl.startsWith("https://")) {
            return null;
        }
        Context appCtx = context.getApplicationContext();
        try {
            SharedPreferences prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            File outFile = getCacheFile(appCtx, cacheKey, ext);
            String expectedToken = buildCacheToken(expectedHash, cleanUrl);

            // فحص البصمة أولاً: إذا تطابقت مع الكاش المحلي، استخدم الملف المحلي فوراً بدون تحميل!
            if (isCacheFresh(prefs, cacheKey, expectedToken, cleanUrl, outFile, 16)) {
                return loadCachedImageByKey(appCtx, cacheKey, ext);
            }

            byte[] bytes = downloadBytesFollowingRedirects(cleanUrl, maxBytes);
            if (bytes == null || bytes.length == 0) {
                return loadCachedImageByKey(appCtx, cacheKey, ext);
            }

            Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (decoded == null) {
                return loadCachedImageByKey(appCtx, cacheKey, ext);
            }

            Bitmap.CompressFormat fmt = ".jpg".equals(ext)
                    ? Bitmap.CompressFormat.JPEG
                    : Bitmap.CompressFormat.PNG;
            int quality = ".jpg".equals(ext) ? 90 : 95;

            File tmpFile = new File(outFile.getParentFile(), outFile.getName() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
                decoded.compress(fmt, quality, fos);
                fos.flush();
            }
            if (outFile.exists()) {
                outFile.delete();
            }
            if (!tmpFile.renameTo(outFile)) {
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    decoded.compress(fmt, quality, fos);
                    fos.flush();
                }
                tmpFile.delete();
            }

            saveCacheToken(prefs, cacheKey, expectedToken, cleanUrl);
            MEMORY_CACHE.put(cacheKey, decoded);
            return decoded;
        } catch (Throwable ignored) {
            return loadCachedImageByKey(appCtx, cacheKey, ext);
        }
    }

    private static byte[] downloadBytesFollowingRedirects(String urlStr, int maxBytes) throws Exception {
        String currentUrl = urlStr;
        for (int redirect = 0; redirect < LauncherConfig.HTTP_MAX_REDIRECTS; redirect++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(currentUrl).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setUseCaches(false);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", STANDARD_USER_AGENT);
            conn.setRequestProperty("Accept", "image/webp,image/*,*/*;q=0.8");
            conn.setRequestProperty("Connection", "keep-alive");
            try {
                int code = conn.getResponseCode();
                if (code == HttpURLConnection.HTTP_MOVED_PERM
                        || code == HttpURLConnection.HTTP_MOVED_TEMP
                        || code == HttpURLConnection.HTTP_SEE_OTHER
                        || code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    if (loc == null || loc.trim().isEmpty()) {
                        return null;
                    }
                    currentUrl = new URL(new URL(currentUrl), loc).toString();
                    continue;
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    return null;
                }
                try (InputStream is = conn.getInputStream();
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[16384];
                    int read;
                    int total = 0;
                    while ((read = is.read(buf)) != -1) {
                        total += read;
                        if (total > maxBytes) {
                            return null;
                        }
                        baos.write(buf, 0, read);
                    }
                    return baos.toByteArray();
                }
            } finally {
                conn.disconnect();
            }
        }
        return null;
    }
}
