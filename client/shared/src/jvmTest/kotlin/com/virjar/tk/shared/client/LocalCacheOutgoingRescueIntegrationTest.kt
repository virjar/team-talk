package com.virjar.tk.shared.client

import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.ChatDraftContent
import com.virjar.tk.protocol.model.ChatDraftMutationResult
import com.virjar.tk.protocol.model.ChatDraftSnapshot as SharedChatDraftSnapshot
import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.payload.MessageAckPayload
import com.virjar.tk.shared.repository.UploadSink
import com.virjar.tk.shared.repository.asSmallUploadSource
import com.virjar.tk.shared.repository.createChatAssetSpool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*

/** Exact archived requests resume through the normal SQLite outbox, never by reconstructing a new message. */
class LocalCacheOutgoingRescueIntegrationTest {
    @Test
    fun `confirmed import resumes an interrupted request with exact payload fingerprint and stable identity`() = runBlocking {
        workspace { workspace ->
            val source = root(workspace, "source")
            cache(source) { cache ->
                cache.enqueueOutgoingMessage(message(), now(), FINGERPRINT)
                assertEquals(ID, assertNotNull(cache.claimNextOutgoingMessage(now())).message.clientMsgId)
            }
            val request = request(source)
            val archived = archive(workspace, source, quarantine = true)
            val target = target(workspace)
            cache(target) { cache ->
                cache.enqueueOutgoingMessage(message("neighbor", OTHER_CHAT), now())
                val claimed = assertNotNull(cache.claimNextOutgoingMessage(now()))
                cache.completeOutgoingMessage(claimed.localOrdinal, ack("neighbor", OTHER_CHAT, 50), now())
            }
            val before = inventory(source)
            val preview = preview(target, archived)
            assertEquals("IN_FLIGHT", preview.sourceState)
            assertTrue(preview.willResumeSending)
            assertEquals(owner, preview.owner)
            assertEquals(ID, preview.clientMsgId)
            assertEquals(request.payload.size.toLong(), preview.payloadBytes.toLong())
            assertFalse(Json.encodeToString(preview).contains(PRIVATE_TEXT))
            assertEquals(1L, preview.outgoingOrdinal)
            val report = install(target, archived, preview)
            assertTrue(report.installedLocalOrdinal > preview.outgoingOrdinal)
            assertRequest(request, request(target))
            assertEquals(before, inventory(source))
            assertEquals(archived.report, LocalCacheArchive.verify(archived.directory))

            cache(target) { cache ->
                val restored = assertNotNull(cache.getOutgoingMessage(CHAT, ID, FINGERPRINT))
                assertEquals(OutgoingMessageState.PENDING, restored.state)
                assertEquals(1L, restored.attemptCount)
                assertEquals(report.installedLocalOrdinal, restored.localOrdinal)
                assertNull(cache.chatDrafts.get(CHAT), "an unrelated outgoing import must not fabricate a composer")
                val completed = CompletableDeferred<Unit>()
                val workerOwner = SupervisorJob()
                val sent = mutableListOf<ByteArray>()
                val queue = SendQueue(UID, cache, MutableStateFlow(ConnectionState.AUTHENTICATED), MessageSender { message ->
                    sent += ProtoCodec.encode(message)
                    ack()
                }, CoroutineScope(workerOwner + Dispatchers.Default), onSent = { _, _ -> completed.complete(Unit) })
                try { withTimeout(5_000) { completed.await() } }
                finally { queue.close(); workerOwner.cancelAndJoin() }
                assertEquals(1, sent.size)
                assertContentEquals(request.payload, sent.single())
                assertEquals(OutgoingMessageState.SUCCESS, cache.getOutgoingMessage(CHAT, ID)?.state)
                assertEquals(9L, cache.findMessage(CHAT, ID)?.serverSeq)
            }
            assertRequest(request, request(target))
            cache(target) { cache ->
                cache.recoverOutgoingState(now())
                assertNull(cache.peekNextOutgoingMessage(), "a restart must not resend the confirmed request")
                assertNotNull(cache.getOutgoingMessage(OTHER_CHAT, "neighbor"))
                assertEquals(9L, cache.getOutgoingMessage(CHAT, ID)?.serverSeq)
            }
        }
    }

