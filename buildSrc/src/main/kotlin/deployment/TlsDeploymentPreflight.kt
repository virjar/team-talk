package deployment

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Properties
import org.gradle.api.GradleException

internal data class TlsPemFiles(
    val certificate: File,
    val privateKey: File,
)

/**
 * 校验所有无需接触部署目标即可检查的 TLS 属性。
 * 它有意在区分全新安装与升级的只读探测之前调用。
 */
internal fun validateLocalTlsPemFiles(
    rootDir: File,
    sslCert: String?,
    sslKey: String?,
): TlsPemFiles? {
    if ((sslCert == null) != (sslKey == null)) {
        throw GradleException("-PsslCert and -PsslKey must be provided together")
    }
    if (sslCert == null || sslKey == null) return null
    if (sslCert.isBlank() || sslKey.isBlank()) {
        throw GradleException("-PsslCert and -PsslKey must both name regular files")
    }

    fun resolvePath(raw: String): File = File(raw).let { path ->
        if (path.isAbsolute) path else File(rootDir, raw)
    }
    val certificate = resolvePath(sslCert)
    val privateKey = resolvePath(sslKey)
    if (!certificate.isFile) {
        throw GradleException("SSL certificate file not found or not a regular file: $sslCert")
    }
    if (!privateKey.isFile) {
        throw GradleException("SSL key file not found or not a regular file: $sslKey")
    }
    return TlsPemFiles(certificate, privateKey)
}

/** 在只读的首次部署探测完成后，应用部署状态规则。 */
internal fun requireTlsPemFilesForDeployment(
    sslEnabled: Boolean,
    isFirstDeploy: Boolean,
    pemFiles: TlsPemFiles?,
) {
    if (!sslEnabled && pemFiles != null) {
        throw GradleException("-PsslCert and -PsslKey are only valid when serverUrl uses HTTPS")
    }
    if (sslEnabled && isFirstDeploy && pemFiles == null) {
        throw GradleException(
            "First HTTPS deployment requires both -PsslCert=<certificate.pem> and " +
                "-PsslKey=<private-key.pem>"
        )
    }
}

internal fun retainedTlsKeystoreCheckCommand(
    deployPath: String,
    keytoolExecutable: String = "keytool",
): String {
    requireCanonicalDeployPath(deployPath)
    require(keytoolExecutable.isNotBlank()) { "keytool executable cannot be blank" }
    val keystore = "$deployPath/conf/ssl/teamtalk.p12"
    // `-certreq` 对密钥库是只读的，但必须加载并使用别名的私钥；
    // 而 `-list` 只能证明 PKCS12 元数据可以被打开。
    return "test -s $keystore && test -r $keystore && " +
        "${posixShellQuote(keytoolExecutable)} " +
        "-certreq -storetype PKCS12 -keystore $keystore -alias mykey " +
        "-storepass:file /dev/stdin -file /dev/null 2>/dev/null"
}

/**
 * 只有当远程仍保留着可用的密钥库时，HTTPS 升级才允许省略新的 PEM 密钥对。
 * 该检查是只读的，并且在服务停止或文件被覆盖之前执行。
 */
internal fun preflightRetainedTlsKeystore(
    host: String,
    user: String,
    port: Int,
    deployPath: String,
) {
    val secrets = readRequiredUpgradeSecretsFromRemote(host, user, port, deployPath)
    val password = requireCompatibleTlsPasswords(secrets)
    require(password.none { it == '\u0000' || it == '\n' || it == '\r' }) {
        "TLS keystore password cannot contain line separators"
    }
    val standardInput = "$password\n".toByteArray(StandardCharsets.UTF_8)
    try {
        remoteSensitiveStdinChecked(
            label = "open retained TLS keystore and private-key entry",
            host = host,
            user = user,
            command = retainedTlsKeystoreCheckCommand(deployPath),
            standardInput = standardInput,
            port = port,
        )
    } catch (failure: ExternalProcessException) {
        throw GradleException(
            "HTTPS upgrade cannot open the retained $deployPath/conf/ssl/teamtalk.p12 " +
                "and its private-key entry with the authoritative deployment password. " +
                "Provide both -PsslCert and -PsslKey.",
            failure,
        )
    } finally {
        standardInput.fill(0)
    }
}

