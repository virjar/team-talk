plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
}

val protocolMajor = rootProject.extra["protocolMajor"] as Int
val protocolMinor = rootProject.extra["protocolMinor"] as Int
val minimumProtocolMinor = rootProject.extra["minimumProtocolMinor"] as Int
val protocolVersionSources = layout.buildDirectory.dir("generated/protocolVersions/kotlin")
val generateProtocolVersions by tasks.registering {
    inputs.property("major", protocolMajor)
    inputs.property("minor", protocolMinor)
    inputs.property("minimumMinor", minimumProtocolMinor)
    outputs.dir(protocolVersionSources)
    doLast {
        val source = protocolVersionSources.get().file("com/virjar/tk/protocol/ProtocolVersions.kt").asFile
        source.parentFile.mkdirs()
        source.writeText("""
            package com.virjar.tk.protocol

            /** Generated from the root protocol release policy; do not edit. */
            object ProtocolVersions {
                const val MAJOR: Int = $protocolMajor
                const val MINOR: Int = $protocolMinor
                const val MINIMUM_MINOR: Int = $minimumProtocolMinor
                const val CURRENT_ID: Int = ${(protocolMajor shl 16) or protocolMinor}
                val CURRENT = ProtocolVersion(MAJOR, MINOR)
                val SUPPORTED = ProtocolRange(MAJOR, MINIMUM_MINOR, MINOR)
            }
        """.trimIndent() + "\n")
    }
}

val recordingProtocolBaseline = gradle.startParameter.taskNames.any { it.substringAfterLast(':') == "writeProtocolBaseline" }
val frozenProtocolRelease = release.ProtocolReleasePolicy.latest(rootDir)
ksp {
    arg("teamtalk.protocolMajor", protocolMajor.toString())
    arg("teamtalk.protocolMinor", protocolMinor.toString())
    arg("teamtalk.minimumProtocolMinor", minimumProtocolMinor.toString())
    arg("teamtalk.protocolBaseline", layout.projectDirectory.file("wire-baseline.tsv").asFile.absolutePath)
    arg("teamtalk.publishedProtocolBaseline", frozenProtocolRelease.wireBaseline.absolutePath)
    arg("teamtalk.recordProtocolBaseline", recordingProtocolBaseline.toString())
}

/** 显式登记开发 schema；仍由独立的发行快照保护已经冻结的线上契约。 */
tasks.register("writeProtocolBaseline") {
    group = "verification"
    description = "Record the current wire schema for explicit review"
    dependsOn("kspCommonMainKotlinMetadata")
    doLast {
        val generated = fileTree(layout.buildDirectory.dir("generated/ksp/metadata/commonMain")) {
            include("**/wire-schema.tsv")
        }.files.single()
        generated.copyTo(layout.projectDirectory.file("wire-baseline.tsv").asFile, overwrite = true)
    }
}

tasks.register("prepareProtocolRelease") {
    group = "release preparation"
    description = "Freeze the reviewed wire schema for the root release version; commit before publishing"
    dependsOn("verifyProtocolBaseline")
    doLast {
        release.ProtocolReleasePolicy.prepare(
            rootDir,
            rootProject.extra["releaseVersion"] as String,
            rootProject.extra["releaseBuildNumber"] as Int,
            protocolMajor,
            protocolMinor,
            minimumProtocolMinor,
        )
    }
}

tasks.register("verifyProtocolBaseline") {
    group = "verification"
    description = "Check wire IDs, signatures and lifecycle against the committed major baseline"
    dependsOn("kspCommonMainKotlinMetadata")
}

/**
 * TeamTalk 跨端契约模块。
 *
 * 这里只允许出现 wire、消息体、传输模型、RPC IDL 和两端必须一致的纯规则。
 * 客户端连接、缓存、Repository、平台实现和服务端基础设施都不得进入本模块。
 */
kotlin {
    jvm()
    androidTarget()

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
            kotlin.srcDir(protocolVersionSources)
        }
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.virjar.tk.protocol"
    compileSdk = 36
    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    add("kspCommonMainMetadata", project(":protocol:rpc-processor"))
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn("kspCommonMainKotlinMetadata")
}
// KSP 2 uses its own task type. Platform processors also read the generated common sources.
tasks.withType<com.google.devtools.ksp.gradle.KspAATask>().configureEach {
    if (name != "kspCommonMainKotlinMetadata") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}
// Metadata-only consumers must verify and generate the same common contract as JVM/Android builds.
tasks.matching { it.name == "compileCommonMainKotlinMetadata" }.configureEach {
    dependsOn("kspCommonMainKotlinMetadata")
}

tasks.matching { it.name == "kspCommonMainKotlinMetadata" }.configureEach {
    dependsOn(generateProtocolVersions)
    inputs.files(provider { listOfNotNull(layout.projectDirectory.file("wire-baseline.tsv").asFile.takeIf { it.isFile }) })
        .withPropertyName("committedWireBaseline")
    inputs.file(frozenProtocolRelease.wireBaseline).withPropertyName("publishedWireBaseline")
}
tasks.named("check") { dependsOn("verifyProtocolBaseline") }
