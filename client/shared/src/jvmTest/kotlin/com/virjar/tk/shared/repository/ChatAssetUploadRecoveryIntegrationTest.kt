package com.virjar.tk.shared.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.sun.net.httpserver.HttpServer
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.protocol.http.AttachmentUploadIdentity
import com.virjar.tk.protocol.http.ATTACHMENT_UPLOAD_ID_HEADER
import com.virjar.tk.protocol.http.ATTACHMENT_UPLOAD_ISSUED_AT_HEADER
import com.virjar.tk.protocol.http.UploadResult
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.payload.MessageAckPayload
import com.virjar.tk.shared.client.*
import com.virjar.tk.shared.database.AppDatabase
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

/** 真实 HTTP + 私有文件 + SQLite 重开；不让 stub 掩盖上传请求身份或源文件交接。 */
class ChatAssetUploadRecoveryIntegrationTest {
    @Test
    fun `failed HTTP upload resumes exact identity after restart and only explicit send consumes source`() = runBlocking {
        fixture(firstStatus = 503) { f ->
            var cache = f.cache(create = true)
            var uploads = f.coordinator(cache)
            try {
                cache.chatDrafts.save(f.draft())
                val original = uploads.registerPrepared("chat", ID, "frozen-source".encodeToByteArray().asSmallUploadSource(), "draft.txt", "text/plain", false)
                eventually { cache.chatDrafts.jobs().singleOrNull()?.state == ChatAssetUploadState.QUEUED && f.requests.size == 1 }
                uploads.close()
                cache.close()
                cache = f.cache()
                uploads = f.coordinator(cache)
                eventually { cache.chatDrafts.jobs().singleOrNull()?.state == ChatAssetUploadState.READY }
                assertEquals(2, f.requests.size)
                assertEquals(f.requests[0], f.requests[1])
                assertEquals(original.uploadId, cache.chatDrafts.jobs().single().uploadId)
                assertEquals(1, f.spool().list().size)
                assertNull(cache.getOutgoingMessage("chat", "send"))
                val restored = checkNotNull(cache.chatDrafts.get("chat"))
                cache.enqueueFromComposer(Message(chatId = "chat", clientMsgId = "send", senderUid = "owner",
                    messageType = MessageType.RICH_TEXT.code, timestamp = 1,
                    body = buildRichTextBody(restored.markdown, restored.assets)), restored.revision, 1)
                assertTrue(cache.chatDrafts.jobs().isEmpty(), "Outbox-owned imports must not replay into the composer")
                assertEquals(1, f.spool().list().size)
                uploads.close()
                cache.close()
                cache = f.cache()
                uploads = f.coordinator(cache)
                assertEquals(1, f.spool().list().size, "Unacknowledged outbox retains sources after restart")
                val sending = checkNotNull(cache.claimNextOutgoingMessage(System.currentTimeMillis()))
                cache.completeOutgoingMessage(sending.localOrdinal, MessageAckPayload("chat", "send", 1, 0), System.currentTimeMillis())
                eventually { f.spool().list().isEmpty() }
                assertNotNull(cache.getOutgoingMessage("chat", "send"))
            } finally { uploads.close(); cache.close() }
        }
    }

    @Test
    fun `HTTP 410 explicitly retires old identity and user retry uploads preserved bytes with new identity`() = runBlocking {
        fixture(firstStatus = 410) { f ->
            val cache = f.cache(create = true)
            val uploads = f.coordinator(cache)
            try {
                cache.chatDrafts.save(f.draft())
                val original = uploads.registerPrepared("chat", ID, "frozen-source".encodeToByteArray().asSmallUploadSource(), "draft.txt", "text/plain", false)
                eventually { cache.chatDrafts.jobs().singleOrNull()?.state == ChatAssetUploadState.FAILED }
                assertEquals(original.sourceId, cache.chatDrafts.jobs().single().sourceId)
                uploads.retry(ID)
                eventually { cache.chatDrafts.jobs().singleOrNull()?.state == ChatAssetUploadState.READY }
                assertEquals(2, f.requests.size)
                assertNotEquals(f.requests[0].first, f.requests[1].first)
                assertEquals(original.sourceId, cache.chatDrafts.jobs().single().sourceId)
                assertNull(cache.getOutgoingMessage("chat", "send"))
            } finally { uploads.close(); cache.close() }
        }
    }

    @Test
    fun `deleting an in flight reference cancels only that upload and next asset still finishes`() = runBlocking {
        fixture(firstStatus = 200) { f ->
            val cache = f.cache(create = true)
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            var calls = 0
            val transport = object : PlatformFileTransport {
                override suspend fun upload(url: String, bearerToken: String, identity: AttachmentUploadIdentity,
                    plan: MultipartUploadPlan, source: UploadSource): String {
                    calls++
                    if (calls == 1) {
                        entered.complete(Unit)
                        try { awaitCancellation() } finally { cancelled.complete(Unit) }
                    }
                    source.writeTo(UploadSink { _, _, _ -> })
                    return Json.encodeToString(UploadResult(Attachment("2026/09/08/draft.txt", "draft.txt", "text/plain", 13)))
                }
                override suspend fun downloadTo(url: String, bearerToken: String, expectedBytes: Long, sink: DownloadSink) = error("unused")
                override fun close() = Unit
            }
            val uploads = ChatAssetUploadCoordinator(cache.chatDrafts,
                FileRepository("http://127.0.0.1:${f.server.address.port}", "owner", { SessionHttpCredentials("owner", "fixture-token") }, transport),
                f.spool(), MutableStateFlow(ConnectionState.AUTHENTICATED))
            try {
                cache.chatDrafts.save(f.draft())
                val first = uploads.registerPrepared("chat", ID, "frozen-source".encodeToByteArray().asSmallUploadSource(), "draft.txt", "text/plain", false)
                withTimeout(5_000) { entered.await() }
                val next = "00000000-0000-4000-8000-000000000002"
                cache.chatDrafts.save(ChatDraftSnapshot("chat", 2, "[next](${EmbeddedAsset.uri(next)})", pendingAssetIds = listOf(next)))
                withTimeout(5_000) { cancelled.await() }
                uploads.registerPrepared("chat", next, "frozen-source".encodeToByteArray().asSmallUploadSource(), "draft.txt", "text/plain", false)
                eventually { cache.chatDrafts.jobs().singleOrNull()?.state == ChatAssetUploadState.READY }
                assertEquals(next, cache.chatDrafts.jobs().single().assetId)
                eventually { f.spool().list().none { it.sourceId == first.sourceId } }
                assertEquals(2, calls)
            } finally { uploads.close(); cache.close() }
        }
    }

