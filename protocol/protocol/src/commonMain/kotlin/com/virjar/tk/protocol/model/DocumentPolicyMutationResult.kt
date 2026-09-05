package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.ProtocolCorruptionException
import com.virjar.tk.protocol.ProtocolEncodingException
import kotlinx.serialization.Serializable

/**
 * 一次可靠显式 ACL 变更后的权威调用方投影。
 *
 * 精确命令重放可能返回比命令最初产生的更晚的 [policyRevision] 与角色。
 * 不可变回执证明副作用已提交；这些字段有意描述当前锁定的策略，
 * 使旧的 ACK 丢失重试无法恢复过期的本地权限。
 */
@Serializable
data class DocumentPolicyMutationResult(
    val spaceId: String,
    val policyRevision: Long,
    val effectiveRole: Int,
) : IProto {
    init {
        validate(::ProtocolEncodingException)
    }

    override fun writeTo(buf: PacketBuffer) {
        validate(::ProtocolEncodingException)
        buf.writeString(spaceId)
        buf.writeVarLong(policyRevision)
        buf.writeVarInt(effectiveRole)
    }

    companion object : IProtoReader<DocumentPolicyMutationResult> {
        override fun readFrom(buf: PacketBuffer): DocumentPolicyMutationResult {
            val spaceId = buf.readRequiredString()
            val policyRevision = buf.readVarLong()
            val effectiveRole = buf.readVarInt()
            validate(spaceId, policyRevision, effectiveRole, ::ProtocolCorruptionException)
            return DocumentPolicyMutationResult(spaceId, policyRevision, effectiveRole)
        }

        private inline fun validate(
            spaceId: String,
            policyRevision: Long,
            effectiveRole: Int,
            failure: (String) -> IllegalArgumentException,
        ) {
            if (spaceId.isBlank() || spaceId.length > DocumentSpaceGrant.MAX_ID_LENGTH) {
                throw failure("Document policy result space id is invalid")
            }
            if (policyRevision <= 0L) {
                throw failure("Document policy revision must be positive")
            }
            if (effectiveRole !in DocumentSpace.ROLE_NONE..DocumentSpace.ROLE_OWNER) {
                throw failure("Document policy result effective role is invalid")
            }
        }
    }

    private inline fun validate(failure: (String) -> IllegalArgumentException) =
        Companion.validate(spaceId, policyRevision, effectiveRole, failure)
}
