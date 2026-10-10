package com.virjar.tk.server.domain.message

import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.ScheduledMessage
import com.virjar.tk.protocol.model.ScheduledMessageDraft
import com.virjar.tk.server.domain.chat.ChatAccess
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * 定时发送（MSG-01/F3）的服务端权威入口。
 *
 * 调度是幂等命令：同一发起者的 [ScheduledMessageDraft.operationId] 重试返回原记录。到点投递由
 * scheduled-message worker 驱动，走与 MESSAGE 帧完全相同的发送管道（准入、提及、落库、事件）；
 * `clientMsgId` 从 [ScheduledMessageRow.scheduledId] 确定性派生，因此投递与结算之间任何时刻的
 * 进程死亡都会在重启后的重扫中被消息幂等索引吸收——重投递返回原消息，绝不产生第二条。
 * 投递失败（权限漂移、附件失效等）置 FAILED 并保留在发起者列表，不重试、不静默丢弃。
 */
class ScheduledMessageService(
    private val repository: ScheduledMessageRepository,
    private val unitOfWork: PgUnitOfWork,
    private val access: ChatAccess,
    private val messageService: MessageService,
) {
    private val logger = LoggerFactory.getLogger("ScheduledMessageService")

    suspend fun schedule(senderUid: String, draft: ScheduledMessageDraft): ScheduledMessage {
        require(draft.chatId.isNotBlank() && draft.chatId.length <= 256) { "chatId 非法" }
        require(draft.operationId.isNotBlank() && draft.operationId.length <= 64) { "operationId 非法" }
        val now = System.currentTimeMillis()
        require(draft.deliverAt > now - CLOCK_SKEW_TOLERANCE_MILLIS) { "投递时间不能早于当前时间" }
        require(draft.deliverAt <= now + MAX_HORIZON_MILLIS) { "投递时间不能超过 30 天" }
        val type = MessageType.fromCode(draft.messageType)
            ?: throw IllegalArgumentException("消息类型非法")
        require(draft.body != null || type == MessageType.RICH_TEXT) { "该消息类型需要正文" }

        // 成员资格在调度时预检；投递时发送管道会再次全量校验（权限可能漂移）。
        access.readAsMember(senderUid, draft.chatId, message = "不是聊天成员") { _, _ -> }

        return unitOfWork.write<ScheduledMessage> {
            val existing = repository.findByOperation(transaction, senderUid, draft.operationId)
            if (existing != null) return@write existing.toProtocol()
            val row = ScheduledMessageRow(
                scheduledId = UUID.randomUUID().toString(),
                chatId = draft.chatId,
                senderUid = senderUid,
                messageType = draft.messageType,
                body = draft.body,
                deliverAt = draft.deliverAt,
                status = STATUS_WAITING,
                operationId = draft.operationId,
                createdAt = now,
                settledAt = null,
            )
            repository.insert(transaction, row)
            row.toProtocol()
        }
    }

    /** 仅发起者可取消；operationId 不匹配按不存在处理。已终态的记录幂等成功。 */
    suspend fun cancel(senderUid: String, scheduledId: String, operationId: String): Boolean =
        unitOfWork.write {
            val row = repository.get(transaction, scheduledId) ?: return@write false
            if (row.senderUid != senderUid || row.operationId != operationId) return@write false
            if (row.status != STATUS_WAITING) return@write true
            repository.settle(transaction, scheduledId, STATUS_WAITING, STATUS_CANCELLED, System.currentTimeMillis())
        }

    suspend fun listWaiting(senderUid: String, chatId: String): List<ScheduledMessage> {
        access.readAsMember(senderUid, chatId) { _, _ -> }
        return unitOfWork.write {
            repository.listWaiting(transaction, senderUid, chatId).map { it.toProtocol() }
        }
    }

    /**
     * 到期扫描投递：每次调用处理一批，返回投递条数（含失败结算）。worker 以固定间隔调用。
     * 每条独立结算：单条失败不影响同批其他调度。
     */
    suspend fun deliverDue(now: Long): Int {
        val due = unitOfWork.write { repository.findDue(transaction, now, DELIVER_BATCH) }
        var processed = 0
        for (row in due) {
            processed++
            try {
                val message = Message(
                    chatId = row.chatId,
                    // 确定性派生的消息幂等身份：投递与结算之间的崩溃恢复天然精确一次。
                    clientMsgId = DELIVERED_CLIENT_MSG_ID_PREFIX + row.scheduledId,
                    senderUid = row.senderUid,
                    messageType = row.messageType,
                    timestamp = maxOf(now, row.deliverAt),
                    body = row.body,
                )
                messageService.sendMessage(row.senderUid, message)
                unitOfWork.write {
                    repository.settle(transaction, row.scheduledId, STATUS_WAITING, STATUS_DELIVERED, now)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.warn(
                    "Scheduled message {} delivery failed: {}",
                    row.scheduledId,
                    failure.message,
                )
                runCatching {
                    unitOfWork.write {
                        repository.settle(transaction, row.scheduledId, STATUS_WAITING, STATUS_FAILED, now)
                    }
                }.onFailure { settleFailure ->
                    logger.error("Scheduled message {} failure settlement itself failed", row.scheduledId, settleFailure)
                }
            }
        }
        return processed
    }

    private fun ScheduledMessageRow.toProtocol() = ScheduledMessage(
        scheduledId = scheduledId,
        chatId = chatId,
        senderUid = senderUid,
        messageType = messageType,
        deliverAt = deliverAt,
        status = status,
        createdAt = createdAt,
        operationId = operationId,
        body = body,
    )

    companion object {
        const val STATUS_WAITING = 0
        const val STATUS_CANCELLED = 2
        const val STATUS_DELIVERED = 1
        const val STATUS_FAILED = 3

        const val DELIVER_BATCH = 50
        const val CLOCK_SKEW_TOLERANCE_MILLIS = 60_000L
        const val MAX_HORIZON_MILLIS = 30L * 24 * 60 * 60 * 1000
        const val DELIVERED_CLIENT_MSG_ID_PREFIX = "scdl-"
    }
}