    @Test
    fun `a current edited authoritative replay completes imported unknown work without restoring old content`() = runBlocking {
        workspace { workspace ->
            val source = root(workspace, "source")
            cache(source) { cache ->
                cache.enqueueOutgoingMessage(message(), now(), FINGERPRINT)
                cache.claimNextOutgoingMessage(now())
            }
            val archived = archive(workspace, source)
            val target = target(workspace)
            install(target, archived, preview(target, archived))
            val authoritative = message().copy(serverSeq = 9, body = RichTextBody("current edited body", plainText = "current edited body"))
            cache(target) { cache ->
                cache.insertMessage(authoritative)
                cache.recoverOutgoingState(now())
                assertNull(cache.claimNextOutgoingMessage(now()))
                assertEquals(authoritative.body, cache.findMessage(CHAT, ID)?.body)
                assertEquals(OutgoingMessageState.SUCCESS, cache.getOutgoingMessage(CHAT, ID)?.state)
            }
            cache(target) { cache ->
                cache.recoverOutgoingState(now())
                assertNull(cache.peekNextOutgoingMessage())
                assertEquals(authoritative.body, cache.findMessage(CHAT, ID)?.body)
            }
            assertRequest(request(source), request(target))
            assertEquals(archived.report, LocalCacheArchive.verify(archived.directory))
        }
    }

    @Test
    fun `terminal outcomes retain their authority and never become automatic retries`() = runBlocking {
        workspace { workspace ->
            for (failure in listOf(OutgoingFailureCode.REMOTE_REJECTED, OutgoingFailureCode.SESSION_RETIRED)) {
                val scenario = directory(workspace, failure.name)
                val source = root(scenario, "source")
                val terminalCode = if (failure == OutgoingFailureCode.REMOTE_REJECTED) 400 else 499
                cache(source) { cache ->
                    cache.enqueueOutgoingMessage(message(), now(), FINGERPRINT)
                    val claimed = assertNotNull(cache.claimNextOutgoingMessage(now()))
                    cache.markOutgoingMessageTerminalFailed(claimed.localOrdinal, "private diagnostic", now(), terminalCode, failure)
                }
                val original = request(source)
                val archived = archive(scenario, source)
                val target = target(scenario)
                val preview = preview(target, archived)
                assertEquals("TERMINAL_FAILED", preview.sourceState)
                assertFalse(preview.willResumeSending)
                install(target, archived, preview)
                cache(target) { cache ->
                    cache.recoverOutgoingState(now())
                    val receipt = assertNotNull(cache.getOutgoingMessage(CHAT, ID, FINGERPRINT))
                    assertEquals(OutgoingMessageState.TERMINAL_FAILED, receipt.state)
                    assertEquals(failure, receipt.failureCode)
                    assertEquals(terminalCode, receipt.terminalCode)
                    assertEquals(1L, receipt.attemptCount)
                    assertNull(cache.claimNextOutgoingMessage(now()))
                    assertEquals(Message.SEND_STATUS_FAILED, cache.findMessage(CHAT, ID)?.sendStatus)
                    val replacement = cache.replaceTerminalFailure(UID, CHAT, ID, message("replacement"), now())
                    if (failure.allowsFreshClientMsgIdReplacement) {
                        assertNotNull(replacement)
                        assertEquals("replacement", replacement.message.clientMsgId)
                        assertNull(cache.getOutgoingMessage(CHAT, ID))
                    } else {
                        assertNull(replacement, "session retirement is not proof that the old request was rejected")
                        assertTrue(cache.discardTerminalFailure(UID, CHAT, ID))
                    }
                }
                assertRequest(original, request(source))
                assertEquals(archived.report, LocalCacheArchive.verify(archived.directory))
            }
        }
    }

