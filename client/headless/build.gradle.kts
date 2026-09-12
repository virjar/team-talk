plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * tt-agent / CLI / MCP 无头客户端模块（bot 运行时 + 运维面）。
 *
 * 从 :client:shared 拆出：ImBot 链（认证准入、事件缓冲、消息收件箱、outgoing 协调）、
 * AgentMain/CLI/MCP 入口、systemd 安装与便携分发包。仅依赖 shared 公开 SDK API；
 * 图形端（app/android/desktop）不再随包携带本模块。可移植 tt-agent 目录由 headlessDist
 * 从本模块 jar 与 runtimeClasspath 组装，布局与既有发行一致。
 */
val sdkReleaseVersion = rootProject.extra.get("releaseVersion") as String
val sdkBuildIdentity = rootProject.extra.get("buildIdentity") as String
val sdkReleaseBuildNumber = rootProject.extra.get("releaseBuildNumber") as Int

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    api(project(":client:shared"))
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(project(":client:shared-testkit"))
    testImplementation(libs.sqldelight.sqlite.driver)
    testImplementation(libs.jna.platform)
}

// ── tt-agent / CLI / MCP 分发包 ──
tasks.named<org.gradle.jvm.tasks.Jar>("jar") {
    manifest { attributes["Main-Class"] = "com.virjar.tk.headless.agent.AgentMainKt" }
}
val headlessVersion = release.ReleaseVersion.read(rootDir)
val headlessDirectory = layout.buildDirectory.dir("headless")
val headlessDist by tasks.registering(org.gradle.api.tasks.Sync::class) {
    group = "distribution"
    description = "Build the portable Headless SDK directory, launchers, identity and SHA256SUMS (requires JDK 21)"
    inputs.property("releaseVersion", sdkReleaseVersion)
    inputs.property("buildIdentity", sdkBuildIdentity)
    inputs.property("releaseBuildNumber", sdkReleaseBuildNumber)
    inputs.property("protocolMajor", headlessVersion.protocolMajor)
    inputs.property("protocolMinor", headlessVersion.protocolMinor)
    duplicatesStrategy = DuplicatesStrategy.FAIL
    into(headlessDirectory)
    from(rootProject.file("LICENSE"))
    into("lib") {
        from(tasks.named<org.gradle.jvm.tasks.Jar>("jar"))
        from(configurations.runtimeClasspath)
    }
    doLast {
        release.HeadlessDistribution.seal(headlessDirectory.get().asFile, headlessVersion, sdkBuildIdentity)
    }
}
tasks.register("verifyHeadlessDist") {
    group = "verification"
    description = "Verify the current Headless directory's exact identity, dependency payload and file checksums"
    dependsOn(headlessDist)
    doLast {
        release.HeadlessDistribution.verify(headlessDirectory.get().asFile, headlessVersion, sdkBuildIdentity)
    }
}
tasks.register("headlessDistZip") {
    group = "distribution"
    description = "Archive the verified portable Headless distribution for installation outside the source checkout"
    dependsOn(headlessDist)
}
