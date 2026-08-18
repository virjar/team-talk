package com.virjar.tk.repository

import com.virjar.tk.Outcome
import com.virjar.tk.model.OrganizationMember
import com.virjar.tk.model.OrganizationUnit
import com.virjar.tk.outcome
import com.virjar.tk.rpc.RpcInvoker
import com.virjar.tk.rpc.gen.OrganizationRpcProxy

/** 终端用户只读的组织目录 SDK。写操作只允许管理端执行。 */
class OrganizationRepository(rpcClient: RpcInvoker) {
    private val rpc = OrganizationRpcProxy(rpcClient)

    suspend fun listUnits(): Outcome<List<OrganizationUnit>> = outcome { rpc.listUnits() }

    suspend fun listMembers(unitId: String, recursive: Boolean = false): Outcome<List<OrganizationMember>> =
        outcome { rpc.listMembers(unitId, recursive) }
}
