import deployment.DeploymentConfig
import org.gradle.api.artifacts.ProjectDependency

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.buildconfig) apply false
    alias(libs.plugins.ksp) apply false
}

val releaseVersion = providers.gradleProperty("teamtalk.releaseVersion").orNull?.trim()
    ?.takeIf(String::isNotEmpty)
    ?: throw GradleException("teamtalk.releaseVersion must be set in gradle.properties")
if (!Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matches(releaseVersion)) {
    throw GradleException("teamtalk.releaseVersion must be a three-part numeric version: $releaseVersion")
}
fun versionCounter(name: String, maximum: Int): Int = providers.gradleProperty("teamtalk.$name")
    .orNull?.toIntOrNull()?.takeIf { it in 0..maximum }
    ?: throw GradleException("teamtalk.$name must be an integer in 0..$maximum")

// 展示版本、平台安装序号、协议版本分别按需推进，禁止再由展示字符串猜测协议或数据库格式。
val releaseBuildNumber = versionCounter("releaseBuildNumber", 2_099_999_999)
// Android 安装器要求正 versionCode；内部零号构建映射为 1，不参与协议兼容判断。
val androidVersionCode = releaseBuildNumber + 1
val protocolMajor = versionCounter("protocolMajor", 32_767)
val protocolMinor = versionCounter("protocolMinor", 65_535)
val minimumProtocolMinor = versionCounter("minimumProtocolMinor", protocolMinor)
version = releaseVersion

extra.apply {
    set("androidMinSdk", 26)
    set("androidTargetSdk", 35)
    set("androidCompileSdk", 36)
    set("releaseVersion", releaseVersion)
    set("androidVersionCode", androidVersionCode)
    set("releaseBuildNumber", releaseBuildNumber)
    set("protocolMajor", protocolMajor)
    set("protocolMinor", protocolMinor)
    set("minimumProtocolMinor", minimumProtocolMinor)
}

// ── 单一部署配置 ──

val deploymentConfigFile = rootProject.file("gradle/deployment.json")
if (!deploymentConfigFile.isFile) throw GradleException("Deployment config not found: $deploymentConfigFile")
val deploymentConfig = DeploymentConfig.load(deploymentConfigFile.readText())
extra.set("deploymentConfig", deploymentConfig)

// ── 构建信息 ──

val gitRevision = providers.exec {
    commandLine("git", "rev-parse", "HEAD")
}.standardOutput.asText.get().trim()
if (!Regex("[0-9a-fA-F]{40}").matches(gitRevision)) {
    throw GradleException("Cannot determine the full Git source revision")
}
val sourceDirty = providers.exec {
    commandLine("git", "status", "--porcelain", "--untracked-files=normal")
}.standardOutput.asText.get().isNotBlank()
val gitCommitId = gitRevision.take(12)
val buildIdentity = "$releaseVersion+$gitRevision${if (sourceDirty) ".dirty" else ""}"

val buildTime = java.time.LocalDateTime.now().format(
    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
)

extra.set("gitCommitId", gitCommitId)
extra.set("gitRevision", gitRevision)
extra.set("sourceDirty", sourceDirty)
extra.set("buildIdentity", buildIdentity)
extra.set("buildTime", buildTime)

// 强制统一 Kotlin 依赖版本，防止 AGP 或其他插件引入低版本导致 metadata 冲突
subprojects {
    version = releaseVersion
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") {
                useVersion(libs.versions.kotlin.get())
            }
        }
    }
}

val trackedAdminGeneratedPaths = providers.exec {
    commandLine("git", "ls-files", "--cached", "--", "server/admin/dist", "server/admin/node_modules")
}.standardOutput.asText.get()
    .lineSequence()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .toList()

val checkArchitecture = tasks.register<ArchitectureCheckTask>("checkArchitecture") {
    group = "verification"
    description = "Verify source-level module and hexagonal architecture boundaries"
    repositoryRoot.set(layout.projectDirectory)
    trackedAdminGeneratedFiles.set(trackedAdminGeneratedPaths)
    configuredDependencyViolations.convention(emptyList())
}