internal fun requireCompatibleTlsPasswords(secrets: Properties): String {
    val keystorePassword = secrets.getProperty("SSL_KEYSTORE_PASSWORD")
        ?.takeIf { it.isNotBlank() && it != "null" }
        ?: throw GradleException("SSL_KEYSTORE_PASSWORD is required for HTTPS deployment")
    val privateKeyPassword = secrets.getProperty("SSL_PRIVATE_KEY_PASSWORD")
        ?.takeIf { it.isNotBlank() && it != "null" }
        ?: throw GradleException("SSL_PRIVATE_KEY_PASSWORD is required for HTTPS deployment")
    if (keystorePassword != privateKeyPassword) {
        throw GradleException(
            "SSL_KEYSTORE_PASSWORD and SSL_PRIVATE_KEY_PASSWORD must be identical for the " +
                "generated PKCS12 keystore; deployment stopped before changing the remote host"
        )
    }
    return keystorePassword
}

private fun verifyPreparedTlsKeystore(file: File, password: String) {
    try {
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            file.inputStream().use { load(it, password.toCharArray()) }
        }
        val aliases = keyStore.aliases()
        var keyAlias: String? = null
        while (aliases.hasMoreElements() && keyAlias == null) {
            val candidate = aliases.nextElement()
            if (keyStore.isKeyEntry(candidate)) keyAlias = candidate
        }
        val alias = keyAlias
            ?: throw GradleException("Prepared PKCS12 keystore contains no private key")
        if (keyStore.getKey(alias, password.toCharArray()) == null) {
            throw GradleException("Prepared PKCS12 private key cannot be loaded")
        }
    } catch (failure: Exception) {
        if (failure is GradleException) throw failure
        throw GradleException("Prepared PKCS12 keystore cannot be loaded", failure)
    }
}

/**
 * 在本地转换并校验提供的 PEM 密钥对。返回的临时密钥库必须由调用方
 * 在上传完成后（或部署失败后）删除。
 */
internal fun prepareTlsKeystore(
    pemFiles: TlsPemFiles,
    secrets: Properties,
    createTemporaryFile: () -> File = { createOwnerOnlyTempFile("teamtalk-ssl-", ".p12") },
    runCommand: (List<String>, Map<String, String>) -> Int = ::runTlsPreparationCommand,
    verifyKeystore: (File, String) -> Unit = ::verifyPreparedTlsKeystore,
): File {
    val password = requireCompatibleTlsPasswords(secrets)
    val output = createTemporaryFile()
    output.deleteOnExit()
    val command = listOf(
        "openssl", "pkcs12", "-export",
        "-in", pemFiles.certificate.absolutePath,
        "-inkey", pemFiles.privateKey.absolutePath,
        "-passin", "pass:",
        "-out", output.absolutePath,
        "-name", "mykey",
        "-passout", "env:TEAMTALK_SSL_KEYSTORE_PASSWORD",
    )

    try {
        val exitCode = runCommand(
            command,
            mapOf("TEAMTALK_SSL_KEYSTORE_PASSWORD" to password),
        )
        if (exitCode != 0 || !output.isFile || output.length() == 0L) {
            throw GradleException("Failed to convert TLS PEM files to PKCS12")
        }
        verifyKeystore(output, password)
        return output
    } catch (failure: Exception) {
        output.delete()
        if (failure is GradleException) throw failure
        throw GradleException("Failed to convert TLS PEM files to PKCS12", failure)
    }
}

private fun runTlsPreparationCommand(
    command: List<String>,
    environmentVariables: Map<String, String>,
): Int = localChecked(
    "convert and validate TLS PEM material",
    command,
    timeoutMillis = 120_000L,
    outputMode = ProcessOutputMode.DISCARD,
    environment = environmentVariables,
).exitCode
