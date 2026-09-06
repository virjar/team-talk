package release

import deployment.DeploymentConfig
import deployment.ClientDistributionIdentity
import org.gradle.api.Project
import release.publish.GitHubPublication
import release.publish.GitHubPublisher
import release.publish.SiteConnection
import release.publish.SitePublication
import release.publish.SitePublisher
import java.io.File
import java.util.Properties

/** The same graph is used by GitHub and private workstations; CI contains no packaging or upload logic. */
fun registerReleaseTasks(
    project: Project,
    version: ReleaseVersion,
    sourceCommit: String,
    config: DeploymentConfig,
    usingLocalConfig: Boolean = false,
) {
    val root = project.rootDir
    val metadata = ReleaseMetadata(root)
    fun option(name: String, environment: String? = null): String? =
        project.providers.gradleProperty(name).orNull ?: environment?.let { project.providers.environmentVariable(it).orNull }
    val mode = option("releaseMode") ?: "version"
    require(mode in setOf("version", "private-first")) { "releaseMode must be version or private-first" }
    val privateFirst = mode == "private-first"
    val baseRevision = option("releaseBase")
    val shouldRelease = baseRevision == null || metadata.releaseChangedSince(baseRevision)
    val targets = option("releaseTargets")?.split(',')?.map(String::trim)?.toSet() ?: setOf("local")
    require(targets.isNotEmpty() && targets.all { it in setOf("local", "site", "github") }) {
        "releaseTargets must be a comma-separated selection of local,site,github"
    }
    require(!usingLocalConfig || "github" !in targets) {
        "GitHub publication requires the committed buildSrc/deployment/Deployment.kt. " +
            "The local buildSrc/deployment-local/ configuration is supported only for local and site releases."
    }
    require(!privateFirst || (usingLocalConfig && "github" !in targets && baseRevision == null &&
        config.client.applicationId != ClientDistributionIdentity().applicationId)) {
        "private-first requires a local deployment configuration, an independent private applicationId, " +
            "local/site targets and no releaseBase; it cannot republish the public application or create GitHub releases"
    }
    val contract = if (privateFirst) ProtocolContractPolicy.verify(root, version.protocolMajor,
        version.protocolMinor, version.minimumProtocolMinor) else null
    val identity = BundleIdentity(version, sourceCommit, config,
        distributionKind = if (privateFirst) "private-first" else "release",
        protocolContractSha256 = contract?.wireBaseline?.let(::sha256))
    fun notes(): String {
        if (!privateFirst) return metadata.verify(version, sourceCommit)
        metadata.verifySource(version, sourceCommit)
        return privateInstallationNotes(identity)
    }
    val existingBundle = option("releaseBundle")?.let(project::file)
    val bundle = existingBundle ?: if (privateFirst) {
        File(root, "build/private-distributions/${config.client.applicationId}/${version.name}/$sourceCommit")
    } else File(root, "build/releases/${version.name}/$sourceCommit")
    val reuseBundle = existingBundle != null || bundle.exists()

    val verifyMetadata = project.tasks.register("verifyReleaseMetadata") {
        val task = this
        task.group = "verification"
        task.description = "Verify committed root versions, maintainer release notes and frozen protocol history"
        task.doLast {
            metadata.verify(version, sourceCommit)
            ProtocolReleasePolicy.verify(root, version.name, version.buildNumber,
                version.protocolMajor, version.protocolMinor, version.minimumProtocolMinor)
            require(version.buildNumber in 0..65534) { "Conveyor installation revision exceeds its supported range" }
            val refType = project.providers.environmentVariable("GITHUB_REF_TYPE").orNull
            val refName = project.providers.environmentVariable("GITHUB_REF_NAME").orNull
            require(refType != "tag" || refName == version.tag) { "Git tag must be ${version.tag}, as defined by gradle.properties" }
        }
    }
    val verifyPrivateDistribution = project.tasks.register("verifyPrivateDistribution") {
        group = "verification"
        description = "Verify the first private package distribution without changing the product version"
        doLast {
            require(privateFirst) { "Use -PreleaseMode=private-first for first private package distribution" }
            metadata.verifySource(version, sourceCommit)
            val frozen = ProtocolContractPolicy.verify(root, version.protocolMajor,
                version.protocolMinor, version.minimumProtocolMinor)
            require(identity.protocolContractSha256 == sha256(frozen.wireBaseline)) {
                "Private distribution contract changed during packaging"
            }
            require(version.buildNumber in 0..65534) { "Conveyor installation revision exceeds its supported range" }
        }
    }
    project.tasks.register("verifyReleaseChange") {
        group = "verification"
        description = "Check immutable release history and require release metadata only when root release counters change"
        if (shouldRelease) dependsOn(verifyMetadata)
        doLast {
            baseRevision?.let(metadata::verifyFrozenHistorySince)
            if (!shouldRelease) project.logger.lifecycle("Development change: no new release metadata required.")
        }
    }

    // Preflight precedes expensive producers and catches missing credentials before building packages.
    val preflight = project.tasks.register("prepareRelease") {
        val task = this
        task.group = "release build"
        task.dependsOn("verifyRelease", if (privateFirst) verifyPrivateDistribution else verifyMetadata)
        task.doLast {
            baseRevision?.let(metadata::verifyFrozenHistorySince)
            if (!privateFirst && targets.any { it != "local" }) {
                val adopted = Properties().apply {
                    File(root, "protocol/protocol/releases/${version.name}/release.properties").reader().use(::load)
                }.getProperty("adoptedSourceCommit")
                require(adopted == null || adopted == sourceCommit) {
                    "${version.name} was already distributed from $adopted. Prepare a new root version and notes before publishing."
                }
            }
            if ("github" in targets) {
                require(!option("releaseRepository", "GITHUB_REPOSITORY").isNullOrBlank()) { "Set releaseRepository=owner/repo for GitHub publication" }
                require(!project.providers.environmentVariable("GITHUB_TOKEN").orNull.isNullOrBlank()) { "Set GITHUB_TOKEN for GitHub publication" }
            }
            if ("site" in targets) {
                require(project.file(option("releaseSshKey", "TEAMTALK_RELEASE_SSH_KEY")
                    ?: error("Set releaseSshKey or TEAMTALK_RELEASE_SSH_KEY to the existing private key file")).isFile)
                require(project.file(option("releaseKnownHosts", "TEAMTALK_RELEASE_KNOWN_HOSTS")
                    ?: error("Set releaseKnownHosts or TEAMTALK_RELEASE_KNOWN_HOSTS to the verified known_hosts file")).isFile)
            }
            if (reuseBundle) {
                ReleaseBundle.verify(bundle, identity, notes())
            } else {
                val signingDirectory = option("conveyorConfigDir", "TEAMTALK_CONVEYOR_CONFIG_DIR")
                    ?.let(project::file) ?: defaultConveyorConfigDirectory()
                requireConveyorSigningConfiguration(signingDirectory)
            }
        }
    }
    val assemble = project.tasks.register("assembleReleaseBundle") {
        val task = this
        task.group = "release build"
        task.dependsOn(preflight)
        if (!reuseBundle) task.dependsOn(":client:desktop:buildConveyorSite", ":client:android:assembleRelease", ":server:server:distZip")
        task.doLast {
            val notes = notes()
            if (reuseBundle) ReleaseBundle.verify(bundle, identity, notes) else ReleaseBundle.assemble(
                bundle, identity, File(root, "client/desktop/output"),
                File(root, "client/android/build/outputs/apk/release"),
                File(root, "server/server/build/distributions/teamtalk-server-${version.name}.zip"),
                notes, metadata.commitAppendix(version), File(root, "gradle/conveyor-tools.properties"),
                File(root, "client/desktop/build/conveyor/tool.properties"),
            )
            project.logger.lifecycle("Sealed release bundle: ${bundle.absolutePath}")
        }
    }
    // Configure ordering lazily in each project. Resolving another project's task dependencies
    // during projectsEvaluated violates Gradle's project state locks.
    project.subprojects.filterNot { it.path.startsWith(":protocol:") }.forEach { child ->
        child.tasks.configureEach { mustRunAfter(preflight) }
    }
    project.tasks.register("buildRelease") {
        val task = this
        task.group = "release build"
        task.description = "Assemble and verify the complete local release bundle (no upload)"
        task.dependsOn(assemble)
    }
    project.tasks.register("release") {
        val task = this
        task.group = "release"
        task.description = "Release the root-configured version: local bundle, optional site and/or GitHub destinations"
        if (shouldRelease) task.dependsOn(assemble)
        task.doLast {
            if (!shouldRelease) {
                project.logger.lifecycle("Root release version/build did not change; no release is required.")
            } else {
                val notes = notes()
                ReleaseBundle.verify(bundle, identity, notes)
                if ("site" in targets) {
                    val result = SitePublisher().publish(
                        SitePublication(
                            desktopDirectory = File(bundle, "desktop"),
                            androidApk = ReleaseBundle.assets(bundle).single { it.extension == "apk" },
                            version = version.name, releaseBuildNumber = version.buildNumber,
                            manifest = File(bundle, ReleaseBundle.MANIFEST),
                            metadataFiles = listOf(
                                "RELEASE_NOTES.md", "COMMITS.md", ReleaseBundle.CHECKSUMS, ReleaseBundle.DEPLOYMENT_CONFIG,
                            ).map { File(bundle, it) },
                            firstPublicationOnly = privateFirst,
                        ),
                        SiteConnection(
                            host = config.deployHost, port = config.deployPort, user = config.deployUser,
                            downloadsPath = "${config.deployPath}/static/downloads",
                            privateKey = project.file(option("releaseSshKey", "TEAMTALK_RELEASE_SSH_KEY")!!),
                            knownHosts = project.file(option("releaseKnownHosts", "TEAMTALK_RELEASE_KNOWN_HOSTS")!!),
                            privateKeyPassphrase = project.providers.environmentVariable("TEAMTALK_RELEASE_SSH_PASSPHRASE").orNull,
                        ),
                    )
                    project.logger.lifecycle("Site publication: $result")
                }
                if ("github" in targets) {
                    val result = GitHubPublisher().publish(
                        GitHubPublication(
                            repository = option("releaseRepository", "GITHUB_REPOSITORY")!!,
                            version = version.name, sourceCommit = sourceCommit, notes = notes,
                            assets = ReleaseBundle.assets(bundle) + listOf(
                                ReleaseBundle.MANIFEST, ReleaseBundle.CHECKSUMS, "RELEASE_NOTES.md", "COMMITS.md",
                                ReleaseBundle.DEPLOYMENT_CONFIG,
                            ).map { File(bundle, it) },
                            createTag = true,
                        ), project.providers.environmentVariable("GITHUB_TOKEN").get(),
                    )
                    project.logger.lifecycle("GitHub publication: $result")
                }
                project.logger.lifecycle("${if (privateFirst) "First private package distribution" else "Release"} ${version.name} completed for ${targets.joinToString()}; bundle: ${bundle.absolutePath}")
            }
        }
    }
}

