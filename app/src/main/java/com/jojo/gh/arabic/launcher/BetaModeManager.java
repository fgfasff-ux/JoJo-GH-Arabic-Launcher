package com.jojo.gh.arabic.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * مدير وضع اختبار التعريب السري (Password-Protected Beta Test Mode).
 * يحفظ كلمة مرور المطور مشفّرة بالكامل عبر SHA-256 فقط دون أي نص صريح.
 */
public final class BetaModeManager {

    private static final String PREFS_NAME = "rdav_beta_vault_prefs";
    private static final String KEY_BETA_ENABLED = "beta_channel_enabled";
    private static final String KEY_PASS_SHA256 = "beta_dev_pass_sha256";

    private BetaModeManager() {
    }

    public static boolean isBetaEnabled(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_BETA_ENABLED, false);
    }

    public static void setBetaEnabled(Context ctx, boolean enabled) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_BETA_ENABLED, enabled).apply();
    }

    public static boolean verifyPassword(Context ctx, String plainInput) {
        if (plainInput == null || plainInput.isEmpty()) {
            return false;
        }
        String inputHash = sha256Hex(plainInput.trim());
        String expectedHash = getStoredPasswordHash(ctx);
        return constantTimeEquals(inputHash, expectedHash);
    }

    public static boolean changePassword(Context ctx, String currentPlain, String newPlain) {
        if (!verifyPassword(ctx, currentPlain)) {
            return false;
        }
        if (newPlain == null || newPlain.trim().length() < 4) {
            return false;
        }
        String newHash = sha256Hex(newPlain.trim());
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_PASS_SHA256, newHash).apply();
        return true;
    }

    private static String getStoredPasswordHash(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String custom = prefs.getString(KEY_PASS_SHA256, null);
        if (custom != null && custom.length() == 64) {
            return custom.toLowerCase(Locale.US);
        }
        return SecretVault.defaultBetaPassSha256().toLowerCase(Locale.US);
    }

    public static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.US, "%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
