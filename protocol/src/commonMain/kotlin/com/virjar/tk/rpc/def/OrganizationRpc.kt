package com.virjar.tk.rpc.def

import com.virjar.tk.model.OrganizationMember
import com.virjar.tk.model.OrganizationUnit
import com.virjar.tk.rpc.RpcMethod
import com.virjar.tk.rpc.RpcService

/** 组织目录只读 RPC。结构和人员变更属于管理端治理能力。 */
@RpcService("organization")
interface OrganizationRpc {
    @RpcMethod(1)
    suspend fun listUnits(): List<OrganizationUnit>
    @RpcMethod(2)
    suspend fun listMembers(unitId: String, recursive: Boolean): List<OrganizationMember>
}
