package com.virjar.tk.server.protocol.rpc

import com.virjar.tk.protocol.model.*
import com.virjar.tk.protocol.rpc.gen.TaskRpcStub
import com.virjar.tk.server.domain.task.TaskService

class TaskRpcImpl(uid: String, private val service: TaskService) : TaskRpcStub(uid) {
    override suspend fun list(view: Int, cursor: String?, limit: Int) = service.list(uid, view, cursor, limit)
    override suspend fun get(taskId: String) = service.get(uid, taskId)
    override suspend fun audit(taskId: String, cursor: String?, limit: Int) = service.audit(uid, taskId, cursor, limit)
    override suspend fun mutate(command: TaskCommand) = service.mutate(uid, command)
    override suspend fun details(taskId: String) = service.details(uid, taskId)
    override suspend fun query(query: TaskQuery, cursor: String?, limit: Int) = service.query(uid, query, cursor, limit)
    override suspend fun modify(command: TaskDetailsCommand) = service.modify(uid, command)
    override suspend fun history(taskId: String, cursor: String?, limit: Int) = service.history(uid, taskId, cursor, limit)
    override suspend fun modifySeries(command: TaskSeriesCommand) = service.modifySeries(uid, command)
}
