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
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.content.res.AssetFileDescriptor;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.MediaPlayer;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
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
    private static final int REQ_PICK_FEEDBACK_IMAGES = 1006;
    private static final int MAX_FEEDBACK_IMAGES = 3;

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
        final boolean otaSyncNeeded;
        final boolean otaAutoSynced;
        final String reason;
        final String otaDetail;

        SafetyCheckResult(boolean safe, boolean hasBackup, String reason) {
            this(safe, hasBackup, false, false, reason, "");
        }

        SafetyCheckResult(
                boolean safe,
                boolean hasBackup,
                boolean otaSyncNeeded,
                boolean otaAutoSynced,
                String reason,
                String otaDetail
        ) {
            this.safe = safe;
            this.hasBackup = hasBackup;
            this.otaSyncNeeded = otaSyncNeeded;
            this.otaAutoSynced = otaAutoSynced;
            this.reason = reason != null ? reason : "";
            this.otaDetail = otaDetail != null ? otaDetail : "";
        }
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService iconExecutor = Executors.newSingleThreadExecutor();

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
    private Button btnInstallBeta;
    private int secretTapCount = 0;
    private long lastSecretTapMs = 0L;
    private String lastHomeRenderSignature = "";
    private String lastRenderedShotsSignature = "";
    private String lastHeroBadgeSessionGameId = "";

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
    private View btnInstallOnline;
    private View btnInstallLocalZip;
    private View btnDiagScan;
    private Button btnLaunchGame;
    private View btnRestore;
    private View btnFeedback;
    private ImageView ivHubLogo;
    private ImageView ivHubPoster;
    private LinearLayout hubRemoteSecurityBanner;
    private TextView tvRemoteSecurityTitle;
    private TextView tvRemoteSecurityMsg;
    private volatile File activeRemoteHeroVideoFile = null;
    private volatile boolean usingRemoteBannerImage = false;
    private AlertDialog liveSyncDialog;
    private TextView tvStatLinesChip;
    private TextView tvStatSizeChip;
    private TextView tvStatCompatChip;
    private TextView tvStoreScorePill;
    private TextView tvStoreInstallsPill;
    private TextView tvStoreDevPill;
    private TextView tvStoreSummary;
    private TextView tvEditorialVersionBox;
    private TextView tvEditorialCompatText;
    private TextView tvHubEyebrow;
    private TextView tvHubTitle;
    private TextView tvHubSubtitle;
    private TextView tvHubBadgeLabel;
    private LinearLayout hubSocialPill;
    private String activeSocialUrl = "";
    private LinearLayout storeScreenshotsSection;
    private HorizontalScrollView storeScreenshotsScroll;
    private LinearLayout storeScreenshotsRow;
    private FrameLayout screenshotLightboxOverlay;
    private FrameLayout lightboxCardWrap;
    private ImageView ivLightboxImage;
    private TextView tvLightboxHint;
    private View lightboxOriginView;
    private boolean isLightboxOpen = false;
    private boolean isLightboxAnimating = false;
    private LinearLayout changelogCardsContainer;
    private View heroDevBadge;
    private final Runnable heroBadgeHideRunnable = () -> {
        if (heroDevBadge == null) {
            return;
        }
        heroDevBadge.animate().cancel();
        heroDevBadge.animate()
                .translationX(dp(220))
                .alpha(0f)
                .setDuration(450L)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> {
                    if (heroDevBadge != null) {
                        heroDevBadge.setVisibility(View.INVISIBLE);
                    }
                })
                .start();
    };
    private final Runnable heroBadgeShowRunnable = () -> {
        if (heroDevBadge == null || !isOnGameHub) {
            return;
        }
        heroDevBadge.animate().cancel();
        heroDevBadge.setTranslationX(dp(220));
        heroDevBadge.setAlpha(0f);
        heroDevBadge.setVisibility(View.VISIBLE);
        heroDevBadge.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(450L)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    if (isOnGameHub) {
                        mainHandler.removeCallbacks(heroBadgeHideRunnable);
                        mainHandler.postDelayed(heroBadgeHideRunnable, 3000L);
                    }
                })
                .start();
    };
    private volatile String lastInstallFailureReason = "";

    // حالة نافذة «اقتراح أو الإبلاغ عن مشكلة» (بحد أقصى 3 صور)
    private final List<File> feedbackSelectedFiles = new ArrayList<>();
    private LinearLayout feedbackThumbsRow;
    private TextView feedbackAttachCountText;
    private View feedbackAttachBtn;

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
        latestRemoteManifest = PatchManifest.loadFromCache(this, activeProfile.id);

        try {
            Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener);
            Shizuku.addBinderDeadListener(shizukuBinderDeadListener);
            Shizuku.addRequestPermissionResultListener(shizukuPermListener);
        } catch (Throwable ignored) {
        }

        setContentView(buildRootContainer());
        applyDynamicMetadataToUi();
        if (latestRemoteManifest != null) {
            syncDynamicMediaForManifest(activeProfile, latestRemoteManifest);
        } else {
            syncStoreScreenshotsForProfile(activeProfile, null);
        }
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
        if (isLightboxOpen) {
            collapseScreenshotLightbox();
            return;
        }
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
        cancelHeroDevBadgeAnimation();
        pausePromoVideo();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacks(hidePromoAudioRunnable);
        mainHandler.removeCallbacks(promoFadeRunnable);
        cancelHeroDevBadgeAnimation();
        dismissLiveSyncLoadingDialog();
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
        // إذا كان البانر المحدّث عن بُعد صورة ثابتة (وليس فيديو)، نُبرز الصورة الحية بوضوح فوق البانر
        if (usingRemoteBannerImage && activeRemoteHeroVideoFile == null) {
            if (promoTextureView != null) {
                promoTextureView.setVisibility(View.GONE);
            }
            return;
        }
        try {
            MediaPlayer mp = new MediaPlayer();
            if (activeRemoteHeroVideoFile != null && activeRemoteHeroVideoFile.isFile()) {
                mp.setDataSource(activeRemoteHeroVideoFile.getAbsolutePath());
            } else {
                AssetFileDescriptor afd = getResources().openRawResourceFd(R.raw.bg_promo);
                if (afd == null) {
                    return;
                }
                mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                afd.close();
            }

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
        if (usingRemoteBannerImage && activeRemoteHeroVideoFile == null) {
            return;
        }
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

        screenshotLightboxOverlay = buildScreenshotLightboxOverlay();
        rootHost.addView(screenshotLightboxOverlay, new FrameLayout.LayoutParams(
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

    private boolean isProfileAccessible(GameProfile profile) {
        if (profile == null) {
            return false;
        }
        if (!profile.comingSoon) {
            return true;
        }
        return BetaModeManager.isBetaEnabled(this) && !profile.githubRepo.isEmpty();
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
        lastHomeRenderSignature = "";
        refreshHomeCards();
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

        // الترويسة التحريرية الفخمة (Luxury Dark-Teal & Gold) مع البوابة السرية على شعار R.D.AV
        LinearLayout studioCard = new LinearLayout(this);
        studioCard.setOrientation(LinearLayout.VERTICAL);
        studioCard.setPadding(dp(14), dp(14), dp(14), dp(12));
        GradientDrawable sBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor("#0F2330"), LauncherConfig.COL_CARD, Color.parseColor("#08141C")}
        );
        sBg.setCornerRadius(dp(11));
        sBg.setStroke(dp(1), LauncherConfig.COL_BORDER);
        studioCard.setBackground(sBg);

        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setOrientation(LinearLayout.HORIZONTAL);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);

        // شعار R.D.AV الذهبي المؤطر — الضغط 5 مرات متتالية أو الضغط المطوّل يفتح بوابة Beta السرية!
        View secretEmblem = makeRdavFramedEmblem(54, true);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(54), dp(54));
        logoLp.leftMargin = dp(12);
        brandRow.addView(secretEmblem, logoLp);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView eyebrow = makeText("R.D.AV • ARABIC LOCALIZATION HUB", 9.2f, LauncherConfig.COL_CYAN, true);
        eyebrow.setLetterSpacing(0.08f);
        titleCol.addView(eyebrow);
        titleCol.addView(makeText(LauncherConfig.HUB_TITLE, 16.5f, LauncherConfig.COL_GOLD, true));
        TextView tvSub = makeText(LauncherConfig.HUB_SUBTITLE, 10.5f, LauncherConfig.COL_MUTED, false);
        tvSub.setPadding(0, dp(2), 0, 0);
        titleCol.addView(tvSub);
        brandRow.addView(titleCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        studioCard.addView(brandRow);

        LinearLayout topPills = new LinearLayout(this);
        topPills.setOrientation(LinearLayout.HORIZONTAL);
        topPills.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tpLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tpLp.topMargin = dp(10);

        TextView hubVer = makeText("الإصدار v" + LauncherConfig.APP_VERSION, 10f, LauncherConfig.COL_GOLD, true);
        hubVer.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable hvBg = new GradientDrawable();
        hvBg.setColor(LauncherConfig.COL_CARD_INNER);
        hvBg.setCornerRadius(dp(8));
        hvBg.setStroke(dp(1), LauncherConfig.COL_BORDER);
        hubVer.setBackground(hvBg);
        LinearLayout.LayoutParams hvLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hvLp.leftMargin = dp(8);
        topPills.addView(hubVer, hvLp);

        View igHome = makeInstagramPill("Instagram: " + LauncherConfig.INSTAGRAM_HANDLE + " ↗", 10.2f, 13, 11, 4);
        igHome.setOnClickListener(v -> openInstagramSupport());
        topPills.addView(igHome);

        studioCard.addView(topPills, tpLp);
        root.addView(studioCard, makeCardLp());

        // فاصل ذهبي رفيع بمعيّن في المنتصف + عنوان قسم مكتبة التعريبات
        root.addView(makeGoldDiamondDivider(), makeDividerLp());
        root.addView(makeEditorialSectionHeader("مكتبة التعريبات"), makeSectionHdrLp());

        // شبكة بطاقات الألعاب الفنية العريضة (عمودان)
        homeCardsGrid = new LinearLayout(this);
        homeCardsGrid.setOrientation(LinearLayout.VERTICAL);
        root.addView(homeCardsGrid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // تذييل مكتبة الألعاب
        root.addView(makeGoldDiamondDivider(), makeDividerLp());
        TextView homeFooter = makeText(LauncherConfig.FOOTER_TEXT, 10.2f, LauncherConfig.COL_MUTED, false);
        homeFooter.setGravity(Gravity.CENTER);
        homeFooter.setPadding(0, dp(4), 0, dp(8));
        root.addView(homeFooter);

        return scroll;
    }

    private String buildHomeRenderSignature() {
        StringBuilder sb = new StringBuilder();
        List<GameProfile> profiles = GameProfileRegistry.getAllProfiles();
        String remoteVer = latestRemoteManifest != null ? latestRemoteManifest.patchVersion : "";
        String remoteBannerHash = latestRemoteManifest != null ? latestRemoteManifest.bannerHash : "";
        sb.append(lastSafetyOk).append('|')
                .append(BetaModeManager.isBetaEnabled(this)).append('|')
                .append(isPatchCurrentlyInstalled).append('|')
                .append(installedPatchVersion).append('|')
                .append(remoteVer).append('|')
                .append(remoteBannerHash).append('|')
                .append(activeProfile != null ? activeProfile.id : "");
        for (GameProfile p : profiles) {
            boolean inst = isPackageInstalled(p.packageName);
            boolean hasBanner = isProfileAccessible(p) && GameIconCache.loadCachedBannerBitmap(this, p.id) != null;
            sb.append(';').append(p.id).append(':').append(inst).append(':').append(hasBanner);
        }
        return sb.toString();
    }

    private void refreshHomeCards() {
        if (homeCardsGrid == null) {
            return;
        }
        String sig = buildHomeRenderSignature();
        if (homeCardsGrid.getChildCount() > 0 && sig.equals(lastHomeRenderSignature)) {
            return;
        }
        lastHomeRenderSignature = sig;
        homeCardsGrid.removeAllViews();
        List<GameProfile> profiles = GameProfileRegistry.getAllProfiles();
        for (int i = 0; i < profiles.size(); i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = dp(10);

            View card1 = createHomeGameCard(profiles.get(i));
            LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp1.leftMargin = dp(4);
            row.addView(card1, lp1);

            if (i + 1 < profiles.size()) {
                View card2 = createHomeGameCard(profiles.get(i + 1));
                LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                lp2.rightMargin = dp(4);
                row.addView(card2, lp2);
            } else {
                View spacer = new View(this);
                LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(0, 1, 1f);
                spLp.rightMargin = dp(4);
                row.addView(spacer, spLp);
            }
            homeCardsGrid.addView(row, rowLp);
        }
    }

    /**
     * بطاقة لعبة فنية عريضة (Full-Bleed Art Card — مستوحاة من شبكة البطاقات المرجعية):
     * تغطي الصورة الفنية كامل البطاقة، مع إطار ذهبي رفيع وتدرج داكن سفلي يحمل اسم اللعبة على اليسار
     * وكبسولة الحالة المتوهجة على اليمين.
     */
    private View createHomeGameCard(GameProfile profile) {
        boolean installed = isPackageInstalled(profile.packageName);
        String statusText;
        int statusBg;
        int statusFg;

        boolean accessible = isProfileAccessible(profile);
        if (!accessible) {
            statusText = "قريباً";
            statusBg = Color.parseColor("#CC2A1A08");
            statusFg = LauncherConfig.COL_GOLD;
        } else if (profile.comingSoon) {
            statusText = installed ? "🧪 Beta" : "🧪 Beta";
            statusBg = Color.parseColor("#CC2E1065");
            statusFg = Color.parseColor("#E9D5FF");
        } else if (!installed) {
            statusText = "غير مثبتة";
            statusBg = Color.parseColor("#CC3B0D1D");
            statusFg = Color.parseColor("#FDA4AF");
        } else if (profile.id.equals(activeProfile.id) && !lastSafetyOk) {
            statusText = "يتوفر تحديث";
            statusBg = Color.parseColor("#CC3B0D1D");
            statusFg = Color.parseColor("#FDA4AF");
        } else if (profile.id.equals(activeProfile.id) && isPatchCurrentlyInstalled) {
            boolean hasRemoteUpdate = latestRemoteManifest != null
                    && !installedPatchVersion.isEmpty()
                    && !installedPatchVersion.equals(latestRemoteManifest.patchVersion);
            if (hasRemoteUpdate) {
                statusText = "يتوفر تحديث";
                statusBg = Color.parseColor("#CC332408");
                statusFg = LauncherConfig.COL_GOLD;
            } else {
                statusText = "مُحدَّث ✓";
                statusBg = Color.parseColor("#CC0A3327");
                statusFg = Color.parseColor("#34D399");
            }
        } else {
            statusText = "جاهز للتعريب";
            statusBg = Color.parseColor("#CC0B2C3B");
            statusFg = LauncherConfig.COL_CYAN;
        }

        // الغلاف الخارجي بنسبة عرض إلى ارتفاع 16:9 (مستطيل أفقي عريض مطابق للصورة المرجعية) مع إطار ذهبي رفيع
        FrameLayout outer = new FrameLayout(this) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int w = MeasureSpec.getSize(widthMeasureSpec);
                int h = Math.round(w * 9f / 16f);
                super.onMeasure(
                        MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
                );
            }
        };
        GradientDrawable frameBg = new GradientDrawable();
        frameBg.setColor(LauncherConfig.COL_CARD_INNER);
        frameBg.setCornerRadius(dp(10));
        frameBg.setStroke(dp(1), profile.comingSoon ? LauncherConfig.COL_BORDER : Color.parseColor("#8CC8A252"));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(10));
        outer.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Color.parseColor("#33F3D078")),
                frameBg,
                mask
        ));
        outer.setPadding(dp(1), dp(1), dp(1), dp(1));
        outer.setClickable(true);
        outer.setFocusable(true);

        FrameLayout inner = new FrameLayout(this);
        clipRounded(inner, dp(9));

        // 1) الصورة الفنية العريضة تغطي كامل البطاقة بنظافة تامة دون أي أيقونة زاوية
        ImageView ivArt = new ImageView(this);
        Bitmap cachedBanner = accessible ? GameIconCache.loadCachedBannerBitmap(this, profile.id) : null;
        if (cachedBanner != null) {
            ivArt.setImageBitmap(cachedBanner);
        } else {
            ivArt.setImageResource(profile.bannerRes != 0 ? profile.bannerRes : profile.iconRes);
        }
        ivArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        inner.addView(ivArt, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 2) تدرج داكن سفلي ناعم لإبراز اسم اللعبة وكبسولة الحالة بوضوح تام
        View bottomScrim = new View(this);
        GradientDrawable scrimBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#0007121A"), Color.parseColor("#3807121A"), Color.parseColor("#EB050E15")}
        );
        bottomScrim.setBackground(scrimBg);
        inner.addView(bottomScrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 3) الشريط السفلي (LTR صريح: اسم اللعبة كاملاً على اليسار + كبسولة الحالة الأنيقة على اليمين)
        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        bottomRow.setGravity(Gravity.CENTER_VERTICAL);
        bottomRow.setPadding(dp(7), dp(5), dp(7), dp(6));

        String shortTitle = profile.id.equals("jojo_golden_hymn") ? "JoJo: Golden Hymn" : profile.title;
        TextView tvTitle = makeText(shortTitle, 9.8f, Color.WHITE, true);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvTitle.setShadowLayer(4f, 0f, 1f, Color.parseColor("#D9000000"));
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tLp.rightMargin = dp(4);
        bottomRow.addView(tvTitle, tLp);

        TextView tvStatus = makeText(statusText, 8.2f, statusFg, true);
        tvStatus.setSingleLine(true);
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setPadding(dp(6), dp(2), dp(6), dp(2));
        GradientDrawable stBg = new GradientDrawable();
        stBg.setColor(statusBg);
        stBg.setCornerRadius(dp(8));
        stBg.setStroke(dp(1), statusFg);
        tvStatus.setBackground(stBg);
        bottomRow.addView(tvStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        inner.addView(bottomRow, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        outer.addView(inner, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        outer.setOnClickListener(v -> {
            if (!isProfileAccessible(profile)) {
                AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
                b.setTitle(profile.title + " — قريباً");
                b.setMessage(
                        "جارٍ تجهيز حزمة التعريب الرسمية للعبة "
                                + profile.title + " داخل منصة R.D.AV Multi-Game Hub مع دعم النسخ الاحتياطي التلقائي والاستعادة السريعة.\n\n"
                                + "تابع حسابنا على Instagram (" + LauncherConfig.INSTAGRAM_HANDLE + ") لموعد الإطلاق!"
                );
                b.setPositiveButton("حسناً", null);
                b.show();
            } else {
                enterGameWithOnlineSecurityGate(profile);
            }
        });
        return outer;
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

    // =========================================================================
    // بوابة الفحص الأمني الإلزامية عند الاتصال (Online Security & Live Sync Gate)
    // =========================================================================

    private boolean isInternetConnected() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            Network active = cm.getActiveNetwork();
            if (active == null) {
                return false;
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            if (caps == null) {
                return false;
            }
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
        } catch (Throwable ignored) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
                return ni != null && ni.isConnected();
            } catch (Throwable t) {
                return false;
            }
        }
    }

    private void enterGameWithOnlineSecurityGate(GameProfile profile) {
        if (!isProfileAccessible(profile)) {
            return;
        }
        if (!isInternetConnected()) {
            showInternetRequiredDialog(profile, null);
            return;
        }
        showLiveSyncLoadingDialog(profile);
        worker.execute(() -> {
            try {
                // جلب ملف manifest الحي من GitHub (وعند فتح لعبة قيد الاختبار في Beta Mode، يُجلب manifest_beta.json مباشرة!)
                PatchManifest liveManifest = null;
                if (BetaModeManager.isBetaEnabled(this) && profile.comingSoon) {
                    try {
                        liveManifest = PatchDownloader.fetchBetaManifest(profile);
                    } catch (Throwable betaErr) {
                        // في وضع اختبار المطوّر (Beta Mode) للعبة جديدة لم يُنشر لها أول manifest_beta بعد، نسمح بالدخول للتجربة اليدوية
                        liveManifest = null;
                    }
                } else {
                    try {
                        liveManifest = PatchDownloader.fetchManifestForProfile(profile);
                    } catch (Throwable pubErr) {
                        if (BetaModeManager.isBetaEnabled(this)) {
                            liveManifest = PatchDownloader.fetchBetaManifest(profile);
                        } else {
                            throw pubErr;
                        }
                    }
                }
                if (liveManifest != null) {
                    liveManifest.saveToCache(this, profile.id);
                }
                latestRemoteManifest = liveManifest;

                // مزامنة الشعار والبانر بالتخزين الذكي بالبصمة (لا يُعاد التحميل إلا إذا تغيّرت البصمة!)
                if (liveManifest != null && !liveManifest.iconUrl.isEmpty()) {
                    GameIconCache.syncIconSync(this, profile.id, liveManifest.iconUrl, liveManifest.iconHash);
                }
                String bannerUrl = liveManifest != null ? liveManifest.getEffectiveBannerImageUrl() : "";
                if (!bannerUrl.isEmpty()) {
                    GameIconCache.syncBannerSync(this, profile.id, bannerUrl, liveManifest.getEffectiveBannerImageHash());
                }
                String videoUrl = liveManifest != null ? liveManifest.getEffectiveHeroVideoUrl() : "";
                if (!videoUrl.isEmpty()) {
                    File cachedVid = GameIconCache.loadCachedHeroVideoFile(this, profile.id);
                    activeRemoteHeroVideoFile = cachedVid;
                    GameIconCache.syncHeroVideoAsync(
                            this, iconExecutor, mainHandler,
                            profile.id, videoUrl, liveManifest.getEffectiveHeroVideoHash(),
                            file -> {
                                activeRemoteHeroVideoFile = file;
                                usingRemoteBannerImage = false;
                                if (isOnGameHub && promoTextureView != null) {
                                    promoTextureView.setVisibility(View.VISIBLE);
                                    if (promoTextureView.isAvailable()) {
                                        startPromoVideoOnSurface(promoTextureView.getSurfaceTexture());
                                    }
                                }
                            });
                } else {
                    activeRemoteHeroVideoFile = null;
                }

                mainHandler.post(() -> {
                    dismissLiveSyncLoadingDialog();
                    openGameHub(profile);
                });
            } catch (Throwable t) {
                final String errDetail = readableError(t);
                mainHandler.post(() -> {
                    dismissLiveSyncLoadingDialog();
                    showInternetRequiredDialog(profile, errDetail);
                });
            }
        });
    }

    private void showLiveSyncLoadingDialog(GameProfile profile) {
        dismissLiveSyncLoadingDialog();

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER_HORIZONTAL);
        container.setPadding(dp(20), dp(22), dp(20), dp(20));
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#0F2330"), LauncherConfig.COL_BG}
        );
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), LauncherConfig.COL_BORDER_GOLD);
        container.setBackground(bg);

        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(LauncherConfig.COL_GOLD));
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        spLp.bottomMargin = dp(14);
        container.addView(spinner, spLp);

        TextView tvTitle = makeText("جاري التحقق من حارس الأمان ومزامنة أحدث البيانات...", 13f, LauncherConfig.COL_GOLD, true);
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setLineSpacing(dp(3), 1f);
        container.addView(tvTitle);

        TextView tvSub = makeText(profile.title + " • اتصال حي آمن (no-cache)", 10.5f, LauncherConfig.COL_MUTED, false);
        tvSub.setGravity(Gravity.CENTER);
        tvSub.setPadding(0, dp(6), 0, 0);
        container.addView(tvSub);

        liveSyncDialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setView(container)
                .setCancelable(false)
                .create();
        liveSyncDialog.show();
    }

    private void dismissLiveSyncLoadingDialog() {
        try {
            if (liveSyncDialog != null && liveSyncDialog.isShowing()) {
                liveSyncDialog.dismiss();
            }
        } catch (Throwable ignored) {
        }
        liveSyncDialog = null;
    }

    private void showInternetRequiredDialog(GameProfile profile, String techDetail) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(18), dp(16), dp(18), dp(14));
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#0F2330"), LauncherConfig.COL_BG}
        );
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), LauncherConfig.COL_BORDER_GOLD);
        container.setBackground(bg);

        // ترويسة ذهبية-كحلية فخمة
        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.VERTICAL);
        hdr.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable hdrBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor("#132A38"), LauncherConfig.COL_CARD_INNER}
        );
        hdrBg.setCornerRadius(dp(10));
        hdrBg.setStroke(dp(1), LauncherConfig.COL_BORDER);
        hdr.setBackground(hdrBg);

        hdr.addView(makeText("🔒  الاتصال بالإنترنت مطلوب", 15f, LauncherConfig.COL_GOLD, true));
        TextView tvSub = makeText("بوابة الفحص الأمني الإلزامية — R.D.AV Security Gate", 10.2f, LauncherConfig.COL_CYAN, true);
        tvSub.setPadding(0, dp(3), 0, 0);
        hdr.addView(tvSub);

        LinearLayout.LayoutParams hdrLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hdrLp.bottomMargin = dp(12);
        container.addView(hdr, hdrLp);

        TextView tvMsg = makeText(
                "لضمان أمان ملفات لعبتك، والتحقق من توافق التعريب مع أحدث إصدار للعبة، وتحديث البيانات، يرجى الاتصال بالإنترنت ثم إعادة المحاولة.",
                12.2f, LauncherConfig.COL_TEXT, false
        );
        tvMsg.setLineSpacing(dp(4), 1f);
        tvMsg.setPadding(dp(2), 0, dp(2), dp(12));
        container.addView(tvMsg);

        if (techDetail != null && !techDetail.trim().isEmpty()) {
            TextView tvTech = makeText("تفاصيل الاتصال: " + techDetail, 10f, LauncherConfig.COL_MUTED, false);
            tvTech.setPadding(dp(10), dp(7), dp(10), dp(7));
            GradientDrawable tBg = new GradientDrawable();
            tBg.setColor(LauncherConfig.COL_CARD_INNER);
            tBg.setCornerRadius(dp(8));
            tBg.setStroke(dp(1), LauncherConfig.COL_BORDER);
            tvTech.setBackground(tBg);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tLp.bottomMargin = dp(12);
            container.addView(tvTech, tLp);
        }

        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setView(container)
                .create();

        Button btnRetry = new Button(this);
        btnRetry.setText("🔄  إعادة المحاولة");
        btnRetry.setAllCaps(false);
        btnRetry.setTextSize(13.5f);
        btnRetry.setTextColor(Color.parseColor("#07121A"));
        btnRetry.setTypeface(fontBold);
        GradientDrawable rBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{LauncherConfig.COL_GOLD, LauncherConfig.COL_GOLD_SOLID}
        );
        rBg.setCornerRadius(dp(10));
        btnRetry.setBackground(rBg);
        btnRetry.setOnClickListener(v -> {
            dialog.dismiss();
            enterGameWithOnlineSecurityGate(profile);
        });
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        rLp.bottomMargin = dp(8);
        container.addView(btnRetry, rLp);

        Button btnClose = makeButton("إغلاق", LauncherConfig.COL_CARD_INNER, LauncherConfig.COL_MUTED, LauncherConfig.COL_BORDER, true);
        btnClose.setTextSize(12f);
        btnClose.setOnClickListener(v -> dialog.dismiss());
        container.addView(btnClose, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));

        dialog.show();
    }

    private void openGameHub(GameProfile profile) {
        activeProfile = profile;
        isOnGameHub = true;
        homeScreenView.setVisibility(View.GONE);
        gameHubScreenView.setVisibility(View.VISIBLE);
        applyDynamicMetadataToUi();
        syncBetaModeUi();
        resumePromoVideo();
        if (!profile.id.equals(lastHeroBadgeSessionGameId)) {
            lastHeroBadgeSessionGameId = profile.id;
            triggerHeroDevBadgeAnimation();
        }
        refreshStatusAsync(null);
    }

    private void showHomeLibrary() {
        isOnGameHub = false;
        if (screenshotLightboxOverlay != null) {
            screenshotLightboxOverlay.animate().cancel();
            screenshotLightboxOverlay.setVisibility(View.GONE);
            isLightboxOpen = false;
            isLightboxAnimating = false;
            lightboxOriginView = null;
        }
        mainHandler.removeCallbacks(hidePromoAudioRunnable);
        mainHandler.removeCallbacks(promoFadeRunnable);
        cancelHeroDevBadgeAnimation();
        pausePromoVideo();
        gameHubScreenView.setVisibility(View.GONE);
        homeScreenView.setVisibility(View.VISIBLE);
        syncBetaModeUi();
        refreshHomeCards();
    }

    private void cancelHeroDevBadgeAnimation() {
        mainHandler.removeCallbacks(heroBadgeShowRunnable);
        mainHandler.removeCallbacks(heroBadgeHideRunnable);
        if (heroDevBadge != null) {
            heroDevBadge.animate().cancel();
            heroDevBadge.setTranslationX(dp(220));
            heroDevBadge.setAlpha(0f);
            heroDevBadge.setVisibility(View.INVISIBLE);
        }
    }

    private void triggerHeroDevBadgeAnimation() {
        cancelHeroDevBadgeAnimation();
        if (heroDevBadge != null && isOnGameHub) {
            mainHandler.postDelayed(heroBadgeShowRunnable, 2000L);
        }
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

        // 0.25) شريط حارس الأمان والتنبيهات الحية عن بُعد (security_guard / latest_version)
        hubRemoteSecurityBanner = new LinearLayout(this);
        hubRemoteSecurityBanner.setOrientation(LinearLayout.VERTICAL);
        hubRemoteSecurityBanner.setPadding(dp(12), dp(10), dp(12), dp(10));
        hubRemoteSecurityBanner.setVisibility(View.GONE);
        tvRemoteSecurityTitle = makeText("", 12f, LauncherConfig.COL_GOLD, true);
        tvRemoteSecurityMsg = makeText("", 10.8f, LauncherConfig.COL_TEXT, false);
        tvRemoteSecurityMsg.setPadding(0, dp(3), 0, 0);
        tvRemoteSecurityMsg.setLineSpacing(dp(2), 1f);
        hubRemoteSecurityBanner.addView(tvRemoteSecurityTitle);
        hubRemoteSecurityBanner.addView(tvRemoteSecurityMsg);
        root.addView(hubRemoteSecurityBanner, makeCardLp());

        // 0.5) شريط الرجوع إلى مكتبة التعريبات
        LinearLayout backRow = new LinearLayout(this);
        backRow.setOrientation(LinearLayout.HORIZONTAL);
        backRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams backRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        backRowLp.bottomMargin = dp(10);

        btnBackToLibrary = makeText("←  مكتبة التعريبات", 11.8f, LauncherConfig.COL_GOLD, true);
        btnBackToLibrary.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable backBg = new GradientDrawable();
        backBg.setColor(LauncherConfig.COL_CARD);
        backBg.setCornerRadius(dp(9));
        backBg.setStroke(dp(1), LauncherConfig.COL_BORDER);
        btnBackToLibrary.setBackground(backBg);
        btnBackToLibrary.setClickable(true);
        btnBackToLibrary.setOnClickListener(v -> {
            if (!isBusy) {
                showHomeLibrary();
            }
        });
        backRow.addView(btnBackToLibrary);

        root.addView(backRow, backRowLp);

        // ---------------------------------------------------------------------
        // 1) الهيدر والبانر التحريري الفخم (Luxury Dark-Teal & Gold Editorial Hero)
        // ---------------------------------------------------------------------
        LinearLayout heroCard = new LinearLayout(this);
        heroCard.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable heroBorderBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#0C1E2A"), LauncherConfig.COL_CARD, Color.parseColor("#08141C")}
        );
        heroBorderBg.setCornerRadius(dp(11));
        heroBorderBg.setStroke(dp(1), Color.parseColor("#80C8A252"));
        heroCard.setBackground(heroBorderBg);
        heroCard.setPadding(dp(1), dp(1), dp(1), dp(1));

        // 1.أ) القسم العلوي: البانر الفني العريض والفيديو التفاعلي الصامت بتدرج ناعم للأسفل
        FrameLayout promoInner = new FrameLayout(this);
        clipRounded(promoInner, dp(10));

        ivHubPoster = new ImageView(this);
        Bitmap initialBanner = GameIconCache.loadCachedBannerBitmap(this, activeProfile.id);
        if (initialBanner != null) {
            ivHubPoster.setImageBitmap(initialBanner);
        } else {
            ivHubPoster.setImageResource(R.drawable.bg_promo_poster);
        }
        ivHubPoster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        promoInner.addView(ivHubPoster, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

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

        // تدرج ناعم جداً يذوب مباشرة في الخلفية الكحلية-الزمردية للبطاقة
        View scrim = new View(this);
        GradientDrawable scrimBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#1807121A"), Color.parseColor("#5507121A"), Color.parseColor("#FF0B1A24")}
        );
        scrim.setBackground(scrimBg);
        promoInner.addView(scrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // شارة المطور R.D.AV العائمة بصورة الأفاتار وحركة انبثاق/اختفاء سلسة (2s -> 3s -> إخفاء)
        heroDevBadge = makeFloatingDeveloperBadge();
        heroDevBadge.setTranslationX(dp(220));
        heroDevBadge.setAlpha(0f);
        heroDevBadge.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams emLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT);
        emLp.setMargins(dp(10), dp(10), dp(10), dp(10));
        promoInner.addView(heroDevBadge, emLp);

        // أيقونة زر كتم/تشغيل الصوت في الزاوية اليسرى العلوية (تظهر عند اللمس وتختفي تلقائياً)
        btnPromoAudio = makeText("🔊", 15f, Color.WHITE, true);
        btnPromoAudio.setGravity(Gravity.CENTER);
        GradientDrawable audioBg = new GradientDrawable();
        audioBg.setShape(GradientDrawable.OVAL);
        audioBg.setColor(Color.parseColor("#B307121A"));
        audioBg.setStroke(dp(1), LauncherConfig.COL_BORDER_GOLD);
        btnPromoAudio.setBackground(audioBg);
        btnPromoAudio.setAlpha(0f);
        btnPromoAudio.setVisibility(View.GONE);
        btnPromoAudio.setOnClickListener(v -> togglePromoAudioMute());
        FrameLayout.LayoutParams audioLp = new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP | Gravity.LEFT);
        audioLp.setMargins(dp(10), dp(10), dp(10), dp(10));
        promoInner.addView(btnPromoAudio, audioLp);

        promoInner.setClickable(true);
        promoInner.setOnClickListener(v -> showPromoAudioControlTemporarily());

        heroCard.addView(promoInner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(184)));

        // 1.ب) القسم التحريري السفلي داخل البطاقة: أيقونة اللعبة + الاسم الذهبي + شريط شارات الإصدار
        LinearLayout editorialBody = new LinearLayout(this);
        editorialBody.setOrientation(LinearLayout.VERTICAL);
        editorialBody.setPadding(dp(14), dp(4), dp(14), dp(14));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        // أيقونة اللعبة الرسمية المتزامنة ديناميكياً بإطار ذهبي رفيع
        FrameLayout iconWrap = new FrameLayout(this);
        GradientDrawable iconFrame = new GradientDrawable();
        iconFrame.setCornerRadius(dp(11));
        iconFrame.setStroke(dp(1), LauncherConfig.COL_BORDER_GOLD);
        iconFrame.setColor(LauncherConfig.COL_CARD_INNER);
        iconWrap.setBackground(iconFrame);
        iconWrap.setPadding(dp(2), dp(2), dp(2), dp(2));

        ivHubLogo = new ImageView(this);
        Bitmap hubCachedIcon = GameIconCache.loadCachedBitmap(this, activeProfile.id);
        if (hubCachedIcon != null) {
            ivHubLogo.setImageBitmap(hubCachedIcon);
        } else {
            ivHubLogo.setImageResource(activeProfile.iconRes);
        }
        ivHubLogo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        clipRounded(ivHubLogo, dp(9));
        iconWrap.addView(ivHubLogo, new FrameLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(52), dp(52));
        iconLp.leftMargin = dp(12);
        titleRow.addView(iconWrap, iconLp);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        tvHubEyebrow = makeText("ANDROID • ARABIC LOCALIZATION UPDATE", 8.8f, LauncherConfig.COL_CYAN, true);
        tvHubEyebrow.setLetterSpacing(0.06f);
        titleCol.addView(tvHubEyebrow);

        tvHubTitle = makeText(LauncherConfig.APP_TITLE, 16.5f, LauncherConfig.COL_GOLD, true);
        tvHubTitle.setPadding(0, dp(2), 0, 0);
        titleCol.addView(tvHubTitle);

        tvHubSubtitle = makeText("تحديث تعريب جوجو: الترنيمة الذهبية — للأندرويد", 11.2f, LauncherConfig.COL_TEXT, false);
        tvHubSubtitle.setPadding(0, dp(2), 0, 0);
        titleCol.addView(tvHubSubtitle);

        titleRow.addView(titleCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        editorialBody.addView(titleRow);

        // شريط شارات الإصدار التحريرية: [تحديث تعريب (ذهبي صلب)] [v1.0.0] + شارة التوافق
        LinearLayout verStrip = new LinearLayout(this);
        verStrip.setOrientation(LinearLayout.HORIZONTAL);
        verStrip.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams vsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        vsLp.topMargin = dp(12);

        tvHubBadgeLabel = makeText("تحديث تعريب", 10.8f, Color.parseColor("#07121A"), true);
        tvHubBadgeLabel.setSingleLine(true);
        tvHubBadgeLabel.setPadding(dp(11), dp(5), dp(11), dp(5));
        GradientDrawable bsgBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{LauncherConfig.COL_GOLD, LauncherConfig.COL_GOLD_SOLID}
        );
        bsgBg.setCornerRadius(dp(7));
        tvHubBadgeLabel.setBackground(bsgBg);
        LinearLayout.LayoutParams bsgLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bsgLp.leftMargin = dp(6);
        verStrip.addView(tvHubBadgeLabel, bsgLp);

        tvEditorialVersionBox = makeText("v1.0.0", 10.8f, LauncherConfig.COL_GOLD, true);
        tvEditorialVersionBox.setSingleLine(true);
        tvEditorialVersionBox.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvEditorialVersionBox.setPadding(dp(10), dp(5), dp(10), dp(5));
        GradientDrawable evbBg = new GradientDrawable();
        evbBg.setColor(LauncherConfig.COL_CARD_INNER);
        evbBg.setCornerRadius(dp(7));
        evbBg.setStroke(dp(1), LauncherConfig.COL_BORDER_GOLD);
        tvEditorialVersionBox.setBackground(evbBg);
        LinearLayout.LayoutParams evbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        evbLp.leftMargin = dp(8);
        verStrip.addView(tvEditorialVersionBox, evbLp);

        tvEditorialCompatText = makeText("متوافق مع v" + activeProfile.targetGameVersionName, 10.2f, Color.parseColor("#6EE7B7"), true);
        tvEditorialCompatText.setSingleLine(true);
        tvEditorialCompatText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvEditorialCompatText.setPadding(dp(8), dp(4), dp(8), dp(4));
        GradientDrawable compBg = new GradientDrawable();
        compBg.setColor(Color.parseColor("#0C2621"));
        compBg.setCornerRadius(dp(7));
        compBg.setStroke(dp(1), Color.parseColor("#6622C55E"));
        tvEditorialCompatText.setBackground(compBg);
        verStrip.addView(tvEditorialCompatText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        editorialBody.addView(verStrip, vsLp);

        // شريط حساب المطوّر السريع داخل الترويسة (قابل للتعديل عن بُعد من Mobile Hub Studio)
        LinearLayout heroSocialRow = new LinearLayout(this);
        heroSocialRow.setOrientation(LinearLayout.HORIZONTAL);
        heroSocialRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hsrLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hsrLp.topMargin = dp(9);

        hubSocialPill = makeInstagramPill("Instagram: " + LauncherConfig.INSTAGRAM_HANDLE + " ↗", 10.2f, 13, 11, 4);
        hubSocialPill.setOnClickListener(v -> {
            if (activeSocialUrl != null && activeSocialUrl.startsWith("https://")) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(activeSocialUrl)));
                    return;
                } catch (Throwable ignored) {
                }
            }
            openInstagramSupport();
        });
        heroSocialRow.addView(hubSocialPill);
        editorialBody.addView(heroSocialRow, hsrLp);

        heroCard.addView(editorialBody);
        root.addView(heroCard, makeCardLp());

        // ---------------------------------------------------------------------
        // 2) بطاقة معلومات اللعبة من المتجر (Google Play) مباشرة أسفل البانر العلوي
        //    قابلة للضغط بتموّج بصري لفتح صفحة اللعبة في المتجر + شريط لقطات الشاشة التفاعلي تحتها
        // ---------------------------------------------------------------------
        LinearLayout storeCard = makeCard(LauncherConfig.COL_BORDER);
        GradientDrawable storeBaseBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#0E202C"), LauncherConfig.COL_CARD}
        );
        storeBaseBg.setCornerRadius(dp(10));
        storeBaseBg.setStroke(dp(1), LauncherConfig.COL_BORDER_GOLD);
        GradientDrawable storeRippleMask = new GradientDrawable();
        storeRippleMask.setColor(Color.WHITE);
        storeRippleMask.setCornerRadius(dp(10));
        storeCard.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Color.parseColor("#33F3D078")),
                storeBaseBg,
                storeRippleMask
        ));
        storeCard.setClickable(true);
        storeCard.setFocusable(true);
        storeCard.setOnClickListener(v -> openPlayStoreForProfile(activeProfile));

        LinearLayout storeHdrRow = new LinearLayout(this);
        storeHdrRow.setOrientation(LinearLayout.HORIZONTAL);
        storeHdrRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams shRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        shRowLp.bottomMargin = dp(8);

        ImageView ivPlayLogo = new ImageView(this);
        ivPlayLogo.setImageResource(R.drawable.ic_google_play);
        ivPlayLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams playIconLp = new LinearLayout.LayoutParams(dp(20), dp(20));
        playIconLp.leftMargin = dp(8);
        storeHdrRow.addView(ivPlayLogo, playIconLp);

        TextView storeHdr = makeText("معلومات اللعبة من المتجر (Google Play)", 11.8f, LauncherConfig.COL_GOLD, true);
        storeHdrRow.addView(storeHdr, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView storeOpenArrow = makeText("المتجر ↗", 9.8f, Color.parseColor("#6EE7B7"), true);
        storeOpenArrow.setSingleLine(true);
        storeOpenArrow.setPadding(dp(8), dp(3), dp(8), dp(3));
        GradientDrawable soaBg = new GradientDrawable();
        soaBg.setColor(Color.parseColor("#0E2921"));
        soaBg.setCornerRadius(dp(8));
        soaBg.setStroke(dp(1), Color.parseColor("#10B981"));
        storeOpenArrow.setBackground(soaBg);
        storeHdrRow.addView(storeOpenArrow);

        storeCard.addView(storeHdrRow, shRowLp);

        LinearLayout storePillsRow = new LinearLayout(this);
        storePillsRow.setOrientation(LinearLayout.HORIZONTAL);
        storePillsRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams spRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        spRowLp.bottomMargin = dp(8);

        tvStoreScorePill = makeStoreInfoPill("⭐ " + activeProfile.defaultStoreScore, LauncherConfig.COL_GOLD);
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scLp.leftMargin = dp(6);
        storePillsRow.addView(tvStoreScorePill, scLp);

        tvStoreInstallsPill = makeStoreInfoPill("⬇ " + activeProfile.defaultStoreInstalls, LauncherConfig.COL_SUCCESS);
        LinearLayout.LayoutParams insLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        insLp.leftMargin = dp(6);
        storePillsRow.addView(tvStoreInstallsPill, insLp);

        tvStoreDevPill = makeStoreInfoPill("🏢 " + activeProfile.defaultStoreDeveloper, LauncherConfig.COL_CYAN);
        storePillsRow.addView(tvStoreDevPill, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        storeCard.addView(storePillsRow, spRowLp);

        tvStoreSummary = makeText(activeProfile.defaultStoreSummary, 11.2f, LauncherConfig.COL_MUTED, false);
        tvStoreSummary.setLineSpacing(dp(3), 1f);
        storeCard.addView(tvStoreSummary);

        root.addView(storeCard, makeCardLp());

        // 2.2) معرض لقطات الشاشة الأفقي من Google Play (Horizontal Screenshots Carousel — Tap to Expand/Collapse)
        storeScreenshotsSection = new LinearLayout(this);
        storeScreenshotsSection.setOrientation(LinearLayout.VERTICAL);
        storeScreenshotsSection.setVisibility(View.GONE);

        LinearLayout shotsHdrRow = new LinearLayout(this);
        shotsHdrRow.setOrientation(LinearLayout.HORIZONTAL);
        shotsHdrRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams shotsHdrLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        shotsHdrLp.bottomMargin = dp(6);

        TextView shotsTitle = makeText("◆  لقطات من داخل اللعبة", 11.4f, LauncherConfig.COL_GOLD, true);
        shotsHdrRow.addView(shotsTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView shotsHint = makeText("اضغط للتكبير  🔍", 9.6f, LauncherConfig.COL_MUTED, false);
        shotsHdrRow.addView(shotsHint);
        storeScreenshotsSection.addView(shotsHdrRow, shotsHdrLp);

        storeScreenshotsScroll = new HorizontalScrollView(this);
        storeScreenshotsScroll.setHorizontalScrollBarEnabled(false);
        storeScreenshotsScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        storeScreenshotsScroll.setFillViewport(false);

        storeScreenshotsRow = new LinearLayout(this);
        storeScreenshotsRow.setOrientation(LinearLayout.HORIZONTAL);
        storeScreenshotsRow.setGravity(Gravity.CENTER_VERTICAL);
        storeScreenshotsRow.setPadding(dp(2), dp(2), dp(2), dp(4));
        storeScreenshotsScroll.addView(storeScreenshotsRow, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        storeScreenshotsSection.addView(storeScreenshotsScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(storeScreenshotsSection, makeCardLp());

        root.addView(makeGoldDiamondDivider(), makeDividerLp());

        // ---------------------------------------------------------------------
        // 2.5) قسم «أبرز التحسينات والإصلاحات» بالترقيم المعيّني الذهبي (◆ 1, 2, 3, 4)
        // ---------------------------------------------------------------------
        root.addView(makeEditorialSectionHeader("أبرز التحسينات والإصلاحات"), makeSectionHdrLp());

        changelogCardsContainer = new LinearLayout(this);
        changelogCardsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(changelogCardsContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        rebuildEditorialChangelogCards(latestRemoteManifest != null ? latestRemoteManifest.changelog : "");

        root.addView(makeGoldDiamondDivider(), makeDividerLp());

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

        // خط فاصل ذهبي رقيق
        View divider = new View(this);
        divider.setBackgroundColor(LauncherConfig.COL_BORDER);
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

        // 1. الزر الرئيسي البارز: ▶ تشغيل اللعبة (تدرج ذهبي تحريري فخم)
        btnLaunchGame = new Button(this);
        btnLaunchGame.setText("▶  تشغيل اللعبة");
        btnLaunchGame.setAllCaps(false);
        btnLaunchGame.setTextSize(15.5f);
        btnLaunchGame.setTextColor(Color.parseColor("#07121A"));
        btnLaunchGame.setTypeface(fontBold);
        GradientDrawable heroBtnBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{LauncherConfig.COL_GOLD, LauncherConfig.COL_GOLD_SOLID, LauncherConfig.COL_BORDER_GOLD}
        );
        heroBtnBg.setCornerRadius(dp(10));
        heroBtnBg.setStroke(dp(1), Color.parseColor("#FBE8A6"));
        btnLaunchGame.setBackground(heroBtnBg);
        btnLaunchGame.setOnClickListener(v -> launchGameWithSafetyCheck());
        LinearLayout.LayoutParams launchLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        launchLp.bottomMargin = dp(9);
        root.addView(btnLaunchGame, launchLp);

        // 2. صف التثبيت: تثبيت / تحديث التعريب (ZIP - ثيم ذهبي) + تحديث أونلاين (GitHub - ثيم GitHub الداكن)
        LinearLayout rowInstall = new LinearLayout(this);
        rowInstall.setOrientation(LinearLayout.HORIZONTAL);

        btnInstallLocalZip = makeIconButton(
                "تثبيت / تحديث التعريب (ZIP)",
                R.drawable.ic_zip_folder,
                Color.parseColor("#29200E"),
                Color.parseColor("#FDE047"),
                Color.parseColor("#F59E0B"),
                11.8f
        );
        btnInstallLocalZip.setOnClickListener(v -> openLocalZipPicker());
        LinearLayout.LayoutParams zipLp = new LinearLayout.LayoutParams(0, dp(50), 1.08f);
        zipLp.leftMargin = dp(5);
        rowInstall.addView(btnInstallLocalZip, zipLp);

        btnInstallOnline = makeIconButton(
                "تحديث أونلاين (GitHub)",
                R.drawable.ic_github_mark,
                Color.parseColor("#161B22"),
                Color.parseColor("#F0F6FC"),
                Color.parseColor("#30363D"),
                11.8f
        );
        btnInstallOnline.setOnClickListener(v -> startOnlineInstallFlow());
        LinearLayout.LayoutParams onlineLp = new LinearLayout.LayoutParams(0, dp(50), 0.92f);
        onlineLp.rightMargin = dp(5);
        rowInstall.addView(btnInstallOnline, onlineLp);

        LinearLayout.LayoutParams rowInstallLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowInstallLp.bottomMargin = dp(9);
        root.addView(rowInstall, rowInstallLp);

        // 3. صف الأدوات: فحص سلامة الملفات (ثيم زمردي) + استعادة النسخة الأصلية (ثيم أحمر)
        LinearLayout rowTools = new LinearLayout(this);
        rowTools.setOrientation(LinearLayout.HORIZONTAL);

        btnDiagScan = makeIconButton(
                "فحص سلامة الملفات",
                R.drawable.ic_shield_check,
                Color.parseColor("#0E2921"),
                Color.parseColor("#6EE7B7"),
                Color.parseColor("#10B981"),
                11.8f
        );
        btnDiagScan.setOnClickListener(v -> runDiagnosticScanFlow());
        LinearLayout.LayoutParams diagLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        diagLp.leftMargin = dp(5);
        rowTools.addView(btnDiagScan, diagLp);

        btnRestore = makeIconButton(
                "استعادة النسخة الأصلية",
                R.drawable.ic_restore_history,
                Color.parseColor("#2B1117"),
                Color.parseColor("#FDA4AF"),
                Color.parseColor("#EF4444"),
                11.8f
        );
        btnRestore.setOnClickListener(v -> confirmAndRestore());
        LinearLayout.LayoutParams restLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        restLp.rightMargin = dp(5);
        rowTools.addView(btnRestore, restLp);

        LinearLayout.LayoutParams rowToolsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowToolsLp.bottomMargin = dp(9);
        root.addView(rowTools, rowToolsLp);

        // 4. زر «💬 اقتراح أو الإبلاغ عن مشكلة» (ثيم أزرق سماوي داكن #0E2233 وحدود #38BDF8)
        btnFeedback = makeIconButton(
                "اقتراح أو الإبلاغ عن مشكلة",
                R.drawable.ic_feedback_chat,
                Color.parseColor("#0E2233"),
                Color.parseColor("#BAE6FD"),
                Color.parseColor("#38BDF8"),
                12.5f
        );
        btnFeedback.setOnClickListener(v -> showFeedbackDialog());
        LinearLayout.LayoutParams fbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        fbLp.bottomMargin = dp(10);
        root.addView(btnFeedback, fbLp);

        // ---------------------------------------------------------------------
        // 5) بطاقة تفاعلية لـ «📊 إحصائيات حزمة التعريب» (تفتح نافذة التفاصيل الكاملة)
        // ---------------------------------------------------------------------
        LinearLayout statsCard = makeCard(LauncherConfig.COL_BORDER_GOLD);
        statsCard.setClickable(true);
        statsCard.setOnClickListener(v -> showPatchStatsModal());

        LinearLayout statsHdrRow = new LinearLayout(this);
        statsHdrRow.setOrientation(LinearLayout.HORIZONTAL);
        statsHdrRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams shLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        shLp.bottomMargin = dp(8);

        TextView statsTitle = makeText("📊  إحصائيات حزمة التعريب", 13f, LauncherConfig.COL_GOLD, true);
        statsHdrRow.addView(statsTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView statsOpenPill = makeText("التفاصيل الكاملة ↗", 10f, Color.parseColor("#0D061A"), true);
        statsOpenPill.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable sopBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{LauncherConfig.COL_GOLD, LauncherConfig.COL_ACCENT}
        );
        sopBg.setCornerRadius(dp(9));
        statsOpenPill.setBackground(sopBg);
        statsOpenPill.setOnClickListener(v -> showPatchStatsModal());
        statsHdrRow.addView(statsOpenPill);
        statsCard.addView(statsHdrRow, shLp);

        LinearLayout statsRow1 = new LinearLayout(this);
        statsRow1.setOrientation(LinearLayout.HORIZONTAL);
        tvStatLinesChip = makeStatChip(LauncherConfig.STAT_LINES, LauncherConfig.COL_GOLD);
        statsRow1.addView(tvStatLinesChip, makeHalfLp(true));
        tvStatSizeChip = makeStatChip("📦  حجم حزمة التعريب: —", LauncherConfig.COL_CYAN);
        statsRow1.addView(tvStatSizeChip, makeHalfLp(false));
        LinearLayout.LayoutParams sr1Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sr1Lp.bottomMargin = dp(7);
        statsCard.addView(statsRow1, sr1Lp);

        LinearLayout statsRow2 = new LinearLayout(this);
        statsRow2.setOrientation(LinearLayout.HORIZONTAL);
        tvStatCompatChip = makeStatChip(LauncherConfig.STAT_COMPAT, LauncherConfig.COL_SUCCESS);
        statsRow2.addView(tvStatCompatChip, makeHalfLp(true));
        statsRow2.addView(makeStatChip("🛡  مفحوص ومعتمد ✓", Color.parseColor("#6EE7B7")), makeHalfLp(false));
        statsCard.addView(statsRow2);

        root.addView(statsCard, makeCardLp());

        // ---------------------------------------------------------------------
        // 6) بطاقة التحديثات أونلاين (GitHub Releases) والتذييل
        // ---------------------------------------------------------------------
        LinearLayout updateCard = makeCard(LauncherConfig.COL_BORDER);
        LinearLayout updateHeader = new LinearLayout(this);
        updateHeader.setOrientation(LinearLayout.HORIZONTAL);
        updateHeader.setGravity(Gravity.CENTER_VERTICAL);

        ImageView ivGhHdr = new ImageView(this);
        ivGhHdr.setImageResource(R.drawable.ic_github_mark);
        ivGhHdr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams ghHdrLp = new LinearLayout.LayoutParams(dp(18), dp(18));
        ghHdrLp.leftMargin = dp(7);
        updateHeader.addView(ivGhHdr, ghHdrLp);

        updateHeader.addView(makeText("تحديثات GitHub Releases", 12f, Color.parseColor("#F0F6FC"), true),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        btnCheckUpdate = makeButton("فحص التحديث", Color.parseColor("#161B22"), LauncherConfig.COL_GOLD, Color.parseColor("#30363D"), true);
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

        // تذييل منسّق بسطرين (يمنع التفاف اسم Instagram على سطرين أبداً)
        LinearLayout footerCol = new LinearLayout(this);
        footerCol.setOrientation(LinearLayout.VERTICAL);
        footerCol.setGravity(Gravity.CENTER_HORIZONTAL);
        footerCol.setPadding(0, dp(4), 0, dp(10));

        TextView tvFooter = makeText(LauncherConfig.FOOTER_TEXT, 10.5f, LauncherConfig.COL_MUTED, false);
        tvFooter.setGravity(Gravity.CENTER);
        tvFooter.setPadding(0, 0, 0, dp(8));
        footerCol.addView(tvFooter);

        LinearLayout footerPillsRow = new LinearLayout(this);
        footerPillsRow.setOrientation(LinearLayout.HORIZONTAL);
        footerPillsRow.setGravity(Gravity.CENTER);

        View ghFooterPill = makeGitHubPill("GitHub", 10.5f);
        ghFooterPill.setOnClickListener(v -> openUrl(LauncherConfig.COMMUNITY_URL));
        LinearLayout.LayoutParams gfLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gfLp.leftMargin = dp(8);
        footerPillsRow.addView(ghFooterPill, gfLp);

        View igFooterPill = makeInstagramPill("Instagram: " + LauncherConfig.INSTAGRAM_HANDLE, 10.5f, 14, 12, 5);
        igFooterPill.setOnClickListener(v -> openInstagramSupport());
        footerPillsRow.addView(igFooterPill);

        footerCol.addView(footerPillsRow);
        root.addView(footerCol);

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
        dotBg.setColor(Color.parseColor("#0E222E"));
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
            bg.setColor(Color.parseColor("#0E222E"));
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
        bg.setCornerRadius(dp(9));
        bg.setStroke(dp(1), LauncherConfig.COL_BORDER);
        tv.setBackground(bg);
        return tv;
    }

    private TextView makeStoreInfoPill(String text, int fgColor) {
        TextView tv = makeText(text, 10.2f, fgColor, true);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setPadding(dp(9), dp(5), dp(9), dp(5));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(LauncherConfig.COL_CARD_INNER);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), LauncherConfig.COL_BORDER);
        tv.setBackground(bg);
        return tv;
    }

    // =========================================================================
    // عناصر الهوية التحريرية الفخمة (Luxury Dark-Teal & Gold Editorial Helpers)
    // =========================================================================

    /**
     * شارة المطور R.D.AV العائمة (Slide-In / Slide-Out Interactive Badge)
     * تعرض صورة الأفاتار (rdav_avatar.jpg) بإطار ذهبي رفيع وتفتح حساب @R.D.AV على Instagram عند الضغط.
     */
    private View makeFloatingDeveloperBadge() {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(7), dp(5), dp(10), dp(5));

        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.parseColor("#EB07121A"), Color.parseColor("#EB0D2230")}
        );
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), LauncherConfig.COL_BORDER_GOLD);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(12));
        pill.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Color.parseColor("#44F3D078")),
                bg,
                mask
        ));
        pill.setClickable(true);
        pill.setFocusable(true);
        pill.setElevation(dp(4));

        // صورة المطور الدائرية بإطار ذهبي ناعم
        FrameLayout avatarWrap = new FrameLayout(this);
        GradientDrawable avFrame = new GradientDrawable();
        avFrame.setShape(GradientDrawable.OVAL);
        avFrame.setColor(LauncherConfig.COL_CARD_INNER);
        avFrame.setStroke(dp(1), LauncherConfig.COL_GOLD);
        avatarWrap.setBackground(avFrame);
        avatarWrap.setPadding(dp(2), dp(2), dp(2), dp(2));

        ImageView ivAvatar = new ImageView(this);
        ivAvatar.setImageResource(R.drawable.rdav_avatar);
        ivAvatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        clipRounded(ivAvatar, dp(20));
        avatarWrap.addView(ivAvatar, new FrameLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        avLp.leftMargin = dp(8);
        pill.addView(avatarWrap, avLp);

        // نص R.D.AV + شارة Instagram الصغيرة
        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView tvName = makeText("R.D.AV", 11.5f, LauncherConfig.COL_GOLD, true);
        tvName.setSingleLine(true);
        textCol.addView(tvName);

        LinearLayout igSubRow = new LinearLayout(this);
        igSubRow.setOrientation(LinearLayout.HORIZONTAL);
        igSubRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams igSubLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        igSubLp.topMargin = dp(1);

        ImageView ivIg = new ImageView(this);
        ivIg.setImageResource(R.drawable.ic_instagram);
        ivIg.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams igIconLp = new LinearLayout.LayoutParams(dp(11), dp(11));
        igIconLp.leftMargin = dp(4);
        igSubRow.addView(ivIg, igIconLp);

        TextView tvHandle = makeText("@R.D.AV", 9.2f, Color.parseColor("#E2EDF4"), true);
        tvHandle.setSingleLine(true);
        igSubRow.addView(tvHandle);

        textCol.addView(igSubRow, igSubLp);
        pill.addView(textCol);

        pill.setOnClickListener(v -> openInstagramSupport());
        return pill;
    }

    /**
     * شعار R.D.AV الذهبي الدائري المؤطر (مستوحى من ختم الاستوديو التحريري المرجعي).
     * عند enableSecretTap = true يحتفظ ببوابة وضع الاختبار السري (5 نقرات أو ضغطة مطوّلة).
     */
    private View makeRdavFramedEmblem(int sizeDp, boolean enableSecretTap) {
        FrameLayout frame = new FrameLayout(this);
        GradientDrawable outerRing = new GradientDrawable();
        outerRing.setCornerRadius(dp(10));
        outerRing.setColor(Color.parseColor("#E607121A"));
        outerRing.setStroke(dp(1), LauncherConfig.COL_BORDER_GOLD);
        frame.setBackground(outerRing);
        frame.setPadding(dp(4), dp(4), dp(4), dp(4));

        LinearLayout circle = new LinearLayout(this);
        circle.setOrientation(LinearLayout.VERTICAL);
        circle.setGravity(Gravity.CENTER);
        GradientDrawable circleBg = new GradientDrawable();
        circleBg.setShape(GradientDrawable.OVAL);
        circleBg.setColor(Color.parseColor("#0B1B25"));
        circleBg.setStroke(dp(1), LauncherConfig.COL_GOLD);
        circle.setBackground(circleBg);

        TextView tvCode = makeText("R.D.AV", sizeDp >= 52 ? 10f : 9f, LauncherConfig.COL_GOLD, true);
        tvCode.setGravity(Gravity.CENTER);
        tvCode.setSingleLine(true);
        circle.addView(tvCode);

        TextView tvMini = makeText("تعريب", 7.8f, LauncherConfig.COL_MUTED, false);
        tvMini.setGravity(Gravity.CENTER);
        tvMini.setSingleLine(true);
        circle.addView(tvMini);

        frame.addView(circle, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        if (enableSecretTap) {
            frame.setClickable(true);
            frame.setLongClickable(true);
            frame.setOnClickListener(v -> handleSecretBrandTap());
            frame.setOnLongClickListener(v -> {
                showBetaPasswordDialog();
                return true;
            });
        }
        return frame;
    }

    /**
     * فاصل خطي ذهبي رفيع يتوسطه معيّن ذهبي صغير (◆).
     */
    private View makeGoldDiamondDivider() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        View lineRight = new View(this);
        lineRight.setBackgroundColor(LauncherConfig.COL_BORDER);
        row.addView(lineRight, new LinearLayout.LayoutParams(0, dp(1), 1f));

        View diamond = new View(this);
        GradientDrawable dBg = new GradientDrawable();
        dBg.setColor(LauncherConfig.COL_GOLD);
        dBg.setCornerRadius(dp(1));
        diamond.setBackground(dBg);
        diamond.setRotation(45f);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(dp(7), dp(7));
        dLp.setMargins(dp(10), dp(2), dp(10), dp(2));
        row.addView(diamond, dLp);

        View lineLeft = new View(this);
        lineLeft.setBackgroundColor(LauncherConfig.COL_BORDER);
        row.addView(lineLeft, new LinearLayout.LayoutParams(0, dp(1), 1f));
        return row;
    }

    private LinearLayout.LayoutParams makeDividerLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(8);
        return lp;
    }

    /**
     * ترويسة قسم تحريرية: عنوان ذهبي عريض + خط ذهبي رفيع يمتد إلى معيّن سماوي متوهج (◆).
     */
    private View makeEditorialSectionHeader(String title) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView tv = makeText(title, 13.2f, LauncherConfig.COL_GOLD, true);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tvLp.leftMargin = dp(10);
        row.addView(tv, tvLp);

        View line = new View(this);
        line.setBackgroundColor(LauncherConfig.COL_BORDER);
        row.addView(line, new LinearLayout.LayoutParams(0, dp(1), 1f));

        View cyanDiamond = new View(this);
        GradientDrawable cdBg = new GradientDrawable();
        cdBg.setColor(LauncherConfig.COL_CYAN);
        cdBg.setCornerRadius(dp(1));
        cyanDiamond.setBackground(cdBg);
        cyanDiamond.setRotation(45f);
        LinearLayout.LayoutParams cdLp = new LinearLayout.LayoutParams(dp(7), dp(7));
        cdLp.rightMargin = dp(8);
        row.addView(cyanDiamond, cdLp);

        return row;
    }

    private LinearLayout.LayoutParams makeSectionHdrLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(2);
        lp.bottomMargin = dp(9);
        return lp;
    }

    /**
     * شارة معيّن ذهبي مرقّم (◆ 1, 2, 3, 4) لقسم «أبرز التحسينات والإصلاحات».
     */
    private View makeDiamondBadge(int number) {
        FrameLayout box = new FrameLayout(this);

        View rotatedSquare = new View(this);
        GradientDrawable sqBg = new GradientDrawable();
        sqBg.setColor(LauncherConfig.COL_CARD_INNER);
        sqBg.setCornerRadius(dp(2));
        sqBg.setStroke(dp(1), LauncherConfig.COL_BORDER_GOLD);
        rotatedSquare.setBackground(sqBg);
        rotatedSquare.setRotation(45f);
        box.addView(rotatedSquare, new FrameLayout.LayoutParams(dp(23), dp(23), Gravity.CENTER));

        TextView numTv = makeText(String.valueOf(number), 11f, LauncherConfig.COL_GOLD, true);
        numTv.setGravity(Gravity.CENTER);
        box.addView(numTv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        return box;
    }

    /**
     * يبني بطاقات «أبرز التحسينات والإصلاحات» المرقّمة بمعيّنات ذهبية (1, 2, 3, 4)
     * ديناميكياً من حقل changelog في manifest.json مع نصوص افتراضية فصحى أنيقة عند عدم التفصيل.
     */
    private void rebuildEditorialChangelogCards(String rawChangelog) {
        if (changelogCardsContainer == null) {
            return;
        }
        changelogCardsContainer.removeAllViews();

        List<String[]> items = new ArrayList<>();
        if (rawChangelog != null && !rawChangelog.trim().isEmpty()) {
            String[] lines = rawChangelog.replace("\r\n", "\n").split("\n");
            for (String line : lines) {
                String cleaned = line.trim();
                while (cleaned.startsWith("-") || cleaned.startsWith("•") || cleaned.startsWith("*") || cleaned.startsWith("★")) {
                    cleaned = cleaned.substring(1).trim();
                }
                if (cleaned.length() < 4) {
                    continue;
                }
                String title;
                String sub;
                int splitIdx = cleaned.indexOf(" — ");
                if (splitIdx < 0) {
                    splitIdx = cleaned.indexOf(": ");
                }
                if (splitIdx > 3 && splitIdx < cleaned.length() - 3) {
                    title = cleaned.substring(0, splitIdx).trim();
                    sub = cleaned.substring(splitIdx + (cleaned.charAt(splitIdx) == ':' ? 2 : 3)).trim();
                } else {
                    title = cleaned;
                    sub = "تحديث رسمي متزامن من حزمة تعريب R.D.AV";
                }
                items.add(new String[]{title, sub});
                if (items.size() >= 4) {
                    break;
                }
            }
        }

        // استكمال أبرز مزايا حزمة تعريب جوجو لتظهر البطاقات الأربع بتناغم تحريري فخم
        String[][] defaults = {
                {
                        "التوافق الكامل مع أحدث إصدار رسمي (v" + activeProfile.targetGameVersionName + ")",
                        "تعريب عربي فصيح متكامل يشمل القوائم، المهارات، الشخصيات، وحوارات القصة"
                },
                {
                        "ضبط التشكيل العربي والتفاف الأسطر داخل اللعبة",
                        "عرض سليم للحوارات والبطاقات والمهارات بدون تقطيع أو تداخل في النصوص"
                },
                {
                        "حارس الأمان الذكي (Version Safety Guard)",
                        "فحص تلقائي لإصدار اللعبة وبصمة MD5 واستعادة فورية للأصل عند صدور تحديث جديد"
                },
                {
                        "تثبيت مباشر بضغطة زر مع نسخة احتياطية آمنة",
                        "دعم كامل لـ Shizuku و SAF مع مزامنة تلقائية للتحديثات من GitHub Releases"
                }
        };
        int dIdx = 0;
        while (items.size() < 4 && dIdx < defaults.length) {
            items.add(defaults[dIdx++]);
        }

        for (int i = 0; i < items.size(); i++) {
            String[] pair = items.get(i);
            LinearLayout itemCard = new LinearLayout(this);
            itemCard.setOrientation(LinearLayout.HORIZONTAL);
            itemCard.setGravity(Gravity.CENTER_VERTICAL);
            itemCard.setPadding(dp(12), dp(10), dp(12), dp(10));
            GradientDrawable icBg = new GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{Color.parseColor("#0A1822"), Color.parseColor("#0E202C")}
            );
            icBg.setCornerRadius(dp(9));
            icBg.setStroke(dp(1), LauncherConfig.COL_BORDER);
            itemCard.setBackground(icBg);

            // 1) المعيّن الذهبي المرقّم (1, 2, 3, 4) على اليمين (بداية RTL)
            View diamond = makeDiamondBadge(i + 1);
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(dp(34), dp(34));
            dLp.leftMargin = dp(10);
            itemCard.addView(diamond, dLp);

            // 2) عنوان الميزة بخط أبيض عريض + سطر الشرح بالرمادي المزرق الناعم
            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            TextView tvT = makeText(pair[0], 11.8f, LauncherConfig.COL_TEXT, true);
            tvT.setLineSpacing(dp(1), 1f);
            textCol.addView(tvT);

            if (pair[1] != null && !pair[1].isEmpty()) {
                TextView tvS = makeText(pair[1], 10.2f, LauncherConfig.COL_MUTED, false);
                tvS.setPadding(0, dp(3), 0, 0);
                tvS.setLineSpacing(dp(2), 1f);
                textCol.addView(tvS);
            }
            itemCard.addView(textCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            // 3) نجمة/معيّن سماوي صغير متوهج على اليسار (نهاية RTL)
            TextView sparkle = makeText("✦", 10f, LauncherConfig.COL_CYAN, true);
            sparkle.setPadding(0, 0, dp(8), 0);
            itemCard.addView(sparkle);

            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cardLp.bottomMargin = dp(7);
            changelogCardsContainer.addView(itemCard, cardLp);
        }
    }

    /**
     * زر احترافي بأيقونة Vector واضحة وخلفية وحدود متناسقة مع هوية الخدمة أو الوظيفة.
     */
    private LinearLayout makeIconButton(
            String text,
            int iconRes,
            int bgColor,
            int textColor,
            int borderColor,
            float textSizeSp
    ) {
        LinearLayout btn = new LinearLayout(this);
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setGravity(Gravity.CENTER);
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setPadding(dp(10), dp(6), dp(10), dp(6));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), borderColor);
        btn.setBackground(bg);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(18), dp(18));
        iconLp.leftMargin = dp(7);
        btn.addView(icon, iconLp);

        TextView lbl = makeText(text, textSizeSp, textColor, true);
        lbl.setGravity(Gravity.CENTER);
        lbl.setMaxLines(2);
        btn.addView(lbl, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return btn;
    }

    /**
     * شارة/زر Instagram بأيقونة الكاميرا البيضاء وتدرج إنستغرام (#833AB4 -> #E1306C) بسطر واحد غير قابل للالتفاف.
     */
    private LinearLayout makeInstagramPill(String text, float textSizeSp, int iconSizeDp, int padHDp, int padVDp) {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        pill.setClickable(true);
        pill.setFocusable(true);
        pill.setPadding(dp(padHDp), dp(padVDp), dp(padHDp), dp(padVDp));

        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.parseColor("#833AB4"), Color.parseColor("#E1306C")}
        );
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), Color.parseColor("#F472B6"));
        pill.setBackground(bg);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_instagram);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(iconSizeDp), dp(iconSizeDp));
        iconLp.leftMargin = dp(6);
        pill.addView(icon, iconLp);

        TextView lbl = makeText(text, textSizeSp, Color.WHITE, true);
        lbl.setSingleLine(true);
        lbl.setMaxLines(1);
        pill.addView(lbl);
        return pill;
    }

    /**
     * شارة GitHub الداكنة (#161B22 / #30363D) مع شعار Octocat الأبيض.
     */
    private LinearLayout makeGitHubPill(String text, float textSizeSp) {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        pill.setClickable(true);
        pill.setFocusable(true);
        pill.setPadding(dp(12), dp(5), dp(12), dp(5));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#161B22"));
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), Color.parseColor("#30363D"));
        pill.setBackground(bg);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_github_mark);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(14), dp(14));
        iconLp.leftMargin = dp(6);
        pill.addView(icon, iconLp);

        TextView lbl = makeText(text, textSizeSp, Color.parseColor("#F0F6FC"), true);
        lbl.setSingleLine(true);
        lbl.setMaxLines(1);
        pill.addView(lbl);
        return pill;
    }

    private void syncDynamicIcon(GameProfile profile, String iconUrl, String iconHash) {
        if (profile == null || iconUrl == null || iconUrl.trim().isEmpty()) {
            return;
        }
        GameIconCache.syncIconAsync(this, iconExecutor, mainHandler, profile.id, iconUrl, iconHash, bmp -> {
            if (bmp == null) {
                return;
            }
            if (ivHubLogo != null && profile.id.equals(activeProfile.id)) {
                ivHubLogo.setImageBitmap(bmp);
            }
            refreshHomeCards();
        });
    }

    private void syncDynamicMediaForManifest(GameProfile profile, PatchManifest manifest) {
        if (profile == null || manifest == null) {
            return;
        }
        if (!manifest.iconUrl.isEmpty()) {
            syncDynamicIcon(profile, manifest.iconUrl, manifest.iconHash);
        }
        String bannerUrl = manifest.getEffectiveBannerImageUrl();
        if (!bannerUrl.isEmpty()) {
            GameIconCache.syncBannerAsync(
                    this, iconExecutor, mainHandler,
                    profile.id, bannerUrl, manifest.getEffectiveBannerImageHash(),
                    bmp -> {
                        if (bmp == null) {
                            return;
                        }
                        if (profile.id.equals(activeProfile.id)) {
                            if (ivHubPoster != null) {
                                ivHubPoster.setImageBitmap(bmp);
                            }
                            if (activeRemoteHeroVideoFile == null) {
                                usingRemoteBannerImage = true;
                                pausePromoVideo();
                                if (promoTextureView != null) {
                                    promoTextureView.setVisibility(View.GONE);
                                }
                            }
                        }
                        refreshHomeCards();
                    });
        }
        String videoUrl = manifest.getEffectiveHeroVideoUrl();
        if (!videoUrl.isEmpty()) {
            GameIconCache.syncHeroVideoAsync(
                    this, iconExecutor, mainHandler,
                    profile.id, videoUrl, manifest.getEffectiveHeroVideoHash(),
                    file -> {
                        if (file != null && profile.id.equals(activeProfile.id)) {
                            activeRemoteHeroVideoFile = file;
                            usingRemoteBannerImage = false;
                            if (isOnGameHub && promoTextureView != null) {
                                promoTextureView.setVisibility(View.VISIBLE);
                                if (promoTextureView.isAvailable()) {
                                    startPromoVideoOnSurface(promoTextureView.getSurfaceTexture());
                                }
                            }
                        }
                    });
        }
        syncStoreScreenshotsForProfile(profile, manifest);
    }

    private void syncStoreScreenshotsForProfile(GameProfile profile, PatchManifest manifest) {
        if (profile == null) {
            return;
        }
        List<String> urls = (manifest != null && !manifest.screenshotsUrls.isEmpty())
                ? manifest.screenshotsUrls
                : profile.defaultScreenshotsUrls;
        String hash = (manifest != null && !manifest.screenshotsUrls.isEmpty())
                ? manifest.screenshotsHash
                : profile.defaultScreenshotsHash;
        if (urls == null || urls.isEmpty()) {
            return;
        }
        GameIconCache.syncScreenshotsAsync(
                this, iconExecutor, mainHandler,
                profile.id, urls, hash,
                files -> {
                    if (profile.id.equals(activeProfile.id)) {
                        renderStoreScreenshotsCarousel(files);
                    }
                });
    }

    private void renderStoreScreenshotsCarousel(List<File> files) {
        if (storeScreenshotsSection == null || storeScreenshotsRow == null) {
            return;
        }
        if (files == null || files.isEmpty()) {
            lastRenderedShotsSignature = "";
            storeScreenshotsRow.removeAllViews();
            storeScreenshotsSection.setVisibility(View.GONE);
            return;
        }
        StringBuilder sigSb = new StringBuilder();
        sigSb.append(activeProfile != null ? activeProfile.id : "").append('|').append(files.size());
        for (File f : files) {
            sigSb.append(';').append(f.getName()).append(':').append(f.length()).append(':').append(f.lastModified());
        }
        String sig = sigSb.toString();
        if (storeScreenshotsRow.getChildCount() > 0 && sig.equals(lastRenderedShotsSignature)) {
            storeScreenshotsSection.setVisibility(View.VISIBLE);
            return;
        }
        lastRenderedShotsSignature = sig;
        storeScreenshotsRow.removeAllViews();
        storeScreenshotsSection.setVisibility(View.VISIBLE);
        final int total = files.size();
        for (int i = 0; i < total; i++) {
            final int idx = i;
            final File shotFile = files.get(i);

            FrameLayout card = new FrameLayout(this);
            GradientDrawable borderBg = new GradientDrawable();
            borderBg.setColor(LauncherConfig.COL_CARD_INNER);
            borderBg.setCornerRadius(dp(12));
            borderBg.setStroke(dp(1), Color.parseColor("#88C8A252"));
            card.setBackground(borderBg);
            card.setPadding(dp(2), dp(2), dp(2), dp(2));

            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            clipRounded(iv, dp(10));
            card.addView(iv, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

            // استخدام الكاش الفوري من الذاكرة (0ms) إن وُجد، وإلا القراءة السريعة من القرص المحلي
            Bitmap memThumb = GameIconCache.getMemoryCachedThumbnail(shotFile, 520);
            if (memThumb != null) {
                iv.setImageBitmap(memThumb);
            } else {
                iconExecutor.execute(() -> {
                    Bitmap thumb = GameIconCache.decodeScaledBitmap(shotFile, 520);
                    if (thumb != null) {
                        mainHandler.post(() -> iv.setImageBitmap(thumb));
                    }
                });
            }

            // شارة ترقيم خفيفة في الزاوية للتكبير التفاعلي
            TextView badge = makeText((idx + 1) + "/" + total, 8.8f, LauncherConfig.COL_GOLD, true);
            badge.setPadding(dp(6), dp(2), dp(6), dp(2));
            GradientDrawable bBg = new GradientDrawable();
            bBg.setColor(Color.parseColor("#B807121A"));
            bBg.setCornerRadius(dp(6));
            bBg.setStroke(dp(1), Color.parseColor("#66C8A252"));
            badge.setBackground(bBg);
            FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.LEFT);
            bLp.setMargins(dp(6), dp(6), dp(6), dp(6));
            card.addView(badge, bLp);

            card.setClickable(true);
            card.setFocusable(true);
            card.setOnClickListener(v -> expandScreenshotLightbox(card, shotFile, idx, total));

            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(dp(178), dp(104));
            cardLp.leftMargin = dp(8);
            storeScreenshotsRow.addView(card, cardLp);
        }
    }

    private FrameLayout buildScreenshotLightboxOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.parseColor("#E6050D13"));
        overlay.setVisibility(View.GONE);
        overlay.setAlpha(0f);
        overlay.setClickable(true);
        overlay.setFocusable(true);
        overlay.setOnClickListener(v -> collapseScreenshotLightbox());

        lightboxCardWrap = new FrameLayout(this);
        GradientDrawable frameBg = new GradientDrawable();
        frameBg.setColor(Color.parseColor("#08141C"));
        frameBg.setCornerRadius(dp(14));
        frameBg.setStroke(dp(1), LauncherConfig.COL_BORDER_GOLD);
        lightboxCardWrap.setBackground(frameBg);
        lightboxCardWrap.setPadding(dp(3), dp(3), dp(3), dp(3));

        ivLightboxImage = new ImageView(this);
        ivLightboxImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        ivLightboxImage.setAdjustViewBounds(true);
        clipRounded(ivLightboxImage, dp(11));
        ivLightboxImage.setClickable(true);
        ivLightboxImage.setOnClickListener(v -> collapseScreenshotLightbox());
        lightboxCardWrap.addView(ivLightboxImage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        FrameLayout.LayoutParams wrapLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        wrapLp.setMargins(dp(14), dp(52), dp(14), dp(42));
        overlay.addView(lightboxCardWrap, wrapLp);

        tvLightboxHint = makeText("اضغط على الصورة للتصغير والعودة", 10.8f, LauncherConfig.COL_GOLD, true);
        tvLightboxHint.setPadding(dp(14), dp(6), dp(14), dp(6));
        GradientDrawable hintBg = new GradientDrawable();
        hintBg.setColor(Color.parseColor("#CC0B1E28"));
        hintBg.setCornerRadius(dp(18));
        hintBg.setStroke(dp(1), Color.parseColor("#88C8A252"));
        tvLightboxHint.setBackground(hintBg);
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hintLp.topMargin = dp(22);
        overlay.addView(tvLightboxHint, hintLp);

        return overlay;
    }

    private void expandScreenshotLightbox(View originThumb, File shotFile, int index, int total) {
        if (screenshotLightboxOverlay == null || ivLightboxImage == null || lightboxCardWrap == null || shotFile == null) {
            return;
        }
        if (isLightboxAnimating || isLightboxOpen) {
            return;
        }
        isLightboxAnimating = true;
        lightboxOriginView = originThumb;

        iconExecutor.execute(() -> {
            Bitmap fullBmp = GameIconCache.decodeScaledBitmap(shotFile, 1600);
            mainHandler.post(() -> {
                if (fullBmp == null || screenshotLightboxOverlay == null) {
                    isLightboxAnimating = false;
                    return;
                }
                ivLightboxImage.setImageBitmap(fullBmp);
                if (tvLightboxHint != null) {
                    tvLightboxHint.setText("لقطة " + (index + 1) + " من " + total + "  •  اضغط على الصورة للتصغير");
                }
                screenshotLightboxOverlay.setAlpha(0f);
                screenshotLightboxOverlay.setVisibility(View.VISIBLE);
                lightboxCardWrap.setAlpha(0f);

                lightboxCardWrap.post(() -> {
                    int[] origLoc = new int[2];
                    int[] targetLoc = new int[2];
                    if (originThumb != null) {
                        originThumb.getLocationInWindow(origLoc);
                    }
                    lightboxCardWrap.getLocationInWindow(targetLoc);

                    float origW = originThumb != null && originThumb.getWidth() > 0 ? originThumb.getWidth() : dp(178);
                    float origH = originThumb != null && originThumb.getHeight() > 0 ? originThumb.getHeight() : dp(104);
                    float targetW = Math.max(1f, lightboxCardWrap.getWidth());
                    float targetH = Math.max(1f, lightboxCardWrap.getHeight());

                    float startScaleX = Math.max(0.18f, Math.min(0.95f, origW / targetW));
                    float startScaleY = Math.max(0.18f, Math.min(0.95f, origH / targetH));
                    float startDx = (origLoc[0] + origW / 2f) - (targetLoc[0] + targetW / 2f);
                    float startDy = (origLoc[1] + origH / 2f) - (targetLoc[1] + targetH / 2f);

                    lightboxCardWrap.setScaleX(startScaleX);
                    lightboxCardWrap.setScaleY(startScaleY);
                    lightboxCardWrap.setTranslationX(startDx);
                    lightboxCardWrap.setTranslationY(startDy);
                    lightboxCardWrap.setAlpha(1f);

                    screenshotLightboxOverlay.animate().cancel();
                    lightboxCardWrap.animate().cancel();

                    screenshotLightboxOverlay.animate()
                            .alpha(1f)
                            .setDuration(260L)
                            .setInterpolator(new DecelerateInterpolator())
                            .start();

                    lightboxCardWrap.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .translationX(0f)
                            .translationY(0f)
                            .setDuration(270L)
                            .setInterpolator(new DecelerateInterpolator())
                            .withEndAction(() -> {
                                isLightboxOpen = true;
                                isLightboxAnimating = false;
                            })
                            .start();
                });
            });
        });
    }

    private void collapseScreenshotLightbox() {
        if (screenshotLightboxOverlay == null || lightboxCardWrap == null || !isLightboxOpen || isLightboxAnimating) {
            return;
        }
        isLightboxAnimating = true;

        float endScaleX = 0.25f;
        float endScaleY = 0.25f;
        float endDx = 0f;
        float endDy = 0f;

        if (lightboxOriginView != null && lightboxOriginView.isAttachedToWindow()) {
            int[] origLoc = new int[2];
            int[] targetLoc = new int[2];
            lightboxOriginView.getLocationInWindow(origLoc);
            lightboxCardWrap.getLocationInWindow(targetLoc);

            float origW = Math.max(1f, lightboxOriginView.getWidth());
            float origH = Math.max(1f, lightboxOriginView.getHeight());
            float targetW = Math.max(1f, lightboxCardWrap.getWidth());
            float targetH = Math.max(1f, lightboxCardWrap.getHeight());

            endScaleX = Math.max(0.18f, Math.min(0.95f, origW / targetW));
            endScaleY = Math.max(0.18f, Math.min(0.95f, origH / targetH));
            endDx = (origLoc[0] + origW / 2f) - (targetLoc[0] + targetW / 2f);
            endDy = (origLoc[1] + origH / 2f) - (targetLoc[1] + targetH / 2f);
        }

        screenshotLightboxOverlay.animate().cancel();
        lightboxCardWrap.animate().cancel();

        screenshotLightboxOverlay.animate()
                .alpha(0f)
                .setDuration(220L)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();

        lightboxCardWrap.animate()
                .scaleX(endScaleX)
                .scaleY(endScaleY)
                .translationX(endDx)
                .translationY(endDy)
                .alpha(0.15f)
                .setDuration(220L)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(() -> {
                    screenshotLightboxOverlay.setVisibility(View.GONE);
                    lightboxCardWrap.setScaleX(1f);
                    lightboxCardWrap.setScaleY(1f);
                    lightboxCardWrap.setTranslationX(0f);
                    lightboxCardWrap.setTranslationY(0f);
                    lightboxCardWrap.setAlpha(1f);
                    isLightboxOpen = false;
                    isLightboxAnimating = false;
                    lightboxOriginView = null;
                })
                .start();
    }

    private void applyDynamicMetadataToUi() {
        PatchManifest m = latestRemoteManifest;

        // 1) تحديث أيقونة شاشة التفاصيل من الذاكرة المخبأة أو المدمجة
        if (ivHubLogo != null) {
            Bitmap cached = GameIconCache.loadCachedBitmap(this, activeProfile.id);
            if (cached != null) {
                ivHubLogo.setImageBitmap(cached);
            } else {
                ivHubLogo.setImageResource(activeProfile.iconRes);
            }
        }

        // 1.5) تحديث البانر الخلفي العلوي أو مقطع الفيديو الحي من banner_url / hero_media_url
        String remoteBannerUrl = m != null ? m.getEffectiveBannerImageUrl() : "";
        String remoteVideoUrl = m != null ? m.getEffectiveHeroVideoUrl() : "";
        Bitmap cachedBanner = !remoteBannerUrl.isEmpty()
                ? GameIconCache.loadCachedBannerBitmap(this, activeProfile.id)
                : null;
        if (ivHubPoster != null) {
            if (cachedBanner != null) {
                ivHubPoster.setImageBitmap(cachedBanner);
            } else {
                ivHubPoster.setImageResource(activeProfile.bannerRes != 0 ? activeProfile.bannerRes : R.drawable.bg_promo_poster);
            }
        }
        if (!remoteVideoUrl.isEmpty()) {
            File cachedVid = GameIconCache.loadCachedHeroVideoFile(this, activeProfile.id);
            activeRemoteHeroVideoFile = cachedVid;
            usingRemoteBannerImage = false;
            if (promoTextureView != null) {
                promoTextureView.setVisibility(View.VISIBLE);
            }
        } else if (cachedBanner != null || activeProfile.promoVideoRes == 0) {
            // عند رفع صورة بانر مخصصة أو إذا كانت اللعبة لا تملك فيديو ترويجي مدمج، نُبرز البانر بدون تشغيل فيديو جوجو
            activeRemoteHeroVideoFile = null;
            usingRemoteBannerImage = true;
            pausePromoVideo();
            if (promoTextureView != null) {
                promoTextureView.setVisibility(View.GONE);
            }
        } else {
            activeRemoteHeroVideoFile = null;
            usingRemoteBannerImage = false;
            if (promoTextureView != null) {
                promoTextureView.setVisibility(View.VISIBLE);
            }
        }

        // 1.75) تحديث حقول الهوية العلوية للبطاقة عن بُعد من Mobile Hub Studio (الاسم، العنوان الفرعي، الشارة، وحساب التواصل)
        if (tvHubEyebrow != null) {
            tvHubEyebrow.setText((m != null && !m.heroEyebrow.isEmpty())
                    ? m.heroEyebrow
                    : "ANDROID • ARABIC LOCALIZATION UPDATE");
        }
        if (tvHubTitle != null) {
            tvHubTitle.setText((m != null && !m.displayTitle.isEmpty())
                    ? m.displayTitle
                    : activeProfile.title);
        }
        if (tvHubSubtitle != null) {
            String fallbackSubtitle = (activeProfile.subtitle != null && !activeProfile.subtitle.isEmpty())
                    ? activeProfile.subtitle
                    : "تحديث تعريب جوجو: الترنيمة الذهبية — للأندرويد";
            tvHubSubtitle.setText((m != null && !m.heroTagline.isEmpty())
                    ? m.heroTagline
                    : fallbackSubtitle);
        }
        if (tvHubBadgeLabel != null) {
            tvHubBadgeLabel.setText((m != null && !m.badgeLabel.isEmpty())
                    ? m.badgeLabel
                    : "تحديث تعريب");
        }
        activeSocialUrl = (m != null && !m.socialUrl.isEmpty()) ? m.socialUrl : "";
        if (hubSocialPill != null && hubSocialPill.getChildCount() > 1 && hubSocialPill.getChildAt(1) instanceof TextView) {
            String socialTxt = (m != null && !m.socialLabel.isEmpty())
                    ? m.socialLabel
                    : ("Instagram: " + LauncherConfig.INSTAGRAM_HANDLE);
            if (!socialTxt.contains("↗")) {
                socialTxt = socialTxt + " ↗";
            }
            ((TextView) hubSocialPill.getChildAt(1)).setText(socialTxt);
        }

        // 2) تحديث بطاقة معلومات المتجر + معرض لقطات الشاشة المخزّن بالبصمة محلياً
        String score = (m != null && !m.storeScore.isEmpty()) ? m.storeScore : activeProfile.defaultStoreScore;
        String installs = (m != null && !m.storeInstalls.isEmpty()) ? m.storeInstalls : activeProfile.defaultStoreInstalls;
        String dev = (m != null && !m.storeDeveloper.isEmpty()) ? m.storeDeveloper : activeProfile.defaultStoreDeveloper;
        String summary = (m != null && !m.storeSummary.isEmpty()) ? m.storeSummary : activeProfile.defaultStoreSummary;

        if (tvStoreScorePill != null) {
            tvStoreScorePill.setText("⭐ " + (score.isEmpty() ? "—" : score));
        }
        if (tvStoreInstallsPill != null) {
            tvStoreInstallsPill.setText("⬇ " + (installs.isEmpty() ? "—" : installs));
        }
        if (tvStoreDevPill != null) {
            tvStoreDevPill.setText("🏢 " + (dev.isEmpty() ? "—" : dev));
        }
        if (tvStoreSummary != null) {
            tvStoreSummary.setText(summary.isEmpty() ? activeProfile.subtitle : summary);
        }

        List<File> cachedShots = GameIconCache.loadCachedScreenshotFiles(this, activeProfile.id);
        renderStoreScreenshotsCarousel(cachedShots);
        syncStoreScreenshotsForProfile(activeProfile, m);

        // 3) تحديث شرائح الإحصائيات المختصرة (متضمنة حجم حزمة التعريب بالميغابايت)
        if (tvStatLinesChip != null) {
            int count = (m != null && m.statsTranslatedStrings > 0)
                    ? m.statsTranslatedStrings
                    : activeProfile.defaultTranslatedStrings;
            if (count > 0) {
                tvStatLinesChip.setText(String.format(Locale.US, "%,d نصاً معرّباً بالفصحى", count));
            } else {
                tvStatLinesChip.setText(activeProfile.statLines);
            }
        }
        if (tvStatSizeChip != null) {
            String sizeMb = m != null ? m.getDisplayPatchSizeMb() : "";
            tvStatSizeChip.setText("📦  حجم الحزمة: " + (sizeMb.isEmpty() ? "—" : sizeMb));
        }
        if (tvStatCompatChip != null) {
            String gVer = (m != null && !m.gameVersion.isEmpty()) ? m.gameVersion : activeProfile.targetGameVersionName;
            tvStatCompatChip.setText("متوافق 100% مع الإصدار " + gVer);
        }

        // 4) تحديث شريط شارات الإصدار التحريري وقسم «أبرز التحسينات والإصلاحات» المرقّم بمعيّنات ذهبية
        if (tvEditorialVersionBox != null) {
            String vStr = (m != null && !m.latestPatchVersion.isEmpty()) ? m.latestPatchVersion : "1.0.0";
            tvEditorialVersionBox.setText(vStr.startsWith("v") ? vStr : ("v" + vStr));
        }
        if (tvEditorialCompatText != null) {
            String gVer = (m != null && !m.gameVersion.isEmpty()) ? m.gameVersion : activeProfile.targetGameVersionName;
            tvEditorialCompatText.setText("متوافق مع الإصدار " + gVer);
        }
        rebuildEditorialChangelogCards(m != null ? m.changelog : "");

        // 5) تحديث شريط حارس الأمان والتنبيهات الحية عن بُعد (security_guard + latest_game_version / latest_patch_version)
        if (hubRemoteSecurityBanner != null && tvRemoteSecurityTitle != null && tvRemoteSecurityMsg != null) {
            if (m != null && (!m.securitySafe || m.securityMaintenance)) {
                GradientDrawable bg = new GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{Color.parseColor("#4C1022"), Color.parseColor("#2B0A14")}
                );
                bg.setCornerRadius(dp(10));
                bg.setStroke(dp(1), LauncherConfig.COL_DANGER);
                hubRemoteSecurityBanner.setBackground(bg);
                tvRemoteSecurityTitle.setText(m.securityMaintenance
                        ? "🛠  صيانة / تحديث حزمة التعريب قيد التجهيز"
                        : "🛡  تنبيه حارس الأمان عن بُعد");
                tvRemoteSecurityTitle.setTextColor(Color.parseColor("#FDA4AF"));
                tvRemoteSecurityMsg.setText(!m.securityMessage.isEmpty()
                        ? m.securityMessage
                        : LauncherConfig.SAFETY_GUARD_ALERT_MESSAGE);
                hubRemoteSecurityBanner.setVisibility(View.VISIBLE);
            } else if (m != null && isPatchCurrentlyInstalled
                    && !installedPatchVersion.isEmpty()
                    && !installedPatchVersion.equals(m.latestPatchVersion)
                    && !installedPatchVersion.startsWith("beta-")) {
                GradientDrawable bg = new GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{Color.parseColor("#2E230B"), Color.parseColor("#181409")}
                );
                bg.setCornerRadius(dp(10));
                bg.setStroke(dp(1), LauncherConfig.COL_BORDER_GOLD);
                hubRemoteSecurityBanner.setBackground(bg);
                tvRemoteSecurityTitle.setText("✨  يتوفر إصدار تعريب جديد (v" + m.latestPatchVersion + ")");
                tvRemoteSecurityTitle.setTextColor(LauncherConfig.COL_GOLD);
                tvRemoteSecurityMsg.setText("الإصدار المثبّت حالياً: " + installedPatchVersion
                        + " • اضغط «تحديث أونلاين (GitHub)» للحصول على أحدث تعريب فوراً.");
                hubRemoteSecurityBanner.setVisibility(View.VISIBLE);
            } else if (m != null && !m.latestGameVersion.isEmpty()
                    && !m.latestGameVersion.equals(m.gameVersion)) {
                GradientDrawable bg = new GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{Color.parseColor("#2E230B"), Color.parseColor("#181409")}
                );
                bg.setCornerRadius(dp(10));
                bg.setStroke(dp(1), LauncherConfig.COL_WARN);
                hubRemoteSecurityBanner.setBackground(bg);
                tvRemoteSecurityTitle.setText("⚠  صدر تحديث جديد للعبة في المتجر (v" + m.latestGameVersion + ")");
                tvRemoteSecurityTitle.setTextColor(LauncherConfig.COL_GOLD);
                tvRemoteSecurityMsg.setText("التعريب الحالي متوافق مع v" + m.gameVersion
                        + " — يرجى انتظار تحديث حزمة التعريب قبل تحديث اللعبة من المتجر.");
                hubRemoteSecurityBanner.setVisibility(View.VISIBLE);
            } else {
                hubRemoteSecurityBanner.setVisibility(View.GONE);
            }
        }
    }

    /**
     * نافذة منبثقة احترافية ونظيفة لعرض «إحصائيات حزمة التعريب» للاعبين
     * (بدون كشف أي تفاصيل تقنية داخلية مثل محرك الحقن أو بصمات MD5 / SHA-256).
     */
    private void showPatchStatsModal() {
        PatchManifest m = latestRemoteManifest;

        int translatedCount = (m != null && m.statsTranslatedStrings > 0)
                ? m.statsTranslatedStrings
                : activeProfile.defaultTranslatedStrings;
        String translatedText = translatedCount > 0
                ? String.format(Locale.US, "%,d نصاً معرّباً بالفصحى", translatedCount)
                : activeProfile.statLines;

        String patchSizeMb = m != null ? m.getDisplayPatchSizeMb() : "";
        String sizeDisplay = !patchSizeMb.isEmpty() ? patchSizeMb : "يُحتسب تلقائياً عند التحديث";

        String currentPatchVer;
        if (isPatchCurrentlyInstalled && !installedPatchVersion.isEmpty()) {
            currentPatchVer = installedPatchVersion;
        } else if (m != null && !m.patchVersion.isEmpty()) {
            currentPatchVer = "v" + m.patchVersion + " (متاح للتثبيت)";
        } else {
            currentPatchVer = activeProfile.patchVersionLabel;
        }

        String channelLabel = (m != null && m.isBetaChannel()) ? "🧪 Beta Test (اختبار)" : "🚀 إصدار رسمي (Public)";
        String gameVer = (m != null && !m.gameVersion.isEmpty()) ? m.gameVersion : activeProfile.targetGameVersionName;
        String releaseDate = (m != null && !m.releaseDate.isEmpty()) ? m.releaseDate : "2026-11-22";
        String securityStatus = (m == null || (m.securitySafe && !m.securityMaintenance))
                ? "🛡  مفحوص ومعتمد ✓"
                : "⚠  قيد الفحص والتحديث الأمني";

        ScrollView scroll = new ScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(16), dp(14), dp(16), dp(10));
        container.setBackgroundColor(LauncherConfig.COL_BG);

        // ترويسة النافذة
        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.VERTICAL);
        hdr.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable hdrBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor("#102634"), LauncherConfig.COL_CARD}
        );
        hdrBg.setCornerRadius(dp(10));
        hdrBg.setStroke(dp(1), LauncherConfig.COL_BORDER_GOLD);
        hdr.setBackground(hdrBg);
        hdr.addView(makeText("📊  إحصائيات حزمة التعريب", 15.5f, LauncherConfig.COL_GOLD, true));
        TextView sub = makeText(activeProfile.title + " • " + channelLabel, 11f, LauncherConfig.COL_CYAN, false);
        sub.setPadding(0, dp(3), 0, 0);
        hdr.addView(sub);
        LinearLayout.LayoutParams hdrLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hdrLp.bottomMargin = dp(10);
        container.addView(hdr, hdrLp);

        addModalDetailRow(container, "إجمالي النصوص المعرّبة", translatedText, LauncherConfig.COL_GOLD, false);
        addModalDetailRow(container, "📦  حجم حزمة التعريب", sizeDisplay, LauncherConfig.COL_CYAN, false);
        addModalDetailRow(container, "إصدار التعريب الحالي", currentPatchVer, LauncherConfig.COL_TEXT, false);
        addModalDetailRow(container, "إصدار اللعبة المتوافق", "v" + gameVer, LauncherConfig.COL_SUCCESS, false);
        addModalDetailRow(container, "تاريخ التحديث", releaseDate, LauncherConfig.COL_TEXT, false);
        addModalDetailRow(container, "حالة الفحص الأمني", securityStatus, Color.parseColor("#6EE7B7"), false);

        scroll.addView(container);

        final String copyPayload = "=== إحصائيات حزمة تعريب R.D.AV ===\n"
                + "اللعبة: " + activeProfile.title + "\n"
                + "إجمالي النصوص المعرّبة: " + translatedText + "\n"
                + "حجم حزمة التعريب: " + sizeDisplay + "\n"
                + "إصدار التعريب: " + currentPatchVer + "\n"
                + "إصدار اللعبة المتوافق: v" + gameVer + "\n"
                + "تاريخ التحديث: " + releaseDate + "\n"
                + "حالة الفحص الأمني: مفحوص ومعتمد ✓";

        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        b.setView(scroll);
        b.setPositiveButton("نسخ الإحصائيات", (d, w) -> copyReportToClipboard(copyPayload, false));
        b.setNegativeButton("إغلاق", null);
        b.show();
    }

    private void addModalDetailRow(LinearLayout parent, String label, String value, int valueColor, boolean monospaceLtr) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(LauncherConfig.COL_CARD_INNER);
        bg.setCornerRadius(dp(9));
        bg.setStroke(dp(1), LauncherConfig.COL_BORDER);
        box.setBackground(bg);

        TextView tvLbl = makeText(label, 10.5f, LauncherConfig.COL_MUTED, false);
        box.addView(tvLbl);

        TextView tvVal = makeText(value, monospaceLtr ? 10.2f : 12.2f, valueColor, true);
        tvVal.setPadding(0, dp(3), 0, 0);
        tvVal.setTextIsSelectable(true);
        if (monospaceLtr) {
            tvVal.setTypeface(Typeface.MONOSPACE);
            tvVal.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        }
        box.addView(tvVal);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(7);
        parent.addView(box, lp);
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
        setBadgeStyle(tvBadge, Color.parseColor("#0C212E"), LauncherConfig.COL_CYAN);
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
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#0E202C"), LauncherConfig.COL_CARD}
        );
        bg.setCornerRadius(dp(10));
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
        btn.setTextSize(12.2f);
        btn.setTextColor(textColor);
        btn.setTypeface(bold ? fontBold : fontRegular);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(10));
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
                new int[]{LauncherConfig.COL_GOLD, LauncherConfig.COL_GOLD_SOLID, LauncherConfig.COL_CYAN}
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
        String activePkg = activeProfile != null ? activeProfile.packageName : "";
        if (activePkg != null && !activePkg.trim().isEmpty()) {
            try {
                PackageInfo info = pm.getPackageInfo(activePkg.trim(), 0);
                if (info != null) {
                    return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
                }
            } catch (Throwable ignored) {
            }
            if (!"jojo_golden_hymn".equals(activeProfile.id)) {
                return -1L;
            }
        }
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

    private static String normalizeVersionName(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        while (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1).trim();
        }
        return s;
    }

    @SuppressWarnings("deprecation")
    private String getInstalledGameApkSignatureSha256(String explicitPkg) {
        try {
            PackageManager pm = getPackageManager();
            String pkg = (explicitPkg != null && !explicitPkg.trim().isEmpty())
                    ? explicitPkg.trim()
                    : (activeProfile != null ? activeProfile.packageName : LauncherConfig.GAME_PACKAGE);
            if (pkg == null || pkg.isEmpty()) {
                return "";
            }
            Signature[] sigs = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
                if (pi != null && pi.signingInfo != null) {
                    SigningInfo si = pi.signingInfo;
                    sigs = si.hasMultipleSigners() ? si.getApkContentsSigners() : si.getSigningCertificateHistory();
                }
            } else {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
                if (pi != null) {
                    sigs = pi.signatures;
                }
            }
            if (sigs == null || sigs.length == 0 || sigs[0] == null) {
                return "";
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sigs[0].toByteArray());
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format(Locale.US, "%02X", b & 0xFF));
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private File getLocalPatchCacheZip(String gameId) {
        File dir = new File(getFilesDir(), "patch_cache/" + (gameId != null ? gameId : "default"));
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, "cached_patch.zip");
    }

    private void saveLocalPatchCache(
            String gameId,
            File sourceZip,
            String patchVersion,
            String targetSubpath,
            long apkVersionCode,
            String apkVersionName,
            String apkSigSha256
    ) {
        if (gameId == null || sourceZip == null || !sourceZip.isFile()) {
            return;
        }
        try {
            File dstZip = getLocalPatchCacheZip(gameId);
            ShellBridgeServer.copyFileAtomically(sourceZip, dstZip);
            SharedPreferences prefs = getSharedPreferences("rdav_ota_cache", MODE_PRIVATE);
            prefs.edit()
                    .putString("ver_" + gameId, patchVersion != null ? patchVersion : "")
                    .putString("subpath_" + gameId, targetSubpath != null ? targetSubpath : "")
                    .putLong("apk_vc_" + gameId, apkVersionCode)
                    .putString("apk_vname_" + gameId, normalizeVersionName(apkVersionName))
                    .putString("apk_sig_" + gameId, apkSigSha256 != null ? apkSigSha256 : "")
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private void saveLocalPatchCacheFromUri(
            String gameId,
            Uri zipUri,
            String patchVersion,
            String targetSubpath,
            long apkVersionCode,
            String apkVersionName,
            String apkSigSha256
    ) {
        if (gameId == null || zipUri == null) {
            return;
        }
        try {
            File dstZip = getLocalPatchCacheZip(gameId);
            File tmp = new File(dstZip.getParentFile(), "cached_patch.zip.tmp");
            try (InputStream in = getContentResolver().openInputStream(zipUri);
                 FileOutputStream out = new FileOutputStream(tmp)) {
                if (in == null) {
                    return;
                }
                byte[] buf = new byte[65536];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                }
                out.flush();
            }
            if (dstZip.exists()) {
                dstZip.delete();
            }
            tmp.renameTo(dstZip);
            SharedPreferences prefs = getSharedPreferences("rdav_ota_cache", MODE_PRIVATE);
            prefs.edit()
                    .putString("ver_" + gameId, patchVersion != null ? patchVersion : "local-zip")
                    .putString("subpath_" + gameId, targetSubpath != null ? targetSubpath : "")
                    .putLong("apk_vc_" + gameId, apkVersionCode)
                    .putString("apk_vname_" + gameId, normalizeVersionName(apkVersionName))
                    .putString("apk_sig_" + gameId, apkSigSha256 != null ? apkSigSha256 : "")
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private boolean hasMatchingLocalPatchCache(String gameId, long installedApkVc) {
        if (gameId == null) {
            return false;
        }
        File cacheZip = getLocalPatchCacheZip(gameId);
        if (!cacheZip.isFile() || cacheZip.length() < 1024) {
            return false;
        }
        SharedPreferences prefs = getSharedPreferences("rdav_ota_cache", MODE_PRIVATE);
        long cachedVc = prefs.getLong("apk_vc_" + gameId, -1L);
        return cachedVc <= 0 || installedApkVc <= 0 || cachedVc == installedApkVc;
    }

    /**
     * المزامنة الذكية والإصلاح الذاتي (Smart OTA Sync & Auto-Reapply):
     * يعيد حقن حزمة التعريب المحفوظة محلياً دون إنترنت، مع أخذ نسخة احتياطية محدّثة (Fresh Backup)
     * من ملفات OTA الجديدة وتطبيق التعديل الموضعي على الكتالوج الحي (In-Place Catalog Patching).
     */
    private boolean autoReapplyCachedPatchSync(AccessMode mode, String gameId) {
        if (gameId == null || !isReadyAccessMode(mode)) {
            return false;
        }
        File cacheZip = getLocalPatchCacheZip(gameId);
        if (!cacheZip.isFile()) {
            return false;
        }
        SharedPreferences prefs = getSharedPreferences("rdav_ota_cache", MODE_PRIVATE);
        String patchVer = prefs.getString("ver_" + gameId, "ota-sync");
        String targetSubpath = prefs.getString("subpath_" + gameId, "");

        File workDir = new File(getHintDir(), "rdav_ota_sync_work");
        File stageDir = new File(workDir, "stage");
        try {
            ShellBridgeServer.deleteRecursive(workDir);
            stageDir.mkdirs();
            PatchDownloader.extractZipFile(cacheZip, stageDir, null, 0, 50);
            File effectiveStage = PatchDownloader.resolveEffectiveStageRoot(stageDir);
            ensureBd2FontBundleStaged(latestRemoteManifest, effectiveStage, 50, 55, false);

            PatchStrategy strategy = activeProfile != null ? activeProfile.patchStrategy : PatchStrategy.ASSET_REPLACE;
            strategy.validateStagedPayload(effectiveStage, activeProfile);
            String normSubpath = strategy.resolveDefaultTargetSubpath(targetSubpath);
            String activePkg = activeProfile != null ? activeProfile.packageName : "";

            String result;
            if (mode == AccessMode.BRIDGE_ACTIVE) {
                result = sendBridgeCommandWithProgress(
                        "APPLY_INSTALL\t" + effectiveStage.getAbsolutePath() + "\t" + patchVer + "\t" + normSubpath + "\t" + activePkg,
                        180000,
                        null
                );
            } else if (mode == AccessMode.DIRECT_FS) {
                result = ShellBridgeServer.applyInstall(effectiveStage, patchVer, normSubpath, activePkg, null);
            } else {
                Uri tree = SafHelper.findGrantedTreeUri(this, activePkg);
                result = tree != null
                        ? SafHelper.applyInstall(this, tree, effectiveStage, patchVer, normSubpath, activePkg, null)
                        : "ERR|NO_SAF";
            }
            if (result != null && result.startsWith("OK|")) {
                isPatchCurrentlyInstalled = true;
                installedPatchVersion = patchVer;
                return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        } finally {
            ShellBridgeServer.deleteRecursive(workDir);
        }
    }

    private SafetyCheckResult evaluateSafetyGuardSync(
            AccessMode mode,
            PatchManifest manifestOpt,
            boolean allowAutoAction
    ) {
        boolean isJoJo = activeProfile == null || "jojo_golden_hymn".equals(activeProfile.id);
        String activeId = activeProfile != null ? activeProfile.id : "jojo_golden_hymn";
        String activePkg = activeProfile != null ? activeProfile.packageName : "";
        long targetVc = manifestOpt != null
                ? manifestOpt.targetGameVersionCode
                : (activeProfile != null && activeProfile.targetGameVersionCode > 0
                ? activeProfile.targetGameVersionCode
                : LauncherConfig.TARGET_GAME_VERSION_CODE);
        long expForceConf = manifestOpt != null
                ? manifestOpt.expectedForceConfSize
                : (isJoJo ? LauncherConfig.TARGET_INDEX_ORIG_SIZE : 0L);
        long expLangSize = manifestOpt != null
                ? manifestOpt.expectedLangOrigSize
                : (isJoJo ? LauncherConfig.TARGET_LANG_ORIG_SIZE : 0L);
        String expLangMd5 = manifestOpt != null
                ? manifestOpt.expectedLangOrigMd5
                : (isJoJo ? LauncherConfig.TARGET_LANG_ORIG_MD5 : "");
        String targetVerName = (manifestOpt != null && !manifestOpt.gameVersion.isEmpty())
                ? manifestOpt.gameVersion
                : (activeProfile != null && activeProfile.targetGameVersionName != null && !activeProfile.targetGameVersionName.isEmpty()
                ? activeProfile.targetGameVersionName
                : LauncherConfig.TARGET_GAME_VERSION_NAME);

        long installedVc = getInstalledGameVersionCode();
        String installedVerName = getInstalledGameVersion();
        String installedApkSig = getInstalledGameApkSignatureSha256(activePkg);

        boolean dataHasBackup = false;
        String apkMismatchReason = null;

        // 1) بوابة القفل الصارم (APK Version & Signature Gate):
        // يرتبط الحظر الصارم فقط بتغيّر versionCode أو versionName أو توقيع الـ APK المثبت في النظام!
        if (installedVc > 0 && targetVc > 0 && installedVc != targetVc) {
            apkMismatchReason = "إصدار اللعبة المثبّت (v" + (installedVerName != null ? installedVerName : "?")
                    + " / code=" + installedVc + ") يختلف عن الإصدار المدعوم للتعريب (v"
                    + targetVerName
                    + " / code=" + targetVc + ").";
        } else {
            String normInst = normalizeVersionName(installedVerName);
            String normTarget = normalizeVersionName(targetVerName);
            if (!normInst.isEmpty() && !normTarget.isEmpty() && !normInst.equalsIgnoreCase(normTarget)) {
                apkMismatchReason = "رقم إصدار اللعبة المثبّت (v" + normInst
                        + ") يختلف عن الإصدار المدعوم للتعريب (v" + normTarget + ").";
            }
        }

        if (apkMismatchReason == null && !installedApkSig.isEmpty()) {
            SharedPreferences prefs = getSharedPreferences("rdav_ota_cache", MODE_PRIVATE);
            String recordedSig = prefs.getString("apk_sig_" + activeId, "");
            if (!recordedSig.isEmpty() && !recordedSig.equalsIgnoreCase(installedApkSig)) {
                apkMismatchReason = "تغيّر التوقيع الرقمي (APK Signature) للعبة المثبّتة عن النسخة التي تم تعريبها مسبقاً.";
            }
        }

        // 2) فحص ملفات Android/data الداخلية لتمييز التحديثات السحابية (OTA Hotfixes) عن تحديثات المتجر
        boolean otaChanged = false;
        String otaDetail = "";

        if (isReadyAccessMode(mode)) {
            String resp = null;
            if (mode == AccessMode.BRIDGE_ACTIVE) {
                resp = sendBridgeCommand(
                        "CHECK_SAFETY\t" + getHintDir().getAbsolutePath()
                                + "\t" + expForceConf
                                + "\t" + expLangSize
                                + "\t" + expLangMd5
                                + "\t" + activePkg,
                        15000
                );
            } else if (mode == AccessMode.DIRECT_FS) {
                resp = ShellBridgeServer.checkDataSafety(getHintDir(), expForceConf, expLangSize, expLangMd5, activePkg);
            } else if (mode == AccessMode.SAF_GRANTED) {
                Uri tree = SafHelper.findGrantedTreeUri(this, activePkg);
                if (tree != null) {
                    resp = SafHelper.checkDataSafety(this, tree, expForceConf, expLangSize, expLangMd5, activePkg);
                }
            }

            if (resp != null && (resp.startsWith("OTA_CHANGED|") || resp.startsWith("MISMATCH|"))) {
                String[] parts = resp.split("\\|", -1);
                if (parts.length >= 3) {
                    dataHasBackup = Boolean.parseBoolean(parts[2]);
                }
                otaDetail = parts.length >= 4 ? parts[3] : parts[1];
                otaChanged = true;
            } else if (resp != null && resp.startsWith("OK|")) {
                String[] parts = resp.split("\\|", -1);
                if (parts.length >= 3) {
                    dataHasBackup = Boolean.parseBoolean(parts[2]);
                }
            }
        }

        // إذا تغيّر الـ APK نفسه (تحديث متجر رئيسي): إيقاف صارم + استعادة الأصل لحماية الحساب
        if (apkMismatchReason != null) {
            if (allowAutoAction && isReadyAccessMode(mode) && (dataHasBackup || isPatchCurrentlyInstalled)) {
                executeRestoreSync(mode, null);
                isPatchCurrentlyInstalled = false;
                installedPatchVersion = "";
            }
            return new SafetyCheckResult(false, dataHasBackup, false, false, apkMismatchReason, "");
        }

        // إذا بقي الـ APK مطابقاً 100% لكن تغيّرت ملفات Android/data بسبب تحديث داخلي (OTA Hotfix):
        // لا نُظهر رسالة حظر حمراء أبداً — نُفعّل المزامنة الذكية والإصلاح الذاتي (Fresh Backup + In-Place Catalog Patch)!
        if (otaChanged) {
            if (allowAutoAction && isReadyAccessMode(mode) && hasMatchingLocalPatchCache(activeId, installedVc)) {
                boolean synced = autoReapplyCachedPatchSync(mode, activeId);
                if (synced) {
                    return new SafetyCheckResult(true, true, false, true, "", otaDetail);
                }
            }
            return new SafetyCheckResult(true, dataHasBackup, true, false, "", otaDetail);
        }

        return new SafetyCheckResult(true, dataHasBackup, false, false, "", "");
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
        String activePkg = activeProfile != null ? activeProfile.packageName : "";
        if (mode == AccessMode.BRIDGE_ACTIVE) {
            return sendBridgeCommandWithProgress("APPLY_RESTORE\t" + getHintDir().getAbsolutePath() + "\t" + activePkg, 120000, listener);
        } else if (mode == AccessMode.DIRECT_FS) {
            return ShellBridgeServer.applyRestore(getHintDir(), activePkg, listener);
        } else {
            Uri tree = SafHelper.findGrantedTreeUri(this, activePkg);
            if (tree == null) {
                return "ERR|NO_SAF_TREE";
            }
            return SafHelper.applyRestore(this, tree, activePkg, listener);
        }
    }

    // =========================================================================
    // كشف وإدارة صلاحيات الوصول (Shizuku / SAF / DirectFS / Root)
    // =========================================================================

    private String getInstalledGameVersion() {
        PackageManager pm = getPackageManager();
        String activePkg = activeProfile != null ? activeProfile.packageName : "";
        if (activePkg != null && !activePkg.trim().isEmpty()) {
            try {
                PackageInfo info = pm.getPackageInfo(activePkg.trim(), 0);
                if (info != null && info.versionName != null) {
                    return info.versionName;
                }
            } catch (Throwable ignored) {
            }
            if (!"jojo_golden_hymn".equals(activeProfile.id)) {
                return null;
            }
        }
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
        String activePkg = activeProfile != null ? activeProfile.packageName : "";
        if (activePkg != null && !activePkg.trim().isEmpty()) {
            if (isPackageInstalled(activePkg.trim())) {
                return true;
            }
            if (!"jojo_golden_hymn".equals(activeProfile.id)) {
                return false;
            }
        }
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

        if (ShellBridgeServer.canDirectReadWriteGameDir(getHintDir(), activeProfile != null ? activeProfile.packageName : null)) {
            return AccessMode.DIRECT_FS;
        }
        if (SafHelper.findGrantedTreeUri(this, activeProfile != null ? activeProfile.packageName : null) != null) {
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

            String activePkg = activeProfile != null ? activeProfile.packageName : "";
            String statusResp = null;
            if (mode == AccessMode.BRIDGE_ACTIVE) {
                statusResp = sendBridgeCommand("STATUS\t" + getHintDir().getAbsolutePath() + "\t" + activePkg, 3000);
            } else if (mode == AccessMode.DIRECT_FS) {
                statusResp = ShellBridgeServer.getStatus(getHintDir(), activePkg);
            } else if (mode == AccessMode.SAF_GRANTED) {
                Uri tree = SafHelper.findGrantedTreeUri(this, activePkg);
                if (tree != null) {
                    statusResp = SafHelper.getStatus(this, tree, activePkg);
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
            } else if (safety.otaAutoSynced) {
                patchInstalled = true;
                patchVer = installedPatchVersion;
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
                setStepDotState(tvStep4Dot, fPatchInstalled && fSafety.safe && !fSafety.otaSyncNeeded, false);

                if (!isBusy) {
                    if (!gameInstalled) {
                        tvHeadlineStatus.setText("اللعبة غير مثبّتة على الجهاز");
                        tvHeadlineStatus.setTextColor(LauncherConfig.COL_DANGER);
                        progressBar.setProgress(0);
                        tvProgressPercent.setText("0%");
                    } else if (!fSafety.safe) {
                        tvHeadlineStatus.setText("تم اكتشاف تحديث جديد لنسخة اللعبة (APK)!");
                        tvHeadlineStatus.setTextColor(LauncherConfig.COL_DANGER);
                        tvStatusText.setText(LauncherConfig.SAFETY_GUARD_ALERT_MESSAGE);
                        progressBar.setProgress(25);
                        tvProgressPercent.setText("تنبيه");
                    } else if (fSafety.otaAutoSynced) {
                        tvHeadlineStatus.setText("تمت مزامنة التعريب تلقائياً (OTA) ✓");
                        tvHeadlineStatus.setTextColor(LauncherConfig.COL_SUCCESS);
                        tvStatusText.setText("تم رصد تحديث داخلي سحابي وأُخذت نسخة احتياطية جديدة وأُعيد حقن التعريب مع تحديث الكتالوج موضعياً ✓");
                        progressBar.setProgress(100);
                        tvProgressPercent.setText("100%");
                    } else if (fSafety.otaSyncNeeded) {
                        tvHeadlineStatus.setText("تحديث داخلي (OTA) — جاهز للمزامنة السريعة");
                        tvHeadlineStatus.setTextColor(LauncherConfig.COL_CYAN);
                        tvStatusText.setText("نسخة الـ APK متوافقة 100%. اضغط «تحديث أونلاين» أو «تثبيت» لمزامنة التعريب مع الملفات الجديدة وحفظ نسخة احتياطية محدّثة.");
                        progressBar.setProgress(85);
                        tvProgressPercent.setText("مزامنة");
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
                } else if (fSafety.otaAutoSynced) {
                    tvSafetyBadge.setText("متزامن (OTA) ✓");
                    setBadgeStyle(tvSafetyBadge, Color.parseColor("#113826"), LauncherConfig.COL_SUCCESS);
                } else if (fSafety.otaSyncNeeded) {
                    tvSafetyBadge.setText("مزامنة OTA متاحة");
                    setBadgeStyle(tvSafetyBadge, Color.parseColor("#0D2F45"), LauncherConfig.COL_CYAN);
                } else if (fSafety.safe) {
                    tvSafetyBadge.setText("متوافق وآمن ✓");
                    setBadgeStyle(tvSafetyBadge, Color.parseColor("#113826"), LauncherConfig.COL_SUCCESS);
                } else {
                    tvSafetyBadge.setText("تحديث APK غير متوافق!");
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
            String activePkg = activeProfile != null ? activeProfile.packageName : null;
            startActivityForResult(SafHelper.buildOpenTreeIntent(this, activePkg), REQ_SAF_TREE);
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
                        Toast.makeText(this, "تم ربط مجلد " + (activeProfile != null ? activeProfile.title : "اللعبة") + " بنجاح ✓", Toast.LENGTH_SHORT).show();
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
            return;
        }

        if (requestCode == REQ_PICK_FEEDBACK_IMAGES) {
            if (resultCode == RESULT_OK && data != null) {
                handleFeedbackImagesPicked(data);
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
                boolean useBeta = activeProfile.comingSoon && BetaModeManager.isBetaEnabled(this);
                PatchManifest manifest;
                if (useBeta) {
                    manifest = PatchDownloader.fetchBetaManifest(activeProfile);
                } else {
                    try {
                        manifest = PatchDownloader.fetchManifestForProfile(activeProfile);
                    } catch (Throwable pubErr) {
                        if (BetaModeManager.isBetaEnabled(this)) {
                            manifest = PatchDownloader.fetchBetaManifest(activeProfile);
                        } else {
                            throw pubErr;
                        }
                    }
                }
                manifest.saveToCache(this, activeProfile.id);
                latestRemoteManifest = manifest;
                syncDynamicMediaForManifest(activeProfile, manifest);
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
        applyDynamicMetadataToUi();
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
            Toast.makeText(this, "يرجى تثبيت لعبة " + activeProfile.title + " أولاً على الجهاز.", Toast.LENGTH_LONG).show();
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

                boolean useBeta = activeProfile.comingSoon && BetaModeManager.isBetaEnabled(this);
                PatchManifest manifest;
                if (useBeta) {
                    manifest = PatchDownloader.fetchBetaManifest(activeProfile);
                } else {
                    try {
                        manifest = PatchDownloader.fetchManifestForProfile(activeProfile);
                    } catch (Throwable pubErr) {
                        if (BetaModeManager.isBetaEnabled(this)) {
                            manifest = PatchDownloader.fetchBetaManifest(activeProfile);
                        } else {
                            throw pubErr;
                        }
                    }
                }
                manifest.saveToCache(this, activeProfile.id);
                latestRemoteManifest = manifest;
                syncDynamicMediaForManifest(activeProfile, manifest);
                mainHandler.post(this::updateRemoteCardUi);

                updateProgress(7, "جارٍ التحقق عبر حارس الأمان (Version Safety Guard)...");
                SafetyCheckResult safety = evaluateSafetyGuardSync(mode, manifest, true);
                if (!safety.safe) {
                    showErrorState(LauncherConfig.SAFETY_GUARD_ALERT_MESSAGE);
                    mainHandler.post(() -> showInstallFailureDialog("عدم تطابق الإصدار / حارس الأمان", safety.reason));
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

                // حفظ نسخة محلية في كاش المزامنة الذكية (Smart OTA Sync) لإعادة التطبيق فورياً دون إنترنت عند أي Hotfix
                saveLocalPatchCache(
                        activeProfile.id,
                        zipFile,
                        manifest.patchVersion,
                        manifest.resolveEffectiveSubpath(),
                        getInstalledGameVersionCode(),
                        getInstalledGameVersion(),
                        getInstalledGameApkSignatureSha256(activeProfile.packageName)
                );

                PatchDownloader.extractZipFile(zipFile, stageDir, this::updateProgress, 48, 55);
                File effectiveStage = PatchDownloader.resolveEffectiveStageRoot(stageDir);
                ensureBd2FontBundleStaged(manifest, effectiveStage, 55, 60, true);

                executeInstallFromStage(mode, effectiveStage, manifest.patchVersion, manifest.resolveEffectiveSubpath());
            } catch (Throwable t) {
                String err = readableError(t);
                showErrorState("تعذر إكمال التثبيت أونلاين: " + err);
                mainHandler.post(() -> showInstallFailureDialog("التثبيت أونلاين (GitHub)", err));
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
            Toast.makeText(this, "يرجى تثبيت لعبة " + activeProfile.title + " أولاً على الجهاز.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/json", "application/octet-stream"});
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
                    mainHandler.post(() -> showInstallFailureDialog("عدم تطابق الإصدار / حارس الأمان", safety.reason));
                    return;
                }

                updateProgress(15, "جارٍ استخراج حزمة التعريب...");
                ShellBridgeServer.deleteRecursive(workDir);
                stageDir.mkdirs();

                PatchDownloader.extractZipUri(this, zipUri, stageDir, this::updateProgress, 20, 58);
                saveLocalPatchCacheFromUri(
                        activeProfile.id,
                        zipUri,
                        "local-zip",
                        activeProfile.patchStrategy.resolveDefaultTargetSubpath(""),
                        getInstalledGameVersionCode(),
                        getInstalledGameVersion(),
                        getInstalledGameApkSignatureSha256(activeProfile.packageName)
                );
                File effectiveStage = PatchDownloader.resolveEffectiveStageRoot(stageDir);
                ensureBd2FontBundleStaged(latestRemoteManifest, effectiveStage, 58, 60, false);
                executeInstallFromStage(mode, effectiveStage, "local-zip", activeProfile.patchStrategy.resolveDefaultTargetSubpath(""));
            } catch (Throwable t) {
                String err = readableError(t);
                showErrorState("فشل تثبيت الحزمة: " + err);
                mainHandler.post(() -> showInstallFailureDialog("تثبيت حزمة ZIP المحلية", err));
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
                syncDynamicMediaForManifest(activeProfile, betaManifest);
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

                saveLocalPatchCache(
                        activeProfile.id,
                        zipFile,
                        "beta-" + betaManifest.patchVersion,
                        betaManifest.resolveEffectiveSubpath(),
                        getInstalledGameVersionCode(),
                        getInstalledGameVersion(),
                        getInstalledGameApkSignatureSha256(activeProfile.packageName)
                );

                PatchDownloader.extractZipFile(zipFile, stageDir, this::updateProgress, 55, 62);
                File effectiveStage = PatchDownloader.resolveEffectiveStageRoot(stageDir);
                ensureBd2FontBundleStaged(betaManifest, effectiveStage, 62, 68, true);

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
                String err = readableError(t);
                showErrorState("تعذر تثبيت نسخة الاختبار (Beta): " + err);
                mainHandler.post(() -> showInstallFailureDialog("تثبيت نسخة الاختبار (Beta Test)", err));
            } finally {
                ShellBridgeServer.deleteRecursive(workDir);
                setBusyUi(false, null, -1);
                refreshStatusAsync(null);
            }
        });
    }

    /**
     * يحمّل حزمة الخط العربي للعبة BrownDust II (rdav_bd2_fonts.bundle) لمرة واحدة فقط ويخزّنها
     * محلياً في كاش التطبيق الدائم مع التحقق من SHA-256، ثم يجهّزها داخل مجلد التثبيت المؤقت
     * بحيث تبقى تحديثات النصوص المستقبلية خفيفة للغاية (~266 KB فقط).
     */
    private void ensureBd2FontBundleStaged(
            PatchManifest manifest,
            File effectiveStage,
            int startPct,
            int endPct,
            boolean allowDownload
    ) {
        if (activeProfile == null || !"browndust2".equalsIgnoreCase(activeProfile.id) || effectiveStage == null) {
            return;
        }
        try {
            File stagedFont = new File(effectiveStage, BrownDust2PatchEngine.FONT_BUNDLE_REL_PATH);
            if (stagedFont.isFile() && stagedFont.length() > 1024L * 1024L) {
                return;
            }
            File fontCacheDir = new File(getFilesDir(), "bd2_font_cache");
            if (!fontCacheDir.exists()) {
                fontCacheDir.mkdirs();
            }
            File cachedFont = new File(fontCacheDir, "rdav_bd2_fonts.bundle");
            String url = manifest != null ? manifest.fontBundleUrl : "";
            String expectedSha = manifest != null ? manifest.fontBundleSha256 : "";
            long expectedSize = manifest != null ? manifest.fontBundleSize : 0L;

            boolean cacheValid = cachedFont.isFile() && cachedFont.length() > 1024L * 1024L;
            if (cacheValid && !expectedSha.isEmpty()) {
                String actualSha = ShellBridgeServer.sha256Lower(cachedFont);
                if (!expectedSha.equalsIgnoreCase(actualSha)) {
                    cacheValid = false;
                }
            }

            if (!cacheValid && allowDownload && url != null && url.startsWith("https://")) {
                updateProgress(startPct, "جارٍ تحميل حزمة الخط العربي لـ BrownDust II (لمرة واحدة فقط)...");
                PatchDownloader.downloadToFile(url, cachedFont, expectedSize, this::updateProgress, startPct, endPct);
                if (!expectedSha.isEmpty()) {
                    PatchDownloader.verifySha256IfProvided(cachedFont, expectedSha);
                }
                cacheValid = cachedFont.isFile() && cachedFont.length() > 1024L * 1024L;
            }

            if (cacheValid) {
                File parent = stagedFont.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                ShellBridgeServer.copyFileAtomically(cachedFont, stagedFont);
            }
        } catch (Throwable ignored) {
            // لا نوقف تثبيت النصوص لو تعذّر تحميل حزمة الخط؛ سيعمل تلقائياً عند توفر الخط محلياً أو لاحقاً
        }
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
        String activePkg = activeProfile != null ? activeProfile.packageName : "";
        String result;

        if (mode == AccessMode.BRIDGE_ACTIVE) {
            result = sendBridgeCommandWithProgress(
                    "APPLY_INSTALL\t" + stageDir.getAbsolutePath() + "\t" + patchVersion + "\t" + normSubpath + "\t" + activePkg,
                    300000,
                    listener
            );
        } else if (mode == AccessMode.DIRECT_FS) {
            result = ShellBridgeServer.applyInstall(stageDir, patchVersion, normSubpath, activePkg, listener);
            if (result == null || (!result.startsWith("OK|") && !result.contains("NEEDS_FIRST_LAUNCH"))) {
                Uri tree = SafHelper.findGrantedTreeUri(this, activePkg);
                if (tree != null) {
                    result = SafHelper.applyInstall(this, tree, stageDir, patchVersion, normSubpath, activePkg, listener);
                }
            }
        } else {
            Uri tree = SafHelper.findGrantedTreeUri(this, activePkg);
            if (tree == null) {
                throw new IllegalStateException("صلاحية SAF غير متوفرة");
            }
            result = SafHelper.applyInstall(this, tree, stageDir, patchVersion, normSubpath, activePkg, listener);
        }

        if (result != null && result.contains("NEEDS_FIRST_LAUNCH")) {
            mainHandler.post(() -> {
                tvStatusText.setText("يرجى تشغيل " + activeProfile.title + " مرة واحدة أولاً لإنشاء مجلد البيانات ثم إعادة المحاولة.");
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
        String bd2RowsNote = "";
        String bd2FontNote = "";
        for (String p : parts) {
            if (p != null && p.startsWith("bd2_rows=")) {
                String rv = p.substring("bd2_rows=".length()).trim();
                if (!rv.isEmpty() && !"0".equals(rv)) {
                    bd2RowsNote = " • " + rv + " صف عربي";
                }
            } else if ("bd2_font=1".equals(p)) {
                bd2FontNote = " • خط عربي مفعّل";
            }
        }
        updateProgress(100, "اكتمل تثبيت تعريب R.D.AV بنجاح (" + fileCount + " ملف" + bd2RowsNote + bd2FontNote + ") مع حفظ النسخة الاحتياطية ✓");
        mainHandler.post(() -> Toast.makeText(this, "تم تثبيت تعريب " + activeProfile.title + " بنجاح ✓", Toast.LENGTH_LONG).show());
    }

    private void confirmAndRestore() {
        if (isBusy) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        b.setTitle("استعادة النسخة الأصلية");
        b.setMessage("هل تريد إزالة التعريب واستعادة جميع ملفات " + activeProfile.title + " الأصلية من النسخة الاحتياطية؟");
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

    /**
     * نافذة التنبيه الحمراء التلقائية عند فشل التثبيت أو الحقن لأي سبب،
     * تطلب من المستخدم فحص سلامة الملفات لإصدار تقرير التشخيص وإرساله فوراً إلى R.D.AV على Instagram.
     */
    private void showInstallFailureDialog(String stageLabel, String errorReason) {
        final String safeStage = (stageLabel != null && !stageLabel.trim().isEmpty()) ? stageLabel.trim() : "تثبيت التعريب";
        final String safeReason = (errorReason != null && !errorReason.trim().isEmpty()) ? errorReason.trim() : "حدث خطأ غير متوقع أثناء العملية.";
        lastInstallFailureReason = "[" + safeStage + "] " + safeReason;

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(16), dp(14), dp(16), dp(10));
        container.setBackgroundColor(Color.parseColor("#14070D"));

        // ترويسة حمراء واضحة
        LinearLayout redBanner = new LinearLayout(this);
        redBanner.setOrientation(LinearLayout.VERTICAL);
        redBanner.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable redBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor("#5F1124"), Color.parseColor("#3B0A17")}
        );
        redBg.setCornerRadius(dp(14));
        redBg.setStroke(dp(2), LauncherConfig.COL_DANGER);
        redBanner.setBackground(redBg);

        redBanner.addView(makeText("⚠  فشل تثبيت / حقن التعريب", 15.5f, Color.WHITE, true));
        TextView tvStage = makeText("المرحلة: " + safeStage, 11.2f, Color.parseColor("#FDA4AF"), true);
        tvStage.setPadding(0, dp(4), 0, 0);
        redBanner.addView(tvStage);
        LinearLayout.LayoutParams rbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rbLp.bottomMargin = dp(10);
        container.addView(redBanner, rbLp);

        // صندوق سبب الفشل
        LinearLayout errBox = new LinearLayout(this);
        errBox.setOrientation(LinearLayout.VERTICAL);
        errBox.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable errBg = new GradientDrawable();
        errBg.setColor(Color.parseColor("#240B14"));
        errBg.setCornerRadius(dp(10));
        errBg.setStroke(dp(1), Color.parseColor("#9F1239"));
        errBox.setBackground(errBg);

        errBox.addView(makeText("سبب الفشل المرصود:", 10.8f, Color.parseColor("#FDA4AF"), true));
        TextView tvErr = makeText(safeReason, 11.8f, LauncherConfig.COL_TEXT, false);
        tvErr.setPadding(0, dp(4), 0, 0);
        tvErr.setLineSpacing(dp(2), 1f);
        errBox.addView(tvErr);
        LinearLayout.LayoutParams ebLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ebLp.bottomMargin = dp(10);
        container.addView(errBox, ebLp);

        TextView tvGuide = makeText(
                "لحل المشكلة بسرعة: اضغط الزر أدناه لإجراء «فحص سلامة الملفات» — سيقوم التطبيق بنسخ التقرير التقني تلقائياً وفتح حساب R.D.AV على Instagram لإرساله فوراً.",
                11.3f, LauncherConfig.COL_MUTED, false
        );
        tvGuide.setLineSpacing(dp(3), 1f);
        tvGuide.setPadding(dp(2), 0, dp(2), dp(12));
        container.addView(tvGuide);

        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setView(container)
                .create();

        // زر بارز لفحص سلامة الملفات وإصدار تقرير
        Button btnScanNow = new Button(this);
        btnScanNow.setText("🔍  فحص سلامة الملفات وإصدار تقرير");
        btnScanNow.setAllCaps(false);
        btnScanNow.setTextSize(13.5f);
        btnScanNow.setTextColor(Color.WHITE);
        btnScanNow.setTypeface(fontBold);
        GradientDrawable scanBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.parseColor("#E11D48"), Color.parseColor("#BE123C")}
        );
        scanBg.setCornerRadius(dp(12));
        scanBg.setStroke(dp(1), Color.parseColor("#FDA4AF"));
        btnScanNow.setBackground(scanBg);
        btnScanNow.setOnClickListener(v -> {
            dialog.dismiss();
            runDiagnosticScanFlow(lastInstallFailureReason);
        });
        LinearLayout.LayoutParams scanLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        scanLp.bottomMargin = dp(8);
        container.addView(btnScanNow, scanLp);

        Button btnClose = makeButton("إغلاق", Color.parseColor("#1D0F18"), LauncherConfig.COL_MUTED, Color.parseColor("#4C1D2E"), true);
        btnClose.setTextSize(12f);
        btnClose.setOnClickListener(v -> dialog.dismiss());
        container.addView(btnClose, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));

        dialog.show();
    }

    private void runDiagnosticScanFlow() {
        runDiagnosticScanFlow(lastInstallFailureReason);
    }

    private void runDiagnosticScanFlow(String failureContext) {
        if (isBusy) {
            return;
        }
        worker.execute(() -> {
            setBusyUi(true, "جارٍ فحص المسارات والصلاحيات وسلامة ملفات " + activeProfile.title + "...", 35);
            try {
                AccessMode mode = detectAccessMode();
                StringBuilder fullReport = new StringBuilder();
                fullReport.append("=== R.D.AV Multi-Game Hub Diagnostic Report ===\n");
                fullReport.append("Launcher: v").append(LauncherConfig.APP_VERSION).append("\n");
                fullReport.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
                        .append(" (Android ").append(Build.VERSION.RELEASE)
                        .append(" / API ").append(Build.VERSION.SDK_INT).append(")\n");
                fullReport.append("Active Game: ").append(activeProfile.title)
                        .append(" [").append(activeProfile.packageName).append("]\n");
                fullReport.append("Strategy: ").append(activeProfile.patchStrategy.code).append("\n");
                fullReport.append("Game Installed: ").append(isPackageInstalled(activeProfile.packageName)).append("\n");
                String instGameVer = getInstalledGameVersion();
                long instGameVc = getInstalledGameVersionCode();
                fullReport.append("Installed Game Version: ")
                        .append(instGameVer != null ? instGameVer : "N/A")
                        .append(" (versionCode=").append(instGameVc).append(")\n");
                fullReport.append("Target Game Version: v").append(activeProfile.targetGameVersionName)
                        .append(" (#").append(activeProfile.targetGameVersionCode).append(")\n");
                fullReport.append("Access Mode: ").append(mode.name()).append("\n");
                fullReport.append("Shizuku Installed: ").append(isPackageInstalled(LauncherConfig.SHIZUKU_PACKAGE))
                        .append(" | SAF Granted: ").append(SafHelper.findGrantedTreeUri(this) != null).append("\n");
                fullReport.append("Patch Installed: ").append(isPatchCurrentlyInstalled)
                        .append(" (").append(installedPatchVersion.isEmpty() ? "none" : installedPatchVersion).append(")\n");
                fullReport.append("Beta Mode: ").append(BetaModeManager.isBetaEnabled(this)).append("\n");
                if (latestRemoteManifest != null) {
                    fullReport.append("Latest Manifest: v").append(latestRemoteManifest.patchVersion)
                            .append(" [").append(latestRemoteManifest.channel).append("]")
                            .append(" for game v").append(latestRemoteManifest.gameVersion).append("\n");
                }
                if (failureContext != null && !failureContext.trim().isEmpty()) {
                    fullReport.append("Last Failure: ").append(failureContext.trim()).append("\n");
                }
                fullReport.append("----------------------------------------\n");

                if (mode == AccessMode.BRIDGE_ACTIVE || mode == AccessMode.DIRECT_FS) {
                    updateProgress(70, "جارٍ فحص سلامة ملفات اللعبة وبصمات MD5...");
                    String activePkg = activeProfile != null ? activeProfile.packageName : "";
                    String resp = (mode == AccessMode.BRIDGE_ACTIVE)
                            ? sendBridgeCommand("SCAN_DIAG\t" + getHintDir().getAbsolutePath() + "\t" + activePkg, 30000)
                            : ShellBridgeServer.runDiagnosticScan(getHintDir(), activePkg);
                    if (resp != null && resp.startsWith("OK|")) {
                        fullReport.append(resp.substring(3).replace("\\n", "\n"));
                    } else {
                        fullReport.append("Deep File Scan Error: ").append(resp).append("\n");
                    }
                } else {
                    fullReport.append("Deep File Scan: SKIPPED (Shizuku / DirectFS not active — mode=")
                            .append(mode.name()).append(")\n");
                    fullReport.append("Tip: Please activate Shizuku or grant SAF folder permission.\n");
                }

                final String finalReport = fullReport.toString();
                updateProgress(100, "اكتمل الفحص وتم نسخ التقرير التقني تلقائياً إلى الحافظة ✓");
                mainHandler.post(() -> {
                    copyReportToClipboard(finalReport, true);
                    showDiagnosticReportDialog(finalReport);
                });
            } catch (Throwable t) {
                showErrorState("خطأ أثناء الفحص: " + readableError(t));
            } finally {
                setBusyUi(false, null, -1);
                refreshStatusAsync(null);
            }
        });
    }

    private void copyReportToClipboard(String text, boolean isAutoDiag) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && text != null) {
                cm.setPrimaryClip(ClipData.newPlainText("R.D.AV Support Report", text));
                String toastMsg = isAutoDiag
                        ? "✓ تم نسخ تقرير التشخيص التقني تلقائياً إلى الحافظة"
                        : "✓ تم النسخ إلى الحافظة بنجاح";
                Toast.makeText(this, toastMsg, Toast.LENGTH_LONG).show();
            }
        } catch (Throwable ignored) {
        }
    }

    private void openInstagramSupport() {
        String igUrl = LauncherConfig.INSTAGRAM_URL;
        try {
            Intent appIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(igUrl));
            appIntent.setPackage("com.instagram.android");
            appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(appIntent);
            return;
        } catch (Throwable ignored) {
        }
        openUrl(igUrl);
    }

    private void showDiagnosticReportDialog(String report) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(12));
        root.setBackgroundColor(LauncherConfig.COL_BG);

        // شارة تأكيد النسخ التلقائي إلى الحافظة
        TextView copiedBanner = makeText(
                "✅  تم نسخ التقرير تلقائياً إلى الحافظة — اضغط الزر أدناه لفتح Instagram ولصقه مباشرة في الرسالة",
                11.2f, LauncherConfig.COL_SUCCESS, true
        );
        copiedBanner.setPadding(dp(12), dp(9), dp(12), dp(9));
        GradientDrawable cbBg = new GradientDrawable();
        cbBg.setColor(Color.parseColor("#0F2D1F"));
        cbBg.setCornerRadius(dp(10));
        cbBg.setStroke(dp(1), LauncherConfig.COL_SUCCESS);
        copiedBanner.setBackground(cbBg);
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cbLp.bottomMargin = dp(10);
        root.addView(copiedBanner, cbLp);

        ScrollView sv = new ScrollView(this);
        sv.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable svBg = new GradientDrawable();
        svBg.setColor(LauncherConfig.COL_CARD_INNER);
        svBg.setCornerRadius(dp(10));
        svBg.setStroke(dp(1), LauncherConfig.COL_BORDER);
        sv.setBackground(svBg);

        TextView tv = new TextView(this);
        tv.setText(report);
        tv.setTextSize(10.5f);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(LauncherConfig.COL_TEXT);
        tv.setTextIsSelectable(true);
        tv.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        sv.addView(tv);

        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(230));
        svLp.bottomMargin = dp(12);
        root.addView(sv, svLp);

        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("تقرير فحص سلامة الملفات — " + activeProfile.title)
                .setView(root)
                .create();

        // الزر البارز: إرسال التقرير إلى R.D.AV على Instagram (بأيقونة إنستغرام وتدرج #833AB4 -> #E1306C)
        LinearLayout btnSendIg = makeInstagramPill("إرسال التقرير إلى R.D.AV على Instagram", 13.2f, 18, 14, 12);
        btnSendIg.setOnClickListener(v -> {
            copyReportToClipboard(report, false);
            openInstagramSupport();
        });
        LinearLayout.LayoutParams igLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        igLp.bottomMargin = dp(8);
        root.addView(btnSendIg, igLp);

        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);

        Button btnRecopy = makeButton("إعادة نسخ التقرير", LauncherConfig.COL_CARD, LauncherConfig.COL_CYAN, LauncherConfig.COL_BORDER, true);
        btnRecopy.setTextSize(11.5f);
        btnRecopy.setOnClickListener(v -> copyReportToClipboard(report, false));
        LinearLayout.LayoutParams rcLp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        rcLp.leftMargin = dp(6);
        bottomRow.addView(btnRecopy, rcLp);

        Button btnClose = makeButton("إغلاق", LauncherConfig.COL_CARD_INNER, LauncherConfig.COL_MUTED, LauncherConfig.COL_BORDER, true);
        btnClose.setTextSize(11.5f);
        btnClose.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams clLp = new LinearLayout.LayoutParams(0, dp(40), 0.7f);
        clLp.rightMargin = dp(6);
        bottomRow.addView(btnClose, clLp);

        root.addView(bottomRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        dialog.show();
    }

    private void showNeedsFirstLaunchDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        b.setTitle("يلزم تشغيل اللعبة لمرة واحدة أولاً");
        b.setMessage("لعبة " + activeProfile.title + " مثبّتة، لكن مجلد بياناتها لم يُنشأ بالكامل بعد.\n\nشغّل اللعبة مرة واحدة واتركها تنشئ ملفاتها الأولية، ثم عد لهذا التطبيق واضغط تثبيت التعريب.");
        b.setPositiveButton("تشغيل اللعبة الآن", (d, w) -> launchGameDirect());
        b.setNegativeButton("حسناً", null);
        b.show();
    }

    private void launchGameWithSafetyCheck() {
        if (isBusy) {
            return;
        }
        if (!isAnyGameInstalled()) {
            Toast.makeText(this, "لعبة " + activeProfile.title + " غير مثبّتة على الجهاز.", Toast.LENGTH_SHORT).show();
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
        String activePkg = activeProfile != null ? activeProfile.packageName : "";
        if (activePkg != null && !activePkg.trim().isEmpty()) {
            Intent launch = pm.getLaunchIntentForPackage(activePkg.trim());
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
                return;
            }
            if (!"jojo_golden_hymn".equals(activeProfile.id)) {
                Toast.makeText(this, "لعبة " + activeProfile.title + " غير مثبّتة على الجهاز.", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        for (String pkg : LauncherConfig.GAME_PACKAGES) {
            Intent launch = pm.getLaunchIntentForPackage(pkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
                return;
            }
        }
        Toast.makeText(this, "لعبة " + activeProfile.title + " غير مثبّتة على الجهاز.", Toast.LENGTH_SHORT).show();
    }

    // =========================================================================
    // فتح صفحة اللعبة في متجر Google Play
    // =========================================================================

    private void openPlayStoreForProfile(GameProfile profile) {
        if (profile == null || profile.packageName == null || profile.packageName.trim().isEmpty()) {
            return;
        }
        String pkg = profile.packageName.trim();
        try {
            Intent marketIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg));
            marketIntent.setPackage("com.android.vending");
            marketIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(marketIntent);
            return;
        } catch (Throwable ignored) {
        }
        try {
            Intent genericMarket = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg));
            genericMarket.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(genericMarket);
            return;
        } catch (Throwable ignored) {
        }
        openUrl("https://play.google.com/store/apps/details?id=" + pkg);
    }

    // =========================================================================
    // نظام «💬 اقتراح أو الإبلاغ عن مشكلة» (حتى 3 صور + بيانات تقنية + FeedbackPayload)
    // =========================================================================

    private void clearFeedbackAttachmentsCache() {
        feedbackSelectedFiles.clear();
        try {
            File dir = FeedbackAttachmentProvider.getAttachmentsDir(this);
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void showFeedbackDialog() {
        clearFeedbackAttachmentsCache();

        final String[] selectedType = {FeedbackPayload.TYPE_BUG_REPORT};

        ScrollView scroll = new ScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(16), dp(14), dp(16), dp(12));
        container.setBackgroundColor(LauncherConfig.COL_BG);

        // ترويسة النافذة بالثيم السماوي الداكن
        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        hdr.setPadding(dp(12), dp(11), dp(12), dp(11));
        GradientDrawable hdrBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor("#0E2233"), Color.parseColor("#11192D")}
        );
        hdrBg.setCornerRadius(dp(14));
        hdrBg.setStroke(dp(1), Color.parseColor("#38BDF8"));
        hdr.setBackground(hdrBg);

        ImageView ivHdr = new ImageView(this);
        ivHdr.setImageResource(R.drawable.ic_feedback_chat);
        ivHdr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams ivHdrLp = new LinearLayout.LayoutParams(dp(24), dp(24));
        ivHdrLp.leftMargin = dp(10);
        hdr.addView(ivHdr, ivHdrLp);

        LinearLayout hdrTextCol = new LinearLayout(this);
        hdrTextCol.setOrientation(LinearLayout.VERTICAL);
        hdrTextCol.addView(makeText("اقتراح أو الإبلاغ عن مشكلة", 14.5f, Color.parseColor("#E0F2FE"), true));
        TextView hdrSub = makeText(activeProfile.title + " • دعم R.D.AV", 10.5f, Color.parseColor("#7DD3FC"), false);
        hdrSub.setPadding(0, dp(2), 0, 0);
        hdrTextCol.addView(hdrSub);
        hdr.addView(hdrTextCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams hdrLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hdrLp.bottomMargin = dp(12);
        container.addView(hdr, hdrLp);

        // 1) محدد نوع الرسالة: «الإبلاغ عن مشكلة» أو «اقتراح»
        TextView lblType = makeText("نوع الرسالة:", 11.5f, LauncherConfig.COL_TEXT, true);
        lblType.setPadding(0, 0, 0, dp(6));
        container.addView(lblType);

        LinearLayout typeRow = new LinearLayout(this);
        typeRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams typeRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        typeRowLp.bottomMargin = dp(12);

        TextView tabBug = makeText("🐞  الإبلاغ عن مشكلة", 12f, Color.WHITE, true);
        tabBug.setGravity(Gravity.CENTER);
        tabBug.setPadding(dp(10), dp(10), dp(10), dp(10));
        tabBug.setClickable(true);

        TextView tabSuggestion = makeText("💡  اقتراح تطوير", 12f, LauncherConfig.COL_MUTED, true);
        tabSuggestion.setGravity(Gravity.CENTER);
        tabSuggestion.setPadding(dp(10), dp(10), dp(10), dp(10));
        tabSuggestion.setClickable(true);

        Runnable updateTypeTabs = () -> {
            boolean isBug = FeedbackPayload.TYPE_BUG_REPORT.equals(selectedType[0]);
            GradientDrawable bugBg = new GradientDrawable();
            bugBg.setCornerRadius(dp(11));
            bugBg.setColor(isBug ? Color.parseColor("#3B1020") : LauncherConfig.COL_CARD_INNER);
            bugBg.setStroke(dp(1), isBug ? Color.parseColor("#F43F5E") : LauncherConfig.COL_BORDER);
            tabBug.setBackground(bugBg);
            tabBug.setTextColor(isBug ? Color.parseColor("#FDA4AF") : LauncherConfig.COL_MUTED);

            GradientDrawable sugBg = new GradientDrawable();
            sugBg.setCornerRadius(dp(11));
            sugBg.setColor(!isBug ? Color.parseColor("#0E2A3F") : LauncherConfig.COL_CARD_INNER);
            sugBg.setStroke(dp(1), !isBug ? Color.parseColor("#38BDF8") : LauncherConfig.COL_BORDER);
            tabSuggestion.setBackground(sugBg);
            tabSuggestion.setTextColor(!isBug ? Color.parseColor("#7DD3FC") : LauncherConfig.COL_MUTED);
        };
        updateTypeTabs.run();

        tabBug.setOnClickListener(v -> {
            selectedType[0] = FeedbackPayload.TYPE_BUG_REPORT;
            updateTypeTabs.run();
        });
        tabSuggestion.setOnClickListener(v -> {
            selectedType[0] = FeedbackPayload.TYPE_SUGGESTION;
            updateTypeTabs.run();
        });

        LinearLayout.LayoutParams tbLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tbLp.leftMargin = dp(5);
        typeRow.addView(tabBug, tbLp);

        LinearLayout.LayoutParams tsLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tsLp.rightMargin = dp(5);
        typeRow.addView(tabSuggestion, tsLp);
        container.addView(typeRow, typeRowLp);

        // 2) حقل إدخال التفاصيل
        TextView lblMsg = makeText("تفاصيل الرسالة:", 11.5f, LauncherConfig.COL_TEXT, true);
        lblMsg.setPadding(0, 0, 0, dp(6));
        container.addView(lblMsg);

        EditText etDetails = new EditText(this);
        etDetails.setHint("اكتب تفاصيل الاقتراح أو وصف المشكلة بوضوح (مثال: نص غير مترجم، خطأ في التثبيت...)");
        etDetails.setHintTextColor(Color.parseColor("#6E628D"));
        etDetails.setTextColor(LauncherConfig.COL_TEXT);
        etDetails.setTextSize(12.2f);
        etDetails.setTypeface(fontRegular);
        etDetails.setGravity(Gravity.TOP | Gravity.START);
        etDetails.setMinLines(4);
        etDetails.setMaxLines(7);
        etDetails.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        etDetails.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable etBg = new GradientDrawable();
        etBg.setColor(LauncherConfig.COL_CARD_INNER);
        etBg.setCornerRadius(dp(12));
        etBg.setStroke(dp(1), Color.parseColor("#2E3B59"));
        etDetails.setBackground(etBg);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        etLp.bottomMargin = dp(12);
        container.addView(etDetails, etLp);

        // 3) إرفاق الصور (بحد أقصى 3 صور) + شريط المعاينة المصغّرة
        LinearLayout attachHeader = new LinearLayout(this);
        attachHeader.setOrientation(LinearLayout.HORIZONTAL);
        attachHeader.setGravity(Gravity.CENTER_VERTICAL);
        attachHeader.addView(makeText("الصور المرفقة (اختياري):", 11.5f, LauncherConfig.COL_TEXT, true),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        feedbackAttachCountText = makeText("0 / 3 صور", 10.5f, Color.parseColor("#7DD3FC"), true);
        attachHeader.addView(feedbackAttachCountText);
        LinearLayout.LayoutParams ahLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ahLp.bottomMargin = dp(6);
        container.addView(attachHeader, ahLp);

        feedbackAttachBtn = makeIconButton(
                "إرفاق صور (بحد أقصى 3 صور)",
                R.drawable.ic_photo_attach,
                Color.parseColor("#102133"),
                Color.parseColor("#BAE6FD"),
                Color.parseColor("#38BDF8"),
                11.8f
        );
        feedbackAttachBtn.setOnClickListener(v -> openFeedbackImagePicker());
        LinearLayout.LayoutParams abLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        abLp.bottomMargin = dp(8);
        container.addView(feedbackAttachBtn, abLp);

        feedbackThumbsRow = new LinearLayout(this);
        feedbackThumbsRow.setOrientation(LinearLayout.HORIZONTAL);
        feedbackThumbsRow.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        feedbackThumbsRow.setVisibility(View.GONE);
        LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        trLp.bottomMargin = dp(10);
        container.addView(feedbackThumbsRow, trLp);

        // 4) ملاحظة البيانات التقنية التلقائية
        String autoVer = getInstalledGameVersion();
        String techNoteText = "⚙ سيُرفق تلقائياً: " + activeProfile.title
                + " • إصدار اللعبة: " + (autoVer != null ? autoVer : "غير مثبتة")
                + " • التعريب: " + (installedPatchVersion.isEmpty() ? "—" : installedPatchVersion)
                + " • اللانشر: v" + LauncherConfig.APP_VERSION
                + " • " + Build.MANUFACTURER + " " + Build.MODEL;
        TextView tvAutoTech = makeText(techNoteText, 10f, LauncherConfig.COL_MUTED, false);
        tvAutoTech.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable tnBg = new GradientDrawable();
        tnBg.setColor(LauncherConfig.COL_CARD_INNER);
        tnBg.setCornerRadius(dp(9));
        tnBg.setStroke(dp(1), Color.parseColor("#272044"));
        tvAutoTech.setBackground(tnBg);
        LinearLayout.LayoutParams tnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tnLp.bottomMargin = dp(12);
        container.addView(tvAutoTech, tnLp);

        scroll.addView(container);

        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setView(scroll)
                .create();

        // أزرار الإرسال والإلغاء
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button btnSubmit = new Button(this);
        btnSubmit.setText("إرسال البلاغ");
        btnSubmit.setAllCaps(false);
        btnSubmit.setTextSize(13f);
        btnSubmit.setTextColor(Color.parseColor("#06131F"));
        btnSubmit.setTypeface(fontBold);
        GradientDrawable subBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.parseColor("#38BDF8"), Color.parseColor("#0EA5E9")}
        );
        subBg.setCornerRadius(dp(12));
        btnSubmit.setBackground(subBg);
        btnSubmit.setOnClickListener(v -> {
            String msg = etDetails.getText() != null ? etDetails.getText().toString().trim() : "";
            if (msg.length() < 4) {
                Toast.makeText(this, "يرجى كتابة تفاصيل الاقتراح أو المشكلة أولاً.", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            submitFeedbackPayload(selectedType[0], msg);
        });
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(0, dp(46), 1.3f);
        subLp.leftMargin = dp(6);
        btnRow.addView(btnSubmit, subLp);

        Button btnCancel = makeButton("إلغاء", LauncherConfig.COL_CARD_INNER, LauncherConfig.COL_MUTED, LauncherConfig.COL_BORDER, true);
        btnCancel.setTextSize(12f);
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams canLp = new LinearLayout.LayoutParams(0, dp(46), 0.7f);
        canLp.rightMargin = dp(6);
        btnRow.addView(btnCancel, canLp);

        container.addView(btnRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        dialog.show();
    }

    /**
     * يفتح منتقي الصور الرسمي (Android Photo Picker على أندرويد 13+ أو SAF ACTION_OPEN_DOCUMENT)
     * بدون الحاجة لأي صلاحية تخزين إضافية، مع احترام الحد الأقصى (3 صور).
     */
    private void openFeedbackImagePicker() {
        int remaining = MAX_FEEDBACK_IMAGES - feedbackSelectedFiles.size();
        if (remaining <= 0) {
            Toast.makeText(this, "تم بلوغ الحد الأقصى (3 صور). احذف صورة لإضافة أخرى.", Toast.LENGTH_SHORT).show();
            return;
        }
        // محاولة استخدام Android Photo Picker الرسمي على API 33+ أولاً
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                Intent picker = new Intent(MediaStore.ACTION_PICK_IMAGES);
                picker.setType("image/*");
                if (remaining >= 2) {
                    picker.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, remaining);
                }
                startActivityForResult(picker, REQ_PICK_FEEDBACK_IMAGES);
                return;
            } catch (Throwable ignored) {
            }
        }
        // بديل SAF الآمن بدون صلاحيات لأندرويد 7–12
        try {
            Intent doc = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            doc.addCategory(Intent.CATEGORY_OPENABLE);
            doc.setType("image/*");
            if (remaining > 1) {
                doc.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            startActivityForResult(doc, REQ_PICK_FEEDBACK_IMAGES);
        } catch (Throwable t) {
            try {
                Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                fallback.setType("image/*");
                if (remaining > 1) {
                    fallback.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                startActivityForResult(fallback, REQ_PICK_FEEDBACK_IMAGES);
            } catch (Throwable ignored) {
                Toast.makeText(this, "تعذر فتح منتقي الصور على هذا الجهاز.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void handleFeedbackImagesPicked(Intent data) {
        List<Uri> pickedUris = new ArrayList<>();
        if (data.getClipData() != null) {
            ClipData clip = data.getClipData();
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) {
                    pickedUris.add(u);
                }
            }
        } else if (data.getData() != null) {
            pickedUris.add(data.getData());
        }
        if (pickedUris.isEmpty()) {
            return;
        }

        int remaining = MAX_FEEDBACK_IMAGES - feedbackSelectedFiles.size();
        if (remaining <= 0) {
            return;
        }
        boolean trimmed = pickedUris.size() > remaining;
        List<Uri> toProcess = pickedUris.subList(0, Math.min(remaining, pickedUris.size()));

        iconExecutor.execute(() -> {
            List<File> added = new ArrayList<>();
            for (Uri u : toProcess) {
                File saved = copyAndResizeImageToCache(u);
                if (saved != null) {
                    added.add(saved);
                }
            }
            mainHandler.post(() -> {
                for (File f : added) {
                    if (feedbackSelectedFiles.size() < MAX_FEEDBACK_IMAGES) {
                        feedbackSelectedFiles.add(f);
                    }
                }
                if (trimmed) {
                    Toast.makeText(this, "تم إرفاق الحد الأقصى المسموح به (3 صور).", Toast.LENGTH_SHORT).show();
                } else if (added.isEmpty()) {
                    Toast.makeText(this, "تعذر قراءة الصورة المختارة.", Toast.LENGTH_SHORT).show();
                }
                refreshFeedbackThumbnails();
            });
        });
    }

    private File copyAndResizeImageToCache(Uri uri) {
        try {
            // 1) قراءة الأبعاد أولاً لحساب معامل التصغير وتجنب استهلاك الذاكرة (OOM)
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream is1 = getContentResolver().openInputStream(uri)) {
                if (is1 == null) {
                    return null;
                }
                BitmapFactory.decodeStream(is1, null, bounds);
            }
            int sample = 1;
            int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
            while (maxDim / sample > 1280) {
                sample *= 2;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bmp;
            try (InputStream is2 = getContentResolver().openInputStream(uri)) {
                if (is2 == null) {
                    return null;
                }
                bmp = BitmapFactory.decodeStream(is2, null, opts);
            }
            if (bmp == null) {
                return null;
            }

            File dir = FeedbackAttachmentProvider.getAttachmentsDir(this);
            String fname = "feedback_img_" + System.currentTimeMillis() + "_" + (int) (Math.random() * 9999) + ".jpg";
            File outFile = new File(dir, fname);
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 84, fos);
                fos.flush();
            }
            bmp.recycle();
            return outFile;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void refreshFeedbackThumbnails() {
        if (feedbackThumbsRow == null) {
            return;
        }
        int count = feedbackSelectedFiles.size();
        if (feedbackAttachCountText != null) {
            feedbackAttachCountText.setText(count + " / " + MAX_FEEDBACK_IMAGES + " صور");
        }
        if (feedbackAttachBtn != null) {
            boolean canAddMore = count < MAX_FEEDBACK_IMAGES;
            feedbackAttachBtn.setEnabled(canAddMore);
            feedbackAttachBtn.setAlpha(canAddMore ? 1f : 0.45f);
        }

        feedbackThumbsRow.removeAllViews();
        if (count == 0) {
            feedbackThumbsRow.setVisibility(View.GONE);
            return;
        }
        feedbackThumbsRow.setVisibility(View.VISIBLE);

        for (int i = 0; i < count; i++) {
            final File imgFile = feedbackSelectedFiles.get(i);
            FrameLayout thumbBox = new FrameLayout(this);
            GradientDrawable tbBg = new GradientDrawable();
            tbBg.setColor(LauncherConfig.COL_CARD_INNER);
            tbBg.setCornerRadius(dp(12));
            tbBg.setStroke(dp(1), Color.parseColor("#38BDF8"));
            thumbBox.setBackground(tbBg);
            thumbBox.setPadding(dp(2), dp(2), dp(2), dp(2));

            ImageView iv = new ImageView(this);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 4;
            Bitmap thumbBmp = BitmapFactory.decodeFile(imgFile.getAbsolutePath(), o);
            if (thumbBmp != null) {
                iv.setImageBitmap(thumbBmp);
            }
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            clipRounded(iv, dp(10));
            thumbBox.addView(iv, new FrameLayout.LayoutParams(dp(76), dp(76)));

            // زر حذف الصورة (✕) في الزاوية العلوية
            TextView btnDel = makeText("✕", 11f, Color.WHITE, true);
            btnDel.setGravity(Gravity.CENTER);
            GradientDrawable delBg = new GradientDrawable();
            delBg.setShape(GradientDrawable.OVAL);
            delBg.setColor(Color.parseColor("#DC2626"));
            delBg.setStroke(dp(1), Color.WHITE);
            btnDel.setBackground(delBg);
            btnDel.setOnClickListener(v -> {
                feedbackSelectedFiles.remove(imgFile);
                imgFile.delete();
                refreshFeedbackThumbnails();
            });
            FrameLayout.LayoutParams delLp = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP | Gravity.LEFT);
            delLp.setMargins(dp(3), dp(3), dp(3), dp(3));
            thumbBox.addView(btnDel, delLp);

            LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(dp(80), dp(80));
            boxLp.leftMargin = dp(8);
            feedbackThumbsRow.addView(thumbBox, boxLp);
        }
    }

    private void submitFeedbackPayload(String type, String message) {
        final List<File> attachedCopy = new ArrayList<>(feedbackSelectedFiles);
        final String installedVer = getInstalledGameVersion();
        final long installedVc = getInstalledGameVersionCode();
        final String remoteVer = latestRemoteManifest != null ? latestRemoteManifest.patchVersion : "";
        final String webhookUrl = latestRemoteManifest != null ? latestRemoteManifest.feedbackWebhookUrl : "";

        final FeedbackPayload payload = new FeedbackPayload(
                type,
                message,
                activeProfile,
                installedVer,
                installedVc,
                isPatchCurrentlyInstalled,
                installedPatchVersion,
                remoteVer,
                BetaModeManager.isBetaEnabled(this),
                attachedCopy
        );

        worker.execute(() -> {
            payload.saveBundleLocally(this);
            final String shareText = payload.formatShareText();

            if (!webhookUrl.isEmpty()) {
                try {
                    boolean sent = payload.postToWebhookSync(webhookUrl);
                    if (sent) {
                        mainHandler.post(() -> Toast.makeText(
                                this,
                                "✓ تم إرسال البلاغ بنجاح إلى فريق R.D.AV! شكراً لمساهمتك.",
                                Toast.LENGTH_LONG
                        ).show());
                        return;
                    }
                } catch (Throwable ignored) {
                    // fallback للنسخ والمشاركة المباشرة عند تعذر الاتصال بالسحابة
                    mainHandler.post(() -> {
                        copyReportToClipboard(shareText, false);
                        showFeedbackShareDialog(payload, shareText, true);
                    });
                    return;
                }
            }

            // الوضع الجاهز قبل ضبط رابط الاستقبال السحابي: حفظ JSON محلياً + نسخ + مشاركة مع الصور لـ @R.D.AV
            mainHandler.post(() -> {
                copyReportToClipboard(shareText, false);
                showFeedbackShareDialog(payload, shareText, false);
            });
        });
    }

    private void showFeedbackShareDialog(FeedbackPayload payload, String shareText, boolean cloudFallback) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(12));
        root.setBackgroundColor(LauncherConfig.COL_BG);

        String bannerMsg = cloudFallback
                ? "تم حفظ حزمة البلاغ محلياً ونسخ النص والبيانات التقنية — تعذر الوصول للخادم السحابي، يمكنك إرسالها مباشرة الآن:"
                : "✅  تم تجهيز وحفظ حزمة البلاغ (JSON + البيانات التقنية"
                  + (payload.attachmentFiles.isEmpty() ? "" : " + " + payload.attachmentFiles.size() + " صور")
                  + ") ونسخ النص تلقائياً للحافظة!";

        TextView tvInfo = makeText(bannerMsg, 11.5f, LauncherConfig.COL_SUCCESS, true);
        tvInfo.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable infoBg = new GradientDrawable();
        infoBg.setColor(Color.parseColor("#0F2D1F"));
        infoBg.setCornerRadius(dp(10));
        infoBg.setStroke(dp(1), LauncherConfig.COL_SUCCESS);
        tvInfo.setBackground(infoBg);
        LinearLayout.LayoutParams infLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        infLp.bottomMargin = dp(12);
        root.addView(tvInfo, infLp);

        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("حزمة البلاغ جاهزة للإرسال ✓")
                .setView(root)
                .create();

        // زر 1: مشاركة النص + الصور المرفقة مباشرة عبر مشاركة النظام (Instagram / تطبيقات التواصل)
        if (!payload.attachmentFiles.isEmpty()) {
            LinearLayout btnShareAll = makeIconButton(
                    "مشاركة البلاغ مع الصور (" + payload.attachmentFiles.size() + ")",
                    R.drawable.ic_photo_attach,
                    Color.parseColor("#0E2233"),
                    Color.parseColor("#E0F2FE"),
                    Color.parseColor("#38BDF8"),
                    12.5f
            );
            btnShareAll.setOnClickListener(v -> {
                copyReportToClipboard(shareText, false);
                shareFeedbackWithImages(payload, shareText);
            });
            LinearLayout.LayoutParams shLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
            shLp.bottomMargin = dp(8);
            root.addView(btnShareAll, shLp);
        }

        // زر 2: فتح حساب @R.D.AV على Instagram مباشرة للصق وإرسال البلاغ
        LinearLayout btnIgDirect = makeInstagramPill("فتح حساب R.D.AV على Instagram وإرسال البلاغ", 12.2f, 16, 12, 11);
        btnIgDirect.setOnClickListener(v -> {
            copyReportToClipboard(shareText, false);
            openInstagramSupport();
        });
        LinearLayout.LayoutParams igLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        igLp.bottomMargin = dp(8);
        root.addView(btnIgDirect, igLp);

        Button btnClose = makeButton("إغلاق", LauncherConfig.COL_CARD_INNER, LauncherConfig.COL_MUTED, LauncherConfig.COL_BORDER, true);
        btnClose.setTextSize(12f);
        btnClose.setOnClickListener(v -> dialog.dismiss());
        root.addView(btnClose, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));

        dialog.show();
    }

    private void shareFeedbackWithImages(FeedbackPayload payload, String shareText) {
        try {
            ArrayList<Uri> uris = new ArrayList<>();
            for (File f : payload.attachmentFiles) {
                if (f != null && f.isFile()) {
                    uris.add(FeedbackAttachmentProvider.buildUriForFile(this, f));
                }
            }
            if (uris.isEmpty()) {
                openInstagramSupport();
                return;
            }
            Intent shareIntent;
            if (uris.size() == 1) {
                shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("image/jpeg");
                shareIntent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            } else {
                shareIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                shareIntent.setType("image/jpeg");
                shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            }
            shareIntent.putExtra(Intent.EXTRA_TEXT, shareText);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "إرسال البلاغ والصور إلى R.D.AV"));
        } catch (Throwable t) {
            Toast.makeText(this, "تم نسخ النص — يمكنك إرفاق الصور مباشرة في Instagram.", Toast.LENGTH_LONG).show();
            openInstagramSupport();
        }
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
            float alpha = busy ? 0.55f : 1f;
            btnInstallOnline.setEnabled(!busy);
            btnInstallOnline.setAlpha(alpha);
            btnInstallLocalZip.setEnabled(!busy);
            btnInstallLocalZip.setAlpha(alpha);
            if (btnInstallBeta != null) {
                btnInstallBeta.setEnabled(!busy);
            }
            if (btnBackToLibrary != null) {
                btnBackToLibrary.setEnabled(!busy);
            }
            if (btnDiagScan != null) {
                btnDiagScan.setEnabled(!busy);
                btnDiagScan.setAlpha(alpha);
            }
            btnRestore.setEnabled(!busy);
            btnRestore.setAlpha(alpha);
            if (btnFeedback != null) {
                btnFeedback.setEnabled(!busy);
                btnFeedback.setAlpha(alpha);
            }
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
