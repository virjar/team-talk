package com.virjar.tk.android

import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 应用内升级闭环的可测内核：版本比较、APK 判定、暂存包管理与流式下载落盘。
 * Context/Intent 边界（安装器拉起、系统开关）不在 JVM 单测范围，走真机验收。
 */
class AndroidAppUpgradeLoopTest {

    /** android.jar 单测类路径没有 com.sun.httpserver；用裸 socket 提供 Content-Length 响应。 */
    private class MinimalHttpServer(private val status: String, private val body: ByteArray) {
        private val server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        private val accepted = AtomicInteger()
        @Volatile private var stopped = false
        private val thread = Thread {
            while (!stopped) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    return@Thread
                }
                accepted.incrementAndGet()
                serve(socket)
            }
        }

        val port: Int get() = server.localPort

        fun start() {
            thread.isDaemon = true
            thread.start()
        }

        private fun serve(socket: Socket) {
            socket.use { connection ->
                val input = connection.getInputStream()
                // 读到请求头结束即可；单请求连接，不解析方法与路径。
                val expected = "\r\n\r\n"
                var matched = 0
                while (matched < expected.length) {
                    val byte = input.read()
                    if (byte == -1) return
                    matched = if (byte == expected[matched].code) matched + 1 else 0
                }
                val output = connection.getOutputStream()
                output.write("HTTP/1.1 $status\r\n".toByteArray())
                output.write("Content-Length: ${body.size}\r\n".toByteArray())
                output.write("Connection: close\r\n\r\n".toByteArray())
                output.write(body)
                output.flush()
            }
        }

        fun stop() {
            stopped = true
            server.close()
        }
    }

    private fun tempDirectory(prefix: String): File =
        File.createTempFile(prefix, "").let { file ->
            file.delete()
            file.mkdirs()
            file
        }

    @Test
    fun `version comparison is segment numeric`() {
        assertTrue(AndroidAppUpgrade.isNewer("0.0.5", "0.0.4"))
        assertTrue(AndroidAppUpgrade.isNewer("0.1.0", "0.0.9"))
        assertTrue(AndroidAppUpgrade.isNewer("v0.0.5", "0.0.4"))
        assertTrue(AndroidAppUpgrade.isNewer("0.0.5-snapshot", "0.0.4"))
        assertFalse(AndroidAppUpgrade.isNewer("0.0.5", "0.0.5"))
        assertFalse(AndroidAppUpgrade.isNewer("0.0.4", "0.0.5"))
        assertFalse(AndroidAppUpgrade.isNewer("", "0.0.5"))
    }

    @Test
    fun `apk detection accepts extension or mime regardless of case`() {
        assertTrue(looksLikeApk("TeamTalk-0.0.5-android.apk", "application/octet-stream"))
        assertTrue(looksLikeApk("TEAMTALK.APK", "text/plain"))
        assertTrue(looksLikeApk("installer", "application/vnd.android.package-archive"))
        assertTrue(looksLikeApk("installer", "APPLICATION/VND.ANDROID.PACKAGE-ARCHIVE"))
        assertFalse(looksLikeApk("report.pdf", "application/pdf"))
        assertFalse(looksLikeApk("apk-not-apk.txt", "text/plain"))
    }

    @Test
    fun `staged package directory stays a dedicated cache prefix and clears completely`() {
        val cacheRoot = tempDirectory("tt-upgrade-root")
        try {
            val directory = AndroidAppUpgrade.upgradePackageDirectory(cacheRoot)
            assertEquals(File(cacheRoot, "teamtalk-upgrade"), directory)
            directory.mkdirs()
            val full = File(directory, "teamtalk-0.0.6-android.apk").apply { writeBytes(ByteArray(16)) }
            val part = File(directory, "teamtalk-0.0.6-android.apk.part").apply { writeBytes(ByteArray(4)) }

            AndroidAppUpgrade.clearStagedUpgradePackages(cacheRoot)
            assertFalse(full.exists())
            assertFalse(part.exists())
            assertTrue(directory.exists())
        } finally {
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun `download streams to the staged name with progress and removes the part file`() = runBlocking {
        val root = tempDirectory("tt-upgrade-dl")
        val body = ByteArray(300 * 1024) { index -> (index % 251).toByte() }
        val server = MinimalHttpServer("200 OK", body)
        server.start()
        try {
            var lastProgress = -1f
            val reportCount = AtomicInteger()
            val target = AndroidAppUpgrade.downloadToDirectory(
                directory = root,
                url = "http://127.0.0.1:${server.port}/pkg",
                fileName = "TeamTalk-0.0.6-android.apk",
            ) { progress ->
                reportCount.incrementAndGet()
                assertTrue(progress in 0f..1f)
                lastProgress = progress
            }
            assertEquals("TeamTalk-0.0.6-android.apk", target.name)
            assertContentEquals(body, target.readBytes())
            assertTrue(reportCount.get() > 0)
            assertEquals(1f, lastProgress)
            assertFalse(File(root, "TeamTalk-0.0.6-android.apk.part").exists())
        } finally {
            server.stop()
            root.deleteRecursively()
        }
    }

    @Test
    fun `failed download surfaces the error and leaves no partial file`() = runBlocking {
        val root = tempDirectory("tt-upgrade-fail")
        val server = MinimalHttpServer("500 Internal Server Error", ByteArray(0))
        server.start()
        try {
            assertFailsWith<IllegalStateException> {
                AndroidAppUpgrade.downloadToDirectory(
                    directory = root,
                    url = "http://127.0.0.1:${server.port}/pkg",
                    fileName = "TeamTalk-0.0.6-android.apk",
                ) { }
            }
            assertFalse(File(root, "TeamTalk-0.0.6-android.apk").exists())
            assertFalse(File(root, "TeamTalk-0.0.6-android.apk.part").exists())
        } finally {
            server.stop()
            root.deleteRecursively()
        }
    }
}
