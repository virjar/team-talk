package release

import com.android.apksig.ApkVerifier
import deployment.requireReleaseArtifact
import kotlinx.serialization.json.*
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipFile

data class BundleIdentity(val version: ReleaseVersion, val sourceCommit: String, val deploymentSha256: String) {
    val buildIdentity: String get() = "${version.name}+$sourceCommit"
}

/** Builds once into a temporary sibling, seals every byte, then publishes the local directory. */
object ReleaseBundle {
    const val MANIFEST = "release-manifest.json"
    const val CHECKSUMS = "SHA256SUMS"

    fun assemble(
        destination: File,
        identity: BundleIdentity,
        desktopSite: File,
        androidOutputs: File,
        serverZip: File,
        notes: String,
        commits: String,
        toolLock: File,
        toolDescriptor: File,
    ): File {
        if (destination.exists()) {
            verify(destination, identity, notes)
            return destination
        }
        requireReleaseArtifact(desktopSite, "desktop-site", identity.version.name, identity.buildIdentity)
        verifyDesktop(desktopSite, identity.version)
        val apk = requireAndroidApk(androidOutputs, identity)
        val apkVerification = ApkVerifier.Builder(apk).build().verify()
        require(apkVerification.isVerified) { "Android APK signature verification failed: ${apkVerification.errors}" }
        require(serverZip.isFile && serverZip.length() > 0) { "Server distribution ZIP is missing" }
        ZipFile(serverZip).use { zip ->
            val entries = zip.entries().asSequence().filter { it.name.endsWith("/teamtalk-release.properties") }.toList()
            require(entries.size == 1) { "Server ZIP needs exactly one identity manifest" }
            val props = Properties().apply { zip.getInputStream(entries.single()).reader().use(::load) }
            require(props.getProperty("buildIdentity") == identity.buildIdentity &&
                props.getProperty("artifactType") == "server-distribution") { "Stale server distribution ZIP" }
        }
        destination.parentFile.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}-${UUID.randomUUID()}.tmp")
        require(temporary.mkdir()) { "Cannot create release staging directory" }
        try {
            copyTree(desktopSite, File(temporary, "desktop"))
            val assets = File(temporary, "assets").apply { mkdirs() }
            apk.copyTo(File(assets, "TeamTalk-${identity.version.name}-android.apk"))
            serverZip.copyTo(File(assets, "TeamTalk-${identity.version.name}-server.zip"))
            zipDirectory(File(temporary, "desktop"), File(assets, "TeamTalk-${identity.version.name}-desktop-site.zip"))
            File(temporary, "RELEASE_NOTES.md").writeText(notes)
            File(temporary, "COMMITS.md").writeText(commits)
            val manifest = buildJsonObject {
                put("format", 1)
                put("version", identity.version.name)
                put("buildNumber", identity.version.buildNumber)
                put("protocolMajor", identity.version.protocolMajor)
                put("protocolMinor", identity.version.protocolMinor)
                put("minimumProtocolMinor", identity.version.minimumProtocolMinor)
                put("sourceCommit", identity.sourceCommit)
                put("tag", identity.version.tag)
                put("buildIdentity", identity.buildIdentity)
                put("deploymentSha256", identity.deploymentSha256)
                put("notesSha256", sha256(File(temporary, "RELEASE_NOTES.md")))
                put("conveyorToolsSha256", sha256(toolLock))
                val tool = Properties().apply { toolDescriptor.reader().use(::load) }
                putJsonObject("conveyor") {
                    put("version", tool.getProperty("version"))
                    put("archiveSha256", tool.getProperty("archiveSha256"))
                }
                put("buildJavaVersion", System.getProperty("java.version"))
                putJsonArray("androidSigningCertificatesSha256") {
                    apkVerification.signerCertificates.forEach { add(sha256(it.encoded)) }
                }
                File(desktopSite, "teamtalk.crt").takeIf(File::isFile)?.let {
                    put("desktopCertificateFileSha256", sha256(it))
                }
                putJsonArray("files") {
                    regularFiles(temporary).forEach { file ->
                        add(buildJsonObject {
                            put("path", file.relativeTo(temporary).invariantSeparatorsPath)
                            put("size", file.length())
                            put("sha256", sha256(file))
                        })
                    }
                }
            }
            File(temporary, MANIFEST).writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), manifest) + "\n")
            File(temporary, CHECKSUMS).writeText(checksumText(temporary))
            verify(temporary, identity, notes)
            Files.move(temporary.toPath(), destination.toPath(), ATOMIC_MOVE)
            return destination
        } finally {
            if (temporary.exists()) temporary.deleteRecursively()
        }
    }

    /** Reuse accepts the same source/configuration and exact sealed files, never a relabelled directory. */
    fun verify(directory: File, identity: BundleIdentity, notes: String): JsonObject {
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) { "Release bundle is missing: $directory" }
        val manifestFile = File(directory, MANIFEST)
        require(Files.isRegularFile(manifestFile.toPath(), NOFOLLOW_LINKS)) { "Release bundle manifest is missing" }
        val manifest = Json.parseToJsonElement(manifestFile.readText()).jsonObject
        fun field(name: String): String = manifest.getValue(name).jsonPrimitive.content
        require(field("format") == "1" && field("version") == identity.version.name &&
            field("buildNumber") == identity.version.buildNumber.toString() &&
            field("protocolMajor") == identity.version.protocolMajor.toString() &&
            field("protocolMinor") == identity.version.protocolMinor.toString() &&
            field("minimumProtocolMinor") == identity.version.minimumProtocolMinor.toString() &&
            field("sourceCommit") == identity.sourceCommit && field("buildIdentity") == identity.buildIdentity &&
            field("tag") == identity.version.tag && field("deploymentSha256") == identity.deploymentSha256) {
            "Release bundle belongs to a different version, source, protocol or deployment configuration"
        }
        val records = manifest.getValue("files").jsonArray.map(JsonElement::jsonObject)
        val paths = records.map { it.getValue("path").jsonPrimitive.content }
        require(paths.isNotEmpty() && paths.distinct().size == paths.size) { "Empty or duplicate bundle file list" }
        require(paths.toSet() == regularFiles(directory).map { it.relativeTo(directory).invariantSeparatorsPath }
            .filterNot { it == MANIFEST || it == CHECKSUMS }.toSet()) { "Bundle file set changed" }
        records.forEach { record ->
            val relative = record.getValue("path").jsonPrimitive.content
            require(relative.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
                '\\' !in relative && ':' !in relative && '\n' !in relative && '\r' !in relative) { "Unsafe bundle path" }
            val file = File(directory, relative)
            require(file.length() == record.getValue("size").jsonPrimitive.long &&
                sha256(file) == record.getValue("sha256").jsonPrimitive.content) { "Bundle checksum mismatch: $relative" }
        }
        require(File(directory, "RELEASE_NOTES.md").readText() == notes &&
            field("notesSha256") == sha256(File(directory, "RELEASE_NOTES.md"))) { "Bundle release notes differ from committed notes" }
        require(File(directory, CHECKSUMS).readText() == checksumText(directory)) { "SHA256SUMS does not match the bundle" }
        require(File(directory, "desktop/download.html").isFile && assets(directory).size == 3) { "Incomplete release bundle" }
        return manifest
    }

    fun assets(directory: File): List<File> = File(directory, "assets").listFiles()?.filter(File::isFile)?.sortedBy(File::getName).orEmpty()

    private fun requireAndroidApk(outputs: File, identity: BundleIdentity): File {
        val metadata = Json.parseToJsonElement(File(outputs, "output-metadata.json").readText()).jsonObject
        val element = metadata.getValue("elements").jsonArray.single().jsonObject
        require(element.getValue("versionName").jsonPrimitive.content == identity.version.name &&
            element.getValue("versionCode").jsonPrimitive.int == identity.version.buildNumber + 1) { "Stale Android output metadata" }
        val name = element.getValue("outputFile").jsonPrimitive.content
        require(name == File(name).name && name.endsWith(".apk")) { "Invalid APK output path" }
        val apk = File(outputs, name)
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry("assets/teamtalk-build.properties") ?: error("APK lacks its producer build identity")
            val props = Properties().apply { zip.getInputStream(entry).reader().use(::load) }
            require(props.getProperty("buildIdentity") == identity.buildIdentity &&
                props.getProperty("version") == identity.version.name &&
                props.getProperty("artifactType") == "android-apk") { "APK came from a different source revision" }
        }
        return apk
    }

    private fun verifyDesktop(site: File, version: ReleaseVersion) {
        val metadata = Properties().apply { File(site, "metadata.properties").reader().use(::load) }
        require(metadata.getProperty("app.version") == version.name &&
            metadata.getProperty("app.revision") == (version.buildNumber + 1).toString()) { "Stale Conveyor version metadata" }
        listOf("download.html", "teamtalk.appinstaller", "teamtalk.exe", "appcast-amd64.rss", "appcast-aarch64.rss").forEach {
            require(File(site, it).isFile && File(site, it).length() > 0) { "Missing Desktop site component: $it" }
        }
        val paths = regularFiles(site).map { it.name }
        listOf("-mac-amd64.zip", "-mac-aarch64.zip", "-windows-amd64.zip", ".msix", "-linux-amd64.tar.gz", ".deb").forEach { suffix ->
            require(paths.any { it.endsWith(suffix) }) { "Desktop site lacks a required platform package: $suffix" }
        }
    }

    private fun copyTree(source: File, destination: File) {
        regularFiles(source).forEach { file ->
            val target = File(destination, file.relativeTo(source).invariantSeparatorsPath)
            target.parentFile.mkdirs()
            file.copyTo(target)
        }
    }

    private fun zipDirectory(source: File, destination: File) {
        ZipArchiveOutputStream(destination).use { zip ->
            regularFiles(source).forEach { file ->
                val entry = ZipArchiveEntry(file.relativeTo(source).invariantSeparatorsPath).apply {
                    time = 0L
                    unixMode = 0b110100100 // Static site files are not executable on the server.
                }
                zip.putArchiveEntry(entry)
                file.inputStream().use { it.copyTo(zip) }
                zip.closeArchiveEntry()
            }
        }
    }

    private fun checksumText(directory: File): String = regularFiles(directory)
        .filterNot { it.name == CHECKSUMS && it.parentFile == directory }
        .joinToString("") { "${sha256(it)}  ${it.relativeTo(directory).invariantSeparatorsPath}\n" }

    private fun regularFiles(directory: File): List<File> = directory.walkTopDown().onEnter { dir ->
        require(!Files.isSymbolicLink(dir.toPath())) { "Release payload cannot contain symbolic links: $dir" }
        true
    }.filter { file ->
        require(!Files.isSymbolicLink(file.toPath())) { "Release payload cannot contain symbolic links: $file" }
        file.isFile
    }.sortedBy { it.relativeTo(directory).invariantSeparatorsPath }.toList()
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { stream ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }
