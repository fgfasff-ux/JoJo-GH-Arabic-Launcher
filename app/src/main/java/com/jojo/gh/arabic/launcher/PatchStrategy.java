package com.jojo.gh.arabic.launcher;

import java.io.File;

/**
 * استراتيجيات الحقن والتعريب المستقلة لكل لعبة داخل منصة R.D.AV Multi-Game Hub.
 * تضمن فصل طريقة الحقن والنسخ الاحتياطي وحارس الأمان لكل لعبة دون أي تداخل.
 */
public enum PatchStrategy {

    /**
     * ألعاب FairyGUI + حزم force المشفّرة (مثل JoJo: Golden Hymn):
     * فك الضغط إلى files/force/ وتحديث بصمة config/force.conf والتحقق عبر حارس الأمان.
     */
    FAIRYGUI_FORCE(
            "FAIRYGUI_FORCE",
            "FairyGUI Force + Safety Guard",
            "حقن حزم force/ وتحديث config/force.conf مع حارس الأمان"
    ),

    /**
     * ألعاب Unity القياسية (مثل Dragon Ball Legends والألعاب المستقبلية):
     * استبدال الملفات المعرّبة داخل files/assets/ أو il2cpp مع أخذ نسخة احتياطية تلقائية.
     */
    ASSET_REPLACE(
            "ASSET_REPLACE",
            "Unity Asset Replace + Backup",
            "نسخ الملفات المعرّبة إلى files/assets/ أو il2cpp مع نسخة احتياطية تلقائية"
    ),

    /**
     * ألعاب Unreal Engine 5 بحزم التراكب المشفّرة (_99_P.pak مثل NTE: Neverness to Everness):
     * حقن حزمة التراكب (pakchunk0-Android_ASTC_99_P.pak) داخل HT/Content/PatchPaks/
     * دون تعديل الملفات الأصلية مع استعادة فورية بنقرة واحدة.
     */
    UE5_PAK_OVERLAY(
            "UE5_PAK_OVERLAY",
            "UE5 Pak Overlay (_99_P.pak) + Backup",
            "حقن حزمة تعريب Unreal Engine 5 (_99_P.pak) في HT/Content/PatchPaks/ مع استعادة فورية"
    );

    public final String code;
    public final String displayName;
    public final String arabicDescription;

    PatchStrategy(String code, String displayName, String arabicDescription) {
        this.code = code;
        this.displayName = displayName;
        this.arabicDescription = arabicDescription;
    }

    /**
     * التحقق المبدئي من بنية مجلد التعريب المستخرج وفقاً لاستراتيجية اللعبة المختارة.
     */
    public void validateStagedPayload(File effectiveStageRoot, GameProfile profile) {
        if (effectiveStageRoot == null || !effectiveStageRoot.isDirectory()) {
            throw new IllegalStateException("مجلد التعريب المؤقت غير صالح.");
        }
        if (this == FAIRYGUI_FORCE) {
            File forceDir = new File(effectiveStageRoot, "force");
            File langFile = new File(effectiveStageRoot, LauncherConfig.TARGET_LANG_REL_PATH);
            if (!forceDir.exists() && !langFile.exists()) {
                // Allow flexible root if t_languageBean.bbytes is inside stage
                File[] listed = effectiveStageRoot.listFiles();
                if (listed == null || listed.length == 0) {
                    throw new IllegalStateException("حزمة التعريب لا تحتوي على ملفات force/ المطلوبة للعبة " + profile.title);
                }
            }
        } else if (this == ASSET_REPLACE || this == UE5_PAK_OVERLAY) {
            File[] listed = effectiveStageRoot.listFiles();
            if (listed == null || listed.length == 0) {
                throw new IllegalStateException("حزمة التعريب (" + this.code + ") فارغة.");
            }
        }
    }

    /**
     * تحديد المسار الفرعي الافتراضي داخل Android/data/<package>/files لكل استراتيجية.
     */
    public String resolveDefaultTargetSubpath(String manifestSubpath) {
        String norm = LauncherConfig.normalizeSubpath(manifestSubpath);
        if (!norm.isEmpty()) {
            return norm;
        }
        if (this == ASSET_REPLACE || this == UE5_PAK_OVERLAY) {
            return "";
        }
        return LauncherConfig.normalizeSubpath(LauncherConfig.GAME_TARGET_SUBPATH);
    }
}
