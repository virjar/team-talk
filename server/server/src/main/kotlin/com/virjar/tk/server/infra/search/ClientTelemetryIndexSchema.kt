package com.virjar.tk.server.infra.search

import com.virjar.tk.protocol.model.AuthRules
import com.virjar.tk.server.domain.telemetry.StoredTelemetryEvent
import com.virjar.tk.server.domain.telemetry.ConnectionTraceContext
import com.virjar.tk.server.domain.telemetry.TelemetryBatchDraft
import com.virjar.tk.server.domain.telemetry.TelemetryBatchReceipt
import com.virjar.tk.server.domain.telemetry.TelemetryEventDraft
import com.virjar.tk.server.domain.telemetry.TelemetryNumericRange
import com.virjar.tk.server.domain.telemetry.TelemetryOutgoingQueueMetrics
import com.virjar.tk.server.domain.telemetry.TelemetryRuntimeSnapshot
import com.virjar.tk.server.domain.telemetry.TelemetrySearchQuery
import com.virjar.tk.server.domain.telemetry.OUTGOING_QUEUE_STORED_MESSAGE
import com.virjar.tk.server.domain.telemetry.OUTGOING_QUEUE_STORED_SEARCH_TEXT
import com.virjar.tk.protocol.telemetry.ClientTelemetryLimits
import com.virjar.tk.protocol.telemetry.TELEMETRY_OUTGOING_QUEUE_EVENT_NAME
import com.virjar.tk.protocol.telemetry.TelemetryEventKind
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.LongPoint
import org.apache.lucene.document.NumericDocValuesField
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.DocValuesType
import org.apache.lucene.index.FieldInfo
import org.apache.lucene.index.FieldInfos
import org.apache.lucene.index.IndexOptions
import org.apache.lucene.index.Term
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.MatchAllDocsQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermInSetQuery
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.Directory
import org.apache.lucene.util.BytesRef

internal const val FIELD_DOC_KEY = "docKey"
internal const val FIELD_DOC_TYPE = "docType"
internal const val FIELD_RECORD_ID = "recordId"
internal const val FIELD_RECEIVED_AT = "receivedAt"
internal const val TELEMETRY_FIELD_TEXT = "text"
internal const val FIELD_ACCOUNTED_BYTES = "accountedBytes"

private const val FIELD_PAYLOAD_SHA256 = "payloadSha256"
private const val FIELD_BATCH_ID = "batchId"
private const val FIELD_UID = "uid"
private const val FIELD_DEVICE_ID = "deviceId"
private const val FIELD_PLATFORM = "platform"
private const val FIELD_OS_NAME = "osName"
private const val FIELD_OS_VERSION = "osVersion"
private const val FIELD_ARCHITECTURE = "architecture"
private const val FIELD_DEVICE_MODEL = "deviceModel"
private const val FIELD_APP_VERSION = "appVersion"
private const val FIELD_BUILD_NUMBER = "buildNumber"
private const val FIELD_GIT_COMMIT = "gitCommit"
private const val FIELD_BUILD_IDENTITY = "buildIdentity"
private const val FIELD_BUILD_TIME = "buildTime"
private const val FIELD_PROTOCOL_VERSION = "protocolVersion"
private const val FIELD_DISTRIBUTION = "distribution"
private const val FIELD_CATEGORY = "category"
private const val FIELD_EVENT_NAME = "eventName"
private const val FIELD_EVENT_ID = "eventId"
private const val FIELD_RUN_ID = "runId"
private const val TELEMETRY_FIELD_SEQUENCE = "sequence"
private const val FIELD_OCCURRED_AT = "occurredAt"
private const val FIELD_MESSAGE = "message"
private const val FIELD_OUTGOING_PENDING_COUNT = "outgoingPendingCount"
private const val FIELD_OUTGOING_RETRY_WAIT_COUNT = "outgoingRetryWaitCount"
private const val FIELD_OUTGOING_TERMINAL_FAILED_COUNT = "outgoingTerminalFailedCount"
private const val FIELD_OUTGOING_OLDEST_ACTIVE_AGE_MILLIS = "outgoingOldestActiveAgeMillis"
private const val FIELD_OUTGOING_MAX_ATTEMPT_COUNT = "outgoingMaxAttemptCount"
private const val FIELD_TRACE_CORRELATION_ID = "traceCorrelationId"
private const val FIELD_TRACE_ID = "traceId"
private const val FIELD_TRACE_SESSION_ID = "traceSessionId"
private const val FIELD_CONNECTION_GENERATION = "connectionGeneration"
private const val FIELD_TRACE_POLICY_REVISION = "tracePolicyRevision"

