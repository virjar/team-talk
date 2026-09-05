package com.virjar.tk.server.e2e

import com.virjar.tk.protocol.model.AuthRules
import com.virjar.tk.shared.client.JvmPrivateAtomicTextFile
import com.virjar.tk.shared.client.JvmPrivateDataDirectory
import com.virjar.tk.protocol.model.DocumentPolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

private const val FIXTURE_SCHEMA_VERSION = 2
private const val FIXTURE_GENERATOR_VERSION = 1
private const val FIXTURE_ROOT_COUNT = 6
private const val FIXTURE_MIDDLE_COUNT_PER_ROOT = 4
private const val FIXTURE_LEAF_COUNT_PER_MIDDLE = 5
internal const val DOCUMENT_FIXTURE_DOCUMENT_COUNT = 150
internal const val DOCUMENT_FIXTURE_ACCOUNT_FILE = "account.properties"
internal const val DOCUMENT_FIXTURE_MANIFEST_FILE = "document-fixture.json"
internal const val DOCUMENT_FIXTURE_LOCK_FILE = "document-fixture.lock"
private const val MAX_ACCOUNT_FILE_BYTES = 4_096L
private const val MAX_MANIFEST_FILE_BYTES = 128L * 1_024L

private const val ENV_ACTION = "TK_E2E_FIXTURE_ACTION"
private const val ENV_STATE_DIR = "TK_E2E_FIXTURE_STATE_DIR"
private const val ENV_CONFIRM_TARGET = "TK_E2E_CONFIRM_TARGET"

private const val PROP_HOST = "tk.e2e.host"
private const val PROP_PORT = "tk.e2e.port"
private const val PROP_PROJECT_ROOT = "tk.e2e.projectRoot"

private val fixtureJson = Json {
    encodeDefaults = true
    prettyPrint = true
    ignoreUnknownKeys = false
}

internal enum class DocumentFixtureAction(val wireName: String) {
    SEED("seed"),
    ARCHIVE("archive");

    companion object {
        fun parse(value: String?): DocumentFixtureAction = entries.singleOrNull { it.wireName == value }
            ?: throw IllegalArgumentException("$ENV_ACTION must be exactly seed or archive")
    }
}

internal class DocumentFixtureCredentials(
    val username: String,
    val password: String,
) {
    override fun toString(): String =
        "DocumentFixtureCredentials(username=<redacted>, password=<redacted>)"
}

internal class DocumentFixtureInvocation(
    val action: DocumentFixtureAction,
    val target: String,
    val credentials: DocumentFixtureCredentials,
    val files: DocumentFixtureFiles,
    private val lifecycleLock: DocumentFixtureLifecycleLock,
) : AutoCloseable {
    override fun close() = lifecycleLock.close()

    override fun toString(): String =
        "DocumentFixtureInvocation(action=$action, target=$target, credentials=<redacted>, " +
            "stateDir=${files.stateDir})"
}

internal fun loadDocumentFixtureInvocationFromProcess(): DocumentFixtureInvocation =
    loadDocumentFixtureInvocation(
        environment = System.getenv(),
        systemProperties = listOf(PROP_HOST, PROP_PORT, PROP_PROJECT_ROOT).mapNotNull { key ->
            System.getProperty(key)?.let { value -> key to value }
        }.toMap(),
    )

internal fun loadDocumentFixtureInvocation(
    environment: Map<String, String>,
    systemProperties: Map<String, String>,
): DocumentFixtureInvocation {
    val action = DocumentFixtureAction.parse(environment[ENV_ACTION])
    val host = systemProperties[PROP_HOST]
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: throw IllegalArgumentException("$PROP_HOST is required")
    val port = systemProperties[PROP_PORT]
        ?.toIntOrNull()
        ?.takeIf { it in 1..65_535 }
        ?: throw IllegalArgumentException("$PROP_PORT must be a valid TCP port")
    val target = confirmedDocumentFixtureTarget(environment[ENV_CONFIRM_TARGET], host, port)
    val projectRoot = systemProperties[PROP_PROJECT_ROOT]
        ?.let(Path::of)
        ?: throw IllegalArgumentException("$PROP_PROJECT_ROOT is required")
    val stateDir = environment[ENV_STATE_DIR]
        ?.let(Path::of)
        ?: throw IllegalArgumentException("$ENV_STATE_DIR is required")
    val files = DocumentFixtureFiles.open(projectRoot, stateDir)
    val lifecycleLock = files.acquireLifecycleLock()
    return try {
        val credentials = files.readCredentials()
        DocumentFixtureInvocation(action, target, credentials, files, lifecycleLock)
    } catch (failure: Throwable) {
        try {
            lifecycleLock.close()
        } catch (closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
        }
        throw failure
    }
}

