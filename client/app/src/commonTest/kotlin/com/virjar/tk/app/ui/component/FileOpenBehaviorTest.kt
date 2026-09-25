package com.virjar.tk.app.ui.component

import com.virjar.tk.protocol.model.Attachment
import kotlin.test.Test
import kotlin.test.assertEquals

/** 文件点按策略：安装包/压缩包/可执行文件只保存，其余交给系统；Android 的 apk 例外（打开即安装）。 */
class FileOpenBehaviorTest {
    private fun attachment(name: String) = Attachment(
        path = "files/$name",
        name = name,
        contentType = "application/octet-stream",
        size = 8,
    )

    @Test
    fun `installer archive and executable files save only by default`() {
        listOf("app.apk", "pkg.ipa", "setup.exe", "install.msi", "backup.zip", "docs.rar", "photos.7z", "data.tar.gz")
            .forEach { name ->
                assertEquals(FileOpenBehavior.SAVE_ONLY, defaultFileOpenBehavior(attachment(name)), name)
            }
    }

    @Test
    fun `regular documents open with system by default`() {
        listOf("report.pdf", "规格.docx", "table.xlsx", "notes.md", "logo.png").forEach { name ->
            assertEquals(FileOpenBehavior.OPEN_WITH_SYSTEM, defaultFileOpenBehavior(attachment(name)), name)
        }
    }

    @Test
    fun `extension match is case insensitive and ignores dots in the stem`() {
        assertEquals(FileOpenBehavior.SAVE_ONLY, defaultFileOpenBehavior(attachment("Release.Final.ZIP")))
        assertEquals(FileOpenBehavior.OPEN_WITH_SYSTEM, defaultFileOpenBehavior(attachment("my.folder/notes.txt")))
    }
}