    @Test
    fun `exact consumed draft waits for the resumed message and never clears a newer remote revision`() = runBlocking {
        workspace { workspace ->
            for (newerRemote in listOf(false, true)) {
                val scenario = directory(workspace, "consume-$newerRemote")
                val source = root(scenario, "source")
                cache(source) { cache ->
                    cache.chatDraftSync.applyRemote(remote(4, PRIVATE_TEXT))
                    val draft = assertNotNull(cache.chatDrafts.get(CHAT))
                    cache.enqueueFromComposer(message(), draft.revision, now())
                }
                val archived = archive(scenario, source)
                val target = target(scenario)
                val preview = preview(target, archived)
                install(target, archived, preview)
                cache(target) { cache ->
                    val draft = assertNotNull(cache.chatDrafts.get(CHAT))
                    assertEquals("", draft.markdown)
                    assertEquals(0L, draft.sharedRevision)
                    assertEquals(preview.composerRevision + 1, draft.revision)
                    assertTrue(CHAT in cache.chatDraftSync.refreshTargets())
                    assertNull(cache.chatDraftSync.nextCommand(CHAT, now()))
                    cache.chatDraftSync.applyRemote(remote(if (newerRemote) 5 else 4, if (newerRemote) "new remote draft" else PRIVATE_TEXT))
                    assertEquals(if (newerRemote) "new remote draft" else "", cache.chatDrafts.get(CHAT)?.markdown)
                    assertNull(cache.chatDraftSync.nextCommand(CHAT, now()))
                }
                cache(target) { cache ->
                    val outgoing = assertNotNull(cache.claimNextOutgoingMessage(now()))
                    cache.completeOutgoingMessage(outgoing.localOrdinal, ack(), now())
                    val clear = cache.chatDraftSync.nextCommand(CHAT, now())
                    if (newerRemote) {
                        assertNull(clear)
                        assertEquals("new remote draft", cache.chatDrafts.get(CHAT)?.markdown)
                    } else {
                        assertNotNull(clear)
                        assertEquals(ID, clear.command.consumedClientMsgId)
                        assertEquals(4L, clear.command.expectedRevision)
                        assertNull(clear.command.content)
                        cache.chatDraftSync.acknowledge(clear, ChatDraftMutationResult(true, 5, remote(5)))
                        assertEquals("", cache.chatDrafts.get(CHAT)?.markdown)
                        assertFalse(cache.chatDraftSync.state(CHAT).pending)
                    }
                }
            }
        }
    }

    @Test
    fun `owned attachment sources stay with the imported outbox until ACK or explicit failed replacement`() = runBlocking {
        workspace { workspace ->
            for (replace in listOf(false, true)) {
                val scenario = directory(workspace, "assets-$replace")
                val source = root(scenario, "source")
                val job = assetOutgoing(source)
                val original = request(source)
                val archived = archive(scenario, source)
                val target = target(scenario)
                install(target, archived, preview(target, archived))
                assertContentEquals(SOURCE_BYTES, readSource(target, job.sourceId))
                cache(target) { cache ->
                    assertTrue(cache.chatDrafts.jobs(CHAT).isEmpty(), "outbox-only jobs must not replay into the composer")
                    assertEquals(setOf(job.sourceId), cache.chatDrafts.retainedSourceIds())
                    assertEquals(job.uploadId, cache.chatDrafts.outgoingAssets(CHAT, ID).single().uploadId)
                    cache.chatDrafts.recoverUploads()
                    assertNull(cache.chatDrafts.claimNext(now()), "a message retry must not refresh its immutable attachment")
                    assertFailsWith<IllegalStateException> { cache.chatDrafts.prepareReplacement(UID, CHAT, ID) }
                    val outgoing = assertNotNull(cache.claimNextOutgoingMessage(now()))
                    if (!replace) {
                        cache.completeOutgoingMessage(outgoing.localOrdinal, ack(), now())
                        assertTrue(cache.chatDrafts.retainedSourceIds().isEmpty())
                        assertTrue(cache.chatDrafts.outgoingAssets(CHAT, ID).isEmpty())
                    } else {
                        cache.markOutgoingMessageTerminalFailed(outgoing.localOrdinal, "attachment no longer available", now(), 400)
                        val prepared = cache.chatDrafts.prepareReplacement(UID, CHAT, ID).single()
                        assertNotEquals(job.uploadId, prepared.uploadId)
                        assertEquals(prepared.uploadId, cache.chatDrafts.prepareReplacement(UID, CHAT, ID).single().uploadId)
                        assertRequest(original, request(target), "repair must not change the original send request")
                        val upload = assertNotNull(cache.chatDrafts.claimNext(now()))
                        val fresh = asset("2026/09/reuploaded.txt")
                        assertTrue(cache.chatDrafts.complete(ASSET, upload.attempt, fresh))
                        val replacement = message("replacement", withAsset = fresh)
                        assertNotNull(cache.replaceTerminalFailure(UID, CHAT, ID, replacement, now()))
                        assertNull(cache.getOutgoingMessage(CHAT, ID))
                        assertTrue(cache.chatDrafts.outgoingAssets(CHAT, ID).isEmpty())
                        assertEquals(job.sourceId, cache.chatDrafts.outgoingAssets(CHAT, "replacement").single().sourceId)
                        val retried = assertNotNull(cache.claimNextOutgoingMessage(now()))
                        cache.markOutgoingMessageTerminalFailed(retried.localOrdinal, "explicit failure", now(), 400)
                        assertTrue(cache.discardTerminalFailure(UID, CHAT, "replacement"))
                        assertTrue(cache.chatDrafts.retainedSourceIds().isEmpty())
                    }
                }
                assertRequest(original, request(source))
                assertEquals(archived.report, LocalCacheArchive.verify(archived.directory))
            }
        }
    }

