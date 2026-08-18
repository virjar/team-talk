import profiles.DemoConfig

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



extra.apply {
    set("androidMinSdk", 26)
    set("androidTargetSdk", 35)
    set("androidCompileSdk", 36)
    set("packageVersion", "1.0.0")
}

// ── 唯一 Demo 环境 ──

val demoConfigFile = rootProject.file("gradle/profiles/demo.json")
if (!demoConfigFile.isFile) throw GradleException("Demo config not found: $demoConfigFile")
val demoConfig = DemoConfig.load(demoConfigFile.readText())
extra.set("demoConfig", demoConfig)

// ── 构建信息 ──

val gitCommitId = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
}.standardOutput.asText.get().trim()

val buildTime = java.time.LocalDateTime.now().format(
    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
)

extra.set("gitCommitId", gitCommitId)
extra.set("buildTime", buildTime)

// 强制统一 Kotlin 依赖版本，防止 AGP 或其他插件引入低版本导致 metadata 冲突
subprojects {
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") {
                useVersion(libs.versions.kotlin.get())
            }
        }
    }
}

tasks.register("buildRelease") {
    group = "release"
    description = "Build all Demo release artifacts"
    dependsOn(":server:buildServerDist", ":desktop:packageReleaseDistributionForCurrentOS", ":android:assembleRelease")
}

tasks.register("uploadRelease") {
    group = "deploy"
    description = "Build and upload Demo client artifacts"
    dependsOn("buildRelease")
    doLast { profiles.uploadArtifacts(rootDir, demoConfig) }
}

tasks.register("uploadClientArtifacts") {
    group = "deploy"
    description = "Upload staged Demo client artifacts (CI use)"
    doLast {
        val stagingPath = project.findProperty("ARTIFACT_STAGING_DIR")?.toString()
            ?: throw GradleException("ARTIFACT_STAGING_DIR property is required")
        val stagingDir = File(stagingPath)
        if (!stagingDir.isDirectory) throw GradleException("Staging directory does not exist: $stagingPath")
        profiles.uploadArtifacts(rootDir, demoConfig, stagingDir)
    }
}

tasks.register("deployServerDemo") {
    group = "deploy"
    description = "Build and deploy the Demo server"
    dependsOn(":server:buildServerDist")
    doLast {
        profiles.deployServer(
            rootDir,
            demoConfig,
            findProperty("sslCert")?.toString(),
            findProperty("sslKey")?.toString(),
        )
    }
}
