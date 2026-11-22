package com.jojo.gh.arabic.launcher;

import android.content.Context;
import android.os.Build;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * نموذج حزمة البلاغات والاقتراحات (FeedbackPayload) بصيغة JSON منظّمة
 * للربط المستقبلي مع نقطة استقبال سحابية / أداة الـ PC (RDAV_Updater)،
 * مع حفظ محلي وتنسيق بشري جاهز للمشاركة والإرسال إلى @R.D.AV على Instagram.
 */
public final class FeedbackPayload {

    public static final String TYPE_SUGGESTION = "suggestion";
    public static final String TYPE_BUG_REPORT = "bug_report";

    public final String type;
    public final String typeArabicLabel;
    public final String message;
    public final long createdAtMs;
    public final String gameId;
    public final String gameTitle;
    public final String packageName;
    public final String installedGameVersion;
    public final long installedGameVersionCode;
    public final String patchStrategy;
    public final boolean patchInstalled;
    public final String installedPatchVersion;
    public final String latestManifestVersion;
    public final boolean betaMode;
    public final String launcherVersion;
    public final String deviceManufacturer;
    public final String deviceModel;
    public final String androidRelease;
    public final int sdkInt;
    public final List<File> attachmentFiles;

    public FeedbackPayload(
            String type,
            String message,
            GameProfile profile,
            String installedGameVersion,
            long installedGameVersionCode,
            boolean patchInstalled,
            String installedPatchVersion,
            String latestManifestVersion,
            boolean betaMode,
            List<File> attachmentFiles
    ) {
        this.type = TYPE_SUGGESTION.equals(type) ? TYPE_SUGGESTION : TYPE_BUG_REPORT;
        this.typeArabicLabel = TYPE_SUGGESTION.equals(this.type) ? "اقتراح" : "الإبلاغ عن مشكلة";
        this.message = message != null ? message.trim() : "";
        this.createdAtMs = System.currentTimeMillis();
        this.gameId = profile != null ? profile.id : "";
        this.gameTitle = profile != null ? profile.title : LauncherConfig.APP_TITLE;
        this.packageName = profile != null ? profile.packageName : LauncherConfig.GAME_PACKAGE;
        this.installedGameVersion = (installedGameVersion != null && !installedGameVersion.trim().isEmpty())
                ? installedGameVersion.trim()
                : "غير مثبتة";
        this.installedGameVersionCode = installedGameVersionCode;
        this.patchStrategy = profile != null ? profile.patchStrategy.code : "FAIRYGUI_FORCE";
        this.patchInstalled = patchInstalled;
        this.installedPatchVersion = (installedPatchVersion != null && !installedPatchVersion.trim().isEmpty())
                ? installedPatchVersion.trim()
                : (patchInstalled ? "مثبّت" : "غير مثبّت");
        this.latestManifestVersion = latestManifestVersion != null ? latestManifestVersion.trim() : "";
        this.betaMode = betaMode;
        this.launcherVersion = LauncherConfig.APP_VERSION;
        this.deviceManufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER : "";
        this.deviceModel = Build.MODEL != null ? Build.MODEL : "";
        this.androidRelease = Build.VERSION.RELEASE != null ? Build.VERSION.RELEASE : "";
        this.sdkInt = Build.VERSION.SDK_INT;
        this.attachmentFiles = new ArrayList<>();
        if (attachmentFiles != null) {
            for (File f : attachmentFiles) {
                if (f != null && f.isFile()) {
                    this.attachmentFiles.add(f);
                }
            }
        }
    }

    public JSONObject toJson(boolean includeBase64Images) throws Exception {
        JSONObject root = new JSONObject();
        root.put("schema_version", 1);
        root.put("type", type);
        root.put("type_label", typeArabicLabel);
        root.put("message", message);
        root.put("created_at_ms", createdAtMs);
        root.put("created_at_iso", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date(createdAtMs)));

        JSONObject gameObj = new JSONObject();
        gameObj.put("id", gameId);
        gameObj.put("title", gameTitle);
        gameObj.put("package_name", packageName);
        gameObj.put("installed_version", installedGameVersion);
        gameObj.put("installed_version_code", installedGameVersionCode);
        gameObj.put("patch_strategy", patchStrategy);
        root.put("game", gameObj);