    @Test
    fun `an unknown shared draft mutation or a successor draft is not split from its outgoing dependency`() = runBlocking {
        workspace { workspace ->
            for (successor in listOf(false, true)) {
                val scenario = directory(workspace, "dependency-$successor")
                val source = root(scenario, "source")
                cache(source) { cache ->
                    cache.chatDraftSync.applyRemote(remote(1, if (successor) PRIVATE_TEXT else "base"))
                    if (!successor) {
                        save(cache, PRIVATE_TEXT)
                        assertNotNull(cache.chatDraftSync.nextCommand(CHAT, now()))
                    }
                    cache.enqueueFromComposer(message(), assertNotNull(cache.chatDrafts.get(CHAT)).revision, now())
                    if (successor) save(cache, "successor local draft")
                }
                val archived = archive(scenario, source)
                val target = target(scenario)
                val beforeSource = inventory(source)
                val beforeTarget = inventory(target)
                rejected(if (successor) "SOURCE_OUTGOING_SUCCESSOR_DRAFT" else "SOURCE_SYNC_HAS_PENDING_DEPENDENCIES") {
                    preview(target, archived)
                }
                assertEquals(beforeSource, inventory(source))
                assertEquals(beforeTarget, inventory(target))
                assertEquals(archived.report, LocalCacheArchive.verify(archived.directory))
            }
        }
    }

    @Test
    fun `composer and outgoing high water marks and accepted target identities prevent overwriting newer work`() = runBlocking {
        workspace { workspace ->
            val source = root(workspace, "source")
            cache(source) { it.enqueueOutgoingMessage(message(), now(), FINGERPRINT) }
            val archived = archive(workspace, source)
            val target = target(workspace)
            val beforeEdits = preview(target, archived)
            cache(target) { save(it, "another chat draft", OTHER_CHAT) }
            val withDraft = inventory(target)
            rejected("TARGET_COMPOSER_CHANGED") { install(target, archived, beforeEdits) }
            assertEquals(withDraft, inventory(target))
            val beforeSend = preview(target, archived)
            cache(target) { it.enqueueOutgoingMessage(message("new-neighbor", OTHER_CHAT), now()) }
            val withOutgoing = inventory(target)
            rejected("TARGET_OUTGOING_CHANGED") { install(target, archived, beforeSend) }
            assertEquals(withOutgoing, inventory(target))
            val beforeAccepted = preview(target, archived)
            val accepted = message().copy(serverSeq = 42, body = RichTextBody("current accepted text", plainText = "current accepted text"))
            cache(target) { it.insertMessage(accepted) }
            val withAccepted = inventory(target)
            rejected("TARGET_MESSAGE_EXISTS") { install(target, archived, beforeAccepted) }
            assertEquals(withAccepted, inventory(target))
            cache(target) { cache ->
                assertEquals(accepted.body, cache.findMessage(CHAT, ID)?.body)
                assertNotNull(cache.getOutgoingMessage(OTHER_CHAT, "new-neighbor"))
                assertEquals("another chat draft", cache.chatDrafts.get(OTHER_CHAT)?.markdown)
            }
        }
    }

