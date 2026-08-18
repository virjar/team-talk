package com.virjar.tk.bot

import com.virjar.tk.body.markdownContentOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch

/**
 * 无头 IM CLI 入口（保留的最小常驻管道：stdin/stdout 行协议，供最简场景）。
 * **产品级 AI 员工基础设施已演进为 tt-agent/tt-cli/tt-mcp**（doc/11-cli-agent）——
 * 守护进程 + REST + systemd + MCP。新场景请用 agent 体系；本入口保留兼容。
 *
 * 启动：
 * ```bash
 * ./gradlew :shared:installDist
 * shared/build/install/shared/bin/shared --host im.virjar.com --port 5100 --prefix my-bot
 * # 或环境变量：TK_HOST/TK_PORT/TK_USER/TK_PASS（login 模式）/ TK_PREFIX（register 模式）
 * ```
 *
 * 模式：
 * - 默认（register）：随机后缀注册新账号，stdout 打印 uid
 * - `--user u --pass p`：已有账号登录
 * - `--selftest`：内嵌双 bot 收发闭环自检后退出（服务器上一键验证环境）
 *
 * 消息协议（行式，便于管道/程序消费）：
 * - 收到消息 → stdout: `MSG<TAB>chatId<TAB>senderUid<TAB>seq<TAB>text`
 * - stdin: `chatId<TAB>text` → 发送到该会话；`quit` 退出
 */
fun main(args: Array<String>) {
    // 支持 --k v 与 --k=v 两种形态；裸 flag 映射为空串
    val opts = buildMap {
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (!a.startsWith("--")) { i++; continue }
            if ('=' in a) { put(a.substringBefore("="), a.substringAfter("=")); i++ }
            else if (i + 1 < args.size && !args[i + 1].startsWith("--")) { put(a, args[i + 1]); i += 2 }
            else { put(a, ""); i++ }
        }
    }
    val env = System.getenv()
    val host = opts["--host"] ?: env["TK_HOST"] ?: "im.virjar.com"
    val port = (opts["--port"] ?: env["TK_PORT"] ?: "5100").toInt()
    val user = opts["--user"] ?: env["TK_USER"]
    val pass = opts["--pass"] ?: env["TK_PASS"]

    when {
        opts.containsKey("--selftest") -> selftest(host, port)
        user != null && pass != null -> serve(host, port) { ImBot.login(host, port, user, pass) }
        else -> {
            val prefix = opts["--prefix"] ?: env["TK_PREFIX"] ?: "headless"
            serve(host, port) { ImBot.register(host, port, prefix) }
        }
    }
}

private fun serve(host: String, port: Int, connect: suspend () -> ImBot) {
    val done = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(Thread { done.countDown() })
    runBlocking {
        val bot = try {
            connect()
        } catch (e: Exception) {
            System.err.println("[headless] connect/auth failed: ${e.message}")
            return@runBlocking
        }
        println("[headless] ready uid=${bot.uid} username=${bot.userSession.username} host=$host:$port")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // 收 → stdout 行协议
        scope.launch {
            bot.messages.collect { m ->
                val text = m.body.markdownContentOrNull() ?: m.body?.let { it::class.simpleName } ?: ""
                println("MSG\t${m.chatId}\t${m.senderUid}\t${m.serverSeq}\t$text")
            }
        }
        // stdin → 发送（chatId<TAB>text；管道关闭或 quit 退出）
        scope.launch {
            while (true) {
                val line = readLine() ?: break
                if (line.isBlank()) continue
                if (line.trim() == "quit") break
                val tab = line.indexOf('\t')
                if (tab <= 0) { System.err.println("[headless] usage: chatId<TAB>text"); continue }
                val ack = bot.sendText(line.substring(0, tab), line.substring(tab + 1))
                if (ack.code != 0) System.err.println("[headless] send failed: ${ack.reason}")
            }
            done.countDown()
        }
        done.await()
        bot.shutdown()
        scope.cancel()
    }
}

/** 内嵌双 bot 收发闭环自检（服务器环境一键验证）。 */
private fun selftest(host: String, port: Int) = runBlocking {
    val a = ImBot.register(host, port, "selftest-a")
    val b = ImBot.register(host, port, "selftest-b")
    try {
        val chatId = a.createPersonalChat(b.uid)
        val text = "selftest-${System.currentTimeMillis()}"
        val ack = a.sendText(chatId, text)
        check(ack.code == 0) { "send failed: ${ack.reason}" }
        val received = b.nextMessage { it.senderUid == a.uid }
        val receivedText = received.body.markdownContentOrNull()
        check(receivedText == text) { "received mismatch: ${received.body}" }
        println("[selftest] PASS  a=${a.uid} b=${b.uid} chat=$chatId")
    } finally {
        a.shutdown(); b.shutdown()
    }
}
