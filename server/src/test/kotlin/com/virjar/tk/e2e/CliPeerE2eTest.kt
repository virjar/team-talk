package com.virjar.tk.e2e

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
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
            // 测试自起 agent（连 embedded server），端口随机避免冲突；临时 dataDir
            val agentPort = 30000 + (System.currentTimeMillis() % 20000).toInt()
            val dataDir = java.nio.file.Files.createTempDirectory("tt-cli-peer").toFile()
            val agentHome = File(System.getProperty("cli.agentHome") ?: "../shared/build/headless")
            val agent = ProcessBuilder(
                "${agentHome}/bin/tt-agent",
                "--host", "127.0.0.1", "--port", env.tcpPort.toString(),
                "--register", "--prefix", "clipeer-b",
                "--api", "127.0.0.1:$agentPort", "--data-dir", dataDir.absolutePath,
            ).redirectErrorStream(true).start()
            try {
                runBlocking {
                // 等 agent ready（stdout 出 token）
                var token = ""
                val deadline = System.currentTimeMillis() + 20_000
                val output = agent.inputStream.bufferedReader()
                while (System.currentTimeMillis() < deadline) {
                    if (output.ready()) {
                        val line = output.readLine() ?: break
                        if ("token=" in line) {
                            token = line.substringAfter("token=").trim()
                            break
                        }
                    } else {
                        if (!agent.isAlive) break
                        kotlinx.coroutines.delay(50)
                    }
                }
                check(token.isNotBlank()) {
                    "agent 未在 20 秒内就绪（alive=${agent.isAlive}, exit=${agent.takeIf { !it.isAlive }?.exitValue()})"
                }
                val cli = CliPeer(api = "127.0.0.1:$agentPort", token = token)
                val status = cli.status()
                check(status["connected"] == "true") { "agent 未连接: $status" }
                val bUid = status["uid"]!!

                // A 注册并私聊 B（agent 账号）
                val a = com.virjar.tk.bot.ImBot.register("127.0.0.1", env.tcpPort, "clipeer-a")
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
