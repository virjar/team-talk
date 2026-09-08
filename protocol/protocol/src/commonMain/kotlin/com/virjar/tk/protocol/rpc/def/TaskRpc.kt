package com.virjar.tk.protocol.rpc.def

import com.virjar.tk.protocol.SinceProtocol
import com.virjar.tk.protocol.model.*
import com.virjar.tk.protocol.rpc.RpcMethod
import com.virjar.tk.protocol.rpc.RpcService

@SinceProtocol(2)
@RpcService("task")
interface TaskRpc {
    @RpcMethod(1)
    suspend fun list(view: Int, cursor: String?, limit: Int): TaskPage
    @RpcMethod(2)
    suspend fun get(taskId: String): WorkTask
    @RpcMethod(3)
    suspend fun audit(taskId: String, cursor: String?, limit: Int): TaskAuditPage
    @RpcMethod(4)
    suspend fun mutate(command: TaskCommand): TaskCommandResult
}