private const val INDEX_SCHEMA_VERSION = "7"
private const val COMMIT_STATE_READY = "ready"
private const val COMMIT_SCHEMA = "teamtalk.telemetry.schema"
private const val COMMIT_STATE = "teamtalk.telemetry.state"
private const val COMMIT_NEXT_RECORD_ID = "teamtalk.telemetry.nextRecordId"
private const val COMMIT_ACCOUNTED_BYTES = "teamtalk.telemetry.accountedBytes"
private const val COMMIT_DOCUMENT_COUNT = "teamtalk.telemetry.documentCount"
private const val DOC_TYPE_RECEIPT = "receipt"
private const val DOC_TYPE_EVENT = "event"
private const val MAX_OWNER_CHARS = 64
private const val MAX_KEYWORD_CHARS = 256
private const val MAX_STORED_MESSAGE_CHARS = 4 * 1_024
private const val MAX_STORED_SEARCH_TEXT_CHARS = 64 * 1_024
private const val FIXED_EVENT_ACCOUNTING_BYTES = 1_024L
private const val FIXED_RECEIPT_ACCOUNTING_BYTES = 256L
private val PAYLOAD_SHA256_PATTERN = Regex("[0-9a-f]{64}")

private val EXACT_INDEX_FIELDS = setOf(
    FIELD_DOC_KEY,
    FIELD_DOC_TYPE,
    FIELD_UID,
    FIELD_DEVICE_ID,
    FIELD_PLATFORM,
    FIELD_OS_NAME,
    FIELD_OS_VERSION,
    FIELD_APP_VERSION,
    FIELD_GIT_COMMIT,
    FIELD_CATEGORY,
    FIELD_EVENT_NAME,
    FIELD_TRACE_CORRELATION_ID,
    FIELD_TRACE_ID,
    FIELD_TRACE_SESSION_ID,
)
private val STORED_ONLY_FIELDS = setOf(
    FIELD_PAYLOAD_SHA256,
    FIELD_BATCH_ID,
    FIELD_ARCHITECTURE,
    FIELD_DEVICE_MODEL,
    FIELD_BUILD_NUMBER,
    FIELD_BUILD_IDENTITY,
    FIELD_BUILD_TIME,
    FIELD_PROTOCOL_VERSION,
    FIELD_DISTRIBUTION,
    FIELD_EVENT_ID,
    FIELD_RUN_ID,
    TELEMETRY_FIELD_SEQUENCE,
    FIELD_OCCURRED_AT,
    FIELD_MESSAGE,
)
private val RECEIPT_INDEX_FIELDS = setOf(
    FIELD_DOC_KEY,
    FIELD_DOC_TYPE,
    FIELD_PAYLOAD_SHA256,
    TELEMETRY_FIELD_SEQUENCE,
    FIELD_RECEIVED_AT,
    FIELD_ACCOUNTED_BYTES,
)
private val BASE_EVENT_INDEX_FIELDS = setOf(
    FIELD_DOC_KEY,
    FIELD_DOC_TYPE,
    FIELD_RECORD_ID,
    FIELD_BATCH_ID,
    FIELD_UID,
    FIELD_DEVICE_ID,
    FIELD_PLATFORM,
    FIELD_OS_NAME,
    FIELD_OS_VERSION,
    FIELD_ARCHITECTURE,
    FIELD_DEVICE_MODEL,
    FIELD_APP_VERSION,
    FIELD_BUILD_NUMBER,
    FIELD_GIT_COMMIT,
    FIELD_BUILD_IDENTITY,
    FIELD_BUILD_TIME,
    FIELD_PROTOCOL_VERSION,
    FIELD_DISTRIBUTION,
    FIELD_CATEGORY,
    FIELD_EVENT_NAME,
    FIELD_EVENT_ID,
    FIELD_RUN_ID,
    TELEMETRY_FIELD_SEQUENCE,
    FIELD_OCCURRED_AT,
    FIELD_RECEIVED_AT,
    FIELD_MESSAGE,
    TELEMETRY_FIELD_TEXT,
    FIELD_ACCOUNTED_BYTES,
)
private val OUTGOING_QUEUE_INDEX_FIELDS = setOf(
    FIELD_OUTGOING_PENDING_COUNT,
    FIELD_OUTGOING_RETRY_WAIT_COUNT,
    FIELD_OUTGOING_TERMINAL_FAILED_COUNT,
    FIELD_OUTGOING_OLDEST_ACTIVE_AGE_MILLIS,
    FIELD_OUTGOING_MAX_ATTEMPT_COUNT,
)
private val CONNECTION_TRACE_INDEX_FIELDS = setOf(
    FIELD_TRACE_CORRELATION_ID,
    FIELD_TRACE_ID,
    FIELD_TRACE_SESSION_ID,
    FIELD_CONNECTION_GENERATION,
    FIELD_TRACE_POLICY_REVISION,
)
private val EVENT_INDEX_FIELD_SETS = setOf(
    BASE_EVENT_INDEX_FIELDS,
    BASE_EVENT_INDEX_FIELDS + OUTGOING_QUEUE_INDEX_FIELDS,
    BASE_EVENT_INDEX_FIELDS + CONNECTION_TRACE_INDEX_FIELDS,
    BASE_EVENT_INDEX_FIELDS + OUTGOING_QUEUE_INDEX_FIELDS + CONNECTION_TRACE_INDEX_FIELDS,
)
private val LEGAL_SEGMENT_FIELD_SETS = setOf(
    RECEIPT_INDEX_FIELDS,
) + EVENT_INDEX_FIELD_SETS + EVENT_INDEX_FIELD_SETS.map { RECEIPT_INDEX_FIELDS + it }

