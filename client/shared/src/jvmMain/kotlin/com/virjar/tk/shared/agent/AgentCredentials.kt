package com.virjar.tk.shared.agent

import com.virjar.tk.protocol.model.AuthRules
import com.virjar.tk.shared.client.DeploymentIdentity
import com.virjar.tk.protocol.payload.AuthPayloadPolicy
import java.io.File
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import java.util.UUID

enum class AgentCredentialState {
    REGISTER_PENDING,
    ACTIVE,
}

/** 完整的磁盘状态。只有 REGISTER_PENDING 可以包含 [password]。 */
class AgentCredentialRecord(
    val username: String?,
    val password: String?,
    val uid: String?,
    val refreshToken: String?,
    val apiToken: String,
    val deviceId: String,
    val state: AgentCredentialState?,
    val deploymentFingerprint: String,
) {
    override fun toString(): String =
        "AgentCredentialRecord(username=$username, uid=$uid, " +
            "password=${if (password == null) "<absent>" else "<redacted>"}, " +
            "refreshToken=${if (refreshToken == null) "<absent>" else "<redacted>"}, " +
            "apiToken=<redacted>, deviceId=$deviceId, state=$state, " +
            "deploymentFingerprint=$deploymentFingerprint)"
}

/** ACTIVE agent 每次重启使用的无密码材料。 */
internal data class AgentActiveRefresh(
    val uid: String,
    val username: String,
    val refreshToken: String,
    val deviceId: String,
) {
    override fun toString(): String =
        "AgentActiveRefresh(uid=$uid, username=$username, refreshToken=<redacted>, deviceId=$deviceId)"
}

internal fun AgentCredentialRecord.requireActiveRefresh(): AgentActiveRefresh {
    require(state == AgentCredentialState.ACTIVE && password == null) {
        "Agent credentials are not ACTIVE refresh credentials"
    }
    return AgentActiveRefresh(
        uid = requireNotNull(uid),
        username = requireNotNull(username),
        refreshToken = requireNotNull(refreshToken),
        deviceId = deviceId,
    )
}

/** 首次认证尝试之前所需的稳定、非登录身份。 */
class AgentRuntimeIdentity(
    val apiToken: String,
    val deviceId: String,
) {
    override fun toString(): String =
        "AgentRuntimeIdentity(apiToken=<redacted>, deviceId=$deviceId)"
}

/** 无哈希的本地登录引导存储，具有失败关闭的 POSIX 所有权与原子持久化。 */
object AgentCredentials {
    private const val FILE_NAME = "credentials.properties"
    private const val STATE_PROPERTY = "registrationState"
    private val filePermissions = PosixFilePermissions.fromString("rw-------")
    private val fileAttribute = PosixFilePermissions.asFileAttribute(filePermissions)
    private val secureRandom = SecureRandom()

    @Synchronized
    fun load(dataDir: File, deploymentIdentity: DeploymentIdentity): AgentCredentialRecord? {
        val directory = AgentDataDirectoryPolicy.openRuntime(dataDir)
        val credentialFile = directory.root.resolve(FILE_NAME)
        if (!Files.exists(credentialFile, LinkOption.NOFOLLOW_LINKS)) return null

        val properties = readProperties(credentialFile, directory)
        val parsed = parseAndMigrate(properties, deploymentIdentity)
        if (parsed.changed) persist(directory, properties)
        return parsed.record
    }

    /** 特权安装器读取：目录 owner 已由其服务计划验证。 */
    @Synchronized
    internal fun requireActiveForInstall(
        directory: AgentDataDirectory,
        deploymentIdentity: DeploymentIdentity,
    ): AgentCredentialRecord {
        val credentialFile = directory.root.resolve(FILE_NAME)
        require(Files.exists(credentialFile, LinkOption.NOFOLLOW_LINKS)) {
            "systemd install requires a foreground-bootstrap ACTIVE dataDir"
        }
        val parsed = parseAndMigrate(readProperties(credentialFile, directory), deploymentIdentity)
        require(!parsed.changed && parsed.record.state == AgentCredentialState.ACTIVE) {
            "systemd install requires complete ACTIVE refresh credentials"
        }
        parsed.record.requireActiveRefresh()
        return parsed.record
    }

