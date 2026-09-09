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
import com.virjar.tk.shared.agent.HeadlessConfiguration
import com.virjar.tk.shared.repository.ChatDraftRepository
import com.virjar.tk.shared.repository.StagedChatAsset
import com.virjar.tk.shared.repository.UploadSink
import com.virjar.tk.shared.repository.asSmallUploadSource
import com.virjar.tk.shared.repository.createChatAssetSpool
import com.virjar.tk.shared.testkit.FakeRpcInvoker
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*

/** Archive bytes enter a fresh SQLite owner only as an explicitly confirmable composer draft. */
class LocalCacheChatDraftRescueIntegrationTest {
    @Test
    fun `quarantine preview is read only and imported plain draft survives restart until explicit keep`() = runBlocking {
        workspace { workspace ->
            val source = root(workspace, "source")
            cache(source) { cache -> save(cache, "recovered private draft") }
            // An unrelated CHECK violation must not prevent rescuing a completely readable draft
            // graph. The archive preserves the damaged user row, but no user data is imported.
            DriverManager.getConnection("jdbc:sqlite:${File(source, DATABASE).path}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA ignore_check_constraints = ON")
                    statement.execute("INSERT INTO user(uid, username, name, revision) VALUES ('unrelated-corrupt', 'unrelated-corrupt', 'Unrelated', 0)")
                    statement.execute("PRAGMA ignore_check_constraints = OFF")
                    statement.executeQuery("PRAGMA quick_check(1)").use { result ->
                        assertTrue(result.next())
                        assertNotEquals("ok", result.getString(1), "the source must actually fail whole-database integrity checking")
                    }
                }
            }
            val archived = archive(workspace, source, quarantine = true)
            val target = target(workspace)
            val sourceBefore = inventory(source)
            val targetBefore = inventory(target)
            val preview = preview(target, archived)
            assertEquals(targetBefore, inventory(target))
            assertEquals(sourceBefore, inventory(source))
            assertFalse(Json.encodeToString(preview).contains("recovered private draft"))

