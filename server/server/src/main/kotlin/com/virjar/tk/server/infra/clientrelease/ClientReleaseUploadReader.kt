package com.virjar.tk.server.infra.clientrelease

import com.virjar.tk.protocol.http.AndroidReleaseManifest
import com.virjar.tk.protocol.http.ClientUpdateContracts
import com.virjar.tk.server.infra.storage.ReleaseStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** 上传包内 release.json 的服务端契约（构建侧 buildSrc 与管理台上传共用同一格式）。 */
@Serializable
data class ClientReleaseUploadMetadata(
    val clientType: String,
    val platform: String,
    val arch: String,
    val version: String,
    val build: Long,
    val channel: String,
    val notes: String? = null,
    val forced: Boolean = false,
    val minShellAbi: Int? = null,
    val shellDigest: String? = null,
    val bundleFilename: String? = null,
    val installers: List<InstallerSpec> = emptyList(),
    val activate: Boolean = true,
    val buildIdentity: String = "",
) {
    @Serializable
    data class InstallerSpec(val filename: String, val label: String)
}

/** 解析上传格式、校验清单并流式收纳制品；不写发布索引或切换通道。 */
internal class ClientReleaseUploadReader(private val store: ReleaseStore) {
    class Artifact(
        val kind: String,
        val path: String,
        val sha256: String,
        val size: Long,
        val mode: Int? = null,
        val label: String? = null,
    )

    class Upload(
        val metadata: ClientReleaseUploadMetadata,
        val files: List<Artifact>,
        val sha256: String,
    ) {
        val totalBytes: Long get() = files.sumOf { it.size }
        val payloadCount: Int get() = files.count { it.kind == ClientUpdateContracts.KIND_PAYLOAD }
        val buildIdentity: String get() = metadata.buildIdentity.ifBlank {
            if (metadata.channel == AndroidReleaseManifest.CHANNEL_SNAPSHOT) "sha256:$sha256" else ""
        }
    }

    fun read(uploadZip: File): Upload {
        ZipFile(uploadZip).use { zip ->
            if (zip.size() > MAX_ZIP_ENTRIES) {
                throw ClientReleaseValidationException("upload zip exceeds $MAX_ZIP_ENTRIES entries")
            }
            val entries = zip.entries().asSequence().toList()
            if (entries.map { it.name }.distinct().size != entries.size) {
                throw ClientReleaseValidationException("upload zip contains duplicate entries")
            }
            var totalBytes = 0L
            for (entry in entries) {
                totalBytes += entry.size.coerceAtLeast(0)
                if (totalBytes > MAX_TOTAL_BYTES) {
                    throw ClientReleaseValidationException("upload zip exceeds $MAX_TOTAL_BYTES bytes")
                }
            }

            val metadataEntry = entries.firstOrNull { !it.isDirectory && it.name == METADATA_ENTRY }
                ?: throw ClientReleaseValidationException("upload zip is missing $METADATA_ENTRY")
            val metadata = runCatching {
                uploadJson.decodeFromString(
                    ClientReleaseUploadMetadata.serializer(),
                    zip.getInputStream(metadataEntry).use { it.readNBytes(64 * 1024 + 1) }.also {
                        if (it.size > 64 * 1024) throw ClientReleaseValidationException("release.json exceeds 64 KiB")
                    }.decodeToString(),
                )
            }.getOrElse { throw ClientReleaseValidationException("release.json is not valid JSON: ${it.message}") }
            validateTargetVocabulary(metadata)

            data class Pending(val entry: ZipEntry, val logicalPath: String)

            val payloadEntries = mutableListOf<Pending>()
            val bundleEntries = mutableMapOf<String, ZipEntry>()
            val installerEntries = mutableMapOf<String, ZipEntry>()
            for (entry in entries) {
                if (entry.isDirectory || entry.name == METADATA_ENTRY) continue
                val name = entry.name
                when {
                    name.startsWith("payload/") && name.length > "payload/".length ->
                        payloadEntries.add(Pending(entry, safeLogicalPath(name.removePrefix("payload/"))))
                    name.startsWith("bundle/") && name.length > "bundle/".length ->
                        bundleEntries[safeLogicalPath(name.removePrefix("bundle/"))] = entry
                    name.startsWith("installers/") && name.length > "installers/".length ->
                        installerEntries[safeLogicalPath(name.removePrefix("installers/"))] = entry
                    else -> throw ClientReleaseValidationException("unexpected zip entry: $name")
                }
            }

            // JDK 的 ZipEntry 不暴露 unix 外部属性；桌面负载（jar/native 库）无 exec 位
            // 需求，mode 暂置空，未来可在 release.json 声明可执行路径。
            fun digest(entry: ZipEntry): Pair<String, Long> {
                val stored = zip.getInputStream(entry).use { store.store(it) }
                return Pair(stored.sha256, stored.size)
            }

            val files = payloadEntries.map { pending ->
                val (sha, size) = digest(pending.entry)
                Artifact(ClientUpdateContracts.KIND_PAYLOAD, pending.logicalPath, sha, size)
            }.toMutableList()
            metadata.bundleFilename?.let { filename ->
                val entry = bundleEntries.remove(filename)
                    ?: throw ClientReleaseValidationException("declared bundle '$filename' missing from upload zip")
                val (sha, size) = digest(entry)
                files += Artifact(ClientUpdateContracts.KIND_BUNDLE, filename, sha, size)
            }
            if (bundleEntries.isNotEmpty()) {
                throw ClientReleaseValidationException("undeclared bundle entries: ${bundleEntries.keys}")
            }
            metadata.installers.forEach { spec ->
                val entry = installerEntries.remove(spec.filename)
                    ?: throw ClientReleaseValidationException("declared installer '${spec.filename}' missing from upload zip")
                val (sha, size) = digest(entry)
                files += Artifact(ClientUpdateContracts.KIND_INSTALLER, spec.filename, sha, size, label = spec.label.take(100))
            }
            if (installerEntries.isNotEmpty()) {
                throw ClientReleaseValidationException("undeclared installer entries: ${installerEntries.keys}")
            }
            if (files.isEmpty()) {
                throw ClientReleaseValidationException("upload contains no artifacts")
            }
            return Upload(metadata, files, sha256(uploadZip))
        }
    }

