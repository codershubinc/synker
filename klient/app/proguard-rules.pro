# Quazaar Synker R8 / ProGuard Optimization Rules

# Preserve Kotlin data classes and models used in database and serialization
-keep class com.quazaar.synker.klient.data.** { *; }

# Keep OkHttp & WebSocket rules
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Kotlinx Coroutines & Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.coroutines.**
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}

# Android Media & Lifecycle
-keep class androidx.lifecycle.** { *; }
