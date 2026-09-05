package com.virjar.tk.server.infra.search

import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.server.domain.message.MessageOperationType
import com.virjar.tk.server.domain.message.MessageArchiveReader
import com.virjar.tk.server.domain.message.MessageProjectionOperation
import com.virjar.tk.server.domain.message.MessageProjectionTarget
import com.virjar.tk.server.infra.storage.MessageStore
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.MessageType
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.document.Field
import org.apache.lucene.document.StringField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.store.FSDirectory
import org.wltea.analyzer.lucene.IKAnalyzer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SearchIndexArchiveRecoveryTest {

    @Test
    fun `empty authoritative archive publishes a valid zero document index`() {
        val root = Files.createTempDirectory("tk-search-empty-archive-")
        val store = MessageStore(root.resolve("messages").toString())
        val indexPath = root.resolve("search")
        try {
            store.init()
            startAndStop(indexPath, store, SearchIndexStartupAction.REBUILT)
            SearchIndex(indexPath.toFile(), store).also { index ->
                index.start()
                assertEquals(SearchIndexStartupAction.VERIFIED, index.startupAudit.action)
                assertEquals(0L, index.startupAudit.authoritativeMessages)
                assertEquals(0, index.search("", emptySet()).total)
                index.stop()
            }
        } finally {
            runCatching { store.close() }
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing index rebuilds latest edited and revoked archive then clean startup only audits`() {
        val root = Files.createTempDirectory("tk-search-archive-rebuild-")
        val store = MessageStore(root.resolve("messages").toString())
        val indexPath = root.resolve("search")
        try {
            store.init()
            val editedSource = message(1L, "editsourceneedle")
            val revokedSource = message(2L, "revokedsourceneedle")
            store.storeTestMessage(editedSource)
            store.storeTestMessage(revokedSource)
            val edited = editedSource.copy(
                body = buildRichTextBody("currenteditedneedle"),
                flags = Message.FLAG_EDITED,
            )
            store.updateMessage(
                edited.chatId,
                edited.serverSeq,
                edited,
                MessageOperationType.EDIT,
                target(edited),
            )
            val revoked = revokedSource.copy(flags = Message.FLAG_REVOKED)
            store.updateMessage(
                revoked.chatId,
                revoked.serverSeq,
                revoked,
                MessageOperationType.REVOKE,
                target(revoked),
            )

            SearchIndex(indexPath.toFile(), store).also { index ->
                index.start()
                assertEquals(SearchIndexStartupAction.REBUILT, index.startupAudit.action)
                assertEquals(2L, index.startupAudit.authoritativeMessages)
                assertEquals(listOf(1L), index.search("currenteditedneedle", setOf(CHAT_ID)).hits.map { it.seq })
                assertTrue(index.search("editsourceneedle", setOf(CHAT_ID)).hits.isEmpty())
                assertTrue(index.search("revokedsourceneedle", setOf(CHAT_ID)).hits.isEmpty())
                assertEquals(1, index.search("", setOf(CHAT_ID)).total, "revoked message must remain a tombstone")
                index.stop()
            }
            val firstGeneration = commitGeneration(indexPath)

            SearchIndex(indexPath.toFile(), store).also { index ->
                index.start()
                assertEquals(SearchIndexStartupAction.VERIFIED, index.startupAudit.action)
                assertEquals(2L, index.startupAudit.authoritativeMessages)
                index.stop()
            }
            assertEquals(firstGeneration, commitGeneration(indexPath), "a clean index must not be rebuilt")

            // 丢失整个派生目录会被确定性地检测到并重建。
            check(indexPath.toFile().deleteRecursively())
            startAndStop(indexPath, store, SearchIndexStartupAction.REBUILT)
        } finally {
            runCatching { store.close() }
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `audit deterministically rebuilds revision missing extra and semantic field corruption`() {
        val root = Files.createTempDirectory("tk-search-archive-corruption-")
        val store = MessageStore(root.resolve("messages").toString())
        val indexPath = root.resolve("search")
        val authority = message(1L, "authoritativeneedle")
        try {
            store.init()
            store.storeTestMessage(authority)
            startAndStop(indexPath, store, SearchIndexStartupAction.REBUILT)

            replaceDocument(indexPath, authority, revision = 2L, text = "authoritativeneedle")
            startAndStop(indexPath, store, SearchIndexStartupAction.REBUILT)

            deleteDocument(indexPath, authority)
            startAndStop(indexPath, store, SearchIndexStartupAction.REBUILT)

            addDocument(indexPath, message(9L, "extraneedle"), revision = 1L, text = "extraneedle")
            startAndStop(indexPath, store, SearchIndexStartupAction.REBUILT)

            replaceDocumentWithExtraChatTerm(indexPath, authority, OTHER_CHAT_ID)
            SearchIndex(indexPath.toFile(), store).also { index ->
                index.start()
                assertEquals(SearchIndexStartupAction.REBUILT, index.startupAudit.action)
                assertTrue(index.search("authoritativeneedle", setOf(OTHER_CHAT_ID)).hits.isEmpty())
                index.stop()
            }

            replaceDocument(indexPath, authority, revision = 1L, text = "wrongsemanticneedle")
            SearchIndex(indexPath.toFile(), store).also { index ->
                index.start()
                assertEquals(SearchIndexStartupAction.REBUILT, index.startupAudit.action)
                assertEquals(
                    listOf(authority.serverSeq),
                    index.search("authoritativeneedle", setOf(CHAT_ID)).hits.map { it.seq },
                )
                assertTrue(index.search("wrongsemanticneedle", setOf(CHAT_ID)).hits.isEmpty())
                index.stop()
            }
        } finally {
            runCatching { store.close() }
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rebuild crosses count page boundary and interrupted switch artifacts converge safely`() {
        val root = Files.createTempDirectory("tk-search-archive-page-switch-")
        val store = MessageStore(root.resolve("messages").toString())
        val indexPath = root.resolve("search")
        val side = root.resolve("search.teamtalk-rebuild-v2")
        val backup = root.resolve("search.teamtalk-backup-v2")
        val firstAuthority = message(1L, "page-keyword-0").copy(clientMsgId = "archive-page-1")
        try {
            store.init()
            repeat(257) { offset ->
                val seq = offset.toLong() + 1L
                store.storeTestMessage(
                    message(seq, "page-keyword-$offset").copy(clientMsgId = "archive-page-$seq"),
                )
            }
            SearchIndex(indexPath.toFile(), store).also { index ->
                index.start()
                assertEquals(SearchIndexStartupAction.REBUILT, index.startupAudit.action)
                assertEquals(257L, index.startupAudit.authoritativeMessages)
                index.stop()
            }

            // 在 side 完成后、active -> backup 之前崩溃：标记证明 side
            // 已通过第二次审计，因此启动时直接发布它，而不是删掉数小时的工作。
            check(indexPath.toFile().copyRecursively(side.toFile()))
            replaceDocument(indexPath, firstAuthority, revision = 1L, text = "stale-active-needle")
            startAndStop(indexPath, store, SearchIndexStartupAction.VERIFIED)
            assertFalse(Files.exists(side, LinkOption.NOFOLLOW_LINKS))
            assertFalse(Files.exists(backup, LinkOption.NOFOLLOW_LINKS))

            // 在 active -> backup 之后、side 不完整时崩溃：丢弃 side 并恢复 backup。
            Files.move(indexPath, backup, StandardCopyOption.ATOMIC_MOVE)
            indexPath.createDirectory() // 下一次进程启动时环境预置的占位目录。
            side.createDirectory()
            side.resolve("partial").writeText("not a complete Lucene index")
            startAndStop(indexPath, store, SearchIndexStartupAction.VERIFIED)
            assertFalse(Files.exists(side, LinkOption.NOFOLLOW_LINKS))
            assertFalse(Files.exists(backup, LinkOption.NOFOLLOW_LINKS))

            // 在 side 构建完成、active -> backup 之后崩溃：优先发布完整的 side。
            Files.move(indexPath, backup, StandardCopyOption.ATOMIC_MOVE)
            check(backup.toFile().copyRecursively(side.toFile()))
            indexPath.createDirectory() // 下一次进程启动时环境预置的占位目录。
            startAndStop(indexPath, store, SearchIndexStartupAction.VERIFIED)
            assertFalse(Files.exists(side, LinkOption.NOFOLLOW_LINKS))
            assertFalse(Files.exists(backup, LinkOption.NOFOLLOW_LINKS))
        } finally {
            runCatching { store.close() }
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `managed sibling cleanup refuses symbolic links without touching their target`() {
        val root = Files.createTempDirectory("tk-search-archive-symlink-")
        val outside = Files.createTempDirectory("tk-search-archive-outside-")
        val store = MessageStore(root.resolve("messages").toString())
        val indexPath = root.resolve("search")
        val side = root.resolve("search.teamtalk-rebuild-v2")
        val sentinel = outside.resolve("sentinel").also { it.writeText("keep") }
        try {
            store.init()
            Files.createSymbolicLink(side, outside)
            assertFailsWith<IllegalStateException> {
                SearchIndex(indexPath.toFile(), store).start()
            }
            assertEquals("keep", Files.readString(sentinel))
            assertTrue(Files.isSymbolicLink(side))
        } finally {
            runCatching { store.close() }
            Files.deleteIfExists(side)
            root.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun `failed authoritative scan drains side writer and replays one terminal failure`() {
        val root = Files.createTempDirectory("tk-search-archive-failed-scan-")
        val indexPath = root.resolve("search")
        val side = root.resolve("search.teamtalk-rebuild-v2")
        val scanFailure = ControlledArchiveFailure()
        val index = SearchIndex(
            indexPath.toFile(),
            MessageArchiveReader { _, _, _ -> throw scanFailure },
        )
        try {
            val first = try {
                index.start()
                error("controlled archive scan should fail")
            } catch (failure: Throwable) {
                failure
            }
            assertSame(scanFailure, first)
            assertFalse(index.isRunning)
            assertFalse(Files.exists(side, LinkOption.NOFOLLOW_LINKS))
            val replayed = try {
                index.stop()
                error("terminal reconciliation failure must be replayed")
            } catch (failure: Throwable) {
                failure
            }
            assertSame(scanFailure, replayed)

            // 失败的 side writer 与目录确实已关闭；另一个持有者可以立即获取
            // 精确的活动路径，而不会观察到泄漏的原生写锁。
            SearchIndex(indexPath.toFile()).also { recovered ->
                recovered.start()
                recovered.stop()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun startAndStop(
        indexPath: Path,
        store: MessageStore,
        expectedAction: SearchIndexStartupAction,
    ) {
        SearchIndex(indexPath.toFile(), store).also { index ->
            index.start()
            assertEquals(expectedAction, index.startupAudit.action)
            index.stop()
        }
    }

    private fun replaceDocument(indexPath: Path, message: Message, revision: Long, text: String?) {
        mutateIndex(indexPath) { writer ->
            writer.updateDocument(
                Term(FIELD_MESSAGE_KEY, MessageProjectionOperation.stableKey(message.chatId, message.serverSeq)),
                buildSearchDocument(message, revision, text),
            )
        }
    }

    private fun addDocument(indexPath: Path, message: Message, revision: Long, text: String?) {
        mutateIndex(indexPath) { writer -> writer.addDocument(buildSearchDocument(message, revision, text)) }
    }

    private fun replaceDocumentWithExtraChatTerm(indexPath: Path, message: Message, extraChatId: String) {
        mutateIndex(indexPath) { writer ->
            val document = buildSearchDocument(message, revision = 1L, text = authoritativeSearchText(message))
            document.add(StringField(FIELD_CHAT_ID, extraChatId, Field.Store.NO))
            writer.updateDocument(
                Term(FIELD_MESSAGE_KEY, MessageProjectionOperation.stableKey(message.chatId, message.serverSeq)),
                document,
            )
        }
    }

    private fun deleteDocument(indexPath: Path, message: Message) {
        mutateIndex(indexPath) { writer ->
            writer.deleteDocuments(
                Term(FIELD_MESSAGE_KEY, MessageProjectionOperation.stableKey(message.chatId, message.serverSeq)),
            )
        }
    }

    private fun mutateIndex(indexPath: Path, block: (IndexWriter) -> Unit) {
        var analyzer: Analyzer? = null
        try {
            val openedAnalyzer = IKAnalyzer(true)
            analyzer = openedAnalyzer
            FSDirectory.open(indexPath).use { directory ->
                IndexWriter(directory, IndexWriterConfig(openedAnalyzer)).use { writer ->
                    block(writer)
                    writer.commit()
                }
            }
        } finally {
            analyzer?.close()
        }
    }

    private fun commitGeneration(indexPath: Path): String =
        FSDirectory.open(indexPath).use { directory ->
            DirectoryReader.open(directory).use { reader ->
                checkNotNull(reader.indexCommit.userData[SEARCH_COMMIT_GENERATION_KEY])
            }
        }

    private fun message(seq: Long, text: String): Message = Message(
        chatId = CHAT_ID,
        clientMsgId = "archive-client-$seq",
        serverSeq = seq,
        senderUid = "archive-sender",
        messageType = MessageType.RICH_TEXT.code,
        timestamp = 1_700_000_000_000L + seq,
        body = buildRichTextBody(text),
    )

    private fun MessageStore.storeTestMessage(message: Message): Long =
        storeMessage(message, message, target(message))

    private fun target(message: Message): MessageProjectionTarget =
        MessageProjectionTarget(chatType = 2, recipientUids = listOf(message.senderUid))

    private companion object {
        const val CHAT_ID = "archive-chat"
        const val OTHER_CHAT_ID = "other-archive-chat"
    }

    private class ControlledArchiveFailure : RuntimeException("controlled archive scan failure")
}