internal fun confirmedDocumentFixtureTarget(confirmation: String?, host: String, port: Int): String {
    require(host.isNotBlank()) { "Configured fixture host is blank" }
    require(port in 1..65_535) { "Configured fixture port is invalid" }
    val target = "$host:$port"
    require(confirmation == target) {
        "$ENV_CONFIRM_TARGET must exactly match the configured deployment target $target"
    }
    return target
}

/**
 * 仓库外的 manifest 是确定性拓扑与重试进度的本地事实来源。本文件刻意将计划生成器
 * 与序列化证据放在一起，使两者都无法独立演化并悄然复用调用方拥有的命令身份。
 */
internal class DocumentFixtureFiles private constructor(
    private val storage: JvmPrivateDataDirectory,
    private val accountFile: JvmPrivateAtomicTextFile,
    private val manifestFile: JvmPrivateAtomicTextFile,
) {
    @Volatile
    private var lifecycleLock: DocumentFixtureLifecycleLock? = null

    val stateDir: Path get() = storage.root
    val manifestPath: Path get() = storage.root.resolve(DOCUMENT_FIXTURE_MANIFEST_FILE)

    fun acquireLifecycleLock(): DocumentFixtureLifecycleLock {
        storage.validatePrivateTree()
        val lockPath = storage.preparePrivateFile(
            privateDirectories = emptyList(),
            fileName = DOCUMENT_FIXTURE_LOCK_FILE,
        ).toPath()
        storage.validatePrivateTree()
        val channel = FileChannel.open(
            lockPath,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        )
        return try {
            val fileLock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            } ?: throw DocumentFixtureLockUnavailableException()
            storage.validatePrivateTree()
            DocumentFixtureLifecycleLock(channel, fileLock).also { lifecycleLock = it }
        } catch (failure: Throwable) {
            try {
                channel.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }

    fun readCredentials(): DocumentFixtureCredentials {
        requireLifecycleLock()
        storage.validatePrivateTree()
        val text = accountFile.readText(MAX_ACCOUNT_FILE_BYTES)
            ?: throw IllegalArgumentException(
                "Private state directory must contain $DOCUMENT_FIXTURE_ACCOUNT_FILE",
            )
        return parseDocumentFixtureCredentials(text)
    }

    fun loadOrCreateManifest(target: String, nowEpochMs: Long = System.currentTimeMillis()): DocumentFixtureManifest {
        requireLifecycleLock()
        val existing = manifestFile.readText(MAX_MANIFEST_FILE_BYTES)
        if (existing != null) {
            return decodeDocumentFixtureManifest(existing).also {
                validateDocumentFixtureManifest(it, target)
            }
        }

        val fixtureId = UUID.randomUUID().toString()
        val plan = documentFixturePlan(fixtureId)
        val created = DocumentFixtureManifest(
            schemaVersion = FIXTURE_SCHEMA_VERSION,
            generatorVersion = FIXTURE_GENERATOR_VERSION,
            fixtureId = fixtureId,
            target = target,
            spaceId = plan.spaceId,
            archiveOperationId = plan.archiveOperationId,
            planFingerprint = documentFixturePlanFingerprint(plan),
            documentCount = plan.nodes.size,
            rootCount = plan.rootCount,
            middleCount = plan.middleCount,
            leafCount = plan.leafCount,
            ownerUid = null,
            datasetId = null,
            status = DocumentFixtureStatus.PLANNED,
            createdDocuments = 0,
            createdAtEpochMs = nowEpochMs,
            completedAtEpochMs = null,
            archivedAtEpochMs = null,
            representatives = plan.representatives,
        )
        writeManifest(created)
        return created
    }

    fun writeManifest(manifest: DocumentFixtureManifest) {
        requireLifecycleLock()
        validateDocumentFixtureManifest(manifest, manifest.target)
        manifestFile.replaceText(encodeDocumentFixtureManifest(manifest), MAX_MANIFEST_FILE_BYTES)
        storage.validatePrivateTree()
    }

    private fun requireLifecycleLock() {
        check(lifecycleLock?.isHeld == true) {
            "Document fixture private state requires its exclusive lifecycle lock"
        }
    }

    companion object {
        fun open(projectRoot: Path, stateDir: Path): DocumentFixtureFiles {
            require(stateDir.isAbsolute) { "$ENV_STATE_DIR must be an absolute path" }
            val projectReal = projectRoot.toAbsolutePath().normalize().toRealPath(LinkOption.NOFOLLOW_LINKS)
            val normalizedState = stateDir.toAbsolutePath().normalize()
            val storage = JvmPrivateDataDirectory.openExisting(
                normalizedState.toFile(),
                projectReal.toFile(),
            )
            require(!storage.root.startsWith(projectReal)) {
                "$ENV_STATE_DIR must be outside the TeamTalk repository"
            }
            return DocumentFixtureFiles(
                storage = storage,
                accountFile = storage.atomicTextFile(fileName = DOCUMENT_FIXTURE_ACCOUNT_FILE),
                manifestFile = storage.atomicTextFile(fileName = DOCUMENT_FIXTURE_MANIFEST_FILE),
            )
        }
    }
}

internal class DocumentFixtureLockUnavailableException : IllegalStateException(
    "Another document fixture command already owns this private state directory",
)

internal class DocumentFixtureLifecycleLock internal constructor(
    private val channel: FileChannel,
    private val fileLock: FileLock,
) : AutoCloseable {
    private var closed = false

    val isHeld: Boolean
        @Synchronized get() = !closed && channel.isOpen && fileLock.isValid

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        try {
            fileLock.release()
        } catch (releaseFailure: Throwable) {
            failure = releaseFailure
        }
        try {
            channel.close()
        } catch (closeFailure: Throwable) {
            if (failure == null) failure = closeFailure else failure.addSuppressed(closeFailure)
        }
        failure?.let { throw it }
    }
}

