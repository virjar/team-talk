package deployment

/** Secret 管理：加载、生成、原子保存、从远程提取敏感配置。 */

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import org.gradle.api.GradleException

internal val requiredDeploymentSecretKeys = listOf(
    "DATABASE_PASSWORD",
    "SSL_KEYSTORE_PASSWORD",
    "SSL_PRIVATE_KEY_PASSWORD",
    "ADMIN_USER",
    "ADMIN_PASSWORD",
)
private val ownerOnlyPermissions = setOf(OWNER_READ, OWNER_WRITE)
private val requiredSecretKeyAlternation = requiredDeploymentSecretKeys
    .joinToString("|") { Regex.escape(it) }
private val secretAssignment = Regex(
    """^($requiredSecretKeyAlternation)=(.*)$""",
)
private val secretReference = Regex(
    """(?<![A-Za-z0-9_])(?:$requiredSecretKeyAlternation)(?![A-Za-z0-9_])""",
)

/**
 * 在升级期间严格读取当前部署 env 中的五个必需 Secret。
 * 不会为缺失或格式错误的值生成替代值：远程状态是升级的唯一权威来源。
 */
fun loadRequiredUpgradeSecretsFromRemote(
    secretsFile: File,
    host: String,
    user: String,
    port: Int,
    deployPath: String,
): Properties {
    val secrets = readRequiredUpgradeSecretsFromRemote(host, user, port, deployPath)
    saveSecrets(secretsFile, secrets)
    println("  Verified required upgrade secrets and saved them to ${secretsFile.name}")
    return secrets
}

/** 读取并校验远程权威数据，而不在本地持久化第二份副本。 */
internal fun readRequiredUpgradeSecretsFromRemote(
    host: String,
    user: String,
    port: Int,
    deployPath: String,
): Properties {
    requireCanonicalDeployPath(deployPath)
    val envContent = remoteSensitiveCaptureProbe(
        "read current deployment env",
        host,
        user,
        "cat $deployPath/conf/env.sh 2>/dev/null",
        port,
    ) ?: throw GradleException(
        "Cannot read required deployment secrets from remote conf/env.sh",
    )
    val secrets = try {
        parseRequiredUpgradeSecrets(envContent)
    } catch (failure: IllegalArgumentException) {
        throw GradleException(
            "Cannot safely read required deployment secrets from remote conf/env.sh",
            failure,
        )
    }
    return secrets
}

/**
 * 每个必需 key 只接受一个规范赋值。任何引用必需 key 但不符合精确生成格式的
 * 非注释行都会被拒绝，而不是忽略。
 */
internal fun parseRequiredUpgradeSecrets(content: String): Properties {
    val parsedValues = linkedMapOf<String, String>()
    content.lineSequence().forEachIndexed { index, line ->
        if (line.isBlank() || line.trimStart().startsWith('#')) return@forEachIndexed
        val match = secretAssignment.matchEntire(line)
        if (match == null) {
            require(!secretReference.containsMatchIn(line)) {
                "Malformed required deployment secret assignment at line ${index + 1}"
            }
            return@forEachIndexed
        }

        val key = match.groupValues[1]
        require(key !in parsedValues) { "Duplicate required deployment secret assignment for $key" }
        val value = try {
            decodeGeneratedShellAssignmentValue(match.groupValues[2])
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException(
                "Malformed required deployment secret assignment for $key",
                failure,
            )
        }
        require(value.isNotBlank() && value != "null") {
            "Required deployment secret $key must not be empty"
        }
        parsedValues[key] = value
    }

    val missing = requiredDeploymentSecretKeys.filterNot(parsedValues::containsKey)
    require(missing.isEmpty()) {
        "Missing required deployment secret assignments: ${missing.joinToString()}"
    }
    return Properties().apply {
        parsedValues.forEach { (key, value) -> setProperty(key, value) }
    }
}

