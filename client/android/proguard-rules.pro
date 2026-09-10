# ── Netty 可选依赖（Log4J/BlockHound 不在 classpath） ──
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn reactor.blockhound.**

# ── 应用入口 ──
-keep class com.virjar.tk.android.MainActivity { *; }
# 厂商推送组件经系统广播/服务反射拉起；未配置厂商的生成占位类同样保留可执行形态。
-keep class com.virjar.tk.android.XiaomiPushReceiver { *; }
-keep class com.virjar.tk.android.HuaweiPushService { *; }
-keep class com.virjar.tk.android.HonorPushService { *; }
-keep class com.virjar.tk.android.VivoPushReceiver { *; }
-keep class com.virjar.tk.android.MeizuPushReceiver { *; }

# ── Compose / Kotlin ──
-keep class androidx.compose.** { *; }
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# ── SQLDelight（Kotlin 生成的代码依赖反射） ──
-keep class com.virjar.tk.shared.database.** { *; }

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
