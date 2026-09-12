package com.virjar.tk.shared.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.protocol.ChatDraftChangedPayload
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.ChatDraftContent
import com.virjar.tk.protocol.model.ChatDraftMutationResult
import com.virjar.tk.protocol.model.ChatDraftSnapshot as SharedChatDraftSnapshot
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.database.AppDatabase
import com.virjar.tk.shared.repository.ChatDraftRepository
import com.virjar.tk.shared.testkit.FakeRpcInvoker
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

/** 真实 SQLite、RPC codec 与事件游标覆盖跨设备草稿的提交、冲突及进程恢复。 */
class ChatDraftSyncRecoveryIntegrationTest {
    @Test
    fun `unknown mutation replays exact bytes then rebases newer own edits`() = runBlocking {
        database { file ->
            lateinit var request: ByteArray
            lateinit var pending: PendingSharedChatDraft
            cache(file, true) { cache ->
                cache.chatDraftSync.applyRemote(remote(0))
                save(cache, "first", 0)
                pending = checkNotNull(cache.chatDraftSync.nextCommand(CHAT, 100))
                save(cache, "second", 0)
                val rpc = FakeRpcInvoker().apply { throwOnInvoke = AppError.Network }
                assertIs<Outcome.Failure>(ChatDraftRepository(rpc, cache).retryPending())
                request = checkNotNull(rpc.calls.single().third)
            }
            cache(file) { cache ->
                assertEquals(pending, cache.chatDraftSync.nextCommand(CHAT, 200))
                val rpc = FakeRpcInvoker().apply {
                    enqueueOk(ProtoCodec.encode(ChatDraftMutationResult(true, 1, remote(1, "first"))))
                    enqueueOk(ProtoCodec.encode(ChatDraftMutationResult(true, 2, remote(2, "second"))))
                }
                ChatDraftRepository(rpc, cache).retryPending().getOrThrow()
                assertContentEquals(request, rpc.calls.first().third)
                assertEquals("second", cache.chatDrafts.get(CHAT)?.markdown)
                assertEquals(2L, cache.chatDrafts.get(CHAT)?.sharedRevision)
                assertFalse(cache.chatDraftSync.state(CHAT).pending)
                assertFalse(cache.chatDraftSync.state(CHAT).conflict)
            }
        }
    }

    @Test
    fun `READY sidecar mode reply and tombstone restore without private source`() = runBlocking {
        database { file ->
            val content = ChatDraftContent(markdown(), listOf(asset()), 2, "reply-id", 15)
            cache(file, true) { cache ->
                cache.chatDraftSync.applyRemote(SharedChatDraftSnapshot(CHAT, 4, 100, content))
                val draft = checkNotNull(cache.chatDrafts.get(CHAT))
                assertEquals(content.markdown, draft.markdown); assertEquals(content.assets, draft.assets)
                assertEquals(2, draft.mode); assertEquals("reply-id", draft.replyToClientMsgId); assertEquals(15L, draft.replyToServerSeq)
                assertTrue(cache.chatAssetUploads.jobs().isEmpty())
                assertNull(cache.getPendingConversationDraft(CHAT), "new protocol must not enqueue scalar mirror")
            }
            cache(file) { cache ->
                assertEquals(listOf(asset()), cache.chatDrafts.get(CHAT)?.assets)
                cache.chatDraftSync.applyRemote(remote(5))
                cache.chatDraftSync.applyRemote(SharedChatDraftSnapshot(CHAT, 4, 100, content))
                assertEquals("", cache.chatDrafts.get(CHAT)?.markdown)
                assertEquals(5L, cache.chatDrafts.get(CHAT)?.sharedRevision)
            }
        }
    }