    /**
     * 在任何网络 I/O 之前持久化精确的注册身份。已有的 ACTIVE dataDir
     * 与不同的待处理身份绝不会被覆盖。
     */
    @Synchronized
    fun beginRegistration(
        dataDir: File,
        deploymentIdentity: DeploymentIdentity,
        username: String,
        password: String,
    ): AgentCredentialRecord {
        // 无头注册刻意使用其稳定的 username 作为展示名。
        AuthRules.validateRegister(username, password, username)
        val directory = AgentDataDirectoryPolicy.openRuntime(dataDir)
        val credentialFile = directory.root.resolve(FILE_NAME)
        val properties = if (Files.exists(credentialFile, LinkOption.NOFOLLOW_LINKS)) {
            readProperties(credentialFile, directory)
        } else {
            Properties()
        }
        val parsed = parseAndMigrate(properties, deploymentIdentity)
        val current = parsed.record
        when (current.state) {
            AgentCredentialState.ACTIVE -> error("ACTIVE agent dataDir cannot start registration")
            AgentCredentialState.REGISTER_PENDING -> {
                require(current.username == username && current.password == password) {
                    "Pending registration identity cannot be replaced"
                }
                if (parsed.changed) persist(directory, properties)
                return current
            }
            null -> Unit
        }

        properties.setProperty("username", username)
        properties.setProperty("password", password)
        properties.remove("uid")
        properties.remove("refreshToken")
        properties.setProperty(STATE_PROPERTY, AgentCredentialState.REGISTER_PENDING.name)
        persist(directory, properties)
        return parseAndMigrate(properties, deploymentIdentity).record
    }

    /**
     * 从 ImBot 的 AUTH 回调同步持久化服务器签发的 refresh 凭据。
     * 这在同步到达 ready 之前执行。待处理的明文密码在同一次
     * 原子替换中被移除，而 ACTIVE dataDir 只能轮换其精确账号的 token。
     */
    @Synchronized
    fun recordAuthentication(
        dataDir: File,
        deploymentIdentity: DeploymentIdentity,
        expectedUsername: String,
        expectedDeviceId: String,
        uid: String,
        authenticatedUsername: String,
        refreshToken: String,
    ): AgentCredentialRecord {
        require(
            uid.isNotBlank() && uid.length <= AuthPayloadPolicy.MAX_UID_LENGTH &&
                uid.none(Char::isISOControl)
        ) {
            "Invalid authenticated uid"
        }
        require(
            authenticatedUsername.isNotBlank() &&
                authenticatedUsername.length <= AuthRules.USERNAME_MAX_LENGTH &&
                authenticatedUsername.none(Char::isISOControl)
        ) {
            "Invalid authenticated username"
        }
        require(
            refreshToken.isNotBlank() &&
                refreshToken.length <= AuthPayloadPolicy.MAX_TOKEN_LENGTH &&
                refreshToken.none(Char::isISOControl)
        ) {
            "Invalid refresh credential"
        }
        val directory = AgentDataDirectoryPolicy.openRuntime(dataDir)
        val credentialFile = directory.root.resolve(FILE_NAME)
        require(Files.exists(credentialFile, LinkOption.NOFOLLOW_LINKS)) {
            "Agent runtime identity is missing"
        }
        val properties = readProperties(credentialFile, directory)
        require(
            properties.getProperty(DEPLOYMENT_FINGERPRINT_PROPERTY) == deploymentIdentity.fingerprint
        ) {
            "Agent deployment changed while authentication was in flight"
        }
        val parsed = parseAndMigrate(properties, deploymentIdentity)
        val current = parsed.record
        require(current.deviceId == expectedDeviceId) { "Authenticated device identity changed" }
        require(authenticatedUsername == expectedUsername) { "Server authenticated an unexpected username" }
        when (current.state) {
            AgentCredentialState.REGISTER_PENDING -> require(current.username == expectedUsername) {
                "Pending registration identity changed"
            }
            AgentCredentialState.ACTIVE -> {
                require(current.username == expectedUsername && current.uid == uid) {
                    "ACTIVE agent account identity changed"
                }
                if (current.refreshToken == refreshToken) {
                    if (parsed.changed) persist(directory, properties)
                    return current
                }
            }
            null -> require(
                current.username == null && current.password == null &&
                    current.uid == null && current.refreshToken == null
            ) {
                "Identity-only dataDir contains unexpected authentication material"
            }
        }
        properties.setProperty("uid", uid)
        properties.setProperty("username", authenticatedUsername)
        properties.setProperty("refreshToken", refreshToken)
        properties.remove("password")
        properties.setProperty(STATE_PROPERTY, AgentCredentialState.ACTIVE.name)
        persist(directory, properties)
        return parseAndMigrate(properties, deploymentIdentity).record
    }

