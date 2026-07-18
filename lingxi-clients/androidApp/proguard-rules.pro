# 灵犀 Android ProGuard 规则

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor
-dontwarn io.ktor.**
-keep class io.ktor.** { *; }

# Koin
-keep class org.koin.** { *; }
-dontwarn org.koin.**

# Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# 项目模型类（序列化）
-keep class top.mvpdark.lingxi.data.model.** { *; }