/**
 * 在不扫描已存储事件载荷的情况下，校验有界的 schema-7 启动契约。
 */
internal fun validateCommittedTelemetryIndex(
    openedDirectory: Directory,
    metadata: Map<String, String>,
    maxDocuments: Long,
    maxAccountedBytes: Long,
    maxPhysicalBytes: Long,
) {
    val expectedDocuments = checkNotNull(metadata[COMMIT_DOCUMENT_COUNT]).toLong()
    val expectedAccountedBytes = checkNotNull(metadata[COMMIT_ACCOUNTED_BYTES]).toLong()
    require(expectedDocuments <= maxDocuments) { "telemetry document capacity marker is invalid" }
    require(expectedAccountedBytes <= maxAccountedBytes) { "telemetry byte capacity marker is invalid" }
    require(telemetryPhysicalBytes(openedDirectory) <= maxPhysicalBytes) {
        "telemetry physical capacity is exceeded"
    }

    DirectoryReader.open(openedDirectory).use { reader ->
        require(reader.numDocs().toLong() == expectedDocuments) {
            "telemetry live document count does not match its commit marker"
        }
        require(reader.maxDoc() == reader.numDocs() && !reader.hasDeletions()) {
            "telemetry index contains physically retained deleted documents"
        }
        if (reader.numDocs() == 0) {
            require(expectedAccountedBytes == 0L) {
                "empty telemetry index has non-zero byte accounting"
            }
            return@use
        }
        validateMergedFieldSet(FieldInfos.getMergedFieldInfos(reader))
        reader.leaves().forEach { leaf ->
            validateSegmentFieldStructure(leaf.reader().fieldInfos)
        }
    }
}

private fun validateMergedFieldSet(fieldInfos: FieldInfos) {
    val fieldNames = fieldInfos.mapTo(linkedSetOf(), FieldInfo::name)
    require(fieldNames in LEGAL_SEGMENT_FIELD_SETS) {
        "telemetry index field set is invalid"
    }
}

private fun validateSegmentFieldStructure(fieldInfos: FieldInfos) {
    val fieldNames = fieldInfos.mapTo(linkedSetOf(), FieldInfo::name)
    require(fieldNames in LEGAL_SEGMENT_FIELD_SETS) {
        "telemetry index segment field set is invalid"
    }
    fieldInfos.forEach { info ->
        when (info.name) {
            in EXACT_INDEX_FIELDS -> requireFieldShape(
                info = info,
                indexOptions = IndexOptions.DOCS,
                omitNorms = true,
            )
            in STORED_ONLY_FIELDS -> requireFieldShape(info)
            FIELD_RECORD_ID,
            -> requireFieldShape(
                info,
                docValuesType = DocValuesType.NUMERIC,
                pointBytes = java.lang.Long.BYTES,
            )
            FIELD_ACCOUNTED_BYTES -> requireFieldShape(info, docValuesType = DocValuesType.NUMERIC)
            FIELD_RECEIVED_AT -> requireFieldShape(
                info = info,
                docValuesType = DocValuesType.NUMERIC,
                pointBytes = java.lang.Long.BYTES,
            )
            TELEMETRY_FIELD_TEXT -> requireFieldShape(
                info = info,
                indexOptions = IndexOptions.DOCS_AND_FREQS_AND_POSITIONS,
            )
            in OUTGOING_QUEUE_INDEX_FIELDS -> requireFieldShape(
                info = info,
                pointBytes = java.lang.Long.BYTES,
            )
            FIELD_CONNECTION_GENERATION,
            FIELD_TRACE_POLICY_REVISION,
            -> requireFieldShape(info = info, pointBytes = java.lang.Long.BYTES)
            else -> error("unreachable telemetry field schema branch")
        }
    }
}

private fun requireFieldShape(
    info: FieldInfo,
    indexOptions: IndexOptions = IndexOptions.NONE,
    docValuesType: DocValuesType = DocValuesType.NONE,
    pointBytes: Int = 0,
    hasTermVectors: Boolean = false,
    omitNorms: Boolean = false,
) {
    info.checkConsistency()
    val expectedPointDimensions = if (pointBytes == 0) 0 else 1
    require(info.indexOptions == indexOptions &&
        info.docValuesType == docValuesType &&
        info.pointDimensionCount == expectedPointDimensions &&
        info.pointIndexDimensionCount == expectedPointDimensions &&
        info.pointNumBytes == pointBytes &&
        info.hasVectors() == hasTermVectors &&
        info.omitsNorms() == omitNorms
    ) {
        "telemetry field schema is invalid"
    }
}

internal fun requireValidTelemetryCommit(metadata: Map<String, String>): Map<String, String> {
    require(metadata[COMMIT_SCHEMA] == INDEX_SCHEMA_VERSION) { "telemetry schema marker is invalid" }
    require(metadata[COMMIT_STATE] == COMMIT_STATE_READY) { "telemetry commit is incomplete" }
    require(metadata[COMMIT_NEXT_RECORD_ID]?.toLongOrNull()?.let { it > 0L } == true) {
        "telemetry record cursor is invalid"
    }
    require(metadata[COMMIT_ACCOUNTED_BYTES]?.toLongOrNull()?.let { it >= 0L } == true) {
        "telemetry byte accounting is invalid"
    }
    require(metadata[COMMIT_DOCUMENT_COUNT]?.toLongOrNull()?.let { it >= 0L } == true) {
        "telemetry document accounting is invalid"
    }
    return metadata
}

