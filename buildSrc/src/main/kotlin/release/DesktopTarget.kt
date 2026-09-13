package release

/** 当前支持的桌面平台；安装器、图标和 JBR 入口随平台确定。 */
enum class DesktopPlatform(
    val id: String,
    val iconName: String,
    val javaExecutable: String,
    val installerExtension: String?,
) {
    MACOS("macos", "TeamTalk.icns", "bin/java", null),
    WINDOWS("windows", "TeamTalk.ico", "bin/java.exe", "exe"),
    LINUX("linux", "TeamTalk-256.png", "bin/java", "deb"),
}

/** 构建、JBR、密封目录与上传共用这四个目标，顺序及外部名称保持稳定。 */
enum class DesktopTarget(
    val platform: DesktopPlatform,
    val arch: String,
    val configurationName: String,
    private val composeArch: String,
) {
    MACOS_AARCH64(DesktopPlatform.MACOS, "aarch64", "macAarch64", "arm64"),
    MACOS_AMD64(DesktopPlatform.MACOS, "amd64", "macAmd64", "x64"),
    WINDOWS_AMD64(DesktopPlatform.WINDOWS, "amd64", "windowsAmd64", "x64"),
    LINUX_AMD64(DesktopPlatform.LINUX, "amd64", "linuxAmd64", "x64");

    val key: String get() = "${platform.id}-$arch"
    val taskSuffix: String get() = platform.id.replaceFirstChar { it.uppercase() } +
        arch.replaceFirstChar { it.uppercase() }

    fun composeDependency(version: String): String =
        "org.jetbrains.compose.desktop:desktop-jvm-${platform.id}-$composeArch:$version"

    fun portableArchiveName(version: String): String = when (platform) {
        DesktopPlatform.MACOS -> "TeamTalk-$version-mac-$arch.zip"
        DesktopPlatform.WINDOWS -> "TeamTalk-$version-windows-$arch-portable.zip"
        DesktopPlatform.LINUX -> "TeamTalk-$version-linux-$arch.tar.gz"
    }
}