    @Synchronized
    fun ensureIdentity(dataDir: File, deploymentIdentity: DeploymentIdentity): AgentRuntimeIdentity {
        val directory = AgentDataDirectoryPolicy.openRuntime(dataDir)
        val credentialFile = directory.root.resolve(FILE_NAME)
        val properties = if (Files.exists(credentialFile, LinkOption.NOFOLLOW_LINKS)) {
            readProperties(credentialFile, directory)
        } else {
            Properties()
        }
        val parsed = parseAndMigrate(properties, deploymentIdentity)
        if (parsed.changed || !Files.exists(credentialFile, LinkOption.NOFOLLOW_LINKS)) {
            persist(directory, properties)
        }
        return AgentRuntimeIdentity(parsed.record.apiToken, parsed.record.deviceId)
    }

    private fun parseAndMigrate(
        properties: Properties,
        deploymentIdentity: DeploymentIdentity,
    ): ParsedCredential {
        val unknownProperties = properties.stringPropertyNames() - ALLOWED_PROPERTIES
        require(unknownProperties.isEmpty()) { "Agent credential file contains unknown fields" }
        var changed = false
        val deploymentFingerprint = deploymentIdentity.fingerprint
        if (properties.getProperty(DEPLOYMENT_FINGERPRINT_PROPERTY) != deploymentFingerprint) {
            AUTHENTICATION_PROPERTIES.forEach { name -> properties.remove(name) }
            properties.setProperty(DEPLOYMENT_FINGERPRINT_PROPERTY, deploymentFingerprint)
            changed = true
        }
        val apiToken = properties.getProperty("apiToken")?.also {
            require(isValidApiToken(it)) { "Agent API credential is invalid" }
        } ?: run {
            changed = true
            ByteArray(32).also { secureRandom.nextBytes(it) }.let {
                Base64.getUrlEncoder().withoutPadding().encodeToString(it)
            }
        }
        val storedDeviceId = properties.getProperty("deviceId")
        val deviceId = storedDeviceId?.also {
            require(isValidDeviceId(it)) { "Agent device identity is invalid" }
        } ?: run {
            changed = true
            "agent-${UUID.randomUUID()}"
        }
        properties.setProperty("apiToken", apiToken)
        properties.setProperty("deviceId", deviceId)

        val username = optionalNonBlank(properties, "username")
        val password = optionalNonBlank(properties, "password")
        val uid = optionalNonBlank(properties, "uid")
        val refreshToken = optionalNonBlank(properties, "refreshToken")
        username?.let {
            require(AuthRules.validateUsername(it) == null && it.none(Char::isISOControl)) {
                "Stored agent username is invalid"
            }
        }
        password?.let {
            require(
                AuthRules.validatePassword(it) == null &&
                    it.length <= AuthPayloadPolicy.MAX_PASSWORD_LENGTH
            ) {
                "Stored pending password is invalid"
            }
        }
        uid?.let {
            require(it.length <= AuthPayloadPolicy.MAX_UID_LENGTH) { "Stored agent uid is invalid" }
        }
        refreshToken?.let {
            require(it.length <= AuthPayloadPolicy.MAX_TOKEN_LENGTH) {
                "Stored refresh credential is invalid"
            }
        }
        val state = properties.getProperty(STATE_PROPERTY)?.let { stored ->
            runCatching { AgentCredentialState.valueOf(stored) }.getOrElse {
                throw IllegalArgumentException("Unknown agent credential state")
            }
        }
        when (state) {
            AgentCredentialState.REGISTER_PENDING -> require(
                storedDeviceId != null &&
                    username != null && password != null && uid == null && refreshToken == null
            ) {
                "REGISTER_PENDING requires exact username/password/device identity"
            }
            AgentCredentialState.ACTIVE -> require(
                storedDeviceId != null &&
                    username != null && password == null && uid != null && refreshToken != null
            ) {
                "Legacy or plaintext ACTIVE credentials are unsupported; bootstrap a fresh dataDir"
            }
            null -> require(username == null && password == null && uid == null && refreshToken == null) {
                "Unversioned login credentials are unsupported; bootstrap a fresh dataDir"
            }
        }
        return ParsedCredential(
            AgentCredentialRecord(
                username,
                password,
                uid,
                refreshToken,
                apiToken,
                deviceId,
                state,
                deploymentFingerprint,
            ),
            changed,
        )
    }

