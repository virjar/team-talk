package com.virjar.tk.repository

import com.virjar.tk.Outcome
import com.virjar.tk.model.Attachment
import com.virjar.tk.model.GroupFileEntry
import com.virjar.tk.model.GroupFileVersion
import com.virjar.tk.outcome
import com.virjar.tk.rpc.RpcInvoker
import com.virjar.tk.rpc.gen.GroupFileRpcProxy

/** 群共享文件 SDK；上传仍由 [FileRepository] 完成，发布后才成为群文件版本。 */
class GroupFileRepository(rpcClient: RpcInvoker) {
    private val rpc = GroupFileRpcProxy(rpcClient)

    suspend fun list(chatId: String, parentId: String? = null): Outcome<List<GroupFileEntry>> =
        outcome { rpc.list(chatId, parentId) }

    suspend fun createFolder(chatId: String, parentId: String?, name: String): Outcome<GroupFileEntry> =
        outcome { rpc.createFolder(chatId, parentId, name) }

    suspend fun createFile(
        chatId: String,
        parentId: String?,
        name: String,
        attachment: Attachment,
    ): Outcome<GroupFileEntry> = outcome { rpc.createFile(chatId, parentId, name, attachment) }

    suspend fun addVersion(
        chatId: String,
        entryId: String,
        attachment: Attachment,
        expectedRevision: Long,
    ): Outcome<GroupFileEntry> = outcome { rpc.addVersion(chatId, entryId, attachment, expectedRevision) }

    suspend fun listVersions(chatId: String, entryId: String): Outcome<List<GroupFileVersion>> =
        outcome { rpc.listVersions(chatId, entryId) }

    suspend fun rename(chatId: String, entryId: String, name: String, expectedRevision: Long): Outcome<GroupFileEntry> =
        outcome { rpc.rename(chatId, entryId, name, expectedRevision) }

    suspend fun delete(chatId: String, entryId: String, expectedRevision: Long): Outcome<Unit> =
        outcome { rpc.delete(chatId, entryId, expectedRevision) }
}
