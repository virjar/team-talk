package com.virjar.tk.rpc.def

import com.virjar.tk.model.OrganizationMember
import com.virjar.tk.model.OrganizationUnit
import com.virjar.tk.rpc.RpcService

/** 组织目录只读 RPC。结构和人员变更属于管理端治理能力。 */
@RpcService("organization")
interface OrganizationRpc {
    suspend fun listUnits(): List<OrganizationUnit>
    suspend fun listMembers(unitId: String, recursive: Boolean): List<OrganizationMember>
}