gradle.projectsEvaluated {
    val dependencyViolations = buildList {
        rootProject.subprojects.forEach { candidate ->
            candidate.configurations
                .filterNot { it.name.contains("test", ignoreCase = true) }
                .forEach { configuration ->
                    configuration.dependencies.withType(ProjectDependency::class.java)
                        .filter { dependency -> dependency.name == "shared-testkit" }
                        .forEach {
                            add(
                                "${candidate.path}:${configuration.name}: product configuration " +
                                    "must not depend on :client:shared-testkit",
                            )
                        }
                }
        }
        rootProject.findProject(":protocol:protocol")?.configurations
            ?.filterNot { it.name.contains("test", ignoreCase = true) }
            ?.forEach { configuration ->
                configuration.dependencies
                    .filter { dependency -> dependency.group == "io.netty" }
                    .forEach { dependency ->
                        add(
                            ":protocol:protocol:${configuration.name}: contract module must not depend on " +
                                "Netty (${dependency.group}:${dependency.name}); use :protocol:protocol-netty",
                        )
                    }
            }
    }
    checkArchitecture.configure {
        configuredDependencyViolations.set(dependencyViolations)
    }
}

val configuredRootVersion = version.toString()
val githubRefType = providers.environmentVariable("GITHUB_REF_TYPE").orNull
val githubRefName = providers.environmentVariable("GITHUB_REF_NAME").orNull

val verifyRelease by tasks.registering {
    group = "verification"
    description = "Verify architecture, version identity, and clean release source provenance"
    dependsOn(checkArchitecture, ":protocol:protocol:verifyProtocolBaseline")
    doLast {
        if (sourceDirty) {
            throw GradleException(
                "Release source tree is dirty; commit or stash every tracked and untracked source change " +
                    "before building or deploying $buildIdentity",
            )
        }
        check(configuredRootVersion == releaseVersion) { "Root release version drifted from its fact source" }
        if (githubRefType == "tag" && githubRefName != "v$releaseVersion") {
            throw GradleException(
                "Release tag/version mismatch: expected v$releaseVersion, found ${githubRefName ?: "missing"}",
            )
        }
        println("Verified release identity: $buildIdentity (Android versionCode=$androidVersionCode)")
    }
}

tasks.register("buildRelease") {
    group = "release"
    description = "Build all release artifacts (desktop via Conveyor jar; jpackage fallback excluded)"
    dependsOn(verifyRelease, ":server:server:buildServerDist", ":client:desktop:desktopJar", ":client:android:assembleRelease")
}

// ── 发布任务（deploy 分组，按发布目标组织；配置见 gradle/deployment.json）──
// deployServer    : 本地构建服务端后部署（发布前必须通过 verifyRelease）
// deployStagedServer: 部署 CI 已构建并带 identity manifest 的服务端产物
// deployServerResetData: 显式确认目标后，以空 PostgreSQL 和本地 stores 部署服务端
// releaseDesktop  : Desktop 三平台安装包 + 更新站点（Conveyor 一键构建上传）
// uploadDesktopSite: 只上传 CI 已构建的 Desktop site，不运行 Conveyor
// releaseAndroid  : Android APK 构建 + 上传下载目录
// releaseClients  : 双端一键（desktop + apk 同版本同时发布——两端共享
//                   app/shared/protocol 代码，常规发版应同时发布）
// uploadClientArtifacts: CI staging 产物上传（工作流内部使用）

tasks.register("deployServer") {
    group = "deploy"
    description = "Build and deploy the server configured by gradle/deployment.json"
    dependsOn(verifyRelease, ":server:server:buildServerDist")
    doLast {
        deployment.deployServer(
            rootDir,
            layout.projectDirectory.dir("server/server/build/install/teamtalk-server").asFile,
            deploymentConfig,
            findProperty("sslCert")?.toString(),
            findProperty("sslKey")?.toString(),
            releaseVersion,
            buildIdentity,
        )
    }
}

