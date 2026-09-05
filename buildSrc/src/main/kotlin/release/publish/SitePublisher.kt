package release.publish

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.common.SftpConstants
import org.apache.sshd.sftp.common.SftpException

data class SitePublication(
    val desktopDirectory: File,
    val androidApk: File,
    val version: String,
    val releaseBuildNumber: Int,
    val manifest: File,
    val metadataFiles: List<File> = listOf(manifest),
)

/**
 * Publishes only the two existing download surfaces. Desktop remains a real directory and Android a
 * regular file, matching the running server's canonical-path checks. The desktop directory switch has
 * a brief rename window; a retained previous directory and journal make failures recoverable. No service
 * process, database, server deployment lock or unknown download file is modified.
 */
class SitePublisher internal constructor(private val afterDesktopSwitch: () -> Unit) {
    constructor() : this({})

    fun publish(publication: SitePublication, connection: SiteConnection): PublicationResult {
        val payload = localPayload(publication)
        val receipt = buildJsonObject {
            put("version", publication.version)
            put("releaseBuildNumber", publication.releaseBuildNumber)
            put("manifestSha256", sha256(publication.manifest))
            put("files", buildJsonObject { payload.forEach { (path, file) -> put(path, sha256(file)) } })
        }
        return connection.connect { session, sftp ->
            val remote = RemoteFiles(sftp)
            val root = connection.downloadsPath
            remote.mkdirs(root)
            ClientPublicationLease.acquire(session, root).use { lease ->
                remote.publicationLease = lease
                val site = SiteTransaction(remote, lease, root)
                site.recoverInterruptedPublication()
                if (site.published(publication.version, receipt)) {
                    return@connect PublicationResult("site:${connection.host}:$root", publication.version, true)
                }
                val staging = "$root/.teamtalk-client-stage-${UUID.randomUUID()}"
                try {
                    payload.forEach { (path, file) ->
                        remote.upload(file, "$staging/$path")
                        check(remote.digest("$staging/$path") == receipt.getValue("files").jsonObject.getValue(path).jsonPrimitive.content) {
                            "Uploaded client artifact failed checksum verification: $path"
                        }
                    }
                    lease.requireHeld()
                    site.activate(staging, receipt, afterDesktopSwitch)
                } finally {
                    // Staging belongs to this invocation. On a lost lease/session, preserve it for journal recovery.
                    if (runCatching { lease.requireHeld() }.isSuccess && !site.hasPendingTransaction()) remote.removeTree(staging)
                }
                PublicationResult("site:${connection.host}:$root", publication.version, false)
            }
        }
    }

    private fun localPayload(publication: SitePublication): Map<String, File> {
        requireVersion(publication.version)
        require(publication.releaseBuildNumber >= 0) { "Release build number must not be negative" }
        requireAsset(publication.androidApk)
        requireAsset(publication.manifest)
        require(publication.desktopDirectory.isDirectory && !Files.isSymbolicLink(publication.desktopDirectory.toPath())) {
            "Desktop site directory is missing or symbolic"
        }
        val files = linkedMapOf<String, File>()
        publication.desktopDirectory.walkTopDown().forEach { file ->
            require(!Files.isSymbolicLink(file.toPath())) { "Desktop site must not contain symbolic links: $file" }
            if (file.isFile) {
                val relative = file.relativeTo(publication.desktopDirectory).invariantSeparatorsPath
                require(relative.none { it.code < 32 || it.code == 127 } &&
                    relative.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
                    "Invalid desktop site path"
                }
                files["desktop/$relative"] = file
            }
        }
        require(files.isNotEmpty() && files.containsKey("desktop/download.html")) { "Desktop site requires download.html and package files" }
        files["TeamTalk-android.apk"] = publication.androidApk
        val metadata = (publication.metadataFiles + publication.manifest).distinctBy(File::getCanonicalPath)
        metadata.forEach(::requireAsset)
        require(metadata.map(File::getName).distinct().size == metadata.size) { "Duplicate site metadata names" }
        metadata.forEach { files["metadata/${it.name}"] = it }
        return files.toSortedMap()
    }
}

