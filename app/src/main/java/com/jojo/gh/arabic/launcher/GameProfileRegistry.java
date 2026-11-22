package com.jojo.gh.arabic.launcher;

import android.graphics.Color;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * سجل ملفات تعريف الألعاب المدعومة والقادمة في منصة R.D.AV Multi-Game Localization Hub.
 */
public final class GameProfileRegistry {

    private static List<GameProfile> cachedProfiles;

    private GameProfileRegistry() {
    }

    public static synchronized List<GameProfile> getAllProfiles() {
        if (cachedProfiles == null) {
            List<GameProfile> list = new ArrayList<>();

            // 1) JoJo's Bizarre Adventure: Golden Hymn (مكتمل وجاهز 100%)
            list.add(new GameProfile(
                    "jojo_golden_hymn",
                    LauncherConfig.APP_TITLE,
                    "الرياح الذهبية",
                    LauncherConfig.APP_SUBTITLE,
                    LauncherConfig.GAME_PACKAGE,
                    LauncherConfig.COL_ACCENT,
                    LauncherConfig.COL_PURPLE,
                    R.drawable.ic_jojo_game,
                    R.drawable.bg_promo_poster,
                    R.raw.bg_promo,
                    SecretVault.githubRepo(),
                    LauncherConfig.MANIFEST_URL,
                    "", // يُكتشف تلقائياً من أحدث Pre-release (manifest_beta.json) على GitHub
                    PatchStrategy.FAIRYGUI_FORCE,
                    LauncherConfig.TARGET_GAME_VERSION_NAME,
                    LauncherConfig.TARGET_GAME_VERSION_CODE,
                    "v3.0",
                    false,
                    LauncherConfig.STAT_LINES,
                    LauncherConfig.STAT_SCOPE,
                    LauncherConfig.STAT_COMPAT,
                    LauncherConfig.STAT_SAFETY
            ));

            // 2) Dragon Ball Legends — قريباً (جاهز بمحرك ASSET_REPLACE لإظهار قابلية التوسع)
            list.add(new GameProfile(
                    "dragon_ball_legends",
                    "Dragon Ball Legends",
                    "دراغون بول ليجندز",
                    "محرك ASSET_REPLACE جاهز — حزمة التعريب قيد الإعداد",
                    "com.bandainamcoent.dblegends_ww",
                    Color.parseColor("#F97316"),
                    Color.parseColor("#2563EB"),
                    R.drawable.ic_dbl_coming_soon,
                    R.drawable.ic_dbl_coming_soon,
                    0,
                    "",
                    "",
                    "",
                    PatchStrategy.ASSET_REPLACE,
                    "قريباً",
                    0L,
                    "قريباً",
                    true,
                    "محرك Unity / il2cpp جاهز",
                    "استبدال أصول files/assets/",
                    "نسخ احتياطي واستعادة تلقائية",
                    "قريباً في R.D.AV Hub"
            ));

            cachedProfiles = Collections.unmodifiableList(list);
        }
        return cachedProfiles;
    }

    public static GameProfile getDefaultProfile() {
        return getAllProfiles().get(0);
    }

    public static GameProfile findById(String id) {
        if (id != null) {
            for (GameProfile p : getAllProfiles()) {
                if (id.equals(p.id)) {
                    return p;
                }
            }
        }
        return getDefaultProfile();
    }
}
