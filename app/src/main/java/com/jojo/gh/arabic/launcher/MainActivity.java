package com.jojo.gh.arabic.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/**
 * الواجهة الرئيسية لمشغّل ومثبّت تعريب JoJo's Bizarre Adventure: Golden Hymn.
 * تدعم:
 * - حارس الأمان وفحص التحديثات (Version Safety Guard) لحماية الحساب عند صدور تحديث للعبة
 * - التحديث والتحميل أونلاين (GitHub Releases / manifest.json)
 * - التثبيت من ملف ZIP محلي للاختبار الفوري
 * - الوصول متعدد الطبقات لـ Android/data عبر Shizuku / SAF / DirectFS / Root
 * - النسخ الاحتياطي التلقائي والاستعادة الأصلية بضغطة زر
 */
public class MainActivity extends Activity {

    private static final int REQ_SAF_TREE = 1001;
    private static final int REQ_SHIZUKU_PERMISSION = 1003;
    private static final int REQ_STORAGE_PERMISSION = 1004;
    private static final int REQ_PICK_LOCAL_ZIP = 1005;

    private static final String PREFS_NAME = "jojo_gh_arabic_launcher_prefs";
    private static final String KEY_BRIDGE_TOKEN = "bridge_auth_token";

    private enum AccessMode {
        NONE,
        BRIDGE_ACTIVE,
        DIRECT_FS,
        SAF_GRANTED,
        SHIZUKU_READY,
        SHIZUKU_NEED_PERM,
        SHIZUKU_STOPPED
    }

    private enum PendingAction {
        NONE,
        ONLINE_INSTALL,
        LOCAL_ZIP_INSTALL,
        RESTORE
    }

    private static final class SafetyCheckResult {
        final boolean safe;
        final boolean hasBackup;
        final String reason;

        SafetyCheckResult(boolean safe, boolean hasBackup, String reason) {
            this.safe = safe;
            this.hasBackup = hasBackup;
            this.reason = reason != null ? reason : "";
        }
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private volatile boolean isBusy = false;
    private volatile PendingAction pendingAction = PendingAction.NONE;
    private volatile Uri pendingLocalZipUri = null;
    private volatile boolean pendingAutoSafAfterDowngrade = false;

    private volatile PatchManifest latestRemoteManifest = null;
    private volatile String installedPatchVersion = "";
    private volatile boolean isPatchCurrentlyInstalled = false;
    private volatile boolean safetyAlertShownThisSession = false;
    private String bridgeAuthToken = "";

    // عناصر الواجهة
    private Typeface fontRegular;
    private Typeface fontBold;
    private TextView tvGameBadge;
    private TextView tvSafetyBadge;
    private TextView tvAccessBadge;
    private TextView tvPatchBadge;
    private TextView tvRemoteInfo;
    private TextView tvProgressPercent;
    private TextView tvStatusText;
    private ProgressBar progressBar;
    private Button btnGrantAccess;
    private Button btnCheckUpdate;
    private Button btnInstallOnline;
    private Button btnInstallLocalZip;
    private Button btnDiagScan;
    private Button btnLaunchGame;
    private Button btnRestore;
    private Button btnCommunity;

    private final Shizuku.OnBinderReceivedListener shizukuBinderReceivedListener = () -> {
        mainHandler.post(() -> {
            if (!isBusy) {
                refreshStatusAsync(this::resumePendingActionIfReady);
            }
        });
    };

    private final Shizuku.OnBinderDeadListener shizukuBinderDeadListener = () -> {
        mainHandler.post(() -> {
            if (!isBusy) {
                refreshStatusAsync(null);
            }
        });
    };

    private final Shizuku.OnRequestPermissionResultListener shizukuPermListener = (requestCode, grantResult) -> {
        if (requestCode == REQ_SHIZUKU_PERMISSION && grantResult == PackageManager.PERMISSION_GRANTED) {
            worker.execute(() -> {
                startBridgeViaShizuku();
                mainHandler.post(() -> refreshStatusAsync(this::resumePendingActionIfReady));
            });
        } else {
            mainHandler.post(() -> {
                if (requestCode == REQ_SHIZUKU_PERMISSION) {
                    Toast.makeText(this, "تم رفض إذن Shizuku. يمكنك منحه لاحقاً من الزر بالأسفل.", Toast.LENGTH_SHORT).show();
                }
                refreshStatusAsync(null);
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(LauncherConfig.COL_BG);
        getWindow().setNavigationBarColor(LauncherConfig.COL_BG);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        bridgeAuthToken = loadOrCreateBridgeToken();
        SafHelper.cleanupInvalidTreePermissions(this);
        loadFonts();

        try {
            Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener);
            Shizuku.addBinderDeadListener(shizukuBinderDeadListener);
            Shizuku.addRequestPermissionResultListener(shizukuPermListener);
        } catch (Throwable ignored) {
        }

        setContentView(buildUi());
        ensureLegacyStoragePermissions();
        refreshStatusAsync(null);

        if (LauncherConfig.isOnlineUrlConfigured()) {
            checkOnlineUpdateAsync(false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        SafHelper.cleanupInvalidTreePermissions(this);
        if (!isBusy) {
            refreshStatusAsync(() -> {
                if (pendingAutoSafAfterDowngrade && SafHelper.canAutoJumpToGameFolder(this)) {
                    pendingAutoSafAfterDowngrade = false;
                    openGameFolderAutoDirect();
                } else {
                    resumePendingActionIfReady();
                }
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener);
            Shizuku.removeBinderDeadListener(shizukuBinderDeadListener);
            Shizuku.removeRequestPermissionResultListener(shizukuPermListener);
        } catch (Throwable ignored) {
        }
    }

    private String loadOrCreateBridgeToken() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String existing = prefs.getString(KEY_BRIDGE_TOKEN, null);
        if (existing != null && existing.length() >= 16) {
            return existing;
        }
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(String.format(Locale.US, "%02x", b & 0xFF));
        }
        String token = sb.toString();
        prefs.edit().putString(KEY_BRIDGE_TOKEN, token).apply();
        return token;
    }

    private void loadFonts() {
        try {
            fontRegular = Typeface.createFromAsset(getAssets(), "Cairo.ttf");
            fontBold = Typeface.create(fontRegular, Typeface.BOLD);
        } catch (Throwable t) {
            fontRegular = Typeface.DEFAULT;
            fontBold = Typeface.DEFAULT_BOLD;
        }
    }

    private void ensureLegacyStoragePermissions() {
        try {
            if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 29) {
                if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                        || checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(
                            new String[]{
                                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                            },
                            REQ_STORAGE_PERMISSION
                    );
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshStatusAsync(this::resumePendingActionIfReady);
    }

    // =========================================================================
    // بناء الواجهة البرمجية (RTL) - ثيم JoJo: Golden Hymn
    // =========================================================================

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(LauncherConfig.COL_BG);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(22));
        scroll.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        ));

        // 1) بطاقة الهيدر
        LinearLayout heroCard = new LinearLayout(this);
        heroCard.setOrientation(LinearLayout.VERTICAL);
        heroCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable heroBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor("#2B1654"), LauncherConfig.COL_CARD, Color.parseColor("#1D1038")}
        );
        heroBg.setCornerRadius(dp(18));
        heroBg.setStroke(dp(1), LauncherConfig.COL_ACCENT);
        heroCard.setBackground(heroBg);

