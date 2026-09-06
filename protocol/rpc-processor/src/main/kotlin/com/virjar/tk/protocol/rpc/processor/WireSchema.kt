package com.virjar.tk.protocol.rpc.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.*
import java.io.File

data class AvailabilityModel(val since: Int, val removed: Int?) {
    val expression: String get() = "com.virjar.tk.protocol.ProtocolAvailability($since, ${removed ?: "null"})"
}

internal class ProtocolBuildPolicy(options: Map<String, String>) {
    val major = options["teamtalk.protocolMajor"]?.toInt() ?: 0
    val current = options["teamtalk.protocolMinor"]?.toInt() ?: 0
    val minimum = options["teamtalk.minimumProtocolMinor"]?.toInt() ?: 0
    val baseline = options["teamtalk.protocolBaseline"]?.let(::File)
    val publishedBaseline = options["teamtalk.publishedProtocolBaseline"]?.let(::File)
    val record = options["teamtalk.recordProtocolBaseline"] == "true"

    fun availability(node: KSAnnotated, parent: KSAnnotated?, logger: KSPLogger): AvailabilityModel {
        fun value(annotation: String): Int? = sequenceOf(node, parent).filterNotNull().mapNotNull {
            it.findAnnotation("com.virjar.tk.protocol.$annotation")?.arguments
                ?.firstOrNull { argument -> argument.name?.asString() == "minor" }?.value as? Int
        }.firstOrNull()
        val since = value("SinceProtocol") ?: 0
        val removed = value("RemovedInProtocol")
        if (since !in 0..current) logger.error("@SinceProtocol minor=$since must be in 0..$current", node)
        if (removed != null && (removed <= since || removed > 65535)) {
            logger.error("@RemovedInProtocol minor=$removed must be greater than since=$since and at most 65535", node)
        }
        if (removed != null && removed <= minimum) {
            logger.error("Protocol implementation removed in minor=$removed must be retired at minimum=$minimum; retain its ID tombstone", node)
        }
        return AvailabilityModel(since, removed)
    }
}

/** 构造字段/IDL/数字 ID 的结构事实；不是手写 writeTo/readFrom 方法体的等价性证明。 */
internal data class WireSchemaEntry(
    val kind: String,
    val key: String,
    val availability: AvailabilityModel,
    val signature: String,
    val retired: Boolean = false,
) {
    val identity: String get() = "$kind:$key"
    fun line(): String = listOf(kind, key, availability.since, availability.removed ?: "-", if (retired) "retired" else "active", signature).joinToString("\t")
}

internal data class WireSchema(val major: Int, val minor: Int, val entries: List<WireSchemaEntry>) {
    fun text(): String = "# TeamTalk wire schema v1\nmajor=$major\nminor=$minor\n" +
        entries.sortedBy { it.identity }.joinToString("\n", postfix = "\n", transform = WireSchemaEntry::line)

    companion object {
        fun parse(text: String): WireSchema {
            val lines = text.lines().filter { it.isNotBlank() && !it.startsWith('#') }
            require(lines.size >= 2 && lines[0].startsWith("major=") && lines[1].startsWith("minor=")) {
                "Invalid wire baseline header"
            }
            return WireSchema(lines[0].substringAfter('=').toInt(), lines[1].substringAfter('=').toInt(), lines.drop(2).map { line ->
                val values = line.split('\t')
                require(values.size == 6) { "Invalid wire baseline entry" }
                WireSchemaEntry(values[0], values[1], AvailabilityModel(values[2].toInt(), values[3].takeUnless { it == "-" }?.toInt()), values[5], values[4] == "retired")
            })
        }
    }
}