internal fun telemetryNextRecordId(metadata: Map<String, String>): Long =
    checkNotNull(metadata[COMMIT_NEXT_RECORD_ID]).toLong()

internal fun telemetryAccountedBytes(metadata: Map<String, String>): Long =
    checkNotNull(metadata[COMMIT_ACCOUNTED_BYTES]).toLong()

internal fun telemetryDocumentCount(metadata: Map<String, String>): Long =
    checkNotNull(metadata[COMMIT_DOCUMENT_COUNT]).toLong()

internal fun telemetryCommitMetadata(nextId: Long, bytes: Long, documents: Long) = mapOf(
    COMMIT_SCHEMA to INDEX_SCHEMA_VERSION,
    COMMIT_STATE to COMMIT_STATE_READY,
    COMMIT_NEXT_RECORD_ID to nextId.toString(),
    COMMIT_ACCOUNTED_BYTES to bytes.toString(),
    COMMIT_DOCUMENT_COUNT to documents.toString(),
)

internal fun findTelemetryReceipt(searcher: IndexSearcher, key: String): TelemetryBatchReceipt? {
    val hit = searcher.search(TermQuery(Term(FIELD_DOC_KEY, key)), 1).scoreDocs.singleOrNull() ?: return null
    val document = searcher.storedFields().document(hit.doc)
    require(document.get(FIELD_DOC_TYPE) == DOC_TYPE_RECEIPT) {
        "telemetry receipt document has an invalid type"
    }
    return TelemetryBatchReceipt(
        payloadSha256 = requireNotNull(document.get(FIELD_PAYLOAD_SHA256)) {
            "telemetry receipt payload hash is missing"
        },
        acceptedThroughSequence = requireNotNull(document.getField(TELEMETRY_FIELD_SEQUENCE)?.numericValue()) {
            "telemetry receipt sequence is missing"
        }.toLong(),
        receivedAt = requireNotNull(document.getField(FIELD_RECEIVED_AT)?.numericValue()) {
            "telemetry receipt received time is missing"
        }.toLong(),
    )
}

internal fun findTelemetryEventById(searcher: IndexSearcher, recordId: Long): StoredTelemetryEvent? {
    require(recordId > 0L) { "telemetry record id must be positive" }
    val hit = searcher.search(LongPoint.newExactQuery(FIELD_RECORD_ID, recordId), 2).scoreDocs
    require(hit.size <= 1) { "telemetry record id is not unique" }
    val document = hit.singleOrNull()?.let { searcher.storedFields().document(it.doc) } ?: return null
    require(document.get(FIELD_DOC_TYPE) == DOC_TYPE_EVENT) { "telemetry record resolved to a non-event" }
    return document.toStoredTelemetryEvent()
}

internal fun findExistingTelemetryEventKeys(searcher: IndexSearcher, keys: Set<String>): Set<String> {
    if (keys.isEmpty()) return emptySet()
    val query = TermInSetQuery(FIELD_DOC_KEY, keys.map(::BytesRef))
    val hits = searcher.search(query, keys.size).scoreDocs
    val storedFields = searcher.storedFields()
    return hits.mapTo(HashSet(hits.size)) { hit ->
        val document = storedFields.document(hit.doc)
        require(document.get(FIELD_DOC_TYPE) == DOC_TYPE_EVENT) {
            "telemetry event key resolved to an invalid document type"
        }
        requireNotNull(document.get(FIELD_DOC_KEY)) { "telemetry event key is missing" }
    }
}

internal fun telemetryReceiptDocument(
    batch: TelemetryBatchDraft,
    receivedAt: Long,
    key: String,
): TelemetryAccountedDocument {
    val sequence = batch.events.last().sequence
    val bytes = FIXED_RECEIPT_ACCOUNTING_BYTES + key.utf8Size() + batch.payloadSha256.utf8Size()
    val document = Document().apply {
        add(StringField(FIELD_DOC_KEY, key, Field.Store.YES))
        add(StringField(FIELD_DOC_TYPE, DOC_TYPE_RECEIPT, Field.Store.YES))
        add(StoredField(FIELD_PAYLOAD_SHA256, batch.payloadSha256))
        add(StoredField(TELEMETRY_FIELD_SEQUENCE, sequence))
        addReceivedAt(receivedAt)
        addAccountedBytes(bytes)
    }
    return TelemetryAccountedDocument(document, bytes)
}

