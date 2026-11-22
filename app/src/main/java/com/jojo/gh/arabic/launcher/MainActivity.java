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
import android.content.res.AssetFileDescriptor;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/**
 * الواجهة الرئيسية لمشغّل ومثبّت تعريب JoJo's Bizarre Adventure: Golden Hymn — تعريب R.D.AV (v1.1.0).
 * تصميم Dark Gaming UI مستوحى من ألوان «الرياح الذهبية - Giorno Giovanna» مع:
 * - خلفية فيديو ترويجية صامتة متكررة مدمجة محلياً (TextureView + MediaPlayer بدون WebView أو إنترنت)
 * - حارس الأمان وفحص التحديثات (Version Safety Guard)
 * - مؤشر خطوات حي (4 مراحل) وإحصائيات التعريب الفعلية (15,967 نصاً معرّباً)
 * - كامل محركات الوصول والنسخ الاحتياطي (Shizuku / SAF / DirectFS / Root)
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
        BETA_INSTALL,
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
    private volatile boolean lastSafetyOk = true;
    private volatile boolean safetyAlertShownThisSession = false;
    private String bridgeAuthToken = "";

    // نظام الألعاب المتعددة (R.D.AV Multi-Game Hub) + وضع Beta السري
    private GameProfile activeProfile = GameProfileRegistry.getDefaultProfile();
    private boolean isOnGameHub = false;
    private FrameLayout rootHost;
    private View homeScreenView;
    private View gameHubScreenView;
    private LinearLayout homeCardsGrid;
    private LinearLayout homeBetaBanner;
    private LinearLayout hubBetaBanner;
    private TextView btnBackToLibrary;
    private TextView tvStrategyBadge;
    private Button btnInstallBeta;
    private int secretTapCount = 0;
    private long lastSecretTapMs = 0L;

    // مشغّل الفيديو المحلي الخفيف (TextureView + MediaPlayer)
    private static final float PROMO_FADE_DURATION_MS = 5000f;
    private static final long PROMO_FADE_TICK_MS = 100L;
    private TextureView promoTextureView;
    private MediaPlayer promoPlayer;
    private Surface promoSurface;
    private boolean promoPrepared = false;
    private boolean isPromoMuted = false;
    private TextView btnPromoAudio;
    private final Runnable promoFadeRunnable = new Runnable() {
        @Override
        public void run() {
            if (promoPlayer != null && promoPrepared) {
                try {
                    if (promoPlayer.isPlaying()) {
                        applyPromoVolume();
                        mainHandler.postDelayed(this, PROMO_FADE_TICK_MS);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    };
    private final Runnable hidePromoAudioRunnable = new Runnable() {
        @Override
        public void run() {
            if (btnPromoAudio != null && btnPromoAudio.getVisibility() == View.VISIBLE) {
                btnPromoAudio.animate()
                        .alpha(0f)
                        .setDuration(260L)
                        .withEndAction(() -> {
                            if (btnPromoAudio != null) {
                                btnPromoAudio.setVisibility(View.GONE);
                            }
                        })
                        .start();
            }
        }
    };

    // عناصر الواجهة
    private Typeface fontRegular;
    private Typeface fontBold;
    private TextView tvHeadlineStatus;
    private TextView tvGameBadge;
    private TextView tvSafetyBadge;
    private TextView tvAccessBadge;
    private TextView tvPatchBadge;
    private TextView tvStep1Dot, tvStep2Dot, tvStep3Dot, tvStep4Dot;
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
        if (!SignatureGuard.verifyOrTerminate(this)) {
            return;
        }
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(LauncherConfig.COL_BG);
        getWindow().setNavigationBarColor(LauncherConfig.COL_BG_BOTTOM);
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

        setContentView(buildRootContainer());
        syncBetaModeUi();
        ensureLegacyStoragePermissions();
        refreshStatusAsync(null);

        if (activeProfile.hasOnlineConfig()) {
            checkOnlineUpdateAsync(false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isOnGameHub) {
            resumePromoVideo();
        }
        syncBetaModeUi();
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
    public void onBackPressed() {
        if (isOnGameHub) {
            if (isBusy) {
                Toast.makeText(this, "يرجى الانتظار حتى انتهاء العملية الحالية...", Toast.LENGTH_SHORT).show();
                return;
            }
            showHomeLibrary();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(hidePromoAudioRunnable);
        mainHandler.removeCallbacks(promoFadeRunnable);
        pausePromoVideo();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacks(hidePromoAudioRunnable);
        mainHandler.removeCallbacks(promoFadeRunnable);
        releasePromoVideo();
        try {
            Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener);
            Shizuku.removeBinderDeadListener(shizukuBinderDeadListener);
            Shizuku.removeRequestPermissionResultListener(shizukuPermListener);
        } catch (Throwable ignored) {
        }
    }

    // =========================================================================
    // إدارة مشغّل الفيديو الترويجي المحلي مع تدرج الصوت (Fade-In/Out 5s) وزر الكتم
    // =========================================================================

    private float computePromoFadeVolume(int posMs, int durMs) {
        if (isPromoMuted) {
            return 0f;
        }
        if (durMs <= 0) {
            return 1f;
        }
        if (posMs < PROMO_FADE_DURATION_MS) {
            return Math.max(0f, Math.min(1f, posMs / PROMO_FADE_DURATION_MS));
        }
        int remaining = durMs - posMs;
        if (remaining < PROMO_FADE_DURATION_MS) {
            return Math.max(0f, Math.min(1f, remaining / PROMO_FADE_DURATION_MS));
        }
        return 1f;
    }

    private void applyPromoVolume() {
        try {
            if (promoPlayer != null && promoPrepared) {
                float vol = computePromoFadeVolume(promoPlayer.getCurrentPosition(), promoPlayer.getDuration());
                promoPlayer.setVolume(vol, vol);
            }
        } catch (Throwable ignored) {
        }
    }

    private void startPromoFadeTicker() {
        mainHandler.removeCallbacks(promoFadeRunnable);
        applyPromoVolume();
        mainHandler.postDelayed(promoFadeRunnable, PROMO_FADE_TICK_MS);
    }

    private void showPromoAudioControlTemporarily() {
        if (btnPromoAudio == null) {
            return;
        }
        mainHandler.removeCallbacks(hidePromoAudioRunnable);
        btnPromoAudio.animate().cancel();
        btnPromoAudio.setText(isPromoMuted ? "🔇" : "🔊");
        if (btnPromoAudio.getVisibility() != View.VISIBLE) {
            btnPromoAudio.setAlpha(0f);
            btnPromoAudio.setVisibility(View.VISIBLE);
        }
        btnPromoAudio.animate().alpha(1f).setDuration(160L).start();
        mainHandler.postDelayed(hidePromoAudioRunnable, 2000L);
    }

    private void togglePromoAudioMute() {
        isPromoMuted = !isPromoMuted;
        applyPromoVolume();
        showPromoAudioControlTemporarily();
    }

    private void startPromoVideoOnSurface(SurfaceTexture st) {
        releasePromoVideo();
        try {
            AssetFileDescriptor afd = getResources().openRawResourceFd(R.raw.bg_promo);
            if (afd == null) {
                return;
            }
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();

            promoSurface = new Surface(st);
            mp.setSurface(promoSurface);
            mp.setLooping(false);
            mp.setVolume(0f, 0f);
            mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            mp.setOnPreparedListener(player -> {
                promoPrepared = true;
                try {
                    player.setVolume(0f, 0f);
                    player.seekTo(0);
                    if (isOnGameHub) {
                        player.start();
                        startPromoFadeTicker();
                    }
                } catch (Throwable ignored) {
                }
            });
            mp.setOnCompletionListener(player -> {
                try {
                    if (promoPlayer == player && promoPrepared && isOnGameHub) {
                        player.setVolume(0f, 0f);
                        player.seekTo(0);
                        player.start();
                        startPromoFadeTicker();
                    }
                } catch (Throwable ignored) {
                }
            });
            mp.setOnErrorListener((player, what, extra) -> {
                mainHandler.post(() -> {
                    if (promoTextureView != null) {
                        promoTextureView.setVisibility(View.GONE);
                    }
                });
                releasePromoVideo();
                return true;
            });
            promoPlayer = mp;
            mp.prepareAsync();
        } catch (Throwable t) {
            if (promoTextureView != null) {
                promoTextureView.setVisibility(View.GONE);
            }
            releasePromoVideo();
        }
    }

    private void pausePromoVideo() {
        mainHandler.removeCallbacks(promoFadeRunnable);
        try {
            if (promoPlayer != null && promoPrepared && promoPlayer.isPlaying()) {
                promoPlayer.pause();
            }
        } catch (Throwable ignored) {
        }
    }

    private void resumePromoVideo() {
        try {
            if (promoPlayer != null && promoPrepared && !promoPlayer.isPlaying()) {
                applyPromoVolume();
                promoPlayer.start();
                startPromoFadeTicker();
            }
        } catch (Throwable ignored) {
        }
    }

    private void releasePromoVideo() {
        mainHandler.removeCallbacks(promoFadeRunnable);
        promoPrepared = false;
        if (promoPlayer != null) {
            try {
                promoPlayer.stop();
            } catch (Throwable ignored) {
            }
            try {
                promoPlayer.release();
            } catch (Throwable ignored) {
            }
            promoPlayer = null;
        }
        if (promoSurface != null) {
            try {
                promoSurface.release();
            } catch (Throwable ignored) {
            }
            promoSurface = null;
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
    // هيكل الشاشتين: (1) مكتبة الألعاب الرئيسية  <->  (2) صفحة تفاصيل اللعبة (Game Hub)
    // =========================================================================

    private View buildRootContainer() {
        rootHost = new FrameLayout(this);
        GradientDrawable pageBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{LauncherConfig.COL_BG, LauncherConfig.COL_BG_BOTTOM, LauncherConfig.COL_BG}
        );
        rootHost.setBackground(pageBg);

        homeScreenView = buildHomeLibraryUi();
        gameHubScreenView = buildUi();
        gameHubScreenView.setVisibility(View.GONE);

        rootHost.addView(homeScreenView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        rootHost.addView(gameHubScreenView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        isOnGameHub = false;
        refreshHomeCards();
        return rootHost;
    }

    private LinearLayout makeBetaStatusBanner() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.parseColor("#3B0764"), Color.parseColor("#6B21A8"), Color.parseColor("#4C1D95")}
        );
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), Color.parseColor("#D8B4FE"));
        bar.setBackground(bg);

        TextView tvLabel = makeText("🧪 وضع اختبار التعريب (Beta Channel) مُفعّل", 11f, Color.WHITE, true);
        bar.addView(tvLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnChangePass = makeText("كلمة المرور", 10f, LauncherConfig.COL_GOLD, true);
        btnChangePass.setPadding(dp(8), dp(4), dp(8), dp(4));
        GradientDrawable cpBg = new GradientDrawable();
        cpBg.setColor(Color.parseColor("#2A0B4D"));
        cpBg.setCornerRadius(dp(8));
        cpBg.setStroke(dp(1), LauncherConfig.COL_GOLD);
        btnChangePass.setBackground(cpBg);
        btnChangePass.setOnClickListener(v -> showChangeBetaPasswordDialog());
        LinearLayout.LayoutParams cpLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cpLp.rightMargin = dp(6);
        bar.addView(btnChangePass, cpLp);

        TextView btnCloseBeta = makeText("إغلاق", 10f, Color.WHITE, true);
        btnCloseBeta.setPadding(dp(9), dp(4), dp(9), dp(4));
        GradientDrawable cbBg = new GradientDrawable();
        cbBg.setColor(Color.parseColor("#7F1D3A"));
        cbBg.setCornerRadius(dp(8));
        cbBg.setStroke(dp(1), LauncherConfig.COL_DANGER);
        btnCloseBeta.setBackground(cbBg);
        btnCloseBeta.setOnClickListener(v -> {
            BetaModeManager.setBetaEnabled(this, false);
            syncBetaModeUi();
            Toast.makeText(this, "تم إغلاق وضع اختبار التعريب السري (Beta).", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cbLp.rightMargin = dp(6);
        bar.addView(btnCloseBeta, cbLp);

        bar.setVisibility(View.GONE);
        return bar;
    }

    private void syncBetaModeUi() {
        boolean enabled = BetaModeManager.isBetaEnabled(this);
        int vis = enabled ? View.VISIBLE : View.GONE;
        if (homeBetaBanner != null) {
            homeBetaBanner.setVisibility(vis);
        }
        if (hubBetaBanner != null) {
            hubBetaBanner.setVisibility(vis);
        }
        if (btnInstallBeta != null) {
            btnInstallBeta.setVisibility(vis);
        }
    }

    private void handleSecretBrandTap() {
        long now = System.currentTimeMillis();
        if (now - lastSecretTapMs > 2500L) {
            secretTapCount = 0;
        }
        lastSecretTapMs = now;
        secretTapCount++;
        if (secretTapCount >= 5) {
            secretTapCount = 0;
            showBetaPasswordDialog();
        }
    }

    private void showBetaPasswordDialog() {
        if (BetaModeManager.isBetaEnabled(this)) {
            showChangeBetaPasswordDialog();
            return;
        }
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(20), dp(12), dp(20), dp(4));

        TextView hint = makeText("أدخل كلمة مرور المطور لتفعيل قناة اختبار التعريب السرية (Beta Channel):", 12f, LauncherConfig.COL_MUTED, false);
        hint.setPadding(0, 0, 0, dp(10));
        container.addView(hint);

        EditText etPass = new EditText(this);
        etPass.setHint("كلمة مرور المطور");
        etPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etPass.setTextColor(LauncherConfig.COL_TEXT);
        etPass.setHintTextColor(LauncherConfig.COL_MUTED);
        etPass.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable etBg = new GradientDrawable();
        etBg.setColor(LauncherConfig.COL_CARD_INNER);
        etBg.setCornerRadius(dp(10));
        etBg.setStroke(dp(1), LauncherConfig.COL_PURPLE);
        etPass.setBackground(etBg);
        container.addView(etPass);

        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        b.setTitle("🧪 بوابة المطور السرية — R.D.AV");
        b.setView(container);
        b.setPositiveButton("تفعيل Beta", (d, w) -> {
            String entered = etPass.getText() != null ? etPass.getText().toString() : "";
            if (BetaModeManager.verifyPassword(this, entered)) {
                BetaModeManager.setBetaEnabled(this, true);
                syncBetaModeUi();
                Toast.makeText(this, "🧪 تم تفعيل وضع اختبار التعريب (Beta Channel) بنجاح ✓", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "كلمة المرور غير صحيحة.", Toast.LENGTH_SHORT).show();
            }
        });
        b.setNegativeButton("إلغاء", null);
        b.show();
    }

    private void showChangeBetaPasswordDialog() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(20), dp(12), dp(20), dp(4));

        EditText etCur = new EditText(this);
        etCur.setHint("كلمة المرور الحالية");
        etCur.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etCur.setTextColor(LauncherConfig.COL_TEXT);
        etCur.setHintTextColor(LauncherConfig.COL_MUTED);
        container.addView(etCur);

        EditText etNew = new EditText(this);
        etNew.setHint("كلمة المرور الجديدة (4 أحرف على الأقل)");
        etNew.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etNew.setTextColor(LauncherConfig.COL_TEXT);
        etNew.setHintTextColor(LauncherConfig.COL_MUTED);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(10);
        container.addView(etNew, nlp);

        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        b.setTitle("تغيير كلمة مرور وضع الاختبار");
        b.setView(container);
        b.setPositiveButton("حفظ (SHA-256)", (d, w) -> {
            String cur = etCur.getText() != null ? etCur.getText().toString() : "";
            String nw = etNew.getText() != null ? etNew.getText().toString() : "";
            if (BetaModeManager.changePassword(this, cur, nw)) {
                Toast.makeText(this, "تم تحديث كلمة مرور المطور وتشفيرها بـ SHA-256 ✓", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "كلمة المرور الحالية خاطئة أو الجديدة أقصر من 4 أحرف.", Toast.LENGTH_SHORT).show();
            }
        });
        b.setNegativeButton("إلغاء", null);
        b.show();
    }

    private View buildHomeLibraryUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(22));
        scroll.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        // شريط وضع الاختبار السري (مخفي افتراضياً)
        homeBetaBanner = makeBetaStatusBanner();
        LinearLayout.LayoutParams bbLp = makeCardLp();
        root.addView(homeBetaBanner, bbLp);

        // الترويسة العليا لمنصة الألعاب المتعددة مع البوابة السرية على شعار R.D.AV
        LinearLayout studioCard = new LinearLayout(this);
        studioCard.setOrientation(LinearLayout.VERTICAL);
        studioCard.setPadding(dp(16), dp(16), dp(16), dp(14));
        GradientDrawable sBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor("#241145"), LauncherConfig.COL_CARD, Color.parseColor("#140A26")}
        );
        sBg.setCornerRadius(dp(18));
        sBg.setStroke(dp(1), LauncherConfig.COL_BORDER_GOLD);
        studioCard.setBackground(sBg);

        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setOrientation(LinearLayout.HORIZONTAL);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);

        // شعار R.D.AV — الضغط 5 مرات متتالية أو الضغط المطوّل يفتح بوابة Beta السرية!
        TextView tvSecretLogo = makeText("R.D.AV", 15f, Color.parseColor("#0D061A"), true);
        tvSecretLogo.setGravity(Gravity.CENTER);
        tvSecretLogo.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable logoBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{LauncherConfig.COL_GOLD, LauncherConfig.COL_ACCENT}
        );
        logoBg.setCornerRadius(dp(13));
        logoBg.setStroke(dp(1), Color.parseColor("#FEF08A"));
        tvSecretLogo.setBackground(logoBg);
        tvSecretLogo.setClickable(true);
        tvSecretLogo.setLongClickable(true);
        tvSecretLogo.setOnClickListener(v -> handleSecretBrandTap());
        tvSecretLogo.setOnLongClickListener(v -> {
            showBetaPasswordDialog();
            return true;
        });
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        logoLp.leftMargin = dp(12);
        brandRow.addView(tvSecretLogo, logoLp);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.addView(makeText(LauncherConfig.HUB_TITLE, 17f, LauncherConfig.COL_GOLD, true));
        TextView tvSub = makeText(LauncherConfig.HUB_SUBTITLE, 11f, LauncherConfig.COL_MUTED, false);
        tvSub.setPadding(0, dp(2), 0, 0);
        titleCol.addView(tvSub);
        brandRow.addView(titleCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        studioCard.addView(brandRow);

        LinearLayout topPills = new LinearLayout(this);
        topPills.setOrientation(LinearLayout.HORIZONTAL);
        topPills.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tpLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tpLp.topMargin = dp(12);

        TextView hubVer = makeText("Multi-Game Hub • v" + LauncherConfig.APP_VERSION, 10.5f, LauncherConfig.COL_CYAN, true);
        hubVer.setPadding(dp(10), dp(5), dp(10), dp(5));
        GradientDrawable hvBg = new GradientDrawable();
        hvBg.setColor(Color.parseColor("#1D1138"));
        hvBg.setCornerRadius(dp(10));
        hvBg.setStroke(dp(1), LauncherConfig.COL_BORDER);
        hubVer.setBackground(hvBg);
        LinearLayout.LayoutParams hvLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hvLp.leftMargin = dp(8);
        topPills.addView(hubVer, hvLp);

        TextView igHome = makeText("Instagram: " + LauncherConfig.INSTAGRAM_HANDLE + " ↗", 10.5f, Color.WHITE, true);
        igHome.setPadding(dp(12), dp(5), dp(12), dp(5));
        GradientDrawable igBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.parseColor("#7C2D8C"), Color.parseColor("#C13584"), Color.parseColor("#D97706")}
        );
        igBg.setCornerRadius(dp(10));
        igBg.setStroke(dp(1), LauncherConfig.COL_GOLD);
        igHome.setBackground(igBg);
        igHome.setOnClickListener(v -> openUrl(LauncherConfig.INSTAGRAM_URL));
        topPills.addView(igHome);

        studioCard.addView(topPills, tpLp);
        root.addView(studioCard, makeCardLp());

        // عنوان القسم
        TextView secTitle = makeText("مكتبة الألعاب المتوفرة", 14.5f, LauncherConfig.COL_TEXT, true);
        secTitle.setPadding(dp(4), dp(4), dp(4), dp(10));
        root.addView(secTitle);

        // شبكة بطاقات الألعاب (عمودان)
        homeCardsGrid = new LinearLayout(this);
        homeCardsGrid.setOrientation(LinearLayout.VERTICAL);
        root.addView(homeCardsGrid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // تذييل مكتبة الألعاب
        TextView homeFooter = makeText(LauncherConfig.FOOTER_TEXT, 10.5f, LauncherConfig.COL_MUTED, false);
        homeFooter.setGravity(Gravity.CENTER);
        homeFooter.setPadding(0, dp(14), 0, dp(8));
        root.addView(homeFooter);

        return scroll;
    }

    private void refreshHomeCards() {
        if (homeCardsGrid == null) {
            return;
        }
        homeCardsGrid.removeAllViews();
        List<GameProfile> profiles = GameProfileRegistry.getAllProfiles();
        for (int i = 0; i < profiles.size(); i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = dp(12);

            View card1 = createHomeGameCard(profiles.get(i));
            LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            lp1.leftMargin = dp(5);
            row.addView(card1, lp1);

            if (i + 1 < profiles.size()) {
                View card2 = createHomeGameCard(profiles.get(i + 1));
                LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                lp2.rightMargin = dp(5);
                row.addView(card2, lp2);
            } else {
                View spacer = new View(this);
                LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(0, 1, 1f);
                spLp.rightMargin = dp(5);
                row.addView(spacer, spLp);
            }
            homeCardsGrid.addView(row, rowLp);
        }
    }

    private View createHomeGameCard(GameProfile profile) {
        boolean installed = isPackageInstalled(profile.packageName);
        String statusText;
        int statusBg;
        int statusFg;

        if (profile.comingSoon) {
            statusText = "قريباً";
            statusBg = Color.parseColor("#3B1F0B");
            statusFg = Color.parseColor("#FDBA74");
        } else if (!installed) {
            statusText = "اللعبة غير مثبتة";
            statusBg = Color.parseColor("#3D1222");
            statusFg = LauncherConfig.COL_DANGER;
        } else if (profile.id.equals(activeProfile.id) && !lastSafetyOk) {
            statusText = "يتوفر تحديث";
            statusBg = Color.parseColor("#3D1222");
            statusFg = LauncherConfig.COL_DANGER;
        } else if (profile.id.equals(activeProfile.id) && isPatchCurrentlyInstalled) {
            boolean hasRemoteUpdate = latestRemoteManifest != null
                    && !installedPatchVersion.isEmpty()
                    && !installedPatchVersion.equals(latestRemoteManifest.patchVersion);
            if (hasRemoteUpdate) {
                statusText = "يتوفر تحديث";
                statusBg = Color.parseColor("#3D2A0D");
                statusFg = LauncherConfig.COL_GOLD;
            } else {
                statusText = "مُحدَّث ✓";
                statusBg = Color.parseColor("#113826");
                statusFg = LauncherConfig.COL_SUCCESS;
            }
        } else {
            statusText = "جاهز للتعريب";
            statusBg = Color.parseColor("#22153D");
            statusFg = LauncherConfig.COL_CYAN;
        }

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(12), dp(14), dp(12), dp(14));
        GradientDrawable cBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#1B1233"), LauncherConfig.COL_CARD}
        );
        cBg.setCornerRadius(dp(18));
        cBg.setStroke(dp(1), profile.comingSoon ? LauncherConfig.COL_BORDER : profile.accentColor);
        card.setBackground(cBg);
        card.setClickable(true);

        // أيقونة / غلاف اللعبة بإطار دائري بلون هوية اللعبة
        FrameLayout iconBox = new FrameLayout(this);
        GradientDrawable iFrame = new GradientDrawable();
        iFrame.setCornerRadius(dp(18));
        iFrame.setStroke(dp(2), profile.accentColor);
        iFrame.setColor(LauncherConfig.COL_CARD_INNER);
        iconBox.setBackground(iFrame);
        iconBox.setPadding(dp(2), dp(2), dp(2), dp(2));

        ImageView iv = new ImageView(this);
        iv.setImageResource(profile.iconRes);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        clipRounded(iv, dp(16));
        iconBox.addView(iv, new FrameLayout.LayoutParams(dp(84), dp(84)));
        card.addView(iconBox, new LinearLayout.LayoutParams(dp(88), dp(88)));

        // شارة الحالة
        TextView tvStatus = makeText(statusText, 10.5f, statusFg, true);
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable stBg = new GradientDrawable();
        stBg.setColor(statusBg);
        stBg.setCornerRadius(dp(10));
        stBg.setStroke(dp(1), statusFg);
        tvStatus.setBackground(stBg);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = dp(10);
        card.addView(tvStatus, stLp);

        // اسم اللعبة
        TextView tvTitle = makeText(profile.title, 12.5f, LauncherConfig.COL_TEXT, true);
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setPadding(0, dp(8), 0, dp(2));
        card.addView(tvTitle);

        TextView tvAr = makeText(profile.arabicTitle, 11f, profile.accentColor, true);
        tvAr.setGravity(Gravity.CENTER);
        card.addView(tvAr);

        // رقم الإصدار واللعبة
        String verLine = profile.comingSoon
                ? "محرك: " + profile.patchStrategy.code
                : ("تعريب " + profile.patchVersionLabel + " • لعبة v" + profile.targetGameVersionName);
        TextView tvVer = makeText(verLine, 9.8f, LauncherConfig.COL_MUTED, false);
        tvVer.setGravity(Gravity.CENTER);
        tvVer.setPadding(0, dp(6), 0, 0);
        card.addView(tvVer);

        card.setOnClickListener(v -> {
            if (profile.comingSoon) {
                AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
                b.setTitle(profile.title + " — قريباً");
                b.setMessage(
                        "تم تجهيز بنية «" + profile.patchStrategy.code + "» داخل منصة R.D.AV Multi-Game Hub لدعم تعريب "
                                + profile.title + " والألعاب القياسية مستقبلاً بنسخ احتياطي تلقائي.\n\n"
                                + "تابع حسابنا على Instagram (" + LauncherConfig.INSTAGRAM_HANDLE + ") لموعد الإطلاق!"
                );
                b.setPositiveButton("حسناً", null);
                b.show();
            } else {
                openGameHub(profile);
            }
        });
        return card;
    }

    private boolean isPackageInstalled(String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) {
            return false;
        }
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void openGameHub(GameProfile profile) {
        activeProfile = profile;
        isOnGameHub = true;
        if (tvStrategyBadge != null) {
            tvStrategyBadge.setText("محرك: " + profile.patchStrategy.code);
        }
        homeScreenView.setVisibility(View.GONE);
        gameHubScreenView.setVisibility(View.VISIBLE);
        syncBetaModeUi();
        resumePromoVideo();
        refreshStatusAsync(null);
    }

    private void showHomeLibrary() {
        isOnGameHub = false;
        mainHandler.removeCallbacks(hidePromoAudioRunnable);
        mainHandler.removeCallbacks(promoFadeRunnable);
        pausePromoVideo();
        gameHubScreenView.setVisibility(View.GONE);
        homeScreenView.setVisibility(View.VISIBLE);
        syncBetaModeUi();
        refreshHomeCards();
    }

    // =========================================================================
    // بناء شاشة تفاصيل اللعبة (Game Hub — التصميم الفخم الكامل للعبة JoJo)
    // =========================================================================

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        GradientDrawable pageBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{LauncherConfig.COL_BG, LauncherConfig.COL_BG_BOTTOM, LauncherConfig.COL_BG}
        );
        scroll.setBackground(pageBg);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(20));
        scroll.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        ));

        // 0) شريط وضع الاختبار السري في صفحة التفاصيل (مخفي افتراضياً)
        hubBetaBanner = makeBetaStatusBanner();
        root.addView(hubBetaBanner, makeCardLp());

        // 0.5) شريط الرجوع إلى مكتبة الألعاب + شارة محرك الحقن النشط
        LinearLayout backRow = new LinearLayout(this);
        backRow.setOrientation(LinearLayout.HORIZONTAL);
        backRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams backRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        backRowLp.bottomMargin = dp(10);

        btnBackToLibrary = makeText("←  مكتبة الألعاب", 12f, LauncherConfig.COL_GOLD, true);
        btnBackToLibrary.setPadding(dp(14), dp(7), dp(14), dp(7));
        GradientDrawable backBg = new GradientDrawable();
        backBg.setColor(Color.parseColor("#1A1033"));
        backBg.setCornerRadius(dp(12));
        backBg.setStroke(dp(1), LauncherConfig.COL_BORDER_GOLD);
        btnBackToLibrary.setBackground(backBg);
        btnBackToLibrary.setClickable(true);
        btnBackToLibrary.setOnClickListener(v -> {
            if (!isBusy) {
                showHomeLibrary();
            }
        });
        backRow.addView(btnBackToLibrary);

        View flexSpacer = new View(this);
        backRow.addView(flexSpacer, new LinearLayout.LayoutParams(0, 1, 1f));

        tvStrategyBadge = makeText("محرك: " + activeProfile.patchStrategy.code, 10f, LauncherConfig.COL_CYAN, true);
        tvStrategyBadge.setPadding(dp(10), dp(5), dp(10), dp(5));
        GradientDrawable stratBg = new GradientDrawable();
        stratBg.setColor(Color.parseColor("#160D2B"));
        stratBg.setCornerRadius(dp(10));
        stratBg.setStroke(dp(1), LauncherConfig.COL_BORDER);
        tvStrategyBadge.setBackground(stratBg);
        backRow.addView(tvStrategyBadge);

        root.addView(backRow, backRowLp);

        // ---------------------------------------------------------------------
        // 1) الترويسة العليا: شعار اللعبة + «تعريب R.D.AV» + شارة إنستقرام
        // ---------------------------------------------------------------------
        LinearLayout headerCard = new LinearLayout(this);
        headerCard.setOrientation(LinearLayout.VERTICAL);
        headerCard.setPadding(dp(14), dp(14), dp(14), dp(12));
        GradientDrawable headerBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor("#241145"), LauncherConfig.COL_CARD, Color.parseColor("#180C2B")}
        );
        headerBg.setCornerRadius(dp(18));
        headerBg.setStroke(dp(1), LauncherConfig.COL_BORDER_GOLD);
        headerCard.setBackground(headerBg);

        LinearLayout topBrandRow = new LinearLayout(this);
        topBrandRow.setOrientation(LinearLayout.HORIZONTAL);
        topBrandRow.setGravity(Gravity.CENTER_VERTICAL);

        // أيقونة اللعبة الرسمية بإطار دائري ذهبي
        FrameLayout iconWrap = new FrameLayout(this);
        GradientDrawable iconFrame = new GradientDrawable();
        iconFrame.setCornerRadius(dp(14));
        iconFrame.setStroke(dp(2), LauncherConfig.COL_ACCENT);
        iconFrame.setColor(LauncherConfig.COL_CARD_INNER);
        iconWrap.setBackground(iconFrame);
        iconWrap.setPadding(dp(2), dp(2), dp(2), dp(2));

        ImageView ivLogo = new ImageView(this);
        ivLogo.setImageResource(R.drawable.ic_jojo_game);
        ivLogo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        clipRounded(ivLogo, dp(12));
        iconWrap.addView(ivLogo, new FrameLayout.LayoutParams(dp(50), dp(50)));

        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(54), dp(54));
        iconLp.leftMargin = dp(12);
        topBrandRow.addView(iconWrap, iconLp);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView tvBrand = makeText(LauncherConfig.BRAND_TITLE, 19, LauncherConfig.COL_GOLD, true);
        TextView tvGameTitle = makeText(LauncherConfig.APP_TITLE, 12.5f, LauncherConfig.COL_TEXT, true);
        tvGameTitle.setPadding(0, dp(2), 0, 0);
        TextView tvSub = makeText(LauncherConfig.APP_SUBTITLE, 10.5f, LauncherConfig.COL_MUTED, false);
        tvSub.setPadding(0, dp(2), 0, 0);
        titleCol.addView(tvBrand);
        titleCol.addView(tvGameTitle);
        titleCol.addView(tvSub);
        topBrandRow.addView(titleCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        headerCard.addView(topBrandRow);

        // صف الشارات العليا: الإصدار + إنستقرام R.D.AV (قابل للضغط)
        LinearLayout pillsRow = new LinearLayout(this);
        pillsRow.setOrientation(LinearLayout.HORIZONTAL);
        pillsRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams pillsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pillsLp.topMargin = dp(10);

        TextView verPill = makeText("v" + LauncherConfig.APP_VERSION + " • 1.0.21", 10.5f, LauncherConfig.COL_CYAN, true);
        verPill.setPadding(dp(10), dp(5), dp(10), dp(5));
        GradientDrawable verBg = new GradientDrawable();
        verBg.setColor(Color.parseColor("#1D1138"));
        verBg.setCornerRadius(dp(10));
        verBg.setStroke(dp(1), LauncherConfig.COL_BORDER);
        verPill.setBackground(verBg);
        LinearLayout.LayoutParams verLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        verLp.leftMargin = dp(8);
        pillsRow.addView(verPill, verLp);

        TextView igPill = makeText("Instagram: " + LauncherConfig.INSTAGRAM_HANDLE + " ↗", 11f, Color.WHITE, true);
        igPill.setPadding(dp(12), dp(5), dp(12), dp(5));
        GradientDrawable igBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.parseColor("#7C2D8C"), Color.parseColor("#C13584"), Color.parseColor("#D97706")}
        );
        igBg.setCornerRadius(dp(10));
        igBg.setStroke(dp(1), LauncherConfig.COL_GOLD);
        igPill.setBackground(igBg);
        igPill.setOnClickListener(v -> openUrl(LauncherConfig.INSTAGRAM_URL));
        pillsRow.addView(igPill);

        headerCard.addView(pillsRow, pillsLp);
        root.addView(headerCard, makeCardLp());

        // ---------------------------------------------------------------------
        // 2) بطاقة العرض الرئيسية بالفيديو التفاعلي المحلي الصامت (bg_promo.mp4)
        // ---------------------------------------------------------------------
        FrameLayout promoCard = new FrameLayout(this);
        GradientDrawable promoBorderBg = new GradientDrawable();
        promoBorderBg.setColor(LauncherConfig.COL_CARD_INNER);
        promoBorderBg.setCornerRadius(dp(18));
        promoBorderBg.setStroke(dp(1), LauncherConfig.COL_BORDER_GOLD);
        promoCard.setBackground(promoBorderBg);
        promoCard.setPadding(dp(1), dp(1), dp(1), dp(1));

        FrameLayout promoInner = new FrameLayout(this);
        clipRounded(promoInner, dp(17));

        // طبقة الصورة الاحتياطية عالية الدقة (تظهر فوراً قبل بدء الفيديو أو عند غياب الترميز)
        ImageView ivPoster = new ImageView(this);
        ivPoster.setImageResource(R.drawable.bg_promo_poster);
        ivPoster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        promoInner.addView(ivPoster, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // طبقة الفيديو المحلي الصامت المتكرر (TextureView)
        promoTextureView = new TextureView(this);
        promoTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                startPromoVideoOnSurface(surface);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                releasePromoVideo();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            }
        });
        promoInner.addView(promoTextureView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // طبقة تدرج ليلية أنيقة أسفل الفيديو لإبراز العنوان
        View scrim = new View(this);
        GradientDrawable scrimBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#1A080512"), Color.parseColor("#44080512"), Color.parseColor("#E60A0516")}
        );
        scrim.setBackground(scrimBg);
        promoInner.addView(scrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // شارة حصرية على بطاقة العرض
        LinearLayout overlayTextCol = new LinearLayout(this);
        overlayTextCol.setOrientation(LinearLayout.VERTICAL);
        overlayTextCol.setPadding(dp(14), dp(10), dp(14), dp(12));
        FrameLayout.LayoutParams overlayLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);

        TextView overlayTag = makeText("GOLDEN WIND • الإصدار الكامل", 10f, LauncherConfig.COL_GOLD, true);
        TextView overlayTitle = makeText("عِش مغامرة جوجو الغريبة... بالعربية الفصحى الكاملة", 13.5f, LauncherConfig.COL_TEXT, true);
        overlayTitle.setPadding(0, dp(2), 0, 0);
        overlayTextCol.addView(overlayTag);
        overlayTextCol.addView(overlayTitle);
        promoInner.addView(overlayTextCol, overlayLp);

        // أيقونة زر كتم/تشغيل الصوت في الزاوية (مخفي افتراضياً ويظهر عند لمس الفيديو ثم يتلاشى بعد ثانيتين)
        btnPromoAudio = makeText("🔊", 15f, Color.WHITE, true);
        btnPromoAudio.setGravity(Gravity.CENTER);
        GradientDrawable audioBg = new GradientDrawable();
        audioBg.setShape(GradientDrawable.OVAL);
        audioBg.setColor(Color.parseColor("#B30A0516"));
        audioBg.setStroke(dp(1), LauncherConfig.COL_GOLD);
        btnPromoAudio.setBackground(audioBg);
        btnPromoAudio.setAlpha(0f);
        btnPromoAudio.setVisibility(View.GONE);
        btnPromoAudio.setOnClickListener(v -> togglePromoAudioMute());

        FrameLayout.LayoutParams audioLp = new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.TOP | Gravity.LEFT);
        audioLp.setMargins(dp(10), dp(10), dp(10), dp(10));
        promoInner.addView(btnPromoAudio, audioLp);

        // لمس مساحة الفيديو يُظهر زر الصوت ويخفيه تلقائياً بعد 2000ms
        promoInner.setClickable(true);
        promoInner.setOnClickListener(v -> showPromoAudioControlTemporarily());

        promoCard.addView(promoInner, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(186)));
        root.addView(promoCard, makeCardLp());

        // ---------------------------------------------------------------------
        // 3) بطاقة حالة التعريب وحارس الأمان + شريط التقدم + مسار الخطوات (4 مراحل)
        // ---------------------------------------------------------------------
        LinearLayout statusCard = makeCard(LauncherConfig.COL_BORDER);

        LinearLayout statusHeader = new LinearLayout(this);
        statusHeader.setOrientation(LinearLayout.HORIZONTAL);
        statusHeader.setGravity(Gravity.CENTER_VERTICAL);
        statusHeader.addView(makeText("حالة التعريب وحارس الأمان", 13f, LauncherConfig.COL_CYAN, true),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tvProgressPercent = makeText("100%", 12.5f, LauncherConfig.COL_GOLD, true);
        statusHeader.addView(tvProgressPercent);
        statusCard.addView(statusHeader);

        tvHeadlineStatus = makeText("جارٍ فحص حالة اللعبة وحارس الأمان...", 15.5f, LauncherConfig.COL_GOLD, true);
        tvHeadlineStatus.setPadding(0, dp(6), 0, dp(2));
        statusCard.addView(tvHeadlineStatus);

        tvStatusText = makeText("حارس الأمان مفعّل: يتحقق من الإصدار 1.0.21 وبصمة الملفات تلقائياً.", 11.5f, LauncherConfig.COL_MUTED, false);
        statusCard.addView(tvStatusText);

        // شريط التقدم المتدرج (ذهبي -> بنفسجي)
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setProgressDrawable(makeProgressDrawable());
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8));
        pbLp.topMargin = dp(10);
        pbLp.bottomMargin = dp(10);
        statusCard.addView(progressBar, pbLp);

        // شريط المراحل الأربع (مستوحى من التصميم المرجعي)
        LinearLayout stepperRow = new LinearLayout(this);
        stepperRow.setOrientation(LinearLayout.HORIZONTAL);
        stepperRow.setGravity(Gravity.CENTER);
        tvStep1Dot = addStepItem(stepperRow, "فحص اللعبة");
        tvStep2Dot = addStepItem(stepperRow, "حارس الأمان");
        tvStep3Dot = addStepItem(stepperRow, "ربط Shizuku");
        tvStep4Dot = addStepItem(stepperRow, "التعريب");
        statusCard.addView(stepperRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // خط فاصل رقيق
        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#2A1B4D"));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        divLp.topMargin = dp(10);
        divLp.bottomMargin = dp(8);
        statusCard.addView(divider, divLp);

        // تفاصيل الحالة الحية
        tvGameBadge = addStatusRow(statusCard, "إصدار اللعبة (1.0.21)", "جارٍ الفحص...");
        tvSafetyBadge = addStatusRow(statusCard, "حارس الأمان (Safety Guard)", "جارٍ التحقق...");
        tvAccessBadge = addStatusRow(statusCard, "صلاحية الوصول (Shizuku / SAF)", "جارٍ الفحص...");
        tvPatchBadge = addStatusRow(statusCard, "حالة حزمة التعريب", "جارٍ الفحص...");

        btnGrantAccess = makeButton("تفعيل الربط التلقائي عبر Shizuku / SAF", LauncherConfig.COL_CARD_INNER, LauncherConfig.COL_GOLD, LauncherConfig.COL_ACCENT, true);
        btnGrantAccess.setVisibility(View.GONE);
        LinearLayout.LayoutParams grantLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        grantLp.topMargin = dp(8);
        btnGrantAccess.setOnClickListener(v -> requestBestAccessInteractive());
        statusCard.addView(btnGrantAccess, grantLp);

        root.addView(statusCard, makeCardLp());

        // ---------------------------------------------------------------------
        // 4) أزرار التحكم الرئيسية (الترتيب الفخم المستوحى من التصميم المرجعي)
        // ---------------------------------------------------------------------

        // 0. زر اختبار التعريب السري (يظهر فقط عند تفعيل وضع Beta بكلمة المرور!)
        btnInstallBeta = new Button(this);
        btnInstallBeta.setText("🧪  تحميل وتثبيت نسخة الاختبار (Beta Test)");
        btnInstallBeta.setAllCaps(false);
        btnInstallBeta.setTextSize(14f);
        btnInstallBeta.setTextColor(Color.WHITE);
        btnInstallBeta.setTypeface(fontBold);
        GradientDrawable betaBtnBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.parseColor("#581C87"), Color.parseColor("#9333EA"), Color.parseColor("#7E22CE")}
        );
        betaBtnBg.setCornerRadius(dp(14));
        betaBtnBg.setStroke(dp(1), Color.parseColor("#E9D5FF"));
        btnInstallBeta.setBackground(betaBtnBg);
        btnInstallBeta.setVisibility(View.GONE);
        btnInstallBeta.setOnClickListener(v -> startBetaInstallFlow());
        LinearLayout.LayoutParams betaLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        betaLp.bottomMargin = dp(9);
        root.addView(btnInstallBeta, betaLp);

        // 1. الزر الرئيسي البارز: ▶ تشغيل اللعبة (تدرج ذهبي -> بنفسجي مضيء)
        btnLaunchGame = new Button(this);
        btnLaunchGame.setText("▶  تشغيل اللعبة");
        btnLaunchGame.setAllCaps(false);
        btnLaunchGame.setTextSize(16f);
        btnLaunchGame.setTextColor(Color.parseColor("#0D061A"));
        btnLaunchGame.setTypeface(fontBold);
        GradientDrawable heroBtnBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{LauncherConfig.COL_GOLD, LauncherConfig.COL_ACCENT, Color.parseColor("#C084FC")}
        );
        heroBtnBg.setCornerRadius(dp(15));
        heroBtnBg.setStroke(dp(1), Color.parseColor("#FEF08A"));
        btnLaunchGame.setBackground(heroBtnBg);
        btnLaunchGame.setOnClickListener(v -> launchGameWithSafetyCheck());
        LinearLayout.LayoutParams launchLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        launchLp.bottomMargin = dp(9);
        root.addView(btnLaunchGame, launchLp);

        // 2. صف التثبيت: تثبيت / تحديث التعريب (ZIP) + التحديث أونلاين
        LinearLayout rowInstall = new LinearLayout(this);
        rowInstall.setOrientation(LinearLayout.HORIZONTAL);

        btnInstallLocalZip = makeButton("تثبيت / تحديث التعريب (ZIP)", Color.parseColor("#1D1236"), LauncherConfig.COL_GOLD, LauncherConfig.COL_BORDER_GOLD, true);
        btnInstallLocalZip.setTextSize(12.5f);
        btnInstallLocalZip.setOnClickListener(v -> openLocalZipPicker());
        LinearLayout.LayoutParams zipLp = new LinearLayout.LayoutParams(0, dp(48), 1.15f);
        zipLp.leftMargin = dp(6);
        rowInstall.addView(btnInstallLocalZip, zipLp);

        btnInstallOnline = makeButton("تحديث أونلاين (GitHub)", Color.parseColor("#18102E"), LauncherConfig.COL_CYAN, LauncherConfig.COL_BORDER, true);
        btnInstallOnline.setTextSize(12.5f);
        btnInstallOnline.setOnClickListener(v -> startOnlineInstallFlow());
        LinearLayout.LayoutParams onlineLp = new LinearLayout.LayoutParams(0, dp(48), 0.85f);
        onlineLp.rightMargin = dp(6);
        rowInstall.addView(btnInstallOnline, onlineLp);

        LinearLayout.LayoutParams rowInstallLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowInstallLp.bottomMargin = dp(9);
        root.addView(rowInstall, rowInstallLp);

        // 3. صف الأدوات: فحص سلامة الملفات + استعادة النسخة الأصلية
        LinearLayout rowTools = new LinearLayout(this);
        rowTools.setOrientation(LinearLayout.HORIZONTAL);

        btnDiagScan = makeButton("فحص سلامة الملفات", LauncherConfig.COL_CARD, LauncherConfig.COL_TEXT, LauncherConfig.COL_BORDER, true);
        btnDiagScan.setTextSize(12.5f);
        btnDiagScan.setOnClickListener(v -> runDiagnosticScanFlow());
        LinearLayout.LayoutParams diagLp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        diagLp.leftMargin = dp(6);
        rowTools.addView(btnDiagScan, diagLp);

        btnRestore = makeButton("استعادة النسخة الأصلية", Color.parseColor("#2B0E1E"), LauncherConfig.COL_DANGER, Color.parseColor("#7F1D3A"), true);
        btnRestore.setTextSize(12.5f);
        btnRestore.setOnClickListener(v -> confirmAndRestore());
        LinearLayout.LayoutParams restLp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        restLp.rightMargin = dp(6);
        rowTools.addView(btnRestore, restLp);

        LinearLayout.LayoutParams rowToolsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowToolsLp.bottomMargin = dp(10);
        root.addView(rowTools, rowToolsLp);

        // ---------------------------------------------------------------------
        // 5) شريط إحصائيات التعريب الفعلية (شبكة 2×2 مطابقة للتصميم المرجعي)
        // ---------------------------------------------------------------------
        LinearLayout statsCard = makeCard(LauncherConfig.COL_BORDER);
        TextView statsTitle = makeText("إحصائيات حزمة تعريب R.D.AV", 12.5f, LauncherConfig.COL_GOLD, true);
        statsTitle.setPadding(0, 0, 0, dp(8));
        statsCard.addView(statsTitle);

        LinearLayout statsRow1 = new LinearLayout(this);
        statsRow1.setOrientation(LinearLayout.HORIZONTAL);
        statsRow1.addView(makeStatChip(LauncherConfig.STAT_LINES, LauncherConfig.COL_GOLD), makeHalfLp(true));
        statsRow1.addView(makeStatChip(LauncherConfig.STAT_SCOPE, LauncherConfig.COL_CYAN), makeHalfLp(false));
        LinearLayout.LayoutParams sr1Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sr1Lp.bottomMargin = dp(7);
        statsCard.addView(statsRow1, sr1Lp);

        LinearLayout statsRow2 = new LinearLayout(this);
        statsRow2.setOrientation(LinearLayout.HORIZONTAL);
        statsRow2.addView(makeStatChip(LauncherConfig.STAT_COMPAT, LauncherConfig.COL_SUCCESS), makeHalfLp(true));
        statsRow2.addView(makeStatChip(LauncherConfig.STAT_SAFETY, LauncherConfig.COL_TEXT), makeHalfLp(false));
        statsCard.addView(statsRow2);

        root.addView(statsCard, makeCardLp());

        // ---------------------------------------------------------------------
        // 6) بطاقة التحديثات أونلاين والتذييل
        // ---------------------------------------------------------------------
        LinearLayout updateCard = makeCard(LauncherConfig.COL_BORDER);
        LinearLayout updateHeader = new LinearLayout(this);
        updateHeader.setOrientation(LinearLayout.HORIZONTAL);
        updateHeader.setGravity(Gravity.CENTER_VERTICAL);
        updateHeader.addView(makeText("تحديثات GitHub Releases", 12f, LauncherConfig.COL_CYAN, true),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        btnCheckUpdate = makeButton("فحص التحديث", LauncherConfig.COL_CARD_INNER, LauncherConfig.COL_GOLD, LauncherConfig.COL_BORDER_GOLD, true);
        btnCheckUpdate.setTextSize(10.5f);
        btnCheckUpdate.setPadding(dp(10), dp(2), dp(10), dp(2));
        btnCheckUpdate.setOnClickListener(v -> checkOnlineUpdateAsync(true));
        updateHeader.addView(btnCheckUpdate, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)));
        updateCard.addView(updateHeader);

        tvRemoteInfo = makeText("اضغط «تثبيت / تحديث التعريب (ZIP)» لاختيار ملف jojo_full_arabic_v3.zip أو «فحص التحديث» للتحقق أونلاين.", 11f, LauncherConfig.COL_MUTED, false);
        tvRemoteInfo.setPadding(0, dp(6), 0, 0);
        tvRemoteInfo.setLineSpacing(dp(2), 1f);
        updateCard.addView(tvRemoteInfo);
        root.addView(updateCard, makeCardLp());

        // تذييل مع روابط سريعة
        LinearLayout footerRow = new LinearLayout(this);
        footerRow.setOrientation(LinearLayout.HORIZONTAL);
        footerRow.setGravity(Gravity.CENTER);
        footerRow.setPadding(0, dp(2), 0, dp(8));

        TextView tvFooter = makeText(LauncherConfig.FOOTER_TEXT + "  •  ", 10.5f, LauncherConfig.COL_MUTED, false);
        TextView tvGitHubLink = makeText("GitHub", 10.5f, LauncherConfig.COL_CYAN, true);
        tvGitHubLink.setOnClickListener(v -> openUrl(LauncherConfig.COMMUNITY_URL));
        TextView tvSep = makeText("  •  ", 10.5f, LauncherConfig.COL_MUTED, false);
        TextView tvIgFooter = makeText("Instagram: " + LauncherConfig.INSTAGRAM_HANDLE, 10.5f, LauncherConfig.COL_GOLD, true);
        tvIgFooter.setOnClickListener(v -> openUrl(LauncherConfig.INSTAGRAM_URL));

        footerRow.addView(tvFooter);
        footerRow.addView(tvGitHubLink);
        footerRow.addView(tvSep);
        footerRow.addView(tvIgFooter);
        root.addView(footerRow);

        return scroll;
    }

    private TextView addStepItem(LinearLayout parent, String title) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView dot = makeText("•", 12f, LauncherConfig.COL_MUTED, true);
        dot.setGravity(Gravity.CENTER);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(Color.parseColor("#241740"));
        dotBg.setStroke(dp(1), LauncherConfig.COL_BORDER);
        dot.setBackground(dotBg);
        col.addView(dot, new LinearLayout.LayoutParams(dp(24), dp(24)));

        TextView lbl = makeText(title, 9.5f, LauncherConfig.COL_MUTED, false);
        lbl.setGravity(Gravity.CENTER);
        lbl.setPadding(0, dp(4), 0, 0);
        col.addView(lbl);

        parent.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return dot;
    }

    private void setStepDotState(TextView dot, boolean ok, boolean danger) {
        if (dot == null) {
            return;
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        if (danger) {
            bg.setColor(Color.parseColor("#4C1022"));
            bg.setStroke(dp(1), LauncherConfig.COL_DANGER);
            dot.setText("!");
            dot.setTextColor(LauncherConfig.COL_DANGER);
        } else if (ok) {
            bg.setColor(Color.parseColor("#113D27"));
            bg.setStroke(dp(1), LauncherConfig.COL_SUCCESS);
            dot.setText("✓");
            dot.setTextColor(LauncherConfig.COL_SUCCESS);
        } else {
            bg.setColor(Color.parseColor("#241740"));
            bg.setStroke(dp(1), LauncherConfig.COL_BORDER);
            dot.setText("•");
            dot.setTextColor(LauncherConfig.COL_MUTED);
        }
        dot.setBackground(bg);
    }

    private TextView makeStatChip(String text, int accentColor) {
        TextView tv = makeText(text, 10.5f, accentColor, true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(8), dp(9), dp(8), dp(9));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(LauncherConfig.COL_CARD_INNER);
        bg.setCornerRadius(dp(11));
        bg.setStroke(dp(1), Color.parseColor("#2D1D52"));
        tv.setBackground(bg);
        return tv;
    }

    private LinearLayout.LayoutParams makeHalfLp(boolean isFirstInRtl) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        if (isFirstInRtl) {
            lp.leftMargin = dp(4);
        } else {
            lp.rightMargin = dp(4);
        }
        return lp;
    }

    private void clipRounded(View view, final int radiusPx) {
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radiusPx);
            }
        });
        view.setClipToOutline(true);
    }

    private TextView addStatusRow(LinearLayout parent, String label, String initialBadge) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));

        TextView tvLabel = makeText(label, 11.5f, LauncherConfig.COL_TEXT, false);
        row.addView(tvLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvBadge = makeText(initialBadge, 10.5f, LauncherConfig.COL_CYAN, true);
        tvBadge.setPadding(dp(9), dp(3), dp(9), dp(3));
        setBadgeStyle(tvBadge, Color.parseColor("#22153D"), LauncherConfig.COL_CYAN);
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

    private LinearLayout makeCard(int borderColor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(LauncherConfig.COL_CARD);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), borderColor);
        card.setBackground(bg);
        return card;
    }

    private LinearLayout.LayoutParams makeCardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        return lp;
    }

    private TextView makeText(String text, float sizeSp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sizeSp);
        tv.setTextColor(color);
        tv.setTypeface(bold ? fontBold : fontRegular);
        return tv;
    }

    private Button makeButton(String text, int bgColor, int textColor, int borderColor, boolean bold) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(12.5f);
        btn.setTextColor(textColor);
        btn.setTypeface(bold ? fontBold : fontRegular);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(13));
        bg.setStroke(dp(1), borderColor);
        btn.setBackground(bg);
        return btn;
    }

    private Drawable makeProgressDrawable() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(LauncherConfig.COL_CARD_INNER);
        bg.setCornerRadius(dp(6));
        GradientDrawable fill = new GradientDrawable(
                GradientDrawable.Orientation.RIGHT_LEFT,
                new int[]{LauncherConfig.COL_GOLD, LauncherConfig.COL_ACCENT, LauncherConfig.COL_PURPLE}
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
    // حارس الأمان وفحص التحديثات (Version Safety Guard) — مطابق 100% لـ v1.0.5
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

        if (installedVc > 0 && targetVc > 0 && installedVc != targetVc) {
            mismatchReason = "إصدار اللعبة المثبّت (v" + (installedVerName != null ? installedVerName : "?")
                    + " / code=" + installedVc + ") يختلف عن الإصدار المدعوم للتعريب (v"
                    + (manifestOpt != null ? manifestOpt.gameVersion : LauncherConfig.TARGET_GAME_VERSION_NAME)
                    + " / code=" + targetVc + ").";
        }

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
    // تحديث الحالة والفحص الحي + تحديث الخطوات الأربعة
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
            final boolean accessOk = isReadyAccessMode(mode);

            mainHandler.post(() -> {
                // تحديث الخطوات الأربع وشريط التقدم الرئيسي
                setStepDotState(tvStep1Dot, gameInstalled, !gameInstalled);
                setStepDotState(tvStep2Dot, gameInstalled && fSafety.safe, gameInstalled && !fSafety.safe);
                setStepDotState(tvStep3Dot, accessOk, false);
                setStepDotState(tvStep4Dot, fPatchInstalled && fSafety.safe, false);

                if (!isBusy) {
                    if (!gameInstalled) {
                        tvHeadlineStatus.setText("اللعبة غير مثبّتة على الجهاز");
                        tvHeadlineStatus.setTextColor(LauncherConfig.COL_DANGER);
                        progressBar.setProgress(0);
                        tvProgressPercent.setText("0%");
                    } else if (!fSafety.safe) {
                        tvHeadlineStatus.setText("تم اكتشاف تحديث جديد للعبة!");
                        tvHeadlineStatus.setTextColor(LauncherConfig.COL_DANGER);
                        tvStatusText.setText(LauncherConfig.SAFETY_GUARD_ALERT_MESSAGE);
                        progressBar.setProgress(25);
                        tvProgressPercent.setText("تنبيه");
                    } else if (fPatchInstalled) {
                        tvHeadlineStatus.setText("التعريب مثبّت وسليم ✓");
                        tvHeadlineStatus.setTextColor(LauncherConfig.COL_SUCCESS);
                        tvStatusText.setText("جميع الملفات والبصمات متطابقة ومحمية بحارس الأمان. يمكنك تشغيل اللعبة الآن!");
                        progressBar.setProgress(100);
                        tvProgressPercent.setText("100%");
                    } else if (accessOk) {
                        tvHeadlineStatus.setText("جاهز لتثبيت التعريب الكامل");
                        tvHeadlineStatus.setTextColor(LauncherConfig.COL_GOLD);
                        tvStatusText.setText("اللعبة وصلاحية الوصول جاهزتان — اختر ملف التعريب (ZIP) للتثبيت الفوري.");
                        progressBar.setProgress(75);
                        tvProgressPercent.setText("75%");
                    } else {
                        tvHeadlineStatus.setText("بانتظار تفعيل Shizuku / SAF");
                        tvHeadlineStatus.setTextColor(LauncherConfig.COL_WARN);
                        tvStatusText.setText("اضغط زر التفعيل بالأسفل لربط مجلد اللعبة لمرة واحدة فقط.");
                        progressBar.setProgress(50);
                        tvProgressPercent.setText("50%");
                    }
                }

                if (gameInstalled) {
                    String text = (gameVer != null && !gameVer.isEmpty())
                            ? "v" + gameVer + " (#" + gameVc + ") ✓"
                            : "مثبّتة وجاهزة ✓";
                    tvGameBadge.setText(text);
                    setBadgeStyle(tvGameBadge, Color.parseColor("#113826"), LauncherConfig.COL_SUCCESS);
                } else {
                    tvGameBadge.setText("غير مثبّتة");
                    setBadgeStyle(tvGameBadge, Color.parseColor("#3D1222"), LauncherConfig.COL_DANGER);
                }

                if (!gameInstalled) {
                    tvSafetyBadge.setText("بانتظار اللعبة");
                    setBadgeStyle(tvSafetyBadge, Color.parseColor("#22153D"), LauncherConfig.COL_MUTED);
                } else if (fSafety.safe) {
                    tvSafetyBadge.setText("متوافق وآمن ✓");
                    setBadgeStyle(tvSafetyBadge, Color.parseColor("#113826"), LauncherConfig.COL_SUCCESS);
                } else {
                    tvSafetyBadge.setText("تحديث غير متوافق!");
                    setBadgeStyle(tvSafetyBadge, Color.parseColor("#3D1222"), LauncherConfig.COL_DANGER);
                    if (!safetyAlertShownThisSession) {
                        safetyAlertShownThisSession = true;
                        showSafetyGuardAlertDialog(fSafety.reason);
                    }
                }

                boolean canAutoSaf = SafHelper.canAutoJumpToGameFolder(this);
                switch (mode) {
                    case BRIDGE_ACTIVE:
                        tvAccessBadge.setText("Shizuku متصل ✓");
                        setBadgeStyle(tvAccessBadge, Color.parseColor("#113826"), LauncherConfig.COL_SUCCESS);
                        btnGrantAccess.setVisibility(View.GONE);
                        break;
                    case DIRECT_FS:
                        tvAccessBadge.setText("وصول مباشر ✓");
                        setBadgeStyle(tvAccessBadge, Color.parseColor("#113826"), LauncherConfig.COL_SUCCESS);
                        btnGrantAccess.setVisibility(View.GONE);
                        break;
                    case SAF_GRANTED:
                        tvAccessBadge.setText("SAF مربوط ✓");
                        setBadgeStyle(tvAccessBadge, Color.parseColor("#113826"), LauncherConfig.COL_SUCCESS);
                        btnGrantAccess.setVisibility(View.GONE);
                        break;
                    case SHIZUKU_READY:
                    case SHIZUKU_NEED_PERM:
                        tvAccessBadge.setText("جاهز بضغطة واحدة");
                        setBadgeStyle(tvAccessBadge, Color.parseColor("#3D2A0D"), LauncherConfig.COL_GOLD);
                        btnGrantAccess.setText("منح إذن Shizuku الآن");
                        btnGrantAccess.setVisibility(View.VISIBLE);
                        break;
                    case SHIZUKU_STOPPED:
                        if (canAutoSaf) {
                            tvAccessBadge.setText("متاح عبر SAF أو Shizuku");
                            setBadgeStyle(tvAccessBadge, Color.parseColor("#22153D"), LauncherConfig.COL_CYAN);
                            btnGrantAccess.setText("تفعيل الربط التلقائي");
                            btnGrantAccess.setVisibility(View.VISIBLE);
                        } else {
                            tvAccessBadge.setText("شغّل Shizuku أولاً");
                            setBadgeStyle(tvAccessBadge, Color.parseColor("#3D2A0D"), LauncherConfig.COL_WARN);
                            btnGrantAccess.setText("فتح تطبيق Shizuku");
                            btnGrantAccess.setVisibility(View.VISIBLE);
                        }
                        break;
                    default:
                        if (canAutoSaf) {
                            tvAccessBadge.setText("ربط مباشر عبر SAF");
                            setBadgeStyle(tvAccessBadge, Color.parseColor("#22153D"), LauncherConfig.COL_CYAN);
                            btnGrantAccess.setText("تحديد مجلد اللعبة تلقائياً");
                            btnGrantAccess.setVisibility(View.VISIBLE);
                        } else {
                            tvAccessBadge.setText("يتطلب Shizuku");
                            setBadgeStyle(tvAccessBadge, Color.parseColor("#3D2A0D"), LauncherConfig.COL_WARN);
                            btnGrantAccess.setText("إعداد الوصول عبر Shizuku");
                            btnGrantAccess.setVisibility(View.VISIBLE);
                        }
                        break;
                }

                if (fPatchInstalled) {
                    String verLabel = fPatchVer.isEmpty() ? "مثبّت ومفعّل ✓" : ("مثبّت (" + fPatchVer + ") ✓");
                    tvPatchBadge.setText(verLabel);
                    setBadgeStyle(tvPatchBadge, Color.parseColor("#113826"), LauncherConfig.COL_SUCCESS);
                } else if (accessOk && gameInstalled && !fGameReady) {
                    tvPatchBadge.setText("شغّل اللعبة مرة أولاً");
                    setBadgeStyle(tvPatchBadge, Color.parseColor("#3D2A0D"), LauncherConfig.COL_WARN);
                } else {
                    tvPatchBadge.setText("غير مثبّت حالياً");
                    setBadgeStyle(tvPatchBadge, Color.parseColor("#22153D"), LauncherConfig.COL_CYAN);
                }

                lastSafetyOk = fSafety.safe;
                updateRemoteCardUi();
                refreshHomeCards();
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
                } else if (action == PendingAction.BETA_INSTALL) {
                    startBetaInstallFlow();
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
            mainHandler.post(() -> tvRemoteInfo.setText("جارٍ الاتصال بـ GitHub للتحقق من آخر إصدار..."));
        }
        worker.execute(() -> {
            try {
                PatchManifest manifest = PatchDownloader.fetchManifestForProfile(activeProfile);
                latestRemoteManifest = manifest;
                mainHandler.post(this::updateRemoteCardUi);
            } catch (Throwable t) {
                if (userTriggered) {
                    String err = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                    mainHandler.post(() -> tvRemoteInfo.setText("لا يوجد إصدار أونلاين منشور حالياً (" + err + ") — استخدم التثبيت من ملف ZIP."));
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
        sb.append("أحدث إصدار أونلاين: v").append(m.patchVersion);
        if (!m.gameVersion.isEmpty()) {
            sb.append(" • متوافق مع v").append(m.gameVersion)
                    .append(" (#").append(m.targetGameVersionCode).append(")");
        }
        if (!m.releaseDate.isEmpty()) {
            sb.append(" (").append(m.releaseDate).append(")");
        }
        if (isPatchCurrentlyInstalled && !installedPatchVersion.isEmpty()
                && !installedPatchVersion.equals(m.patchVersion)) {
            sb.append("\n★ يتوفر إصدار تعريب أحدث من المثبّت حالياً!");
        }
        if (!m.changelog.isEmpty()) {
            sb.append("\n• ").append(m.changelog);
        }
        tvRemoteInfo.setText(sb.toString());
        tvRemoteInfo.setTextColor(LauncherConfig.COL_TEXT);
        refreshHomeCards();
    }

    // =========================================================================
    // تدفقات التثبيت المحمية بحارس الأمان + الاستعادة + الفحص التشخيصي
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
            Toast.makeText(this, "استخدم زر «تثبيت / تحديث التعريب (ZIP)» لاختيار ملف التعريب المحلي.", Toast.LENGTH_LONG).show();
            return;
        }

        worker.execute(() -> {
            AccessMode mode = detectAccessMode();
            if (!isReadyAccessMode(mode)) {
                pendingAction = PendingAction.ONLINE_INSTALL;
                mainHandler.post(this::requestBestAccessInteractive);
                return;
            }

            setBusyUi(true, "جارٍ جلب معلومات آخر تحديث وفحص حارس الأمان...", 5);
            File workDir = new File(getHintDir(), "jojo_gh_patch_work");
            File stageDir = new File(workDir, "stage");
            File zipFile = new File(workDir, "patch.zip");

            try {
                ShellBridgeServer.deleteRecursive(workDir);
                stageDir.mkdirs();

                PatchManifest manifest = PatchDownloader.fetchManifestForProfile(activeProfile);
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

                updateProgress(15, "جارٍ استخراج حزمة التعريب...");
                ShellBridgeServer.deleteRecursive(workDir);
                stageDir.mkdirs();

                PatchDownloader.extractZipUri(this, zipUri, stageDir, this::updateProgress, 20, 58);
                File effectiveStage = PatchDownloader.resolveEffectiveStageRoot(stageDir);
                executeInstallFromStage(mode, effectiveStage, "v3-full", LauncherConfig.GAME_TARGET_SUBPATH);
            } catch (Throwable t) {
                showErrorState("فشل تثبيت الحزمة: " + readableError(t));
            } finally {
                ShellBridgeServer.deleteRecursive(workDir);
                setBusyUi(false, null, -1);
                refreshStatusAsync(null);
            }
        });
    }

    /**
     * تدفق تحميل وتثبيت نسخة الاختبار السرية (Beta Test — manifest_beta.json / GitHub Pre-release)
     * عبر استراتيجية الحقن الخاصة باللعبة النشطة دون التأثير على قناة المستخدمين العاديين.
     */
    private void startBetaInstallFlow() {
        if (isBusy) {
            return;
        }
        if (!BetaModeManager.isBetaEnabled(this)) {
            showBetaPasswordDialog();
            return;
        }
        if (!isAnyGameInstalled()) {
            Toast.makeText(this, "يرجى تثبيت لعبة " + activeProfile.title + " أولاً على الجهاز.", Toast.LENGTH_LONG).show();
            return;
        }

        worker.execute(() -> {
            AccessMode mode = detectAccessMode();
            if (!isReadyAccessMode(mode)) {
                pendingAction = PendingAction.BETA_INSTALL;
                mainHandler.post(this::requestBestAccessInteractive);
                return;
            }

            setBusyUi(true, "🧪 جارٍ جلب أحدث نسخة اختبار (Beta Pre-release) من GitHub...", 6);
            File workDir = new File(getHintDir(), "rdav_beta_patch_work");
            File stageDir = new File(workDir, "stage");
            File zipFile = new File(workDir, "beta_patch.zip");

            try {
                ShellBridgeServer.deleteRecursive(workDir);
                stageDir.mkdirs();

                PatchManifest betaManifest = PatchDownloader.fetchBetaManifest(activeProfile);
                updateProgress(12, "🧪 تم العثور على نسخة الاختبار (" + betaManifest.patchVersion + ") — جارٍ التحميل...");

                PatchDownloader.downloadToFile(
                        betaManifest.downloadUrl,
                        zipFile,
                        betaManifest.sizeBytes,
                        this::updateProgress,
                        15,
                        50
                );

                updateProgress(52, "🧪 جارٍ التحقق من بصمة SHA-256 لنسخة الاختبار...");
                PatchDownloader.verifySha256IfProvided(zipFile, betaManifest.sha256);

                PatchDownloader.extractZipFile(zipFile, stageDir, this::updateProgress, 55, 68);
                File effectiveStage = PatchDownloader.resolveEffectiveStageRoot(stageDir);

                executeInstallFromStage(
                        mode,
                        effectiveStage,
                        "beta-" + betaManifest.patchVersion,
                        betaManifest.resolveEffectiveSubpath()
                );
                mainHandler.post(() -> Toast.makeText(
                        this,
                        "🧪 تم تثبيت نسخة الاختبار (" + betaManifest.patchVersion + ") بنجاح — يمكنك تجربة اللعبة الآن ✓",
                        Toast.LENGTH_LONG
                ).show());
            } catch (Throwable t) {
                showErrorState("تعذر تثبيت نسخة الاختبار (Beta): " + readableError(t));
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
        // استدعاء محرك/استراتيجية الحقن الخاصة باللعبة المختارة دون أي تداخل
        PatchStrategy strategy = activeProfile.patchStrategy;
        strategy.validateStagedPayload(stageDir, activeProfile);

        ShellBridgeServer.ProgressListener listener = this::updateProgress;
        String normSubpath = strategy.resolveDefaultTargetSubpath(targetSubpath);
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
        updateProgress(100, "اكتمل تثبيت تعريب R.D.AV بنجاح (" + fileCount + " ملف) مع حفظ النسخة الاحتياطية ✓");
        mainHandler.post(() -> Toast.makeText(this, "تم تثبيت تعريب JoJo: Golden Hymn بنجاح ✓", Toast.LENGTH_LONG).show());
    }

    private void confirmAndRestore() {
        if (isBusy) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        b.setTitle("استعادة النسخة الأصلية");
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
                    Toast.makeText(this, "يلزم تفعيل Shizuku لفحص سلامة الملفات وبصمة MD5.", Toast.LENGTH_LONG).show();
                    requestBestAccessInteractive();
                });
                return;
            }
            setBusyUi(true, "جارٍ فحص سلامة ملفات JoJo: Golden Hymn وبصمة MD5...", 40);
            try {
                String resp = (mode == AccessMode.BRIDGE_ACTIVE)
                        ? sendBridgeCommand("SCAN_DIAG\t" + getHintDir().getAbsolutePath(), 30000)
                        : ShellBridgeServer.runDiagnosticScan(getHintDir());
                if (resp == null || !resp.startsWith("OK|")) {
                    throw new IllegalStateException("تعذر إتمام الفحص: " + resp);
                }
                String report = resp.substring(3).replace("\\n", "\n");
                updateProgress(100, "اكتمل فحص سلامة الملفات بنجاح ✓");
                mainHandler.post(() -> showDiagnosticReportDialog(report));
            } catch (Throwable t) {
                showErrorState("خطأ أثناء الفحص: " + readableError(t));
            } finally {
                setBusyUi(false, null, -1);
                refreshStatusAsync(null);
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
        b.setTitle("تقرير فحص سلامة ملفات JoJo: Golden Hymn");
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
            tvHeadlineStatus.setText("تنبيه أثناء العملية");
            tvHeadlineStatus.setTextColor(LauncherConfig.COL_WARN);
            tvStatusText.setText(msg);
            tvProgressPercent.setText("تنبيه");
        });
    }

    private void setBusyUi(boolean busy, String statusMsg, int percent) {
        isBusy = busy;
        mainHandler.post(() -> {
            btnInstallOnline.setEnabled(!busy);
            btnInstallLocalZip.setEnabled(!busy);
            if (btnInstallBeta != null) {
                btnInstallBeta.setEnabled(!busy);
            }
            if (btnBackToLibrary != null) {
                btnBackToLibrary.setEnabled(!busy);
            }
            if (btnDiagScan != null) {
                btnDiagScan.setEnabled(!busy);
            }
            btnRestore.setEnabled(!busy);
            btnGrantAccess.setEnabled(!busy);
            btnCheckUpdate.setEnabled(!busy);
            btnLaunchGame.setEnabled(!busy);
            if (busy) {
                tvHeadlineStatus.setText("جارٍ التنفيذ...");
                tvHeadlineStatus.setTextColor(LauncherConfig.COL_GOLD);
            }
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