internal fun parseDocumentFixtureCredentials(text: String): DocumentFixtureCredentials {
    require(text.encodeToByteArray().size.toLong() <= MAX_ACCOUNT_FILE_BYTES) {
        "Credential file exceeds its size limit"
    }
    val values = linkedMapOf<String, String>()
    val lines = text.split('\n')
    lines.forEachIndexed { index, rawLine ->
        val line = rawLine.removeSuffix("\r")
        if (index == lines.lastIndex && line.isEmpty()) return@forEachIndexed
        require(line.isNotEmpty()) { "Credential file contains an empty record" }
        val separator = line.indexOf('=')
        require(separator > 0) { "Credential file contains a malformed record" }
        val key = line.substring(0, separator)
        val value = line.substring(separator + 1)
        require(key == "username" || key == "password") {
            "Credential file contains an unknown key"
        }
        require(values.putIfAbsent(key, value) == null) {
            "Credential file contains a duplicate key"
        }
    }
    require(values.keys == setOf("username", "password")) {
        "Credential file must contain exactly username and password"
    }
    val username = values.getValue("username")
    val password = values.getValue("password")
    require('\u0000' !in username && '\u0000' !in password) {
        "Credential file contains an unsupported value"
    }
    AuthRules.validateLogin(username, password)
    return DocumentFixtureCredentials(username, password)
}

@Serializable
internal enum class DocumentFixtureStatus {
    PLANNED,
    SEEDING,
    READY,
    ARCHIVED,
    OBSOLETE_DATASET,
}