internal fun telemetryEventDocument(
    uid: String,
    deviceId: String,
    batch: TelemetryBatchDraft,
    event: TelemetryEventDraft,
    receivedAt: Long,
    key: String,
    recordId: Long,
): TelemetryAccountedDocument {
    val runtime = batch.runtime
    val bytes = FIXED_EVENT_ACCOUNTING_BYTES + event.searchText.utf8Size() * 2L +
        listOfNotNull(
            key,
            batch.batchId,
            uid,
            deviceId,
            runtime.platform,
            runtime.osName,
            runtime.osVersion,
            runtime.architecture,
            runtime.deviceModel,
            runtime.appVersion,
            runtime.buildNumber,
            runtime.gitCommit,
            runtime.buildIdentity,
            runtime.buildTime,
            runtime.distribution,
            event.eventId,
            event.runId,
            event.category,
            event.eventName,
            event.message,
            event.connectionTraceContext?.correlationId,
            event.connectionTraceContext?.traceId,
            event.connectionTraceContext?.sessionId,
        ).sumOf { it.utf8Size() }
    val document = Document().apply {
        add(StringField(FIELD_DOC_KEY, key, Field.Store.YES))
        add(StringField(FIELD_DOC_TYPE, DOC_TYPE_EVENT, Field.Store.YES))
        add(StoredField(FIELD_RECORD_ID, recordId))
        add(NumericDocValuesField(FIELD_RECORD_ID, recordId))
        add(LongPoint(FIELD_RECORD_ID, recordId))
        add(StoredField(FIELD_BATCH_ID, batch.batchId))
        add(StringField(FIELD_UID, uid, Field.Store.YES))
        add(StringField(FIELD_DEVICE_ID, deviceId, Field.Store.YES))
        add(StringField(FIELD_PLATFORM, runtime.platform, Field.Store.YES))
        add(StringField(FIELD_OS_NAME, runtime.osName, Field.Store.YES))
        add(StringField(FIELD_OS_VERSION, runtime.osVersion, Field.Store.YES))
        add(StoredField(FIELD_ARCHITECTURE, runtime.architecture))
        add(StoredField(FIELD_DEVICE_MODEL, runtime.deviceModel))
        add(StringField(FIELD_APP_VERSION, runtime.appVersion, Field.Store.YES))
        add(StoredField(FIELD_BUILD_NUMBER, runtime.buildNumber))
        add(StringField(FIELD_GIT_COMMIT, runtime.gitCommit, Field.Store.YES))
        add(StoredField(FIELD_BUILD_IDENTITY, runtime.buildIdentity))
        add(StoredField(FIELD_BUILD_TIME, runtime.buildTime))
        add(StoredField(FIELD_PROTOCOL_VERSION, runtime.protocolVersion))
        add(StoredField(FIELD_DISTRIBUTION, runtime.distribution))
        add(StringField(FIELD_CATEGORY, event.category, Field.Store.YES))
        add(StringField(FIELD_EVENT_NAME, event.eventName, Field.Store.YES))
        add(StoredField(FIELD_EVENT_ID, event.eventId))
        add(StoredField(FIELD_RUN_ID, event.runId))
        add(StoredField(TELEMETRY_FIELD_SEQUENCE, event.sequence))
        add(StoredField(FIELD_OCCURRED_AT, event.occurredAt))
        add(StoredField(FIELD_MESSAGE, event.message))
        add(TextField(TELEMETRY_FIELD_TEXT, event.searchText, Field.Store.YES))
        event.outgoingQueue?.let { addOutgoingQueueMetrics(it) }
        event.connectionTraceContext?.let { addConnectionTraceContext(it) }
        addReceivedAt(receivedAt)
        addAccountedBytes(bytes)
    }
    return TelemetryAccountedDocument(document, bytes)
}

private fun Document.addConnectionTraceContext(context: ConnectionTraceContext) {
    add(StringField(FIELD_TRACE_CORRELATION_ID, context.correlationId, Field.Store.YES))
    add(StringField(FIELD_TRACE_ID, context.traceId, Field.Store.YES))
    add(StringField(FIELD_TRACE_SESSION_ID, context.sessionId, Field.Store.YES))
    addLongPointAndStored(FIELD_CONNECTION_GENERATION, context.connectionGeneration)
    addLongPointAndStored(FIELD_TRACE_POLICY_REVISION, context.policyRevision)
}

private fun Document.addOutgoingQueueMetrics(metrics: TelemetryOutgoingQueueMetrics) {
    addLongPointAndStored(FIELD_OUTGOING_PENDING_COUNT, metrics.pendingCount.toLong())
    addLongPointAndStored(FIELD_OUTGOING_RETRY_WAIT_COUNT, metrics.retryWaitCount.toLong())
    addLongPointAndStored(FIELD_OUTGOING_TERMINAL_FAILED_COUNT, metrics.terminalFailedCount.toLong())
    addLongPointAndStored(FIELD_OUTGOING_OLDEST_ACTIVE_AGE_MILLIS, metrics.oldestActiveAgeMillis)
    addLongPointAndStored(FIELD_OUTGOING_MAX_ATTEMPT_COUNT, metrics.maxAttemptCount)
}

private fun Document.addLongPointAndStored(field: String, value: Long) {
    add(LongPoint(field, value))
    add(StoredField(field, value))
}

private fun Document.addReceivedAt(value: Long) {
    add(LongPoint(FIELD_RECEIVED_AT, value))
    add(StoredField(FIELD_RECEIVED_AT, value))
    add(NumericDocValuesField(FIELD_RECEIVED_AT, value))
}

