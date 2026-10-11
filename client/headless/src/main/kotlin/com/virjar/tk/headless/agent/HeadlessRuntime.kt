package com.virjar.tk.headless.agent

import com.virjar.tk.protocol.ProtocolVersions
import com.virjar.tk.shared.TeamTalkBuild
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** 启动器传入其物理 bundle 根目录；源码/IDE 运行没有 bundle 租约。 */
internal object HeadlessRuntime {
    fun currentBundle(): File? = System.getProperty("teamtalk.headless.bundle")
        ?.takeIf(String::isNotBlank)?.let(::File)

    fun facts() = buildJsonObject {
        put("version", TeamTalkBuild.RELEASE_VERSION)
        put("buildIdentity", TeamTalkBuild.BUILD_IDENTITY)
        put("releaseBuildNumber", TeamTalkBuild.RELEASE_BUILD_NUMBER)
        put("protocolMajor", ProtocolVersions.MAJOR)
        put("protocolMinor", ProtocolVersions.MINOR)
        put("javaVersion", System.getProperty("java.version"))
        put("minimumJavaVersion", 21)
        val bundle = currentBundle()
        if (bundle == null) {
            put("distribution", "source")
        } else {
            val verified = HeadlessBundleInstaller.verifyBundle(bundle)
            check(verified.buildIdentity == TeamTalkBuild.BUILD_IDENTITY &&
                verified.protocolMajor == ProtocolVersions.MAJOR && verified.protocolMinor == ProtocolVersions.MINOR) {
                "Bundle manifest does not match the running client"
            }
            put("distribution", "verified")
            put("bundle", verified.directory.absolutePath)
            put("checksumSha256", verified.checksumSha256)
        }
    }
}