private class SiteTransaction(
    private val remote: RemoteFiles,
    private val lease: ClientPublicationLease,
    private val root: String,
) {
    private val history = "$root/.teamtalk-client-releases"
    private val current = "$root/.teamtalk-client-release.json"
    private val journal = "$root/.teamtalk-client-transaction.json"

    fun hasPendingTransaction(): Boolean = remote.exists(journal)

    fun published(version: String, expected: JsonObject): Boolean {
        val currentReceipt = remote.jsonOrNull(current)
        if (currentReceipt != null) {
            val currentNumber = currentReceipt.buildNumber()
            val expectedNumber = expected.buildNumber()
            val sameVersion = currentReceipt.getValue("version").jsonPrimitive.content == version
            require(if (sameVersion) expectedNumber == currentNumber else expectedNumber > currentNumber) {
                "Site releaseBuildNumber must increase for a new version and remain unchanged for the same version; " +
                    "current=$currentNumber, requested=$expectedNumber. Automatic site downgrade is not supported"
            }
        }
        val saved = remote.jsonOrNull("$history/v$version/receipt.json") ?: return false
        saved.buildNumber()
        require(saved == expected) { "Site version $version was already published with different bytes; use a new version" }
        require(remote.jsonOrNull(current) == expected) { "Site version $version is historical; refusing to replace the current release with an old version" }
        require(matchesLiveDesktop(expected) && remote.digest("$root/TeamTalk-android.apk") == expected.hash("TeamTalk-android.apk")) {
            "Published site was modified outside the release task; investigate before publishing again"
        }
        expected.getValue("files").jsonObject.filterKeys { it.startsWith("metadata/") }.forEach { (path, hash) ->
            require(remote.digest("$history/v$version/$path") == hash.jsonPrimitive.content) { "Published release metadata changed: $path" }
        }
        return true
    }

    fun activate(staging: String, receipt: JsonObject, afterDesktopSwitch: () -> Unit) {
        lease.requireHeld()
        remote.requirePublicType("$root/desktop", directory = true)
        remote.requirePublicType("$root/TeamTalk-android.apk", directory = false)
        val backup = "$history/previous-${receipt.getValue("version").jsonPrimitive.content}-${UUID.randomUUID()}"
        remote.mkdirs(backup)
        val transaction = buildJsonObject {
            put("staging", staging)
            put("backup", backup)
            put("receipt", receipt)
            put("previousReceipt", remote.jsonOrNull(current) ?: JsonNull)
            put("hadDesktop", remote.exists("$root/desktop"))
            put("hadAndroid", remote.exists("$root/TeamTalk-android.apk"))
        }
        val previousReceipt = transaction["previousReceipt"]
        if (previousReceipt != null && previousReceipt != JsonNull) {
            remote.writeJsonAtomic("$backup/release-receipt.json", previousReceipt.jsonObject)
        }
        remote.writeJsonAtomic(journal, transaction)
        try {
            if (transaction.flag("hadAndroid")) remote.copy("$root/TeamTalk-android.apk", "$backup/TeamTalk-android.apk")
            if (transaction.flag("hadDesktop")) {
                lease.requireHeld()
                remote.rename("$root/desktop", "$backup/desktop")
            }
            lease.requireHeld()
            remote.rename("$staging/desktop", "$root/desktop")
            afterDesktopSwitch()
            lease.requireHeld()
            remote.replace("$staging/TeamTalk-android.apk", "$root/TeamTalk-android.apk")
            lease.requireHeld()
            remote.writeJsonAtomic(current, receipt)
            finish(transaction)
        } catch (failure: Exception) {
            // Losing the lease forbids rollback: another publisher may already own these public paths.
            runCatching {
                lease.requireHeld()
                recoverInterruptedPublication()
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    fun recoverInterruptedPublication() {
        val transaction = remote.jsonOrNull(journal) ?: return
        lease.requireHeld()
        val staging = transaction.getValue("staging").jsonPrimitive.content
        val backup = transaction.getValue("backup").jsonPrimitive.content
        require(staging.startsWith("$root/.teamtalk-client-stage-") && '/' !in staging.removePrefix("$root/")) { "Invalid client publication journal staging path" }
        require(backup.startsWith("$history/previous-") && '/' !in backup.removePrefix("$history/")) { "Invalid client publication journal backup path" }
        val expected = transaction.getValue("receipt").jsonObject
        requireVersion(expected.getValue("version").jsonPrimitive.content)
        expected.buildNumber()
        if (remote.jsonOrNull(current) == expected) {
            finish(transaction)
            return
        }
        if (remote.exists("$backup/desktop")) {
            if (remote.exists("$root/desktop")) {
                require(matchesLiveDesktop(expected)) { "Desktop changed outside the pending release; preserved backup: $backup" }
                lease.requireHeld()
                remote.rename("$root/desktop", "$staging/recovered-desktop")
            }
            lease.requireHeld()
            remote.rename("$backup/desktop", "$root/desktop")
        } else if (!transaction.flag("hadDesktop") && remote.exists("$root/desktop")) {
            require(matchesLiveDesktop(expected)) { "Unknown desktop site appeared during release recovery" }
            lease.requireHeld()
            remote.rename("$root/desktop", "$staging/recovered-desktop")
        } else {
            require(!transaction.flag("hadDesktop") || remote.exists("$root/desktop")) { "Previous desktop site is missing; preserved journal: $journal" }
        }
        if (remote.exists("$backup/TeamTalk-android.apk")) {
            val oldHash = remote.digest("$backup/TeamTalk-android.apk")
            val liveHash = if (remote.exists("$root/TeamTalk-android.apk")) remote.digest("$root/TeamTalk-android.apk") else null
            require(liveHash == null || liveHash == oldHash || liveHash == expected.hash("TeamTalk-android.apk")) {
                "Android APK changed outside the pending release; preserved backup: $backup"
            }
            lease.requireHeld()
            remote.replace("$backup/TeamTalk-android.apk", "$root/TeamTalk-android.apk")
        } else if (!transaction.flag("hadAndroid") && remote.exists("$root/TeamTalk-android.apk")) {
            require(remote.digest("$root/TeamTalk-android.apk") == expected.hash("TeamTalk-android.apk")) { "Unknown Android APK appeared during release recovery" }
            lease.requireHeld()
            remote.rename("$root/TeamTalk-android.apk", "$staging/recovered-android.apk")
        }
        lease.requireHeld()
        val previous = transaction["previousReceipt"]
        if (previous != null && previous != JsonNull) remote.writeJsonAtomic(current, previous.jsonObject)
        else if (remote.exists(current)) remote.removeFile(current)
        remote.removeFile(journal)
        remote.removeTree(staging)
        // Previous complete releases are retained; only this failed transaction's now-restored backup is removed.
        remote.removeTree(backup)
    }

    private fun finish(transaction: JsonObject) {
        lease.requireHeld()
        val expected = transaction.getValue("receipt").jsonObject
        val versionDir = "$history/v${expected.getValue("version").jsonPrimitive.content}"
        val staging = transaction.getValue("staging").jsonPrimitive.content
        remote.mkdirs(versionDir)
        remote.writeJsonAtomic("$versionDir/receipt.json", expected)
        if (remote.exists("$staging/metadata")) {
            require(!remote.exists("$versionDir/metadata")) { "Release metadata destination already exists" }
            remote.rename("$staging/metadata", "$versionDir/metadata")
        }
        remote.removeFile(journal)
        remote.removeTree(staging)
    }

    private fun matchesLiveDesktop(receipt: JsonObject): Boolean {
        val expected = receipt.getValue("files").jsonObject.filterKeys { it.startsWith("desktop/") }
        val actualPaths = remote.regularFiles("$root/desktop").map { "desktop/$it" }.toSet()
        return actualPaths == expected.keys && expected.all { (path, digest) ->
            remote.digest("$root/$path") == digest.jsonPrimitive.content
        }
    }

    private fun JsonObject.flag(name: String): Boolean = getValue(name).jsonPrimitive.boolean
    private fun JsonObject.hash(path: String): String = getValue("files").jsonObject.getValue(path).jsonPrimitive.content
    private fun JsonObject.buildNumber(): Int = get("releaseBuildNumber")?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 }
        ?: error("Managed site receipt has no valid releaseBuildNumber; migrate it from the matching sealed release bundle before publishing")
}

private class RemoteFiles(private val sftp: SftpClient) {
    lateinit var publicationLease: ClientPublicationLease

    fun exists(path: String): Boolean = attributes(path) != null

    fun requirePublicType(path: String, directory: Boolean) {
        val attrs = attributes(path) ?: return
        require(!attrs.isSymbolicLink && if (directory) attrs.isDirectory else attrs.isRegularFile) {
            "Existing publication path must be a real ${if (directory) "directory" else "file"}: $path"
        }
    }

    private fun attributes(path: String): SftpClient.Attributes? = try {
        sftp.lstat(path)
    } catch (missing: SftpException) {
        if (missing.status == SftpConstants.SSH_FX_NO_SUCH_FILE || missing.status == SftpConstants.SSH_FX_NO_SUCH_PATH) null
        else throw missing
    }

    fun mkdirs(path: String) {
        var parent = ""
        path.split('/').filter(String::isNotEmpty).forEach { part ->
            parent += "/$part"
            val attrs = attributes(parent)
            if (attrs == null) {
                try { sftp.mkdir(parent) } catch (race: SftpException) {
                    if (attributes(parent)?.isDirectory != true) throw race
                }
                sftp.setStat(parent, SftpClient.Attributes().perms(493)) // 0755, independent of SSH umask.
            } else require(attrs.isDirectory && !attrs.isSymbolicLink) { "Publication parent is not a real directory: $parent" }
        }
    }

    fun upload(local: File, path: String) {
        mkdirs(path.substringBeforeLast('/'))
        local.inputStream().use { input -> sftp.write(path).use { output -> input.copyTo(output, 128 * 1024) } }
        sftp.setStat(path, SftpClient.Attributes().perms(420)) // 0644: the running server may use a different account.
    }

    fun digest(path: String): String = sftp.read(path).use(::sha256)

    fun copy(source: String, target: String) {
        val temporary = "$target.${UUID.randomUUID()}.tmp"
        sftp.read(source).use { input -> sftp.write(temporary).use { output -> input.copyTo(output, 128 * 1024) } }
        check(digest(source) == digest(temporary)) { "Previous Android APK backup checksum mismatch" }
        sftp.setStat(temporary, SftpClient.Attributes().perms(420))
        replace(temporary, target)
    }

    fun jsonOrNull(path: String): JsonObject? = if (exists(path)) {
        sftp.read(path).bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
    } else null

    fun writeJsonAtomic(path: String, value: JsonObject) {
        val temporary = "$path.${UUID.randomUUID()}.tmp"
        try {
            sftp.write(temporary).bufferedWriter(Charsets.UTF_8).use { it.write(value.toString() + "\n") }
            sftp.setStat(temporary, SftpClient.Attributes().perms(420))
            replace(temporary, path)
        } finally {
            if (exists(temporary)) removeFile(temporary)
        }
    }

    fun rename(source: String, target: String) = publicationLease.rename(source, target, replace = false)
    fun replace(source: String, target: String) = publicationLease.rename(source, target, replace = true)
    fun removeFile(path: String) = publicationLease.remove(path, directory = false)

    fun regularFiles(root: String): List<String> {
        val found = mutableListOf<String>()
        fun visit(path: String, relative: String) {
            val attrs = attributes(path) ?: return
            require(!attrs.isSymbolicLink) { "Unexpected symbolic link in published site: $path" }
            if (attrs.isRegularFile) found += relative
            else if (attrs.isDirectory) sftp.readDir(path).filter { it.filename != "." && it.filename != ".." }.forEach { entry ->
                visit("$path/${entry.filename}", if (relative.isEmpty()) entry.filename else "$relative/${entry.filename}")
            }
            else error("Unexpected special file in published site: $path")
        }
        visit(root, "")
        return found
    }

    /** Invoked only with a unique task-owned staging/backup directory; never follows a symbolic link. */
    fun removeTree(path: String) {
        val attrs = attributes(path) ?: return
        if (attrs.isDirectory && !attrs.isSymbolicLink) {
            sftp.readDir(path).filter { it.filename != "." && it.filename != ".." }.forEach { removeTree("$path/${it.filename}") }
            publicationLease.remove(path, directory = true)
        } else removeFile(path)
    }
}
