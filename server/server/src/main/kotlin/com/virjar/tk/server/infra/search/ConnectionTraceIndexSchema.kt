package com.virjar.tk.server.infra.search

import com.virjar.tk.server.domain.telemetry.ConnectionTraceEventDraft
import com.virjar.tk.server.domain.telemetry.ConnectionTraceOutcome
import com.virjar.tk.server.domain.telemetry.ConnectionTracePhase
import com.virjar.tk.server.domain.telemetry.StoredConnectionTraceEvent
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.LongPoint
import org.apache.lucene.document.NumericDocValuesField
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.StringField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.DocValuesType
import org.apache.lucene.index.FieldInfo
import org.apache.lucene.index.FieldInfos
import org.apache.lucene.index.IndexOptions
import org.apache.lucene.store.Directory

internal const val TRACE_FIELD_ID = "id"
internal const val TRACE_FIELD_UID = "uid"
internal const val TRACE_FIELD_DEVICE_ID = "deviceId"
internal const val TRACE_FIELD_CORRELATION_ID = "correlationId"
internal const val TRACE_FIELD_TRACE_ID = "traceId"
internal const val TRACE_FIELD_SESSION_ID = "sessionId"
internal const val TRACE_FIELD_GENERATION = "connectionGeneration"
internal const val TRACE_FIELD_POLICY_REVISION = "policyRevision"
internal const val TRACE_FIELD_OCCURRED_AT = "occurredAt"
internal const val TRACE_FIELD_PHASE = "phase"
internal const val TRACE_FIELD_OUTCOME = "outcome"
internal const val TRACE_FIELD_DETAIL = "detail"
internal const val TRACE_FIELD_ACCOUNTED_BYTES = "accountedBytes"

private const val TRACE_SCHEMA_VERSION = "1"
private const val TRACE_COMMIT_SCHEMA = "teamtalk.connectionTrace.schema"
private const val TRACE_COMMIT_STATE = "teamtalk.connectionTrace.state"
private const val TRACE_COMMIT_READY = "ready"
private const val TRACE_COMMIT_NEXT_ID = "teamtalk.connectionTrace.nextId"
private const val TRACE_COMMIT_DOCUMENTS = "teamtalk.connectionTrace.documents"
private const val TRACE_COMMIT_BYTES = "teamtalk.connectionTrace.accountedBytes"
private const val TRACE_FIXED_ACCOUNTING_BYTES = 512L

private val TRACE_EXACT_FIELDS = setOf(
    TRACE_FIELD_UID,
    TRACE_FIELD_DEVICE_ID,
    TRACE_FIELD_CORRELATION_ID,
    TRACE_FIELD_TRACE_ID,
    TRACE_FIELD_SESSION_ID,
    TRACE_FIELD_PHASE,
    TRACE_FIELD_OUTCOME,
)
private val TRACE_NUMERIC_POINT_FIELDS = setOf(
    TRACE_FIELD_GENERATION,
    TRACE_FIELD_POLICY_REVISION,
)
private val TRACE_NUMERIC_DOC_VALUE_FIELDS = setOf(
    TRACE_FIELD_ID,
    TRACE_FIELD_ACCOUNTED_BYTES,
)
private val TRACE_FIELDS = TRACE_EXACT_FIELDS + TRACE_NUMERIC_POINT_FIELDS + TRACE_NUMERIC_DOC_VALUE_FIELDS + setOf(
    TRACE_FIELD_OCCURRED_AT,
    TRACE_FIELD_DETAIL,
)

internal data class ConnectionTraceCommitState(
    val nextId: Long,
    val documentCount: Long,
    val accountedBytes: Long,
)

internal data class ConnectionTraceDocument(
    val document: Document,
    val accountedBytes: Long,
)

internal fun connectionTraceCommitMetadata(state: ConnectionTraceCommitState): Map<String, String> = mapOf(
    TRACE_COMMIT_SCHEMA to TRACE_SCHEMA_VERSION,
    TRACE_COMMIT_STATE to TRACE_COMMIT_READY,
    TRACE_COMMIT_NEXT_ID to state.nextId.toString(),
    TRACE_COMMIT_DOCUMENTS to state.documentCount.toString(),
    TRACE_COMMIT_BYTES to state.accountedBytes.toString(),
)

