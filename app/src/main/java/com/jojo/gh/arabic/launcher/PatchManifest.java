package com.jojo.gh.arabic.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * نموذج بيانات يمثّل ملف manifest.json أو manifest_beta.json المستضاف على GitHub Releases،
 * شامل حقول التوافق لحارس الأمان + رابط الأيقونة الديناميكية + بيانات المتجر + إحصائيات الحزمة.
 */
public final class PatchManifest {

    private static final String CACHE_PREFS = "rdav_manifest_cache";

    public final String patchVersion;
    public final String gameVersion;
    public final long targetGameVersionCode;
    public final String expectedLangOrigMd5;
    public final long expectedLangOrigSize;
    public final String expectedPatchedLangMd5;
    public final String expectedPatchedLangSha256;
    public final long expectedPatchedLangSize;
    public final long expectedForceConfSize;
    public final String releaseDate;
    public final String changelog;
    public final String downloadUrl;
    public final String sha256;
    public final long sizeBytes;
    public final String targetSubpath;
    public final String channel;

    // المزامنة الديناميكية من أداة الـ PC
    public final String iconUrl;
    public final String storeTitle;
    public final String storeSummary;
    public final String storeDeveloper;
    public final String storeScore;
    public final String storeInstalls;
    public final String storeContentRating;
    public final int statsTranslatedStrings;
    public final int statsTotalRows;
    public final String statsEngine;
    public final String rawJson;

    public PatchManifest(
            String patchVersion,
            String gameVersion,
            long targetGameVersionCode,
            String expectedLangOrigMd5,
            long expectedLangOrigSize,
            long expectedForceConfSize,
            String releaseDate,
            String changelog,
            String downloadUrl,
            String sha256,
            long sizeBytes,
            String targetSubpath
    ) {
        this(
                patchVersion, gameVersion, targetGameVersionCode,
                expectedLangOrigMd5, expectedLangOrigSize,
                "", "", 0L,
                expectedForceConfSize,
                releaseDate, changelog, downloadUrl, sha256, sizeBytes, targetSubpath,
                "public",
                "", "", "", "", "", "", "", 0, 0, "", ""
        );
    }

    public PatchManifest(
            String patchVersion,
            String gameVersion,
            long targetGameVersionCode,
            String expectedLangOrigMd5,
            long expectedLangOrigSize,
            long expectedForceConfSize,
            String releaseDate,
            String changelog,
            String downloadUrl,
            String sha256,
            long sizeBytes,
            String targetSubpath,
            String channel
    ) {
        this(
                patchVersion, gameVersion, targetGameVersionCode,
                expectedLangOrigMd5, expectedLangOrigSize,
                "", "", 0L,
                expectedForceConfSize,
                releaseDate, changelog, downloadUrl, sha256, sizeBytes, targetSubpath,
                channel,
                "", "", "", "", "", "", "", 0, 0, "", ""
        );
    }

    public PatchManifest(
            String patchVersion,
            String gameVersion,
            long targetGameVersionCode,
            String expectedLangOrigMd5,
            long expectedLangOrigSize,
            String expectedPatchedLangMd5,
            String expectedPatchedLangSha256,
            long expectedPatchedLangSize,
            long expectedForceConfSize,
            String releaseDate,
            String changelog,
            String downloadUrl,
            String sha256,
            long sizeBytes,
            String targetSubpath,
            String channel,
            String iconUrl,
            String storeTitle,
            String storeSummary,
            String storeDeveloper,
            String storeScore,
            String storeInstalls,
            String storeContentRating,
            int statsTranslatedStrings,
            int statsTotalRows,
            String statsEngine,
            String rawJson
    ) {
        this.patchVersion = patchVersion != null ? patchVersion.trim() : "1.0.0";
        this.gameVersion = gameVersion != null ? gameVersion.trim() : LauncherConfig.TARGET_GAME_VERSION_NAME;
        this.targetGameVersionCode = targetGameVersionCode > 0 ? targetGameVersionCode : LauncherConfig.TARGET_GAME_VERSION_CODE;
        this.expectedLangOrigMd5 = (expectedLangOrigMd5 != null && !expectedLangOrigMd5.trim().isEmpty())
                ? expectedLangOrigMd5.trim().toUpperCase()
                : LauncherConfig.TARGET_LANG_ORIG_MD5;
        this.expectedLangOrigSize = expectedLangOrigSize > 0 ? expectedLangOrigSize : LauncherConfig.TARGET_LANG_ORIG_SIZE;
        this.expectedPatchedLangMd5 = expectedPatchedLangMd5 != null ? expectedPatchedLangMd5.trim().toUpperCase() : "";
        this.expectedPatchedLangSha256 = expectedPatchedLangSha256 != null ? expectedPatchedLangSha256.trim().toLowerCase() : "";
        this.expectedPatchedLangSize = Math.max(0L, expectedPatchedLangSize);
        this.expectedForceConfSize = expectedForceConfSize > 0 ? expectedForceConfSize : LauncherConfig.TARGET_INDEX_ORIG_SIZE;
        this.releaseDate = releaseDate != null ? releaseDate.trim() : "";
        this.changelog = changelog != null ? changelog.trim() : "";
        this.downloadUrl = downloadUrl != null ? downloadUrl.trim() : "";
        this.sha256 = sha256 != null ? sha256.trim().toLowerCase() : "";
        this.sizeBytes = Math.max(0L, sizeBytes);
        this.targetSubpath = LauncherConfig.normalizeSubpath(targetSubpath);
        this.channel = (channel != null && !channel.trim().isEmpty()) ? channel.trim().toLowerCase() : "public";
        this.iconUrl = iconUrl != null ? iconUrl.trim() : "";
        this.storeTitle = storeTitle != null ? storeTitle.trim() : "";
        this.storeSummary = storeSummary != null ? storeSummary.trim() : "";
        this.storeDeveloper = storeDeveloper != null ? storeDeveloper.trim() : "";
        this.storeScore = storeScore != null ? storeScore.trim() : "";
        this.storeInstalls = storeInstalls != null ? storeInstalls.trim() : "";
        this.storeContentRating = storeContentRating != null ? storeContentRating.trim() : "";
        this.statsTranslatedStrings = Math.max(0, statsTranslatedStrings);
        this.statsTotalRows = Math.max(0, statsTotalRows);
        this.statsEngine = statsEngine != null ? statsEngine.trim() : "";
        this.rawJson = rawJson != null ? rawJson : "";
    }

