package release

import deployment.DeploymentConfig
import org.gradle.api.Project
import release.publish.GitHubPublication
import release.publish.GitHubPublisher
import release.publish.SiteConnection
import release.publish.SitePublication
import release.publish.SitePublisher
import java.io.File
import java.util.Properties

/** The same graph is used by GitHub and private workstations; CI contains no packaging or upload logic. */
fun registerReleaseTasks(project: Project, version: ReleaseVersion, sourceCommit: String, config: DeploymentConfig) {
    val root = project.rootDir
    val metadata = ReleaseMetadata(root)
    val identity = BundleIdentity(version, sourceCommit, sha256(File(root, "gradle/deployment.json")))
    fun option(name: String, environment: String? = null): String? =
        project.providers.gradleProperty(name).orNull ?: environment?.let { project.providers.environmentVariable(it).orNull }
    val baseRevision = option("releaseBase")
    val shouldRelease = baseRevision == null || metadata.releaseChangedSince(baseRevision)
    val targets = option("releaseTargets")?.split(',')?.map(String::trim)?.toSet() ?: setOf("local")
    require(targets.isNotEmpty() && targets.all { it in setOf("local", "site", "github") }) {
        "releaseTargets must be a comma-separated selection of local,site,github"
    }
    val existingBundle = option("releaseBundle")?.let(project::file)
    val bundle = existingBundle ?: File(root, "build/releases/${version.name}/$sourceCommit")
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
        task.dependsOn("verifyRelease", verifyMetadata)
        task.doLast {
            baseRevision?.let(metadata::verifyFrozenHistorySince)
            if (targets.any { it != "local" }) {
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
                ReleaseBundle.verify(bundle, identity, metadata.notesFile(version).readText())
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
            val notes = metadata.verify(version, sourceCommit)
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
                val notes = metadata.verify(version, sourceCommit)
                ReleaseBundle.verify(bundle, identity, notes)
                if ("site" in targets) {
                    val result = SitePublisher().publish(
                        SitePublication(
                            desktopDirectory = File(bundle, "desktop"),
                            androidApk = ReleaseBundle.assets(bundle).single { it.extension == "apk" },
                            version = version.name, releaseBuildNumber = version.buildNumber,
                            manifest = File(bundle, ReleaseBundle.MANIFEST),
                            metadataFiles = listOf("RELEASE_NOTES.md", "COMMITS.md", ReleaseBundle.CHECKSUMS).map { File(bundle, it) },
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
                            assets = ReleaseBundle.assets(bundle) + listOf(ReleaseBundle.MANIFEST, ReleaseBundle.CHECKSUMS, "RELEASE_NOTES.md", "COMMITS.md").map { File(bundle, it) },
                            createTag = true,
                        ), project.providers.environmentVariable("GITHUB_TOKEN").get(),
                    )
                    project.logger.lifecycle("GitHub publication: $result")
                }
                project.logger.lifecycle("Release ${version.name} completed for ${targets.joinToString()}; bundle: ${bundle.absolutePath}")
            }
        }
    }
}
