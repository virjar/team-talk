package release

import com.android.apksig.ApkVerifier
import deployment.ClientDistributionIdentity
import deployment.DeploymentConfig
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

data class BundleIdentity(
    val version: ReleaseVersion,
    val sourceCommit: String,
    val deployment: DeploymentConfig,
    val distributionKind: String = "release",
    // Historical manifest field name; snapshots record the actual development schema, without freezing it.
    val protocolContractSha256: String? = null,
    val desktopRevision: Int = version.buildNumber + 1,
) {
    init {
        require(distributionKind in setOf("release", "private-first", "snapshot")) { "Unknown distribution kind" }
        require(if (distributionKind != "release") {
            protocolContractSha256?.matches(Regex("[0-9a-f]{64}")) == true
        } else protocolContractSha256 == null) { "Non-release distributions require the actual protocol schema SHA-256" }
        require(desktopRevision in 1..65535) { "Desktop installation revision is out of range" }
        require(distributionKind == "snapshot" || desktopRevision == version.buildNumber + 1) {
            "Only snapshots may use an independent Desktop installation revision"
        }
    }

    val buildIdentity: String get() = "${version.name}+$sourceCommit"
    val client: ClientDistributionIdentity get() = deployment.client
    val deploymentSha256: String get() = sha256(deployment.toCanonicalJson().toByteArray(Charsets.UTF_8))
}

