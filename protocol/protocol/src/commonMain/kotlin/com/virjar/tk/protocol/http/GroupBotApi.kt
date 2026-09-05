package com.virjar.tk.protocol.http

import kotlinx.serialization.Serializable

/** 可选的 webhook 请求头。重试同一业务事件时复用相同的值。 */
const val BOT_IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"

/** 客户端生成的 256-bit 群机器人凭据的前缀与精确编码长度。 */
const val GROUP_BOT_WEBHOOK_TOKEN_PREFIX = "ttb_"
const val GROUP_BOT_WEBHOOK_TOKEN_LENGTH = 47
const val GROUP_BOT_NAME_MAX_LENGTH = 100

/**
 * 目标绑定的 webhook 请求体。目标群只存在于 URL 路径中。
 *
 * 开发协议只有一种严格的请求形态：JSON `chatId` 永远不会被接受为路由输入，
 * URL 始终是唯一的授权目标。
 */
@Serializable
data class GroupBotMessageRequest(
    val markdown: String,
)

/** 目标绑定 webhook 投递的最小确认。 */
@Serializable
data class GroupBotMessageResponse(
    val ok: Boolean,
)

/** 面向群的安全机器人元数据。webhook 凭据永远不会出现在列表响应中。 */
@Serializable
data class GroupBotSummary(
    val botId: String,
    val name: String,
    val status: Int,
    val lastUsedAt: Long?,
    val createdAt: Long,
    /** 本列表上下文中目标绑定的 `/api/v1/groups/{chatId}/bots/{botId}/messages` 路径。 */
    val apiPath: String,
    val groupManaged: Boolean,
    /** 当前调用方创建了这个群范围机器人。 */
    val createdByMe: Boolean,
    /** 只有创建者可以轮换并收到替代凭据。 */
    val canRotateToken: Boolean,
    /** 创建者、群管理员或群主可以移除群范围机器人。 */
    val canRemove: Boolean,
)

/**
 * 客户端由其持久命令与服务端回执组装出的一次性凭据。
 *
 * 该类型有意不作为 HTTP 响应：服务端永远不会回显或持久化这份可恢复的凭据。
 * [operationId] 会一直保留，直到用户明确确认凭据已复制或有意丢弃。
 */
@Serializable
data class GroupBotCredentials(
    val bot: GroupBotSummary,
    val webhookToken: String,
    val operationId: String,
) {
    override fun toString(): String =
        "GroupBotCredentials(bot=$bot, webhookToken=<redacted>, operationId=$operationId)"
}

/** 完整的幂等创建命令。服务端只存储 [webhookToken] 的 SHA-256。 */
@Serializable
data class CreateGroupBotRequest(
    val operationId: String,
    val name: String,
    val webhookToken: String,
) {
    override fun toString(): String =
        "CreateGroupBotRequest(operationId=$operationId, name=$name, webhookToken=<redacted>)"
}

/** 完整的幂等轮换命令。目标机器人仍然由 URL 路径绑定。 */
@Serializable
data class RotateGroupBotTokenRequest(
    val operationId: String,
    val webhookToken: String,
) {
    override fun toString(): String =
        "RotateGroupBotTokenRequest(operationId=$operationId, webhookToken=<redacted>)"
}

/** 首次执行与精确重放都会返回的无机密持久命令结果。 */
@Serializable
data class GroupBotCommandReceipt(
    val operationId: String,
    val bot: GroupBotSummary,
)
