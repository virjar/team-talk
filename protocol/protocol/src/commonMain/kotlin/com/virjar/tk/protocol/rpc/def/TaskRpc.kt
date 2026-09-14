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
    @SinceProtocol(3)
    @RpcMethod(5)
    suspend fun details(taskId: String): TaskDetails
    @SinceProtocol(3)
    @RpcMethod(6)
    suspend fun query(query: TaskQuery, cursor: String?, limit: Int): TaskQueryPage
    @SinceProtocol(3)
    @RpcMethod(7)
    suspend fun modify(command: TaskDetailsCommand): TaskDetailsCommandResult
    @SinceProtocol(3)
    @RpcMethod(8)
    suspend fun history(taskId: String, cursor: String?, limit: Int): TaskHistoryPage
    @SinceProtocol(3)
    @RpcMethod(9)
    suspend fun modifySeries(command: TaskSeriesCommand): TaskSeries
}
