package com.jojo.gh.arabic.launcher;

import org.json.JSONObject;

/**
 * نموذج بيانات يمثّل ملف manifest.json المستضاف على GitHub Releases،
 * شامل حقول التوافق لحارس الأمان (Version Safety Guard).
 */
public final class PatchManifest {

    public final String patchVersion;
    public final String gameVersion;
    public final long targetGameVersionCode;
    public final String expectedLangOrigMd5;
    public final long expectedLangOrigSize;
    public final long expectedForceConfSize;
    public final String releaseDate;
    public final String changelog;
    public final String downloadUrl;
    public final String sha256;
    public final long sizeBytes;
    public final String targetSubpath;
    public final String channel;

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
                expectedLangOrigMd5, expectedLangOrigSize, expectedForceConfSize,
                releaseDate, changelog, downloadUrl, sha256, sizeBytes, targetSubpath,
                "public"
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
        this.patchVersion = patchVersion != null ? patchVersion.trim() : "1.0.0";
        this.gameVersion = gameVersion != null ? gameVersion.trim() : LauncherConfig.TARGET_GAME_VERSION_NAME;
        this.targetGameVersionCode = targetGameVersionCode > 0 ? targetGameVersionCode : LauncherConfig.TARGET_GAME_VERSION_CODE;
        this.expectedLangOrigMd5 = (expectedLangOrigMd5 != null && !expectedLangOrigMd5.trim().isEmpty())
                ? expectedLangOrigMd5.trim().toUpperCase()
                : LauncherConfig.TARGET_LANG_ORIG_MD5;
        this.expectedLangOrigSize = expectedLangOrigSize > 0 ? expectedLangOrigSize : LauncherConfig.TARGET_LANG_ORIG_SIZE;
        this.expectedForceConfSize = expectedForceConfSize > 0 ? expectedForceConfSize : LauncherConfig.TARGET_INDEX_ORIG_SIZE;
        this.releaseDate = releaseDate != null ? releaseDate.trim() : "";
        this.changelog = changelog != null ? changelog.trim() : "";
        this.downloadUrl = downloadUrl != null ? downloadUrl.trim() : "";
        this.sha256 = sha256 != null ? sha256.trim().toLowerCase() : "";
        this.sizeBytes = Math.max(0L, sizeBytes);
        this.targetSubpath = LauncherConfig.normalizeSubpath(targetSubpath);
        this.channel = (channel != null && !channel.trim().isEmpty()) ? channel.trim().toLowerCase() : "public";
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
        return new PatchManifest(
                obj.optString("patch_version", "1.0.0"),
                obj.optString("game_version", LauncherConfig.TARGET_GAME_VERSION_NAME),
                obj.optLong("target_game_version_code", LauncherConfig.TARGET_GAME_VERSION_CODE),
                obj.optString("expected_lang_orig_md5", LauncherConfig.TARGET_LANG_ORIG_MD5),
                obj.optLong("expected_lang_orig_size", LauncherConfig.TARGET_LANG_ORIG_SIZE),
                obj.optLong("expected_force_conf_size", LauncherConfig.TARGET_INDEX_ORIG_SIZE),
                obj.optString("release_date", ""),
                obj.optString("changelog", ""),
                downloadUrl,
                obj.optString("sha256", ""),
                obj.optLong("size_bytes", 0L),
                obj.optString("target_subpath", ""),
                obj.optString("channel", "public")
        );
    }

    public String resolveEffectiveSubpath() {
        if (!targetSubpath.isEmpty()) {
            return targetSubpath;
        }
        return LauncherConfig.normalizeSubpath(LauncherConfig.GAME_TARGET_SUBPATH);
    }
}