/** Installation facts for an independent private app, separate from the public version's release notes. */
private fun privateInstallationNotes(identity: BundleIdentity): String = """
    # ${identity.client.displayName} ${identity.version.name} 私有安装包

    这是独立私有应用的首次测试分发，保持仓库当前展示版本和安装序号，不创建产品发行 tag 或 GitHub Release。

    - 服务器：${identity.deployment.serverUrl}
    - 应用标识：${identity.client.applicationId}（Android：${identity.client.androidApplicationId}）
    - 英文安装名称：${identity.client.desktopName}
    - 展示版本：${identity.version.name}；平台安装序号：${identity.version.buildNumber + 1}
    - 源码：${identity.sourceCommit}
    - 协议：${identity.version.protocolMajor}.${identity.version.protocolMinor}；最低 minor：${identity.version.minimumProtocolMinor}
    - 已冻结协议契约 SHA-256：${identity.protocolContractSha256}

    Android 安装包入口为 `/downloads/TeamTalk-android.apk`；Desktop 安装说明和更新入口为
    `/downloads/desktop/download.html`，均相对上述服务器地址。
    安装身份与公版独立，首次安装后登录自己的私有节点；后续保留同一应用身份、签名和资料目录。
    已分发的同版本安装包只允许按原文件重试，后续更新需要另行确认版本与安装序号。

    当前仍为开发者预览，不保证长期兼容；Android 最低 8.0，macOS 最低 14.0。
    macOS 公证和 Windows 受信任签名尚未配置，按 Desktop 下载页说明安装。
""".trimIndent() + "\n"
