package com.virjar.tk.protocol.rpc.def

import com.virjar.tk.protocol.model.OrganizationMemberPage
import com.virjar.tk.protocol.model.OrganizationMemberPageRequest
import com.virjar.tk.protocol.model.OrganizationUnitPage
import com.virjar.tk.protocol.model.OrganizationUnitPageRequest
import com.virjar.tk.protocol.rpc.RpcMethod
import com.virjar.tk.protocol.rpc.RpcService

/** 组织目录只读 RPC。结构和人员变更属于管理端治理能力。 */
@com.virjar.tk.protocol.SinceProtocol(0)
@RpcService("organization")
interface OrganizationRpc {
    @RpcMethod(1)
    suspend fun listUnitPage(request: OrganizationUnitPageRequest): OrganizationUnitPage
    @RpcMethod(2)
    suspend fun listMemberPage(request: OrganizationMemberPageRequest): OrganizationMemberPage
}