    /** zip 内相对路径准入：拒绝绝对路径、穿越与非法字符。 */
    private fun safeLogicalPath(path: String): String {
        if (path.isEmpty() || path.length > 500 || path.contains("..") || path.startsWith("/") ||
            path.contains('\\') || path.contains('\u0000') || path.contains("//")
        ) {
            throw ClientReleaseValidationException("unsafe zip entry path: $path")
        }
        return path
    }

    private fun validateTargetVocabulary(metadata: ClientReleaseUploadMetadata) {
        val clientOk = metadata.clientType in setOf(
            ClientUpdateContracts.CLIENT_DESKTOP, ClientUpdateContracts.CLIENT_ANDROID,
            ClientUpdateContracts.CLIENT_HEADLESS,
        )
        val platformOk = metadata.platform in setOf(
            ClientUpdateContracts.PLATFORM_MACOS, ClientUpdateContracts.PLATFORM_WINDOWS,
            ClientUpdateContracts.PLATFORM_LINUX, ClientUpdateContracts.PLATFORM_ANDROID,
            ClientUpdateContracts.PLATFORM_ANY,
        )
        val archOk = metadata.arch in setOf(
            ClientUpdateContracts.ARCH_AMD64, ClientUpdateContracts.ARCH_AARCH64, ClientUpdateContracts.ARCH_ANY,
        )
        val channelOk = metadata.channel in setOf(
            AndroidReleaseManifest.CHANNEL_STABLE, AndroidReleaseManifest.CHANNEL_PREVIEW,
            AndroidReleaseManifest.CHANNEL_SNAPSHOT,
        )
        if (!clientOk || !platformOk || !archOk || !channelOk) {
            throw ClientReleaseValidationException("invalid target vocabulary in release.json")
        }
        if (metadata.version.isEmpty() || metadata.version.length > 32 || metadata.build <= 0) {
            throw ClientReleaseValidationException("invalid version/build in release.json")
        }
        if ((metadata.notes?.length ?: 0) > MAX_NOTES_LENGTH) {
            throw ClientReleaseValidationException("notes exceed $MAX_NOTES_LENGTH characters")
        }
        if (metadata.buildIdentity.length > 128) throw ClientReleaseValidationException("buildIdentity too long")
        if ((metadata.shellDigest?.length ?: 0) > 128) {
            throw ClientReleaseValidationException("shellDigest too long")
        }
    }

    private fun sha256(file: File): String = file.inputStream().buffered().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(bytes)
            if (count < 0) break
            digest.update(bytes, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val METADATA_ENTRY = "release.json"
        private const val MAX_ZIP_ENTRIES = 5000
        private const val MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024
        private const val MAX_NOTES_LENGTH = 20000
        private val uploadJson = Json { ignoreUnknownKeys = false }
    }
}
