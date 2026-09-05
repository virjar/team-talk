package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.command.ReliableCommandCapacityException
import com.virjar.tk.server.domain.command.ReliableCommandConflictException
import com.virjar.tk.server.domain.command.ReliableCommandPolicy
import com.virjar.tk.server.domain.contact.ContactDecisionCommand
import com.virjar.tk.server.domain.contact.ContactPolicy
import com.virjar.tk.server.infra.db.ContactDecisionReceipts
import com.virjar.tk.protocol.model.ContactApply
import com.virjar.tk.protocol.ProtoCodec
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less

enum class ContactRepositoryStage { BEFORE_DECISION_RESERVATION, AFTER_DECISION_RESERVATION }

/** 围绕持久命令/变更交错边界的确定性测试缝隙。 */
fun interface ContactRepositoryHooks {
    fun hit(stage: ContactRepositoryStage, operationId: String)

    object None : ContactRepositoryHooks {
        override fun hit(stage: ContactRepositoryStage, operationId: String) = Unit
    }
}

/**
 * 持久化联系人决策命令的有限重放窗口。
 *
 * 每个方法都刻意加入调用方当前的 Exposed 事务。因此关系
 * 变更与其回执一起提交或回滚；此适配器从不开启
 * 自己的嵌套事务。
 */
internal class ExposedContactDecisionReceiptStore(
    private val hooks: ContactRepositoryHooks,
) {
    /**
     * 在触及由 token 拥有的关系之前，原子地预留一个操作身份。
     * PostgreSQL 的唯一插入会等待在途获胜者，因此并发的精确重试看到的
     * 要么是完整回执，要么是原始事务回滚；不需要进程本地锁，
     * 且不匹配的载荷永远无法窃取该身份。
     */
    fun reserveOrReplay(command: ContactDecisionCommand): ContactApply? {
        hooks.hit(ContactRepositoryStage.BEFORE_DECISION_RESERVATION, command.operationId)
        val inserted = ContactDecisionReceipts.insertIgnore {
            it[actorUid] = command.receiverUid
            it[operationId] = command.operationId
            it[requestFingerprint] = command.requestFingerprint
            it[decision] = command.decision
            it[resultPayload] = null
            it[issuedAt] = command.issuedAt
            it[expiresAt] = ReliableCommandPolicy.expiresAt(command.issuedAt)
            it[createdAt] = System.currentTimeMillis()
        }.insertedCount == 1
        if (inserted) {
            hooks.hit(ContactRepositoryStage.AFTER_DECISION_RESERVATION, command.operationId)
            return null
        }

        val receipt = ContactDecisionReceipts.selectAll().where {
            (ContactDecisionReceipts.actorUid eq command.receiverUid) and
                (ContactDecisionReceipts.operationId eq command.operationId)
        }.forUpdate().singleOrNull()
        if (receipt == null) {
            ReliableCommandPolicy.requireActiveIssuedAt(
                command.issuedAt,
                System.currentTimeMillis(),
                "好友申请处理",
            )
            error("Contact-decision operation uniqueness conflict has no receipt")
        }
        if (
            receipt[ContactDecisionReceipts.requestFingerprint] != command.requestFingerprint ||
            receipt[ContactDecisionReceipts.decision] != command.decision
        ) {
            throw ReliableCommandConflictException("好友申请操作标识已用于不同请求")
        }
        val payload = checkNotNull(receipt[ContactDecisionReceipts.resultPayload]) {
            "Committed contact-decision receipt has no result"
        }
        check(payload.size <= MAX_PAYLOAD_BYTES) {
            "好友申请操作回执超出容量边界"
        }
        return ProtoCodec.decode(ContactApply, payload)
    }

    fun complete(command: ContactDecisionCommand, apply: ContactApply) {
        val payload = ProtoCodec.encode(apply)
        check(payload.size <= MAX_PAYLOAD_BYTES) {
            "好友申请操作回执超出容量边界"
        }
        check(ContactDecisionReceipts.update({
            (ContactDecisionReceipts.actorUid eq command.receiverUid) and
                (ContactDecisionReceipts.operationId eq command.operationId) and
                ContactDecisionReceipts.resultPayload.isNull()
        }) {
            it[resultPayload] = payload
        } == 1) { "Reserved contact-decision receipt was lost" }
    }

    /**
     * 调用方持有接收者 User 行，即此 actor 作用域窗口的聚合 fence。
     * 只有无法再接受重试的回执才可以被收集。硬边界上的新命令
     * 会被拒绝（其预留随之回滚），而不是牺牲一个其原始客户端
     * 可能仍在等待 ACK 的身份。
     */
    fun pruneExpiredAndRequireCapacity(receiverUid: String, nowMillis: Long) {
        ContactDecisionReceipts.deleteWhere {
            (ContactDecisionReceipts.actorUid eq receiverUid) and
                (ContactDecisionReceipts.expiresAt less nowMillis)
        }
        val retained = ContactDecisionReceipts.selectAll().where {
            ContactDecisionReceipts.actorUid eq receiverUid
        }.count()
        if (retained > ContactPolicy.MAX_DECISION_RECEIPTS_PER_ACTOR.toLong()) {
            throw ReliableCommandCapacityException("好友申请可靠重试窗口已满")
        }
    }

    private companion object {
        const val MAX_PAYLOAD_BYTES = 8 * 1_024
    }
}