@Serializable
internal data class DocumentFixtureRepresentatives(
    val rootWithChildrenId: String,
    val middleWithChildrenId: String,
    val leafId: String,
    val longTitleLeafId: String,
    val offlineMissingLeafId: String,
)

@Serializable
internal data class DocumentFixtureManifest(
    val schemaVersion: Int,
    val generatorVersion: Int,
    val fixtureId: String,
    val target: String,
    val spaceId: String,
    val archiveOperationId: String,
    val planFingerprint: String,
    val documentCount: Int,
    val rootCount: Int,
    val middleCount: Int,
    val leafCount: Int,
    val ownerUid: String?,
    val datasetId: String?,
    val status: DocumentFixtureStatus,
    val createdDocuments: Int,
    val createdAtEpochMs: Long,
    val completedAtEpochMs: Long?,
    val archivedAtEpochMs: Long?,
    val representatives: DocumentFixtureRepresentatives,
)

internal data class DocumentFixtureNodeSpec(
    val documentId: String,
    val parentId: String?,
    val level: Int,
    val title: String,
    val markdown: String,
)

internal data class DocumentFixturePlan(
    val fixtureId: String,
    val spaceId: String,
    val archiveOperationId: String,
    val spaceName: String,
    val spaceDescription: String,
    val nodes: List<DocumentFixtureNodeSpec>,
    val representatives: DocumentFixtureRepresentatives,
) {
    val rootCount: Int get() = nodes.count { it.level == 1 }
    val middleCount: Int get() = nodes.count { it.level == 2 }
    val leafCount: Int get() = nodes.count { it.level == 3 }
}

internal fun documentFixturePlan(fixtureId: String): DocumentFixturePlan {
    requireCanonicalUuid(fixtureId, "fixtureId")
    val nodes = ArrayList<DocumentFixtureNodeSpec>(DOCUMENT_FIXTURE_DOCUMENT_COUNT)
    val categories = listOf("产品与战略", "研发与架构", "设计与体验", "客户与交付", "运营与财务", "组织与治理")
    repeat(FIXTURE_ROOT_COUNT) { rootIndex ->
        val rootOrdinal = rootIndex + 1
        val rootId = fixtureUuid(fixtureId, "document/root/$rootOrdinal")
        val rootTitle = "%02d %s知识综述".format(rootOrdinal, categories[rootIndex])
        nodes += fixtureNode(rootId, null, 1, rootTitle, "$rootOrdinal")

        repeat(FIXTURE_MIDDLE_COUNT_PER_ROOT) { middleIndex ->
            val middleOrdinal = middleIndex + 1
            val middlePath = "$rootOrdinal.$middleOrdinal"
            val middleId = fixtureUuid(fixtureId, "document/middle/$middlePath")
            val middleTitle = "%02d.%02d %s专题指南".format(
                rootOrdinal,
                middleOrdinal,
                categories[rootIndex],
            )
            nodes += fixtureNode(middleId, rootId, 2, middleTitle, middlePath)

            repeat(FIXTURE_LEAF_COUNT_PER_MIDDLE) { leafIndex ->
                val leafOrdinal = leafIndex + 1
                val leafPath = "$rootOrdinal.$middleOrdinal.$leafOrdinal"
                val leafId = fixtureUuid(fixtureId, "document/leaf/$leafPath")
                val leafTitle = if (
                    rootOrdinal == FIXTURE_ROOT_COUNT &&
                    middleOrdinal == FIXTURE_MIDDLE_COUNT_PER_ROOT &&
                    leafOrdinal == FIXTURE_LEAF_COUNT_PER_MIDDLE
                ) {
                    "06.04.05 跨团队协作与复杂交付场景下需要在紧凑文档树中稳定截断并保留语义命中区的长标题验收页面"
                } else {
                    "%02d.%02d.%02d %s操作页".format(
                        rootOrdinal,
                        middleOrdinal,
                        leafOrdinal,
                        categories[rootIndex],
                    )
                }
                nodes += fixtureNode(leafId, middleId, 3, leafTitle, leafPath)
            }
        }
    }
    check(nodes.size == DOCUMENT_FIXTURE_DOCUMENT_COUNT)
    check(nodes.mapTo(hashSetOf(), DocumentFixtureNodeSpec::documentId).size == nodes.size)

    val root = nodes.first { it.level == 1 }
    val middle = nodes.first { it.level == 2 }
    val firstLeaf = nodes.first { it.level == 3 }
    val longTitleLeaf = nodes.last()
    val offlineMissingLeaf = nodes.asReversed().first { it.documentId != longTitleLeaf.documentId && it.level == 3 }
    val spaceName = "TeamTalk 文档树真实验收 ${fixtureId.take(8)}"
    val spaceDescription = "150 篇三层统一文档节点；一、二级文档同时承载正文与子页。"
    DocumentPolicy.normalizeSpaceName(spaceName)
    DocumentPolicy.normalizeDescription(spaceDescription)
    return DocumentFixturePlan(
        fixtureId = fixtureId,
        spaceId = fixtureUuid(fixtureId, "space"),
        archiveOperationId = fixtureUuid(fixtureId, "archive-operation"),
        spaceName = spaceName,
        spaceDescription = spaceDescription,
        nodes = nodes,
        representatives = DocumentFixtureRepresentatives(
            rootWithChildrenId = root.documentId,
            middleWithChildrenId = middle.documentId,
            leafId = firstLeaf.documentId,
            longTitleLeafId = longTitleLeaf.documentId,
            offlineMissingLeafId = offlineMissingLeaf.documentId,
        ),
    )
}

