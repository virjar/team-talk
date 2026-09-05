package com.virjar.tk.server.infra.health

import java.io.InputStream
import java.util.Properties

internal data class RuntimeBuildIdentity(
    val version: String,
    val buildIdentity: String,
)

internal object ServerBuildIdentity {
    val current: RuntimeBuildIdentity by lazy {
        val stream = ServerBuildIdentity::class.java.classLoader
            .getResourceAsStream(BUILD_IDENTITY_RESOURCE)
            ?: error("Server build identity resource is missing: $BUILD_IDENTITY_RESOURCE")
        stream.use(::readRuntimeBuildIdentity)
    }
}

internal fun readRuntimeBuildIdentity(input: InputStream): RuntimeBuildIdentity {
    val properties = Properties().apply { input.reader(Charsets.UTF_8).use { reader -> load(reader) } }
    val version = properties.getProperty("version")?.trim().orEmpty()
    val buildIdentity = properties.getProperty("buildIdentity")?.trim().orEmpty()
    require(version.isNotEmpty()) { "Server build version is missing" }
    require(buildIdentity.isNotEmpty()) { "Server build identity is missing" }
    return RuntimeBuildIdentity(version, buildIdentity)
}

private const val BUILD_IDENTITY_RESOURCE = "teamtalk-build.properties"