    @Test
    fun `successful receipts and noncanonical archived payload bytes cannot enter a new send queue`() = runBlocking {
        workspace { workspace ->
            val invalidSources = listOf(
                "payload" to "SOURCE_OUTGOING_PAYLOAD_INVALID",
                "successful" to "SOURCE_OUTGOING_SUCCESS",
                "terminal-without-failure" to "SOURCE_OUTGOING_STATE_INVALID",
                "blob-identity" to "SOURCE_SQL_VALUE_INVALID",
            )
            for ((kind, expectedFailure) in invalidSources) {
                val scenario = directory(workspace, "unusable-$kind")
                val source = root(scenario, "source")
                cache(source) { cache ->
                    cache.enqueueOutgoingMessage(message(), now(), FINGERPRINT)
                    if (kind == "successful" || kind == "terminal-without-failure") {
                        val outgoing = assertNotNull(cache.claimNextOutgoingMessage(now()))
                        if (kind == "successful") cache.completeOutgoingMessage(outgoing.localOrdinal, ack(), now())
                        else cache.markOutgoingMessageTerminalFailed(outgoing.localOrdinal, "rejected", now(), 400,
                            OutgoingFailureCode.REMOTE_REJECTED)
                    }
                }
                if (kind != "successful") {
                    val original = request(source)
                    connection(source).use { db ->
                        val sql = when (kind) {
                            "payload" -> "UPDATE outgoing_message SET payload=? WHERE chat_id=? AND client_msg_id=?"
                            "terminal-without-failure" -> "UPDATE outgoing_message SET failure_code=NULL WHERE chat_id=? AND client_msg_id=?"
                            else -> "UPDATE outgoing_message SET chat_id=CAST(chat_id AS BLOB) WHERE chat_id=? AND client_msg_id=?"
                        }
                        db.prepareStatement(sql).use { statement ->
                            var parameter = 1
                            if (kind == "payload") statement.setBytes(parameter++, original.payload + byteArrayOf(0))
                            statement.setString(parameter++, CHAT); statement.setString(parameter, ID)
                            assertEquals(1, statement.executeUpdate())
                        }
                    }
                }
                val archived = archive(scenario, source)
                val target = target(scenario)
                val beforeSource = inventory(source)
                val beforeTarget = inventory(target)
                rejected(expectedFailure) { preview(target, archived) }
                assertEquals(beforeSource, inventory(source))
                assertEquals(beforeTarget, inventory(target))
                assertEquals(archived.report, LocalCacheArchive.verify(archived.directory))
            }
        }
    }

    private data class Archived(val directory: File, val database: String, val report: LocalCacheArchiveReport)
    private data class Request(val payload: ByteArray, val fingerprint: ByteArray?)