/** 比较指定事实源；发行快照不可改，开发清单可通过显式登记收敛未发布增量。 */
internal fun mergeWireSchema(
    previous: WireSchema?,
    current: WireSchema,
    minimumMinor: Int,
    reservedRpcIds: Set<String>,
    report: (String) -> Unit,
): WireSchema {
    if (previous == null) return current
    if (previous.major > current.major) report("Protocol major must not move backwards")
    if (previous.major != current.major) return current
    if (current.minor < previous.minor) report("Protocol minor must not move backwards within a major")
    val oldById = previous.entries.associateBy { it.identity }
    val currentById = current.entries.associateBy { it.identity }
    val retained = mutableListOf<WireSchemaEntry>()
    current.entries.forEach { entry ->
        val old = oldById[entry.identity]
        if (old == null) {
            if (entry.availability.since <= previous.minor) {
                report("New wire ${entry.identity} must declare @SinceProtocol above registered minor=${previous.minor}")
            }
        } else {
            if (old.retired) report("Wire ID tombstone cannot be reused within this major: ${entry.identity}")
            if (entry.signature != old.signature || entry.availability.since != old.availability.since) {
                report("Existing wire signature cannot change within this major: ${entry.identity}; append a new ID/type or advance major")
            }
            if (old.availability.removed != entry.availability.removed) {
                if (old.availability.removed != null || entry.availability.removed == null || entry.availability.removed <= previous.minor) {
                    report("Wire removal must first be declared in a newer minor and cannot be rewritten: ${entry.identity}")
                }
            }
        }
    }
    previous.entries.filter { it.identity !in currentById }.forEach { old ->
        if (!old.retired) {
            if (old.availability.removed == null || old.availability.removed > minimumMinor) {
                report("Wire implementation cannot disappear before its declared removal reaches minimum: ${old.identity}")
            }
            if (old.kind == "rpc" && old.key !in reservedRpcIds) {
                report("Removed RPC must retain @RpcReservedMethodIds: ${old.key}")
            }
        }
        retained += old.copy(retired = true)
    }
    return current.copy(entries = current.entries + retained)
}

