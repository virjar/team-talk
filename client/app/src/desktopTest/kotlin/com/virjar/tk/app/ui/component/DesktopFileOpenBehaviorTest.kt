package com.virjar.tk.app.ui.component

import com.virjar.tk.protocol.model.Attachment
import kotlin.test.Test
import kotlin.test.assertEquals

/** 桌面 actual：安装包/压缩包/可执行文件一律 SAVE_ONLY，文本预览之外的常规文件交给系统。 */
class DesktopFileOpenBehaviorTest {
    private fun attachment(name: String) = Attachment(
        path = "files/$name",
        name = name,
        contentType = "application/octet-stream",
        size = 8,
    )

    @Test
    fun `apk zip and exe never open on desktop`() {
        listOf("app.apk", "backup.zip", "setup.exe").forEach { name ->
            assertEquals(FileOpenBehavior.SAVE_ONLY, platformFileOpenBehavior(attachment(name)), name)
        }
    }

    @Test
    fun `regular documents still open with system`() {
        assertEquals(
            FileOpenBehavior.OPEN_WITH_SYSTEM,
            platformFileOpenBehavior(attachment("report.pdf")),
        )
    }
}
