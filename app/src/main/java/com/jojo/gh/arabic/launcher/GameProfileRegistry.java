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

            // 2) Dragon Ball Legends — جاهز بالكامل للاختبار السري (Beta Mode) وقريباً للجمهور العام
            list.add(new GameProfile(
                    "dragon_ball_legends",
                    "Dragon Ball Legends",
                    "دراغون بول ليجندز",
                    "تحديث تعريب دراغون بول ليجندز — القوائم والقدرات والمهارات بالعربية الفصحى",
                    "com.bandainamcoent.dblegends_ww",
                    Color.parseColor("#F97316"),
                    Color.parseColor("#2563EB"),
                    R.drawable.ic_dbl_coming_soon,
                    R.drawable.bg_dbl_banner,
                    0,
                    SecretVault.dblGithubRepo(),
                    SecretVault.dblManifestUrl(),
                    SecretVault.dblBetaManifestUrl(),
                    PatchStrategy.ASSET_REPLACE,
                    "6.32.0",
                    455L,
                    "v0.1.0-beta.1",
                    true,
                    "5,078 نصاً معرّباً (المرحلة الأولى)",
                    "القوائم والإعدادات والقدرات والمهارات",
                    "متوافق 100% مع الإصدار 6.32.0",
                    "🛡 مفحوص ومعتمد ✓",
                    "Bandai Namco Entertainment Inc.",
                    "4.4 ★",
                    "+50,000,000",
                    "لعبة قتال أكشن ثلاثية الأبعاد مستوحاة من أنمي دراغون بول — نسخة اختبار Beta متاحة الآن للمطوّر.",
                    5078,
                    Arrays.asList(
                            "https://play-lh.googleusercontent.com/tYOT4BBXdLB3BNrm2J5OPuvAfzI8mOOeZgjaWe0g28KvBGEZfy5zNFDaWs3dpzhtyl99UdohGZnJNp9O6mnvsg=w1280",
                            "https://play-lh.googleusercontent.com/bfLlgVepSPyOmeYzv3I2OiUUh39F8F1FrbpBWjHoOvn9LWkxxjtPNY_VeFpdySjvARkQO12o0rCYldsQYTvN=w1280",
                            "https://play-lh.googleusercontent.com/irI0arp-7j0J9KoPJ7Loj0e0U1uS5wSq8qHeb7NBrBaoREfiipmRkakRwkyTQ_38u_V2ByKu1HeTC8cjRAGv=w1280",
                            "https://play-lh.googleusercontent.com/gbtaT0Xa3ATTJXr6AVSPqZS7ccZvPKwmchXYmDEulzQaTgz39ANcgy09qOWqvKy1xyBVZZQBoH7-EMoj8PWc3g=w1280",
                            "https://play-lh.googleusercontent.com/pCKU5GWs4RX0McQasXHEWnecMyU-o9Rw_x-zuYcE1RHG1upMXC7zLkVg-J_0cSHKaLP9Inr60jaDx1F0f1zX=w1280",
                            "https://play-lh.googleusercontent.com/-_OXA0ZqN-HRrmgia_KUJPpzoGsXwhbXcYomRWptThMS9DJ_-8i6orlGnbAxogfJGs5IboAyM2x-meeSmEyg8A=w1280"
                    ),
                    "48dccefe894d7951"
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