private fun Document.addAccountedBytes(value: Long) {
    add(StoredField(FIELD_ACCOUNTED_BYTES, value))
    add(NumericDocValuesField(FIELD_ACCOUNTED_BYTES, value))
}

internal fun Document.toStoredTelemetryEvent(): StoredTelemetryEvent {
    require(get(FIELD_DOC_TYPE) == DOC_TYPE_EVENT) { "telemetry search hit has an invalid document type" }
    return StoredTelemetryEvent(
        id = getField(FIELD_RECORD_ID).numericValue().toLong(),
        batchId = get(FIELD_BATCH_ID),
        uid = get(FIELD_UID),
        deviceId = get(FIELD_DEVICE_ID),
        receivedAt = getField(FIELD_RECEIVED_AT).numericValue().toLong(),
        runtime = TelemetryRuntimeSnapshot(
            platform = get(FIELD_PLATFORM),
            osName = get(FIELD_OS_NAME),
            osVersion = get(FIELD_OS_VERSION),
            architecture = get(FIELD_ARCHITECTURE),
            deviceModel = get(FIELD_DEVICE_MODEL),
            appVersion = get(FIELD_APP_VERSION),
            buildNumber = get(FIELD_BUILD_NUMBER),
            gitCommit = get(FIELD_GIT_COMMIT),
            buildIdentity = get(FIELD_BUILD_IDENTITY),
            buildTime = get(FIELD_BUILD_TIME),
            protocolVersion = getField(FIELD_PROTOCOL_VERSION).numericValue().toInt(),
            distribution = get(FIELD_DISTRIBUTION),
        ),
        event = TelemetryEventDraft(
            eventId = get(FIELD_EVENT_ID),
            runId = get(FIELD_RUN_ID),
            sequence = getField(TELEMETRY_FIELD_SEQUENCE).numericValue().toLong(),
            occurredAt = getField(FIELD_OCCURRED_AT).numericValue().toLong(),
            category = get(FIELD_CATEGORY),
            eventName = get(FIELD_EVENT_NAME),
            message = get(FIELD_MESSAGE),
            searchText = get(TELEMETRY_FIELD_TEXT),
            outgoingQueue = outgoingQueueMetricsOrNull(),
            connectionTraceContext = connectionTraceContextOrNull(),
        ),
    )
}

private fun Document.connectionTraceContextOrNull(): ConnectionTraceContext? {
    val correlationId = get(FIELD_TRACE_CORRELATION_ID)
    val traceId = get(FIELD_TRACE_ID)
    val sessionId = get(FIELD_TRACE_SESSION_ID)
    val generation = getField(FIELD_CONNECTION_GENERATION)?.numericValue()
    val policyRevision = getField(FIELD_TRACE_POLICY_REVISION)?.numericValue()
    if (correlationId == null && traceId == null && sessionId == null && generation == null && policyRevision == null) {
        return null
    }
    require(correlationId != null && traceId != null && sessionId != null && generation != null && policyRevision != null) {
        "telemetry connection trace context is incomplete"
    }
    return ConnectionTraceContext(
        correlationId = correlationId,
        traceId = traceId,
        sessionId = sessionId,
        connectionGeneration = generation.toLong(),
        policyRevision = policyRevision.toLong(),
    )
}

private fun Document.outgoingQueueMetricsOrNull(): TelemetryOutgoingQueueMetrics? {
    val fields = listOf(
        getField(FIELD_OUTGOING_PENDING_COUNT),
        getField(FIELD_OUTGOING_RETRY_WAIT_COUNT),
        getField(FIELD_OUTGOING_TERMINAL_FAILED_COUNT),
        getField(FIELD_OUTGOING_OLDEST_ACTIVE_AGE_MILLIS),
        getField(FIELD_OUTGOING_MAX_ATTEMPT_COUNT),
    )
    if (fields.all { it == null }) return null
    require(fields.all { it?.numericValue() != null }) {
        "telemetry outgoing queue fields are incomplete"
    }
    return TelemetryOutgoingQueueMetrics(
        pendingCount = checkNotNull(fields[0]?.numericValue()).toInt(),
        retryWaitCount = checkNotNull(fields[1]?.numericValue()).toInt(),
        terminalFailedCount = checkNotNull(fields[2]?.numericValue()).toInt(),
        oldestActiveAgeMillis = checkNotNull(fields[3]?.numericValue()).toLong(),
        maxAttemptCount = checkNotNull(fields[4]?.numericValue()).toLong(),
    )
}

