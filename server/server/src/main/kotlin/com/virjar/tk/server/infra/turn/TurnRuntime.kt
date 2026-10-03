package com.virjar.tk.server.infra.turn

import com.virjar.tk.server.protocol.executor.guardedNioEventLoopGroup
import java.util.concurrent.TimeUnit

/**
 * 内嵌 TURN 的运行时入口：从部署环境变量解析配置。
 *
 * env 由部署流程写入 conf/env.sh（权限 600）；未设置 TURN_ENABLED 时不启用，
 * 通话仅 P2P 直连。secret 只在进程内使用，不进入日志。
 */
object TurnRuntime {

    data class Options(
        val config: TurnServerConfig,
        val eventLoopThreads: Int = 1,
    )

    fun fromEnvironment(env: Map<String, String> = System.getenv()): Options? {
        if (env["TURN_ENABLED"] != "true") return null
        val secret = requireNotNull(env["TURN_SECRET"]) { "TURN_ENABLED=true requires TURN_SECRET" }
        val config = TurnServerConfig(
            listenPort = env["TURN_PORT"]?.toIntOrNull() ?: 3478,
            publicHost = requireNotNull(env["TURN_PUBLIC_HOST"].takeIf { !it.isNullOrBlank() }) {
                "TURN_ENABLED=true requires TURN_PUBLIC_HOST"
            },
            relayPortStart = env["TURN_RELAY_PORT_START"]?.toIntOrNull() ?: 51000,
            relayPortEnd = env["TURN_RELAY_PORT_END"]?.toIntOrNull() ?: 51100,
            secret = secret.toByteArray(Charsets.UTF_8),
            realm = env["TURN_REALM"].takeUnless { it.isNullOrBlank() } ?: "teamtalk",
        )
        return Options(config)
    }

    /** 创建并启动 TURN 服务；返回值由调用方纳入进程资源清理。 */
    fun start(options: Options): TurnServer {
        val group = guardedNioEventLoopGroup(options.eventLoopThreads, "teamtalk-turn")
        var server: TurnServer? = null
        try {
            val opened = TurnServer(options.config, group, ownsEventLoopGroup = true)
            server = opened
            opened.start()
            return opened
        } catch (failure: Throwable) {
            try {
                if (server != null) {
                    server.close()
                } else {
                    group.shutdownGracefully(0, STARTUP_CLEANUP_TIMEOUT_SECONDS, TimeUnit.SECONDS).syncUninterruptibly()
                }
            } catch (closeFailure: Throwable) {
                if (closeFailure !== failure) failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }

    private const val STARTUP_CLEANUP_TIMEOUT_SECONDS = 5L
}