        TextView pill = makeText(LauncherConfig.BADGE_LABEL, 11, LauncherConfig.COL_GOLD, true);
        pill.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(Color.parseColor("#2D1F08"));
        pillBg.setCornerRadius(dp(14));
        pillBg.setStroke(dp(1), LauncherConfig.COL_GOLD);
        pill.setBackground(pillBg);
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pillLp.bottomMargin = dp(10);
        heroCard.addView(pill, pillLp);

        heroCard.addView(makeText(LauncherConfig.APP_TITLE, 17, LauncherConfig.COL_TEXT, true));
        TextView subtitle = makeText(LauncherConfig.APP_SUBTITLE, 12, LauncherConfig.COL_MUTED, false);
        subtitle.setPadding(0, dp(4), 0, 0);
        heroCard.addView(subtitle);
        root.addView(heroCard, makeCardLp());

        // 2) بطاقة الفحص الحي وحارس الأمان
        LinearLayout statusCard = makeCard();
        TextView statusTitle = makeText("لوحة الفحص وحارس الأمان", 14, LauncherConfig.COL_CYAN, true);
        statusTitle.setPadding(0, 0, 0, dp(8));
        statusCard.addView(statusTitle);

        tvGameBadge = addStatusRow(statusCard, "JoJo: Golden Hymn", "جارٍ الفحص...");
        tvSafetyBadge = addStatusRow(statusCard, "حارس الأمان (توافق الإصدار)", "جارٍ التحقق...");
        tvAccessBadge = addStatusRow(statusCard, "صلاحية الوصول لملفات اللعبة", "جارٍ الفحص...");
        tvPatchBadge = addStatusRow(statusCard, "حالة التعريب المثبّت", "جارٍ الفحص...");

        btnGrantAccess = makeButton("تفعيل الربط التلقائي لمجلد اللعبة", LauncherConfig.COL_CARD_INNER, LauncherConfig.COL_CYAN, true);
        btnGrantAccess.setVisibility(View.GONE);
        LinearLayout.LayoutParams grantLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        grantLp.topMargin = dp(8);
        btnGrantAccess.setOnClickListener(v -> requestBestAccessInteractive());
        statusCard.addView(btnGrantAccess, grantLp);
        root.addView(statusCard, makeCardLp());