    private suspend fun assetOutgoing(root: File): ChatAssetUpload {
        val staged = createChatAssetSpool(root, accountOwner).stage(SOURCE_BYTES.asSmallUploadSource())
        val job = ChatAssetUpload(ASSET, CHAT, staged.sourceId, staged.length, staged.sha256, "source.txt", "text/plain", false,
            UUID.randomUUID().toString(), now())
        cache(root) { cache ->
            cache.chatDraftSync.ensure(CHAT)
            cache.chatDrafts.save(ChatDraftSnapshot(CHAT, cache.chatDrafts.maxRevision() + 1, assetMarkdown(), pendingAssetIds = listOf(ASSET), sharedRevision = 0))
            cache.chatDrafts.register(job)
            val claimed = assertNotNull(cache.chatDrafts.claimNext(now()))
            assertTrue(cache.chatDrafts.complete(ASSET, claimed.attempt, asset()))
            cache.enqueueFromComposer(message(withAsset = asset()), assertNotNull(cache.chatDrafts.get(CHAT)).revision, now())
        }
        return job
    }
    private fun archive(workspace: File, source: File, quarantine: Boolean = false): Archived {
        val destination = File(workspace, "archive")
        val selected = if (quarantine) QUARANTINE else DATABASE
        if (quarantine) file(source, selected, File(source, DATABASE).readBytes())
        val report = if (quarantine) LocalCacheArchive.export(source, selected, destination)
            else LocalCacheNamespaceArchive.export(source, owner, destination)
        return Archived(destination, selected, report)
    }
    private fun preview(target: File, archived: Archived) =
        LocalCacheOutgoingRescue.preview(target, DATABASE, archived.directory, archived.database, CHAT, ID)
    private fun install(target: File, archived: Archived, preview: LocalCacheOutgoingRescuePreview) =
        LocalCacheOutgoingRescue.importMessage(target, DATABASE, archived.directory, archived.database, CHAT, ID,
            preview.manifestSha256, preview.composerRevision, preview.outgoingOrdinal)
    private fun request(root: File): Request = connection(root).use { db ->
        db.prepareStatement("SELECT payload, request_fingerprint FROM outgoing_message WHERE chat_id=? AND client_msg_id=?").use { statement ->
            statement.setString(1, CHAT); statement.setString(2, ID)
            statement.executeQuery().use { rows -> assertTrue(rows.next()); Request(rows.getBytes(1), rows.getBytes(2)) }
        }
    }
    private fun assertRequest(expected: Request, actual: Request, message: String? = null) {
        assertContentEquals(expected.payload, actual.payload, message)
        assertContentEquals(expected.fingerprint, actual.fingerprint, message)
    }
    private fun target(workspace: File): File = root(workspace, "target").also { root ->
        cache(root) { it.upsertConversation(Conversation(CHAT, 2, chatName = "Outgoing rescue")) }
    }
    private fun directory(parent: File, name: String) = JvmPrivateDataDirectory.createNew(File(parent, name), parent).root.toFile()
    private fun root(workspace: File, name: String): File = directory(workspace, name).also {
        JvmClientDataLease.acquire(it).use { _ -> prepareJvmClientDataVersion(it) }
    }
    private inline fun cache(root: File, block: (LocalCache) -> Unit) {
        val cache = createDesktopLocalCache(deployment, DATASET, UID, root)
        try { cache.bindSyncDataset(DATASET); block(cache) } finally { cache.close() }
    }
    private fun save(cache: LocalCache, text: String, chatId: String = CHAT) {
        cache.chatDraftSync.ensure(chatId)
        cache.chatDrafts.save(ChatDraftSnapshot(chatId, cache.chatDrafts.maxRevision() + 1, text,
            sharedRevision = cache.chatDrafts.get(chatId)?.sharedRevision ?: 0))
    }
    private fun file(root: File, path: String, bytes: ByteArray): File {
        val parts = path.split('/')
        return JvmPrivateDataDirectory.openExisting(root).preparePrivateFile(parts.dropLast(1), parts.last()).also { it.writeBytes(bytes) }
    }
    private suspend fun readSource(root: File, sourceId: String): ByteArray {
        val output = ByteArrayOutputStream()
        createChatAssetSpool(root, accountOwner).open(sourceId).writeTo(UploadSink { bytes, offset, length -> output.write(bytes, offset, length) })
        return output.toByteArray()
    }
    private fun connection(root: File): Connection = DriverManager.getConnection("jdbc:sqlite:${File(root, DATABASE).path}")
    private fun inventory(root: File): Map<String, String> = Files.walk(root.toPath()).use { paths ->
        paths.filter { !Files.isDirectory(it, NOFOLLOW_LINKS) }.toList().associate { path ->
            root.toPath().relativize(path).toString() to MessageDigest.getInstance("SHA-256").digest(path.toFile().readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
    private fun rejected(code: String, block: () -> Unit) {
        assertEquals("Local cache outgoing rescue failed: $code", assertFailsWith<IllegalStateException>(block = block).message)
    }
    private suspend fun workspace(block: suspend (File) -> Unit) {
        val root = Files.createTempDirectory("tk-outgoing-rescue-").toRealPath().toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun remote(revision: Long, markdown: String? = null) = SharedChatDraftSnapshot(CHAT, revision, now(), markdown?.let { ChatDraftContent(it) })
    private fun assetMarkdown() = "[source](${EmbeddedAsset.uri(ASSET)})"
    private fun asset(path: String = "2026/09/source.txt") = EmbeddedAsset(ASSET, Attachment(path, "source.txt", "text/plain", SOURCE_BYTES.size.toLong()))
    private fun message(id: String = ID, chatId: String = CHAT, withAsset: EmbeddedAsset? = null) = Message(chatId, id, senderUid = UID,
        timestamp = 1_700_000_000_000, messageType = MessageType.RICH_TEXT.code,
        body = if (withAsset == null) RichTextBody(PRIVATE_TEXT, plainText = PRIVATE_TEXT)
            else RichTextBody(assetMarkdown(), plainText = "source", assets = listOf(withAsset)))
    private fun ack(id: String = ID, chatId: String = CHAT, sequence: Long = 9) = MessageAckPayload(chatId, id, sequence, 0)
    private fun now() = System.currentTimeMillis()
    private companion object {
        val deployment = DeploymentIdentity.from("outgoing-rescue.test.example", 5100, "https://outgoing-rescue.test.example/api")
        val fingerprint = deployment.fingerprint
        const val DATASET = "11111111-1111-4111-8111-111111111111"
        const val UID = "alice"
        const val CHAT = "rescue-chat"
        const val OTHER_CHAT = "other-chat"
        const val ID = "original-client-message"
        const val ASSET = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val PRIVATE_TEXT = "private original message declaration"
        val owner = LocalCacheDiagnosticOwner(fingerprint, DATASET, UID)
        val accountOwner = AccountDataOwner(fingerprint, DATASET, UID)
        val DATABASE = "deployments/$fingerprint/datasets/$DATASET/users/$UID/cache_e0.db"
        val QUARANTINE = "deployments/$fingerprint/datasets/$DATASET/users/$UID.corrupt-rescue/cache_e0.db"
        val SOURCE_BYTES = "frozen outgoing source bytes".encodeToByteArray()
        val FINGERPRINT = ByteArray(32) { (it + 7).toByte() }
    }
}
