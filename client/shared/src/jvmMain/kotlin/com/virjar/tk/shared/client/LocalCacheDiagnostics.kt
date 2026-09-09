package com.virjar.tk.shared.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

@Serializable
enum class LocalCacheDiagnosticLayout { JVM, ANDROID }

@Serializable
data class LocalCacheDiagnosticOwner(val deploymentFingerprint: String, val datasetId: String, val uid: String)

@Serializable
data class LocalCacheDiagnosticFile(val path: String, val bytes: Long)

@Serializable
data class LocalCacheDatabaseDiagnostic(
    val path: String,
    val owner: LocalCacheDiagnosticOwner?,
    val quarantine: Boolean,
    val schemaVersion: Long? = null,
    val familyFiles: List<LocalCacheDiagnosticFile> = emptyList(),
    val counts: Map<String, Long?> = emptyMap(),
    val issues: List<String> = emptyList(),
)

@Serializable
data class LocalCacheDiagnosticReport(
    val root: String,
    val layout: LocalCacheDiagnosticLayout,
    val databases: List<LocalCacheDatabaseDiagnostic> = emptyList(),
    val issues: List<String> = emptyList(),
    val truncated: Boolean = false,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault
    val uninspected: List<String> = listOf(
        "Independent document drafts/operations and attachment spool are not SQLite tables; preserve them with the database family",
        "A zero count is not permission to delete a namespace; inaccessible or unsupported facts remain unknown",
        "Quarantine names identify corruption recovery; the original exception is only available in client logs",
    ),
)

/**
 * Offline diagnostics of an installation or an exported Android app-data directory. No cache factory,
 * migration, credentials reader or application session is opened. SQLite only sees a private temporary
 * copy; WAL is included. Source changes during capture invalidate every aggregate from that capture.
 */
object LocalCacheDiagnostics {
    const val MAX_DATABASES = 128
    const val MAX_DATABASE_BYTES = 512L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
    internal const val MAX_ENTRIES = 4096

    fun inspect(root: File, layout: LocalCacheDiagnosticLayout = LocalCacheDiagnosticLayout.JVM): LocalCacheDiagnosticReport {
        val requested = root.toPath().toAbsolutePath().normalize()
        if (!Files.exists(requested, NOFOLLOW_LINKS)) return LocalCacheDiagnosticReport(requested.toString(), layout, issues = listOf("ROOT_NOT_FOUND"))
        if (!Files.isDirectory(requested, NOFOLLOW_LINKS)) return LocalCacheDiagnosticReport(requested.toString(), layout, issues = listOf("ROOT_NOT_DIRECTORY_OR_SYMLINK"))
        return try { Inventory(requested.toRealPath(), layout).inspect() }
        catch (_: Exception) { LocalCacheDiagnosticReport(requested.toString(), layout, issues = listOf("ROOT_UNREADABLE")) }
    }

    private class Inventory(private val root: Path, private val layout: LocalCacheDiagnosticLayout) {
        private val issues = mutableListOf<String>()
        private val candidates = linkedMapOf<Path, Pair<LocalCacheDiagnosticOwner?, Boolean>>()
        private var visited = 0
        private var truncated = false

        fun inspect(): LocalCacheDiagnosticReport {
            if (layout == LocalCacheDiagnosticLayout.JVM) scanJvm() else scanAndroid()
            var remainingBytes = MAX_TOTAL_BYTES
            val databases = candidates.map { (file, identity) ->
                val result = inspectLocalCacheCopy(root, file, identity.first, identity.second, remainingBytes)
                remainingBytes -= result.second
                result.first
            }
            return LocalCacheDiagnosticReport(root.toString(), layout, databases, issues.distinct(), truncated)
        }

        private fun children(directory: Path): List<Path> {
            if (!Files.exists(directory, NOFOLLOW_LINKS)) return emptyList()
            if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) {
                issues += "DIRECTORY_UNREADABLE_OR_SYMLINK:${relative(directory)}"
                return emptyList()
            }
            return try {
                Files.newDirectoryStream(directory).use { stream ->
                    val found = mutableListOf<Path>()
                    for (entry in stream) {
                        if (++visited > MAX_ENTRIES) { truncated = true; break }
                        found.add(entry)
                    }
                    found.sortedBy { it.fileName.toString() }
                }
            } catch (_: Exception) {
                issues += "DIRECTORY_UNREADABLE:${relative(directory)}"
                emptyList()
            }
        }