    public boolean isBetaChannel() {
        return "beta".equalsIgnoreCase(channel);
    }

    public static PatchManifest fromJson(String jsonText) throws Exception {
        JSONObject obj = new JSONObject(jsonText);
        String downloadUrl = obj.optString("download_url", "").trim();
        if (downloadUrl.isEmpty()) {
            throw new IllegalArgumentException("حقل download_url مفقود في ملف manifest.json");
        }

        JSONObject storeObj = obj.optJSONObject("store_info");
        JSONObject statsObj = obj.optJSONObject("patch_stats");

        String patchedMd5 = obj.optString("expected_patched_lang_md5", "");
        if (patchedMd5.trim().isEmpty() && statsObj != null) {
            patchedMd5 = statsObj.optString("patched_lang_md5", "");
        }
        String patchedSha = obj.optString("expected_patched_lang_sha256", "");
        if (patchedSha.trim().isEmpty() && statsObj != null) {
            patchedSha = statsObj.optString("patched_lang_sha256", "");
        }

        String storeTitle = storeObj != null ? storeObj.optString("title", "") : "";
        String storeSummary = storeObj != null ? storeObj.optString("summary", "") : "";
        if (storeSummary.trim().isEmpty() && storeObj != null) {
            storeSummary = storeObj.optString("description_short", "");
        }
        String storeDeveloper = storeObj != null ? storeObj.optString("developer", "") : "";
        String storeScore = storeObj != null ? storeObj.optString("score", "") : "";
        String storeInstalls = storeObj != null ? storeObj.optString("installs", "") : "";
        String storeContentRating = storeObj != null ? storeObj.optString("content_rating", "") : "";

        int statsTranslated = statsObj != null ? statsObj.optInt("translated_strings", 0) : 0;
        int statsTotal = statsObj != null ? statsObj.optInt("total_rows", 0) : 0;
        String statsEngine = statsObj != null ? statsObj.optString("engine", "") : "";

        return new PatchManifest(
                obj.optString("patch_version", "1.0.0"),
                obj.optString("game_version", LauncherConfig.TARGET_GAME_VERSION_NAME),
                obj.optLong("target_game_version_code", LauncherConfig.TARGET_GAME_VERSION_CODE),
                obj.optString("expected_lang_orig_md5", LauncherConfig.TARGET_LANG_ORIG_MD5),
                obj.optLong("expected_lang_orig_size", LauncherConfig.TARGET_LANG_ORIG_SIZE),
                patchedMd5,
                patchedSha,
                obj.optLong("expected_patched_lang_size", 0L),
                obj.optLong("expected_force_conf_size", LauncherConfig.TARGET_INDEX_ORIG_SIZE),
                obj.optString("release_date", ""),
                obj.optString("changelog", ""),
                downloadUrl,
                obj.optString("sha256", ""),
                obj.optLong("size_bytes", 0L),
                obj.optString("target_subpath", ""),
                obj.optString("channel", "public"),
                obj.optString("icon_url", ""),
                storeTitle,
                storeSummary,
                storeDeveloper,
                storeScore,
                storeInstalls,
                storeContentRating,
                statsTranslated,
                statsTotal,
                statsEngine,
                jsonText
        );
    }

    public void saveToCache(Context context, String gameId) {
        if (context == null || gameId == null || rawJson.isEmpty()) {
            return;
        }
        try {
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE);
            prefs.edit().putString("manifest_" + gameId, rawJson).apply();
        } catch (Throwable ignored) {
        }
    }

    public static PatchManifest loadFromCache(Context context, String gameId) {
        if (context == null || gameId == null) {
            return null;
        }
        try {
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE);
            String json = prefs.getString("manifest_" + gameId, "");
            if (json == null || json.trim().isEmpty()) {
                return null;
            }
            return fromJson(json);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public String resolveEffectiveSubpath() {
        if (!targetSubpath.isEmpty()) {
            return targetSubpath;
        }
        return LauncherConfig.normalizeSubpath(LauncherConfig.GAME_TARGET_SUBPATH);
    }
}
