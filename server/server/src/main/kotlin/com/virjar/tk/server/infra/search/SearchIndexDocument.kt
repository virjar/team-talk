package com.virjar.tk.server.infra.search

import com.virjar.tk.server.domain.message.MessageProjectionOperation
import com.virjar.tk.server.domain.message.MessageTextExtractor
import com.virjar.tk.protocol.model.Message
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.IntPoint
import org.apache.lucene.document.LongPoint
import org.apache.lucene.document.NumericDocValuesField
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField

internal const val FIELD_MESSAGE_KEY = "messageKey"
internal const val FIELD_PROJECTION_REVISION = "projectionRevision"
internal const val FIELD_SEARCHABLE = "searchable"
internal const val FIELD_CLIENT_MESSAGE_ID = "clientMsgId"
internal const val FIELD_CHAT_ID = "chatId"
internal const val FIELD_SEQUENCE = "seq"
internal const val FIELD_SENDER_UID = "senderUid"
internal const val FIELD_TEXT = "text"
internal const val FIELD_TIMESTAMP = "timestamp"
internal const val FIELD_MESSAGE_TYPE = "messageType"
internal const val SEARCHABLE_TRUE = "1"
internal const val SEARCHABLE_FALSE = "0"
internal const val SEARCH_INDEX_SCHEMA_VERSION = "2"
internal const val SEARCH_COMMIT_SCHEMA_KEY = "teamtalk.search.schema"
internal const val SEARCH_COMMIT_GENERATION_KEY = "teamtalk.search.generation"

/** 由权威消息派生的完整当前文本；已撤回的值保持为墓碑。 */
internal fun authoritativeSearchText(message: Message): String? =
    if (message.flags and Message.FLAG_REVOKED != 0) {
        null
    } else {
        MessageTextExtractor.extractSearchText(message, message.body)
    }

internal fun buildSearchDocument(
    message: Message,
    revision: Long,
    text: String?,
    forceTombstone: Boolean = false,
): Document {
    require(revision > 0L) { "Search projection revision must be positive" }
    val projectionKey = MessageProjectionOperation.stableKey(message.chatId, message.serverSeq)
    val searchable = !forceTombstone &&
        message.flags and Message.FLAG_REVOKED == 0 &&
        !text.isNullOrBlank()
    return Document().apply {
        add(StringField(FIELD_MESSAGE_KEY, projectionKey, Field.Store.YES))
        add(StoredField(FIELD_PROJECTION_REVISION, revision))
        add(
            StringField(
                FIELD_SEARCHABLE,
                if (searchable) SEARCHABLE_TRUE else SEARCHABLE_FALSE,
                Field.Store.YES,
            ),
        )
        add(StringField(FIELD_CLIENT_MESSAGE_ID, message.clientMsgId, Field.Store.YES))
        add(StringField(FIELD_CHAT_ID, message.chatId, Field.Store.YES))
        add(LongPoint(FIELD_SEQUENCE, message.serverSeq))
        add(StoredField(FIELD_SEQUENCE, message.serverSeq))
        add(StringField(FIELD_SENDER_UID, message.senderUid, Field.Store.YES))
        if (searchable) add(TextField(FIELD_TEXT, checkNotNull(text), Field.Store.YES))
        add(LongPoint(FIELD_TIMESTAMP, message.timestamp))
        add(StoredField(FIELD_TIMESTAMP, message.timestamp))
        add(NumericDocValuesField(FIELD_TIMESTAMP, message.timestamp))
        add(IntPoint(FIELD_MESSAGE_TYPE, message.messageType))
        add(StoredField(FIELD_MESSAGE_TYPE, message.messageType))
    }
}

/**
 * 比较每个当前的派生/存储字段。返回简短的固定原因，使启动日志
 * 保持有用，同时不把消息文本或用户控制的标识符复制进诊断。
 */
internal fun searchDocumentMismatch(
    document: Document,
    message: Message,
    revision: Long,
    text: String?,
): String? {
    val searchable = message.flags and Message.FLAG_REVOKED == 0 && !text.isNullOrBlank()
    val expectedStrings = linkedMapOf(
        FIELD_MESSAGE_KEY to MessageProjectionOperation.stableKey(message.chatId, message.serverSeq),
        FIELD_SEARCHABLE to if (searchable) SEARCHABLE_TRUE else SEARCHABLE_FALSE,
        FIELD_CLIENT_MESSAGE_ID to message.clientMsgId,
        FIELD_CHAT_ID to message.chatId,
        FIELD_SENDER_UID to message.senderUid,
    )
    expectedStrings.forEach { (field, expected) ->
        val fields = document.getFields(field)
        if (fields.size != 1 || fields.single().stringValue() != expected) return "field:$field"
    }

    val expectedNumbers = linkedMapOf(
        FIELD_PROJECTION_REVISION to revision,
        FIELD_SEQUENCE to message.serverSeq,
        FIELD_TIMESTAMP to message.timestamp,
        FIELD_MESSAGE_TYPE to message.messageType.toLong(),
    )
    expectedNumbers.forEach { (field, expected) ->
        val stored = document.getFields(field).filter { it.fieldType().stored() }
        if (stored.size != 1 || stored.single().numericValue()?.toLong() != expected) return "field:$field"
    }

    val textFields = document.getFields(FIELD_TEXT)
    if (searchable) {
        if (textFields.size != 1 || textFields.single().stringValue() != text) return "field:$FIELD_TEXT"
    } else if (textFields.isNotEmpty()) {
        return "field:$FIELD_TEXT"
    }
    return null
}
