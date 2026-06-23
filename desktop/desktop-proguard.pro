# ─────────────────────────────────────────────────────────────
# TeamTalk Desktop ProGuard 规则（仅压缩，不混淆）
#
# 原则：最小 keep，报错驱动补 keep。
# 只 keep 真正的反射/序列化/SPI 入口，其余交给 ProGuard 按静态引用裁剪。
# 避免 -keep class xxx.** { *; } 这种整包保留——会让压缩形同虚设。
# Compose/Skiko/Coroutines 的通用规则已由 compose 插件的
# default-compose-desktop-rules.pro 提供，这里只补项目特定 + 第三方反射点。
#
# 调试方法：构建后运行，看 NoClassDefFoundError / ClassNotFoundException /
# VerifyError / ServiceConfigurationError 等异常，按需补 keep。
# ─────────────────────────────────────────────────────────────

-dontwarn

# 注意：不要用 -dontpreverify。Compose 字节码分支多，JVM 9+ 强制要求
# StackMapTable；-dontpreverify 会导致 VerifyError: Expecting a stackmap frame。

# ── 入口 ──
-keep class com.virjar.tk.MainKt {
    public static void main(java.lang.String[]);
}

# ── SQLDelight 生成类 ──
# AppDatabase(driver) 构造器被直接调用，但其内部生成的 Query 子类、
# Schema、Adapters 通过反射/泛型分发。ProGuard 看不到这些动态分发，需保留。
-keep class com.virjar.tk.database.AppDatabase { *; }
-keep class com.virjar.tk.database.AppDatabase$* { *; }

# ── JDBC SQLite 驱动（通过 jdbc:sqlite URL 反射加载）──
# DriverManager 通过 META-INF/services/java.sql.Driver 找到驱动实现类。
# 报错驱动补 keep：
#  - JDBC：Driver SPI 入口，Class.forName 反射加载，引用分析看不到
#  - BusyHandler/Collation/Function/ProgressHandler：native 库 JNI 注册时 FindClass（从 libsqlitejdbc 符号表提取）
# DB/NativeDB/SQLiteDataSource 等由引用分析自动保留。
-keep class org.sqlite.JDBC
# native 库 JNI 注册时 FindClass 的类（从 libsqlitejdbc 符号表提取）+ 其内部类与方法。
# JNI 回调引用具体方法签名（如 DB$ProgressObserver.progress），需保留 { *; }。
-keep class org.sqlite.core.DB { *; }
-keep class org.sqlite.core.DB$* { *; }
-keep class org.sqlite.core.NativeDB { *; }
-keep class org.sqlite.core.NativeDB$* { *; }
-keep class org.sqlite.BusyHandler { *; }
-keep class org.sqlite.BusyHandler$* { *; }
-keep class org.sqlite.Collation { *; }
-keep class org.sqlite.Collation$* { *; }
-keep class org.sqlite.Function { *; }
-keep class org.sqlite.Function$* { *; }
-keep class org.sqlite.ProgressHandler { *; }
-keep class org.sqlite.ProgressHandler$* { *; }
-dontwarn org.sqlite.**

# ── composemediaplayer JNI native bridge ──
# MacNativeBridge 通过 JNI 加载 .dylib，native 方法签名必须与 .dylib 精确匹配。
# ProGuard 压缩可能改变 native 方法的参数/返回类型签名，导致 NoSuchMethodError。
# 只 keep native 方法和 class 初始化（<clinit> 加载 .dylib），不整包保留。
-keep class io.github.kdroidfilter.composemediaplayer.mac.MacNativeBridge {
    native <methods>;
}
-keep class io.github.kdroidfilter.composemediaplayer.util.NativeLibraryLoader { *; }
-dontwarn io.github.kdroidfilter.composemediaplayer.**

# ── Logback SPI 入口 ──
# SLF4J 通过 META-INF/services 加载 LogbackServiceProvider。
# logback.xml 配置的 Appender/Encoder/Policy 通过类名字符串引用，ProGuard 看不到。
# logback 内部还有大量动态加载（Joran 解析器按 XML 元素名映射 handler）。
# 实测整包 allowshrinking 会导致配置解析失败、日志丢失，故整包保留。
-keep class ch.qos.logback.** { *; }
-dontwarn ch.qos.logback.**

# slf4j：logback Logger 方法签名依赖 Marker 等，静态分析易误删
-keep class org.slf4j.** { *; }

# ── Netty：内部 shaded jctools 队列互引紧密，报错驱动发现需整包保留 ──
-keep class io.netty.** { *; }
-dontwarn io.netty.**

# ── JNI native 方法持有方（Skiko/JNA/sqlite）──
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── 枚举 valueOf（反射常用）──
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