internal fun buildTelemetryQuery(query: TelemetrySearchQuery, openedAnalyzer: Analyzer): Query {
    requireValidOutgoingQueueQuery(query)
    val builder = BooleanQuery.Builder()
    val keyword = query.keyword?.trim().orEmpty()
    builder.add(TermQuery(Term(FIELD_DOC_TYPE, DOC_TYPE_EVENT)), BooleanClause.Occur.FILTER)
    builder.add(
        if (keyword.isEmpty()) MatchAllDocsQuery()
        else QueryParser(TELEMETRY_FIELD_TEXT, openedAnalyzer).parse(QueryParser.escape(keyword)),
        BooleanClause.Occur.MUST,
    )
    addExact(builder, FIELD_UID, query.uid)
    addExact(builder, FIELD_DEVICE_ID, query.deviceId)
    addExact(builder, FIELD_PLATFORM, query.platform)
    addExact(builder, FIELD_OS_NAME, query.osName)
    addExact(builder, FIELD_OS_VERSION, query.osVersion)
    addExact(builder, FIELD_APP_VERSION, query.appVersion)
    addExact(builder, FIELD_GIT_COMMIT, query.gitCommit)
    addExact(builder, FIELD_CATEGORY, query.category)
    addExact(builder, FIELD_EVENT_NAME, query.eventName)
    query.outgoingQueue?.let { outgoing ->
        addRange(builder, FIELD_OUTGOING_PENDING_COUNT, outgoing.pendingCount)
        addRange(builder, FIELD_OUTGOING_RETRY_WAIT_COUNT, outgoing.retryWaitCount)
        addRange(builder, FIELD_OUTGOING_TERMINAL_FAILED_COUNT, outgoing.terminalFailedCount)
        addRange(
            builder,
            FIELD_OUTGOING_OLDEST_ACTIVE_AGE_MILLIS,
            outgoing.oldestActiveAgeMillis,
        )
        addRange(builder, FIELD_OUTGOING_MAX_ATTEMPT_COUNT, outgoing.maxAttemptCount)
    }
    builder.add(
        LongPoint.newRangeQuery(FIELD_RECEIVED_AT, query.receivedAtFrom, query.receivedAtUntil),
        BooleanClause.Occur.FILTER,
    )
    return builder.build()
}

internal fun requireValidOutgoingQueueQuery(query: TelemetrySearchQuery) {
    val outgoing = query.outgoingQueue ?: return
    requireValidRange(outgoing.pendingCount, ClientTelemetryLimits.MAX_OUTGOING_ACTIVE_COUNT.toLong())
    requireValidRange(outgoing.retryWaitCount, ClientTelemetryLimits.MAX_OUTGOING_ACTIVE_COUNT.toLong())
    requireValidRange(
        outgoing.terminalFailedCount,
        ClientTelemetryLimits.MAX_OUTGOING_TERMINAL_FAILED_COUNT.toLong(),
    )
    requireValidRange(
        outgoing.oldestActiveAgeMillis,
        ClientTelemetryLimits.MAX_OUTGOING_ACTIVE_AGE_MILLIS,
    )
    requireValidRange(outgoing.maxAttemptCount, ClientTelemetryLimits.MAX_OUTGOING_ATTEMPT_COUNT)
}

private fun requireValidRange(range: TelemetryNumericRange?, maximum: Long) {
    range ?: return
    require(range.minInclusive != null || range.maxInclusive != null) {
        "telemetry numeric range is empty"
    }
    val minimum = range.minInclusive ?: 0L
    val upper = range.maxInclusive ?: maximum
    require(minimum in 0L..maximum && upper in minimum..maximum) {
        "telemetry numeric range is invalid"
    }
}

private fun addRange(builder: BooleanQuery.Builder, field: String, range: TelemetryNumericRange?) {
    range ?: return
    builder.add(
        LongPoint.newRangeQuery(
            field,
            range.minInclusive ?: Long.MIN_VALUE,
            range.maxInclusive ?: Long.MAX_VALUE,
        ),
        BooleanClause.Occur.FILTER,
    )
}

private fun addExact(builder: BooleanQuery.Builder, field: String, value: String?) {
    value?.trim()?.takeIf(String::isNotEmpty)?.let {
        builder.add(TermQuery(Term(field, it)), BooleanClause.Occur.FILTER)
    }
}

internal fun requireTelemetrySearchText(keyword: String?) {
    if (keyword == null) return
    require(keyword.length <= MAX_KEYWORD_CHARS) { "telemetry keyword is too long" }
    require(keyword.none(Char::isISOControl)) { "telemetry keyword contains control characters" }
}

