# Don't obfuscate code
-dontobfuscate

# GSON
# Gson uses generic type information stored in a class file when working with
# fields. Proguard removes such information by default, keep it.
-keepattributes Signature
-keepattributes *Annotation*

# This is also needed for R8 in compat mode since multiple
# optimizations will remove the generic signature such as class
# merging and argument removal. See:
# https://r8.googlesource.com/r8/+/refs/heads/main/compatibility-faq.md#troubleshooting-gson-gson
-keep,allowobfuscation class com.google.gson.reflect.TypeToken{<init>(...);}
-keep,allowobfuscation class * extends com.google.gson.reflect.TypeToken{<init>(...);}

# Firebase
-keepattributes SourceFile,LineNumberTable
-keep class com.google.firebase.crashlytics.** { *; }

# Optimized Google Tink Rules
-keep class com.google.crypto.tink.** { public *; }
-dontwarn com.google.crypto.tink.**

# Specifically preserve classes used by AndroidKeysetManager & SharedPreferences
-keep class com.google.crypto.tink.integration.android.** { *; }

# oKhttp3
# A resource is loaded with a relative path so the package of this class must be preserved.
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase{<init>(...);}

# Kotlin
-keep class kotlin.coroutines.Continuation{<init>(...);}

# Optional. For using GSON @Expose annotation
-keepattributes AnnotationDefault, RuntimeVisibleAnnotations

# Our code
-keep class com.starlight.binding.input.evdev.* {*;}

# Starlight common
-keep class com.starlight.nvstream.jni.* {*;}

# Class Members
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

# Prevent Proguard from stripping away the Javascript Bridge callbacks
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

#Drawables
-keep class android.support.graphics.drawable.** { *; }

# Okio
#noinspection ShrinkerUnresolvedReference
-keep class sun.misc.Unsafe {*;}
-dontwarn java.nio.file.*
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

# BouncyCastle
-keep class org.bouncycastle.jcajce.provider.asymmetric.* {*;}
-keep class org.bouncycastle.jcajce.provider.asymmetric.util.* {*;}
-keep class org.bouncycastle.jcajce.provider.asymmetric.rsa.* {*;}
-keep class org.bouncycastle.jcajce.provider.digest.** {*;}
-keep class org.bouncycastle.jcajce.provider.symmetric.** {*;}
-keep class org.bouncycastle.jcajce.spec.* {*;}
-keep class org.bouncycastle.jce.** {*;}
-dontwarn javax.naming.**

# Volley
-dontwarn org.chromium.**

# jMDNS
-dontwarn javax.jmdns.impl.DNSCache
-dontwarn org.slf4j.**

# AC-3 passthrough encoder (JNI)
-keep class com.starlight.binding.audio.NativeAc3Encoder { native <methods>; }
