@file:JvmName("BootstrapMain")

package com.virjar.tk.desktop.shell

import java.awt.GraphicsEnvironment
import java.awt.HeadlessException
import java.io.File
import java.net.URLClassLoader
import javax.swing.JOptionPane

/**
 * 桌面壳入口：解析负载版本目录 → 以 URLClassLoader 装载真实应用并调用其 main。
 *
 * 布局与生命周期见 PayloadStore 头注释；本类刻意零依赖（纯 JDK），
 * 使壳包极小且极少更新。负载更新由应用内的 com.virjar.tk.shared.update.DesktopUpdater
 * 完成（下载 → 校验 → 原子切换 current.properties），本类只在下次启动时看到新指针。
 *
 * dev/裸 JVM 直接运行 TeamTalkMain 不经过本类，自更新入口自动隐藏。
 */
fun main(args: Array<String>) {
    // 必须在 AWT 初始化前声明 macOS 菜单栏应用名。
    System.setProperty("apple.awt.application.name", "TeamTalk")

    try {
        val restartParent = args.singleOrNull { it.startsWith("--teamtalk-restart-parent=") }
        restartParent?.substringAfter('=')?.toLongOrNull()?.let { pid ->
            // 新进程必须等旧进程释放账号数据库和实例锁，不能刚拉起就互相抢占。
            ProcessHandle.of(pid).ifPresent { it.onExit().get(30, java.util.concurrent.TimeUnit.SECONDS) }
        }
        launchPayload(args.filterNot { it == restartParent }.toTypedArray())
    } catch (failure: Throwable) {
        fatal("TeamTalk 启动失败", failure)
    }
}

private fun launchPayload(args: Array<String>) {
    val versionsRoot = resolveVersionsRoot()
    versionsRoot.mkdirs()

    val install = installDir()
    val installDirs = listOfNotNull(install, install?.parentFile).flatMap { listOf(it, File(it, "app")) }
    val pointer = PayloadStore.selectPayload(installDirs, versionsRoot)
        ?: error("未找到可用负载，请重新安装 TeamTalk。")
    val currentDir = File(versionsRoot, pointer.directory)
    val descriptor = PayloadStore.readDescriptor(currentDir)
        ?: throw IllegalStateException("负载描述符缺失：${currentDir.absolutePath}")
    if (descriptor.minShellAbi != null && descriptor.minShellAbi > SHELL_ABI) {
        throw IllegalStateException(
            "当前安装壳过旧（shell ABI $SHELL_ABI < ${descriptor.minShellAbi}）。" +
                "请到下载页重新下载安装包完成升级。",
        )
    }
    PayloadStore.verifyLight(currentDir, descriptor)

    // 把壳上下文交给负载（更新器/重启逻辑读取；dev 模式无这些属性即隐藏自更新）。
    System.setProperty("teamtalk.payload.dir", currentDir.absolutePath)
    System.setProperty("teamtalk.payload.version", descriptor.version)
    System.setProperty("teamtalk.payload.build", descriptor.build.toString())
    System.setProperty("teamtalk.shell.abi", SHELL_ABI.toString())
    // teamtalk.shell.launcher 由实际脚本或原生启动器传入；Java 进程路径不是可重启命令。

    val classpath = buildClasspath(currentDir)
    val loader = URLClassLoader(classpath.toTypedArray(), PayloadStore::class.java.classLoader)
    val mainClass = Class.forName(PAYLOAD_MAIN_CLASS, true, loader)
    val main = mainClass.getDeclaredMethod("main", Array<String>::class.java)
    val previousContextLoader = Thread.currentThread().contextClassLoader
    Thread.currentThread().contextClassLoader = loader
    try {
        main.invoke(null, args)
    } finally {
        Thread.currentThread().contextClassLoader = previousContextLoader
    }
}

/**
 * 负载根目录优先级：系统属性（验收/测试）> 环境变量 > ~/.teamtalk-client/<appId>/versions。
 * 规则必须与 client/shared 更新器约定一致（应用数据目录策略与此无关，不共用）。
 */
private fun resolveVersionsRoot(): File {
    System.getProperty("teamtalk.payload.root")?.let { return File(it) }
    System.getenv("TEAMTALK_PAYLOAD_DIR")?.takeIf(String::isNotBlank)?.let { return File(it) }
    val home = File(System.getProperty("user.home") ?: return File("teamtalk-client").apply { mkdirs() })
    return File(File(File(home, ".teamtalk-client"), SHELL_APP_ID), "versions")
}

/** 壳自身所在目录（bootstrap jar 所在处），用于定位种子 payload。 */
private fun installDir(): File? = runCatching {
    File(
        PayloadStore::class.java.protectionDomain.codeSource.location.toURI(),
    ).absoluteFile.parentFile
}.getOrNull()

/**
 * 负载 classpath：版本根目录（资源按相对路径可见）+ 目录内全部 jar。
 * 目录在前，jar 在后；native 库以普通文件按资源路径平铺，加载方按绝对路径或资源流读取。
 */
private fun buildClasspath(versionDir: File): List<java.net.URL> {
    val urls = mutableListOf<java.net.URL>()
    urls += versionDir.toURI().resolve(".").toURL()
    versionDir.walkTopDown()
        .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
        .sortedBy { it.invariantSeparatorsPath }
        .forEach { urls += it.toURI().toURL() }
    return urls
}

private fun fatal(title: String, failure: Throwable) {
    failure.printStackTrace()
    val detail = failure.message ?: failure.javaClass.simpleName
    if (!GraphicsEnvironment.isHeadless()) {
        try {
            JOptionPane.showMessageDialog(
                null,
                "$title\n\n$detail\n\n请把以上信息反馈给部署维护者。",
                title,
                JOptionPane.ERROR_MESSAGE,
            )
        } catch (_: HeadlessException) {
            // 无显示环境（例如服务上下文误启动）：仅打印。
        }
    }
    kotlin.system.exitProcess(1)
}
