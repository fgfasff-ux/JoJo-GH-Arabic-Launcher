package com.jojo.gh.arabic.launcher;

import android.graphics.Color;

/**
 * ملف الإعدادات المركزي لمعرّب لعبة JoJo's Bizarre Adventure: Golden Hymn.
 * يتضمن إعدادات حارس الأمان (Version Safety Guard) المرتبطة برقم إصدار اللعبة وبصمة ملف اللغة الأصلي.
 */
public final class LauncherConfig {

    private LauncherConfig() {
    }

    // =========================================================================
    // 1) معلومات التطبيق وهويته
    // =========================================================================
    public static final String APP_VERSION = "1.0.2";
    public static final String APP_TITLE = "معرّب JoJo's Bizarre Adventure: Golden Hymn";
    public static final String APP_SUBTITLE = "مثبّت ذكي مع حارس الأمان والنسخ الاحتياطي (Shizuku & SAF)";
    public static final String BADGE_LABEL = "تعريب JoJo: Golden Hymn";
    public static final String FOOTER_TEXT = "JoJo GH Arabic Launcher • الإصدار " + APP_VERSION;

    // =========================================================================
    // 2) إعدادات اللعبة المستهدفة (مستخرجة رسمياً من XAPK 1.0.21)
    // =========================================================================
    /** معرّف الحزمة الرسمي للعبة JoJo's Bizarre Adventure: Golden Hymn (Golden Spirit) */
    public static final String GAME_PACKAGE = "com.wanda.jojo.gp.global";

    public static final String[] GAME_PACKAGES = {
            "com.wanda.jojo.gp.global"
    };

    /**
     * إعدادات حارس الأمان (Version Safety Guard):
     * يتم فحص إصدار اللعبة المثبت وحجم/بصمة ملفات البيانات الأصلية قبل التثبيت والتشغيل.
     */
    public static final long TARGET_GAME_VERSION_CODE = 21L;
    public static final String TARGET_GAME_VERSION_NAME = "1.0.21";

    /** مسار ملف اللغة الرئيسي داخل /Android/data/com.wanda.jojo.gp.global/files/ */
    public static final String TARGET_LANG_REL_PATH = "force/t_languageBean.bbytes";
    /** الحجم الأصلي وبصمة MD5 الرسمية لملف t_languageBean.bbytes في إصدار 1.0.21 */
    public static final long TARGET_LANG_ORIG_SIZE = 5429509L;
    public static final String TARGET_LANG_ORIG_MD5 = "22C346BBFB12F16B6D7AE6D44EF855A9";

    /** الحجم وبصمة MD5 المعتمدة لحزمة تعريب القوائم (jojo_test_menus_v2.zip) لإصدار 1.0.21 */
    public static final long TARGET_LANG_PATCHED_SIZE = 5469428L;
    public static final String TARGET_LANG_PATCHED_MD5 = "6C7497BC6DB8BACFD3DDD8E66935E77A";

    /** مسار وحجم ملف الفهرس الأساسي config/force.conf في إصدار 1.0.21 */
    public static final String TARGET_INDEX_REL_PATH = "config/force.conf";
    public static final long TARGET_INDEX_ORIG_SIZE = 223559L;

    /** المسار الفرعي الافتراضي داخل files/ (فارغ لأن الحزم توضع في force/ و config/ و ab/) */
    public static final String GAME_TARGET_SUBPATH = "";

    public static final String DEFAULT_FORCE_SUBDIR = "force";
    public static final String DEFAULT_CONFIG_SUBDIR = "config";
    public static final String DEFAULT_AB_SUBDIR = "ab";

    /** مجلدات التأكد من تشغيل اللعبة لمرة واحدة على الأقل */
    public static final String[] FIRST_LAUNCH_ANY_OF_PATHS = {
            "force",
            "ab",
            "config"
    };

    /** أسماء المجلدات الفرعية الحقيقية داخل files/ لتمييزها عن مجلدات الغلاف داخل الـ ZIP */
    public static final String[] KNOWN_GAME_SUBDIRS = {
            "force",
            "ab",
            "config",
            "il2cpp",
            "Unity",
            "UnityCache"
    };

    /** مجلد النسخ الاحتياطي الخاص بلعبة JoJo Golden Hymn */
    public static final String BACKUP_DIR_NAME = ".jojo_gh_arabic_backup";
    public static final String BACKUP_MANIFEST_FILE = "manifest.txt";

    /** رسالة التنبيه الرسمية لحارس الأمان عند اكتشاف تحديث جديد للعبة */
    public static final String SAFETY_GUARD_ALERT_MESSAGE =
            "تم اكتشاف تحديث جديد للعبة! تم إيقاف التعريب واستعادة الملفات الأصلية الآمنة لحماية حسابك حتى يتم تحديث حزمة التعريب";

    // =========================================================================
    // 3) إعدادات التحديث والتحميل أونلاين (GitHub Releases)
    // =========================================================================
    public static final String MANIFEST_URL =
            "https://github.com/fgfasff-ux/JoJo-GH-Arabic-Launcher/releases/latest/download/manifest.json";

    public static final String FALLBACK_ZIP_URL = "";

    public static final String COMMUNITY_URL =
            "https://github.com/fgfasff-ux/JoJo-GH-Arabic-Launcher/releases";

    public static final int HTTP_CONNECT_TIMEOUT_MS = 15000;
    public static final int HTTP_READ_TIMEOUT_MS = 45000;
    public static final int HTTP_MAX_REDIRECTS = 6;

    // =========================================================================
    // 4) إعدادات جسر Shizuku المحلي (منفذ مستقل عن لانشر DBL)
    // =========================================================================
    public static final int BRIDGE_PORT = 38922;
    public static final String BRIDGE_REV = "r3";
    public static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";

    // =========================================================================
    // 5) ألوان الواجهة (ثيم Golden Hymn: بنفسجي ملكي + ذهبي)
    // =========================================================================
    public static final int COL_BG = Color.parseColor("#0D0819");
    public static final int COL_CARD = Color.parseColor("#18102D");
    public static final int COL_CARD_INNER = Color.parseColor("#110B21");
    public static final int COL_BORDER = Color.parseColor("#35245E");
    public static final int COL_ACCENT = Color.parseColor("#F59E0B");     // ذهبي Golden Hymn
    public static final int COL_GOLD = Color.parseColor("#FDE047");       // ذهبي ساطع
    public static final int COL_CYAN = Color.parseColor("#C084FC");       // بنفسجي مضيء
    public static final int COL_TEXT = Color.parseColor("#F8FAFC");
    public static final int COL_MUTED = Color.parseColor("#A799C4");
    public static final int COL_SUCCESS = Color.parseColor("#22C55E");
    public static final int COL_WARN = Color.parseColor("#F59E0B");
    public static final int COL_DANGER = Color.parseColor("#EF4444");

    public static boolean isOnlineUrlConfigured() {
        if (MANIFEST_URL != null && !MANIFEST_URL.trim().isEmpty()
                && !MANIFEST_URL.contains("YOUR_USERNAME")) {
            return true;
        }
        return FALLBACK_ZIP_URL != null && !FALLBACK_ZIP_URL.trim().isEmpty()
                && !FALLBACK_ZIP_URL.contains("YOUR_USERNAME");
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
