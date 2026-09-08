package com.virjar.tk.server.infra.search

import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.ContentSearchRequest
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.server.domain.message.MAX_MESSAGE_SEARCH_CHAT_FILTERS
import com.virjar.tk.server.domain.message.MAX_MESSAGE_SEARCH_COLLECTION_WINDOW
import com.virjar.tk.server.domain.message.MAX_MESSAGE_SEARCH_QUERY_CHARS
import com.virjar.tk.server.domain.message.MessageOperationType
import com.virjar.tk.server.domain.message.MessageProjectionOperation
import com.virjar.tk.server.domain.message.MessageProjectionTarget
import com.virjar.tk.server.infra.storage.MessageStore
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StringField
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.store.FSDirectory
import org.wltea.analyzer.lucene.IKAnalyzer
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchIndexAttachmentTest {
    @Test
    fun `attachment filename and type belong to same descriptor and special characters stay literal`() {
        val root = Files.createTempDirectory("tk-attachment-fields-").toFile()
        val index = SearchIndex(root)
        try {
            index.start()
            val mixed = richMessage(
                listOf(
                    attachment("meeting-图片.PNG", "image/png"),
                    attachment("different-document.pdf", "application/pdf"),
                    attachment("literal*?.pdf", "application/pdf"),
                ),
            )
            assertTrue(index.applyProjection(operation(mixed, 1), authoritativeSearchText(mixed)))
            assertEquals(1, index.searchAttachments("MEETING", setOf(CHAT), ContentSearchRequest.FILE_TYPE_IMAGE, 1).hits.size)
            assertTrue(index.searchAttachments("meeting", setOf(CHAT), ContentSearchRequest.FILE_TYPE_OTHER, 1).hits.isEmpty())
            assertEquals(1, index.searchAttachments("图片", setOf(CHAT), 0, 1).hits.size)
            assertEquals(1, index.searchAttachments("*?", setOf(CHAT), 0, 1).hits.size)
            assertTrue(index.searchAttachments("missing*", setOf(CHAT), 0, 1).hits.isEmpty())
            assertTrue(index.searchAttachments("", emptySet(), 0, 1).hits.isEmpty())
            assertTrue(index.searchAttachments("meeting", setOf("other-chat"), 0, 1).hits.isEmpty())
        } finally {
            runCatching { index.stop() }
            root.deleteRecursively()
        }
    }

    @Test
    fun `attachment projection edits revokes and exact replay retain revision across reopen`() {
        val root = Files.createTempDirectory("tk-attachment-revisions-").toFile()
        var index = SearchIndex(root)
        try {
            index.start()
            val first = richMessage(listOf(attachment("original.png", "image/png")))
            val original = operation(first, 1)
            val changed = richMessage(listOf(attachment("replacement.pdf", "application/pdf")))
            val edited = operation(changed, 2, MessageOperationType.EDIT)
            assertTrue(index.applyProjection(original, authoritativeSearchText(first)))
            assertTrue(index.applyProjection(edited, authoritativeSearchText(changed)))
            assertFalse(index.applyProjection(original, authoritativeSearchText(first)))
            assertTrue(index.searchAttachments("original", setOf(CHAT), 0, 10).hits.isEmpty())
            assertEquals(2L, index.searchAttachments("replacement", setOf(CHAT), 0, 10).hits.single().revision)
            index.stop()
            index = SearchIndex(root).also { it.start() }
            assertFalse(index.applyProjection(edited, authoritativeSearchText(changed)))
            assertEquals(2L, index.searchAttachments("replacement", setOf(CHAT), 0, 10).hits.single().revision)
            assertTrue(index.applyProjection(operation(changed, 3, MessageOperationType.REVOKE), null))
            assertFalse(index.applyProjection(edited, authoritativeSearchText(changed)))
            assertTrue(index.searchAttachments("", setOf(CHAT), 0, 10).hits.isEmpty())
            assertTrue(index.search("*", setOf(CHAT)).hits.isEmpty())
        } finally {
            runCatching { index.stop() }
            root.deleteRecursively()
        }
    }

    @Test
    fun `attachment queries bound collector and filters before acquiring index resources`() {
        val root = Files.createTempDirectory("tk-attachment-bounds-").toFile()
        val index = SearchIndex(root)
        try {
            assertFailsWith<IllegalArgumentException> { index.searchAttachments("x".repeat(MAX_MESSAGE_SEARCH_QUERY_CHARS + 1), setOf(CHAT), 0, 1) }
            assertFailsWith<IllegalArgumentException> { index.searchAttachments("query\u0000", setOf(CHAT), 0, 1) }
            assertFailsWith<IllegalArgumentException> { index.searchAttachments("query", setOf(CHAT), 0, 0) }
            assertFailsWith<IllegalArgumentException> { index.searchAttachments("query", setOf(CHAT), 0, MAX_MESSAGE_SEARCH_COLLECTION_WINDOW) }
            assertFailsWith<IllegalArgumentException> { index.searchAttachments("query", setOf(CHAT), 5, 1) }
            assertFailsWith<IllegalArgumentException> { index.searchAttachments("query", setOf("invalid chat"), 0, 1) }
            assertFailsWith<IllegalArgumentException> {
                index.searchAttachments("query", (0..MAX_MESSAGE_SEARCH_CHAT_FILTERS).mapTo(linkedSetOf()) { "chat-$it" }, 0, 1)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `legacy schema and missing or poisoned attachment fields rebuild from current authoritative messages`() {
        val root = Files.createTempDirectory("tk-attachment-rebuild-")
        val store = MessageStore(root.resolve("messages").toString())
        val indexPath = root.resolve("search")
        try {
            store.init()
            val source = richMessage(listOf(attachment("archive-image.png", "image/png")))
            store.storeMessage(source, source, target())
            verifyArchive(indexPath, store, SearchIndexStartupAction.REBUILT)
            mutateDocument(indexPath, source, legacySchema = true) { document ->
                document.removeFields(FIELD_ATTACHMENT_MANIFEST)
                ATTACHMENT_SEARCH_TYPES.forEach { document.removeFields(attachmentNameField(it)) }
            }
            verifyArchive(indexPath, store, SearchIndexStartupAction.REBUILT)
            mutateDocument(indexPath, source) { it.removeFields(attachmentNameField(ContentSearchRequest.FILE_TYPE_IMAGE)) }
            verifyArchive(indexPath, store, SearchIndexStartupAction.REBUILT)
            mutateDocument(indexPath, source) {
                it.add(StringField(attachmentNameField(ContentSearchRequest.FILE_TYPE_OTHER), "poisoned-secret.pdf", Field.Store.NO))
            }
            verifyArchive(indexPath, store, SearchIndexStartupAction.REBUILT)
            verifyArchive(indexPath, store, SearchIndexStartupAction.VERIFIED)

            val revoked = source.copy(flags = Message.FLAG_REVOKED)
            store.updateMessage(CHAT, 1L, revoked, MessageOperationType.REVOKE, target())
            SearchIndex(indexPath.toFile(), store).also { index ->
                try {
                    index.start()
                    assertEquals(SearchIndexStartupAction.REBUILT, index.startupAudit.action)
                    assertTrue(index.searchAttachments("archive", setOf(CHAT), 0, 10).hits.isEmpty())
                    assertFalse(index.applyProjection(operation(source, 1), authoritativeSearchText(source)))
                } finally {
                    index.stop()
                }
            }
        } finally {
            runCatching { store.close() }
            root.toFile().deleteRecursively()
        }
    }

    private fun verifyArchive(path: Path, store: MessageStore, expected: SearchIndexStartupAction) {
        val index = SearchIndex(path.toFile(), store)
        try {
            index.start()
            assertEquals(expected, index.startupAudit.action)
            assertEquals(1L, index.startupAudit.authoritativeMessages)
            assertEquals(1, index.searchAttachments("ARCHIVE-IMAGE", setOf(CHAT), ContentSearchRequest.FILE_TYPE_IMAGE, 10).hits.size)
            assertTrue(index.searchAttachments("poisoned-secret", setOf(CHAT), 0, 10).hits.isEmpty())
        } finally {
            index.stop()
        }
    }

    private fun mutateDocument(path: Path, message: Message, legacySchema: Boolean = false, mutation: (Document) -> Unit) {
        IKAnalyzer(true).use { analyzer ->
            FSDirectory.open(path).use { directory ->
                IndexWriter(directory, IndexWriterConfig(analyzer)).use { writer ->
                    val document = buildSearchDocument(message, 1L, authoritativeSearchText(message))
                    mutation(document)
                    writer.updateDocument(Term(FIELD_MESSAGE_KEY, MessageProjectionOperation.stableKey(CHAT, 1L)), document)
                    if (legacySchema) {
                        val data = writer.liveCommitData.associate { it.key to it.value }.toMutableMap()
                        data[SEARCH_COMMIT_SCHEMA_KEY] = "2"
                        writer.setLiveCommitData(data.entries)
                    }
                    writer.commit()
                }
            }
        }
    }

    private fun attachment(name: String, type: String) = Attachment("owner/$name", name, type, 10L)

    private fun richMessage(attachments: List<Attachment>): Message {
        val assets = attachments.map { EmbeddedAsset(UUID.randomUUID().toString(), it) }
        return Message(
            chatId = CHAT,
            clientMsgId = "search-attachment-message",
            serverSeq = 1L,
            senderUid = "search-owner",
            messageType = MessageType.RICH_TEXT.code,
            timestamp = 100L,
            body = buildRichTextBody(assets.joinToString("\n") { "[file](${EmbeddedAsset.uri(it.assetId)})" }, assets),
        )
    }

    private fun operation(message: Message, revision: Long, type: MessageOperationType = MessageOperationType.CREATE) =
        MessageProjectionOperation(MessageProjectionOperation.stableKey(CHAT, 1L), type, revision, message, target())

    private fun target() = MessageProjectionTarget(2, listOf("search-owner"))

    private companion object {
        const val CHAT = "attachment-search-chat"
    }
}
