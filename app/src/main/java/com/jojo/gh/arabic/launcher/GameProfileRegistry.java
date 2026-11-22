package com.jojo.gh.arabic.launcher;

import android.graphics.Color;

import java.util.ArrayList;
import java.util.Arrays;
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
                    LauncherConfig.STAT_SAFETY,
                    "Wanda Cinemas Games",
                    "4.5 ★",
                    "+1,000,000",
                    "كوّن فريق أحلامك من 6 أجيال من أبطال جوجو في لعبة تقمص الأدوار الرسمية ثلاثية الأبعاد بقدرات الستاند الاستراتيجية.",
                    15967,
                    Arrays.asList(
                            "https://play-lh.googleusercontent.com/sX0IfnPvBEkkbMyWNSmJXPcj27eOo1F4F0d-KS9M7P9PbAZ-CP5kF6wZ1cNmN9kjhgLrDmobgSl51Slju4k=w1280",
                            "https://play-lh.googleusercontent.com/3lFDQrOHr4DZQEZ2l6GcRHjXkF542QeUh9lsDHZwXY9wRgJy4fgum6-zS-sOb-ksKF_TiW_Q8X7HFyYBsah2444=w1280",
                            "https://play-lh.googleusercontent.com/mn13tXbk-iHbDbwytDiBLRVnfCCtr681FmWaeDfdMiW0zjQX7_oNMflCSU6lDgsCniQT1Ew3GtDnS7_qp43UPVM=w1280",
                            "https://play-lh.googleusercontent.com/etYOzTQapiFlDotsBtpLm5iEQAuebR080a7bRM27-6khwBvR8TuYr_zwkyt1WwPoAnM-TGuQI62xmyd9EZdBXbk=w1280",
                            "https://play-lh.googleusercontent.com/sSV8sh5pwKcaW3XKh461y7GCtXzQO0gRq3gIMqXV6s05zMD7DjfhPZyXJdpqw9hjuH3lLvr5kmZKHktMsqYpIKY=w1280",
                            "https://play-lh.googleusercontent.com/i28I5vhCpF5rWH4FhzgjZx_68k_v51VZB15fX91DN9OrdA5staX-J26MN8P8UoXY75_Xqk1cFryw43WaLfYUocc=w1280"
                    ),
                    "84a4242904daf457"
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
                    R.drawable.bg_dbl_banner,
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
                    "قريباً في R.D.AV Hub",
                    "Bandai Namco Entertainment",
                    "4.5 ★",
                    "+100,000,000",
                    "لعبة قتال بطاقات ثلاثية الأبعاد لعالم دراغون بول — قيد التجهيز عبر محرك ASSET_REPLACE.",
                    0
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
