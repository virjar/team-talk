package com.virjar.tk.protocol.rpc.def

import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.GroupFileEntry
import com.virjar.tk.protocol.model.GroupFileVersion
import com.virjar.tk.protocol.rpc.RpcMethod
import com.virjar.tk.protocol.rpc.RpcService

/** 群共享文件空间 RPC；每个方法显式声明当前协议基线的 methodId。 */
@com.virjar.tk.protocol.SinceProtocol(0)
@RpcService("groupFile")
interface GroupFileRpc {
    @RpcMethod(1)
    suspend fun list(chatId: String, parentId: String?): List<GroupFileEntry>
    /** [entryId] 与 [commandId] 是 canonical UUID，本次创建的每次重试都复用同一值。 */
    @RpcMethod(2)
    suspend fun createFolder(
        entryId: String,
        commandId: String,
        chatId: String,
        parentId: String?,
        name: String,
    ): GroupFileEntry
    /** [entryId] 与 [commandId] 是 canonical UUID，本次发布的每次重试都复用同一值。 */
    @RpcMethod(3)
    suspend fun createFile(
        entryId: String,
        commandId: String,
        chatId: String,
        parentId: String?,
        name: String,
        attachment: Attachment,
    ): GroupFileEntry
    /** [commandId] 是一个 canonical UUID，在收到未知结果响应后复用。 */
    @RpcMethod(4)
    suspend fun addVersion(
        commandId: String,
        chatId: String,
        entryId: String,
        attachment: Attachment,
        expectedRevision: Long,
    ): GroupFileEntry
    @RpcMethod(5)
    suspend fun listVersions(chatId: String, entryId: String): List<GroupFileVersion>
    /** [commandId] 是一个 canonical UUID，在收到未知结果响应后复用。 */
    @RpcMethod(6)
    suspend fun rename(
        commandId: String,
        chatId: String,
        entryId: String,
        name: String,
        expectedRevision: Long,
    )
    /** [commandId] 是一个 canonical UUID，在收到未知结果响应后复用。 */
    @RpcMethod(7)
    suspend fun delete(commandId: String, chatId: String, entryId: String, expectedRevision: Long)
    /** 类型化引用的打开校验：按当前群成员身份读取单个条目。 */
    @RpcMethod(8)
    suspend fun getEntry(chatId: String, entryId: String): GroupFileEntry
}
