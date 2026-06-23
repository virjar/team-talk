package com.virjar.tk.env

import java.io.File

/**
 * Desktop 客户端运行环境。
 *
 * 数据目录策略：
 * - **开发模式**（从 Gradle 运行）：项目根目录/data/desktop/
 * - **生产模式**（安装后运行）：应用安装目录下的 data/
 *
 * 通过 `-Dteamtalk.data.dir` 和 `-Dteamtalk.is.dev` 系统属性区分。
 */
object DesktopEnvironment {

    val isDevelopment: Boolean =
        (System.getProperty("teamtalk.is.dev") ?: "false").toBoolean()

    /** 客户端数据目录 */
    val dataDir: File = resolveDataDir()

    private fun resolveDataDir(): File {
        // 1. 系统属性显式指定（Gradle run 任务或手动指定）
        val customDir = System.getProperty("teamtalk.data.dir")
        if (!customDir.isNullOrBlank()) {
            val dir = File(customDir)
            dir.mkdirs()
            return dir
        }

        // 2. 开发模式 fallback：尝试使用项目根目录/data/desktop
        if (isDevelopment) {
            val projectDataDir = File(System.getProperty("user.dir"), "data/desktop")
            projectDataDir.mkdirs()
            return projectDataDir
        }

        // 3. 生产模式：相对于应用安装位置
        val appDir = File(
            DesktopEnvironment::class.java.protectionDomain.codeSource.location.toURI()
        ).let { jar ->
            // jar 在 bin/ 或 app/ 目录下，数据目录在其父级
            when (jar.parentFile?.name) {
                "bin", "app" -> jar.parentFile.parentFile
                else -> jar.parentFile
            }
        }
        val dataDir = File(appDir, "data")
        dataDir.mkdirs()
        return dataDir
    }
}