    private fun optionalNonBlank(properties: Properties, name: String): String? {
        val value = properties.getProperty(name) ?: return null
        require(value.isNotBlank() && value.none(Char::isISOControl)) {
            "Agent credential field $name is invalid"
        }
        return value
    }

    private fun readProperties(path: Path, directory: AgentDataDirectory): Properties {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        require(!attributes.isSymbolicLink && attributes.isRegularFile) {
            "Agent credential path must be a real regular file"
        }
        require(attributes.size() in 1L..MAX_CREDENTIAL_FILE_BYTES) {
            "Agent credential file has an invalid size"
        }
        require(posixPermissions(path) == filePermissions) {
            "Existing agent credential file must already have mode 0600"
        }
        require(unixInt(path, "uid") == directory.ownerUid && unixInt(path, "gid") == directory.ownerGid) {
            "Agent credential file has the wrong owner"
        }
        require((Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt() == 1) {
            "Agent credential file cannot be hard-linked"
        }
        val options: Set<OpenOption> = setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        return Properties().also { properties ->
            Files.newByteChannel(path, options).use { channel ->
                Channels.newInputStream(channel).use { input -> properties.load(input) }
            }
        }
    }

    private fun persist(directory: AgentDataDirectory, properties: Properties) {
        val target = directory.root.resolve(FILE_NAME)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) readProperties(target, directory)
        val temporary = Files.createTempFile(directory.root, ".credentials-", ".tmp", fileAttribute)
        try {
            Files.setPosixFilePermissions(temporary, filePermissions)
            FileChannel.open(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                val output = Channels.newOutputStream(channel)
                properties.store(output, "tt-agent private credentials")
                output.flush()
                channel.force(true)
            }
            require(unixInt(temporary, "nlink") == 1) {
                "Private credential staging file cannot be hard-linked"
            }
            require(
                unixInt(temporary, "uid") == directory.ownerUid &&
                    unixInt(temporary, "gid") == directory.ownerGid
            ) {
                "Private credential staging file has the wrong owner"
            }
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (unsupported: AtomicMoveNotSupportedException) {
                throw IllegalStateException("Credential filesystem does not support atomic replace", unsupported)
            }
            forceDirectory(directory.root)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun posixPermissions(path: Path): Set<PosixFilePermission> {
        val view = Files.getFileAttributeView(
            path,
            PosixFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ) ?: error("Agent storage requires a POSIX filesystem")
        return view.readAttributes().permissions()
    }

    private fun unixInt(path: Path, attribute: String): Int =
        (Files.getAttribute(path, "unix:$attribute", LinkOption.NOFOLLOW_LINKS) as Number).toInt()

    private fun forceDirectory(directory: Path) {
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    }

    private fun isValidApiToken(value: String): Boolean =
        value.length in 32..128 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun isValidDeviceId(value: String): Boolean =
        value.length >= 8 && AuthRules.validateDeviceId(value) == null

    private data class ParsedCredential(
        val record: AgentCredentialRecord,
        val changed: Boolean,
    )

    private val ALLOWED_PROPERTIES = setOf(
        "username",
        "password",
        "uid",
        "refreshToken",
        "apiToken",
        "deviceId",
        DEPLOYMENT_FINGERPRINT_PROPERTY,
        STATE_PROPERTY,
    )
    private val AUTHENTICATION_PROPERTIES = setOf(
        "username",
        "password",
        "uid",
        "refreshToken",
        STATE_PROPERTY,
    )
    private const val DEPLOYMENT_FINGERPRINT_PROPERTY = "deploymentFingerprint"
    private const val MAX_CREDENTIAL_FILE_BYTES = 64L * 1024L
}