internal fun requireConnectionTraceCommit(
    metadata: Map<String, String>,
    maxDocuments: Long,
    maxAccountedBytes: Long,
): ConnectionTraceCommitState {
    require(metadata[TRACE_COMMIT_SCHEMA] == TRACE_SCHEMA_VERSION) { "connection trace schema is invalid" }
    require(metadata[TRACE_COMMIT_STATE] == TRACE_COMMIT_READY) { "connection trace commit is incomplete" }
    val state = ConnectionTraceCommitState(
        nextId = metadata[TRACE_COMMIT_NEXT_ID]?.toLongOrNull()
            ?: error("connection trace id cursor is missing"),
        documentCount = metadata[TRACE_COMMIT_DOCUMENTS]?.toLongOrNull()
            ?: error("connection trace document count is missing"),
        accountedBytes = metadata[TRACE_COMMIT_BYTES]?.toLongOrNull()
            ?: error("connection trace byte count is missing"),
    )
    require(state.nextId > 0L && state.documentCount >= 0L && state.accountedBytes >= 0L) {
        "connection trace commit counters are invalid"
    }
    require(state.documentCount <= maxDocuments && state.accountedBytes <= maxAccountedBytes) {
        "connection trace commit exceeds capacity"
    }
    return state
}

internal fun validateConnectionTraceIndex(
    directory: Directory,
    state: ConnectionTraceCommitState,
    maxPhysicalBytes: Long,
) {
    require(telemetryPhysicalBytes(directory) <= maxPhysicalBytes) {
        "connection trace physical capacity is exceeded"
    }
    DirectoryReader.open(directory).use { reader ->
        require(reader.numDocs().toLong() == state.documentCount) {
            "connection trace document count does not match commit"
        }
        require(reader.maxDoc() == reader.numDocs() && !reader.hasDeletions()) {
            "connection trace index contains retained deletions"
        }
        if (reader.numDocs() == 0) {
            require(state.accountedBytes == 0L) { "empty connection trace index has byte accounting" }
            return@use
        }
        require(FieldInfos.getMergedFieldInfos(reader).mapTo(linkedSetOf(), FieldInfo::name) == TRACE_FIELDS) {
            "connection trace index field set is invalid"
        }
        reader.leaves().forEach { leaf ->
            val infos = leaf.reader().fieldInfos
            require(infos.mapTo(linkedSetOf(), FieldInfo::name) == TRACE_FIELDS) {
                "connection trace segment field set is invalid"
            }
            infos.forEach(::requireConnectionTraceFieldShape)
        }
    }
}

private fun requireConnectionTraceFieldShape(info: FieldInfo) {
    info.checkConsistency()
    when (info.name) {
        in TRACE_EXACT_FIELDS -> requireTraceShape(info, IndexOptions.DOCS, omitNorms = true)
        in TRACE_NUMERIC_POINT_FIELDS -> requireTraceShape(info, pointBytes = java.lang.Long.BYTES)
        in TRACE_NUMERIC_DOC_VALUE_FIELDS -> requireTraceShape(info, docValuesType = DocValuesType.NUMERIC)
        TRACE_FIELD_OCCURRED_AT -> requireTraceShape(
            info,
            docValuesType = DocValuesType.NUMERIC,
            pointBytes = java.lang.Long.BYTES,
        )
        TRACE_FIELD_DETAIL -> requireTraceShape(info)
        else -> error("unknown connection trace field")
    }
}

private fun requireTraceShape(
    info: FieldInfo,
    indexOptions: IndexOptions = IndexOptions.NONE,
    docValuesType: DocValuesType = DocValuesType.NONE,
    pointBytes: Int = 0,
    omitNorms: Boolean = false,
) {
    val dimensions = if (pointBytes == 0) 0 else 1
    require(
        info.indexOptions == indexOptions &&
            info.docValuesType == docValuesType &&
            info.pointDimensionCount == dimensions &&
            info.pointIndexDimensionCount == dimensions &&
            info.pointNumBytes == pointBytes &&
            !info.hasVectors() &&
            info.omitsNorms() == omitNorms,
    ) { "connection trace field schema is invalid" }
}

