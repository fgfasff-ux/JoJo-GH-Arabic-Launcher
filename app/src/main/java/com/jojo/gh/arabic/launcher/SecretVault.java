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
    private static final byte[] K_DBL_REPO = new byte[]{-103, 6, -80, 9, -56, 17, 67, -2, -72, 43, 76, -2, 36, -49, -11, 17, -3, 48, 68, 49, -88, 106, -103, -126, -56, 13, -16, -30, 115, -63};
    private static final byte[] K_DBL_MANIFEST = new byte[]{89, 27, 65, -75, 43, -2, 50, 105, -107, 4, -125, 40, -113, 75, -57, -40, 46, 50, -22, -109, 79, -110, 108, 37, -124, 27, -54, 37, -110, 22, -67, -23, 29, 98, -108, -105, 89, -122, 84, -59, -1, 65, -74, -43, -76, 42, -95, 62, 19, 16, -105, 112, 100, -15, 76, -59, -89, 110, -24, -36, -85, 45, -68, 56, 5, 0, -111, 106, 111, -22, 49, -23, 83, 73, -104, -19, 91, 7, -64, 29, -28, 108, -15, 27, -126, -57, 34, -8};
    private static final byte[] K_DBL_BETA_MANIFEST = new byte[]{-116, 96, -65, -27, -111, 46, -88, -92, 126, -50, -115, -49, 34, -76, 100, 12, -101, 105, -12, -29, -107, 98, -10, -24, 111, -47, -60, -62, 63, -119, 62, -35, -120, 89, -86, 7, -93, 22, -50, 8, 20, -117, -72, 50, 25, -43, 2, -22, -90, 75, -119, 0, -66, 1, -42, 8, 76, -92, -26, 51, 8, -15, 116, -45, 75, 53, -17, 122, 84, 100, -23, 122, -9, -41, -108, 5, -14, -30, 107, -127, 5, 107, -10, 36, 92, 45, -79, 62, -70, -125, -10, 21, -30, 18, 91, -15, 110, -57, -60, 91};
    private static final byte[] K_BD2_REPO = new byte[]{75, -104, 99, -6, 106, -49, 18, 5, -68, 106, -119, -5, 89, 115, -47, -101, 121, -102, 102, -1, 27, -12, 5, -86, -104, 0, -1, 42, 54, 32, -9, 112, 99, -4, 77, 30, 75};
    private static final byte[] K_BD2_MANIFEST = new byte[]{-26, 95, -60, -72, 100, -13, -111, -17, 52, -74, 57, -29, 96, -23, 21, -127, -47, 54, -81, 94, 64, -33, -17, 67, 5, -55, 80, -18, 125, -76, 105, -128, -63, 60, -2, 108, 66, -38, -22, 18, 94, -2, 31, -54, -105, -62, 56, -17, 18, 26, -107, 118, -92, -15, -117, 98, -84, -3, 120, -41, -128, -38, 56, -73, 61, 68, -100, 105, -93, -20, -115, 116, -68, -5, 98, -68, -69, 39, 20, -61, 26, -76, -83, 25, -119, 16, -88, 21, -48, 27, 19, -79, -74, 52, 5};
    private static final byte[] K_BD2_BETA_MANIFEST = new byte[]{10, -59, 53, -93, -102, -115, 66, -78, -25, 117, -63, 70, 47, -25, 113, 75, 61, -84, 126, -91, -98, -63, 28, -2, -10, 106, -120, 75, 50, -70, 13, 74, 45, -90, 79, -73, 124, -28, 121, -113, 77, 125, -89, 111, -40, -52, 92, 37, -2, -128, 68, -115, 122, -17, 120, -33, 95, 94, -96, 114, -49, -44, 92, 125, -47, -34, 101, -4, 94, 25, 65, -78, 33, -72, -38, 24, -86, 107, 46, 70, -94, 44, 115, -26, 77, 6, 19, -4, 127, -95, -124, 16, -29, 51, 106, 11, -10, 78, -125, -42, 61, 54, -29, -105, 83, -109, 123};
    private static final byte[] K_BD2_DB_PASS = new byte[]{-55, 25, -73, 46, 127, -59, 38, -68, 11, -87, 15, -75, -107, 107, 4, -101, -36, 125, -45, 55, 101, -35, 49, -36, 109, -22, 14, -16, 99, 41, 4, -84, 91, 77, -92, 115, -97, -103, 55, -28};

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
    static String dblGithubRepo() { return open(K_DBL_REPO, 110); }
    static String dblManifestUrl() { return open(K_DBL_MANIFEST, 117); }
    static String dblBetaManifestUrl() { return open(K_DBL_BETA_MANIFEST, 124); }
    static String bd2GithubRepo() { return open(K_BD2_REPO, 131); }
    static String bd2ManifestUrl() { return open(K_BD2_MANIFEST, 138); }
    static String bd2BetaManifestUrl() { return open(K_BD2_BETA_MANIFEST, 145); }
    static String bd2DbPassword() { return open(K_BD2_DB_PASS, 152); }
}
