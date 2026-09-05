package com.virjar.tk.server

import java.nio.file.Files
import kotlin.io.path.createDirectory
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StaticDownloadPolicyTest {
    @Test
    fun `package download route accepts only direct regular files inside its root`() {
        val sandbox = Files.createTempDirectory("teamtalk-static-download-")
        try {
            val downloads = sandbox.resolve("downloads").createDirectory()
            val direct = downloads.resolve("TeamTalk-android.apk").createFile()
            val nested = downloads.resolve("nested").createDirectory().resolve("hidden.bin").createFile()
            val outside = sandbox.resolve("outside.bin").also { it.writeText("outside") }
            val escapingLink = downloads.resolve("escape.bin")
            Files.createSymbolicLink(escapingLink, outside)

            assertEquals(direct.toFile().canonicalFile, resolveDirectDownload(downloads.toFile(), direct.fileName.toString()))
            assertNull(resolveDirectDownload(downloads.toFile(), "../${outside.fileName}"))
            assertNull(resolveDirectDownload(downloads.toFile(), "nested/${nested.fileName}"))
            assertNull(resolveDirectDownload(downloads.toFile(), escapingLink.fileName.toString()))
            assertNull(resolveDirectDownload(downloads.toFile(), "."))
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }
}