tasks.register("deployStagedServer") {
    group = "deploy"
    description = "Deploy a prebuilt CI server distribution without rebuilding it"
    dependsOn(verifyRelease)
    doLast {
        val stagingPath = findProperty("SERVER_DIST_DIR")?.toString()
            ?: throw GradleException("SERVER_DIST_DIR property is required")
        deployment.deployServer(
            rootDir,
            file(stagingPath),
            deploymentConfig,
            findProperty("sslCert")?.toString(),
            findProperty("sslKey")?.toString(),
            releaseVersion,
            buildIdentity,
        )
    }
}

tasks.register("deployServerResetData") {
    group = "deploy"
    description = "DESTRUCTIVE: deploy server after rebuilding its exact configured data target from empty"
    dependsOn(verifyRelease, ":server:server:buildServerDist")
    doLast {
        deployment.deployServerResetData(
            rootDir,
            layout.projectDirectory.dir("server/server/build/install/teamtalk-server").asFile,
            deploymentConfig,
            findProperty("sslCert")?.toString(),
            findProperty("sslKey")?.toString(),
            releaseVersion,
            buildIdentity,
            findProperty(deployment.RESET_DEPLOY_CONFIRM_PROPERTY)?.toString(),
        )
    }
}

tasks.register("releaseDesktop") {
    group = "deploy"
    description = "Desktop: build three-platform site with Conveyor and upload the update site"
    dependsOn(verifyRelease, ":client:desktop:desktopJar")
    doLast {
        deployment.buildDesktopSite(rootDir)
        val siteDir = layout.projectDirectory.dir("client/desktop/output").asFile
        deployment.writeReleaseArtifactManifest(siteDir, "desktop-site", releaseVersion, buildIdentity)
        deployment.uploadDesktopSite(siteDir, deploymentConfig, releaseVersion, buildIdentity)
    }
}

tasks.register("writeDesktopSiteManifest") {
    group = "release"
    description = "Stamp an already-built Conveyor site with the verified release identity"
    dependsOn(verifyRelease)
    val manifestFile = layout.projectDirectory.file(
        "client/desktop/output/${deployment.RELEASE_ARTIFACT_MANIFEST_FILE}",
    )
    inputs.property("releaseVersion", releaseVersion)
    inputs.property("buildIdentity", buildIdentity)
    outputs.file(manifestFile)
    doLast {
        deployment.writeReleaseArtifactManifest(
            layout.projectDirectory.dir("client/desktop/output").asFile,
            "desktop-site",
            releaseVersion,
            buildIdentity,
        )
    }
}

tasks.register("uploadDesktopSite") {
    group = "deploy"
    description = "Upload a prebuilt Conveyor site without rebuilding or modifying it"
    dependsOn(verifyRelease)
    doLast {
        val stagingPath = findProperty("DESKTOP_SITE_DIR")?.toString()
            ?: throw GradleException("DESKTOP_SITE_DIR property is required")
        deployment.uploadDesktopSite(file(stagingPath), deploymentConfig, releaseVersion, buildIdentity)
    }
}

tasks.register("releaseAndroid") {
    group = "deploy"
    description = "Android: build release APK and upload to the downloads directory"
    dependsOn(verifyRelease, ":client:android:assembleRelease")
    doLast { deployment.uploadAndroidApk(rootDir, deploymentConfig) }
}

tasks.register("releaseClients") {
    group = "deploy"
    description = "Both clients in lockstep: releaseDesktop + releaseAndroid"
    dependsOn("releaseDesktop", "releaseAndroid")
}

tasks.register("uploadClientArtifacts") {
    group = "deploy"
    description = "Upload staged client artifacts from CI (APK; desktop ships via releaseDesktop)"
    dependsOn(verifyRelease)
    doLast {
        val stagingPath = project.findProperty("ARTIFACT_STAGING_DIR")?.toString()
            ?: throw GradleException("ARTIFACT_STAGING_DIR property is required")
        val stagingDir = File(stagingPath)
        if (!stagingDir.isDirectory) throw GradleException("Staging directory does not exist: $stagingPath")
        deployment.uploadAndroidApk(rootDir, deploymentConfig, stagingDir)
    }
}