            val options = options(target, archived)
            HeadlessConfiguration.execute("preview-draft-rescue", options)
            HeadlessConfiguration.execute("import-draft-rescue", options + listOf(
                "--confirm-manifest-sha256", preview.manifestSha256,
                "--expected-composer-revision", preview.composerRevision.toString(),
            ))
            assertEquals(sourceBefore, inventory(source))
            assertEquals(archived.report, LocalCacheArchive.verify(archived.directory))
            cache(target) { cache ->
                val restored = assertNotNull(cache.chatDrafts.get(CHAT))
                assertEquals("recovered private draft", restored.markdown)
                assertEquals(preview.composerRevision + 1, restored.revision)
                assertEquals(0L, restored.sharedRevision, "null would let ordinary-mirror hydration overwrite the rescued text")
                assertTrue(cache.chatDraftSync.state(CHAT).conflict)
                assertTrue(CHAT in cache.chatDraftSync.refreshTargets())
                assertNull(cache.chatDraftSync.nextCommand(CHAT, now()))
                assertNull(cache.getPendingConversationDraft(CHAT))
                assertNull(cache.getUser("unrelated-corrupt"), "recovery copies the selected draft, not unrelated damaged tables")
                val rpc = FakeRpcInvoker().apply { enqueueOk(ProtoCodec.encode(remote(4, "other device"))) }
                ChatDraftRepository(rpc, cache).retryPending().getOrThrow()
                assertEquals(1, rpc.calls.size, "background recovery may read, but must not publish the rescue")
                assertEquals("recovered private draft", cache.chatDrafts.get(CHAT)?.markdown)
                assertTrue(cache.chatDraftSync.state(CHAT).conflict)
            }
            cache(target) { cache ->
                assertTrue(cache.chatDraftSync.state(CHAT).conflict)
                val rpc = FakeRpcInvoker().apply { enqueueOk(ProtoCodec.encode(remote(5, "newer other device"))) }
                ChatDraftRepository(rpc, cache).keepLocal(CHAT).getOrThrow()
                val pending = assertNotNull(cache.chatDraftSync.nextCommand(CHAT, now()))
                assertEquals(5L, pending.command.expectedRevision)
                assertEquals("recovered private draft", pending.command.content?.markdown)
                cache.chatDraftSync.acknowledge(pending, ChatDraftMutationResult(true, 6, remote(6, "recovered private draft")))
                assertFalse(cache.chatDraftSync.state(CHAT).conflict)
                assertFalse(cache.chatDraftSync.state(CHAT).pending)
                assertEquals(6L, cache.chatDrafts.get(CHAT)?.sharedRevision)
            }
        }
    }

    @Test
    fun `owned READY source is preserved as failed and only explicit retry resumes its original upload identity`() = runBlocking {
        workspace { workspace ->
            val source = root(workspace, "source")
            val job = sourceAsset(source)
            val archived = archive(workspace, source)
            val target = target(workspace)
            val preview = preview(target, archived)
            install(target, archived, preview)
            val sourceBytes = readSource(target, job.sourceId)
            assertContentEquals(SOURCE_BYTES, sourceBytes)
            cache(target) { cache ->
                val restored = assertNotNull(cache.chatDrafts.get(CHAT))
                assertEquals(assetMarkdown(), restored.markdown)
                assertEquals(2, restored.mode)
                assertEquals("reply-id", restored.replyToClientMsgId)
                assertEquals(15L, restored.replyToServerSeq)
                assertEquals(listOf(ASSET), restored.pendingAssetIds)
                assertTrue(restored.assets.isEmpty())
                val recovered = assertNotNull(cache.chatDrafts.upload(ASSET))
                assertEquals(ChatAssetUploadState.FAILED, recovered.state)
                assertEquals(job.uploadId, recovered.uploadId)
                assertEquals(job.issuedAt, recovered.issuedAt)
                assertEquals(job.sourceId, recovered.sourceId)
                assertNull(recovered.asset)
                assertTrue(recovered.failure.orEmpty().contains("重试"))
                cache.chatDrafts.recoverUploads()
                assertNull(cache.chatDrafts.claimNext(now()), "startup alone must not resume rescued uploads")
                cache.chatDraftSync.applyRemote(remote(2, "remote"))
                cache.chatDraftSync.resolve(CHAT, true)
                assertNull(cache.chatDraftSync.nextCommand(CHAT, now()), "unready bytes cannot become a shared draft")
            }
            cache(target) { cache ->
                cache.chatDrafts.retry(ASSET)
                val claimed = assertNotNull(cache.chatDrafts.claimNext(now()))
                assertEquals(job.uploadId, claimed.uploadId)
                assertTrue(cache.chatDrafts.complete(ASSET, claimed.attempt, asset()))
                val pending = assertNotNull(cache.chatDraftSync.nextCommand(CHAT, now()))
                assertEquals(listOf(asset()), pending.command.content?.assets)
                assertEquals(2L, pending.command.expectedRevision)
                assertEquals(setOf(job.sourceId), cache.chatDrafts.retainedSourceIds())
                val outgoing = cache.outgoingQueueSnapshot(now())
                assertEquals(0L, outgoing.pendingOrInFlightCount + outgoing.retryWaitCount + outgoing.terminalFailedCount)
            }
            assertContentEquals(SOURCE_BYTES, readSource(target, job.sourceId))
            assertEquals(archived.report, LocalCacheArchive.verify(archived.directory))
        }
    }

    @Test
    fun `choosing the current other device draft removes rescued job ownership and keeps unrelated local work`() = runBlocking {
        workspace { workspace ->
            val source = root(workspace, "source")
            sourceAsset(source)
            val archived = archive(workspace, source)
            val target = target(workspace)
            cache(target) { cache ->
                cache.upsertConversation(Conversation(OTHER_CHAT, 2, chatName = "Other chat"))
                save(cache, "another chat stays", OTHER_CHAT)
                cache.enqueueOutgoingMessage(message("other-outgoing", OTHER_CHAT), now())
            }
            install(target, archived, preview(target, archived))
            cache(target) { cache ->
                val rpc = FakeRpcInvoker().apply { enqueueOk(ProtoCodec.encode(remote(8, "selected remote"))) }
                ChatDraftRepository(rpc, cache).useRemote(CHAT).getOrThrow()
                assertEquals("selected remote", cache.chatDrafts.get(CHAT)?.markdown)
                assertEquals(8L, cache.chatDrafts.get(CHAT)?.sharedRevision)
                assertFalse(cache.chatDraftSync.state(CHAT).conflict)
                assertTrue(cache.chatDrafts.jobs(CHAT).isEmpty())
                assertTrue(cache.chatDrafts.retainedSourceIds().isEmpty())
                assertEquals("another chat stays", cache.chatDrafts.get(OTHER_CHAT)?.markdown)
                assertNotNull(cache.getOutgoingMessage(OTHER_CHAT, "other-outgoing"))
                assertNull(cache.chatDraftSync.nextCommand(CHAT, now()))
            }
            assertEquals(archived.report, LocalCacheArchive.verify(archived.directory))
        }
    }

    @Test
    fun `global revision and target reliable work are rechecked without overwriting any local facts`() = runBlocking {
        workspace { workspace ->
            val source = root(workspace, "source")
            cache(source) { save(it, "rescued") }
            val archived = archive(workspace, source)
            val target = target(workspace)
            val first = preview(target, archived)
            cache(target) { save(it, "unrelated newer draft", OTHER_CHAT) }
            val before = inventory(target)
            rejected("TARGET_COMPOSER_CHANGED") { install(target, archived, first) }
            assertEquals(before, inventory(target))

            val second = preview(target, archived)
            cache(target) { it.enqueueOutgoingMessage(message("unknown-send"), now()) }
            val withOutgoing = inventory(target)
            rejected("TARGET_RELIABLE_WORK_EXISTS") { install(target, archived, second) }
            assertEquals(withOutgoing, inventory(target), "outbox can change while composer clock remains identical")
            cache(target) { cache ->
                assertEquals(second.composerRevision, cache.chatDrafts.maxRevision())
                assertNotNull(cache.getOutgoingMessage(CHAT, "unknown-send"))
                assertNull(cache.chatDrafts.get(CHAT))
            }

            val nonempty = target(workspace, "nonempty-target")
            cache(nonempty) { save(it, "current local draft") }
            rejected("TARGET_DRAFT_NOT_EMPTY") { preview(nonempty, archived) }
            cache(nonempty) { assertEquals("current local draft", it.chatDrafts.get(CHAT)?.markdown) }

            val legacy = target(workspace, "legacy-target")
            cache(legacy) { it.chatDraftSync.ensure(CHAT); it.setConversationDraft(CHAT, "unconfirmed scalar draft") }
            rejected("TARGET_RELIABLE_WORK_EXISTS") { preview(legacy, archived) }
        }
    }

    @Test
    fun `source unknown mutation or consumed message cannot be converted into a new independent draft`() = runBlocking {
        workspace { workspace ->
            for (consumed in listOf(false, true)) {
                val scenario = JvmPrivateDataDirectory.createNew(File(workspace, "source-dependency-$consumed"), workspace).root.toFile()
                val source = root(scenario, "source")
                cache(source) { cache ->
                    cache.chatDraftSync.applyRemote(remote(1, "base"))
                    save(cache, "draft before pending result")
                    if (consumed) {
                        cache.enqueueFromComposer(message("unknown-source-send"), assertNotNull(cache.chatDrafts.get(CHAT)).revision, now())
                        save(cache, "successor while send is unconfirmed")
                    } else assertNotNull(cache.chatDraftSync.nextCommand(CHAT, now()))
                }
                val archived = archive(scenario, source)
                val target = target(scenario)
                val sourceBefore = inventory(source)
                val targetBefore = inventory(target)
                rejected("SOURCE_SYNC_HAS_PENDING_DEPENDENCIES") { preview(target, archived) }
                assertEquals(sourceBefore, inventory(source))
                assertEquals(targetBefore, inventory(target))
                assertEquals(archived.report, LocalCacheArchive.verify(archived.directory))
            }
        }
    }

    @Test
    fun `changed archived source bytes are rejected before installing any draft or source`() = runBlocking {
        workspace { workspace ->
            val source = root(workspace, "source")
            val job = sourceAsset(source)
            val archived = archive(workspace, source)
            val target = target(workspace)
            val preview = preview(target, archived)
            val sourceBefore = inventory(source)
            val targetBefore = inventory(target)
            val archivedBlob = File(archived.directory, "payload/$SPOOL/${job.sourceId}.${job.sha256}.blob")
            val original = archivedBlob.readBytes()
            archivedBlob.writeBytes(original.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
            rejected("SOURCE_DRAFT_UNREADABLE_OR_INVALID") { install(target, archived, preview) }
            assertEquals(sourceBefore, inventory(source))
            assertEquals(targetBefore, inventory(target))
            archivedBlob.writeBytes(original)
            assertEquals(archived.report, LocalCacheArchive.verify(archived.directory))
            install(target, archived, preview)
            assertContentEquals(SOURCE_BYTES, readSource(target, job.sourceId))
        }
    }

    @Test
    fun `owner digest locks and unsupported target schema refuse import without touching payloads`() = runBlocking {
        workspace { workspace ->
            val source = root(workspace, "source")
            cache(source) { save(it, "rescued") }
            val archived = archive(workspace, source)
            val target = target(workspace)
            val preview = preview(target, archived)
            val before = inventory(target)
            rejected("ARCHIVE_CONFIRMATION_MISMATCH") {
                LocalCacheChatDraftRescue.importDraft(target, DATABASE, archived.directory, archived.database, CHAT,
                    changedDigest(preview.manifestSha256), preview.composerRevision)
            }
            JvmClientDataLease.acquire(target).use {
                rejected("CLIENT_LOCK_UNAVAILABLE") { install(target, archived, preview) }
            }
            DriverManager.getConnection("jdbc:sqlite:${File(target, DATABASE).path}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("BEGIN EXCLUSIVE")
                    try { rejected("TARGET_DATABASE_IN_USE") { install(target, archived, preview) } }
                    finally { statement.execute("ROLLBACK") }
                }
            }
            assertEquals(before, inventory(target))

            val other = root(workspace, "other-owner")
            cache(other, OTHER_DATASET) { it.upsertConversation(Conversation(CHAT, 2)) }
            val otherPath = DATABASE.replace(DATASET, OTHER_DATASET)
            val otherBefore = inventory(other)
            rejected("OWNER_MISMATCH") {
                LocalCacheChatDraftRescue.preview(other, otherPath, archived.directory, archived.database, CHAT)
            }
            assertEquals(otherBefore, inventory(other))

            sql(target, "PRAGMA user_version = 999")
            val newerBefore = inventory(target)
            rejected("UNSUPPORTED_TARGET_SCHEMA") { install(target, archived, preview) }
            assertEquals(newerBefore, inventory(target))
        }
    }

    @Test
    fun `Android quarantine archive restores into the same JVM owner with private source bytes and remote only sidecar`() = runBlocking {
        workspace { workspace ->
            val source = root(workspace, "source")
            val job = sourceAsset(source, includeRemoteAsset = true)
            val android = root(workspace, "android")
            val androidDatabase = "databases/cache_e0_${fingerprint}_${DATASET}_${UID}.db.corrupt-rescue"
            file(android, androidDatabase, File(source, DATABASE).readBytes())
            val sourceSpool = File(source, SPOOL)
            sourceSpool.listFiles().orEmpty().forEach { file(android, "no_backup/$SPOOL/${it.name}", it.readBytes()) }
            val archiveDirectory = File(workspace, "android-archive")
            val exported = LocalCacheArchive.export(android, androidDatabase, archiveDirectory, LocalCacheDiagnosticLayout.ANDROID)
            val archived = Archived(archiveDirectory, androidDatabase, exported)
            val before = inventory(android)
            val target = target(workspace)
            install(target, archived, preview(target, archived))
            cache(target) { cache ->
                val draft = assertNotNull(cache.chatDrafts.get(CHAT))
                assertEquals(listOf(ASSET), draft.pendingAssetIds)
                assertEquals(listOf(remoteAsset()), draft.assets, "READY data without a local source stays a remote descriptor")
                assertTrue(cache.chatDraftSync.state(CHAT).conflict)
                assertEquals(job.uploadId, cache.chatDrafts.upload(ASSET)?.uploadId)
            }
            assertContentEquals(SOURCE_BYTES, readSource(target, job.sourceId))
            assertEquals(before, inventory(android))
            assertEquals(exported, LocalCacheArchive.verify(archiveDirectory))
        }
    }

    private data class Archived(val directory: File, val database: String, val report: LocalCacheArchiveReport)

    private suspend fun sourceAsset(root: File, includeRemoteAsset: Boolean = false): ChatAssetUpload {
        val staged: StagedChatAsset = createChatAssetSpool(root, accountOwner).stage(SOURCE_BYTES.asSmallUploadSource())
        val job = ChatAssetUpload(ASSET, CHAT, staged.sourceId, staged.length, staged.sha256, "rescued.txt", "text/plain", false,
            UUID.randomUUID().toString(), now())
        cache(root) { cache ->
            cache.chatDraftSync.ensure(CHAT)
            val text = assetMarkdown() + if (includeRemoteAsset) "\n[remote](${EmbeddedAsset.uri(REMOTE_ASSET)})" else ""
            cache.chatDrafts.save(ChatDraftSnapshot(CHAT, cache.chatDrafts.maxRevision() + 1, text,
                assets = if (includeRemoteAsset) listOf(remoteAsset()) else emptyList(), pendingAssetIds = listOf(ASSET), mode = 2,
                selectionStart = 2, selectionEnd = 5, replyToClientMsgId = "reply-id", replyToServerSeq = 15, sharedRevision = 0))
            cache.chatDrafts.register(job)
            val claimed = assertNotNull(cache.chatDrafts.claimNext(now()))
            assertTrue(cache.chatDrafts.complete(ASSET, claimed.attempt, asset()))
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
        LocalCacheChatDraftRescue.preview(target, DATABASE, archived.directory, archived.database, CHAT)
    private fun install(target: File, archived: Archived, preview: LocalCacheChatDraftRescuePreview) =
        LocalCacheChatDraftRescue.importDraft(target, DATABASE, archived.directory, archived.database, CHAT,
            preview.manifestSha256, preview.composerRevision)
    private fun options(target: File, archived: Archived) = listOf("--cache-root", target.path, "--database", DATABASE,
        "--archive", archived.directory.path, "--source-database", archived.database, "--chat-id", CHAT)
    private fun target(workspace: File, name: String = "target"): File = root(workspace, name).also { root ->
        cache(root) { it.upsertConversation(Conversation(CHAT, 2, chatName = "Rescue destination")) }
    }
    private fun root(workspace: File, name: String): File = JvmPrivateDataDirectory.createNew(File(workspace, name), workspace).root.toFile().also {
        JvmClientDataLease.acquire(it).use { _ -> prepareJvmClientDataVersion(it) }
    }
    private inline fun cache(root: File, dataset: String = DATASET, block: (LocalCache) -> Unit) {
        val cache = createDesktopLocalCache(deployment, dataset, UID, root)
        try { cache.bindSyncDataset(dataset); block(cache) } finally { cache.close() }
    }
    private fun save(cache: LocalCache, text: String, chatId: String = CHAT) {
        cache.chatDraftSync.ensure(chatId)
        cache.chatDrafts.save(ChatDraftSnapshot(chatId, cache.chatDrafts.maxRevision() + 1, text,
            sharedRevision = cache.chatDrafts.get(chatId)?.sharedRevision ?: 0))
    }
    private fun file(root: File, path: String, bytes: ByteArray): File {
        val components = path.split('/')
        return JvmPrivateDataDirectory.openExisting(root).preparePrivateFile(components.dropLast(1), components.last()).also { it.writeBytes(bytes) }
    }
    private suspend fun readSource(root: File, sourceId: String): ByteArray {
        val output = ByteArrayOutputStream()
        createChatAssetSpool(root, accountOwner).open(sourceId).writeTo(UploadSink { bytes, offset, length -> output.write(bytes, offset, length) })
        return output.toByteArray()
    }
    private fun sql(root: File, statement: String) = DriverManager.getConnection("jdbc:sqlite:${File(root, DATABASE).path}").use { connection ->
        connection.createStatement().use { it.execute(statement) }
    }
    private fun inventory(root: File): Map<String, String> = Files.walk(root.toPath()).use { paths ->
        paths.filter { !Files.isDirectory(it, NOFOLLOW_LINKS) }.toList().associate { path ->
            root.toPath().relativize(path).toString() to MessageDigest.getInstance("SHA-256").digest(path.toFile().readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
    private fun changedDigest(digest: String) = (if (digest.first() == '0') "1" else "0") + digest.drop(1)
    private fun rejected(code: String, block: () -> Unit) {
        assertEquals("Local cache draft rescue failed: $code", assertFailsWith<IllegalStateException>(block = block).message)
    }
    private suspend fun workspace(block: suspend (File) -> Unit) {
        val root = Files.createTempDirectory("tk-draft-rescue-").toRealPath().toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun remote(revision: Long, text: String) = SharedChatDraftSnapshot(CHAT, revision, now(), ChatDraftContent(text))
    private fun assetMarkdown() = "[rescued](${EmbeddedAsset.uri(ASSET)})"
    private fun asset() = EmbeddedAsset(ASSET, Attachment("2026/09/rescued.txt", "rescued.txt", "text/plain", SOURCE_BYTES.size.toLong()))
    private fun remoteAsset() = EmbeddedAsset(REMOTE_ASSET, Attachment("2026/09/remote.txt", "remote.txt", "text/plain", 6))
    private fun message(id: String, chatId: String = CHAT) = Message(chatId, id, senderUid = UID, timestamp = now(),
        messageType = MessageType.RICH_TEXT.code, body = RichTextBody("pending message", plainText = "pending message"))
    private fun now() = System.currentTimeMillis()
    private companion object {
        val deployment = DeploymentIdentity.from("draft-rescue.test.example", 5100, "https://draft-rescue.test.example/api")
        val fingerprint = deployment.fingerprint
        const val DATASET = "11111111-1111-4111-8111-111111111111"
        const val OTHER_DATASET = "22222222-2222-4222-8222-222222222222"
        const val UID = "alice"
        const val CHAT = "rescue-chat"
        const val OTHER_CHAT = "other-chat"
        const val ASSET = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val REMOTE_ASSET = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val owner = LocalCacheDiagnosticOwner(fingerprint, DATASET, UID)
        val accountOwner = AccountDataOwner(fingerprint, DATASET, UID)
        val DATABASE = "deployments/$fingerprint/datasets/$DATASET/users/$UID/cache_e0.db"
        val QUARANTINE = "deployments/$fingerprint/datasets/$DATASET/users/$UID.corrupt-rescue/cache_e0.db"
        val SPOOL = "chat-assets/$fingerprint/$DATASET/$UID"
        val SOURCE_BYTES = "private source bytes retained across process death".encodeToByteArray()
    }
}