/** Builds once into a temporary sibling, seals every byte, then publishes the local directory. */
object ReleaseBundle {
    const val MANIFEST = "release-manifest.json"
    const val CHECKSUMS = "SHA256SUMS"
    const val DEPLOYMENT_CONFIG = "deployment-config.json"

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
        headlessZip: File,
    ): File {
        if (destination.exists()) {
            verify(destination, identity, notes)
            return destination
        }
        requireReleaseArtifact(desktopSite, "desktop-site", identity.version.name, identity.buildIdentity)
        verifyDesktop(desktopSite, identity.version, identity.client, identity.desktopRevision)
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
        HeadlessDistribution.verifyArchive(headlessZip, identity.version, identity.buildIdentity)
        destination.parentFile.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}-${UUID.randomUUID()}.tmp")
        require(temporary.mkdir()) { "Cannot create release staging directory" }
        try {
            copyTree(desktopSite, File(temporary, "desktop"))
            val assets = File(temporary, "assets").apply { mkdirs() }
            apk.copyTo(File(assets, "${identity.client.desktopName}-${identity.version.name}-android.apk"))
            serverZip.copyTo(File(assets, "TeamTalk-${identity.version.name}-server.zip"))
            val headlessArtifact = "assets/${HeadlessDistribution.archiveName(identity.buildIdentity)}"
            headlessZip.copyTo(File(temporary, headlessArtifact))
            zipDirectory(File(temporary, "desktop"), File(assets, "${identity.client.desktopName}-${identity.version.name}-desktop-site.zip"))
            File(temporary, "RELEASE_NOTES.md").writeText(notes)
            File(temporary, "COMMITS.md").writeText(commits)
            File(temporary, DEPLOYMENT_CONFIG).writeText(identity.deployment.toCanonicalJson(), Charsets.UTF_8)
            val manifest = buildJsonObject {
                put("format", 2)
                put("version", identity.version.name)
                put("buildNumber", identity.version.buildNumber)
                put("protocolMajor", identity.version.protocolMajor)
                put("protocolMinor", identity.version.protocolMinor)
                put("minimumProtocolMinor", identity.version.minimumProtocolMinor)
                put("sourceCommit", identity.sourceCommit)
                if (identity.distributionKind == "release") {
                    put("tag", identity.version.tag)
                } else {
                    put("distributionKind", identity.distributionKind)
                    put("protocolContractSha256", identity.protocolContractSha256)
                }
                if (identity.distributionKind == "snapshot") put("desktopRevision", identity.desktopRevision)
                put("buildIdentity", identity.buildIdentity)
                put("headlessArtifact", headlessArtifact)
                put("deploymentSha256", identity.deploymentSha256)
                put("client", clientManifest(identity.client))
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
                File(desktopSite, "${identity.client.desktopFsName}.crt").takeIf(File::isFile)?.let {
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
        require(field("format") in setOf("1", "2") && field("version") == identity.version.name &&
            field("buildNumber") == identity.version.buildNumber.toString() &&
            field("protocolMajor") == identity.version.protocolMajor.toString() &&
            field("protocolMinor") == identity.version.protocolMinor.toString() &&
            field("minimumProtocolMinor") == identity.version.minimumProtocolMinor.toString() &&
            field("sourceCommit") == identity.sourceCommit && field("buildIdentity") == identity.buildIdentity) {
            "Release bundle belongs to a different version, source, protocol or deployment configuration"
        }
        // Keep established release manifests unchanged; snapshot and private deliveries cannot claim their tag.
        require((manifest["distributionKind"]?.jsonPrimitive?.content ?: "release") == identity.distributionKind &&
            manifest["protocolContractSha256"]?.jsonPrimitive?.content == identity.protocolContractSha256 &&
            if (identity.distributionKind == "release") manifest["tag"]?.jsonPrimitive?.content == identity.version.tag
            else "tag" !in manifest) { "Bundle distribution kind or protocol schema differs" }
        require(if (identity.distributionKind == "snapshot") {
            manifest["desktopRevision"]?.jsonPrimitive?.intOrNull == identity.desktopRevision
        } else "desktopRevision" !in manifest) { "Bundle Desktop installation revision differs" }
        require(manifest["client"] == clientManifest(identity.client)) {
            "Bundle client identity differs from the effective deployment configuration"
        }
        val deploymentSnapshot = File(directory, DEPLOYMENT_CONFIG)
        val snapshotText = deploymentSnapshot.takeIf { Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) }
            ?.readText(Charsets.UTF_8)
        require(snapshotText != null && matchesDeploymentSnapshot(snapshotText, identity.deployment)) {
            "Release bundle deployment snapshot is missing or differs from the effective deployment configuration"
        }
        require(field("deploymentSha256") == sha256(snapshotText.toByteArray(Charsets.UTF_8))) {
            "Release bundle deployment digest differs from its sealed snapshot"
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
            field("notesSha256") == sha256(File(directory, "RELEASE_NOTES.md"))) { "Bundle notes differ from the distribution description" }
        require(File(directory, CHECKSUMS).readText() == checksumText(directory)) { "SHA256SUMS does not match the bundle" }
        // Existing sealed format 1/2 bundles predate Headless; retries preserve those exact three assets.
        val headlessArtifact = manifest["headlessArtifact"]?.jsonPrimitive?.content
        require(File(directory, "desktop/download.html").isFile &&
            assets(directory).size == (if (headlessArtifact == null) 3 else 4)) { "Incomplete release bundle" }
        if (headlessArtifact != null) {
            require(headlessArtifact == "assets/${HeadlessDistribution.archiveName(identity.buildIdentity)}") {
                "Unexpected Headless release artifact path"
            }
            HeadlessDistribution.verifyArchive(File(directory, headlessArtifact), identity.version, identity.buildIdentity)
        }
        verifyDesktop(File(directory, "desktop"), identity.version, identity.client, identity.desktopRevision)
        verifyAndroidApkIdentity(
            assets(directory).single { it.extension == "apk" }, identity,
            allowLegacyProducer = field("format") == "1",
            canonicalConfig = snapshotText,
        )
        return manifest
    }

    fun assets(directory: File): List<File> = File(directory, "assets").listFiles()?.filter(File::isFile)?.sortedBy(File::getName).orEmpty()

    internal fun requireAndroidApk(outputs: File, identity: BundleIdentity): File {
        val metadata = Json.parseToJsonElement(File(outputs, "output-metadata.json").readText()).jsonObject
        require(metadata.getValue("applicationId").jsonPrimitive.content == identity.client.androidApplicationId) {
            "Android output belongs to a different applicationId"
        }
        val element = metadata.getValue("elements").jsonArray.single().jsonObject
        require(element.getValue("versionName").jsonPrimitive.content == identity.version.name &&
            element.getValue("versionCode").jsonPrimitive.int == identity.version.buildNumber + 1) { "Stale Android output metadata" }
        val name = element.getValue("outputFile").jsonPrimitive.content
        require(name == File(name).name && name.endsWith(".apk")) { "Invalid APK output path" }
        val apk = File(outputs, name)
        verifyAndroidApkIdentity(apk, identity)
        return apk
    }

    internal fun verifyDesktop(
        site: File,
        version: ReleaseVersion,
        client: ClientDistributionIdentity,
        desktopRevision: Int = version.buildNumber + 1,
    ) {
        val metadata = Properties().apply { File(site, "metadata.properties").reader().use(::load) }
        require(metadata.getProperty("app.version") == version.name &&
            metadata.getProperty("app.revision") == desktopRevision.toString()) { "Stale Conveyor version metadata" }
        listOf("download.html", "${client.desktopFsName}.appinstaller", "${client.desktopFsName}.exe", "appcast-amd64.rss", "appcast-aarch64.rss").forEach {
            require(File(site, it).isFile && File(site, it).length() > 0) { "Missing Desktop site component: $it" }
        }
        val packagePrefix = "${client.desktopFsName}-${version.name}-$desktopRevision"
        val requiredPackages = listOf(
            "$packagePrefix-mac-amd64.zip", "$packagePrefix-mac-aarch64.zip",
            "$packagePrefix-windows-amd64.zip", "$packagePrefix.x64.msix", "$packagePrefix-linux-amd64.tar.gz",
            "${client.desktopFsName}_${version.name}-${desktopRevision}_amd64.deb",
        )
        val files = regularFiles(site).associateBy(File::getName)
        // A snapshot can keep the readable version. Suffix-only checks would accept the previous build's packages.
        requiredPackages.forEach { name ->
            require(files[name]?.length()?.let { it > 0 } == true) { "Desktop site lacks the current platform package: $name" }
        }
        verifyMsixDataDirectoryPolicy(files.getValue("$packagePrefix.x64.msix"))
        requiredPackages.forEach { verifySkikoNativePackage(files.getValue(it)) }
    }

    private fun clientManifest(client: ClientDistributionIdentity): JsonObject = buildJsonObject {
        put("applicationId", client.applicationId)
        put("androidApplicationId", client.androidApplicationId)
        put("displayName", client.displayName)
        put("desktopName", client.desktopName)
    }

    private fun matchesDeploymentSnapshot(snapshot: String, deployment: DeploymentConfig): Boolean {
        val current = deployment.toCanonicalJson()
        if (snapshot == current) return true
        // Existing seals omitted the final Android ID. Only the historical derivation is compatible;
        // preserve their exact canonical bytes and hashes instead of rewriting the sealed directory.
        if (deployment.client.androidApplicationId != "${deployment.client.applicationId}.android") return false
        val values = Json.parseToJsonElement(current).jsonObject.toMutableMap()
        values["client"] = JsonObject(values.getValue("client").jsonObject - "androidApplicationId")
        val legacy = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), JsonObject(values)) + "\n"
        return snapshot == legacy
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
