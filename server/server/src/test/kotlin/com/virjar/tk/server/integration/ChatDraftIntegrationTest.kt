package com.virjar.tk.server.integration

import com.virjar.tk.protocol.*
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.protocol.model.*
import com.virjar.tk.protocol.payload.InvokePayload
import com.virjar.tk.protocol.rpc.gen.ChatDraftRpcContract
import com.virjar.tk.server.domain.attachment.*
import com.virjar.tk.server.domain.chat.ChatAccessDeniedException
import com.virjar.tk.server.domain.chat.ChatLifecycleGate
import com.virjar.tk.server.domain.command.*
import com.virjar.tk.server.domain.conversation.*
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.server.infra.db.*
import com.virjar.tk.server.infra.db.repository.ExposedChatDraftAttachmentReferences
import com.virjar.tk.server.infra.db.repository.ExposedChatDraftRepository
import com.virjar.tk.server.protocol.dispatcher.RpcDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

/** Real PostgreSQL, durable events, FileStore retention and the normal message acceptance path. */
class ChatDraftIntegrationTest {
    companion object { @JvmField @RegisterExtension val ext = IntegrationTestExtension() }
    private val ctx get() = ext.env
    private val now = AtomicLong(System.currentTimeMillis())
    private val lifecycle = ChatLifecycleGate()
    private val assetLifecycle = AttachmentLifecycleGate()
    private val repository = ExposedChatDraftRepository()
    private fun service(uow: PgUnitOfWork = ctx.pgUnitOfWork, catalog: AttachmentCatalog = ctx.fileStore,
        repo: ChatDraftRepository = repository) = ChatDraftService(
        repo, ctx.conversationRepo, uow, lifecycle, assetLifecycle,
        AttachmentService(ctx.fileStore, ctx.attachmentAccess), catalog,
        ChatDraftMessageLookup(ctx.messageStore::findCommittedMessage), now::get,
    )
    private fun id() = UUID.randomUUID().toString()
    private suspend fun fixture(): Triple<String, String, String> {
        val owner = ctx.registerUser(uniqueUsername("draft-owner"))
        val peer = ctx.registerUser(uniqueUsername("draft-peer"))
        return Triple(owner, peer, ctx.chatService.createPersonalChat(owner, peer).chatId)
    }
    private fun command(chatId: String, revision: Long, content: ChatDraftContent? = null, consumed: String? = null) =
        ChatDraftCommand(chatId, revision, id(), now.get(), content, consumed)
    private fun asset(uid: String): EmbeddedAsset {
        val path = ctx.fileStore.store(uid, "ready.txt", "text/plain", "durable source".byteInputStream())
        return EmbeddedAsset(id(), checkNotNull(ctx.fileStore.getAttachment(path)))
    }
    private fun content(asset: EmbeddedAsset) = ChatDraftContent("正文 [ready.txt](${EmbeddedAsset.uri(asset.assetId)})", listOf(asset), 2)
    private fun events(uid: String) = transaction(ctx.database) {
        SyncEvents.selectAll().where { (SyncEvents.uid eq uid) and (SyncEvents.eventType eq NotifyType.CHAT_DRAFT_CHANGED.code) }
            .orderBy(SyncEvents.streamSeq).map { ProtoCodec.decode(ChatDraftChangedPayload, it[SyncEvents.payload]) }
    }

    @Test fun `CAS replies retain current and clear tombstones reject delayed writes and unknown expired retries`() = runTest {
        val (owner, _, chat) = fixture()
        val initial = command(chat, 0, ChatDraftContent("设备A"))
        val first = service().mutate(owner, initial)
        assertTrue(first.applied); assertEquals(1, events(owner).size)
        assertEquals(first, service(ExposedPgUnitOfWork(ctx.database, onEventsCommitted = {})).mutate(owner, initial))
        val second = service().mutate(owner, command(chat, 1, ChatDraftContent("设备B")))
        val replay = service().mutate(owner, initial)
        assertTrue(replay.applied); assertEquals(1L, replay.operationRevision); assertEquals(second.current, replay.current)
        val delayed = command(chat, 1, ChatDraftContent("迟到旧稿"))
        assertEquals(ChatDraftMutationResult(false, 0, second.current), service().mutate(owner, delayed))
        val cleared = service().mutate(owner, command(chat, 2))
        assertNull(cleared.current.content); assertEquals(3L, cleared.current.revision)
        assertEquals(cleared.current, service().mutate(owner, delayed).current)
        assertEquals(3, events(owner).size)
        assertFailsWith<ReliableCommandConflictException> { service().mutate(owner, initial.copy(content = ChatDraftContent("替换字节"))) }
        now.addAndGet(ReliableCommandPolicy.RETRY_HORIZON_MILLIS + 1)
        assertFailsWith<ReliableCommandExpiredException> { service().mutate(owner, initial) }
        assertEquals(cleared.current, service().get(owner, chat))
    }

