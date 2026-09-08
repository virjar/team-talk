package com.virjar.tk.app.ui.screen

import com.virjar.tk.app.navigation.UiLocalDataBoundary
import com.virjar.tk.app.ui.component.rich.ChatComposerMode
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.shared.client.*
import com.virjar.tk.shared.testkit.FakeLocalCache
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import kotlin.test.*

/** App ownership tests control the commit boundary; SDK integration tests own actual SQLite recovery. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatComposerPersistenceTest {
    @Test
    fun `remote full draft replaces a clean editor but unsaved typing keeps its older shared base`() = runTest {
        val cache = FakeLocalCache()
        cache.chatDrafts.save(ChatDraftSnapshot(CHAT, revision = 1, markdown = "共同草稿", sharedRevision = 5))
        val writer = ControlledWriter(cache)
        val store = newStore(cache, writer)
        val original = assertNotNull(store.hydrate(CHAT))
        cache.chatDrafts.save(ChatDraftSnapshot(CHAT, revision = 2, markdown = "其他设备的新稿", mode = 1, sharedRevision = 6))
        assertNull(store.receivePersisted(CHAT) { original.copy(markdown = "本机尚未防抖保存的输入") })
        writer.runNext()
        val retained = assertNotNull(cache.chatDrafts.get(CHAT))
        assertEquals("本机尚未防抖保存的输入", retained.markdown)
        assertEquals(5L, retained.sharedRevision, "storage must see the original base to detect the conflict")

        val clean = assertNotNull(store.restore(CHAT))
        cache.chatDrafts.save(ChatDraftSnapshot(CHAT, revision = retained.revision + 1,
            markdown = "选用远端草稿", mode = 2, sharedRevision = 7))
        val received = assertNotNull(store.receivePersisted(CHAT) { clean })
        assertEquals("选用远端草稿", received.markdown)
        assertEquals(ChatComposerMode.PREVIEW, received.mode)
        assertEquals(7L, received.sharedRevision)
    }

    @Test
    fun `send preflight waits for the admitted draft write and sees storage failure`() = runTest {
        val cache = FakeLocalCache()
        val writer = ControlledWriter(cache)
        val store = newStore(cache, writer) {}
        store.hydrate(CHAT)
        val revision = assertNotNull(store.save(CHAT, ChatComposerContext(markdown = "需要先落盘")))
        val beforeSend = async { runCatching { store.awaitSaved(CHAT, revision) } }
        runCurrent()
        assertFalse(beforeSend.isCompleted)
        writer.failNext()
        assertTrue(beforeSend.await().isFailure)
        assertEquals("需要先落盘", store.restore(CHAT)?.markdown)
    }

    @Test
    fun `remote read captures the latest frame after IO and a rejected send releases its observation lease`() = runTest {
        val cache = FakeLocalCache()
        cache.chatDrafts.save(ChatDraftSnapshot(CHAT, revision = 1, markdown = "原稿", sharedRevision = 1))
        val writer = ControlledWriter(cache)
        val store = newStore(cache, writer)
        var current = assertNotNull(store.hydrate(CHAT))
        cache.chatDrafts.save(ChatDraftSnapshot(CHAT, revision = 2, markdown = "远端更新", sharedRevision = 2))
        val receiving = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            store.receivePersisted(CHAT) { current }
        }
        // localData uses a queued dispatcher; this edit happens after the read began and before it resumes.
        current = current.copy(markdown = "读取期间输入")
        assertNull(receiving.await())
        writer.runNext()
        assertEquals("读取期间输入", cache.chatDrafts.get(CHAT)?.markdown)
        val local = assertNotNull(store.restore(CHAT))
        val submission = store.beginSend(CHAT, local.draftRevision)
        submission.completion.complete(false)
        store.finishSend(CHAT, submission)
        cache.chatDrafts.save(ChatDraftSnapshot(CHAT, revision = writer.revision + 1,
            markdown = "随后选择远端", sharedRevision = 3))
        assertEquals("随后选择远端", store.receivePersisted(CHAT) { local }?.markdown)
    }

    @Test
    fun `device A receives device B plain draft before opening and does not restore or echo its older composer`() = runTest {
        val cache = FakeLocalCache()
        cache.chatDrafts.save(ChatDraftSnapshot(CHAT, revision = 1, markdown = "A的旧草稿"))
        cache.upsertConversation(Conversation(CHAT, chatType = 2, draft = "A的旧草稿"))
        // A is viewing another page when the regular conversation projection receives B's update.
        cache.upsertConversation(Conversation(CHAT, chatType = 2, draft = "B的新草稿"))
        val writer = ControlledWriter(cache)
        val reopened = newStore(cache, writer)
        val recovered = assertNotNull(reopened.hydrate(CHAT))
        assertEquals("B的新草稿", recovered.markdown)
        assertEquals("B的新草稿", recovered.previousCachedDraft)
        val revision = assertNotNull(reopened.save(CHAT, recovered))
        assertTrue(revision > 1, "following the mirror must persist a new composer revision")
        writer.runNext()
        assertEquals("B的新草稿", cache.chatDrafts.get(CHAT)?.markdown)

        // A remote clear is an explicit empty draft, not absence of a loaded conversation.
        cache.upsertConversation(Conversation(CHAT, chatType = 2, draft = null))
        assertEquals("", newStore(cache, writer).hydrate(CHAT)?.markdown)
    }

    @Test
    fun `hydration keeps local pending mirror and reply context over an unrelated remote draft`() = runTest {
        val cache = FakeLocalCache()
        cache.chatDrafts.save(ChatDraftSnapshot(CHAT, revision = 1, markdown = "本机离线输入"))
        cache.upsertConversation(Conversation(CHAT, chatType = 2, draft = "先前远端草稿"))
        cache.setConversationDraft(CHAT, "本机离线输入")
        val writer = ControlledWriter(cache)
        assertEquals("本机离线输入", newStore(cache, writer).hydrate(CHAT)?.markdown)

        val reply = ChatDraftSnapshot(CHAT, revision = 2, markdown = "我的回复", replyToClientMsgId = "reply-id", replyToServerSeq = 9)
        cache.chatDrafts.save(reply)
        assertEquals(reply.copy(revision = 0),
            assertNotNull(newStore(cache, writer).hydrate(CHAT)).toDraftSnapshot(CHAT))
    }

    @Test
    fun `fresh editor recovers full local context and an empty consumed revision`() = runTest {
        val cache = FakeLocalCache()
        val writer = ControlledWriter(cache)
        val first = newStore(cache, writer)
        first.hydrate(CHAT)
        val asset = EmbeddedAsset(ASSET, Attachment("/files/example/report.txt", "report.txt", "text/plain", 7))
        val markdown = "[报告](teamtalk-asset://asset/$ASSET)\n原始空行\n"
        val frame = ChatComposerContext(
            markdown = markdown, assets = listOf(asset), mode = ChatComposerMode.MARKDOWN,
            selectionStart = 4, selectionEnd = 6, replyTarget = SavedChatReplyTarget("reply-id", 42),
        )
        assertNotNull(first.save(CHAT, frame))
        assertNull(cache.chatDrafts.get(CHAT), "in-memory admission is not a durable commit")
        writer.runNext()
        val restored = assertNotNull(newStore(cache, writer).hydrate(CHAT))
        assertEquals(frame.toDraftSnapshot(CHAT), restored.toDraftSnapshot(CHAT))
        cache.chatDrafts.save(ChatDraftSnapshot(CHAT, revision = writer.revision + 1))
        val empty = assertNotNull(newStore(cache, writer).hydrate(CHAT))
        assertEquals("", empty.markdown, "an empty revision must not fall back to an older remote draft")
        assertTrue(empty.draftRevision > 0)
    }

    @Test
    fun `temporary message editing persists the suspended ordinary draft`() = runTest {
        val cache = FakeLocalCache()
        val writer = ControlledWriter(cache)
        val store = newStore(cache, writer)
        store.hydrate(CHAT)
        val frame = ChatComposerContext(
            markdown = "临时消息编辑内容",
            editingSession = SavedChatEditingSession(
                editingClientMsgId = "edit-id", suspendedMarkdown = "尚未发送的普通草稿",
                suspendedMode = ChatComposerMode.PREVIEW, selectionStart = 2, selectionEnd = 3,
                replyingClientMsgId = "original-reply", replyingServerSeq = 18,
            ),
        )
        store.save(CHAT, frame)
        writer.runNext()
        val recovered = assertNotNull(newStore(cache, writer).hydrate(CHAT))
        assertEquals("尚未发送的普通草稿", recovered.markdown)
        assertEquals(ChatComposerMode.PREVIEW, recovered.mode)
        assertEquals(SavedChatReplyTarget("original-reply", 18), recovered.replyTarget)
        assertTrue(recovered.editingSession.editingClientMsgId.isEmpty())
    }

    @Test
    fun `navigation waits for a local send and cannot resurrect its consumed hot frame`() = runTest {
        val cache = FakeLocalCache()
        val writer = ControlledWriter(cache)
        val store = newStore(cache, writer)
        store.hydrate(CHAT)
        val revision = assertNotNull(store.save(CHAT, ChatComposerContext(markdown = "发送这份")))
        writer.runNext()
        val submission = store.beginSend(CHAT, revision)
        val reopening = async { store.hydrate(CHAT) }
        runCurrent()
        assertFalse(reopening.isCompleted)
        cache.enqueueFromComposer(message("发送这份"), revision, 1)
        submission.completion.complete(true)
        assertEquals("", assertNotNull(reopening.await()).markdown)
        assertEquals("", assertNotNull(store.restore(CHAT)).markdown)
    }

    @Test
    fun `new input survives an older send completion and failed draft writes can retry unchanged text`() = runTest {
        val cache = FakeLocalCache()
        val writer = ControlledWriter(cache)
        val failures = mutableListOf<Throwable>()
        val store = newStore(cache, writer, failures::add)
        store.hydrate(CHAT)
        val oldRevision = assertNotNull(store.save(CHAT, ChatComposerContext(markdown = "旧稿")))
        writer.runNext()
        store.beginSend(CHAT, oldRevision)
        store.save(CHAT, ChatComposerContext(markdown = "下一份草稿"))
        writer.runNext()
        assertFalse(store.acceptSent(CHAT, oldRevision))
        assertEquals("下一份草稿", store.restore(CHAT)?.markdown)
        val failedRevision = assertNotNull(store.save(CHAT, ChatComposerContext(markdown = "保存失败仍有原文")))
        writer.failNext()
        assertEquals(1, failures.size)
        val retryRevision = assertNotNull(store.save(CHAT, ChatComposerContext(markdown = "保存失败仍有原文")))
        assertTrue(retryRevision > failedRevision, "failed admission must not poison identical-frame deduplication")
        writer.runNext()
        assertEquals("保存失败仍有原文", cache.chatDrafts.get(CHAT)?.markdown)
    }

    private fun TestScope.newStore(
        cache: FakeLocalCache,
        writer: ControlledWriter,
        onFailure: (Throwable) -> Unit = { throw it },
    ) = ChatComposerContextStore().also {
        it.bindPersistence(cache, writer, UiLocalDataBoundary(StandardTestDispatcher(testScheduler)), onFailure)
    }

    private fun message(text: String) = Message(
        chatId = CHAT, clientMsgId = "outgoing-id", senderUid = "owner",
        messageType = MessageType.RICH_TEXT.code, timestamp = 1, body = RichTextBody(text, plainText = text),
    )

    private class ControlledWriter(private val cache: FakeLocalCache) : SessionLocalMutationWriter {
        var revision = cache.chatDrafts.maxRevision()
        private val pending = ArrayDeque<Pair<() -> Unit, (Throwable) -> Unit>>()
        override fun saveChatDraft(snapshot: ChatDraftSnapshot, onCommitted: (ChatDraftSnapshot) -> Unit, onFailure: (Throwable) -> Unit): Long {
            revision = maxOf(revision, cache.chatDrafts.maxRevision()) + 1
            val accepted = snapshot.copy(revision = revision)
            pending += ({ onCommitted(cache.chatDrafts.save(accepted)); Unit }) to onFailure
            return accepted.revision
        }
        fun runNext() { pending.removeFirst().first() }
        fun failNext() { pending.removeFirst().second(IllegalStateException("fixture storage rejected")) }
        override fun setDraft(chatId: String, draft: String?, onFailure: (Throwable) -> Unit) = error("Unexpected legacy draft write")
        override fun markRead(chatId: String, readSeq: Long, onFailure: (Throwable) -> Unit) = error("Unused")
        override fun insertUploadingPlaceholder(message: Message, onFailure: (Throwable) -> Unit) = error("Unused")
        override fun updateUploadProgress(chatId: String, clientMsgId: String, progress: Float) = error("Unused")
        override fun enqueueOutgoing(message: Message, onFailure: (Throwable) -> Unit) = error("Unexpected non-atomic send")
        override fun discardTerminalFailure(chatId: String, clientMsgId: String, onResult: (Boolean) -> Unit, onFailure: (Throwable) -> Unit) = error("Unused")
        override fun replaceTerminalFailure(chatId: String, clientMsgId: String, replacement: Message, onResult: (OutgoingMessage?) -> Unit, onFailure: (Throwable) -> Unit) = error("Unused")
        override fun markMessageFailed(chatId: String, clientMsgId: String, onFailure: (Throwable) -> Unit) = error("Unused")
        override fun closePager(pager: MessagePager) = error("Unused")
        override fun rollbackOptimisticEdit(lease: OptimisticMessageEditLease) = error("Unused")
    }

    private companion object {
        const val CHAT = "draft-chat"
        const val ASSET = "00000000-0000-0000-0000-000000000017"
    }
}
