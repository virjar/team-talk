package com.virjar.tk.protocol.rpc.def

import com.virjar.tk.protocol.SinceProtocol
import com.virjar.tk.protocol.model.ContentSearchPage
import com.virjar.tk.protocol.model.ContentSearchRequest
import com.virjar.tk.protocol.rpc.RpcMethod
import com.virjar.tk.protocol.rpc.RpcService

/** 分领域有界搜索；每页按当前对象状态和权限裁决，摘要不授予打开或下载能力。 */
@SinceProtocol(2)
@RpcService("contentSearch")
interface ContentSearchRpc {
    @RpcMethod(1)
    suspend fun search(request: ContentSearchRequest): ContentSearchPage
}
