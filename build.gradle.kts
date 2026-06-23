import profiles.BuildProfile

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
}



extra.apply {
    set("androidMinSdk", 26)
    set("androidTargetSdk", 35)
    set("androidCompileSdk", 36)
    set("packageVersion", "1.0.0")
}

// ── Profile 系统 ──

// 从 gradle/profiles/ 目录加载所有 JSON Profile（内置 + 外部注入）
val profileDir = rootProject.file("gradle/profiles")
val profileList = profiles.loadAllProfiles(profileDir)
val profileMap: Map<String, BuildProfile> = profileList.associateBy { it.name }
if (profileMap.isEmpty()) {
    throw GradleException("No profiles found in gradle/profiles/. Add .json files there.")
}

extra.set("allProfiles", profileMap)
extra.set("profileNames", profileMap.keys.toList())

// 当前 Profile：通过 -Pprofile=demo 指定，默认 dev
// 不用环境变量（Gradle 最佳实践：用项目属性做构建配置）
val profileName = (project.findProperty("profile") as String?)?.takeIf { it.isNotBlank() } ?: "dev"
val activeProfile = profileMap[profileName]
    ?: throw GradleException(
        "Profile '$profileName' not found. Available: ${profileMap.keys.joinToString(", ")}"
    )

extra.set("activeProfileName", profileName)
extra.set("activeProfile", activeProfile)

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

// ── 辅助函数：按 profile 首字母大写 ──

fun capitalize(s: String) = s.replaceFirstChar { it.uppercase() }

// ── 发布聚合任务（按 profile 注册） ──

profileMap.forEach { (pn, _) ->
    val cap = capitalize(pn)

    tasks.register("build${cap}Release") {
        group = "release"
        description = "Build all release artifacts for profile: $pn"
        // Desktop 用固定的 packageReleaseDistributionForCurrentOS（含 ProGuard 压缩），
        // 与 profile 无关——Compose 的 release 任务名不带 profile 前缀。
        dependsOn(":server:buildServerDist", ":desktop:packageReleaseDistributionForCurrentOS", ":android:assemble${cap}Release")
    }
}

tasks.register("buildRelease") {
    group = "release"
    description = "Build all release artifacts (alias for build${capitalize(profileName)}Release)"
    dependsOn("build${capitalize(profileName)}Release")
}

// ── 上传任务（按 profile 注册） ──

profileMap.forEach { (pn, profile) ->
    val cap = capitalize(pn)

    tasks.register("upload${cap}Release") {
        group = "deploy"
        description = "Build and upload release artifacts for profile: $pn"
        dependsOn("build${cap}Release")

        doLast {
            profiles.uploadArtifacts(rootDir, profile)
        }
    }

    tasks.register("upload${cap}ClientArtifacts") {
        group = "deploy"
        description = "Upload client artifacts from staging dir for profile: $pn (CI use)"

        doLast {
            val stagingPath = project.findProperty("ARTIFACT_STAGING_DIR")?.toString()
                ?: throw GradleException("ARTIFACT_STAGING_DIR property is required for uploadClientArtifacts")
            val stagingDir = File(stagingPath)
            if (!stagingDir.exists()) {
                throw GradleException("Staging directory does not exist: $stagingPath")
            }
            profiles.uploadArtifacts(rootDir, profile, stagingDir)
        }
    }
}

tasks.register("uploadRelease") {
    group = "deploy"
    description = "Upload release artifacts (alias for upload${capitalize(profileName)}Release)"
    dependsOn("upload${capitalize(profileName)}Release")
}

// ── 部署任务（按 profile 注册） ──

profileMap.forEach { (pn, profile) ->
    val cap = capitalize(pn)
    tasks.register("deployServer$cap") {
        group = "deploy"
        description = "Build and deploy server with profile: $pn"
        dependsOn(":server:buildServerDist")

        doLast {
            val sslCert = findProperty("sslCert")?.toString()
            val sslKey = findProperty("sslKey")?.toString()
            profiles.deployServer(rootDir, profile, sslCert, sslKey)
        }
    }
}

tasks.register("deployServer") {
    group = "deploy"
    description = "Deploy server (alias for deployServer${capitalize(profileName)})"
    dependsOn("deployServer${capitalize(profileName)}")
}
