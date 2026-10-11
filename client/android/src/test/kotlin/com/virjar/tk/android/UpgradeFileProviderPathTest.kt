package com.virjar.tk.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 回归护栏（0.0.7 私有分发闪退）：FileProvider 路径声明必须覆盖升级包目录。 */
class UpgradeFileProviderPathTest {
    @Test
    fun `file provider paths declare the upgrade package cache directory`() {
        val xml = File("src/main/res/xml/file_paths.xml").readText()
        // 升级包目录常量（AndroidAppUpgrade.UPGRADE_DIRECTORY）与声明保持一致
        assertTrue("teamtalk-upgrade/" in xml, "file_paths.xml 缺少 teamtalk-upgrade/ 的 cache-path 声明")
        assertEquals(AndroidAppUpgrade.UPGRADE_DIRECTORY, "teamtalk-upgrade")
    }
}