        JSONObject patchObj = new JSONObject();
        patchObj.put("installed", patchInstalled);
        patchObj.put("installed_version", installedPatchVersion);
        patchObj.put("latest_manifest_version", latestManifestVersion);
        patchObj.put("beta_mode", betaMode);
        root.put("patch", patchObj);

        JSONObject deviceObj = new JSONObject();
        deviceObj.put("launcher_version", launcherVersion);
        deviceObj.put("manufacturer", deviceManufacturer);
        deviceObj.put("model", deviceModel);
        deviceObj.put("android_release", androidRelease);
        deviceObj.put("sdk_int", sdkInt);
        root.put("device", deviceObj);

        JSONArray attArr = new JSONArray();
        for (int i = 0; i < attachmentFiles.size(); i++) {
            File f = attachmentFiles.get(i);
            JSONObject a = new JSONObject();
            a.put("index", i + 1);
            a.put("filename", f.getName());
            a.put("mime_type", "image/jpeg");
            a.put("size_bytes", f.length());
            if (includeBase64Images) {
                byte[] bytes = readAllBytes(f);
                a.put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP));
            }
            attArr.put(a);
        }
        root.put("attachments", attArr);
        return root;
    }

    /**
     * يحفظ حزمة البلاغ كاملة (JSON + الصور) في مجلد feedback_outbox المحلي للرجوع إليها أو مزامنتها لاحقاً.
     */
    public File saveBundleLocally(Context context) {
        try {
            File outbox = new File(context.getFilesDir(), "feedback_outbox");
            if (!outbox.exists()) {
                outbox.mkdirs();
            }
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(createdAtMs));
            File jsonFile = new File(outbox, "feedback_" + type + "_" + stamp + ".json");
            String pretty = toJson(false).toString(2);
            try (FileOutputStream fos = new FileOutputStream(jsonFile)) {
                fos.write(pretty.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
            return jsonFile;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * يرسل حزمة FeedbackPayload بصيغة JSON إلى نقطة استقبال سحابية (Webhook / GitHub Issues Proxy)
     * عندما يكون حقل feedback_webhook_url مضبوطاً في manifest.json.
     */
    public boolean postToWebhookSync(String webhookUrl) throws Exception {
        if (webhookUrl == null || !webhookUrl.trim().startsWith("https://")) {
            return false;
        }
        byte[] body = toJson(true).toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) new URL(webhookUrl.trim()).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "RDAV-MultiGame-Hub/" + launcherVersion);
        conn.setFixedLengthStreamingMode(body.length);
        try {
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                return true;
            }
            throw new IllegalStateException("HTTP " + code);
        } finally {
            conn.disconnect();
        }
    }

    /**
     * ينشئ نصاً منسقاً وواضحاً للنسخ أو المشاركة المباشرة إلى @R.D.AV على Instagram.
     */
    public String formatShareText() {
        StringBuilder sb = new StringBuilder();
        sb.append("📩 [").append(typeArabicLabel).append(" — R.D.AV Multi-Game Hub]\n\n");
        sb.append("📝 التفاصيل:\n").append(message).append("\n\n");
        sb.append("━━━━━━━━━━━━━━━\n");
        sb.append("⚙ البيانات التقنية (تلقائية):\n");
        sb.append("• اللعبة: ").append(gameTitle).append("\n");
        sb.append("• الحزمة: ").append(packageName).append("\n");
        sb.append("• إصدار اللعبة المثبت: ").append(installedGameVersion);
        if (installedGameVersionCode > 0) {
            sb.append(" (#").append(installedGameVersionCode).append(")");
        }
        sb.append("\n");
        sb.append("• إصدار التعريب الحالي: ").append(installedPatchVersion).append("\n");
        sb.append("• إصدار اللانشر: v").append(launcherVersion);
        if (betaMode) {
            sb.append(" [Beta]");
        }
        sb.append("\n");
        sb.append("• الجهاز: ").append(deviceManufacturer).append(" ").append(deviceModel)
                .append(" (Android ").append(androidRelease).append(" / API ").append(sdkInt).append(")\n");
        if (!attachmentFiles.isEmpty()) {
            sb.append("• الصور المرفقة: ").append(attachmentFiles.size()).append(" صورة\n");
        }
        return sb.toString();
    }

    private static byte[] readAllBytes(File file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = fis.read(buf)) != -1) {
                baos.write(buf, 0, r);
            }
            return baos.toByteArray();
        }
    }
}
