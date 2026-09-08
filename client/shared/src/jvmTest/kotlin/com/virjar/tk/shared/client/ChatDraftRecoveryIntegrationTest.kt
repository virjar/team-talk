package com.virjar.tk.shared.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.ReliableCommandContract
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.payload.MessageAckPayload
import com.virjar.tk.shared.database.AppDatabase
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

/** 真实 SQLite 关闭重开验证，覆盖草稿、上传结果和消息准入之间的崩溃边界。 */
class ChatDraftRecoveryIntegrationTest {
    @Test
    fun `markdown sidecar reply and upload identity survive restart without naked mirror`() = database { file ->
        val snapshot = draft().copy(mode = 1, selectionStart = 4, selectionEnd = 7,
            replyToClientMsgId = "old-message", replyToServerSeq = 120)
        val original = upload()
        cache(file, true) {
            it.chatDrafts.save(snapshot)
            it.chatDrafts.register(original)
            assertNull(it.getPendingConversationDraft("chat")?.draft)
            assertNotNull(it.chatDrafts.claimNext(System.currentTimeMillis()))
        }
        cache(file) {
            assertEquals(snapshot, it.chatDrafts.get("chat"))
            it.chatDrafts.recoverUploads()
            val retry = checkNotNull(it.chatDrafts.claimNext(System.currentTimeMillis()))
            assertEquals(original.uploadId, retry.uploadId)
            assertEquals(original.issuedAt, retry.issuedAt)
            assertEquals(original.sourceId, retry.sourceId)
            assertEquals(2L, retry.attempt)
            it.chatDrafts.complete(retry.assetId, retry.attempt, asset())
        }
        cache(file) {
            val restored = checkNotNull(it.chatDrafts.get("chat"))
            assertEquals(listOf(asset()), restored.assets)
            assertTrue(restored.pendingAssetIds.isEmpty())
            assertEquals(original.sourceId, it.chatDrafts.jobs().single().sourceId, "READY keeps source ownership")
        }
    }

    @Test
    fun `removed reference rejects late READY and never recreates draft`() = database { file ->
        cache(file, true) {
            it.chatDrafts.save(draft())
            it.chatDrafts.register(upload())
            val inFlight = checkNotNull(it.chatDrafts.claimNext(System.currentTimeMillis()))
            it.chatDrafts.save(ChatDraftSnapshot("chat", 2, "继续写字"))
            assertFalse(it.chatDrafts.complete(ID, inFlight.attempt, asset()))
        }
        cache(file) {
            assertEquals("继续写字", it.chatDrafts.get("chat")?.markdown)
            assertTrue(it.chatDrafts.jobs().isEmpty())
        }
    }

    @Test
    fun `outbox admission consumes exact revision and preserves not yet placed import`() = database { file ->
        cache(file, true) {
            it.chatDrafts.save(draft().copy(assets = listOf(asset()), pendingAssetIds = emptyList()))
            it.chatDrafts.register(upload().copy(state = ChatAssetUploadState.READY, asset = asset()))
            val unplaced = upload().copy(assetId = id(9), sourceId = id(10), uploadId = id(11))
            it.chatDrafts.register(unplaced)
            val first = it.enqueueFromComposer(message("send", withAsset = true), 1, 1)
            assertEquals(first, it.enqueueFromComposer(message("send", withAsset = true), 1, 2))
            assertEquals("", it.chatDrafts.get("chat")?.markdown)
            assertEquals(listOf(unplaced), it.chatDrafts.jobs())
        }
        cache(file) {
            assertNotNull(it.getOutgoingMessage("chat", "send"))
            assertEquals("", it.chatDrafts.get("chat")?.markdown)
            assertNull(it.getPendingConversationDraft("chat")?.draft)
            assertEquals(id(9), it.chatDrafts.jobs().single().assetId)
            assertEquals(ID, it.chatDrafts.outgoingAssets("chat", "send").single().assetId)
            val sending = checkNotNull(it.claimNextOutgoingMessage(System.currentTimeMillis()))
            it.completeOutgoingMessage(sending.localOrdinal, MessageAckPayload("chat", "send", 1, 0), System.currentTimeMillis())
            assertTrue(it.chatDrafts.outgoingAssets("chat", "send").isEmpty())
            assertEquals(setOf(id(10)), it.chatDrafts.retainedSourceIds())
        }
    }

