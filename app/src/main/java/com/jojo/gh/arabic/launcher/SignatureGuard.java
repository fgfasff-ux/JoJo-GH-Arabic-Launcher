package com.jojo.gh.arabic.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.os.Process;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * حارس سلامة التوقيع الرقمي للتطبيق (Anti-Tamper Signature Verification).
 * يتحقق عند التشغيل من بصمة SHA-256 لشهادة توقيع الـ APK مقابل البصمة المشفّرة في SecretVault.
 * إذا تم فك التطبيق وتعديله وإعادة توقيعه بمفتاح مختلف يتوقف التشغيل فوراً.
 */
final class SignatureGuard {

    private SignatureGuard() {
    }

    static boolean verifyOrTerminate(Activity activity) {
        if (isOfficialSignatureValid(activity)) {
            return true;
        }
        try {
            activity.finishAffinity();
        } catch (Throwable ignored) {
        }
        try {
            Process.killProcess(Process.myPid());
        } catch (Throwable ignored) {
        }
        System.exit(0);
        return false;
    }

    @SuppressWarnings("deprecation")
    static boolean isOfficialSignatureValid(Context context) {
        try {
            String expected = SecretVault.releaseCertSha256();
            if (expected == null || expected.length() != 64) {
                return false;
            }
            PackageManager pm = context.getPackageManager();
            String pkg = context.getPackageName();
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

            if (sigs == null || sigs.length == 0) {
                return false;
            }
            for (Signature sig : sigs) {
                if (sig != null && expected.equalsIgnoreCase(sha256Hex(sig.toByteArray()))) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String sha256Hex(byte[] input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(input);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format(Locale.US, "%02X", b & 0xFF));
        }
        return sb.toString();
    }
}
