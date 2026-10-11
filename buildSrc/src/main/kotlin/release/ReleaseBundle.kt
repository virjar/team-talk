package release

import com.android.apksig.ApkVerifier
import deployment.ClientDistributionIdentity
import deployment.DeploymentConfig
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
    // 历史遗留的 manifest 字段名；快照记录实际的开发协议 schema，但不冻结它。
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

/** 一次性构建到临时同级目录，密封每个字节，然后发布本地目录。 */
object ReleaseBundle {
    const val MANIFEST = "release-manifest.json"
    const val CHECKSUMS = "SHA256SUMS"
    const val DEPLOYMENT_CONFIG = "deployment-config.json"

    fun assemble(
        destination: File,
        identity: BundleIdentity,
        desktopShellRoot: File,
        desktopPayloadRoot: File,
        androidOutputs: File,
        serverZip: File,
        notes: String,
        commits: String,
        headlessZip: File,
        androidMappingFile: File? = null,
    ): File {
        if (destination.exists()) {
            verify(destination, identity, notes)
            return destination
        }
        verifyDesktopArtifacts(desktopShellRoot, desktopPayloadRoot, identity)
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
            val desktopDir = File(temporary, "desktop").apply { mkdirs() }
            DesktopTarget.values().forEach { desktopTarget ->
                val key = desktopTarget.key
                val shell = File(desktopShellRoot, key)
                val target = File(desktopDir, key).apply { mkdirs() }
                desktopInstallers(shell).forEach { it.copyTo(File(target, it.name)) }
                val payloadZip = File(File(desktopPayloadRoot, key), "payload.zip")
                payloadZip.copyTo(File(target, "payload.zip"))
            }
            val assets = File(temporary, "assets").apply { mkdirs() }
            apk.copyTo(File(assets, "${identity.client.desktopName}-${identity.version.name}-android.apk"))
            // R8 反混淆 mapping 随制品密封（0.0.7 遥测复盘需求）：混淆栈帧
            // （o8.f u8.n1.c:427）只有靠同构建 mapping 才能还原类名/行号。
            androidMappingFile?.takeIf { it.isFile }?.copyTo(File(assets, "mapping.txt"))
            serverZip.copyTo(File(assets, "TeamTalk-${identity.version.name}-server.zip"))
            val headlessArtifact = "assets/${HeadlessDistribution.archiveName(identity.buildIdentity)}"
            headlessZip.copyTo(File(temporary, headlessArtifact))
            zipDirectory(File(temporary, "desktop"), File(assets, "${identity.client.desktopName}-${identity.version.name}-desktop-site.zip"))
            File(temporary, "RELEASE_NOTES.md").writeText(notes)
            File(temporary, "COMMITS.md").writeText(commits)
            File(temporary, DEPLOYMENT_CONFIG).writeText(identity.deployment.toCanonicalJson(), Charsets.UTF_8)
            val manifest = buildJsonObject {
                put("format", 3)
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
                put("buildJavaVersion", System.getProperty("java.version"))
                putJsonArray("androidSigningCertificatesSha256") {
                    apkVerification.signerCertificates.forEach { add(sha256(it.encoded)) }
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

    /** 复用只接受相同源码/配置与逐字节一致的密封文件，绝不接受重新贴标的目录。 */
    fun verify(directory: File, identity: BundleIdentity, notes: String): JsonObject {
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) { "Release bundle is missing: $directory" }
        val manifestFile = File(directory, MANIFEST)
        require(Files.isRegularFile(manifestFile.toPath(), NOFOLLOW_LINKS)) { "Release bundle manifest is missing" }
        val manifest = Json.parseToJsonElement(manifestFile.readText()).jsonObject
        fun field(name: String): String = manifest.getValue(name).jsonPrimitive.content
        val format = field("format")
        require(format in setOf("1", "2", "3") && field("version") == identity.version.name &&
            field("buildNumber") == identity.version.buildNumber.toString() &&
            field("protocolMajor") == identity.version.protocolMajor.toString() &&
            field("protocolMinor") == identity.version.protocolMinor.toString() &&
            field("minimumProtocolMinor") == identity.version.minimumProtocolMinor.toString() &&
            field("sourceCommit") == identity.sourceCommit && field("buildIdentity") == identity.buildIdentity) {
            "Release bundle belongs to a different version, source, protocol or deployment configuration"
        }
        // 既有正式版 manifest 保持不变；快照与私有分发不能认领其 tag。
        require((manifest["distributionKind"]?.jsonPrimitive?.content ?: "release") == identity.distributionKind &&
            manifest["protocolContractSha256"]?.jsonPrimitive?.content == identity.protocolContractSha256 &&
            if (identity.distributionKind == "release") manifest["tag"]?.jsonPrimitive?.content == identity.version.tag
            else "tag" !in manifest) { "Bundle distribution kind or protocol schema differs" }
        require(if (identity.distributionKind == "snapshot") {
            manifest["desktopRevision"]?.jsonPrimitive?.intOrNull == identity.desktopRevision
        } else "desktopRevision" !in manifest) { "Bundle Desktop installation revision differs" }
        val currentClient = clientManifest(identity.client)
        val legacyClient = JsonObject(currentClient - "iosBundleId")
        require(manifest["client"] == currentClient ||
            (identity.client.iosBundleId == "${identity.client.applicationId}.ios" && manifest["client"] == legacyClient)) {
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
        // 既有 format 1/2 密封 bundle 早于 Headless 引入；重试时原样保留那三份制品。
        val headlessArtifact = manifest["headlessArtifact"]?.jsonPrimitive?.content
        if (format == "3") {
            require(assets(directory).size == 4) { "Incomplete release bundle" }
            requireNotNull(headlessArtifact) { "Format 3 bundles always seal the Headless artifact" }
            require(headlessArtifact == "assets/${HeadlessDistribution.archiveName(identity.buildIdentity)}") {
                "Unexpected Headless release artifact path"
            }
            HeadlessDistribution.verifyArchive(File(directory, headlessArtifact), identity.version, identity.buildIdentity)
            verifyDesktopArtifacts(File(directory, "desktop"), File(directory, "desktop"), identity)
        } else {
            // 历史 Conveyor 布局的密封目录：字节完整性已由上方 files 清单校验保证。
            require(File(directory, "desktop/download.html").isFile &&
                assets(directory).size == (if (headlessArtifact == null) 3 else 4)) { "Incomplete release bundle" }
            if (headlessArtifact != null) {
                HeadlessDistribution.verifyArchive(File(directory, headlessArtifact), identity.version, identity.buildIdentity)
            }
        }
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

    /**
     * 校验桌面四目标产物：壳归档 + 安装器 + payload.zip（其内 payload.properties 的
     * version/build 必须与发行身份一致——build 取 desktopRevision，正式=buildNumber+1，
     * 快照=提交历史推导值，保证更新通道内单调）。
     */
    internal fun verifyDesktopArtifacts(shellRoot: File, payloadRoot: File, identity: BundleIdentity) {
        DesktopTarget.values().forEach { target ->
            val key = target.key
            val shell = File(shellRoot, key)
            val payloadZip = if (payloadRoot == shellRoot) {
                File(shell, "payload.zip")
            } else {
                File(File(payloadRoot, key), "payload.zip")
            }
            require(payloadZip.isFile && payloadZip.length() > 0) { "Desktop target $key lacks payload.zip" }
            java.util.zip.ZipFile(payloadZip).use { zip ->
                val entry = zip.getEntry("payload.properties")
                    ?: error("payload.zip for $key has no payload.properties")
                val props = Properties().apply { zip.getInputStream(entry).reader().use(::load) }
                require(props.getProperty("version") == identity.version.name &&
                    props.getProperty("build") == identity.desktopRevision.toString()) {
                    "Desktop payload for $key is stale: ${props.getProperty("version")}+" +
                        "${props.getProperty("build")} (expected ${identity.version.name}+${identity.desktopRevision})"
                }
                require(props.getProperty("buildIdentity") == identity.buildIdentity) {
                    "Desktop payload for $key belongs to a different source build"
                }
                require(props.getProperty("minShellAbi")?.toIntOrNull()?.let { it > 0 } == true) {
                    "Desktop payload for $key lacks minShellAbi"
                }
            }
            val archives = desktopInstallers(shell)
            require(archives.isNotEmpty()) { "Desktop target $key lacks shell archives" }
            target.platform.installerExtension?.let { extension ->
                require(archives.any { it.extension == extension }) {
                    "Desktop target $key lacks the .$extension installer"
                }
            }
        }
    }

    /** GitHub/站点发布用的桌面产物清单。 */
    fun desktopArtifacts(directory: File): List<File> =
        DesktopTarget.values().flatMap { target -> desktopInstallers(File(directory, "desktop/${target.key}")) }
            .sortedBy(File::getName)

    /** 生产方把安装器放在子目录；密封 bundle 只平铺收录可分发文件。 */
    internal fun desktopInstallers(directory: File): List<File> =
        listOf(directory, File(directory, "installer")).flatMap { it.listFiles().orEmpty().toList() }
            .filter { it.isFile && it.name != "payload.zip" && it.extension in setOf("zip", "gz", "exe", "deb") }
            .sortedBy(File::getName)

    private fun clientManifest(client: ClientDistributionIdentity): JsonObject = buildJsonObject {
        put("applicationId", client.applicationId)
        put("androidApplicationId", client.androidApplicationId)
        put("iosBundleId", client.iosBundleId)
        put("displayName", client.displayName)
        put("desktopName", client.desktopName)
    }

    private fun matchesDeploymentSnapshot(snapshot: String, deployment: DeploymentConfig): Boolean {
        val current = deployment.toCanonicalJson()
        if (snapshot == current) return true
        // 已发布的密封省略了 iOS，更早的密封还省略了 Android。只有历史默认值
        // 才允许省略；保留密封时的原始字节，而不重写已分发的目录。
        val values = Json.parseToJsonElement(current).jsonObject.toMutableMap()
        val client = values.getValue("client").jsonObject.toMutableMap()
        if (deployment.client.iosBundleId != "${deployment.client.applicationId}.ios" || deployment.apnsPush != null) return false
        client.remove("iosBundleId")
        fun legacySnapshot(): String {
            values["client"] = JsonObject(client.toMap())
            return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), JsonObject(values)) + "\n"
        }
        if (snapshot == legacySnapshot()) return true
        if (deployment.client.androidApplicationId != "${deployment.client.applicationId}.android") return false
        client.remove("androidApplicationId")
        return snapshot == legacySnapshot()
    }

    private fun zipDirectory(source: File, destination: File) {
        ZipArchiveOutputStream(destination).use { zip ->
            regularFiles(source).forEach { file ->
                val entry = ZipArchiveEntry(file.relativeTo(source).invariantSeparatorsPath).apply {
                    time = 0L
                    unixMode = 0b110100100 // 静态站点文件在服务器上不可执行。
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
