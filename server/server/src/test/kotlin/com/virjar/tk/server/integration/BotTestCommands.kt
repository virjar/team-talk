package com.virjar.tk.server.integration

import com.virjar.tk.server.domain.bot.BotService
import com.virjar.tk.protocol.http.GROUP_BOT_WEBHOOK_TOKEN_PREFIX
import com.virjar.tk.protocol.http.GroupBotCredentials
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** 测试侧适配器：在不含秘密的回执之外保留客户端生成的秘密。 */
internal suspend fun BotService.createGroupBotForTest(
    actorUid: String,
    chatId: String,
    name: String,
): GroupBotCredentials {
    val operationId = UUID.randomUUID().toString()
    val token = newGroupBotTestToken()
    val receipt = createForGroup(actorUid, chatId, operationId, name, token)
    return GroupBotCredentials(receipt.bot, token, receipt.operationId)
}

internal suspend fun BotService.rotateGroupBotTokenForTest(
    actorUid: String,
    chatId: String,
    botId: String,
): GroupBotCredentials {
    val operationId = UUID.randomUUID().toString()
    val token = newGroupBotTestToken()
    val receipt = rotateTokenForGroup(actorUid, chatId, botId, operationId, token)
    return GroupBotCredentials(receipt.bot, token, receipt.operationId)
}

internal fun newGroupBotTestToken(): String = GROUP_BOT_WEBHOOK_TOKEN_PREFIX +
    Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(TEST_TOKEN_RANDOM::nextBytes))

private val TEST_TOKEN_RANDOM = SecureRandom()