internal class WireSchemaGenerator(
    private val policy: ProtocolBuildPolicy,
    private val logger: KSPLogger,
    private val generator: CodeGenerator,
) {
    fun generate(resolver: Resolver, services: List<ServiceModel>) {
        val entries = mutableListOf<WireSchemaEntry>()
        services.forEach { service ->
            service.methods.forEach { method ->
                val params = method.params.joinToString(",") { "${it.name}:${it.typeName}${if (it.nullable) "?" else ""}" }
                val result = if (method.ret.isList) "List<${method.ret.listArg}>" else method.ret.typeName
                entries += WireSchemaEntry("rpc", "${service.name}/${method.id}", method.availability, "${method.name}($params):$result")
            }
        }
        val classes = resolver.getAllFiles().flatMap { it.declarations }.flatMap(::collectClasses).toList()
        classes.filter { it.classKind != ClassKind.ENUM_ENTRY && it.asStarProjectedType().isIProto() }.forEach { declaration ->
            val name = declaration.qualifiedName?.asString() ?: return@forEach
            val fields = declaration.primaryConstructor?.parameters.orEmpty()
                .filter { it.findAnnotation("com.virjar.tk.protocol.ProtocolLocal") == null }
                .joinToString(",") { "${it.name?.asString()}:${typeName(it.type.resolve())}" }
            entries += WireSchemaEntry("type", name, policy.availability(declaration, null, logger), "${declaration.classKind}($fields)")
        }
        classes.filter {
            it.classKind == ClassKind.ENUM_CLASS && it.primaryConstructor?.parameters?.any { parameter ->
                parameter.name?.asString() == "code" &&
                    parameter.type.resolve().declaration.qualifiedName?.asString() in setOf("kotlin.Int", "kotlin.Byte")
            } == true
        }.forEach { declaration ->
            val source = declaration.containingFile?.filePath?.let { File(it).readText() }.orEmpty()
            declaration.declarations.filterIsInstance<KSClassDeclaration>().filter { it.classKind == ClassKind.ENUM_ENTRY }.forEach { entry ->
                val name = entry.simpleName.asString()
                val code = Regex("\\b${Regex.escape(name)}\\s*\\(\\s*(\\d+)\\s*\\)").findAll(source).toList().singleOrNull()?.groupValues?.get(1)?.toIntOrNull()
                if (code == null) logger.error("Wire enum $name must have one explicit integer code literal", entry)
                else entries += WireSchemaEntry("enum", "${declaration.qualifiedName?.asString()}/$code", policy.availability(entry, declaration, logger), name)
            }
        }
        entries.groupBy { it.identity }.filterValues { it.size > 1 }.forEach { (key, _) -> logger.error("Duplicate wire identity: $key") }
        // A source set emptied by retirement must still be checked against its committed tombstones.
        if (entries.isEmpty() && policy.baseline == null) return
        val current = WireSchema(policy.major, policy.current, entries)
        val previous = policy.baseline?.takeIf(File::isFile)?.let { WireSchema.parse(it.readText()) }
        val published = policy.publishedBaseline?.let { WireSchema.parse(it.readText()) }
        val reservedIds = services.flatMap { service -> service.reservedMethodIds.map { "${service.name}/$it" } }.toSet()
        val merged = reconcileWireSchema(previous, published, current, policy.minimum, reservedIds, policy.record) { logger.error(it) }
        if (policy.baseline != null && !policy.record && previous?.text() != merged.text()) {
            logger.error("Wire baseline is missing or unregistered; review changes and run :protocol:protocol:writeProtocolBaseline")
        }
        generator.createNewFileByPath(Dependencies.ALL_FILES, "wire-schema", "tsv").use { it.write(merged.text().toByteArray()) }
        generateRegistry(entries)
    }

    private fun collectClasses(declaration: KSDeclaration): Sequence<KSClassDeclaration> = sequence {
        if (declaration is KSClassDeclaration) {
            yield(declaration)
            declaration.declarations.forEach { yieldAll(collectClasses(it)) }
        }
    }

    private fun typeName(type: KSType): String = buildString {
        append(type.declaration.qualifiedName?.asString())
        if (type.arguments.isNotEmpty()) append(type.arguments.joinToString(",", "<", ">") { argument -> argument.type?.resolve()?.let(::typeName) ?: "*" })
        if (type.isMarkedNullable) append('?')
    }

    private fun generateRegistry(entries: List<WireSchemaEntry>) {
        val source = buildString {
            appendLine("package com.virjar.tk.protocol")
            appendLine("/** Generated wire availability; model codecs still require reviewed golden tests. */")
            appendLine("object ProtocolWireRegistry {")
            listOf("PacketType", "NotifyType", "MessageType").forEach { type ->
                val rows = entries.filter { it.kind == "enum" && it.key.startsWith("com.virjar.tk.protocol.$type/") }
                appendLine("    private val ${type.replaceFirstChar(Char::lowercase)}Versions: Map<Int, ProtocolAvailability> = mapOf(")
                rows.forEach { appendLine("        ${it.key.substringAfterLast('/')} to ${it.availability.expression},") }
                appendLine("    )")
                appendLine("    fun supports$type(code: Int, version: ProtocolVersion): Boolean = ${type.replaceFirstChar(Char::lowercase)}Versions[code]?.supports(version) == true")
            }
            appendLine("}")
        }
        generator.createNewFileByPath(Dependencies.ALL_FILES, "ProtocolWireRegistry", "kt").use { it.write(source.toByteArray()) }
    }
}

/** A changed development TSV must never mask a published signature or tombstone. */
internal fun reconcileWireSchema(
    development: WireSchema?,
    published: WireSchema?,
    current: WireSchema,
    minimumMinor: Int,
    reservedRpcIds: Set<String>,
    recordDevelopment: Boolean,
    report: (String) -> Unit,
): WireSchema {
    // Always protect the frozen release, even if somebody also edited the development baseline.
    val frozenChecked = mergeWireSchema(published, current, minimumMinor, reservedRpcIds, report)
    // Explicit recording may reclaim only unpublished work. Ordinary builds still require its review.
    return if (recordDevelopment && published != null) frozenChecked
    else mergeWireSchema(development, current, minimumMinor, reservedRpcIds, report)
}
