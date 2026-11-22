package com.jojo.gh.arabic.launcher;

import java.nio.charset.StandardCharsets;

/**
 * خزنة الثوابت المشفّرة في الذاكرة (XOR + Rolling Salt).
 * تمنع ظهور بصمات MD5 وروابط التحديث وبصمة التوقيع كنصوص صريحة في classes.dex.
 */
final class SecretVault {

    private static final byte[] MASK = new byte[]{90, -61, 113, -98, 45, -12, -117, 54, -25, 25, -92, 98, -40, 79, -79, 12};

    private static final byte[] K_PKG = new byte[]{125, -44, 77, 118, -16, 56, 64, 52, -94, 97, -73, -108, -49, 20, -91, -11, 126, -123, 87, 36, -8, 43, 95, 44};
    private static final byte[] K_ORIG_MD5 = new byte[]{-64, 19, -78, -112, 109, 113, -97, -81, 118, -82, 52, 108, -84, 68, -39, 26, -44, 117, -10, 18, 44, 1, -87, 41, 52, -39, 115, -106, -17, 48, -98, -111};
    private static final byte[] K_PATCH_MD5 = new byte[]{10, -17, 1, 34, -60, 61, -111, -71, -51, 73, -98, 84, 2, -3, -105, 10, 79, -98, 68, -42, -113, 68, -47, 59, -14, 89, -114, 64, 101, -18, -125, 104};
    private static final byte[] K_MANIFEST = new byte[]{123, 16, -22, 125, 118, -83, 2, 92, 70, -72, 56, -72, -124, 8, -80, 34, 108, 25, -95, 123, 114, -31, 92, 16, 87, -89, 113, -75, -103, 85, -60, 62, -71, 43, -45, 106, -83, -102, 12, 33, -96, -109, 69, -109, 60, 6, -97, 20, -115, 55, -122, 88, -121, -120, 47, 38, -67, -124, 93, -109, 100, 41, -63, 29, -78, -48, -69, 62, -79, 120, 9, 92, -106, 127, 96, -1, 80, -50, -15, 108, -94, -38, -89, 59, -80, 52, 9, 13, -101, 114, 115, -18};
    private static final byte[] K_COMMUNITY = new byte[]{117, -9, 71, -102, -59, 105, -89, 15, 88, -56, -30, 64, 14, -43, 75, -16, 98, -2, 12, -100, -63, 37, -7, 99, -87, -9, -53, 109, -13, -88, 95, -52, -73, -52, 126, -115, 30, 94, -87, 114, -66, -29, -97, 107, -74, -37, 100, -58, -125, -48, 43, -65, 52, 76, -118, 21, -125, 20, -89, 11, -50, 20};
    private static final byte[] K_INSTAGRAM = new byte[]{42, -23, 51, 64, 57, -29, 118, -28, -122, -40, 2, -85, -15, 106, -82, 114, -45, -54, 69, 97, -41, -57, 74, -108, 108, -80, 119, -101, 12, 26, -84, 96, -115};
    private static final byte[] K_SIGN_SHA256 = new byte[]{-104, 101, 125, -28, -41, 67, -40, 123, -31, -31, 62, -25, -101, -29, 126, -7, 26, 94, 15, -42, 81, 80, -68, 23, -124, -12, 41, -127, -126, -9, 102, -98, 122, 53, 27, -59, 70, -42, -7, 105, -79, 3, 26, -9, -79, 1, 85, -110, 75, -76, 32, -73, 1, -58, -104, 11, -83, 97, 125, -31, -90, 97, 79, -113};
    private static final byte[] K_LANG_PATH = new byte[]{-1, -83, 4, -118, 30, -29, 2, -6, 1, 94, -85, -68, 44, 8, -45, 13, -53, -73, 7, -105, 69, -66, 4, -52, 9, 74, -90};
    private static final byte[] K_INDEX_PATH = new byte[]{96, 96, -13, 93, 12, 92, -28, 52, -95, -103, 19, -19, 121, 106, 17, -18, 117};
    private static final byte[] K_SHIZUKU = new byte[]{-99, -61, 32, -80, 89, 93, -58, -30, 87, 26, -12, 61, -39, -123, -60, 43, -87, 48, 16, -119, 127, -95, -79, -119, 98, -24};
    private static final byte[] K_REPO = new byte[]{92, -37, -119, 66, -126, 105, -9, 34, -54, 22, 29, -99, 99, -10, -52, 16, 77, -28, -46, 114, -109, 126, -29, 118, -52, 83, 110, -90, 105, -62, -48, 69, 127, -18};
    private static final byte[] K_BETA_PASS_HASH = new byte[]{-40, 112, 58, -87, 4, -104, -23, 54, -6, -41, -9, 63, -20, 118, 30, 21, -112, 52, 42, -80, 24, -117, -4, 35, -126, -32, 11, 90, -105, 79, -69, 119, -75, 6, -118, -119, 96, -68, 91, 18, -62, -90, 26, 30, -121, 94, -81, 103, -92, 64, -49, -108, 37, -82, 28, 80, -14, -128, 122, -20, -28, 33, -99, -54};

    private SecretVault() {
    }

    private static String open(byte[] enc, int salt) {
        byte[] out = new byte[enc.length];
        int mLen = MASK.length;
        for (int i = 0; i < enc.length; i++) {
            int k = (MASK[(i + salt) % mLen] & 0xFF) ^ ((i * 31 + salt * 17) & 0xFF);
            out[i] = (byte) ((enc[i] & 0xFF) ^ k);
        }
        return new String(out, StandardCharsets.UTF_8);
    }

    static String gamePackage() { return open(K_PKG, 26); }
    static String origLangMd5() { return open(K_ORIG_MD5, 33); }
    static String patchedLangMd5() { return open(K_PATCH_MD5, 40); }
    static String manifestUrl() { return open(K_MANIFEST, 47); }
    static String communityUrl() { return open(K_COMMUNITY, 54); }
    static String instagramUrl() { return open(K_INSTAGRAM, 61); }
    static String releaseCertSha256() { return open(K_SIGN_SHA256, 68); }
    static String langRelPath() { return open(K_LANG_PATH, 75); }
    static String indexRelPath() { return open(K_INDEX_PATH, 82); }
    static String shizukuPackage() { return open(K_SHIZUKU, 89); }
    static String githubRepo() { return open(K_REPO, 96); }
    static String defaultBetaPassSha256() { return open(K_BETA_PASS_HASH, 103); }
}
