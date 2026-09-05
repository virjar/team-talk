package deployment

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Properties
import org.gradle.api.GradleException

const val RELEASE_ARTIFACT_MANIFEST_FILE = "teamtalk-release.properties"

data class ReleaseArtifactIdentity(
    val artifactType: String,
    val version: String,
    val buildIdentity: String,
    val serverProtocol: ServerProtocolWindow? = null,
)

/** The target artifact's window, including staged builds produced outside this checkout. */
data class ServerProtocolWindow(val major: Int, val minimumMinor: Int, val currentMinor: Int) {
    init {
        require(major in 0..32767 && minimumMinor in 0..65535 && currentMinor in minimumMinor..65535) {
            "Server artifact protocol window is invalid"
        }
    }
}

/**
 * 为已构建完成的产物目录加盖印记。清单有意在生产者完成后才写入，
 * 因此 CI 部署消费到的正是构建任务上传的那份字节内容。
 */
fun writeReleaseArtifactManifest(
    artifactDirectory: File,
    artifactType: String,
    version: String,
    buildIdentity: String,
    serverProtocol: ServerProtocolWindow? = null,
) {
    requireManifestValue("artifactType", artifactType)
    requireManifestValue("version", version)
    requireManifestValue("buildIdentity", buildIdentity)
    requireArtifactPayload(artifactDirectory)
    writeReleaseArtifactManifestFile(
        File(artifactDirectory, RELEASE_ARTIFACT_MANIFEST_FILE),
        artifactType,
        version,
        buildIdentity,
        serverProtocol,
    )
}

/** 在负载目录尚不存在时，先生成清单作为分发包 CopySpec 的输入。 */
fun writeReleaseArtifactManifestFile(
    manifestFile: File,
    artifactType: String,
    version: String,
    buildIdentity: String,
    serverProtocol: ServerProtocolWindow? = null,
) {
    requireManifestValue("artifactType", artifactType)
    requireManifestValue("version", version)
    requireManifestValue("buildIdentity", buildIdentity)
    manifestFile.parentFile.mkdirs()
    manifestFile.writeText(
        buildString {
            appendLine("artifactType=$artifactType")
            appendLine("version=$version")
            appendLine("buildIdentity=$buildIdentity")
            serverProtocol?.let {
                appendLine("protocolMajor=${it.major}")
                appendLine("minimumProtocolMinor=${it.minimumMinor}")
                appendLine("protocolMinor=${it.currentMinor}")
            }
        },
    )
}

/** 当暂存目录缺失、为空、类型错误或来自其他构建时，失败即停。 */
fun requireReleaseArtifact(
    artifactDirectory: File,
    expectedArtifactType: String,
    expectedVersion: String,
    expectedBuildIdentity: String,
): ReleaseArtifactIdentity {
    requireManifestValue("expectedArtifactType", expectedArtifactType)
    requireManifestValue("expectedVersion", expectedVersion)
    requireManifestValue("expectedBuildIdentity", expectedBuildIdentity)
    requireArtifactPayload(artifactDirectory)

    val manifest = File(artifactDirectory, RELEASE_ARTIFACT_MANIFEST_FILE)
    if (!Files.isRegularFile(manifest.toPath(), LinkOption.NOFOLLOW_LINKS) || manifest.length() <= 0L) {
        throw GradleException("Release artifact manifest is missing or empty: $manifest")
    }
    val properties = try {
        Properties().apply { manifest.reader(Charsets.UTF_8).use { reader -> load(reader) } }
    } catch (failure: Exception) {
        throw GradleException("Release artifact manifest is unreadable: $manifest", failure)
    }
    val actual = ReleaseArtifactIdentity(
        artifactType = requireManifestProperty(properties, "artifactType", manifest),
        version = requireManifestProperty(properties, "version", manifest),
        buildIdentity = requireManifestProperty(properties, "buildIdentity", manifest),
        serverProtocol = if (expectedArtifactType == "server-distribution") {
            requireServerProtocolWindow(properties)
        } else null,
    )
    if (actual.artifactType != expectedArtifactType) {
        throw GradleException(
            "Release artifact type mismatch: expected $expectedArtifactType, found ${actual.artifactType}",
        )
    }
    if (actual.version != expectedVersion) {
        throw GradleException(
            "Release artifact version mismatch: expected $expectedVersion, found ${actual.version}",
        )
    }
    if (actual.buildIdentity != expectedBuildIdentity) {
        throw GradleException(
            "Release artifact build identity mismatch: expected $expectedBuildIdentity, " +
                "found ${actual.buildIdentity}",
        )
    }
    return actual
}

private fun requireServerProtocolWindow(properties: Properties): ServerProtocolWindow = try {
    fun number(key: String): Int {
        val value = properties.getProperty(key)
        require(value != null && value.matches(Regex("0|[1-9][0-9]{0,4}"))) {
            "Server artifact $key is missing or malformed"
        }
        return value.toInt()
    }
    ServerProtocolWindow(number("protocolMajor"), number("minimumProtocolMinor"), number("protocolMinor"))
} catch (failure: IllegalArgumentException) {
    throw GradleException(
        "Server artifact protocol window is missing or invalid; rebuild the server distribution before deployment",
        failure,
    )
}

private fun requireArtifactPayload(artifactDirectory: File) {
    if (!artifactDirectory.isDirectory) {
        throw GradleException("Release artifact directory is missing: $artifactDirectory")
    }
    val hasPayload = artifactDirectory.walkTopDown().any { file ->
        file.isFile && file.name != RELEASE_ARTIFACT_MANIFEST_FILE && file.length() > 0L
    }
    if (!hasPayload) {
        throw GradleException("Release artifact directory has no non-empty payload: $artifactDirectory")
    }
}

private fun requireManifestProperty(properties: Properties, key: String, manifest: File): String {
    val value = properties.getProperty(key)?.trim().orEmpty()
    try {
        requireManifestValue(key, value)
    } catch (failure: GradleException) {
        throw GradleException("${failure.message}: $manifest", failure)
    }
    return value
}

private fun requireManifestValue(name: String, value: String) {
    if (value.isBlank() || value.any { it == '\n' || it == '\r' || it == '=' }) {
        throw GradleException("Release artifact $name is missing or invalid")
    }
}
