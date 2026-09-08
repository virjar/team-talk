package com.virjar.tk.server.protocol.rpc

import com.virjar.tk.protocol.model.ContentSearchRequest
import com.virjar.tk.protocol.rpc.gen.ContentSearchRpcStub
import com.virjar.tk.server.domain.search.ContentSearchService

class ContentSearchRpcImpl(uid: String, private val service: ContentSearchService) : ContentSearchRpcStub(uid) {
    override suspend fun search(request: ContentSearchRequest) = service.search(uid, request)
}