    @Test
    fun `unready local source stays local and explicit conflict choices preserve intent`() = runBlocking {
        cache { cache ->
            cache.chatDraftSync.applyRemote(remote(1, "base"))
            val pending = ChatDraftSnapshot(CHAT, cache.reserveChatDraftRevision(), markdown(), pendingAssetIds = listOf(ASSET), sharedRevision = 1)
            cache.chatDrafts.save(pending)
            cache.chatAssetUploads.register(upload())
            assertNull(cache.chatDraftSync.nextCommand(CHAT, 100))
            cache.chatDraftSync.applyRemote(remote(2, "other device"))
            assertTrue(cache.chatDraftSync.state(CHAT).conflict)
            assertEquals(markdown(), cache.chatDrafts.get(CHAT)?.markdown)
            assertEquals(setOf(SOURCE), cache.chatAssetUploads.retainedSourceIds())
            cache.chatDraftSync.resolve(CHAT, true)
            assertFalse(cache.chatDraftSync.state(CHAT).conflict)
            assertNull(cache.chatDraftSync.nextCommand(CHAT, 100))
            val claimed = checkNotNull(cache.chatAssetUploads.claimNext(System.currentTimeMillis()))
            assertTrue(cache.chatAssetUploads.complete(ASSET, claimed.attempt, asset()))
            val command = checkNotNull(cache.chatDraftSync.nextCommand(CHAT, 100))
            assertEquals(2L, command.command.expectedRevision)
            assertEquals(listOf(asset()), command.command.content?.assets)
            cache.chatDraftSync.acknowledge(command, ChatDraftMutationResult(false, 0, remote(3, "newer")))
            cache.chatDraftSync.resolve(CHAT, false)
            assertEquals("newer", cache.chatDrafts.get(CHAT)?.markdown)
            assertTrue(cache.chatAssetUploads.retainedSourceIds().isEmpty())
        }
    }

    @Test
    fun `successful receipt with newer current installs it and rejects stale hot frame`() = runBlocking {
        cache { cache ->
            cache.chatDraftSync.applyRemote(remote(1, "base"))
            val hot = save(cache, "mine", 1)
            val pending = checkNotNull(cache.chatDraftSync.nextCommand(CHAT, 100))
            cache.chatDraftSync.acknowledge(pending, ChatDraftMutationResult(true, 2, remote(3, "other device")))
            assertEquals("other device", cache.chatDrafts.get(CHAT)?.markdown)
            assertEquals(3L, cache.chatDrafts.get(CHAT)?.sharedRevision)
            // UI debounce 中的帧仍带旧的 remote baseline，不能被误认作在 revision 3 上的新编辑。
            cache.chatDrafts.save(hot.copy(revision = cache.reserveChatDraftRevision(), markdown = "still typing"))
            assertTrue(cache.chatDraftSync.state(CHAT).conflict)
            assertEquals("still typing", cache.chatDrafts.get(CHAT)?.markdown)
            assertNull(cache.chatDraftSync.nextCommand(CHAT, 100))
        }
    }

    @Test
    fun `send consume waits for message success and cannot clear another device newer draft`() = runBlocking {
        database { file ->
            val message = message("sent", "remote text")
            cache(file, true) { cache ->
                cache.chatDraftSync.applyRemote(remote(4, "remote text"))
                val draft = checkNotNull(cache.chatDrafts.get(CHAT))
                cache.enqueueFromComposer(message, draft.revision, 100)
                assertEquals("", cache.chatDrafts.get(CHAT)?.markdown)
                assertNull(cache.chatDraftSync.nextCommand(CHAT, 100))
            }
            cache(file) { cache ->
                cache.insertMessage(message.copy(serverSeq = 7))
                val clear = checkNotNull(cache.chatDraftSync.nextCommand(CHAT, 200))
                assertEquals("sent", clear.command.consumedClientMsgId)
                assertEquals(4L, clear.command.expectedRevision)
                cache.chatDraftSync.acknowledge(clear, ChatDraftMutationResult(false, 0, remote(5, "new draft")))
                assertEquals("new draft", cache.chatDrafts.get(CHAT)?.markdown)
                assertNull(cache.chatDraftSync.nextCommand(CHAT, 300))
            }
        }
    }