        private fun scanJvm() {
            children(root.resolve("deployments")).forEach deployments@{ deployment ->
                val fingerprint = deployment.fileName.toString()
                if (runCatching { validatedDeploymentFingerprint(fingerprint) }.isFailure) return@deployments
                if (!safeDirectory(deployment)) return@deployments
                children(deployment.resolve("datasets")).forEach datasets@{ dataset ->
                    val datasetId = dataset.fileName.toString()
                    if (runCatching { validatedLocalCacheDatasetId(datasetId) }.isFailure) return@datasets
                    if (!safeDirectory(dataset)) return@datasets
                    children(dataset.resolve("users")).forEach users@{ user ->
                        val name = user.fileName.toString()
                        val uid = name.substringBefore(".corrupt-")
                        if (runCatching { validatedLocalCacheOwnerId(uid) }.isFailure) return@users
                        if (name != uid && !name.removePrefix("$uid.corrupt-").matches(Regex("[A-Za-z0-9-]+"))) return@users
                        if (!safeDirectory(user)) return@users
                        children(user).forEach { entry ->
                            val base = removeSidecar(entry.fileName.toString())
                            if (base.matches(Regex("cache_e[0-9]+\\.db"))) {
                                add(user.resolve(base), LocalCacheDiagnosticOwner(fingerprint, datasetId, uid), name != uid)
                            }
                        }
                    }
                }
            }
            // Older flat files are inventory only: their namespace cannot be inferred safely.
            children(root).forEach { entry ->
                val base = removeSidecar(entry.fileName.toString())
                if (base.matches(Regex("cache_e[0-9]+(?:_[\\p{L}\\p{N}_-]+)?\\.db"))) add(root.resolve(base), null, false)
            }
        }

        private fun scanAndroid() {
            val databases = root.resolve("databases")
            children(databases).forEach { entry ->
                val base = removeSidecar(entry.fileName.toString())
                val match = ANDROID_NAME.matchEntire(base)
                if (match != null) {
                    val (_, fingerprint, dataset, uid, quarantine) = match.destructured
                    if (runCatching { validatedLocalCacheDatasetId(dataset); validatedLocalCacheOwnerId(uid) }.isSuccess) {
                        add(databases.resolve(base), LocalCacheDiagnosticOwner(fingerprint, dataset, uid), quarantine.isNotEmpty())
                    }
                } else if (base.matches(Regex("cache_e[0-9]+(?:_[\\p{L}\\p{N}_-]+)?\\.db(?:\\.corrupt-[A-Za-z0-9-]+)?"))) {
                    add(databases.resolve(base), null, ".corrupt-" in base)
                }
            }
        }

        private fun safeDirectory(path: Path): Boolean {
            if (Files.isDirectory(path, NOFOLLOW_LINKS)) return true
            issues += "DIRECTORY_UNREADABLE_OR_SYMLINK:${relative(path)}"
            return false
        }

        private fun add(path: Path, owner: LocalCacheDiagnosticOwner?, quarantine: Boolean) {
            if (path in candidates) return
            if (candidates.size >= MAX_DATABASES) { truncated = true; return }
            candidates[path] = owner to quarantine
        }

        private fun relative(path: Path) = root.relativize(path).toString().replace(File.separatorChar, '/')
    }

    private val ANDROID_NAME = Regex("cache_e([0-9]+)_([0-9a-f]{64})_([0-9a-f-]{36})_([\\p{L}\\p{N}_-]+)\\.db(\\.corrupt-[A-Za-z0-9-]+)?")
    internal val FAMILY_SUFFIXES = listOf("", "-wal", "-shm", "-journal", ".open", ".integrity-checked", ".corruption-reported")
    private fun removeSidecar(name: String): String = FAMILY_SUFFIXES.drop(1).firstOrNull(name::endsWith)?.let(name::removeSuffix) ?: name
}