    @Test fun `concurrent devices have one winner and old string mirrors cannot replace independent drafts`() = runTest {
        val (owner, _, chat) = fixture()
        ctx.conversationService.setDraft(owner, chat, "旧端普通草稿")
        assertEquals("旧端普通草稿", service().get(owner, chat).content?.markdown)
        val commands = listOf(command(chat, 0, ChatDraftContent("A")), command(chat, 0, ChatDraftContent("B")))
        val results = commands.map { async(Dispatchers.IO) { service().mutate(owner, it) } }.awaitAll()
        assertEquals(1, results.count { it.applied })
        val current = service().get(owner, chat)
        ctx.conversationService.setDraft(owner, chat, null)
        ctx.conversationService.setDraft(owner, chat, "迟到旧字符串")
        assertEquals(current, service().get(owner, chat))
    }

    @Test fun `clear releases active draft capacity while retaining its revision tombstone`() = runTest {
        val (owner, _, chat) = fixture()
        val peer = ctx.registerUser(uniqueUsername("draft-capacity-peer"))
        val secondChat = ctx.chatService.createPersonalChat(owner, peer).chatId
        val limited = service(repo = ExposedChatDraftRepository(maxActiveDrafts = 1))
        limited.mutate(owner, command(chat, 0, ChatDraftContent("占用一个槽")))
        assertFailsWith<IllegalStateException> { limited.mutate(owner, command(secondChat, 0, ChatDraftContent("第二个草稿"))) }
        limited.mutate(owner, command(chat, 1))
        assertTrue(limited.mutate(owner, command(secondChat, 0, ChatDraftContent("清空后可以保存"))).applied)
        assertEquals(2L, limited.get(owner, chat).revision)
        assertNull(limited.get(owner, chat).content)
        assertFalse(limited.mutate(owner, command(chat, 0, ChatDraftContent("迟到稿"))).applied)
    }

    @Test fun `explicit conversation deletion clears rich state and fences delayed first publication`() = runTest {
        val (owner, peer, chat) = fixture()
        val asset = asset(owner)
        service().mutate(owner, command(chat, 0, content(asset)))
        ctx.conversationService.deleteConversation(owner, chat)
        val cleared = service().get(owner, chat)
        assertNull(cleared.content); assertEquals(2L, cleared.revision)
        assertTrue(ExposedChatDraftAttachmentReferences(ctx.database).getReferencedPaths(setOf(asset.attachment.path)).isEmpty())
        assertEquals(cleared, service().mutate(owner, command(chat, 1, ChatDraftContent("迟到富稿"))).current)
        ctx.conversationService.setDraft(peer, chat, "legacy only")
        ctx.conversationService.deleteConversation(peer, chat)
        val peerCleared = service().get(peer, chat)
        assertEquals(1L, peerCleared.revision); assertNull(peerCleared.content)
        assertFalse(service().mutate(peer, command(chat, 0, ChatDraftContent("迟到首次发布"))).applied)
        assertEquals(listOf(1L), events(peer).map { it.revision })
    }