    @Test
    fun `send clear acknowledgement preserves successor draft written before or after clear preparation`() = runBlocking {
        for (editBeforeClear in listOf(true, false)) database { file ->
            lateinit var clear: PendingSharedChatDraft
            lateinit var successor: ChatDraftSnapshot
            cache(file, true) { cache ->
                cache.chatDraftSync.applyRemote(remote(1, "draft A"))
                val first = checkNotNull(cache.chatDrafts.get(CHAT))
                val outgoing = message("sent-A", first.markdown)
                cache.enqueueFromComposer(outgoing, first.revision, 100)
                fun editSuccessor() {
                    successor = cache.chatDrafts.save(ChatDraftSnapshot(CHAT, cache.reserveChatDraftRevision(),
                        "draft B ${markdown()}", listOf(asset()), mode = 2, sharedRevision = 1))
                    cache.chatAssetUploads.register(upload().copy(state = ChatAssetUploadState.READY, asset = asset()))
                }
                if (editBeforeClear) editSuccessor()
                assertNull(cache.chatDraftSync.nextCommand(CHAT, 150), "clear must wait for A's message ACK")
                cache.insertMessage(outgoing.copy(serverSeq = 7))
                clear = checkNotNull(cache.chatDraftSync.nextCommand(CHAT, 200))
                assertEquals("sent-A", clear.command.consumedClientMsgId)
                assertNull(clear.command.content)
                if (!editBeforeClear) editSuccessor()
            }
            cache(file) { cache ->
                assertEquals(clear, cache.chatDraftSync.nextCommand(CHAT, 250), "restart must retain the exact clear command")
                cache.chatDraftSync.acknowledge(clear, ChatDraftMutationResult(true, 2, remote(2)))
                assertEquals(successor.copy(sharedRevision = 2), cache.chatDrafts.get(CHAT))
                assertEquals(setOf(SOURCE), cache.chatAssetUploads.retainedSourceIds())
                assertEquals(listOf(ASSET), cache.chatAssetUploads.jobs(CHAT).map { it.assetId })
                assertTrue(cache.chatDraftSync.state(CHAT).pending)
                assertFalse(cache.chatDraftSync.state(CHAT).conflict)
                val publish = checkNotNull(cache.chatDraftSync.nextCommand(CHAT, 300))
                assertEquals(2L, publish.command.expectedRevision)
                assertEquals(ChatDraftContent(successor.markdown, successor.assets, successor.mode), publish.command.content)
                assertNull(publish.command.consumedClientMsgId)
            }
        }
    }

    @Test
    fun `send after unknown SET binds clear to its receipt and never republishes consumed text`() = runBlocking {
        cache { cache ->
            cache.chatDraftSync.applyRemote(remote(0))
            val draft = save(cache, "send this", 0)
            val set = checkNotNull(cache.chatDraftSync.nextCommand(CHAT, 100))
            val message = message("queued", draft.markdown)
            cache.enqueueFromComposer(message, draft.revision, 100)
            cache.chatDraftSync.acknowledge(set, ChatDraftMutationResult(true, 1, remote(1, "send this")))
            assertEquals("", cache.chatDrafts.get(CHAT)?.markdown)
            cache.chatDraftSync.applyRemote(remote(1, "send this"))
            assertEquals("", cache.chatDrafts.get(CHAT)?.markdown)
            assertNull(cache.chatDraftSync.nextCommand(CHAT, 200))
            cache.insertMessage(message.copy(serverSeq = 8))
            val clear = checkNotNull(cache.chatDraftSync.nextCommand(CHAT, 200))
            assertEquals(1L, clear.command.expectedRevision)
            assertEquals("queued", clear.command.consumedClientMsgId)
            cache.chatDraftSync.acknowledge(clear, ChatDraftMutationResult(true, 2, remote(2)))
            assertFalse(cache.chatDraftSync.state(CHAT).pending)
            assertEquals("", cache.chatDrafts.get(CHAT)?.markdown)
        }
    }

    @Test
    fun `failed message replacement transfers consume and discard uses original conditional clear`() = runBlocking {
        cache { cache ->
            fun fail(id: String): Message {
                val draft = checkNotNull(cache.chatDrafts.get(CHAT))
                val message = message(id, draft.markdown)
                val receipt = cache.enqueueFromComposer(message, draft.revision, 100)
                cache.claimNextOutgoingMessage(100)
                cache.markOutgoingMessageTerminalFailed(receipt.localOrdinal, "rejected", 101, 404)
                return message
            }
            cache.chatDraftSync.applyRemote(remote(1, "retry me"))
            fail("old")
            assertNull(cache.chatDraftSync.nextCommand(CHAT, 200))
            cache.chatDraftSync.applyRemote(remote(1, "retry me"))
            assertEquals("", cache.chatDrafts.get(CHAT)?.markdown, "failed outbox must not resurrect its consumed draft")
            val replacement = message("replacement", "repaired text")
            assertNotNull(cache.replaceTerminalFailure("owner", CHAT, "old", replacement, 200))
            assertNull(cache.chatDraftSync.nextCommand(CHAT, 201))
            cache.insertMessage(replacement.copy(serverSeq = 5))
            val clear = checkNotNull(cache.chatDraftSync.nextCommand(CHAT, 300))
            assertEquals("replacement", clear.command.consumedClientMsgId)
            assertEquals(1L, clear.command.expectedRevision)
            cache.chatDraftSync.acknowledge(clear, ChatDraftMutationResult(true, 2, remote(2)))

            cache.chatDraftSync.applyRemote(remote(3, "discard me"))
            fail("discard")
            assertTrue(cache.discardTerminalFailure("owner", CHAT, "discard"))
            val manual = checkNotNull(cache.chatDraftSync.nextCommand(CHAT, 400))
            assertNull(manual.command.consumedClientMsgId)
            assertEquals(3L, manual.command.expectedRevision)
            cache.chatDraftSync.acknowledge(manual, ChatDraftMutationResult(false, 0, remote(4, "other device new text")))
            assertEquals("other device new text", cache.chatDrafts.get(CHAT)?.markdown)

            fail("discard-with-new-draft")
            save(cache, "keep this new draft", 4)
            assertTrue(cache.discardTerminalFailure("owner", CHAT, "discard-with-new-draft"))
            val publish = checkNotNull(cache.chatDraftSync.nextCommand(CHAT, 500))
            assertEquals("keep this new draft", publish.command.content?.markdown)
            assertEquals(4L, publish.command.expectedRevision)
        }
    }

