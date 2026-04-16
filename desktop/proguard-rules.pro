# TeamTalk Desktop ProGuard Rules
# 策略: tree-shaking 移除未使用代码，不混淆（Compose 应用混淆后极易出问题）

-dontoptimize
-dontobfuscate
-dontpreverify

# ===== 保留应用入口点 =====
-keep class com.virjar.tk.MainKt {
    public static void main(...);
}

# ===== Kotlin =====
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }
-keepclassmembers class * {
    ** Companion;
}
-keepnames class * implements java.io.Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    !static !transient <fields>;
}

# ===== Kotlinx Serialization =====
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.virjar.tk.**$$serializer { *; }
-keepclassmembers class com.virjar.tk.** {
    *** Companion;
}
-keepclasseswithmembers class com.virjar.tk.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ===== Compose =====
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# ===== Netty =====
-dontwarn io.netty.**
-keep class io.netty.** { *; }
-keep class com.virjar.tk.protocol.** { *; }

# ===== SLF4J / Logback =====
-dontwarn org.slf4j.**
-dontwarn ch.qos.logback.**

# ===== SQLDelight =====
-keep class com.virjar.tk.database.** { *; }
-keep class app.cash.sqldelight.** { *; }

# ===== Nucleus (Decorated Window / Tray) =====
-dontwarn io.github.kdroidfilter.**
-keep class io.github.kdroidfilter.** { *; }

# ===== Ktor Client =====
-dontwarn io.ktor.**

# ===== General =====
-keepattributes SourceFile,LineNumberTable
-keep public class * {
    public protected *;
}