    @Test
    fun `explicit failed replacement reuploads source without changing old immutable payload then transfers ownership`() = runBlocking {
        fixture(firstStatus = 503) { f ->
            val cache = f.cache(create = true)
            val uploads = f.coordinator(cache)
            try {
                cache.chatDrafts.save(f.draft())
                uploads.registerPrepared("chat", ID, "frozen-source".encodeToByteArray().asSmallUploadSource(), "draft.txt", "text/plain", false)
                eventually { cache.chatDrafts.jobs().singleOrNull()?.state == ChatAssetUploadState.READY }
                val draft = checkNotNull(cache.chatDrafts.get("chat"))
                val original = Message(chatId = "chat", clientMsgId = "failed", senderUid = "owner", messageType = MessageType.RICH_TEXT.code,
                    timestamp = 1, body = buildRichTextBody(draft.markdown, draft.assets))
                val admitted = cache.enqueueFromComposer(original, draft.revision, System.currentTimeMillis())
                cache.claimNextOutgoingMessage(System.currentTimeMillis())
                cache.markOutgoingMessageTerminalFailed(admitted.localOrdinal, "file expired", System.currentTimeMillis(), 404)
                val readyReplacement = uploads.prepareFailedReplacement("owner", "failed", original.copy(clientMsgId = "replacement"))
                assertEquals(3, f.requests.size)
                assertNotEquals(f.requests[1].first, f.requests[2].first)
                assertEquals(admitted.message, cache.getOutgoingMessage("chat", "failed")?.message)
                val replacement = checkNotNull(cache.replaceTerminalFailure("owner", "chat", "failed", readyReplacement, System.currentTimeMillis()))
                assertTrue(cache.chatDrafts.outgoingAssets("chat", "failed").isEmpty())
                assertEquals(1, cache.chatDrafts.outgoingAssets("chat", "replacement").size)
                assertTrue(cache.chatDrafts.jobs().isEmpty())
                assertEquals(1, f.spool().list().size)
                cache.claimNextOutgoingMessage(System.currentTimeMillis())
                cache.completeOutgoingMessage(replacement.localOrdinal, MessageAckPayload("chat", "replacement", 1, 0), System.currentTimeMillis())
                eventually { f.spool().list().isEmpty() }
            } finally { uploads.close(); cache.close() }
        }
    }

    private suspend fun fixture(firstStatus: Int, block: suspend (Fixture) -> Unit) {
        val root = Files.createTempDirectory("teamtalk-upload-recovery-").toFile()
        val privateRoot = JvmPrivateDataDirectory.createNew(File(root, "private"), root).root.toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val f = Fixture(privateRoot, server)
        val count = AtomicInteger()
        server.createContext("/api/v1/files/upload") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            f.requests += exchange.requestHeaders.getFirst(ATTACHMENT_UPLOAD_ID_HEADER) to
                exchange.requestHeaders.getFirst(ATTACHMENT_UPLOAD_ISSUED_AT_HEADER)
            if (count.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(firstStatus, -1)
                exchange.close()
            } else {
                val response = Json.encodeToString(UploadResult(Attachment("2026/09/08/draft.txt", "draft.txt", "text/plain", 13))).encodeToByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
        }
        server.start()
        try { block(f) } finally { server.stop(0); root.deleteRecursively() }
    }

    private class Fixture(val root: File, val server: HttpServer) {
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        private val owner = AccountDataOwner("a".repeat(64), "00000000-0000-4000-8000-000000000099", "owner")
        fun spool() = createChatAssetSpool(root, owner)
        fun cache(create: Boolean = false): LocalCacheImpl {
            val driver = JdbcSqliteDriver("jdbc:sqlite:${File(root, "cache.db").path}")
            if (create) AppDatabase.Schema.create(driver)
            return LocalCacheImpl(driver)
        }
        fun coordinator(cache: LocalCacheImpl) = ChatAssetUploadCoordinator(cache.chatDrafts,
            FileRepository("http://127.0.0.1:${server.address.port}", "owner", { SessionHttpCredentials("owner", "fixture-token") }),
            spool(), MutableStateFlow(ConnectionState.AUTHENTICATED))
        fun draft() = ChatDraftSnapshot("chat", 1, "[file](${EmbeddedAsset.uri(ID)})", pendingAssetIds = listOf(ID))
    }

    private suspend fun eventually(predicate: () -> Boolean) = withTimeout(10_000) {
        while (!predicate()) delay(10)
    }
    companion object { private const val ID = "00000000-0000-4000-8000-000000000001" }
}