private fun fixtureNode(
    documentId: String,
    parentId: String?,
    level: Int,
    title: String,
    path: String,
): DocumentFixtureNodeSpec {
    DocumentPolicy.normalizeNodeName(title)
    val role = when (level) {
        1 -> "一级综述文档，同时是子页的组织入口"
        2 -> "二级专题文档，同时是详细页的组织入口"
        3 -> "可独立阅读和编辑的详细文档"
        else -> error("Unsupported fixture document level")
    }
    val markdown = """
        # $title

        这是 TeamTalk 真实 UI 验收夹具中的文档页。

        - 树路径：$path
        - 节点语义：$role
        - 验收重点：标题打开正文，展开按钮只加载子文档。

        ## 本页摘要

        文档内容与子节点能力相互独立，不存在文件夹节点。
    """.trimIndent()
    DocumentPolicy.validateMarkdownEnvelope(markdown)
    return DocumentFixtureNodeSpec(documentId, parentId, level, title, markdown)
}

private fun fixtureUuid(fixtureId: String, coordinate: String): String = UUID.nameUUIDFromBytes(
    "teamtalk-document-fixture-v$FIXTURE_GENERATOR_VERSION/$fixtureId/$coordinate"
        .toByteArray(StandardCharsets.UTF_8),
).toString()

internal fun documentFixturePlanFingerprint(plan: DocumentFixturePlan): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.updateFramed("teamtalk-document-fixture-plan-fingerprint-v1")
    digest.updateFramed(plan.fixtureId)
    digest.updateFramed(plan.spaceId)
    digest.updateFramed(plan.archiveOperationId)
    digest.updateFramed(plan.spaceName)
    digest.updateFramed(plan.spaceDescription)
    digest.updateFramed(plan.rootCount)
    digest.updateFramed(plan.middleCount)
    digest.updateFramed(plan.leafCount)
    digest.updateFramed(plan.nodes.size)
    plan.nodes.forEach { node ->
        digest.updateFramed(node.documentId)
        digest.updateNullableFramed(node.parentId)
        digest.updateFramed(node.level)
        digest.updateFramed(node.title)
        digest.updateFramed(node.markdown)
    }
    with(plan.representatives) {
        digest.updateFramed(rootWithChildrenId)
        digest.updateFramed(middleWithChildrenId)
        digest.updateFramed(leafId)
        digest.updateFramed(longTitleLeafId)
        digest.updateFramed(offlineMissingLeafId)
    }
    return "sha256:${digest.digest().toLowerHex()}"
}

private fun MessageDigest.updateFramed(value: String) {
    updateFramed(value.toByteArray(StandardCharsets.UTF_8))
}

