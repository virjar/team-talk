package com.virjar.tk.infra.search

import com.virjar.tk.body.buildRichTextBody
import com.virjar.tk.domain.message.MessageOperationType
import com.virjar.tk.domain.message.MessageProjectionOperation
import com.virjar.tk.domain.message.MessageProjectionTarget
import com.virjar.tk.model.Message
import com.virjar.tk.protocol.MessageType
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.Term
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.FSDirectory
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchIndexProjectionTest {
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
            // No search/stop helper has committed behind the projection: an independent reader
            // must see the revision immediately when applyProjection returns.
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

            // Startup must rebuild the fence from committed Lucene documents.
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
            // Even a mistakenly supplied extracted body cannot make REVOKE searchable.
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

    private fun operation(
        revision: Long,
        operation: MessageOperationType,
        text: String,
    ): MessageProjectionOperation {
        val flags = when (operation) {
            MessageOperationType.CREATE -> 0
            MessageOperationType.EDIT -> Message.FLAG_EDITED
            MessageOperationType.REVOKE -> Message.FLAG_REVOKED
        }
        val message = Message(
            chatId = CHAT_ID,
            clientMsgId = CLIENT_MESSAGE_ID,
            serverSeq = MESSAGE_SEQUENCE,
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

    private fun committedRevision(root: File, projectionKey: String): Long =
        FSDirectory.open(root.toPath()).use { directory ->
            DirectoryReader.open(directory).use { reader ->
                val searcher = IndexSearcher(reader)
                val docs = searcher.search(TermQuery(Term("messageKey", projectionKey)), 2)
                assertEquals(1L, docs.totalHits.value)
                val document = searcher.doc(docs.scoreDocs.single().doc)
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
    }
}
