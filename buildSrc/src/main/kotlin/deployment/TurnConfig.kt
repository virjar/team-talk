package deployment

import java.io.File

/**
 * 内嵌 TURN/STUN（协议 minor 0.5 通话）的部署配置。
 *
 * secret 文件是部署状态目录里的凭据材料（Git 忽略）；其内容只在部署时写入远端
 * conf/env.sh（权限 600），绝不进入 DeploymentConfig 快照 JSON 或任何构建产物。
 * 未配置 secretFile 时 TURN 不启用，通话仅 P2P 直连（同一机房/内网仍可用）。
 */
data class TurnDeployment(
    /** STUN/TURN 共用 UDP 监听端口。 */
    val port: Int = 3478,
    /** 对外宣告的主机名或公网 IP；留空使用最终 HTTP URL 的主机。 */
    val publicHost: String = "",
    /** relay 端口范围（含端点）。 */
    val relayPortStart: Int = 51000,
    val relayPortEnd: Int = 51100,
    /** STUN realm。 */
    val realm: String = "teamtalk",
    /** TURN time-limited 凭据共享 secret（≥16 字节）；null = 不启用。 */
    val secretFile: File? = null,
) {
    val enabled: Boolean get() = secretFile != null

    init {
        require(port in 1..65535) { "server.turn.port must be in 1..65535" }
        require(relayPortStart in 1..65535 && relayPortEnd >= relayPortStart && relayPortEnd - relayPortStart <= 1024) {
            "server.turn relay port range is invalid (max 1024 ports)"
        }
        require(realm.isNotBlank()) { "server.turn.realm must not be blank" }
    }
}
