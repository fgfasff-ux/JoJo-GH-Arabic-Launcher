# =============================================================================
# JoJo-GH-Arabic-Launcher — R8 / ProGuard Hardening Rules
# =============================================================================
-optimizationpasses 5
-allowaccessmodification
-repackageclasses 'o'
-mergeinterfacesaggressively
-dontusemixedcaseclassnames

# Strip source file names and line numbers to harden against reverse engineering
-renamesourcefileattribute SourceFile
-keepattributes Signature,InnerClasses,EnclosingMethod

# 1) Keep ONLY the exact app_process entrypoint for ShellBridgeServer.
#    All internal logic, MD5 checks, and helper methods inside ShellBridgeServer remain obfuscated!
-keep class com.jojo.gh.arabic.launcher.ShellBridgeServer {
    public static void main(java.lang.String[]);
}

# 2) Keep Shizuku SDK, AIDL binder interfaces, and reflection target Shizuku.newProcess
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keep interface moe.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**
-dontwarn org.tukaani.xz.**

# 3) Keep Android Parcelable & Binder stubs used by Shizuku IPC
-keep class * implements android.os.IInterface { *; }
-keep class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# 4) Remove verbose logging calls in release builds
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
