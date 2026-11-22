package com.jojo.gh.arabic.launcher;

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
            String statSafety
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
    }

    public boolean hasOnlineConfig() {
        return !comingSoon && (!manifestUrl.isEmpty() || !githubRepo.isEmpty());
    }
}
