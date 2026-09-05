package com.virjar.tk.server.infra.search

import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.server.domain.message.MAX_MESSAGE_SEARCH_CHAT_FILTERS
import com.virjar.tk.server.domain.message.MAX_MESSAGE_SEARCH_COLLECTION_WINDOW
import com.virjar.tk.server.domain.message.MAX_MESSAGE_SEARCH_QUERY_CHARS
import com.virjar.tk.server.domain.message.MessageOperationType
import com.virjar.tk.server.domain.message.MessageProjectionOperation
import com.virjar.tk.server.domain.message.MessageProjectionTarget
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.MessageType
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.StringField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.FSDirectory
import org.wltea.analyzer.lucene.IKAnalyzer
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchIndexProjectionTest {
    @Test
    fun `search rejects parser and collector work outside hard resource budgets`() {
        val root = Files.createTempDirectory("tk-search-boundary-").toFile()
        val index = SearchIndex(root)
        try {
            assertFailsWith<IllegalArgumentException> {
                index.search("x".repeat(MAX_MESSAGE_SEARCH_QUERY_CHARS + 1), emptySet())
            }
            assertFailsWith<IllegalArgumentException> {
                index.search("control\u0000query", emptySet())
            }
            assertFailsWith<IllegalArgumentException> {
                index.search(
                    query = "bounded",
                    chatIds = emptySet(),
                    limit = 1,
                    offset = MAX_MESSAGE_SEARCH_COLLECTION_WINDOW,
                )
            }
            assertFailsWith<IllegalArgumentException> {
                index.search(
                    query = "bounded",
                    chatIds = (0..MAX_MESSAGE_SEARCH_CHAT_FILTERS)
                        .mapTo(linkedSetOf()) { "chat-$it" },
                )
            }
            assertFailsWith<IllegalArgumentException> {
                index.search(query = "bounded", chatIds = setOf("invalid chat"))
            }
            assertFailsWith<IllegalArgumentException> {
                index.search(query = "bounded", chatIds = emptySet(), senderUid = "invalid sender")
            }
            assertFailsWith<IllegalArgumentException> {
                index.search(
                    query = "bounded",
                    chatIds = emptySet(),
                    startTimestamp = 2L,
                    endTimestamp = 1L,
                )
            }
            assertTrue(
                index.search(
                    query = "bounded",
                    chatIds = emptySet(),
                    limit = 1,
                    offset = MAX_MESSAGE_SEARCH_COLLECTION_WINDOW - 1,
                ).hits.isEmpty(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `reserved Lucene syntax is treated as product search text`() {
        val root = Files.createTempDirectory("tk-search-literal-").toFile()
        val index = SearchIndex(root)
        try {
            index.start()
            val literalText = "title:quarterly"
            assertTrue(
                index.applyProjection(
                    operation(
                        revision = 1,
                        operation = MessageOperationType.CREATE,
                        text = literalText,
                    ),
                    literalText,
                ),
            )

            assertEquals(
                listOf(MESSAGE_SEQUENCE),
                index.search(literalText, setOf(CHAT_ID)).hits.map { it.seq },
            )
            assertEquals(
                listOf(MESSAGE_SEQUENCE),
                index.search("*", setOf(CHAT_ID)).hits.map { it.seq },
                "the explicit browse query must remain match-all before literal escaping",
            )
        } finally {
            runCatching { index.stop() }
            root.deleteRecursively()
        }
    }

    @Test
    fun `chat authorization set may exceed Lucene boolean clause limit`() {
        val root = Files.createTempDirectory("tk-search-chat-set-").toFile()
        val index = SearchIndex(root)
        try {
            index.start()
            val operation = operation(
                revision = 1,
                operation = MessageOperationType.CREATE,
                text = FIRST_TEXT,
            )
            assertTrue(index.applyProjection(operation, FIRST_TEXT))

            val authorizedChats = buildSet {
                repeat(BOOLEAN_CLAUSE_EXCEEDING_CHAT_COUNT) { add("authorized-chat-$it") }
                add(CHAT_ID)
            }
            assertEquals(
                listOf(MESSAGE_SEQUENCE),
                index.search(FIRST_TEXT, authorizedChats).hits.map { it.seq },
            )
        } finally {
            runCatching { index.stop() }
            root.deleteRecursively()
        }
    }

    @Test
    fun `revision fence and tombstones survive restart`() {
        val root = Files.createTempDirectory("tk-search-projection-").toFile()
        var index = SearchIndex(root)
        try {
            index.start()

            val create = operation(
                revision = 1,
                operation = MessageOperationType.CREATE,
                text = FIRST_TEXT,
            )
            assertTrue(index.applyProjection(create, FIRST_TEXT))
            // 没有任何 search/stop 辅助方法在投影背后提交过：独立 reader
            // 必须在 applyProjection 返回时立即看到该 revision。
            assertEquals(1L, committedRevision(root, create.projectionKey))
            assertEquals(
                listOf(MESSAGE_SEQUENCE),
                index.search(FIRST_TEXT, setOf(CHAT_ID)).hits.map { it.seq },
            )

            val edit = operation(
                revision = 2,
                operation = MessageOperationType.EDIT,
                text = SECOND_TEXT,
            )
            assertTrue(index.applyProjection(edit, SECOND_TEXT))
            assertTrue(index.search(FIRST_TEXT, setOf(CHAT_ID)).hits.isEmpty())
            assertEquals(
                listOf(MESSAGE_SEQUENCE),
                index.search(SECOND_TEXT, setOf(CHAT_ID)).hits.map { it.seq },
            )

            assertFalse(index.applyProjection(create, FIRST_TEXT))
            assertTrue(index.search(FIRST_TEXT, setOf(CHAT_ID)).hits.isEmpty())
            assertEquals(
                listOf(MESSAGE_SEQUENCE),
                index.search(SECOND_TEXT, setOf(CHAT_ID)).hits.map { it.seq },
            )

            index.stop()
            index = SearchIndex(root).also { it.start() }

            // 启动时按这个精确的投影 key 解析已提交的门禁。
            assertFalse(index.applyProjection(edit, SECOND_TEXT))
            assertEquals(
                listOf(MESSAGE_SEQUENCE),
                index.search(SECOND_TEXT, setOf(CHAT_ID)).hits.map { it.seq },
            )

            val emptyTextEdit = operation(
                revision = 3,
                operation = MessageOperationType.EDIT,
                text = SECOND_TEXT,
            )
            assertTrue(index.applyProjection(emptyTextEdit, ""))
            assertEquals(3L, committedRevision(root, emptyTextEdit.projectionKey))
            assertTrue(index.search(SECOND_TEXT, setOf(CHAT_ID)).hits.isEmpty())
            assertEquals(0, index.search("", setOf(CHAT_ID)).total)
            assertFalse(index.applyProjection(edit, SECOND_TEXT))
            assertTrue(index.search(SECOND_TEXT, setOf(CHAT_ID)).hits.isEmpty())

            val restored = operation(
                revision = 4,
                operation = MessageOperationType.EDIT,
                text = THIRD_TEXT,
            )
            assertTrue(index.applyProjection(restored, THIRD_TEXT))
            assertEquals(
                listOf(MESSAGE_SEQUENCE),
                index.search(THIRD_TEXT, setOf(CHAT_ID)).hits.map { it.seq },
            )

            val revoke = operation(
                revision = 5,
                operation = MessageOperationType.REVOKE,
                text = THIRD_TEXT,
            )
            // 即使误传了抽取出的正文，也不能让 REVOKE 变得可搜索。
            assertTrue(index.applyProjection(revoke, THIRD_TEXT))
            assertEquals(5L, committedRevision(root, revoke.projectionKey))
            assertTrue(index.search(THIRD_TEXT, setOf(CHAT_ID)).hits.isEmpty())
            assertEquals(0, index.search("", setOf(CHAT_ID)).total)

            index.stop()
            index = SearchIndex(root).also { it.start() }

            assertFalse(index.applyProjection(restored, THIRD_TEXT))
            assertTrue(index.search(THIRD_TEXT, setOf(CHAT_ID)).hits.isEmpty())
            assertEquals(0, index.search("", setOf(CHAT_ID)).total)
        } finally {
            runCatching { index.stop() }
            root.deleteRecursively()
        }
    }

    @Test
    fun `large durable index keeps revision lookup exact and search current across restart`() {
        val root = Files.createTempDirectory("tk-search-projection-scale-").toFile()
        seedRevisionTombstones(root, SCALE_DOCUMENT_COUNT, SCALE_REVISION)
        var index = SearchIndex(root)
        try {
            index.start()

            val middleChatId = scaleChatId(SCALE_DOCUMENT_COUNT / 2)
            val equalRevision = operation(
                revision = SCALE_REVISION,
                operation = MessageOperationType.EDIT,
                text = SCALE_TEXT,
                chatId = middleChatId,
                clientMessageId = "scale-middle",
                messageSequence = SCALE_MESSAGE_SEQUENCE,
            )
            assertFalse(index.applyProjection(equalRevision, SCALE_TEXT))
            assertFalse(index.applyProjection(equalRevision.copy(revision = SCALE_REVISION - 1L), SCALE_TEXT))

            val updated = equalRevision.copy(revision = SCALE_REVISION + 1L)
            assertTrue(index.applyProjection(updated, SCALE_TEXT))
            assertEquals(
                listOf(SCALE_MESSAGE_SEQUENCE),
                index.search(SCALE_TEXT, setOf(middleChatId)).hits.map { it.seq },
            )

            val untouched = operation(
                revision = SCALE_REVISION,
                operation = MessageOperationType.EDIT,
                text = "untouchedscaleneedle",
                chatId = scaleChatId(SCALE_DOCUMENT_COUNT - 1),
                clientMessageId = "scale-last",
                messageSequence = SCALE_MESSAGE_SEQUENCE,
            )
            assertFalse(index.applyProjection(untouched, "untouchedscaleneedle"))

            index.stop()
            index = SearchIndex(root).also { it.start() }

            assertFalse(index.applyProjection(updated, SCALE_TEXT))
            assertEquals(
                listOf(SCALE_MESSAGE_SEQUENCE),
                index.search(SCALE_TEXT, setOf(middleChatId)).hits.map { it.seq },
            )

            val revoked = updated.copy(
                revision = SCALE_REVISION + 2L,
                operation = MessageOperationType.REVOKE,
                message = updated.message.copy(flags = Message.FLAG_REVOKED),
            )
            assertTrue(index.applyProjection(revoked, SCALE_TEXT))
            assertTrue(index.search(SCALE_TEXT, setOf(middleChatId)).hits.isEmpty())
            assertFalse(index.applyProjection(updated, SCALE_TEXT))
        } finally {
            runCatching { index.stop() }
            root.deleteRecursively()
        }
    }

    private fun operation(
        revision: Long,
        operation: MessageOperationType,
        text: String,
        chatId: String = CHAT_ID,
        clientMessageId: String = CLIENT_MESSAGE_ID,
        messageSequence: Long = MESSAGE_SEQUENCE,
    ): MessageProjectionOperation {
        val flags = when (operation) {
            MessageOperationType.CREATE -> 0
            MessageOperationType.EDIT -> Message.FLAG_EDITED
            MessageOperationType.REVOKE -> Message.FLAG_REVOKED
        }
        val message = Message(
            chatId = chatId,
            clientMsgId = clientMessageId,
            serverSeq = messageSequence,
            senderUid = SENDER_UID,
            messageType = MessageType.RICH_TEXT.code,
            timestamp = MESSAGE_TIMESTAMP,
            flags = flags,
            body = buildRichTextBody(text),
        )
        return MessageProjectionOperation(
            projectionKey = MessageProjectionOperation.stableKey(message.chatId, message.serverSeq),
            operation = operation,
            revision = revision,
            message = message,
            target = MessageProjectionTarget(chatType = 2, recipientUids = listOf(SENDER_UID)),
        )
    }

    private fun seedRevisionTombstones(root: File, count: Int, revision: Long) {
        IKAnalyzer(true).use { analyzer ->
            FSDirectory.open(root.toPath()).use { directory ->
                IndexWriter(directory, IndexWriterConfig(analyzer)).use { writer ->
                    repeat(count) { index ->
                        val chatId = scaleChatId(index)
                        writer.addDocument(
                            Document().apply {
                                add(
                                    StringField(
                                        "messageKey",
                                        MessageProjectionOperation.stableKey(chatId, SCALE_MESSAGE_SEQUENCE),
                                        Field.Store.YES,
                                    ),
                                )
                                add(StoredField("projectionRevision", revision))
                                add(StringField("searchable", "0", Field.Store.NO))
                            },
                        )
                    }
                    // 元数据文档有意不包含 messageKey。精确 key 的启动流程
                    // 绝不能枚举或反序列化不相关的 Lucene 文档。
                    writer.addDocument(
                        Document().apply {
                            add(StringField("indexMetadata", "scale-boundary", Field.Store.YES))
                        },
                    )
                    writer.commit()
                }
            }
        }
    }

    private fun scaleChatId(index: Int): String = "scale-chat-$index"

    private fun committedRevision(root: File, projectionKey: String): Long =
        FSDirectory.open(root.toPath()).use { directory ->
            DirectoryReader.open(directory).use { reader ->
                val searcher = IndexSearcher(reader)
                val docs = searcher.search(TermQuery(Term("messageKey", projectionKey)), 2)
                assertEquals(1L, docs.totalHits.value)
                val document = searcher.storedFields().document(docs.scoreDocs.single().doc)
                checkNotNull(document.getField("projectionRevision").numericValue()).toLong()
            }
        }

    private companion object {
        const val CHAT_ID = "chat-search-projection"
        const val CLIENT_MESSAGE_ID = "client-search-projection"
        const val SENDER_UID = "search-sender"
        const val MESSAGE_SEQUENCE = 7L
        const val MESSAGE_TIMESTAMP = 1_700_000_000_000L
        const val FIRST_TEXT = "firstprojectionneedle"
        const val SECOND_TEXT = "secondprojectionneedle"
        const val THIRD_TEXT = "thirdprojectionneedle"
        const val SCALE_DOCUMENT_COUNT = 10_000
        const val SCALE_REVISION = 7L
        const val SCALE_MESSAGE_SEQUENCE = 1L
        const val SCALE_TEXT = "updatedscaleneedle"
        const val BOOLEAN_CLAUSE_EXCEEDING_CHAT_COUNT = 2_048
    }
}