    @Test
    fun `newer draft survives enqueue of an older composer snapshot`() = database { file ->
        cache(file, true) {
            it.chatDrafts.save(ChatDraftSnapshot("chat", 1, "old"))
            it.chatDrafts.save(ChatDraftSnapshot("chat", 2, "new"))
            it.enqueueFromComposer(message("old-send"), 1, 1)
        }
        cache(file) {
            assertEquals("new", it.chatDrafts.get("chat")?.markdown)
            assertEquals("new", it.getPendingConversationDraft("chat")?.draft)
            assertNotNull(it.getOutgoingMessage("chat", "old-send"))
        }
    }

    @Test
    fun `failed outbox transaction retains draft assets and mirror`() = database { file ->
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.path}")
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver, DEFAULT_LOCAL_OUTBOX_LIMITS.copy(activeOutgoingCount = 1))
        try {
            cache.enqueueOutgoingMessage(message("occupy"), 1)
            cache.chatDrafts.save(draft())
            cache.chatDrafts.register(upload())
            assertFailsWith<LocalOutboxCapacityExceededException> { cache.enqueueFromComposer(message("blocked"), 1, 1) }
        } finally { cache.close() }
        cache(file) {
            assertEquals(draft(), it.chatDrafts.get("chat"))
            assertEquals(1, it.chatDrafts.jobs().size)
            assertNull(it.getOutgoingMessage("chat", "blocked"))
        }
    }

    @Test
    fun `expired READY keeps source and explicit retry persists a fresh upload intent`() = database { file ->
        val old = upload().copy(issuedAt = System.currentTimeMillis() - ReliableCommandContract.RETRY_HORIZON_MILLIS - 10_000,
            state = ChatAssetUploadState.READY, asset = asset())
        cache(file, true) {
            it.chatDrafts.save(draft().copy(assets = listOf(asset()), pendingAssetIds = emptyList()))
            it.chatDrafts.register(old)
            it.chatDrafts.expireUploads(System.currentTimeMillis())
            assertTrue(checkNotNull(it.chatDrafts.get("chat")).assets.isEmpty())
            assertEquals(listOf(ID), it.chatDrafts.get("chat")?.pendingAssetIds)
            it.chatDrafts.retry(ID)
        }
        cache(file) {
            val fresh = it.chatDrafts.jobs().single()
            assertEquals(old.sourceId, fresh.sourceId)
            assertNotEquals(old.uploadId, fresh.uploadId)
            assertTrue(fresh.issuedAt > old.issuedAt)
            assertEquals(ChatAssetUploadState.QUEUED, fresh.state)
            assertNull(it.getOutgoingMessage("chat", "send"))
        }
    }

    @Test
    fun `empty tombstones stay bounded without losing persisted revision clock`() = database { file ->
        cache(file, true) {
            repeat(1_100) { index -> it.chatDrafts.save(ChatDraftSnapshot("chat-$index", index + 1L)) }
            assertNull(it.chatDrafts.get("chat-0"))
            assertEquals(1_100L, it.chatDrafts.maxRevision())
            it.chatDrafts.save(ChatDraftSnapshot("chat-0", 1, "stale"))
            assertNull(it.chatDrafts.get("chat-0"))
        }
        cache(file) { assertEquals(1_100L, it.chatDrafts.maxRevision()) }
    }

    @Test
    fun `schema three upgrade preserves existing conversation draft and outgoing`() = database { file ->
        cache(file, true) {
            it.setConversationDraft("chat", "原有草稿")
            it.enqueueOutgoingMessage(message("previous"), 1)
        }
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.path}")
        try {
            listOf("outgoing_chat_asset", "chat_composer_clock", "chat_composer_draft", "chat_asset_upload").forEach { driver.execute(null, "DROP TABLE $it", 0) }
            AppDatabase.Schema.migrate(driver, 3, 5)
        } finally { driver.close() }
        cache(file) {
            assertEquals("原有草稿", it.getPendingConversationDraft("chat")?.draft)
            assertNotNull(it.getOutgoingMessage("chat", "previous"))
            assertTrue(it.chatDrafts.jobs().isEmpty())
        }
    }

    @Test
    fun `schema four upgrade retains rich draft upload source and identity`() = database { file ->
        val job = upload()
        cache(file, true) { it.chatDrafts.save(draft()); it.chatDrafts.register(job) }
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.path}")
        try {
            driver.execute(null, "DROP TABLE outgoing_chat_asset", 0)
            AppDatabase.Schema.migrate(driver, 4, 5)
        } finally { driver.close() }
        cache(file) {
            assertEquals(draft(), it.chatDrafts.get("chat"))
            assertEquals(job, it.chatDrafts.jobs().single())
            assertEquals(setOf(job.sourceId), it.chatDrafts.retainedSourceIds())
        }
    }

    @Test
    fun `offline expired READY and stale descriptor are rejected without consuming source`() = database { file ->
        cache(file, true) {
            val old = upload().copy(state = ChatAssetUploadState.READY, asset = asset(),
                issuedAt = System.currentTimeMillis() - ReliableCommandContract.RETRY_HORIZON_MILLIS - 10_000)
            it.chatDrafts.save(draft().copy(assets = listOf(asset()), pendingAssetIds = emptyList()))
            it.chatDrafts.register(old)
            assertFailsWith<IllegalStateException> { it.enqueueFromComposer(message("expired", true), 1, System.currentTimeMillis()) }
            assertNotNull(it.chatDrafts.get("chat"))
            assertEquals(setOf(old.sourceId), it.chatDrafts.retainedSourceIds())
            it.chatDrafts.retry(ID)
            val active = checkNotNull(it.chatDrafts.claimNext(System.currentTimeMillis()))
            it.chatDrafts.complete(ID, active.attempt, asset().copy(attachment = asset().attachment.copy(path = "2026/09/new.txt")))
            assertFailsWith<IllegalStateException> { it.enqueueFromComposer(message("stale", true), 1, System.currentTimeMillis()) }
            assertNull(it.getOutgoingMessage("chat", "expired"))
            assertNull(it.getOutgoingMessage("chat", "stale"))
        }
    }

    @Test
    fun `uncertain outgoing cannot refresh its assets and explicit discard releases sources`() = database { file ->
        cache(file, true) {
            it.chatDrafts.save(draft().copy(assets = listOf(asset()), pendingAssetIds = emptyList()))
            val original = upload().copy(state = ChatAssetUploadState.READY, asset = asset())
            it.chatDrafts.register(original)
            val outgoing = it.enqueueFromComposer(message("uncertain", true), 1, System.currentTimeMillis())
            assertFailsWith<IllegalStateException> { it.chatDrafts.prepareReplacement("owner", "chat", "uncertain") }
            assertEquals(original, it.chatDrafts.outgoingAssets("chat", "uncertain").single())
            it.claimNextOutgoingMessage(System.currentTimeMillis())
            it.markOutgoingMessageTerminalFailed(outgoing.localOrdinal, "rejected", System.currentTimeMillis(), 404)
            val prepared = it.chatDrafts.prepareReplacement("owner", "chat", "uncertain").single()
            val again = it.chatDrafts.prepareReplacement("owner", "chat", "uncertain").single()
            assertNotEquals(original.uploadId, prepared.uploadId)
            assertEquals(prepared.uploadId, again.uploadId)
            assertTrue(it.discardTerminalFailure("owner", "chat", "uncertain"))
            assertTrue(it.chatDrafts.retainedSourceIds().isEmpty())
        }
    }

    @Test
    fun `code literal asset URI stays in ordinary draft mirror`() = database { file ->
        cache(file, true) {
            val text = "代码 `" + EmbeddedAsset.uri(ID) + "`\n```\n" + EmbeddedAsset.uri(ID) + "\n```"
            it.chatDrafts.save(ChatDraftSnapshot("chat", 1, text))
            assertEquals(text, it.getPendingConversationDraft("chat")?.draft)
        }
    }

    private fun draft() = ChatDraftSnapshot("chat", 1, "正文 [文件](${EmbeddedAsset.uri(ID)})", pendingAssetIds = listOf(ID))
    private fun asset() = EmbeddedAsset(ID, Attachment("2026/09/test.txt", "test.txt", "text/plain", 3))
    private fun upload() = ChatAssetUpload(ID, "chat", id(2), 3, "a".repeat(64), "test.txt", "text/plain", false, id(3), System.currentTimeMillis())
    private fun message(id: String, withAsset: Boolean = false): Message {
        val markdown = if (withAsset) draft().markdown else "message"
        return Message(chatId = "chat", clientMsgId = id, senderUid = "owner", messageType = MessageType.RICH_TEXT.code,
            timestamp = 1, body = RichTextBody(markdown, plainText = "message", assets = if (withAsset) listOf(asset()) else emptyList()))
    }
    private fun database(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("teamtalk-chat-drafts-").toFile()
        try { block(directory.resolve("client.db")) } finally { directory.deleteRecursively() }
    }
    private fun cache(file: File, create: Boolean = false, block: (LocalCacheImpl) -> Unit) {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.path}")
        if (create) AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        try { block(cache) } finally { cache.close() }
    }
    private fun id(value: Long) = UUID(0, value).toString()
    companion object { private const val ID = "00000000-0000-4000-8000-000000000001" }
}
