package com.virjar.tk.shared.client

/**
 * 本客户端安装所创建的遥测命名空间的严格有界清单。
 *
 * 根维护绝不会通过枚举任意目录来发现删除目标。每个合格的身份都必须先在 root 生命周期锁下被原子
 * 注册。游标是最后尝试的身份而非数组下标，因此删除与有序插入不会让后续页永久不可达。
 */
internal data class ClientTelemetryNamespaceRegistry(
    val cursor: List<String>?,
    val identities: List<List<String>>,
    val cycleDeadlineEpochMs: Long? = null,
    val cycleNeedsImmediateRetry: Boolean = false,
) {
    fun register(identityDirectories: List<String>): ClientTelemetryNamespaceRegistry {
        val identity = validatedTelemetryRegistryIdentity(identityDirectories)
        if (identities.any { it == identity }) return this
        check(identities.size < MAX_TELEMETRY_REGISTERED_NAMESPACES) {
            "Telemetry namespace registry reached its hard limit"
        }
        return copy(identities = (identities + listOf(identity)).sortedWith(TELEMETRY_IDENTITY_COMPARATOR))
    }

    fun remove(identityDirectories: List<String>): ClientTelemetryNamespaceRegistry {
        val identity = validatedTelemetryRegistryIdentity(identityDirectories)
        val remaining = identities.filterNot { it == identity }
        return if (remaining.size == identities.size) this else copy(identities = remaining)
    }

    fun page(maxIdentities: Int): TelemetryNamespaceRegistryPage {
        require(maxIdentities > 0) { "Telemetry namespace registry page must be positive" }
        if (identities.isEmpty()) {
            return TelemetryNamespaceRegistryPage(emptyList(), copy(cursor = null), truncated = false)
        }
        val start = cursor?.let { fixedCursor ->
            identities.indexOfFirst { identity ->
                TELEMETRY_IDENTITY_COMPARATOR.compare(identity, fixedCursor) > 0
            }
        } ?: 0
        if (start < 0) {
            return TelemetryNamespaceRegistryPage(emptyList(), copy(cursor = null), truncated = false)
        }
        val selected = identities.drop(start).take(maxIdentities)
        val hasMoreInCurrentCycle = start + selected.size < identities.size
        return TelemetryNamespaceRegistryPage(
            identities = selected,
            registry = copy(cursor = if (hasMoreInCurrentCycle) selected.last() else null),
            truncated = hasMoreInCurrentCycle,
        )
    }

    companion object {
        fun empty(): ClientTelemetryNamespaceRegistry = ClientTelemetryNamespaceRegistry(
            cursor = null,
            identities = emptyList(),
            cycleDeadlineEpochMs = null,
            cycleNeedsImmediateRetry = false,
        )
    }
}

internal data class TelemetryNamespaceRegistryPage(
    val identities: List<List<String>>,
    val registry: ClientTelemetryNamespaceRegistry,
    val truncated: Boolean,
)

internal fun encodeClientTelemetryNamespaceRegistry(registry: ClientTelemetryNamespaceRegistry): String {
    val normalized = validatedTelemetryNamespaceRegistry(registry)
    val content = buildString {
        append(CLIENT_TELEMETRY_REGISTRY_HEADER)
        append('\n')
        append("cursor=")
        append(normalized.cursor?.let(::encodeTelemetryRegistryIdentity).orEmpty())
        append('\n')
        append("deadline=")
        append(normalized.cycleDeadlineEpochMs?.toString().orEmpty())
        append('\n')
        append("retry=")
        append(if (normalized.cycleNeedsImmediateRetry) "1" else "0")
        normalized.identities.forEach { identity ->
            append('\n')
            append("identity=")
            append(encodeTelemetryRegistryIdentity(identity))
        }
    }
    check(content.encodeToByteArray().size <= MAX_TELEMETRY_NAMESPACE_REGISTRY_BYTES) {
        "Telemetry namespace registry exceeds its byte limit"
    }
    return content
}

