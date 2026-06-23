# ── Netty 可选依赖（Log4J/BlockHound 不在 classpath） ──
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn reactor.blockhound.**

# ── 应用入口 ──
-keep class com.virjar.tk.MainActivity { *; }

# ── Compose / Kotlin ──
-keep class androidx.compose.** { *; }
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# ── SQLDelight（Kotlin 生成的代码依赖反射） ──
-keep class com.virjar.tk.database.** { *; }

# ── 序列化 ──
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# ── Netty ──
-keep class io.netty.** { *; }
-dontwarn io.netty.**

# ── slf4j ──
-keep class org.slf4j.** { *; }

# ── 枚举 ──
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
