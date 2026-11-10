# libVLC is accessed from native code (JNI); keep every class and member.
-keep class org.videolan.** { *; }
-dontwarn org.videolan.**

# kotlinx.serialization (the plugin ships its own consumer rules; these are belt and braces)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.jellydirect.app.data.**$$serializer { *; }
-keepclassmembers class com.jellydirect.app.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.jellydirect.app.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp / Okio optional platform classes
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
