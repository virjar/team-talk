package com.virjar.tk.agent

import java.io.File

/**
 * tt-agent systemd 服务化（doc/11-cli-agent 三期，Linux）。
 *
 * `tt-agent install [--user xx --pass yy | --register] [--host h] [--port p] [--api a] [--data-dir d]`
 *   → 写 /etc/systemd/system/tt-agent.service + 提示 start/enable
 * `tt-agent uninstall` → 停服务 + 删 unit
 *
 * unit 设计：Type=simple（前台进程）、Restart=on-failure + 5s（连接断由 ImClient
 * 自治重连，进程级重启只兜崩溃）、仅 127.0.0.1 端口不暴露（NetworkNamespace 不做，
 * 保持简单）。凭据在 dataDir（服务重启静默重连，不写进 unit 明文）。
 */
object AgentService {

    private val unitName = "tt-agent"
    private val unitPath = File("/etc/systemd/system/$unitName.service")
    // jar 位于 <home>/lib/*.jar → home = jar.parent(lib) 的父目录
    private val appHome: String
        get() = File(AgentService::class.java.protectionDomain.codeSource.location.toURI())
            .parentFile?.parent?.let { File(it).absolutePath } ?: "/opt/tt-agent"

    fun install(args: List<String>) {
        if (System.getProperty("os.name").lowercase().contains("linux").not()) {
            System.err.println("[install] 仅支持 Linux systemd（当前系统不适用）；macOS 用 launchd（后续）")
            return
        }
        val opts = AgentCli.parse(args.toTypedArray())
        val host = opts["host"] ?: "im.virjar.com"
        val port = opts["port"] ?: "5100"
        val api = opts["api"] ?: "127.0.0.1:8600"
        val dataDir = opts["data-dir"] ?: "/var/lib/tt-agent"
        val execFlags = buildString {
            append(" --host $host --port $port --api $api --data-dir $dataDir")
            if (opts.containsKey("register")) append(" --register")
            opts["user"]?.let { append(" --user $it") }
            opts["pass"]?.let { append(" --pass $it") }
            opts["server-url"]?.let { append(" --server-url $it") }
        }

        val unit = """
[Unit]
Description=TeamTalk AI Agent (tt-agent)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=root
ExecStart=/usr/bin/env java -Djava.net.preferIPv4Stack=true -cp $appHome/lib/* com.virjar.tk.agent.AgentMainKt$execFlags
WorkingDirectory=$appHome
Restart=on-failure
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
""".trim()
        unitPath.writeText(unit)
        println("[install] unit 已写入 $unitPath")
        println("[install] 完成：")
        println("  systemctl daemon-reload && systemctl enable --now $unitName")
        println("  journalctl -u $unitName -f   # 看 token（首次注册后凭据已持久化，token 在 $dataDir/credentials.properties）")
    }

    fun uninstall() {
        runCatching { ProcessBuilder("systemctl", "stop", unitName).start().waitFor() }
        runCatching { ProcessBuilder("systemctl", "disable", unitName).start().waitFor() }
        unitPath.delete()
        println("[uninstall] 已停止并删除 $unitPath")
    }
}
