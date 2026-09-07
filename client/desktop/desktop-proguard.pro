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
-keep class com.virjar.tk.desktop.TeamTalkMain {
    public static void main(java.lang.String[]);
}

# ── SQLDelight 生成类 ──
# AppDatabase(driver) 构造器被直接调用，但其内部生成的 Query 子类、
# Schema、Adapters 通过反射/泛型分发。ProGuard 看不到这些动态分发，需保留。
-keep class com.virjar.tk.shared.database.AppDatabase { *; }
-keep class com.virjar.tk.shared.database.AppDatabase$* { *; }

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
# 原生库在 JNI 绑定时按名反查方法；即使没有 JVM 调用者，也不能删除或改写签名。
# Windows 实机曾因 nShutdownMediaFoundation 被删而启动失败；Linux 桥也有同类反向引用。
# keepclasseswithmembernames 只防改名，不能防止未引用 native 方法被压缩删除。
# 三个平台都保留完整 native 方法组，不扩大为整个媒体库；构建后比较原始和压缩后的签名。
-keep class io.github.kdroidfilter.composemediaplayer.mac.MacNativeBridge {
    native <methods>;
}
-keep class io.github.kdroidfilter.composemediaplayer.windows.WindowsNativeBridge {
    native <methods>;
}
-keep class io.github.kdroidfilter.composemediaplayer.linux.LinuxNativeBridge {
    native <methods>;
}
-keep class io.github.kdroidfilter.composemediaplayer.util.NativeLibraryLoader { *; }
-dontwarn io.github.kdroidfilter.composemediaplayer.**

# ── JNA Windows 身份查询（Secur32 / Advapi32）──
# Structure 按反射读取 public 字段与 FieldOrder；PSID.sid 没有普通 JVM 读写者也不能删除。
# NativeMappedConverter / Structure.newInstance 通过反射调用构造器。
# 仅保留仍被使用类的反射成员，不让整套 jna-platform / Win32 API 变成打包根。
# 来源：JNA 5.15.0 Structure.java、NativeMappedConverter.java、WinNT.PSID。
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep @interface com.sun.jna.Structure$FieldOrder { *; }
-keepclassmembers class * extends com.sun.jna.Structure {
    public <fields>;
    public <init>();
    public <init>(com.sun.jna.Pointer);
}
-keepclassmembers class * implements com.sun.jna.NativeMapped {
    public <init>();
}
# JNA Native.initIDs 在启动时固定查询这些 JNI 入口，包括当前没有 Java 调用者的分支。
# 依据已解析 JNA 5.18.1 的 native/dispatch.c:Java_com_sun_jna_Native_initIDs。
-keep class com.sun.jna.Native {
    private static void dispose();
    private static com.sun.jna.NativeMapped fromNative(java.lang.Class, java.lang.Object);
    private static com.sun.jna.NativeMapped fromNative(java.lang.reflect.Method, java.lang.Object);
    private static java.lang.Class nativeType(java.lang.Class);
    private static java.lang.Object toNative(com.sun.jna.ToNativeConverter, java.lang.Object);
    private static java.lang.Object fromNative(com.sun.jna.FromNativeConverter, java.lang.Object, java.lang.reflect.Method);
}
-keep class com.sun.jna.Pointer {
    public <init>(long);
    long peer;
}
-keep class com.sun.jna.Structure {
    com.sun.jna.Pointer getTypeInfo();
    private static com.sun.jna.Structure newInstance(java.lang.Class, long);
    public void autoRead();
    public void autoWrite();
    com.sun.jna.Pointer memory;
    long typeInfo;
}
-keep interface com.sun.jna.Structure$ByValue
-keep interface com.sun.jna.Callback
-keep class com.sun.jna.CallbackReference$AttachOptions {
    public <fields>;
    public <init>();
}
-keep class com.sun.jna.CallbackReference {
    private static com.sun.jna.Callback getCallback(java.lang.Class, com.sun.jna.Pointer, boolean);
    private static com.sun.jna.Pointer getFunctionPointer(com.sun.jna.Callback, boolean);
    private static com.sun.jna.Pointer getNativeString(java.lang.Object, boolean);
    private static java.lang.ThreadGroup initializeThread(com.sun.jna.Callback, com.sun.jna.CallbackReference$AttachOptions);
}
-keep class com.sun.jna.WString { public <init>(java.lang.String); }
-keep interface com.sun.jna.NativeMapped { java.lang.Object toNative(); }
-keep class com.sun.jna.IntegerType { long value; }
-keep class com.sun.jna.PointerType { com.sun.jna.Pointer pointer; }
-keep class com.sun.jna.JNIEnv
-keep interface com.sun.jna.Native$ffi_callback { void invoke(long, long, long); }
-keep interface com.sun.jna.FromNativeConverter { java.lang.Class nativeType(); }
-keep class com.sun.jna.Structure$FFIType$FFITypes { com.sun.jna.Pointer ffi_type_*; }

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
