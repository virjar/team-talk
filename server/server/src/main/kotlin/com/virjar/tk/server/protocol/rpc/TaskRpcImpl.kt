package com.virjar.tk.server.protocol.rpc

import com.virjar.tk.protocol.model.TaskCommand
import com.virjar.tk.protocol.rpc.gen.TaskRpcStub
import com.virjar.tk.server.domain.task.TaskService

class TaskRpcImpl(uid: String, private val service: TaskService) : TaskRpcStub(uid) {
    override suspend fun list(view: Int, cursor: String?, limit: Int) = service.list(uid, view, cursor, limit)
    override suspend fun get(taskId: String) = service.get(uid, taskId)
    override suspend fun audit(taskId: String, cursor: String?, limit: Int) = service.audit(uid, taskId, cursor, limit)
    override suspend fun mutate(command: TaskCommand) = service.mutate(uid, command)
}