private fun MessageDigest.updateNullableFramed(value: String?) {
    update(if (value == null) 0.toByte() else 1.toByte())
    if (value != null) updateFramed(value)
}

private fun MessageDigest.updateFramed(value: Int) {
    update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array())
}

private fun MessageDigest.updateFramed(bytes: ByteArray) {
    updateFramed(bytes.size)
    update(bytes)
}

private fun ByteArray.toLowerHex(): String = buildString(size * 2) {
    for (byte in this@toLowerHex) {
        val value = byte.toInt() and 0xff
        append(HEX_DIGITS[value ushr 4])
        append(HEX_DIGITS[value and 0x0f])
    }
}

private const val HEX_DIGITS = "0123456789abcdef"

private fun requireCanonicalUuid(value: String, label: String) {
    val parsed = runCatching { UUID.fromString(value) }.getOrNull()
    require(parsed != null && parsed.toString() == value) { "$label must be one canonical UUID" }
}

internal fun encodeDocumentFixtureManifest(manifest: DocumentFixtureManifest): String =
    fixtureJson.encodeToString(DocumentFixtureManifest.serializer(), manifest) + "\n"

internal fun decodeDocumentFixtureManifest(text: String): DocumentFixtureManifest = try {
    fixtureJson.decodeFromString(DocumentFixtureManifest.serializer(), text)
} catch (_: Exception) {
    throw IllegalArgumentException("Document fixture manifest is invalid")
}

internal fun validateDocumentFixtureManifest(
    manifest: DocumentFixtureManifest,
    expectedTarget: String,
): DocumentFixturePlan {
    require(manifest.schemaVersion == FIXTURE_SCHEMA_VERSION) {
        "Unsupported document fixture manifest schema"
    }
    require(manifest.generatorVersion == FIXTURE_GENERATOR_VERSION) {
        "Document fixture generator changed; use a new private state directory"
    }
    require(manifest.target == expectedTarget) {
        "Document fixture manifest belongs to a different deployment target"
    }
    requireCanonicalUuid(manifest.fixtureId, "fixtureId")
    requireCanonicalUuid(manifest.spaceId, "spaceId")
    requireCanonicalUuid(manifest.archiveOperationId, "archiveOperationId")
    val plan = documentFixturePlan(manifest.fixtureId)
    require(manifest.spaceId == plan.spaceId && manifest.archiveOperationId == plan.archiveOperationId) {
        "Document fixture manifest identities do not match its deterministic plan"
    }
    require(manifest.planFingerprint == documentFixturePlanFingerprint(plan)) {
        "Document fixture manifest plan fingerprint does not match its complete deterministic plan"
    }
    require(
        manifest.documentCount == plan.nodes.size &&
            manifest.rootCount == plan.rootCount &&
            manifest.middleCount == plan.middleCount &&
            manifest.leafCount == plan.leafCount &&
            manifest.representatives == plan.representatives
    ) { "Document fixture manifest topology does not match its generator" }
    require(manifest.createdDocuments in 0..manifest.documentCount) {
        "Document fixture manifest progress is invalid"
    }
    require((manifest.ownerUid == null) == (manifest.datasetId == null)) {
        "Document fixture authority identity is incomplete"
    }
    if (manifest.status == DocumentFixtureStatus.SEEDING || manifest.status == DocumentFixtureStatus.READY) {
        require(manifest.ownerUid != null && manifest.datasetId != null) {
            "Document fixture status requires an admitted authority identity"
        }
    }
    if (manifest.status == DocumentFixtureStatus.READY) {
        require(manifest.createdDocuments == manifest.documentCount && manifest.completedAtEpochMs != null) {
            "Completed document fixture manifest is missing completion evidence"
        }
    }
    if (manifest.status == DocumentFixtureStatus.ARCHIVED) {
        require(manifest.archivedAtEpochMs != null) {
            "Archived document fixture manifest is missing its archive time"
        }
    }
    require(manifest.createdAtEpochMs > 0L) { "Document fixture creation time is invalid" }
    return plan
}
