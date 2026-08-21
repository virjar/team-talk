package com.virjar.tk.e2e

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CLI 路径 e2e（doc/05-clients/headless.md）：A（内嵌）↔ B（常驻 agent CLI）。
 * 前置：本机/CI 起 tt-agent（-Dcli.api/-Dcli.token 指向它）。
 */
class CliPeerE2eTest {

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    fun `cli 对等账号收发`() {
        TcpE2eEnvironment().use { env ->
            // 测试自起 agent（连 embedded server），端口随机避免冲突；安全的专用 dataDir
            val agentPort = 30000 + (System.currentTimeMillis() % 20000).toInt()
            val dataParent = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize()
                .resolve("build")
                .resolve("agent-e2e-data")
            Files.createDirectories(dataParent)
            check(!Files.isSymbolicLink(dataParent)) { "agent e2e data parent cannot be a symlink" }
            Files.setPosixFilePermissions(dataParent, PosixFilePermissions.fromString("rwx------"))
            val dataDir = dataParent.resolve("tt-cli-peer-${UUID.randomUUID()}").toFile()
            check(!Files.exists(dataDir.toPath(), LinkOption.NOFOLLOW_LINKS))
            val agentHome = File(System.getProperty("cli.agentHome") ?: "../shared/build/headless")
            val agent = ProcessBuilder(
                "${agentHome}/bin/tt-agent",
                "--host", "127.0.0.1", "--port", env.tcpPort.toString(),
                "--register", "--prefix", "clipeer-b",
                "--api", "127.0.0.1:$agentPort", "--data-dir", dataDir.absolutePath,
            ).redirectErrorStream(true).start()
            try {
                runBlocking {
                // 等 agent ready；API token 只从测试专用的私有凭据文件读取，不允许日志输出秘密。
                var ready = false
                val deadline = System.currentTimeMillis() + 20_000
                val output = agent.inputStream.bufferedReader()
                val startupOutput = mutableListOf<String>()
                while (System.currentTimeMillis() < deadline) {
                    if (output.ready()) {
                        val line = output.readLine() ?: break
                        startupOutput += line
                        if (line.startsWith("[tt-agent] ready ")) {
                            ready = true
                            break
                        }
                    } else {
                        if (!agent.isAlive) break
                        kotlinx.coroutines.delay(50)
                    }
                }
                if (!ready && !agent.isAlive) {
                    generateSequence(output::readLine).take(50).forEach(startupOutput::add)
                }
                check(ready) {
                    "agent 未在 20 秒内就绪（alive=${agent.isAlive}, " +
                        "exit=${agent.takeIf { !it.isAlive }?.exitValue()}, " +
                        "output=${startupOutput.takeLast(20).joinToString(" | ")}）"
                }
                val credentials = Properties().also { properties ->
                    Files.newInputStream(dataDir.toPath().resolve("credentials.properties")).use(properties::load)
                }
                val token = credentials.getProperty("apiToken").orEmpty()
                check(token.isNotBlank()) { "agent private credentials did not contain an API token" }
                val cli = CliPeer(api = "127.0.0.1:$agentPort", token = token)
                val status = cli.status()
                check(status["connected"] == "true") { "agent 未连接: $status" }
                val bUid = status["uid"]!!

                // A 注册并私聊 B（agent 账号）
                val a = com.virjar.tk.bot.ImBot.register(
                    "127.0.0.1", env.tcpPort, "clipeer-a", testImBotCacheOwner,
                )
                try {
                    val chatId = a.createPersonalChat(bUid)

                    // A 发 → B（agent）经 CLI recv 收到
                    a.sendText(chatId, "hello from embedded")
                    val received = assertNotNull(cli.recvFrom(a.uid, timeoutSec = 15), "agent 应收到消息")
                    assertEquals("hello from embedded", received["text"])
                    assertEquals(chatId, received["chatId"])

                    // B（agent CLI）发 → A 收到（seq 递增验证真实送达）
                    val (code, seq) = cli.sendText(chatId, "reply from cli")
                    assertEquals(0, code)
                    assertTrue(seq > 0, "CLI 发送应拿到服务端 seq")
                    val aReceived = a.nextMessage { it.senderUid == bUid }
                    assertEquals("reply from cli", (aReceived.body as com.virjar.tk.body.RichTextBody).markdown)
                } finally {
                    a.shutdown()
                }
                }
            } finally {
                agent.destroyForcibly()
                agent.waitFor(5, TimeUnit.SECONDS)
                dataDir.deleteRecursively()
            }
        }
    }

    private operator fun File.plus(s: String) = File(this, s)
}
