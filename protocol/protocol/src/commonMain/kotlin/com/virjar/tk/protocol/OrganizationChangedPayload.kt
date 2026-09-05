package com.virjar.tk.protocol

/**
 * 粗粒度的组织目录失效通知。
 *
 * 该通知不携带任何目录行。终端客户端只把它当作一个提示，用来合并一次新的、
 * 带 revision 围栏的 [OrganizationRpc][com.virjar.tk.protocol.rpc.def.OrganizationRpc] 快照；
 * 重连在错过瞬时通知时执行同样的刷新。
 */
data class OrganizationChangedPayload(
    val revision: Long,
) : IProto {
    init {
        require(revision > 0L) { "organization revision must be positive" }
    }

    override fun writeTo(buf: PacketBuffer) {
        buf.writeVarLong(revision)
    }

    companion object : IProtoReader<OrganizationChangedPayload> {
        override fun readFrom(buf: PacketBuffer): OrganizationChangedPayload =
            OrganizationChangedPayload(buf.readVarLong())
    }
}