    @Test
    fun `remote asset send requires fresh availability but source owner can enqueue offline`() = runBlocking {
        cache { cache ->
            val remote = SharedChatDraftSnapshot(CHAT, 1, 100, ChatDraftContent(markdown(), listOf(asset())))
            cache.chatDraftSync.applyRemote(remote)
            val revision = checkNotNull(cache.chatDrafts.get(CHAT)).revision
            val rpc = FakeRpcInvoker().apply { throwOnInvoke = AppError.Network }
            val repo = ChatDraftRepository(rpc, cache)
            assertIs<Outcome.Failure>(repo.validateForSend(CHAT, revision))
            cache.chatAssetUploads.register(upload().copy(state = ChatAssetUploadState.READY, asset = asset()))
            repo.validateForSend(CHAT, revision).getOrThrow()
            assertEquals(1, rpc.calls.size)
            cache.chatAssetUploads.remove(ASSET)
            rpc.throwOnInvoke = null
            rpc.enqueueOk(ProtoCodec.encode(remote.copy(assetsAvailable = false)))
            assertIs<Outcome.Failure>(repo.validateForSend(CHAT, revision))
            assertFalse(cache.chatDraftSync.state(CHAT).assetsAvailable)

            // READY 修复会改变 get 的覆盖层，必须由上传完成事务触发同步，不依赖页面再保存。
            cache.chatAssetUploads.register(upload())
            val claim = checkNotNull(cache.chatAssetUploads.claimNext(System.currentTimeMillis()))
            val repaired = asset().copy(attachment = asset().attachment.copy(path = "2026/09/repaired.txt"))
            cache.chatAssetUploads.complete(ASSET, claim.attempt, repaired)
            val updated = checkNotNull(cache.chatDraftSync.nextCommand(CHAT, 300))
            assertEquals(listOf(repaired), updated.command.content?.assets)
            assertTrue(checkNotNull(cache.chatDrafts.get(CHAT)).revision > revision)
            cache.chatDraftSync.acknowledge(updated, ChatDraftMutationResult(true, 2,
                SharedChatDraftSnapshot(CHAT, 2, 300, ChatDraftContent(markdown(), listOf(repaired)))))

            val draft = checkNotNull(cache.chatDrafts.get(CHAT))
            val outgoing = message("repair-only-outbox", markdown()).copy(body = RichTextBody(markdown(), plainText = "file", assets = listOf(repaired)))
            val receipt = cache.enqueueFromComposer(outgoing, draft.revision, System.currentTimeMillis())
            cache.claimNextOutgoingMessage(System.currentTimeMillis())
            cache.markOutgoingMessageTerminalFailed(receipt.localOrdinal, "rejected", System.currentTimeMillis(), 404)
            cache.chatAssetUploads.prepareReplacement("owner", CHAT, outgoing.clientMsgId)
            val repair = checkNotNull(cache.chatAssetUploads.claimNext(System.currentTimeMillis()))
            cache.chatAssetUploads.complete(ASSET, repair.attempt, repaired.copy(attachment = repaired.attachment.copy(path = "2026/09/outbox-only.txt")))
            assertEquals("", cache.chatDrafts.get(CHAT)?.markdown)
            assertNull(cache.chatDraftSync.nextCommand(CHAT, 400), "outbox repair must not dirty or clear the shared draft")
        }
    }