        // 3) بطاقة التحديثات أونلاين (GitHub Releases)
        LinearLayout updateCard = makeCard();
        LinearLayout updateHeader = new LinearLayout(this);
        updateHeader.setOrientation(LinearLayout.HORIZONTAL);
        updateHeader.setGravity(Gravity.CENTER_VERTICAL);
        updateHeader.addView(makeText("حزمة التعريب أونلاين (GitHub)", 13, LauncherConfig.COL_GOLD, true),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        btnCheckUpdate = makeButton("فحص التحديث", LauncherConfig.COL_CARD_INNER, LauncherConfig.COL_GOLD, true);
        btnCheckUpdate.setTextSize(11f);
        btnCheckUpdate.setPadding(dp(12), dp(4), dp(12), dp(4));
        btnCheckUpdate.setOnClickListener(v -> checkOnlineUpdateAsync(true));
        updateHeader.addView(btnCheckUpdate, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)));
        updateCard.addView(updateHeader);

        String defaultRemoteMsg = LauncherConfig.isOnlineUrlConfigured()
                ? "اضغط «فحص التحديث» للتحقق من أحدث حزمة تعريب متوافقة مع إصدار اللعبة."
                : "يمكنك استخدام «تثبيت من ملف ZIP محلي» للاختبار الفوري على هاتفك.";
        tvRemoteInfo = makeText(defaultRemoteMsg, 12, LauncherConfig.COL_MUTED, false);
        tvRemoteInfo.setPadding(0, dp(8), 0, 0);
        tvRemoteInfo.setLineSpacing(dp(3), 1f);
        updateCard.addView(tvRemoteInfo);
        root.addView(updateCard, makeCardLp());

        // 4) بطاقة التقدم والحالة التنفيذية
        LinearLayout progressCard = makeCard();
        LinearLayout progRow = new LinearLayout(this);
        progRow.setOrientation(LinearLayout.HORIZONTAL);
        progRow.setGravity(Gravity.CENTER_VERTICAL);
        progRow.addView(makeText("حالة التنفيذ", 13, LauncherConfig.COL_CYAN, true),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tvProgressPercent = makeText("جاهز", 12, LauncherConfig.COL_GOLD, true);
        progRow.addView(tvProgressPercent);
        progressCard.addView(progRow);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setProgressDrawable(makeProgressDrawable());
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10));
        pbLp.topMargin = dp(8);
        pbLp.bottomMargin = dp(8);
        progressCard.addView(progressBar, pbLp);

        tvStatusText = makeText("حارس الأمان مفعّل: يتم فحص إصدار اللعبة وبصمة ملفاتها قبل كل تثبيت أو تشغيل.", 12, LauncherConfig.COL_TEXT, false);
        progressCard.addView(tvStatusText);
        root.addView(progressCard, makeCardLp());

        // 5) الأزرار الرئيسية
        btnInstallOnline = makeButton("تثبيت / تحديث التعريب أونلاين", LauncherConfig.COL_ACCENT, Color.parseColor("#120A1E"), true);
        btnInstallOnline.setTextSize(15f);
        btnInstallOnline.setOnClickListener(v -> startOnlineInstallFlow());
        LinearLayout.LayoutParams primaryLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        primaryLp.bottomMargin = dp(8);
        root.addView(btnInstallOnline, primaryLp);

        btnInstallLocalZip = makeButton("تثبيت من ملف ZIP محلي (للاختبار)", LauncherConfig.COL_CARD, LauncherConfig.COL_GOLD, true);
        btnInstallLocalZip.setTextSize(13.5f);
        btnInstallLocalZip.setOnClickListener(v -> openLocalZipPicker());
        LinearLayout.LayoutParams localLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        localLp.bottomMargin = dp(8);
        root.addView(btnInstallLocalZip, localLp);

        btnDiagScan = makeButton("فحص مسارات وملفات اللعبة (Shizuku)", LauncherConfig.COL_CARD_INNER, LauncherConfig.COL_CYAN, true);
        btnDiagScan.setTextSize(13f);
        btnDiagScan.setOnClickListener(v -> runDiagnosticScanFlow());
        LinearLayout.LayoutParams diagLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        diagLp.bottomMargin = dp(8);
        root.addView(btnDiagScan, diagLp);

        btnLaunchGame = makeButton("تشغيل JoJo: Golden Hymn", Color.parseColor("#2A184D"), LauncherConfig.COL_TEXT, true);
        btnLaunchGame.setTextSize(14f);
        btnLaunchGame.setOnClickListener(v -> launchGameWithSafetyCheck());
        LinearLayout.LayoutParams launchLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        launchLp.bottomMargin = dp(8);
        root.addView(btnLaunchGame, launchLp);

        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);

        btnCommunity = makeButton("صفحة التحديثات (GitHub)", LauncherConfig.COL_CARD_INNER, LauncherConfig.COL_CYAN, true);
        btnCommunity.setOnClickListener(v -> openUrl(LauncherConfig.COMMUNITY_URL));
        LinearLayout.LayoutParams commLp = new LinearLayout.LayoutParams(0, dp(44), 1.05f);
        commLp.leftMargin = dp(6);
        bottomRow.addView(btnCommunity, commLp);

        btnRestore = makeButton("استعادة الأصل", Color.parseColor("#351323"), LauncherConfig.COL_DANGER, true);
        btnRestore.setOnClickListener(v -> confirmAndRestore());
        LinearLayout.LayoutParams restLp = new LinearLayout.LayoutParams(0, dp(44), 0.95f);
        restLp.rightMargin = dp(6);
        bottomRow.addView(btnRestore, restLp);

        LinearLayout.LayoutParams bottomRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bottomRowLp.bottomMargin = dp(12);
        root.addView(bottomRow, bottomRowLp);

        TextView footer = makeText(LauncherConfig.FOOTER_TEXT, 11, LauncherConfig.COL_MUTED, false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(4), 0, dp(6));
        root.addView(footer);

        return scroll;
    }

    private TextView addStatusRow(LinearLayout parent, String label, String initialBadge) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        TextView tvLabel = makeText(label, 12, LauncherConfig.COL_TEXT, false);
        row.addView(tvLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvBadge = makeText(initialBadge, 11, LauncherConfig.COL_CYAN, true);
        tvBadge.setPadding(dp(10), dp(4), dp(10), dp(4));
        setBadgeStyle(tvBadge, Color.parseColor("#241740"), LauncherConfig.COL_CYAN);
        row.addView(tvBadge);

        parent.addView(row);
        return tvBadge;
    }

    private void setBadgeStyle(TextView badge, int bgColor, int textColor) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(8));
        badge.setBackground(bg);
        badge.setTextColor(textColor);
    }

    private LinearLayout makeCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(LauncherConfig.COL_CARD);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), LauncherConfig.COL_BORDER);
        card.setBackground(bg);
        return card;
    }

    private LinearLayout.LayoutParams makeCardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        return lp;
    }

    private TextView makeText(String text, int sizeSp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sizeSp);
        tv.setTextColor(color);
        tv.setTypeface(bold ? fontBold : fontRegular);
        return tv;
    }

    private Button makeButton(String text, int bgColor, int textColor, boolean bold) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(13f);
        btn.setTextColor(textColor);
        btn.setTypeface(bold ? fontBold : fontRegular);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(14));
        if (bgColor == LauncherConfig.COL_CARD_INNER || bgColor == LauncherConfig.COL_CARD) {
            bg.setStroke(dp(1), LauncherConfig.COL_BORDER);
        } else if (bgColor == Color.parseColor("#2A184D")) {
            bg.setStroke(dp(1), LauncherConfig.COL_CYAN);
        }
        btn.setBackground(bg);
        return btn;
    }

    private Drawable makeProgressDrawable() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(LauncherConfig.COL_CARD_INNER);
        bg.setCornerRadius(dp(6));
        GradientDrawable fill = new GradientDrawable(
                GradientDrawable.Orientation.RIGHT_LEFT,
                new int[]{LauncherConfig.COL_GOLD, LauncherConfig.COL_ACCENT}
        );
        fill.setCornerRadius(dp(6));
        LayerDrawable ld = new LayerDrawable(new Drawable[]{
                bg,
                new ClipDrawable(fill, Gravity.END, ClipDrawable.HORIZONTAL)
        });
        ld.setId(0, android.R.id.background);
        ld.setId(1, android.R.id.progress);
        return ld;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // =========================================================================
    // حارس الأمان وفحص التحديثات (Version Safety Guard)
    // =========================================================================

    private long getInstalledGameVersionCode() {
        PackageManager pm = getPackageManager();
        for (String pkg : LauncherConfig.GAME_PACKAGES) {
            try {
                PackageInfo info = pm.getPackageInfo(pkg, 0);
                if (info != null) {
                    return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
                }
            } catch (Throwable ignored) {
            }
        }
        return -1L;
    }

    private SafetyCheckResult evaluateSafetyGuardSync(
            AccessMode mode,
            PatchManifest manifestOpt,
            boolean autoRestoreIfMismatch
    ) {
        long targetVc = manifestOpt != null
                ? manifestOpt.targetGameVersionCode
                : LauncherConfig.TARGET_GAME_VERSION_CODE;
        long expForceConf = manifestOpt != null
                ? manifestOpt.expectedForceConfSize
                : LauncherConfig.TARGET_INDEX_ORIG_SIZE;
        long expLangSize = manifestOpt != null
                ? manifestOpt.expectedLangOrigSize
                : LauncherConfig.TARGET_LANG_ORIG_SIZE;
        String expLangMd5 = manifestOpt != null
                ? manifestOpt.expectedLangOrigMd5
                : LauncherConfig.TARGET_LANG_ORIG_MD5;

        long installedVc = getInstalledGameVersionCode();
        String installedVerName = getInstalledGameVersion();

        boolean dataHasBackup = false;
        String mismatchReason = null;

        // 1) فحص رقم إصدار الـ APK الرسمي (versionCode)
        if (installedVc > 0 && targetVc > 0 && installedVc != targetVc) {
            mismatchReason = "إصدار اللعبة المثبّت (v" + (installedVerName != null ? installedVerName : "?")
                    + " / code=" + installedVc + ") يختلف عن الإصدار المدعوم للتعريب (v"
                    + (manifestOpt != null ? manifestOpt.gameVersion : LauncherConfig.TARGET_GAME_VERSION_NAME)
                    + " / code=" + targetVc + ").";
        }

        // 2) فحص حجم وبصمة ملف اللغة والفهرس في مجلد بيانات اللعبة (كشف التحديثات الداخلية Hot-Updates)
        if (isReadyAccessMode(mode)) {
            String resp = null;
            if (mode == AccessMode.BRIDGE_ACTIVE) {
                resp = sendBridgeCommand(
                        "CHECK_SAFETY\t" + getHintDir().getAbsolutePath()
                                + "\t" + expForceConf
                                + "\t" + expLangSize
                                + "\t" + expLangMd5,
                        15000
                );
            } else if (mode == AccessMode.DIRECT_FS) {
                resp = ShellBridgeServer.checkDataSafety(getHintDir(), expForceConf, expLangSize, expLangMd5);
            } else if (mode == AccessMode.SAF_GRANTED) {
                Uri tree = SafHelper.findGrantedTreeUri(this);
                if (tree != null) {
                    resp = SafHelper.checkDataSafety(this, tree, expForceConf, expLangSize, expLangMd5);
                }
            }

            if (resp != null && resp.startsWith("MISMATCH|")) {
                String[] parts = resp.split("\\|", -1);
                if (parts.length >= 3) {
                    dataHasBackup = Boolean.parseBoolean(parts[2]);
                }
                String detail = parts.length >= 4 ? parts[3] : parts[1];
                if (mismatchReason == null) {
                    mismatchReason = "تم رصد تغيّر في ملفات بيانات اللعبة الداخلية: " + detail;
                }
            } else if (resp != null && resp.startsWith("OK|")) {
                String[] parts = resp.split("\\|", -1);
                if (parts.length >= 3) {
                    dataHasBackup = Boolean.parseBoolean(parts[2]);
                }
            }
        }

        if (mismatchReason != null) {
            // إذا كان هناك نسخة احتياطية/تعريب مثبّت، نستعيد الملفات الأصلية الآمنة تلقائياً فوراً
            if (autoRestoreIfMismatch && isReadyAccessMode(mode) && (dataHasBackup || isPatchCurrentlyInstalled)) {
                executeRestoreSync(mode, null);
                isPatchCurrentlyInstalled = false;
                installedPatchVersion = "";
            }
            return new SafetyCheckResult(false, dataHasBackup, mismatchReason);
        }

        return new SafetyCheckResult(true, dataHasBackup, "");
    }

    private void showSafetyGuardAlertDialog(String technicalReason) {
        StringBuilder body = new StringBuilder();
        body.append(LauncherConfig.SAFETY_GUARD_ALERT_MESSAGE);
        if (technicalReason != null && !technicalReason.trim().isEmpty()) {
            body.append("\n\nالتفاصيل الفنية:\n• ").append(technicalReason.trim());
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        b.setTitle("حارس الأمان — تم إيقاف التعريب");
        b.setMessage(body.toString());
        b.setCancelable(false);
        b.setPositiveButton("فحص تحديثات التعريب", (d, w) -> checkOnlineUpdateAsync(true));
        b.setNegativeButton("حسناً", null);
        b.show();
    }

    private String executeRestoreSync(AccessMode mode, ShellBridgeServer.ProgressListener listener) {
        if (mode == AccessMode.BRIDGE_ACTIVE) {
            return sendBridgeCommandWithProgress("APPLY_RESTORE\t" + getHintDir().getAbsolutePath(), 120000, listener);
        } else if (mode == AccessMode.DIRECT_FS) {
            return ShellBridgeServer.applyRestore(getHintDir(), listener);
        } else {
            Uri tree = SafHelper.findGrantedTreeUri(this);
            if (tree == null) {
                return "ERR|NO_SAF_TREE";
            }
            return SafHelper.applyRestore(this, tree, listener);
        }
    }

    // =========================================================================
    // كشف وإدارة صلاحيات الوصول (Shizuku / SAF / DirectFS / Root)
    // =========================================================================

    private boolean isPackageInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private String getInstalledGameVersion() {
        PackageManager pm = getPackageManager();
        for (String pkg : LauncherConfig.GAME_PACKAGES) {
            try {
                PackageInfo info = pm.getPackageInfo(pkg, 0);
                if (info != null && info.versionName != null) {
                    return info.versionName;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private boolean isAnyGameInstalled() {
        for (String pkg : LauncherConfig.GAME_PACKAGES) {
            if (isPackageInstalled(pkg)) {
                return true;
            }
        }
        return false;
    }

    private File getHintDir() {
        File ext = getExternalFilesDir(null);
        return ext != null ? ext : getFilesDir();
    }

    private boolean isReadyAccessMode(AccessMode mode) {
        return mode == AccessMode.BRIDGE_ACTIVE
                || mode == AccessMode.DIRECT_FS
                || mode == AccessMode.SAF_GRANTED;
    }

    private AccessMode detectAccessMode() {
        String pong = sendBridgeCommand("PING", 600);
        if (pong != null && pong.startsWith("OK|PONG")) {
            if (pong.contains("|ver=" + LauncherConfig.APP_VERSION)
                    && pong.contains("|rev=" + LauncherConfig.BRIDGE_REV)) {
                return AccessMode.BRIDGE_ACTIVE;
            }
            sendBridgeCommand("STOP", 400);
            try {
                Thread.sleep(150L);
            } catch (Throwable ignored) {
            }
        }

        if (ShellBridgeServer.canDirectReadWriteGameDir(getHintDir())) {
            return AccessMode.DIRECT_FS;
        }
        if (SafHelper.findGrantedTreeUri(this) != null) {
            return AccessMode.SAF_GRANTED;
        }

        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    if (startBridgeViaShizuku()) {
                        return AccessMode.BRIDGE_ACTIVE;
                    }
                    return AccessMode.SHIZUKU_READY;
                }
                return AccessMode.SHIZUKU_NEED_PERM;
            }
        } catch (Throwable ignored) {
        }

        if (startBridgeViaRoot()) {
            return AccessMode.BRIDGE_ACTIVE;
        }

        if (isPackageInstalled(LauncherConfig.SHIZUKU_PACKAGE)) {
            return AccessMode.SHIZUKU_STOPPED;
        }
        return AccessMode.NONE;
    }

    private boolean startBridgeViaShizuku() {
        try {
            String cmd = "CLASSPATH='" + getApplicationInfo().sourceDir
                    + "' nohup app_process /system/bin " + ShellBridgeServer.class.getName()
                    + " '" + bridgeAuthToken + "' >/dev/null 2>&1 &";
            Method newProcess = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            newProcess.setAccessible(true);
            Process p = (Process) newProcess.invoke(null, new String[]{"sh", "-c", cmd}, null, null);
            for (int i = 0; i < 20; i++) {
                Thread.sleep(150L);
                String pong = sendBridgeCommand("PING", 500);
                if (pong != null && pong.startsWith("OK|PONG")) {
                    return true;
                }
            }
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean startBridgeViaRoot() {
        try {
            String cmd = "CLASSPATH='" + getApplicationInfo().sourceDir
                    + "' app_process /system/bin " + ShellBridgeServer.class.getName()
                    + " '" + bridgeAuthToken + "' >/dev/null 2>&1 &";
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            for (int i = 0; i < 8; i++) {
                Thread.sleep(120L);
                String pong = sendBridgeCommand("PING", 400);
                if (pong != null && pong.startsWith("OK|PONG")) {
                    return true;
                }
            }
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private String sendBridgeCommand(String command, int timeoutMs) {
        return sendBridgeCommandWithProgress(command, timeoutMs, null);
    }

    private String sendBridgeCommandWithProgress(
            String command,
            int timeoutMs,
            ShellBridgeServer.ProgressListener listener
    ) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", LauncherConfig.BRIDGE_PORT), Math.min(timeoutMs, 1500));
            socket.setSoTimeout(timeoutMs);

            try (PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                out.println(bridgeAuthToken + "\t" + command);
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("PROG|")) {
                        if (listener != null) {
                            int first = line.indexOf('|');
                            int second = line.indexOf('|', first + 1);
                            if (second > first) {
                                try {
                                    int pct = Integer.parseInt(line.substring(first + 1, second));
                                    String msg = line.substring(second + 1);
                                    listener.onProgress(pct, msg);
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                        continue;
                    }
                    return line;
                }
                return null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    // =========================================================================
    // تحديث الحالة والفحص الحي + تشغيل حارس الأمان عند البدء
    // =========================================================================

    private void refreshStatusAsync(Runnable afterUiUpdate) {
        worker.execute(() -> {
            boolean gameInstalled = isAnyGameInstalled();
            String gameVer = getInstalledGameVersion();
            long gameVc = getInstalledGameVersionCode();
            AccessMode mode = detectAccessMode();

            String statusResp = null;
            if (mode == AccessMode.BRIDGE_ACTIVE) {
                statusResp = sendBridgeCommand("STATUS\t" + getHintDir().getAbsolutePath(), 3000);
            } else if (mode == AccessMode.DIRECT_FS) {
                statusResp = ShellBridgeServer.getStatus(getHintDir());
            } else if (mode == AccessMode.SAF_GRANTED) {
                Uri tree = SafHelper.findGrantedTreeUri(this);
                if (tree != null) {
                    statusResp = SafHelper.getStatus(this, tree);
                }
            }

            boolean gameReady = false;
            boolean patchInstalled = false;
            String patchVer = "";
            if (statusResp != null && statusResp.startsWith("OK|")) {
                String[] parts = statusResp.split("\\|", -1);
                if (parts.length >= 4) {
                    gameReady = Boolean.parseBoolean(parts[1]);
                    patchInstalled = Boolean.parseBoolean(parts[2]);
                    patchVer = parts[3];
                }
            }

            isPatchCurrentlyInstalled = patchInstalled;
            installedPatchVersion = patchVer;

            // تشغيل حارس الأمان عند التشغيل: إذا تم اكتشاف تحديث جديد مع وجود تعريب مثبّت -> استعادة تلقائية + تنبيه
            SafetyCheckResult safety = gameInstalled
                    ? evaluateSafetyGuardSync(mode, latestRemoteManifest, true)
                    : new SafetyCheckResult(true, false, "");

            if (!safety.safe && (patchInstalled || safety.hasBackup)) {
                patchInstalled = false;
                patchVer = "";
            }

            final boolean fGameReady = gameReady;
            final boolean fPatchInstalled = patchInstalled;
            final String fPatchVer = patchVer;
            final SafetyCheckResult fSafety = safety;

            mainHandler.post(() -> {
                if (gameInstalled) {
                    String text = (gameVer != null && !gameVer.isEmpty())
                            ? "مثبّتة (v" + gameVer + " #" + gameVc + ") ✓"
                            : "مثبّتة وجاهزة ✓";
                    tvGameBadge.setText(text);
                    setBadgeStyle(tvGameBadge, Color.parseColor("#143829"), LauncherConfig.COL_SUCCESS);
                } else {
                    tvGameBadge.setText("غير مثبّتة");
                    setBadgeStyle(tvGameBadge, Color.parseColor("#3A1822"), LauncherConfig.COL_DANGER);
                }

                if (!gameInstalled) {
                    tvSafetyBadge.setText("بانتظار تثبيت اللعبة");
                    setBadgeStyle(tvSafetyBadge, Color.parseColor("#241740"), LauncherConfig.COL_MUTED);
                } else if (fSafety.safe) {
                    tvSafetyBadge.setText("متوافق وآمن ✓");
                    setBadgeStyle(tvSafetyBadge, Color.parseColor("#143829"), LauncherConfig.COL_SUCCESS);
                } else {
                    tvSafetyBadge.setText("تم اكتشاف تحديث للعبة!");
                    setBadgeStyle(tvSafetyBadge, Color.parseColor("#3A1822"), LauncherConfig.COL_DANGER);
                    tvStatusText.setText(LauncherConfig.SAFETY_GUARD_ALERT_MESSAGE);
                    if (!safetyAlertShownThisSession) {
                        safetyAlertShownThisSession = true;
                        showSafetyGuardAlertDialog(fSafety.reason);
                    }
                }

                boolean canAutoSaf = SafHelper.canAutoJumpToGameFolder(this);
                switch (mode) {
                    case BRIDGE_ACTIVE:
                        tvAccessBadge.setText("متصل عبر Shizuku/Bridge ✓");
                        setBadgeStyle(tvAccessBadge, Color.parseColor("#143829"), LauncherConfig.COL_SUCCESS);
                        btnGrantAccess.setVisibility(View.GONE);
                        break;
                    case DIRECT_FS:
                        tvAccessBadge.setText("وصول مباشر متاح ✓");
                        setBadgeStyle(tvAccessBadge, Color.parseColor("#143829"), LauncherConfig.COL_SUCCESS);
                        btnGrantAccess.setVisibility(View.GONE);
                        break;
                    case SAF_GRANTED:
                        tvAccessBadge.setText("مربوط عبر SAF ✓");
                        setBadgeStyle(tvAccessBadge, Color.parseColor("#143829"), LauncherConfig.COL_SUCCESS);
                        btnGrantAccess.setVisibility(View.GONE);
                        break;
                    case SHIZUKU_READY:
                    case SHIZUKU_NEED_PERM:
                        tvAccessBadge.setText("جاهز عبر Shizuku بضغطة واحدة");
                        setBadgeStyle(tvAccessBadge, Color.parseColor("#3B2B12"), LauncherConfig.COL_GOLD);
                        btnGrantAccess.setText("منح إذن Shizuku الآن");
                        btnGrantAccess.setVisibility(View.VISIBLE);
                        break;
                    case SHIZUKU_STOPPED:
                        if (canAutoSaf) {
                            tvAccessBadge.setText("متاح عبر SAF أو Shizuku");
                            setBadgeStyle(tvAccessBadge, Color.parseColor("#241740"), LauncherConfig.COL_CYAN);
                            btnGrantAccess.setText("تفعيل الربط التلقائي");
                            btnGrantAccess.setVisibility(View.VISIBLE);
                        } else {
                            tvAccessBadge.setText("شغّل Shizuku للربط التلقائي");
                            setBadgeStyle(tvAccessBadge, Color.parseColor("#3B2B12"), LauncherConfig.COL_WARN);
                            btnGrantAccess.setText("فتح Shizuku للتشغيل");
                            btnGrantAccess.setVisibility(View.VISIBLE);
                        }
                        break;
                    default:
                        if (canAutoSaf) {
                            tvAccessBadge.setText("ربط مباشر عبر SAF");
                            setBadgeStyle(tvAccessBadge, Color.parseColor("#241740"), LauncherConfig.COL_CYAN);
                            btnGrantAccess.setText("تحديد مجلد اللعبة تلقائياً");
                            btnGrantAccess.setVisibility(View.VISIBLE);
                        } else {
                            tvAccessBadge.setText("يتطلب إعداد Shizuku");
                            setBadgeStyle(tvAccessBadge, Color.parseColor("#3B2B12"), LauncherConfig.COL_WARN);
                            btnGrantAccess.setText("إعداد الوصول عبر Shizuku");
                            btnGrantAccess.setVisibility(View.VISIBLE);
                        }
                        break;
                }

                if (fPatchInstalled) {
                    String verLabel = fPatchVer.isEmpty() ? "مثبّت ومفعّل ✓" : ("مثبّت (v" + fPatchVer + ") ✓");
                    tvPatchBadge.setText(verLabel);
                    setBadgeStyle(tvPatchBadge, Color.parseColor("#143829"), LauncherConfig.COL_SUCCESS);
                    btnInstallOnline.setText("تحديث / إعادة تثبيت التعريب أونلاين");
                } else if (isReadyAccessMode(mode) && gameInstalled && !fGameReady) {
                    tvPatchBadge.setText("شغّل اللعبة مرة أولاً");
                    setBadgeStyle(tvPatchBadge, Color.parseColor("#3B2B12"), LauncherConfig.COL_WARN);
                    btnInstallOnline.setText("تثبيت التعريب أونلاين الآن");
                } else {
                    tvPatchBadge.setText("غير مثبّت حالياً");
                    setBadgeStyle(tvPatchBadge, Color.parseColor("#241740"), LauncherConfig.COL_CYAN);
                    btnInstallOnline.setText("تثبيت التعريب أونلاين الآن");
                }

                updateRemoteCardUi();
                if (afterUiUpdate != null) {
                    afterUiUpdate.run();
                }
            });
        });
    }

    private void resumePendingActionIfReady() {
        if (pendingAction == PendingAction.NONE) {
            return;
        }
        worker.execute(() -> {
            AccessMode mode = detectAccessMode();
            if (!isReadyAccessMode(mode) && mode == AccessMode.SHIZUKU_READY) {
                if (startBridgeViaShizuku()) {
                    mode = AccessMode.BRIDGE_ACTIVE;
                }
            }
            if (!isReadyAccessMode(mode)) {
                return;
            }
            PendingAction action = pendingAction;
            Uri localUri = pendingLocalZipUri;
            pendingAction = PendingAction.NONE;
            pendingLocalZipUri = null;

            mainHandler.post(() -> {
                if (action == PendingAction.ONLINE_INSTALL) {
                    startOnlineInstallFlow();
                } else if (action == PendingAction.LOCAL_ZIP_INSTALL && localUri != null) {
                    startLocalZipInstallFlow(localUri);
                } else if (action == PendingAction.RESTORE) {
                    runRestoreFlow();
                }
            });
        });
    }

    // =========================================================================
    // حوارات وإعداد صلاحيات الوصول
    // =========================================================================

    private void requestBestAccessInteractive() {
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.isPreV11()) {
                    Toast.makeText(this, "إصدار Shizuku قديم جداً، يرجى تحديث تطبيق Shizuku.", Toast.LENGTH_LONG).show();
                    return;
                }
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    if (Shizuku.shouldShowRequestPermissionRationale()) {
                        Toast.makeText(this, "يرجى السماح للتطبيق باستخدام Shizuku للوصول إلى ملفات اللعبة.", Toast.LENGTH_LONG).show();
                    }
                    Shizuku.requestPermission(REQ_SHIZUKU_PERMISSION);
                    return;
                }
                worker.execute(() -> {
                    if (startBridgeViaShizuku()) {
                        mainHandler.post(this::resumePendingActionIfReady);
                    }
                    refreshStatusAsync(null);
                });
                return;
            }
        } catch (Throwable ignored) {
        }

        if (SafHelper.canAutoJumpToGameFolder(this)) {
            openGameFolderAutoDirect();
        } else {
            showAccessWizardDialog(false);
        }
    }

    private void openGameFolderAutoDirect() {
        try {
            startActivityForResult(SafHelper.buildOpenTreeIntent(this), REQ_SAF_TREE);
            Toast.makeText(this, "اضغط «استخدام هذا المجلد» بالأسفل ثم «السماح».", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            showAccessWizardDialog(false);
        }
    }

    private boolean canDowngradeDocumentsUi() {
        if (Build.VERSION.SDK_INT < 30 || Build.VERSION.SDK_INT >= 34) {
            return false;
        }
        try {
            PackageInfo info = getPackageManager().getPackageInfo("com.google.android.documentsui", 0);
            return info != null && info.applicationInfo != null
                    && (info.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private void openDocumentsUiSettings() {
        pendingAutoSafAfterDowngrade = true;
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:com.google.android.documentsui"));
            startActivity(i);
            Toast.makeText(this, "اضغط القائمة (⋮) ثم «إلغاء تثبيت التحديثات» ثم عد للتطبيق.", Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    private void openShizukuAppOrStore() {
        if (isPackageInstalled(LauncherConfig.SHIZUKU_PACKAGE)) {
            Intent launch = getPackageManager().getLaunchIntentForPackage(LauncherConfig.SHIZUKU_PACKAGE);
            if (launch != null) {
                startActivity(launch);
                return;
            }
        }
        openUrl("https://shizuku.rikka.app/download/");
    }

    private void showAccessWizardDialog(boolean wrongFolderPicked) {
        boolean shizukuInstalled = isPackageInstalled(LauncherConfig.SHIZUKU_PACKAGE);
        boolean canDowngrade = canDowngradeDocumentsUi();

        StringBuilder msg = new StringBuilder();
        if (wrongFolderPicked) {
            msg.append("تنبيه: المجلد الذي تم اختياره ليس مجلد لعبة JoJo: Golden Hymn.\n\n");
        } else {
            msg.append("يقيد نظام أندرويد الحديث الوصول المباشر إلى مجلد Android/data.\n\n");
        }
        msg.append("لتفعيل التثبيت وحارس الأمان والنسخ الاحتياطي التلقائي (مرة واحدة فقط):\n");
        if (!shizukuInstalled) {
            msg.append("١) ثبّت تطبيق Shizuku الرسمي.\n");
            msg.append("٢) شغّله عبر «تصحيح الأخطاء اللاسلكي».\n");
            msg.append("٣) عد لهذا التطبيق وسيعمل التثبيت تلقائياً.\n");
        } else {
            msg.append("١) افتح تطبيق Shizuku واضغط «تشغيل» (Start).\n");
            msg.append("٢) عد لهذا التطبيق وسيكمل التثبيت تلقائياً.\n");
        }
        if (canDowngrade) {
            msg.append("\n• بديل بدون Shizuku (أندرويد 11–13): اضغط «إعدادات الملفات» ثم (⋮) ← «إلغاء تثبيت التحديثات» ثم عد للتطبيق.");
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        builder.setTitle("تفعيل الوصول لمجلد اللعبة");
        builder.setMessage(msg.toString());
        builder.setPositiveButton(shizukuInstalled ? "فتح Shizuku" : "تحميل Shizuku",
                (d, w) -> openShizukuAppOrStore());
        if (canDowngrade) {
            builder.setNeutralButton("إعدادات الملفات", (d, w) -> openDocumentsUiSettings());
        } else if (wrongFolderPicked && SafHelper.canAutoJumpToGameFolder(this)) {
            builder.setNeutralButton("إعادة اختيار المجلد", (d, w) -> openGameFolderAutoDirect());
        }
        builder.setNegativeButton("إغلاق", (d, w) -> {
            pendingAction = PendingAction.NONE;
            pendingLocalZipUri = null;
            pendingAutoSafAfterDowngrade = false;
        });
        builder.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_SAF_TREE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri treeUri = data.getData();
                String docId = null;
                try {
                    docId = DocumentsContract.getTreeDocumentId(treeUri);
                } catch (Throwable ignored) {
                }
                if (SafHelper.isValidGameTreeDocId(docId)) {
                    try {
                        getContentResolver().takePersistableUriPermission(
                                treeUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        );
                        Toast.makeText(this, "تم ربط مجلد JoJo: Golden Hymn بنجاح ✓", Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {
                    }
                } else {
                    refreshStatusAsync(null);
                    showAccessWizardDialog(true);
                    return;
                }
            } else {
                pendingAction = PendingAction.NONE;
                pendingLocalZipUri = null;
            }
            refreshStatusAsync(this::resumePendingActionIfReady);
            return;
        }

        if (requestCode == REQ_PICK_LOCAL_ZIP) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                startLocalZipInstallFlow(data.getData());
            }
        }
    }

    // =========================================================================
    // فحص التحديثات أونلاين (GitHub Releases)
    // =========================================================================

    private void checkOnlineUpdateAsync(boolean userTriggered) {
        if (!LauncherConfig.isOnlineUrlConfigured()) {
            if (userTriggered) {
                Toast.makeText(this, "يرجى تعديل MANIFEST_URL في LauncherConfig.java أولاً.", Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (userTriggered) {
            mainHandler.post(() -> tvRemoteInfo.setText("جارٍ الاتصال بـ GitHub للتحقق من آخر تحديث..."));
        }
        worker.execute(() -> {
            try {
                PatchManifest manifest = PatchDownloader.fetchManifest();
                latestRemoteManifest = manifest;
                mainHandler.post(this::updateRemoteCardUi);
            } catch (Throwable t) {
                if (userTriggered) {
                    String err = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                    mainHandler.post(() -> tvRemoteInfo.setText("تعذر جلب معلومات التحديث: " + err));
                }
            }
        });
    }

    private void updateRemoteCardUi() {
        PatchManifest m = latestRemoteManifest;
        if (m == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("أحدث إصدار متاح: v").append(m.patchVersion);
        if (!m.gameVersion.isEmpty()) {
            sb.append(" • متوافق مع اللعبة: v").append(m.gameVersion)
                    .append(" (#").append(m.targetGameVersionCode).append(")");
        }
        if (!m.releaseDate.isEmpty()) {
            sb.append(" (").append(m.releaseDate).append(")");
        }
        if (isPatchCurrentlyInstalled && !installedPatchVersion.isEmpty()
                && !installedPatchVersion.equals(m.patchVersion)) {
            sb.append("\n★ يتوفر إصدار تعريب أحدث من المثبّت حالياً (v").append(installedPatchVersion).append(")!");
        }
        if (!m.changelog.isEmpty()) {
            sb.append("\n• ").append(m.changelog);
        }
        tvRemoteInfo.setText(sb.toString());
        tvRemoteInfo.setTextColor(LauncherConfig.COL_TEXT);
    }

    // =========================================================================
    // تدفقات التثبيت المحمية بحارس الأمان + الاستعادة
    // =========================================================================

    private void startOnlineInstallFlow() {
        if (isBusy) {
            return;
        }
        if (!isAnyGameInstalled()) {
            Toast.makeText(this, "يرجى تثبيت لعبة JoJo's Bizarre Adventure: Golden Hymn أولاً على الجهاز.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!LauncherConfig.isOnlineUrlConfigured()) {
            Toast.makeText(this, "لم يتم ضبط رابط التحميل في LauncherConfig.java بعد. استخدم «تثبيت من ملف ZIP محلي» للاختبار.", Toast.LENGTH_LONG).show();
            return;
        }

        worker.execute(() -> {
            AccessMode mode = detectAccessMode();
            if (!isReadyAccessMode(mode)) {
                pendingAction = PendingAction.ONLINE_INSTALL;
                mainHandler.post(this::requestBestAccessInteractive);
                return;
            }

            setBusyUi(true, "جارٍ جلب معلومات آخر تحديث وفحص توافق حارس الأمان...", 5);
            File workDir = new File(getHintDir(), "jojo_gh_patch_work");
            File stageDir = new File(workDir, "stage");
            File zipFile = new File(workDir, "patch.zip");

            try {
                ShellBridgeServer.deleteRecursive(workDir);
                stageDir.mkdirs();

                PatchManifest manifest = PatchDownloader.fetchManifest();
                latestRemoteManifest = manifest;
                mainHandler.post(this::updateRemoteCardUi);

                updateProgress(7, "جارٍ التحقق عبر حارس الأمان (Version Safety Guard)...");
                SafetyCheckResult safety = evaluateSafetyGuardSync(mode, manifest, true);
                if (!safety.safe) {
                    showErrorState(LauncherConfig.SAFETY_GUARD_ALERT_MESSAGE);
                    mainHandler.post(() -> showSafetyGuardAlertDialog(safety.reason));
                    return;
                }

                PatchDownloader.downloadToFile(
                        manifest.downloadUrl,
                        zipFile,
                        manifest.sizeBytes,
                        this::updateProgress,
                        10,
                        45
                );

                updateProgress(47, "جارٍ التحقق من سلامة حزمة التعريب (SHA-256)...");
                PatchDownloader.verifySha256IfProvided(zipFile, manifest.sha256);

                PatchDownloader.extractZipFile(zipFile, stageDir, this::updateProgress, 48, 58);
                File effectiveStage = PatchDownloader.resolveEffectiveStageRoot(stageDir);

                executeInstallFromStage(mode, effectiveStage, manifest.patchVersion, manifest.resolveEffectiveSubpath());
            } catch (Throwable t) {
                showErrorState("تعذر إكمال التثبيت أونلاين: " + readableError(t));
            } finally {
                ShellBridgeServer.deleteRecursive(workDir);
                setBusyUi(false, null, -1);
                refreshStatusAsync(null);
            }
        });
    }

    private void openLocalZipPicker() {
        if (isBusy) {
            return;
        }
        if (!isAnyGameInstalled()) {
            Toast.makeText(this, "يرجى تثبيت لعبة JoJo's Bizarre Adventure: Golden Hymn أولاً على الجهاز.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/octet-stream"});
        try {
            startActivityForResult(intent, REQ_PICK_LOCAL_ZIP);
        } catch (Throwable t) {
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.setType("*/*");
            startActivityForResult(fallback, REQ_PICK_LOCAL_ZIP);
        }
    }

    private void startLocalZipInstallFlow(Uri zipUri) {
        if (isBusy) {
            return;
        }
        worker.execute(() -> {
            AccessMode mode = detectAccessMode();
            if (!isReadyAccessMode(mode)) {
                pendingAction = PendingAction.LOCAL_ZIP_INSTALL;
                pendingLocalZipUri = zipUri;
                mainHandler.post(this::requestBestAccessInteractive);
                return;
            }

            setBusyUi(true, "جارٍ فحص توافق اللعبة عبر حارس الأمان...", 10);
            File workDir = new File(getHintDir(), "jojo_gh_patch_work");
            File stageDir = new File(workDir, "stage");

            try {
                SafetyCheckResult safety = evaluateSafetyGuardSync(mode, latestRemoteManifest, true);
                if (!safety.safe) {
                    showErrorState(LauncherConfig.SAFETY_GUARD_ALERT_MESSAGE);
                    mainHandler.post(() -> showSafetyGuardAlertDialog(safety.reason));
                    return;
                }

                updateProgress(15, "جارٍ استخراج حزمة التعريب المحلية...");
                ShellBridgeServer.deleteRecursive(workDir);
                stageDir.mkdirs();

                PatchDownloader.extractZipUri(this, zipUri, stageDir, this::updateProgress, 20, 58);
                File effectiveStage = PatchDownloader.resolveEffectiveStageRoot(stageDir);
                executeInstallFromStage(mode, effectiveStage, "local", LauncherConfig.GAME_TARGET_SUBPATH);
            } catch (Throwable t) {
                showErrorState("فشل تثبيت الحزمة المحلية: " + readableError(t));
            } finally {
                ShellBridgeServer.deleteRecursive(workDir);
                setBusyUi(false, null, -1);
                refreshStatusAsync(null);
            }
        });
    }

    private void executeInstallFromStage(
            AccessMode mode,
            File stageDir,
            String patchVersion,
            String targetSubpath
    ) throws Exception {
        ShellBridgeServer.ProgressListener listener = this::updateProgress;
        String normSubpath = LauncherConfig.normalizeSubpath(targetSubpath);
        String result;

        if (mode == AccessMode.BRIDGE_ACTIVE) {
            result = sendBridgeCommandWithProgress(
                    "APPLY_INSTALL\t" + stageDir.getAbsolutePath() + "\t" + patchVersion + "\t" + normSubpath,
                    180000,
                    listener
            );
        } else if (mode == AccessMode.DIRECT_FS) {
            result = ShellBridgeServer.applyInstall(stageDir, patchVersion, normSubpath, listener);
            if (result == null || (!result.startsWith("OK|") && !result.contains("NEEDS_FIRST_LAUNCH"))) {
                Uri tree = SafHelper.findGrantedTreeUri(this);
                if (tree != null) {
                    result = SafHelper.applyInstall(this, tree, stageDir, patchVersion, normSubpath, listener);
                }
            }
        } else {
            Uri tree = SafHelper.findGrantedTreeUri(this);
            if (tree == null) {
                throw new IllegalStateException("صلاحية SAF غير متوفرة");
            }
            result = SafHelper.applyInstall(this, tree, stageDir, patchVersion, normSubpath, listener);
        }

        if (result != null && result.contains("NEEDS_FIRST_LAUNCH")) {
            mainHandler.post(() -> {
                tvStatusText.setText("يرجى تشغيل JoJo: Golden Hymn مرة واحدة أولاً لإنشاء مجلد البيانات ثم إعادة المحاولة.");
                tvProgressPercent.setText("تنبيه");
                showNeedsFirstLaunchDialog();
            });
            return;
        }

        if (result == null || !result.startsWith("OK|")) {
            throw new IllegalStateException("فشل تطبيق التعريب: " + result);
        }

        String[] parts = result.split("\\|");
        String fileCount = parts.length >= 4 ? parts[3] : "";
        updateProgress(100, "اكتمل تثبيت التعريب بنجاح (" + fileCount + " ملف) مع حفظ النسخة الاحتياطية الأصلية ✓");
        mainHandler.post(() -> Toast.makeText(this, "تم تثبيت تعريب JoJo: Golden Hymn بنجاح ✓", Toast.LENGTH_LONG).show());
    }

    private void confirmAndRestore() {
        if (isBusy) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        b.setTitle("استعادة الملفات الأصلية");
        b.setMessage("هل تريد إزالة التعريب واستعادة جميع ملفات JoJo: Golden Hymn الأصلية من النسخة الاحتياطية؟");
        b.setPositiveButton("نعم، استعادة الأصل", (d, w) -> runRestoreFlow());
        b.setNegativeButton("إلغاء", null);
        b.show();
    }

    private void runRestoreFlow() {
        if (isBusy) {
            return;
        }
        worker.execute(() -> {
            AccessMode mode = detectAccessMode();
            if (!isReadyAccessMode(mode)) {
                pendingAction = PendingAction.RESTORE;
                mainHandler.post(this::requestBestAccessInteractive);
                return;
            }

            setBusyUi(true, "جارٍ استعادة الملفات الأصلية من النسخة الاحتياطية...", 20);
            try {
                String result = executeRestoreSync(mode, this::updateProgress);
                if (result == null || !result.startsWith("OK|")) {
                    throw new IllegalStateException("فشل استعادة الأصل: " + result);
                }

                updateProgress(100, "تمت استعادة ملفات اللعبة الأصلية بنجاح 100% ✓");
                mainHandler.post(() -> Toast.makeText(this, "تمت استعادة ملفات اللعبة الأصلية ✓", Toast.LENGTH_LONG).show());
            } catch (Throwable t) {
                showErrorState("خطأ أثناء الاستعادة: " + readableError(t));
            } finally {
                setBusyUi(false, null, -1);
                refreshStatusAsync(null);
            }
        });
    }

    private void runDiagnosticScanFlow() {
        if (isBusy) {
            return;
        }
        worker.execute(() -> {
            AccessMode mode = detectAccessMode();
            if (mode != AccessMode.BRIDGE_ACTIVE && mode != AccessMode.DIRECT_FS) {
                mainHandler.post(() -> {
                    Toast.makeText(this, "يلزم تفعيل Shizuku لتنفيذ الفحص التشخيصي لمسارات اللعبة.", Toast.LENGTH_LONG).show();
                    requestBestAccessInteractive();
                });
                return;
            }
            setBusyUi(true, "جارٍ فحص مجلدات JoJo: Golden Hymn وبصمة ملف اللغة والفهرس...", 40);
            try {
                String resp = (mode == AccessMode.BRIDGE_ACTIVE)
                        ? sendBridgeCommand("SCAN_DIAG\t" + getHintDir().getAbsolutePath(), 30000)
                        : ShellBridgeServer.runDiagnosticScan(getHintDir());
                if (resp == null || !resp.startsWith("OK|")) {
                    throw new IllegalStateException("تعذر إتمام الفحص: " + resp);
                }
                String report = resp.substring(3).replace("\\n", "\n");
                updateProgress(100, "اكتمل الفحص التشخيصي لمسارات اللعبة ✓");
                mainHandler.post(() -> showDiagnosticReportDialog(report));
            } catch (Throwable t) {
                showErrorState("خطأ أثناء الفحص: " + readableError(t));
            } finally {
                setBusyUi(false, null, -1);
            }
        });
    }

    private void showDiagnosticReportDialog(String report) {
        ScrollView sv = new ScrollView(this);
        sv.setPadding(dp(16), dp(12), dp(16), dp(12));
        TextView tv = new TextView(this);
        tv.setText(report);
        tv.setTextSize(11.5f);
        tv.setTextColor(LauncherConfig.COL_TEXT);
        tv.setTextIsSelectable(true);
        tv.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        sv.addView(tv);

        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        b.setTitle("تقرير فحص مسارات JoJo: Golden Hymn");
        b.setView(sv);
        b.setPositiveButton("نسخ التقرير", (d, w) -> {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("JoJo GH Diagnostic", report));
                    Toast.makeText(this, "تم نسخ التقرير إلى الحافظة ✓", Toast.LENGTH_SHORT).show();
                }
            } catch (Throwable ignored) {
            }
        });
        b.setNegativeButton("إغلاق", null);
        b.show();
    }

    private void showNeedsFirstLaunchDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        b.setTitle("يلزم تشغيل اللعبة لمرة واحدة أولاً");
        b.setMessage("لعبة JoJo: Golden Hymn مثبّتة، لكن مجلد بياناتها لم يُنشأ بالكامل بعد.\n\nشغّل اللعبة مرة واحدة واتركها تنشئ ملفاتها الأولية، ثم عد لهذا التطبيق واضغط تثبيت التعريب.");
        b.setPositiveButton("تشغيل اللعبة الآن", (d, w) -> launchGameDirect());
        b.setNegativeButton("حسناً", null);
        b.show();
    }

    private void launchGameWithSafetyCheck() {
        if (isBusy) {
            return;
        }
        if (!isAnyGameInstalled()) {
            Toast.makeText(this, "لعبة JoJo: Golden Hymn غير مثبّتة على الجهاز.", Toast.LENGTH_SHORT).show();
            return;
        }
        worker.execute(() -> {
            AccessMode mode = detectAccessMode();
            SafetyCheckResult safety = evaluateSafetyGuardSync(mode, latestRemoteManifest, true);
            mainHandler.post(() -> {
                if (!safety.safe) {
                    showSafetyGuardAlertDialog(safety.reason);
                    refreshStatusAsync(null);
                    return;
                }
                launchGameDirect();
            });
        });
    }

    private void launchGameDirect() {
        PackageManager pm = getPackageManager();
        for (String pkg : LauncherConfig.GAME_PACKAGES) {
            Intent launch = pm.getLaunchIntentForPackage(pkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
                return;
            }
        }
        Toast.makeText(this, "لعبة JoJo: Golden Hymn غير مثبّتة على الجهاز.", Toast.LENGTH_SHORT).show();
    }

    private void openUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    private void updateProgress(int percent, String message) {
        mainHandler.post(() -> {
            if (message != null) {
                tvStatusText.setText(message);
            }
            if (percent >= 0) {
                progressBar.setProgress(Math.min(100, percent));
                tvProgressPercent.setText(Math.min(100, percent) + "%");
            }
        });
    }

    private void showErrorState(String msg) {
        mainHandler.post(() -> {
            tvStatusText.setText(msg);
            tvProgressPercent.setText("تنبيه");
        });
    }

    private void setBusyUi(boolean busy, String statusMsg, int percent) {
        isBusy = busy;
        mainHandler.post(() -> {
            btnInstallOnline.setEnabled(!busy);
            btnInstallLocalZip.setEnabled(!busy);
            if (btnDiagScan != null) {
                btnDiagScan.setEnabled(!busy);
            }
            btnRestore.setEnabled(!busy);
            btnGrantAccess.setEnabled(!busy);
            btnCheckUpdate.setEnabled(!busy);
            btnLaunchGame.setEnabled(!busy);
            if (statusMsg != null) {
                tvStatusText.setText(statusMsg);
            }
            if (percent >= 0) {
                progressBar.setProgress(percent);
                tvProgressPercent.setText(percent + "%");
            }
        });
    }

    private static String readableError(Throwable t) {
        if (t == null) {
            return "خطأ غير معروف";
        }
        return (t.getMessage() != null && !t.getMessage().isEmpty())
                ? t.getMessage()
                : t.getClass().getSimpleName();
    }
}
