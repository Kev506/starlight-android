# ==============================================================================
# 1. CORE COMPILER CONFIGURATION & AGGRESSIVE SECURITY STRIPPING
# ==============================================================================

# Completely eliminate OkHttp logging from the release binary at compile time
-assumenosideeffects class okhttp3.logging.HttpLoggingInterceptor$Logger {
    public void log(java.lang.String);
}

# Necessary for GSON reflection and Kotlin serialization signatures
-keepattributes Signature, *Annotation*, AnnotationDefault, RuntimeVisibleAnnotations

# ==============================================================================
# 2. NATIVE JNI & SECURITY BOUNDARY SECURITY (Hardened)
# ==============================================================================

# Obfuscate all native bridge packages and classes while keeping the bare-minimum link names
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Explicit entries for your critical JNI handlers to ensure R8 handles their package structures cleanly
-keep class com.starlight.nvstream.jni.** { *; }
-keep class com.starlight.binding.audio.NativeAc3Encoder { native <methods>; }
-keep class com.starlight.utils.NativeKeyLoader { native <methods>; }

# ==============================================================================
# 3. CRITICAL DATA MODELS & SERIALIZATION (GSON)
# ==============================================================================

# Kept flexible: allows type tokens to be obfuscated while preserving serialization constructors
-keep,allowobfuscation class com.google.gson.reflect.TypeToken { <init>(...); }
-keep,allowobfuscation class * extends com.google.gson.reflect.TypeToken { <init>(...); }

# CRITICAL SECURITY STEP FOR GSON:
# If you use @SerializedName, fields can still be obfuscated safely.
# If you DO NOT use @SerializedName on a data class, you must manually add a explicit rule:
# "-keep class com.yourpackage.models.** { *; }" to prevent fields dropping to null.
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ==============================================================================
# 4. APP-SPECIFIC APP CODE & THIRD PARTY OVERRIDES
# ==============================================================================

# Input hardware interfaces (Preserved if handled by reflection or hardware layer mapping)
-keep class com.starlight.binding.input.evdev.* { *; }

# Google Tink (Hardened to protect crypto engine structures)
-keep class com.google.crypto.tink.** { public *; }
-keep class com.google.crypto.tink.integration.android.** { *; }
-dontwarn com.google.crypto.tink.**

# BouncyCastle Cryptography Configuration (X.509 Certificate & Crypto Engine)
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.asn1.** { *; }
-keep class org.bouncycastle.cert.** { *; }
-keep class org.bouncycastle.operator.** { *; }
-keep class org.bouncycastle.openssl.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# Preserve Security Providers
-keep class * extends java.security.Provider { *; }

# Core Android Lifecycle / System Callback Mapping
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepclassmembers class **.R$* {
    public static <fields>;
}

# WebView JavaScript Interfaces
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Unsafe Fallbacks
-dontwarn java.nio.file.*
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

# Firebase
-keepattributes SourceFile,LineNumberTable
-keep class com.google.firebase.crashlytics.** { *; }
