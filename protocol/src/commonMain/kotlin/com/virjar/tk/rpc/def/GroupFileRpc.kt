package com.virjar.tk.rpc.def

import com.virjar.tk.model.Attachment
import com.virjar.tk.model.GroupFileEntry
import com.virjar.tk.model.GroupFileVersion
import com.virjar.tk.rpc.RpcMethod
import com.virjar.tk.rpc.RpcService

/** 群共享文件空间 RPC；每个方法显式声明稳定 methodId。 */
@RpcService("groupFile")
interface GroupFileRpc {
    @RpcMethod(1)
    suspend fun list(chatId: String, parentId: String?): List<GroupFileEntry>
    @RpcMethod(2)
    suspend fun createFolder(chatId: String, parentId: String?, name: String): GroupFileEntry
    @RpcMethod(3)
    suspend fun createFile(chatId: String, parentId: String?, name: String, attachment: Attachment): GroupFileEntry
    @RpcMethod(4)
    suspend fun addVersion(chatId: String, entryId: String, attachment: Attachment, expectedRevision: Long): GroupFileEntry
    @RpcMethod(5)
    suspend fun listVersions(chatId: String, entryId: String): List<GroupFileVersion>
    @RpcMethod(6)
    suspend fun rename(chatId: String, entryId: String, name: String, expectedRevision: Long): GroupFileEntry
    @RpcMethod(7)
    suspend fun delete(chatId: String, entryId: String, expectedRevision: Long)
}
