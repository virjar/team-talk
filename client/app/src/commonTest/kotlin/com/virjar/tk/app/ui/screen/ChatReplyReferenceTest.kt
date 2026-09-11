package com.virjar.tk.app.ui.screen

import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.repository.MessageRepository
import com.virjar.tk.shared.testkit.FakeLocalCache
import com.virjar.tk.shared.testkit.FakeRpcInvoker
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import com.virjar.tk.app.navigation.feature.chat.SavedChatReplyTarget

class ChatReplyReferenceTest {
    @Test
    fun `reading a reply outside the current page preserves the resident history`() = runTest {
        val cache = FakeLocalCache()
        val recent = message("recent", 99)
        val target = message("reply-target", 12)
        cache.insertMessage(recent)
        val rpc = FakeRpcInvoker().apply { enqueueOk(ProtoCodec.encodeList(listOf(target))) }
        assertEquals(target, MessageRepository(rpc, cache).getMessage("chat", 12).getOrThrow())
        assertNull(cache.findMessage("chat", target.clientMsgId))
        assertEquals(listOf(recent), cache.getMessages("chat"))
    }

    @Test
    fun `reply reads preserve permission failures and cannot resolve another sequence`() = runTest {
        val cache = FakeLocalCache()
        val rpc = FakeRpcInvoker()
        val repository = MessageRepository(rpc, cache)
        rpc.enqueueError(403)
        val denied = assertIs<Outcome.Failure>(repository.getMessage("chat", 12))
        assertEquals(403, assertIs<AppError.Business>(denied.error).code)
        rpc.enqueueOk(ProtoCodec.encodeList(listOf(message("other", 13))))
        assertNull(repository.getMessage("chat", 12).getOrThrow())
        val revoked = message("reply-target", 12).copy(flags = Message.FLAG_REVOKED)
        assertNull(SavedChatReplyTarget(revoked.clientMsgId, 12).bind(listOf(revoked)))
    }

    private fun message(id: String, sequence: Long) = Message(
        chatId = "chat", clientMsgId = id, serverSeq = sequence, senderUid = "sender",
        messageType = MessageType.RICH_TEXT.code, timestamp = sequence, body = RichTextBody("正文 $sequence", plainText = "正文 $sequence"),
    )
}