/** 精确解码由 [posixShellQuote] 生成的 POSIX 单引号形式。 */
private fun decodeGeneratedShellAssignmentValue(encoded: String): String {
    require(encoded.length >= 2 && encoded.first() == '\'' && encoded.last() == '\'') {
        "Deployment env secret must use canonical single quoting"
    }
    val decoded = StringBuilder()
    var index = 1
    while (index < encoded.lastIndex) {
        if (encoded[index] == '\'') {
            require(encoded.startsWith("'\\''", index)) {
                "Deployment env secret contains non-canonical shell syntax"
            }
            decoded.append('\'')
            index += 4
        } else {
            decoded.append(encoded[index])
            index++
        }
    }
    return decoded.toString().also { value ->
        require(posixShellQuote(value) == encoded) {
            "Deployment env secret is not canonically encoded"
        }
    }
}

/** 补齐所有缺失的首次部署 Secret；升级路径永远不会调用该生成器。 */
internal fun ensureFirstDeploymentSecretsComplete(secrets: Properties) {
    fun missing(key: String): Boolean = secrets.getProperty(key).let {
        it.isNullOrBlank() || it == "null"
    }
    if (missing("ADMIN_USER")) secrets.setProperty("ADMIN_USER", "admin")
    if (missing("ADMIN_PASSWORD")) {
        secrets.setProperty("ADMIN_PASSWORD", genPassword())
        println("  Generated ADMIN_PASSWORD (new)")
    }
    if (missing("DATABASE_PASSWORD")) secrets.setProperty("DATABASE_PASSWORD", genPassword())
    val keystorePasswordMissing = missing("SSL_KEYSTORE_PASSWORD")
    val privateKeyPasswordMissing = missing("SSL_PRIVATE_KEY_PASSWORD")
    when {
        keystorePasswordMissing && privateKeyPasswordMissing -> {
            val password = genPassword()
            secrets.setProperty("SSL_KEYSTORE_PASSWORD", password)
            secrets.setProperty("SSL_PRIVATE_KEY_PASSWORD", password)
        }
        keystorePasswordMissing -> secrets.setProperty(
            "SSL_KEYSTORE_PASSWORD",
            secrets.getProperty("SSL_PRIVATE_KEY_PASSWORD"),
        )
        privateKeyPasswordMissing -> secrets.setProperty(
            "SSL_PRIVATE_KEY_PASSWORD",
            secrets.getProperty("SSL_KEYSTORE_PASSWORD"),
        )
    }
}

/** 加载或生成首次部署 Secret，并立即持久化完整集合。 */
fun loadOrGenerateFirstDeploymentSecrets(secretsFile: File): Properties {
    val secrets = Properties()
    if (Files.exists(secretsFile.toPath(), NOFOLLOW_LINKS)) {
        loadSecretsNoFollow(secretsFile.toPath(), secrets)
        println("  Loaded secrets from ${secretsFile.name}")
    }

    ensureFirstDeploymentSecretsComplete(secrets)
    // 始终通过安全的原子路径替换，使新生成的字段与精确的 owner-only
    // 权限一起发布。
    saveSecrets(secretsFile, secrets)
    println("  Secrets secured in ${secretsFile.name}")
    return secrets
}

private fun loadSecretsNoFollow(path: Path, destination: Properties) {
    if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, NOFOLLOW_LINKS)) {
        throw GradleException("Deployment secrets must be a regular non-symbolic-link file: $path")
    }
    try {
        Files.newInputStream(path, READ, NOFOLLOW_LINKS).bufferedReader(StandardCharsets.UTF_8).use {
            destination.load(it)
        }
    } catch (failure: Exception) {
        if (failure is GradleException) throw failure
        throw GradleException("Cannot safely read deployment secrets: $path", failure)
    }
}

private fun propertyValue(value: String): String = buildString {
    value.forEachIndexed { index, character ->
        when (character) {
            '\\' -> append("\\\\")
            '\t' -> append("\\t")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\u000C' -> append("\\f")
            '=', ':', '#', '!' -> append('\\').append(character)
            ' ' -> if (index == 0) append("\\ ") else append(character)
            else -> append(character)
        }
    }
}

