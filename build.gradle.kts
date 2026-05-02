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
}

extra.apply {
    set("androidMinSdk", 26)
    set("androidTargetSdk", 35)
    set("androidCompileSdk", 36)
    set("packageVersion", "1.0.0")
}

// ── Profile 全量发现 ──

// 扫描所有 profile 文件
val allProfiles: Map<String, Map<String, String>> = file("gradle/profiles").listFiles()
    ?.filter { it.name.endsWith(".properties") }
    ?.associate { profileFile ->
        val name = profileFile.name.removeSuffix(".properties")
        val props = java.util.Properties().apply {
            profileFile.inputStream().use { load(it) }
        }
        name to props.map { it.key.toString() to it.value.toString() }.toMap()
    } ?: emptyMap()

if (allProfiles.isEmpty()) {
    throw GradleException("No profiles found in gradle/profiles/. Expected *.properties files.")
}

// 注册到 extra 供子模块消费
extra.set("allProfiles", allProfiles)
extra.set("profileNames", allProfiles.keys.toList())

// 活跃 profile（向后兼容 -PbuildProfile=xxx）
val profileName = findProperty("buildProfile")?.toString() ?: "dev"
if (!allProfiles.containsKey(profileName)) {
    throw GradleException(
        "Profile '$profileName' not found. Available: ${allProfiles.keys.joinToString(", ")}"
    )
}
extra.set("activeProfileName", profileName)

// 验证活跃 profile 必填字段
val activeProps = allProfiles[profileName]!!
val serverUrl = activeProps["serverUrl"]
    ?: throw GradleException("Profile '$profileName' must define 'serverUrl'")
if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
    throw GradleException("Profile '$profileName': serverUrl must start with http:// or https://")
}
activeProps["tcpHost"] ?: throw GradleException("Profile '$profileName' must define 'tcpHost'")
activeProps["tcpPort"] ?: throw GradleException("Profile '$profileName' must define 'tcpPort'")
activeProps["buildProfile"] ?: throw GradleException("Profile '$profileName' must define 'buildProfile'")

// 注入活跃 profile 到 extra（向后兼容：子模块通过 rootProject.extra 读取）
activeProps.forEach { (k, v) -> extra.set(k, v) }

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

allProfiles.forEach { (pn, _) ->
    val cap = capitalize(pn)

    tasks.register("build${cap}Release") {
        group = "release"
        description = "Build all release artifacts for profile: $pn"
        dependsOn(":server:buildServerDist", ":desktop:package${cap}DistributionForCurrentOS", ":android:assemble${cap}Release")
    }
}

// buildRelease 作为活跃 profile 的别名
tasks.register("buildRelease") {
    group = "release"
    description = "Build all release artifacts (alias for build${capitalize(profileName)}Release)"
    dependsOn("build${capitalize(profileName)}Release")
}

// ── 上传任务（按 profile 注册） ──

allProfiles.keys.forEach { pn ->
    val cap = capitalize(pn)
    tasks.register("upload${cap}Release") {
        group = "deploy"
        description = "Build and upload release artifacts for profile: $pn"
        dependsOn("build${cap}Release")

        doLast {
            uploadArtifacts(rootDir, allProfiles, pn)
        }
    }
}

// uploadRelease 作为活跃 profile 的别名
tasks.register("uploadRelease") {
    group = "deploy"
    description = "Upload release artifacts (alias for upload${capitalize(profileName)}Release)"
    dependsOn("upload${capitalize(profileName)}Release")
}

// ── 部署任务（按 profile 注册） ──

allProfiles.keys.forEach { pn ->
    val cap = capitalize(pn)
    tasks.register("deployServer$cap") {
        group = "deploy"
        description = "Build and deploy server with profile: $pn"
        dependsOn(":server:buildServerDist")

        doLast {
            val sslCert = findProperty("sslCert")?.toString()
            val sslKey = findProperty("sslKey")?.toString()
            deployServer(rootDir, allProfiles, pn, sslCert, sslKey)
        }
    }
}

// deployServer 作为活跃 profile 的别名
tasks.register("deployServer") {
    group = "deploy"
    description = "Deploy server (alias for deployServer${capitalize(profileName)})"
    dependsOn("deployServer${capitalize(profileName)}")
}
