package com.virjar.tk.rpc.def

import com.virjar.tk.model.Attachment
import com.virjar.tk.model.GroupFileEntry
import com.virjar.tk.model.GroupFileVersion
import com.virjar.tk.rpc.RpcService

/** 群共享文件空间 RPC。新方法只能追加，不能重排。 */
@RpcService("groupFile")
interface GroupFileRpc {
    suspend fun list(chatId: String, parentId: String?): List<GroupFileEntry>
    suspend fun createFolder(chatId: String, parentId: String?, name: String): GroupFileEntry
    suspend fun createFile(chatId: String, parentId: String?, name: String, attachment: Attachment): GroupFileEntry
    suspend fun addVersion(chatId: String, entryId: String, attachment: Attachment, expectedRevision: Long): GroupFileEntry
    suspend fun listVersions(chatId: String, entryId: String): List<GroupFileVersion>
    suspend fun rename(chatId: String, entryId: String, name: String, expectedRevision: Long): GroupFileEntry
    suspend fun delete(chatId: String, entryId: String, expectedRevision: Long)
}