private fun secretFileContent(secrets: Properties): String {
    val values = requiredDeploymentSecretKeys.associateWith { key ->
        secrets.getProperty(key)?.takeIf { it.isNotBlank() && it != "null" }
            ?: throw GradleException("$key is missing; refusing to persist incomplete deployment secrets")
    }
    return buildString {
        append("# TeamTalk deployment secrets\n")
        append("# 此文件包含敏感信息，已加入 .gitignore\n\n")
        append("# Database\n")
        append("DATABASE_PASSWORD=").append(propertyValue(values.getValue("DATABASE_PASSWORD"))).append("\n\n")
        append("# Administration\n")
        append("ADMIN_USER=").append(propertyValue(values.getValue("ADMIN_USER"))).append('\n')
        append("ADMIN_PASSWORD=").append(propertyValue(values.getValue("ADMIN_PASSWORD"))).append("\n\n")
        append("# SSL\n")
        append("SSL_KEYSTORE_PASSWORD=").append(propertyValue(values.getValue("SSL_KEYSTORE_PASSWORD"))).append('\n')
        append("SSL_PRIVATE_KEY_PASSWORD=").append(propertyValue(values.getValue("SSL_PRIVATE_KEY_PASSWORD"))).append('\n')
    }
}

internal fun setOwnerOnly(path: Path) {
    try {
        Files.setPosixFilePermissions(path, ownerOnlyPermissions)
    } catch (_: UnsupportedOperationException) {
        val file = path.toFile()
        val secured = file.setReadable(false, false) &&
            file.setWritable(false, false) &&
            file.setExecutable(false, false) &&
            file.setReadable(true, true) &&
            file.setWritable(true, true)
        if (!secured) throw GradleException("Cannot apply owner-only permissions to $path")
    }
}

internal fun createOwnerOnlyTempFile(prefix: String, suffix: String): File {
    val file = try {
        Files.createTempFile(
            prefix,
            suffix,
            PosixFilePermissions.asFileAttribute(ownerOnlyPermissions),
        )
    } catch (_: UnsupportedOperationException) {
        Files.createTempFile(prefix, suffix).also(::setOwnerOnly)
    }
    setOwnerOnly(file)
    return file.toFile()
}

/** 通过同目录、owner-only、禁止跟随符号链接的原子替换保存所有生效凭据。 */
fun saveSecrets(secretsFile: File, secrets: Properties) {
    val target = secretsFile.toPath().toAbsolutePath().normalize()
    val parent = target.parent
        ?: throw GradleException("Deployment secrets path has no parent: $target")
    try {
        Files.createDirectories(parent)
        if (Files.isSymbolicLink(parent) || !Files.isDirectory(parent, NOFOLLOW_LINKS)) {
            throw GradleException("Deployment secrets parent must be a real directory: $parent")
        }
        if (Files.exists(target, NOFOLLOW_LINKS) &&
            (Files.isSymbolicLink(target) || !Files.isRegularFile(target, NOFOLLOW_LINKS))
        ) {
            throw GradleException("Refusing to replace non-regular deployment secrets path: $target")
        }

        val temporary = try {
            try {
                Files.createTempFile(
                    parent,
                    ".${target.fileName}.",
                    ".tmp",
                    PosixFilePermissions.asFileAttribute(ownerOnlyPermissions),
                )
            } catch (_: UnsupportedOperationException) {
                Files.createTempFile(parent, ".${target.fileName}.", ".tmp").also(::setOwnerOnly)
            }
        } catch (failure: Exception) {
            throw GradleException("Cannot create secure deployment secrets temporary file", failure)
        }
        try {
            setOwnerOnly(temporary)
            val bytes = secretFileContent(secrets).toByteArray(StandardCharsets.UTF_8)
            FileChannel.open(temporary, WRITE, TRUNCATE_EXISTING, NOFOLLOW_LINKS).use { channel ->
                var remaining = ByteBuffer.wrap(bytes)
                while (remaining.hasRemaining()) channel.write(remaining)
                channel.force(true)
            }
            try {
                Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (failure: AtomicMoveNotSupportedException) {
                throw GradleException(
                    "Atomic replacement is not supported for deployment secrets at $parent",
                    failure,
                )
            }
            setOwnerOnly(target)
        } finally {
            Files.deleteIfExists(temporary)
        }
    } catch (failure: Exception) {
        if (failure is GradleException) throw failure
        throw GradleException("Cannot securely save deployment secrets: $target", failure)
    }
}
