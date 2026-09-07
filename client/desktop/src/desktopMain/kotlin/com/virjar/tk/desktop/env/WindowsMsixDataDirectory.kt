package com.virjar.tk.desktop.env

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.platform.win32.KnownFolders
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.ShlObj
import com.sun.jna.platform.win32.W32Errors
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * MSIX 现在和 ZIP/MSI 共用真实 LocalAppData，不再让 Windows 重定向应用数据。
 * 旧包可能在 LocalCache 留下覆盖层；先发现它，避免把空的真实目录当成首次登录。
 * 这里只读检查当前包自己的固定位置，不合并两棵可能包含不同 SQLite/WAL 的目录。
 */
internal object WindowsMsixDataDirectory {
    fun checkBeforeOpening(
        plan: DesktopDataDirectoryPlan,
        platform: DesktopHostPlatform = desktopHostPlatform(System.getProperty("os.name").orEmpty()),
        packageFamilyName: () -> String? = ::currentPackageFamilyName,
        realLocalAppData: () -> Path = ::currentUnvirtualizedLocalAppData,
    ) {
        if (plan.isExplicitOverride || platform != DesktopHostPlatform.WINDOWS) return
        val familyName = packageFamilyName() ?: return
        val localAppData = realLocalAppData().toAbsolutePath().normalize()
        requireNoLegacyData(
            localAppData, plan.dataDirectory.name, familyName, plan.dataDirectory.toPath(),
        )
        check(plan.baseDirectory.toPath().toAbsolutePath().normalize() == localAppData) {
            "MSIX 默认数据目录与 Windows 真实 LocalAppData 不一致，已停止启动。\n" +
                "当前目录：${plan.dataDirectory}\n" +
                "系统目录：${localAppData.resolve(plan.dataDirectory.name)}\n" +
                "请核对 LOCALAPPDATA 环境与安装包配置；不会自动切换目录或迁移资料。MSIX 包：$familyName"
        }
    }

    internal fun requireNoLegacyData(
        localAppData: Path,
        dataDirectoryName: String,
        packageFamilyName: String?,
        currentDataDirectory: Path = localAppData.resolve(dataDirectoryName),
    ) {
        // 普通进程没有 MSIX 包身份；不扫描其他发行的 Packages 目录。
        if (packageFamilyName == null) return
        require(packageFamilyName.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid MSIX package family name" }
        val legacy = localAppData.resolve("Packages").resolve(packageFamilyName)
            .resolve("LocalCache").resolve("Local").resolve(dataDirectoryName)
        val attributes = try {
            Files.readAttributes(legacy, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return
        }
        val hasData = !attributes.isDirectory || attributes.isSymbolicLink || attributes.isOther ||
            Files.newDirectoryStream(legacy).use { it.iterator().hasNext() }
        check(!hasData) {
            "发现旧 MSIX 虚拟数据，已停止启动以保留登录、消息和草稿。\n" +
                "旧目录：$legacy\n" +
                "当前目录：$currentDataDirectory\n" +
                "请勿卸载旧应用或删除目录。退出应用并备份两处目录后，由管理员确认迁移；" +
                "两处都有资料时不能直接覆盖合并。MSIX 包：$packageFamilyName"
        }
    }

    /** 不使用可能已被包环境重定向的 LOCALAPPDATA，也不创建缺失的系统目录。 */
    internal fun currentUnvirtualizedLocalAppData(): Path {
        val output = PointerByReference()
        val result = Shell32.INSTANCE.SHGetKnownFolderPath(
            KnownFolders.FOLDERID_LocalAppData,
            // JNA 保留旧枚举名；Windows 1703 起同值正式名为 KF_FLAG_NO_PACKAGE_REDIRECTION。
            ShlObj.KNOWN_FOLDER_FLAG.NO_APPCONTAINER_REDIRECTION.flag,
            null,
            output,
        )
        val pointer = output.value
        return try {
            check(W32Errors.SUCCEEDED(result.toInt())) {
                "无法读取真实 LocalAppData：SHGetKnownFolderPath=$result"
            }
            val path = Path.of(checkNotNull(pointer) { "真实 LocalAppData 路径为空" }.getWideString(0))
            check(path.isAbsolute) { "真实 LocalAppData 必须为绝对路径" }
            path.normalize()
        } finally {
            if (pointer != null) Ole32.INSTANCE.CoTaskMemFree(pointer)
        }
    }

    /** JNA 5.18 的 Kernel32 接口尚未声明此 API，按 Win32 原型直接绑定，避免反射方法名混淆。 */
    internal fun currentPackageFamilyName(): String? {
        val query = NativeLibrary.getInstance("kernel32")
            .getFunction("GetCurrentPackageFamilyName", Function.ALT_CONVENTION)
        val length = IntByReference()
        val firstResult = query.invokeInt(arrayOf(length, null))
        if (firstResult == APPMODEL_ERROR_NO_PACKAGE) return null
        check(firstResult == ERROR_INSUFFICIENT_BUFFER && length.value in 1..256) {
            "无法读取 MSIX 包身份：GetCurrentPackageFamilyName=$firstResult"
        }
        // Win32 PWSTR 是 UTF-16；长度包含结尾的 NUL。
        return Memory(length.value.toLong() * 2).use { buffer ->
            val result = query.invokeInt(arrayOf(length, buffer))
            check(result == 0) { "无法读取 MSIX 包身份：GetCurrentPackageFamilyName=$result" }
            buffer.getWideString(0)
        }
    }

    private const val APPMODEL_ERROR_NO_PACKAGE = 15700
    private const val ERROR_INSUFFICIENT_BUFFER = 122
}
