package com.jojo.gh.arabic.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * نموذج بيانات يمثّل ملف manifest.json أو manifest_beta.json المستضاف على GitHub Releases،
 * شامل حقول حارس الأمان + التحكم الديناميكي الكامل عن بُعد (Remote Live Config):
 * icon_url, banner_url, hero_media_url, store_info, latest_game_version, latest_patch_version,
 * changelog, patch_stats, security_guard, و feedback_webhook_url.
 */
public final class PatchManifest {

    private static final String CACHE_PREFS = "rdav_manifest_cache";

    public final String patchVersion;
    public final String latestPatchVersion;
    public final String gameVersion;
    public final String latestGameVersion;
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

    // المزامنة الديناميكية للوسائط والبيانات وحارس الأمان عن بُعد (مع بصمات التخزين الذكي)
    public final String iconUrl;
    public final String iconHash;
    public final String bannerUrl;
    public final String bannerHash;
    public final String heroMediaUrl;
    public final String heroMediaHash;
    public final String heroEyebrow;
    public final String displayTitle;
    public final String heroTagline;
    public final String badgeLabel;
    public final String socialLabel;
    public final String socialUrl;
    public final List<String> screenshotsUrls;
    public final String screenshotsHash;
    public final String storeTitle;
    public final String storeSummary;
    public final String storeDeveloper;
    public final String storeScore;
    public final String storeInstalls;
    public final String storeContentRating;
    public final int statsTranslatedStrings;
    public final int statsTotalRows;
    public final String statsEngine;
    public final boolean securitySafe;
    public final boolean securityMaintenance;
    public final String securityMessage;
    public final String feedbackWebhookUrl;
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
                patchVersion, patchVersion, gameVersion, gameVersion, targetGameVersionCode,
                expectedLangOrigMd5, expectedLangOrigSize,
                "", "", 0L,
                expectedForceConfSize,
                releaseDate, changelog, downloadUrl, sha256, sizeBytes, targetSubpath,
                "public",
                "", "", "", "", "", "",
                "", "", "", "", "", "", Collections.emptyList(), "",
                "", "", "", "", "", "", 0, 0, "",
                true, false, "", "", ""
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
                patchVersion, patchVersion, gameVersion, gameVersion, targetGameVersionCode,
                expectedLangOrigMd5, expectedLangOrigSize,
                "", "", 0L,
                expectedForceConfSize,
                releaseDate, changelog, downloadUrl, sha256, sizeBytes, targetSubpath,
                channel,
                "", "", "", "", "", "",
                "", "", "", "", "", "", Collections.emptyList(), "",
                "", "", "", "", "", "", 0, 0, "",
                true, false, "", "", ""
        );
    }

    public PatchManifest(
            String patchVersion,
            String latestPatchVersion,
            String gameVersion,
            String latestGameVersion,
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
            String iconHash,
            String bannerUrl,
            String bannerHash,
            String heroMediaUrl,
            String heroMediaHash,
            String heroEyebrow,
            String displayTitle,
            String heroTagline,
            String badgeLabel,
            String socialLabel,
            String socialUrl,
            List<String> screenshotsUrls,
            String screenshotsHash,
            String storeTitle,
            String storeSummary,
            String storeDeveloper,
            String storeScore,
            String storeInstalls,
            String storeContentRating,
            int statsTranslatedStrings,
            int statsTotalRows,
            String statsEngine,
            boolean securitySafe,
            boolean securityMaintenance,
            String securityMessage,
            String feedbackWebhookUrl,
            String rawJson
    ) {
        this.patchVersion = patchVersion != null ? patchVersion.trim() : "1.0.0";
        this.latestPatchVersion = (latestPatchVersion != null && !latestPatchVersion.trim().isEmpty())
                ? latestPatchVersion.trim()
                : this.patchVersion;
        this.gameVersion = gameVersion != null ? gameVersion.trim() : LauncherConfig.TARGET_GAME_VERSION_NAME;
        this.latestGameVersion = (latestGameVersion != null && !latestGameVersion.trim().isEmpty())
                ? latestGameVersion.trim()
                : this.gameVersion;
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
        this.iconHash = iconHash != null ? iconHash.trim().toLowerCase() : "";
        this.bannerUrl = bannerUrl != null ? bannerUrl.trim() : "";
        this.bannerHash = bannerHash != null ? bannerHash.trim().toLowerCase() : "";
        this.heroMediaUrl = heroMediaUrl != null ? heroMediaUrl.trim() : "";
        this.heroMediaHash = heroMediaHash != null ? heroMediaHash.trim().toLowerCase() : "";
        this.heroEyebrow = heroEyebrow != null ? heroEyebrow.trim() : "";
        this.displayTitle = displayTitle != null ? displayTitle.trim() : "";
        this.heroTagline = heroTagline != null ? heroTagline.trim() : "";
        this.badgeLabel = badgeLabel != null ? badgeLabel.trim() : "";
        this.socialLabel = socialLabel != null ? socialLabel.trim() : "";
        this.socialUrl = socialUrl != null ? socialUrl.trim() : "";
        List<String> cleanShots = new ArrayList<>();
        if (screenshotsUrls != null) {
            for (String u : screenshotsUrls) {
                if (u != null && u.trim().startsWith("https://")) {
                    cleanShots.add(u.trim());
                }
            }
        }
        this.screenshotsUrls = Collections.unmodifiableList(cleanShots);
        this.screenshotsHash = screenshotsHash != null ? screenshotsHash.trim().toLowerCase() : "";
        this.storeTitle = storeTitle != null ? storeTitle.trim() : "";
        this.storeSummary = storeSummary != null ? storeSummary.trim() : "";
        this.storeDeveloper = storeDeveloper != null ? storeDeveloper.trim() : "";
        this.storeScore = storeScore != null ? storeScore.trim() : "";
        this.storeInstalls = storeInstalls != null ? storeInstalls.trim() : "";
        this.storeContentRating = storeContentRating != null ? storeContentRating.trim() : "";
        this.statsTranslatedStrings = Math.max(0, statsTranslatedStrings);
        this.statsTotalRows = Math.max(0, statsTotalRows);
        this.statsEngine = statsEngine != null ? statsEngine.trim() : "";
        this.securitySafe = securitySafe;
        this.securityMaintenance = securityMaintenance;
        this.securityMessage = securityMessage != null ? securityMessage.trim() : "";
        this.feedbackWebhookUrl = feedbackWebhookUrl != null ? feedbackWebhookUrl.trim() : "";
        this.rawJson = rawJson != null ? rawJson : "";
    }

    public boolean isBetaChannel() {
        return "beta".equalsIgnoreCase(channel);
    }

    public PatchManifest withChannel(String newChannel) {
        return new PatchManifest(
                patchVersion, latestPatchVersion, gameVersion, latestGameVersion, targetGameVersionCode,
                expectedLangOrigMd5, expectedLangOrigSize,
                expectedPatchedLangMd5, expectedPatchedLangSha256, expectedPatchedLangSize,
                expectedForceConfSize, releaseDate, changelog, downloadUrl, sha256, sizeBytes,
                targetSubpath, newChannel, iconUrl, iconHash, bannerUrl, bannerHash, heroMediaUrl, heroMediaHash,
                heroEyebrow, displayTitle, heroTagline, badgeLabel, socialLabel, socialUrl,
                screenshotsUrls, screenshotsHash,
                storeTitle, storeSummary, storeDeveloper, storeScore, storeInstalls, storeContentRating,
                statsTranslatedStrings, statsTotalRows, statsEngine,
                securitySafe, securityMaintenance, securityMessage, feedbackWebhookUrl, rawJson
        );
    }

    public String getEffectiveBannerImageUrl() {
        if (!bannerUrl.isEmpty()) {
            return bannerUrl;
        }
        if (!heroMediaUrl.isEmpty() && !GameIconCache.isVideoMediaUrl(heroMediaUrl)) {
            return heroMediaUrl;
        }
        return "";
    }

    public String getEffectiveBannerImageHash() {
        if (!bannerUrl.isEmpty()) {
            return bannerHash;
        }
        if (!heroMediaUrl.isEmpty() && !GameIconCache.isVideoMediaUrl(heroMediaUrl)) {
            return heroMediaHash;
        }
        return "";
    }

    public String getEffectiveHeroVideoUrl() {
        if (!heroMediaUrl.isEmpty() && GameIconCache.isVideoMediaUrl(heroMediaUrl)) {
            return heroMediaUrl;
        }
        return "";
    }

    public String getEffectiveHeroVideoHash() {
        if (!heroMediaUrl.isEmpty() && GameIconCache.isVideoMediaUrl(heroMediaUrl)) {
            return heroMediaHash;
        }
        return "";
    }

    public static PatchManifest fromJson(String jsonText) throws Exception {
        JSONObject obj = new JSONObject(jsonText);
        String downloadUrl = obj.optString("download_url", "").trim();
        if (downloadUrl.isEmpty()) {
            throw new IllegalArgumentException("حقل download_url مفقود في ملف manifest.json");
        }

        JSONObject storeObj = obj.optJSONObject("store_info");
        JSONObject statsObj = obj.optJSONObject("patch_stats");
        JSONObject sgObj = obj.optJSONObject("security_guard");

        String patchVer = obj.optString("patch_version", "1.0.0");
        String latestPatchVer = obj.optString("latest_patch_version", patchVer);
        String gameVer = obj.optString("game_version", LauncherConfig.TARGET_GAME_VERSION_NAME);
        String latestGameVer = obj.optString("latest_game_version", gameVer);

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

        boolean secSafe = sgObj == null || sgObj.optBoolean("safe", true);
        boolean secMaint = sgObj != null && sgObj.optBoolean("maintenance", false);
        String secMsg = sgObj != null ? sgObj.optString("message", "") : "";

        String feedbackWebhook = obj.optString("feedback_webhook_url", "").trim();
        if (feedbackWebhook.isEmpty()) {
            JSONObject fbObj = obj.optJSONObject("feedback");
            if (fbObj != null) {
                feedbackWebhook = fbObj.optString("webhook_url", "").trim();
            }
        }

        List<String> screenshotsUrls = new ArrayList<>();
        JSONArray shotsArr = obj.optJSONArray("screenshots_urls");
        if ((shotsArr == null || shotsArr.length() == 0) && storeObj != null) {
            shotsArr = storeObj.optJSONArray("screenshots_urls");
        }
        if (shotsArr != null) {
            for (int i = 0; i < shotsArr.length() && i < 12; i++) {
                String u = shotsArr.optString(i, "").trim();
                if (u.startsWith("https://")) {
                    screenshotsUrls.add(u);
                }
            }
        }
        String screenshotsHash = obj.optString("screenshots_hash", "").trim();
        if (screenshotsHash.isEmpty() && storeObj != null) {
            screenshotsHash = storeObj.optString("screenshots_hash", "").trim();
        }

        return new PatchManifest(
                patchVer,
                latestPatchVer,
                gameVer,
                latestGameVer,
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
                obj.optString("icon_hash", ""),
                obj.optString("banner_url", ""),
                obj.optString("banner_hash", ""),
                obj.optString("hero_media_url", ""),
                obj.optString("hero_media_hash", ""),
                obj.optString("hero_eyebrow", ""),
                obj.optString("display_title", ""),
                obj.optString("hero_tagline", ""),
                obj.optString("badge_label", ""),
                obj.optString("social_label", ""),
                obj.optString("social_url", ""),
                screenshotsUrls,
                screenshotsHash,
                storeTitle,
                storeSummary,
                storeDeveloper,
                storeScore,
                storeInstalls,
                storeContentRating,
                statsTranslated,
                statsTotal,
                statsEngine,
                secSafe,
                secMaint,
                secMsg,
                feedbackWebhook,
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
