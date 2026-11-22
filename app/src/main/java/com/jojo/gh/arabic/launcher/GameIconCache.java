package com.jojo.gh.arabic.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * مدير التخزين المؤقت والمزامنة الفورية للوسائط عن بُعد (Disk + Memory Cache):
 * يدعم الأيقونات الديناميكية (icon_url)، صور البانر العلوية (banner_url)،
 * ومقاطع الفيديو الترويجية (hero_media_url) مع تجاوز الكاش عند تغيّر الرابط والعمل كاحتياطي محلي.
 */
public final class GameIconCache {

    private static final String PREFS_NAME = "rdav_icon_cache_meta";
    private static final String DIR_NAME = "icon_cache";
    private static final int MAX_ICON_BYTES = 4 * 1024 * 1024;    // 4 MB
    private static final int MAX_BANNER_BYTES = 8 * 1024 * 1024;  // 8 MB
    private static final int MAX_VIDEO_BYTES = 24 * 1024 * 1024;  // 24 MB
    private static final Map<String, Bitmap> MEMORY_CACHE = new ConcurrentHashMap<>();

    public interface Callback {
        void onIconReady(Bitmap bitmap);
    }

    public interface FileCallback {
        void onFileReady(File file);
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

    // -------------------------------------------------------------------------
    // 1) أيقونة اللعبة (icon_url)
    // -------------------------------------------------------------------------

    public static Bitmap loadCachedBitmap(Context context, String gameId) {
        return loadCachedImageByKey(context, "icon_" + gameId, ".png");
    }

    public static Bitmap syncIconSync(Context context, String gameId, String iconUrl) {
        return syncImageSync(context, "icon_" + gameId, ".png", iconUrl, MAX_ICON_BYTES);
    }

    public static void syncIconAsync(
            Context context,
            Executor executor,
            Handler mainHandler,
            String gameId,
            String iconUrl,
            Callback callback
    ) {
        syncImageAsync(context, executor, mainHandler, "icon_" + gameId, ".png", iconUrl, MAX_ICON_BYTES, callback);
    }

    // -------------------------------------------------------------------------
    // 2) صورة البانر الخلفية العلوية (banner_url)
    // -------------------------------------------------------------------------

    public static Bitmap loadCachedBannerBitmap(Context context, String gameId) {
        return loadCachedImageByKey(context, "banner_" + gameId, ".jpg");
    }

    public static Bitmap syncBannerSync(Context context, String gameId, String bannerUrl) {
        return syncImageSync(context, "banner_" + gameId, ".jpg", bannerUrl, MAX_BANNER_BYTES);
    }

    public static void syncBannerAsync(
            Context context,
            Executor executor,
            Handler mainHandler,
            String gameId,
            String bannerUrl,
            Callback callback
    ) {
        syncImageAsync(context, executor, mainHandler, "banner_" + gameId, ".jpg", bannerUrl, MAX_BANNER_BYTES, callback);
    }

    // -------------------------------------------------------------------------
    // 3) مقطع الفيديو الترويجي المحدّث عن بُعد (hero_media_url -> .mp4/.webm)
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
        executor.execute(() -> {
            try {
                String key = "hero_video_" + gameId;
                SharedPreferences prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                String cachedUrl = prefs.getString("url_" + key, "");
                File outFile = getCacheFile(appCtx, key, ".mp4");

                if (cleanUrl.equals(cachedUrl) && outFile.isFile() && outFile.length() > 1024) {
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
                prefs.edit().putString("url_" + key, cleanUrl).apply();
                if (callback != null && mainHandler != null) {
                    mainHandler.post(() -> callback.onFileReady(outFile));
                }
            } catch (Throwable ignored) {
            }
        });
    }

    // -------------------------------------------------------------------------
    // نواة التخزين والتحميل المشتركة للصور
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
            int maxBytes,
            Callback callback
    ) {
        if (context == null || imageUrl == null || !imageUrl.trim().startsWith("https://")) {
            return;
        }
        final Context appCtx = context.getApplicationContext();
        executor.execute(() -> {
            Bitmap bmp = syncImageSync(appCtx, cacheKey, ext, imageUrl, maxBytes);
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
            String cachedUrl = prefs.getString("url_" + cacheKey, "");
            File outFile = getCacheFile(appCtx, cacheKey, ext);

            if (cleanUrl.equals(cachedUrl) && outFile.isFile() && outFile.length() > 0) {
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

            prefs.edit().putString("url_" + cacheKey, cleanUrl).apply();
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
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(25000);
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.setRequestProperty("User-Agent", "RDAV-MultiGame-Hub/" + LauncherConfig.APP_VERSION);
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
