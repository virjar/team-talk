package com.virjar.tk.desktop

import com.virjar.tk.desktop.env.DesktopDataDirectoryAdmission
import com.virjar.tk.desktop.env.DesktopDataDirectoryInputs
import com.virjar.tk.desktop.env.DesktopDataDirectoryPolicy
import com.virjar.tk.shared.client.JvmFileSystemIdentity
import com.virjar.tk.shared.client.JvmPrivateDataDirectory
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 必须在 Windows 执行：覆盖真实 SID 查找、默认 profile 父链、marker 与文本状态的二次读取。 */
class WindowsDesktopStartupTest {
    @Test
    fun `Windows startup and restart preserve the current users private text state`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val home = File(System.getProperty("user.home"))
        val plan = DesktopDataDirectoryPolicy.resolve(DesktopDataDirectoryInputs(
            osName = System.getProperty("os.name"), userHome = home, environment = System.getenv(),
            explicitDataDirectory = null, dataDirectoryName = "TeamTalk-启动验收-${UUID.randomUUID()}",
        ))
        val root = plan.dataDirectory
        assertTrue(!root.exists())
        try {
            DesktopDataDirectoryAdmission.prepare(plan)
            val owner = JvmFileSystemIdentity.currentOwner(home.toPath())
            assertEquals(owner, Files.getOwner(root.toPath(), LinkOption.NOFOLLOW_LINKS))
            assertTrue(JvmFileSystemIdentity.trustedParentOwners(root.toPath(), owner).size >= 3)
            val storage = JvmPrivateDataDirectory.openExisting(root, home)
            storage.atomicTextFile(fileName = "saved-state").replaceText("中文账号与草稿 remain intact")
            DesktopDataDirectoryAdmission.prepare(plan)
            assertEquals("中文账号与草稿 remain intact", JvmPrivateDataDirectory.openExisting(root, home)
                .atomicTextFile(fileName = "saved-state").readText())
            storage.atomicTextFile(fileName = "saved-state").replaceText("replacement remains readable")
            assertEquals("replacement remains readable", storage.atomicTextFile(fileName = "saved-state").readText())
        } finally {
            // 唯一随机目录只属于本测试；不改真实 TeamTalk 安装或 profile 目录权限。
            root.deleteRecursively()
        }
    }
}
