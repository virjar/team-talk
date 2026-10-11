package com.virjar.tk.protocol.payload

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.ProtocolCorruptionException
import com.virjar.tk.protocol.SinceProtocol

/**
 * 服务端 → 客户端，仅在密码/刷新凭证确认被封禁账号后发送。
 * 这是认证终态，不是成功会话，也不是通用授权错误。
 * 独立帧保住了 AUTH_RESP 已冻结的规则：失败响应不能携带 datasetId。
 */
@SinceProtocol(1)
data class AccountBannedPayload(
    val uid: String,
    val datasetId: String,
    val reason: String? = null,
) : IProto {
    init {
        require(uid.isNotBlank()) { "accountBan.uid must not be blank" }
        SyncDatasetIdPolicy.requireValid(datasetId)
    }

    override fun writeTo(buf: PacketBuffer) {
        AuthPayloadPolicy.requireOutboundLength(uid, AuthPayloadPolicy.MAX_UID_LENGTH, "accountBan.uid")
        AuthPayloadPolicy.requireOutboundLength(reason, AuthPayloadPolicy.MAX_REASON_LENGTH, "accountBan.reason")
        buf.writeString(uid)
        buf.writeString(datasetId)
        buf.writeString(reason)
    }

    companion object : IProtoReader<AccountBannedPayload> {
        override fun readFrom(buf: PacketBuffer) = AccountBannedPayload(
            uid = AuthPayloadPolicy.readRequiredString(buf, AuthPayloadPolicy.MAX_UID_LENGTH, "accountBan.uid").also {
                if (it.isBlank()) throw ProtocolCorruptionException("accountBan.uid must not be blank")
            },
            datasetId = SyncDatasetIdPolicy.readRequired(buf, "accountBan.datasetId"),
            reason = AuthPayloadPolicy.readString(buf, AuthPayloadPolicy.MAX_REASON_LENGTH, "accountBan.reason"),
        )
    }
}
