package com.jojo.gh.arabic.launcher;

import android.graphics.Color;

/**
 * ملف الإعدادات المركزي لمعرّب لعبة JoJo's Bizarre Adventure: Golden Hymn (تعريب R.D.AV).
 * جميع البصمات والروابط والمعرّفات الحساسة تُفك في الذاكرة فقط لحظة التشغيل عبر SecretVault.
 */
public final class LauncherConfig {

    private LauncherConfig() {
    }

    // =========================================================================
    // 1) معلومات التطبيق وهويته
    // =========================================================================
    public static final String APP_VERSION = "2.1.0";
    public static final String BRAND_TITLE = "تعريب R.D.AV";
    public static final String HUB_TITLE = "R.D.AV Multi-Game Hub";
    public static final String HUB_SUBTITLE = "منصة تعريب الألعاب العربية الفصحى • متعدد الألعاب";
    public static final String APP_TITLE = "JoJo's Bizarre Adventure: Golden Hymn";
    public static final String APP_SUBTITLE = "الرياح الذهبية • تعريب عربي فصيح كامل مع حارس الأمان";
    public static final String BADGE_LABEL = "تعريب R.D.AV • v" + APP_VERSION;
    public static final String INSTAGRAM_HANDLE = "R.D.AV";
    public static final String INSTAGRAM_URL = SecretVault.instagramUrl();
    public static final String FOOTER_TEXT = "تعريب R.D.AV • Multi-Game Localization Hub v" + APP_VERSION;

    // إحصائيات المشروع الفعلية المعروضة في شريط الواجهة
    public static final String STAT_LINES = "15,967 نصاً معرّباً بالفصحى";
    public static final String STAT_SCOPE = "القوائم والمهارات والشخصيات والقصة";
    public static final String STAT_COMPAT = "متوافق 100% مع الإصدار 1.0.21";
    public static final String STAT_SAFETY = "حارس أمان واستعادة أصل بضغطة زر";

    // =========================================================================
    // 2) إعدادات اللعبة المستهدفة (محمية داخل SecretVault)
    // =========================================================================
    public static final String GAME_PACKAGE = SecretVault.gamePackage();

    public static final String[] GAME_PACKAGES = {
            GAME_PACKAGE
    };

    public static final long TARGET_GAME_VERSION_CODE = 21L;
    public static final String TARGET_GAME_VERSION_NAME = "1.0.21";

    public static final String TARGET_LANG_REL_PATH = SecretVault.langRelPath();
    public static final long TARGET_LANG_ORIG_SIZE = 5429509L;
    public static final String TARGET_LANG_ORIG_MD5 = SecretVault.origLangMd5();

    public static final long TARGET_LANG_PATCHED_SIZE = 5636573L;
    public static final String TARGET_LANG_PATCHED_MD5 = SecretVault.patchedLangMd5();

    public static final String TARGET_INDEX_REL_PATH = SecretVault.indexRelPath();
    public static final long TARGET_INDEX_ORIG_SIZE = 223559L;

    public static final String GAME_TARGET_SUBPATH = "";

    public static final String DEFAULT_FORCE_SUBDIR = "force";
    public static final String DEFAULT_CONFIG_SUBDIR = "config";
    public static final String DEFAULT_AB_SUBDIR = "ab";

    public static final String[] FIRST_LAUNCH_ANY_OF_PATHS = {
            "force",
            "ab",
            "config"
    };

    public static final String[] KNOWN_GAME_SUBDIRS = {
            "force",
            "ab",
            "config",
            "il2cpp",
            "Unity",
            "UnityCache"
    };

    public static final String BACKUP_DIR_NAME = ".jojo_gh_arabic_backup";
    public static final String BACKUP_MANIFEST_FILE = "manifest.txt";

    public static final String SAFETY_GUARD_ALERT_MESSAGE =
            "تم اكتشاف تحديث جديد للعبة! تم إيقاف التعريب واستعادة الملفات الأصلية الآمنة لحماية حسابك حتى يتم تحديث حزمة التعريب";

    // =========================================================================
    // 3) إعدادات التحديث والتحميل أونلاين (محمية داخل SecretVault)
    // =========================================================================
    public static final String MANIFEST_URL = SecretVault.manifestUrl();
    public static final String FALLBACK_ZIP_URL = "";
    public static final String COMMUNITY_URL = SecretVault.communityUrl();

    public static final int HTTP_CONNECT_TIMEOUT_MS = 15000;
    public static final int HTTP_READ_TIMEOUT_MS = 45000;
    public static final int HTTP_MAX_REDIRECTS = 6;

    // =========================================================================
    // 4) إعدادات جسر Shizuku المحلي
    // =========================================================================
    public static final int BRIDGE_PORT = 38922;
    public static final String BRIDGE_REV = "r12";
    public static final String SHIZUKU_PACKAGE = SecretVault.shizukuPackage();

    // =========================================================================
    // 5) ألوان الواجهة الليلية الفخمة (ثيم Giorno Giovanna)
    // =========================================================================
    public static final int COL_BG = Color.parseColor("#080512");
    public static final int COL_BG_BOTTOM = Color.parseColor("#130A24");
    public static final int COL_CARD = Color.parseColor("#140D26");
    public static final int COL_CARD_INNER = Color.parseColor("#0E081C");
    public static final int COL_BORDER = Color.parseColor("#3B256B");
    public static final int COL_BORDER_GOLD = Color.parseColor("#B47B17");
    public static final int COL_ACCENT = Color.parseColor("#F59E0B");
    public static final int COL_GOLD = Color.parseColor("#FDE047");
    public static final int COL_PURPLE = Color.parseColor("#A855F7");
    public static final int COL_CYAN = Color.parseColor("#D8B4FE");
    public static final int COL_TEXT = Color.parseColor("#F8FAFC");
    public static final int COL_MUTED = Color.parseColor("#A594C7");
    public static final int COL_SUCCESS = Color.parseColor("#22C55E");
    public static final int COL_WARN = Color.parseColor("#F59E0B");
    public static final int COL_DANGER = Color.parseColor("#F43F5E");

    public static boolean isOnlineUrlConfigured() {
        if (MANIFEST_URL != null && !MANIFEST_URL.trim().isEmpty()) {
            return true;
        }
        return FALLBACK_ZIP_URL != null && !FALLBACK_ZIP_URL.trim().isEmpty();
    }

    public static String normalizeSubpath(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().replace('\\', '/');
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