internal fun decodeClientTelemetryNamespaceRegistry(content: String): ClientTelemetryNamespaceRegistry {
    require(content.encodeToByteArray().size <= MAX_TELEMETRY_NAMESPACE_REGISTRY_BYTES) {
        "Telemetry namespace registry exceeds its byte limit"
    }
    require('\r' !in content && content.isNotEmpty()) { "Telemetry namespace registry is malformed" }
    var newlineCount = 0
    content.forEach { character ->
        if (character == '\n') {
            newlineCount++
            require(newlineCount <= MAX_TELEMETRY_REGISTERED_NAMESPACES + REGISTRY_METADATA_LINES - 1) {
                "Telemetry namespace registry has too many entries"
            }
        }
    }
    val lines = content.lineSequence().iterator()
    require(lines.hasNext() && lines.next() == CLIENT_TELEMETRY_REGISTRY_HEADER) {
        "Telemetry namespace registry version is invalid"
    }
    require(lines.hasNext()) { "Telemetry namespace registry cursor is missing" }
    val cursorLine = lines.next()
    require(cursorLine.startsWith("cursor=")) { "Telemetry namespace registry cursor is missing" }
    val cursorValue = cursorLine.removePrefix("cursor=")
    val cursor = cursorValue.takeIf(String::isNotEmpty)?.let(::decodeTelemetryRegistryIdentity)
    require(lines.hasNext()) { "Telemetry namespace registry deadline is missing" }
    val deadlineLine = lines.next()
    require(deadlineLine.startsWith("deadline=")) { "Telemetry namespace registry deadline is missing" }
    val deadlineValue = deadlineLine.removePrefix("deadline=")
    val cycleDeadlineEpochMs = deadlineValue.takeIf(String::isNotEmpty)?.toLongOrNull()
    require(deadlineValue.isEmpty() || cycleDeadlineEpochMs != null && cycleDeadlineEpochMs >= 0L) {
        "Telemetry namespace registry deadline is malformed"
    }
    require(lines.hasNext()) { "Telemetry namespace registry retry flag is missing" }
    val retryLine = lines.next()
    require(retryLine == "retry=0" || retryLine == "retry=1") {
        "Telemetry namespace registry retry flag is malformed"
    }
    val identities = mutableListOf<List<String>>()
    while (lines.hasNext()) {
        val line = lines.next()
        require(line.startsWith("identity=")) { "Telemetry namespace registry entry is malformed" }
        identities += decodeTelemetryRegistryIdentity(line.removePrefix("identity="))
    }
    return validatedTelemetryNamespaceRegistry(
        ClientTelemetryNamespaceRegistry(
            cursor = cursor,
            identities = identities,
            cycleDeadlineEpochMs = cycleDeadlineEpochMs,
            cycleNeedsImmediateRetry = retryLine == "retry=1",
        ),
    )
}

private fun validatedTelemetryNamespaceRegistry(
    registry: ClientTelemetryNamespaceRegistry,
): ClientTelemetryNamespaceRegistry {
    require(registry.identities.size <= MAX_TELEMETRY_REGISTERED_NAMESPACES) {
        "Telemetry namespace registry has too many entries"
    }
    val identities = registry.identities.map(::validatedTelemetryRegistryIdentity)
    require(identities.distinct().size == identities.size) {
        "Telemetry namespace registry contains duplicate entries"
    }
    require(identities == identities.sortedWith(TELEMETRY_IDENTITY_COMPARATOR)) {
        "Telemetry namespace registry entries are not in stable order"
    }
    val cursor = registry.cursor?.let(::validatedTelemetryRegistryIdentity)
    registry.cycleDeadlineEpochMs?.let { deadline ->
        require(deadline >= 0L) { "Telemetry namespace registry deadline is invalid" }
    }
    return ClientTelemetryNamespaceRegistry(
        cursor = cursor,
        identities = identities,
        cycleDeadlineEpochMs = registry.cycleDeadlineEpochMs,
        cycleNeedsImmediateRetry = registry.cycleNeedsImmediateRetry,
    )
}

private fun validatedTelemetryRegistryIdentity(identityDirectories: List<String>): List<String> =
    identityDirectories.toList().also { identity ->
        require(isTelemetryIdentityDirectories(identity)) {
            "Telemetry namespace registry identity is invalid"
        }
    }

private fun encodeTelemetryRegistryIdentity(identityDirectories: List<String>): String =
    validatedTelemetryRegistryIdentity(identityDirectories).joinToString("/")

private fun decodeTelemetryRegistryIdentity(encoded: String): List<String> {
    require(
        encoded.isNotEmpty() &&
            encoded.length <= MAX_TELEMETRY_REGISTRY_IDENTITY_CHARS &&
            encoded.count { it == '/' } == 2 &&
            encoded.none(Char::isWhitespace)
    ) {
        "Telemetry namespace registry identity is malformed"
    }
    return validatedTelemetryRegistryIdentity(encoded.split('/', limit = 3))
}

private val TELEMETRY_IDENTITY_COMPARATOR = Comparator<List<String>> { left, right ->
    var index = 0
    while (index < minOf(left.size, right.size)) {
        val compared = left[index].compareTo(right[index])
        if (compared != 0) return@Comparator compared
        index++
    }
    left.size.compareTo(right.size)
}

internal const val CLIENT_TELEMETRY_REGISTRY_FILE = "telemetry-namespaces.registry"
internal const val MAX_TELEMETRY_REGISTERED_NAMESPACES = 16_384
internal const val MAX_TELEMETRY_NAMESPACE_REGISTRY_BYTES = 3 * 1024 * 1024
private const val MAX_TELEMETRY_REGISTRY_IDENTITY_CHARS = 3 * 40 + 2
private const val REGISTRY_METADATA_LINES = 4
private const val CLIENT_TELEMETRY_REGISTRY_HEADER = "teamtalk-client-telemetry-registry-v2"