/** 对服务器内部调用方的纵深防御；HTTP 适配器先校验 wire 格式。 */
internal fun requireValidTelemetryDraft(uid: String, deviceId: String, batch: TelemetryBatchDraft) {
    requireBoundedKey(uid, MAX_OWNER_CHARS, "uid")
    require(AuthRules.validateDeviceId(deviceId) == null) { "telemetry deviceId is invalid" }
    requireBoundedKey(batch.batchId, ClientTelemetryLimits.MAX_ID_CHARS, "batchId")
    require(PAYLOAD_SHA256_PATTERN.matches(batch.payloadSha256)) { "telemetry payload hash is invalid" }
    require(batch.createdAt > 0L) { "telemetry batch creation time is invalid" }
    require(batch.events.size in 1..ClientTelemetryLimits.MAX_EVENTS_PER_BATCH) {
        "telemetry batch event count is invalid"
    }
    val runtime = batch.runtime
    requireBoundedText(runtime.platform, ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS, "platform")
    requireBoundedText(runtime.osName, ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS, "osName")
    requireBoundedText(runtime.osVersion, ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS, "osVersion")
    requireBoundedText(runtime.architecture, ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS, "architecture")
    requireBoundedText(runtime.deviceModel, ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS, "deviceModel")
    requireBoundedText(runtime.appVersion, ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS, "appVersion")
    requireBoundedText(runtime.buildNumber, ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS, "buildNumber")
    requireBoundedText(runtime.gitCommit, ClientTelemetryLimits.MAX_GIT_COMMIT_CHARS, "gitCommit")
    requireBoundedText(
        runtime.buildIdentity,
        ClientTelemetryLimits.MAX_BUILD_IDENTITY_CHARS,
        "buildIdentity",
    )
    requireBoundedText(runtime.buildTime, ClientTelemetryLimits.MAX_BUILD_TIME_CHARS, "buildTime")
    require(runtime.protocolVersion >= 0) { "telemetry protocol version is invalid" }
    requireBoundedText(runtime.distribution, ClientTelemetryLimits.MAX_RUNTIME_FIELD_CHARS, "distribution")

    val firstRunId = batch.events.first().runId
    val eventIds = HashSet<String>(batch.events.size)
    var previousSequence = Long.MIN_VALUE
    batch.events.forEach { event ->
        requireBoundedKey(event.eventId, ClientTelemetryLimits.MAX_ID_CHARS, "eventId")
        requireBoundedKey(event.runId, ClientTelemetryLimits.MAX_ID_CHARS, "runId")
        require(eventIds.add(event.eventId)) { "telemetry event id is duplicated within its batch" }
        require(event.runId == firstRunId) { "telemetry batch contains multiple run ids" }
        require(event.sequence >= 0L && event.sequence > previousSequence) {
            "telemetry event sequence is not strictly increasing"
        }
        previousSequence = event.sequence
        require(event.occurredAt > 0L) { "telemetry event occurrence time is invalid" }
        requireBoundedText(event.category, ClientTelemetryLimits.MAX_NAME_CHARS, "category")
        requireBoundedText(event.eventName, ClientTelemetryLimits.MAX_NAME_CHARS, "eventName")
        requireBoundedText(event.message, MAX_STORED_MESSAGE_CHARS, "message")
        requireBoundedText(event.searchText, MAX_STORED_SEARCH_TEXT_CHARS, "searchText")
        val outgoing = event.outgoingQueue
        if (outgoing == null) {
            require(event.category != TelemetryEventKind.OUTGOING_QUEUE.name) {
                "outgoing queue telemetry is missing typed metrics"
            }
        } else {
            require(event.category == TelemetryEventKind.OUTGOING_QUEUE.name) {
                "outgoing queue metrics have the wrong category"
            }
            require(event.eventName == TELEMETRY_OUTGOING_QUEUE_EVENT_NAME) {
                "outgoing queue event name is not canonical"
            }
            require(event.message == OUTGOING_QUEUE_STORED_MESSAGE &&
                event.searchText == OUTGOING_QUEUE_STORED_SEARCH_TEXT
            ) { "outgoing queue telemetry contains non-canonical text" }
            require(outgoing.pendingCount in 0..ClientTelemetryLimits.MAX_OUTGOING_ACTIVE_COUNT)
            require(outgoing.retryWaitCount in 0..ClientTelemetryLimits.MAX_OUTGOING_ACTIVE_COUNT)
            require(
                outgoing.pendingCount + outgoing.retryWaitCount <=
                    ClientTelemetryLimits.MAX_OUTGOING_ACTIVE_COUNT,
            )
            require(
                outgoing.terminalFailedCount in
                    0..ClientTelemetryLimits.MAX_OUTGOING_TERMINAL_FAILED_COUNT,
            )
            require(
                outgoing.oldestActiveAgeMillis in
                    0L..ClientTelemetryLimits.MAX_OUTGOING_ACTIVE_AGE_MILLIS,
            )
            require(outgoing.maxAttemptCount in 0L..ClientTelemetryLimits.MAX_OUTGOING_ATTEMPT_COUNT)
            if (outgoing.pendingCount + outgoing.retryWaitCount == 0) {
                require(outgoing.oldestActiveAgeMillis == 0L)
            }
            if (outgoing.pendingCount + outgoing.retryWaitCount + outgoing.terminalFailedCount == 0) {
                require(outgoing.maxAttemptCount == 0L)
            }
        }
    }
}

private fun requireBoundedKey(value: String, maxChars: Int, field: String) {
    require(value.length in 1..maxChars && '\u0000' !in value && value.none(Char::isISOControl)) {
        "telemetry $field is invalid"
    }
}

private fun requireBoundedText(value: String, maxChars: Int, field: String) {
    require(value.length in 1..maxChars && '\u0000' !in value) { "telemetry $field is invalid" }
}

internal fun telemetryReceiptKey(uid: String, deviceId: String, batchId: String) =
    "$DOC_TYPE_RECEIPT\u0000$uid\u0000$deviceId\u0000$batchId"

internal fun telemetryEventKey(uid: String, deviceId: String, eventId: String) =
    "$DOC_TYPE_EVENT\u0000$uid\u0000$deviceId\u0000$eventId"

private fun String.utf8Size(): Long = toByteArray(Charsets.UTF_8).size.toLong()
