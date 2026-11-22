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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * مدير التخزين المؤقت للأيقونات الديناميكية (Disk + Memory Cache).
 * يحمّل أيقونة اللعبة تلقائياً من حقل icon_url في manifest.json ويخزّنها محلياً على القرص،
 * مع الرجوع للأيقونة المدمجة عند انقطاع الإنترنت أو قبل أول مزامنة.
 */
public final class GameIconCache {

    private static final String PREFS_NAME = "rdav_icon_cache_meta";
    private static final String DIR_NAME = "icon_cache";
    private static final int MAX_ICON_BYTES = 4 * 1024 * 1024; // 4 MB safety cap
    private static final Map<String, Bitmap> MEMORY_CACHE = new ConcurrentHashMap<>();

    public interface Callback {
        void onIconReady(Bitmap bitmap);
    }

    private GameIconCache() {
    }

    private static File getIconFile(Context context, String gameId) {
        File dir = new File(context.getFilesDir(), DIR_NAME);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String safeId = (gameId == null ? "default" : gameId).replaceAll("[^a-zA-Z0-9._-]", "_");
        return new File(dir, safeId + ".png");
    }

    /**
     * يحمّل الأيقونة المخبأة محلياً (من الذاكرة أو القرص) دون اتصال بالإنترنت.
     */
    public static Bitmap loadCachedBitmap(Context context, String gameId) {
        if (context == null || gameId == null || gameId.trim().isEmpty()) {
            return null;
        }
        Bitmap mem = MEMORY_CACHE.get(gameId);
        if (mem != null && !mem.isRecycled()) {
            return mem;
        }
        try {
            File f = getIconFile(context, gameId);
            if (!f.isFile() || f.length() <= 0) {
                return null;
            }
            Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (bmp != null) {
                MEMORY_CACHE.put(gameId, bmp);
            }
            return bmp;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * يزامن أيقونة اللعبة مع رابط icon_url في الخلفية:
     * - إذا لم يتغير الرابط والملف المخبأ موجود: يعيد النسخة المخبأة فوراً دون إعادة تحميل.
     * - إذا تغيّر الرابط أو لم يكن الملف مخبأً: يحمّلها، يحفظها في Disk Cache، ويُبلّغ الواجهة.
     */
    public static void syncIconAsync(
            Context context,
            Executor executor,
            Handler mainHandler,
            String gameId,
            String iconUrl,
            Callback callback
    ) {
        if (context == null || gameId == null || gameId.trim().isEmpty()) {
            return;
        }
        final String cleanUrl = iconUrl != null ? iconUrl.trim() : "";
        if (cleanUrl.isEmpty() || !cleanUrl.startsWith("https://")) {
            return;
        }

        final Context appCtx = context.getApplicationContext();
        executor.execute(() -> {
            try {
                SharedPreferences prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                String cachedUrl = prefs.getString("url_" + gameId, "");
                File iconFile = getIconFile(appCtx, gameId);

                if (cleanUrl.equals(cachedUrl) && iconFile.isFile() && iconFile.length() > 0) {
                    Bitmap existing = loadCachedBitmap(appCtx, gameId);
                    if (existing != null && callback != null && mainHandler != null) {
                        mainHandler.post(() -> callback.onIconReady(existing));
                    }
                    return;
                }

                byte[] bytes = downloadBytesFollowingRedirects(cleanUrl);
                if (bytes == null || bytes.length == 0) {
                    return;
                }

                Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (decoded == null) {
                    return;
                }

                File tmpFile = new File(iconFile.getParentFile(), iconFile.getName() + ".tmp");
                try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
                    decoded.compress(Bitmap.CompressFormat.PNG, 95, fos);
                    fos.flush();
                }
                if (iconFile.exists()) {
                    iconFile.delete();
                }
                if (!tmpFile.renameTo(iconFile)) {
                    try (FileOutputStream fos = new FileOutputStream(iconFile)) {
                        decoded.compress(Bitmap.CompressFormat.PNG, 95, fos);
                        fos.flush();
                    }
                    tmpFile.delete();
                }

                prefs.edit().putString("url_" + gameId, cleanUrl).apply();
                MEMORY_CACHE.put(gameId, decoded);

                if (callback != null && mainHandler != null) {
                    mainHandler.post(() -> callback.onIconReady(decoded));
                }
            } catch (Throwable ignored) {
                // عند انقطاع الإنترنت أو فشل التحميل نكتفي بالنسخة المخبأة أو الأيقونة المدمجة
            }
        });
    }

    private static byte[] downloadBytesFollowingRedirects(String urlStr) throws Exception {
        String currentUrl = urlStr;
        for (int redirect = 0; redirect < LauncherConfig.HTTP_MAX_REDIRECTS; redirect++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(currentUrl).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(20000);
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
                    byte[] buf = new byte[8192];
                    int read;
                    int total = 0;
                    while ((read = is.read(buf)) != -1) {
                        total += read;
                        if (total > MAX_ICON_BYTES) {
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