    @Test fun `removed member may clear only their owned draft without recovering read or write permission`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("draft-group-owner"))
        val member = ctx.registerUser(uniqueUsername("draft-group-member"))
        val outsider = ctx.registerUser(uniqueUsername("draft-group-outsider"))
        val chat = ctx.chatService.createGroup(id(), "私有草稿清理", null, owner, listOf(member)).chatId
        val asset = asset(member)
        service().mutate(member, command(chat, 0, content(asset)))
        ctx.chatService.removeMember(owner, chat, member)
        assertFailsWith<ChatAccessDeniedException> { service().get(member, chat) }
        assertFalse(ctx.attachmentAccess.canRead(member, asset.attachment.path))
        assertFailsWith<ChatAccessDeniedException> { service().mutate(member, command(chat, 1, ChatDraftContent("无权再写"))) }
        assertFailsWith<ChatAccessDeniedException> { service().mutate(outsider, command(chat, 0)) }
        val clear = command(chat, 1)
        val result = service().mutate(member, clear)
        assertTrue(result.applied); assertNull(result.current.content); assertEquals(2L, result.current.revision)
        assertTrue(ExposedChatDraftAttachmentReferences(ctx.database).getReferencedPaths(setOf(asset.attachment.path)).isEmpty())
        assertEquals(result, service().mutate(member, clear))
        assertFailsWith<ChatAccessDeniedException> { service().get(member, chat) }
    }

    @Test fun `removed member cannot read nonempty draft through stale clear or a successful historical receipt`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("draft-clear-owner"))
        val member = ctx.registerUser(uniqueUsername("draft-clear-member"))
        val chat = ctx.chatService.createGroup(id(), "清理不能恢复读权限", null, owner, listOf(member)).chatId
        val asset = asset(member)
        service().mutate(member, command(chat, 0, content(asset)))
        val historicalClear = command(chat, 1)
        service().mutate(member, historicalClear)
        val newer = service().mutate(member, command(chat, 2, content(asset))).current
        ctx.chatService.removeMember(owner, chat, member)
        val staleClear = command(chat, 0)
        assertFailsWith<ChatAccessDeniedException> { service().mutate(member, staleClear) }
        assertFailsWith<ChatAccessDeniedException> { service().mutate(member, historicalClear) }
        assertNull(ctx.pgUnitOfWork.read { repository.receipt(transaction, member, staleClear.operationId) })
        assertEquals(newer, ctx.pgUnitOfWork.read { repository.get(transaction, member, chat) })
        assertTrue(ExposedChatDraftAttachmentReferences(ctx.database).getReferencedPaths(setOf(asset.attachment.path)).isNotEmpty())
        val clear = command(chat, newer.revision)
        assertTrue(service().mutate(member, clear).applied)
        assertNull(service().mutate(member, clear).current.content)
    }

    @Test fun `ready assets are private retained beyond staging TTL and released only by clear`() = runTest {
        val (owner, peer, chat) = fixture()
        val asset = asset(owner)
        val saved = service().mutate(owner, command(chat, 0, content(asset))).current
        assertEquals(content(asset), service().get(owner, chat).content)
        assertTrue(ctx.attachmentAccess.canRead(owner, asset.attachment.path))
        assertFalse(ctx.attachmentAccess.canRead(peer, asset.attachment.path))
        assertTrue(events(peer).isEmpty())
        val draftReferences = ExposedChatDraftAttachmentReferences(ctx.database)
        val retention = AttachmentRetentionService(ctx.fileStore, object : AttachmentReferences {
            override fun getChatIds(path: String) = emptySet<String>()
            override fun getReferencedPaths(paths: Set<String>) = draftReferences.getReferencedPaths(paths) + ctx.messageStore.getReferencedAttachmentPaths(paths)
        }, assetLifecycle, wallClockMillis = { now.get() + 8L * 24 * 60 * 60 * 1000 })
        retention.cleanupExpiredUnreferenced()
        assertNotNull(ctx.fileStore.getAttachment(asset.attachment.path))
        val unavailableCatalog = object : AttachmentCatalog by ctx.fileStore {
            override fun getAttachment(path: String): Attachment? = null
        }
        assertFalse(service(catalog = unavailableCatalog).get(owner, chat).assetsAvailable)
        service().mutate(owner, command(chat, saved.revision))
        retention.cleanupExpiredUnreferenced()
        assertNull(ctx.fileStore.getAttachment(asset.attachment.path))
    }

    @Test fun `send consumption requires accepted identity and never clears a newer remote draft`() = runTest {
        val (owner, peer, chat) = fixture()
        val asset = asset(owner); val body = content(asset)
        val saved = service().mutate(owner, command(chat, 0, body)).current
        val clientMsgId = id()
        val consume = command(chat, saved.revision, consumed = clientMsgId)
        assertFailsWith<ReliableCommandConflictException> { service().mutate(owner, consume) }
        assertEquals(saved, service().get(owner, chat))
        assertNull(ctx.pgUnitOfWork.read { repository.receipt(transaction, owner, consume.operationId) })
        val message = Message(chat, clientMsgId, 0, owner, MessageType.RICH_TEXT.code, now.get(), body = buildRichTextBody(body.markdown, body.assets))
        ctx.messageService.sendMessage(owner, message)
        val cleared = service().mutate(owner, consume)
        assertTrue(cleared.applied); assertNull(cleared.current.content)
        assertTrue(ctx.attachmentAccess.canRead(peer, asset.attachment.path))
        val newDraft = service().mutate(owner, command(chat, 2, ChatDraftContent("发送期间的新稿"))).current
        assertEquals(newDraft, service().mutate(owner, consume).current)
        assertEquals(1L, ctx.messageService.getHistory(owner, chat, 0, 10).single().serverSeq)
    }

    @Test fun `foreign or rebound assets and mismatched reply identities are rejected without changing the draft`() = runTest {
        val (owner, peer, chat) = fixture()
        val foreign = asset(peer)
        val messageId = id()
        val sent = ctx.messageService.sendMessage(peer, Message(chat, messageId, 0, peer, MessageType.RICH_TEXT.code, now.get(),
            body = buildRichTextBody(content(foreign).markdown, listOf(foreign))))
        assertTrue(ctx.attachmentAccess.canRead(owner, foreign.attachment.path))
        assertFailsWith<IllegalArgumentException> { service().mutate(owner, command(chat, 0, content(foreign))) }
        val own = asset(owner)
        val forged = own.copy(attachment = own.attachment.copy(size = own.attachment.size + 1))
        assertFailsWith<IllegalArgumentException> { service().mutate(owner, command(chat, 0, content(forged))) }
        assertFailsWith<IllegalArgumentException> {
            service().mutate(owner, command(chat, 0, ChatDraftContent("回复", replyToClientMsgId = id(), replyToServerSeq = sent)))
        }
        val reply = ChatDraftContent("回复", replyToClientMsgId = messageId, replyToServerSeq = sent)
        assertEquals(reply, service().mutate(owner, command(chat, 0, reply)).current.content)
        val outsider = ctx.registerUser(uniqueUsername("draft-outsider"))
        assertFailsWith<ChatAccessDeniedException> { service().get(outsider, chat) }
        assertFailsWith<ChatAccessDeniedException> { service().mutate(outsider, command(chat, 0, ChatDraftContent("越权"))) }
    }

    @Test fun `draft references receipt mirror and event roll back together`() = runTest {
        val (owner, _, chat) = fixture()
        val asset = asset(owner)
        val operation = command(chat, 0, content(asset))
        val failing = ExposedPgUnitOfWork(ctx.database, onEventsCommitted = {}, hooks = PgUnitOfWorkHooks { stage ->
            if (stage == PgUnitOfWorkStage.AFTER_EVENT_FLUSH_BEFORE_COMMIT && ChatDraftCommands.selectAll().where {
                (ChatDraftCommands.uid eq owner) and (ChatDraftCommands.operationId eq operation.operationId)
            }.any()) error("injected draft transaction failure")
        })
        assertFailsWith<IllegalStateException> { service(failing).mutate(owner, operation) }
        assertEquals(0L, service().get(owner, chat).revision)
        assertTrue(ExposedChatDraftAttachmentReferences(ctx.database).getReferencedPaths(setOf(asset.attachment.path)).isEmpty())
        assertTrue(events(owner).isEmpty())
        assertNull(ctx.pgUnitOfWork.read { repository.receipt(transaction, owner, operation.operationId) })
        assertTrue(service().mutate(owner, operation).applied)
    }

    @Test fun `RPC supports conflicts as results and refuses the new contract to old negotiated clients`() = runTest {
        val (owner, _, chat) = fixture()
        val dispatcher = RpcDispatcher(ctx.rpcStubRegistry)
        suspend fun invoke(method: Int, bytes: ByteArray, version: ProtocolVersion = ProtocolVersion(0, 2)) = dispatcher.dispatch(
            owner, "draft-device", 1, "draft-session", InvokePayload(1, ChatDraftRpcContract.SERVICE, method, bytes), version)
        val save = command(chat, 0, ChatDraftContent("RPC"))
        assertEquals(0, invoke(ChatDraftRpcContract.M_MUTATE, ChatDraftRpcContract.encodeMutate(save)).status)
        val conflict = invoke(ChatDraftRpcContract.M_MUTATE, ChatDraftRpcContract.encodeMutate(command(chat, 0, ChatDraftContent("迟到"))))
        assertEquals(0, conflict.status)
        val decoded = ProtoCodec.decode(ChatDraftMutationResult, assertNotNull(conflict.payload))
        assertFalse(decoded.applied); assertEquals("RPC", decoded.current.content?.markdown)
        assertEquals(0, invoke(ChatDraftRpcContract.M_GET, ChatDraftRpcContract.encodeGet(chat)).status)
        assertNotEquals(0, invoke(ChatDraftRpcContract.M_GET, ChatDraftRpcContract.encodeGet(chat), ProtocolVersion(0, 1)).status)
    }
}