    @Test
    fun `draft event is durable before cursor and reset preserves unknown mutation`() = runBlocking {
        database { file ->
            cache(file, true) { cache ->
                cache.bindSyncDataset(DATASET)
                val ep = EventProcessor(ImClient("127.0.0.1", 1), cache, ownerUid = "owner")
                ep.processNotify(NotifyPayload(1, NotifyType.CHAT_DRAFT_CHANGED.code,
                    ProtoCodec.encode(ChatDraftChangedPayload(CHAT, 3))))
                assertEquals(1L, cache.getSyncState()?.cursor)
                assertEquals(listOf(CHAT), cache.chatDraftSync.refreshTargets())
            }
            cache(file) { cache ->
                cache.chatDraftSync.applyRemote(remote(2, "stale response"))
                assertNull(cache.chatDrafts.get(CHAT))
                cache.chatDraftSync.applyRemote(remote(3, "current"))
                save(cache, "offline edit", 3)
                val pending = checkNotNull(cache.chatDraftSync.nextCommand(CHAT, 100))
                cache.resetServerProjection(DATASET)
                assertEquals(pending, cache.chatDraftSync.nextCommand(CHAT, 200))
                assertEquals("offline edit", cache.chatDrafts.get(CHAT)?.markdown)
            }
        }
    }

    @Test
    fun `schema five migration preserves rich draft upload and queued source ownership`() = runBlocking {
        database { file ->
            cache(file, true) { cache ->
                cache.chatDrafts.save(ChatDraftSnapshot(CHAT, 1, markdown(), listOf(asset())))
                cache.chatAssetUploads.register(upload().copy(state = ChatAssetUploadState.READY, asset = asset()))
                cache.enqueueFromComposer(message("pending", markdown()).copy(body = RichTextBody(markdown(), plainText = "file", assets = listOf(asset()))), 1, System.currentTimeMillis())
                cache.chatDrafts.save(ChatDraftSnapshot(CHAT, 2, "new local draft"))
            }
            val driver = JdbcSqliteDriver("jdbc:sqlite:${file.path}")
            try {
                driver.execute(null, "DROP TABLE chat_draft_sync", 0)
                AppDatabase.Schema.migrate(driver, 5, 6)
            } finally { driver.close() }
            cache(file) { cache ->
                assertEquals("new local draft", cache.chatDrafts.get(CHAT)?.markdown)
                assertEquals(setOf(SOURCE), cache.chatAssetUploads.retainedSourceIds())
                assertNotNull(cache.getOutgoingMessage(CHAT, "pending"))
                assertEquals(listOf(asset()), cache.chatAssetUploads.outgoingAssets(CHAT, "pending").mapNotNull { it.asset })
                assertNull(cache.chatDraftSync.state(CHAT).remote)
            }
        }
    }

    private fun save(cache: LocalCacheImpl, markdown: String, sharedRevision: Long) = cache.chatDrafts.save(
        ChatDraftSnapshot(CHAT, cache.reserveChatDraftRevision(), markdown, sharedRevision = sharedRevision))
    private fun remote(revision: Long, markdown: String? = null) = SharedChatDraftSnapshot(CHAT, revision, 100,
        markdown?.let { ChatDraftContent(it) })
    private fun markdown() = "[文件](${EmbeddedAsset.uri(ASSET)})"
    private fun asset() = EmbeddedAsset(ASSET, Attachment("2026/09/file.txt", "file.txt", "text/plain", 3))
    private fun upload() = ChatAssetUpload(ASSET, CHAT, SOURCE, 3, "a".repeat(64), "file.txt", "text/plain", false,
        UUID.randomUUID().toString(), System.currentTimeMillis())
    private fun message(id: String, markdown: String) = Message(chatId = CHAT, clientMsgId = id, senderUid = "owner", messageType = MessageType.RICH_TEXT.code,
        body = RichTextBody(markdown, plainText = markdown), timestamp = 100)
    private suspend fun database(block: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("teamtalk-shared-draft-").toFile()
        try { block(directory.resolve("client.db")) } finally { directory.deleteRecursively() }
    }
    private suspend fun cache(file: File? = null, create: Boolean = file == null, block: suspend (LocalCacheImpl) -> Unit) {
        val driver = JdbcSqliteDriver(file?.let { "jdbc:sqlite:${it.path}" } ?: JdbcSqliteDriver.IN_MEMORY)
        if (create) AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        try { block(cache) } finally { cache.close() }
    }
    companion object {
        private const val CHAT = "chat"
        private const val ASSET = "00000000-0000-4000-8000-000000000001"
        private const val SOURCE = "00000000-0000-4000-8000-000000000002"
        private const val DATASET = "00000000-0000-4000-8000-000000000010"
    }
}