internal fun connectionTraceDocument(id: Long, event: ConnectionTraceEventDraft): ConnectionTraceDocument {
    val accountedBytes = TRACE_FIXED_ACCOUNTING_BYTES + listOfNotNull(
        event.uid,
        event.deviceId,
        event.correlationId,
        event.traceId,
        event.sessionId,
        event.phase.name,
        event.outcome.name,
        event.detail,
    ).sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }
    val document = Document().apply {
        add(StoredField(TRACE_FIELD_ID, id))
        add(NumericDocValuesField(TRACE_FIELD_ID, id))
        add(StringField(TRACE_FIELD_UID, event.uid.orEmpty(), Field.Store.YES))
        add(StringField(TRACE_FIELD_DEVICE_ID, event.deviceId.orEmpty(), Field.Store.YES))
        add(StringField(TRACE_FIELD_CORRELATION_ID, event.correlationId, Field.Store.YES))
        add(StringField(TRACE_FIELD_TRACE_ID, event.traceId, Field.Store.YES))
        add(StringField(TRACE_FIELD_SESSION_ID, event.sessionId, Field.Store.YES))
        addTraceLong(TRACE_FIELD_GENERATION, event.connectionGeneration)
        addTraceLong(TRACE_FIELD_POLICY_REVISION, event.policyRevision)
        add(LongPoint(TRACE_FIELD_OCCURRED_AT, event.occurredAt))
        add(StoredField(TRACE_FIELD_OCCURRED_AT, event.occurredAt))
        add(NumericDocValuesField(TRACE_FIELD_OCCURRED_AT, event.occurredAt))
        add(StringField(TRACE_FIELD_PHASE, event.phase.name, Field.Store.YES))
        add(StringField(TRACE_FIELD_OUTCOME, event.outcome.name, Field.Store.YES))
        add(StoredField(TRACE_FIELD_DETAIL, event.detail.orEmpty()))
        add(StoredField(TRACE_FIELD_ACCOUNTED_BYTES, accountedBytes))
        add(NumericDocValuesField(TRACE_FIELD_ACCOUNTED_BYTES, accountedBytes))
    }
    return ConnectionTraceDocument(document, accountedBytes)
}

private fun Document.addTraceLong(name: String, value: Long) {
    add(LongPoint(name, value))
    add(StoredField(name, value))
}

internal fun Document.toStoredConnectionTraceEvent(): StoredConnectionTraceEvent = StoredConnectionTraceEvent(
    id = requireNotNull(getField(TRACE_FIELD_ID)?.numericValue()).toLong(),
    event = ConnectionTraceEventDraft(
        uid = get(TRACE_FIELD_UID).takeIf(String::isNotEmpty),
        deviceId = get(TRACE_FIELD_DEVICE_ID).takeIf(String::isNotEmpty),
        correlationId = get(TRACE_FIELD_CORRELATION_ID),
        traceId = get(TRACE_FIELD_TRACE_ID),
        sessionId = get(TRACE_FIELD_SESSION_ID),
        connectionGeneration = requireNotNull(getField(TRACE_FIELD_GENERATION)?.numericValue()).toLong(),
        policyRevision = requireNotNull(getField(TRACE_FIELD_POLICY_REVISION)?.numericValue()).toLong(),
        occurredAt = requireNotNull(getField(TRACE_FIELD_OCCURRED_AT)?.numericValue()).toLong(),
        phase = ConnectionTracePhase.valueOf(get(TRACE_FIELD_PHASE)),
        outcome = ConnectionTraceOutcome.valueOf(get(TRACE_FIELD_OUTCOME)),
        detail = get(TRACE_FIELD_DETAIL).takeIf(String::isNotEmpty),
    ),
)
