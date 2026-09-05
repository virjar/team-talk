package release.publish

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Target receipt; the caller persists this next to the sealed local release bundle. */
data class PublicationResult(
    val target: String,
    val version: String,
    val alreadyPublished: Boolean,
)

internal fun sha256(file: File): String = file.inputStream().use(::sha256)

internal fun sha256(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(128 * 1024)
    while (true) {
        val size = input.read(buffer)
        if (size < 0) break
        digest.update(buffer, 0, size)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun requireVersion(version: String) {
    require(version.matches(Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)"))) {
        "Release version must be the numeric version from root gradle.properties"
    }
}

internal fun requireAsset(file: File) {
    require(file.isFile && file.length() > 0 && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
        "Release asset must be a non-empty regular file: $file"
    }
    require(file.name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) {
        "Release asset name must contain only letters, numbers, dots, underscores and hyphens: ${file.name}"
    }
}
