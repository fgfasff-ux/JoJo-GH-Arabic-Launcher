package com.jojo.gh.arabic.launcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * نموذج تعريف اللعبة المستقل (GameProfile) داخل منصة R.D.AV Multi-Game Localization Hub.
 */
public final class GameProfile {

    public final String id;
    public final String title;
    public final String arabicTitle;
    public final String subtitle;
    public final String packageName;
    public final int accentColor;
    public final int secondaryColor;
    public final int iconRes;
    public final int bannerRes;
    public final int promoVideoRes;
    public final String githubRepo;
    public final String manifestUrl;
    public final String betaManifestUrl;
    public final PatchStrategy patchStrategy;
    public final String targetGameVersionName;
    public final long targetGameVersionCode;
    public final String patchVersionLabel;
    public final boolean comingSoon;
    public final String statLines;
    public final String statScope;
    public final String statCompat;
    public final String statSafety;
    public final String defaultStoreDeveloper;
    public final String defaultStoreScore;
    public final String defaultStoreInstalls;
    public final String defaultStoreSummary;
    public final int defaultTranslatedStrings;
    public final List<String> defaultScreenshotsUrls;
    public final String defaultScreenshotsHash;

    public GameProfile(
            String id,
            String title,
            String arabicTitle,
            String subtitle,
            String packageName,
            int accentColor,
            int secondaryColor,
            int iconRes,
            int bannerRes,
            int promoVideoRes,
            String githubRepo,
            String manifestUrl,
            String betaManifestUrl,
            PatchStrategy patchStrategy,
            String targetGameVersionName,
            long targetGameVersionCode,
            String patchVersionLabel,
            boolean comingSoon,
            String statLines,
            String statScope,
            String statCompat,
            String statSafety,
            String defaultStoreDeveloper,
            String defaultStoreScore,
            String defaultStoreInstalls,
            String defaultStoreSummary,
            int defaultTranslatedStrings
    ) {
        this(
                id, title, arabicTitle, subtitle, packageName, accentColor, secondaryColor,
                iconRes, bannerRes, promoVideoRes, githubRepo, manifestUrl, betaManifestUrl,
                patchStrategy, targetGameVersionName, targetGameVersionCode, patchVersionLabel,
                comingSoon, statLines, statScope, statCompat, statSafety,
                defaultStoreDeveloper, defaultStoreScore, defaultStoreInstalls, defaultStoreSummary,
                defaultTranslatedStrings, Collections.emptyList(), ""
        );
    }

    public GameProfile(
            String id,
            String title,
            String arabicTitle,
            String subtitle,
            String packageName,
            int accentColor,
            int secondaryColor,
            int iconRes,
            int bannerRes,
            int promoVideoRes,
            String githubRepo,
            String manifestUrl,
            String betaManifestUrl,
            PatchStrategy patchStrategy,
            String targetGameVersionName,
            long targetGameVersionCode,
            String patchVersionLabel,
            boolean comingSoon,
            String statLines,
            String statScope,
            String statCompat,
            String statSafety,
            String defaultStoreDeveloper,
            String defaultStoreScore,
            String defaultStoreInstalls,
            String defaultStoreSummary,
            int defaultTranslatedStrings,
            List<String> defaultScreenshotsUrls,
            String defaultScreenshotsHash
    ) {
        this.id = id;
        this.title = title;
        this.arabicTitle = arabicTitle;
        this.subtitle = subtitle;
        this.packageName = packageName;
        this.accentColor = accentColor;
        this.secondaryColor = secondaryColor;
        this.iconRes = iconRes;
        this.bannerRes = bannerRes;
        this.promoVideoRes = promoVideoRes;
        this.githubRepo = githubRepo != null ? githubRepo.trim() : "";
        this.manifestUrl = manifestUrl != null ? manifestUrl.trim() : "";
        this.betaManifestUrl = betaManifestUrl != null ? betaManifestUrl.trim() : "";
        this.patchStrategy = patchStrategy != null ? patchStrategy : PatchStrategy.FAIRYGUI_FORCE;
        this.targetGameVersionName = targetGameVersionName != null ? targetGameVersionName : "";
        this.targetGameVersionCode = targetGameVersionCode;
        this.patchVersionLabel = patchVersionLabel != null ? patchVersionLabel : "";
        this.comingSoon = comingSoon;
        this.statLines = statLines;
        this.statScope = statScope;
        this.statCompat = statCompat;
        this.statSafety = statSafety;
        this.defaultStoreDeveloper = defaultStoreDeveloper != null ? defaultStoreDeveloper : "";
        this.defaultStoreScore = defaultStoreScore != null ? defaultStoreScore : "";
        this.defaultStoreInstalls = defaultStoreInstalls != null ? defaultStoreInstalls : "";
        this.defaultStoreSummary = defaultStoreSummary != null ? defaultStoreSummary : "";
        this.defaultTranslatedStrings = Math.max(0, defaultTranslatedStrings);
        List<String> cleanShots = new ArrayList<>();
        if (defaultScreenshotsUrls != null) {
            for (String u : defaultScreenshotsUrls) {
                if (u != null && u.trim().startsWith("https://")) {
                    cleanShots.add(u.trim());
                }
            }
        }
        this.defaultScreenshotsUrls = Collections.unmodifiableList(cleanShots);
        this.defaultScreenshotsHash = defaultScreenshotsHash != null ? defaultScreenshotsHash.trim().toLowerCase() : "";
    }

    public boolean hasOnlineConfig() {
        return !comingSoon && (!manifestUrl.isEmpty() || !githubRepo.isEmpty());
    }
}
