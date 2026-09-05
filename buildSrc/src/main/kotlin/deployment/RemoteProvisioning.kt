package deployment

/** 远程主机配置：TLS、systemd、数据库用户与严格部署后健康检查。 */

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.GradleException

internal val requiredHealthComponents = listOf(
    "postgres",
    "rocksdb",
    "lucene",
    "sync-event-dispatcher",
    "message-projection",
    "managed-chat-projection",
    "client-telemetry",
    "file-storage",
    "tcp",
)

internal data class ValidatedHealth(
    val httpStatus: Int,
    val components: List<String>,
    val buildIdentity: String,
)

fun uploadTlsKeystore(
    host: String,
    user: String,
    port: Int,
    deployPath: String,
    preparedKeystore: File,
) {
    requireCanonicalDeployPath(deployPath)
    requireActiveRemoteDeploymentGuard(host, user, port)
    if (!preparedKeystore.isFile || preparedKeystore.length() == 0L) {
        throw GradleException("Prepared TLS keystore is missing before upload")
    }

    val remoteTemporary = "$deployPath/conf/ssl/.teamtalk-${UUID.randomUUID()}.p12.tmp"
    println("  Uploading SSL certificate ...")
    try {
        remoteChecked(
            "create remote TLS directory",
            host,
            user,
            "mkdir -p $deployPath/conf/ssl",
            port,
            outputMode = ProcessOutputMode.DISCARD,
        )
        remoteFileUploadChecked(
            label = "upload TLS keystore",
            file = preparedKeystore,
            host = host,
            user = user,
            port = port,
            remotePath = remoteTemporary,
        )
        remoteChecked(
            "publish TLS keystore atomically",
            host,
            user,
            "chmod 600 $remoteTemporary && mv -f $remoteTemporary $deployPath/conf/ssl/teamtalk.p12",
            port,
            outputMode = ProcessOutputMode.DISCARD,
        )
    } catch (failure: Exception) {
        remoteBestEffort(
            "remove unpublished TLS keystore",
            host,
            user,
            "rm -f $remoteTemporary",
            port,
            timeoutMillis = 20_000L,
        )
        throw failure
    }
    println("  SSL certificate configured")
}

fun registerSystemd(host: String, user: String, port: Int, deployPath: String) {
    requireCanonicalDeployPath(deployPath)
    requireActiveRemoteDeploymentGuard(host, user, port)
    val serviceContent = generateSystemdServiceContent(deployPath)
    val localTemporary = File.createTempFile("teamtalk-", ".service")
    val remoteTemporary = "/tmp/teamtalk-${UUID.randomUUID()}.service"
    try {
        localTemporary.writeText(serviceContent)
        remoteFileUploadChecked(
            label = "upload systemd service",
            file = localTemporary,
            host = host,
            user = user,
            port = port,
            remotePath = remoteTemporary,
        )
        remoteChecked(
            "install systemd service",
            host,
            user,
            "chmod 644 $remoteTemporary && mv -f $remoteTemporary /etc/systemd/system/teamtalk.service",
            port,
            outputMode = ProcessOutputMode.DISCARD,
        )
    } catch (failure: Exception) {
        remoteBestEffort(
            "remove unpublished systemd service",
            host,
            user,
            "rm -f $remoteTemporary",
            port,
            timeoutMillis = 20_000L,
        )
        throw failure
    } finally {
        localTemporary.delete()
    }
    println("  systemd service registered")
}

internal fun generateSystemdServiceContent(deployPath: String): String =
    """
[Unit]
Description=TeamTalk Server
After=network.target docker.service
Requires=docker.service

[Service]
Type=simple
WorkingDirectory=$deployPath
EnvironmentFile=$deployPath/conf/env.sh
ExecStartPre=/bin/bash -c 'cd $deployPath && export DB_PASSWORD="$${'$'}{DATABASE_PASSWORD}" && ${dockerComposeCmd(systemdContext = true)} up -d'
ExecStart=$deployPath/bin/teamtalk.sh
SuccessExitStatus=143
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
    """.trimIndent()

/** 解析 curl 的 `正文 + 换行 + 状态码` 结果，并强制执行完整的组件契约。 */
internal fun requireHealthyResponse(
    curlOutput: String,
    expectedBuildIdentity: String,
): ValidatedHealth {
    if (expectedBuildIdentity.isBlank()) {
        throw GradleException("Expected server build identity is missing")
    }
    val separator = curlOutput.lastIndexOf('\n')
    if (separator < 0) throw GradleException("Health response did not contain an HTTP status")
    val body = curlOutput.substring(0, separator).trim()
    val httpStatus = curlOutput.substring(separator + 1).trim().toIntOrNull()
        ?: throw GradleException("Health response contained an invalid HTTP status")
    if (httpStatus != 200) {
        throw GradleException("Health endpoint returned HTTP $httpStatus instead of 200")
    }

    val root = try {
        Json.parseToJsonElement(body).jsonObject
    } catch (failure: Exception) {
        throw GradleException("Health endpoint returned invalid JSON", failure)
    }
    val overall = try {
        root["status"]?.jsonPrimitive?.contentOrNull
    } catch (_: Exception) {
        null
    }
    if (overall != "UP") {
        throw GradleException("Health endpoint overall status is ${overall ?: "missing"}, expected UP")
    }
    val buildIdentity = try {
        root["buildIdentity"]?.jsonPrimitive?.contentOrNull
    } catch (_: Exception) {
        null
    }
    if (buildIdentity != expectedBuildIdentity) {
        throw GradleException(
            "Health endpoint build identity is ${buildIdentity ?: "missing"}, " +
                "expected $expectedBuildIdentity",
        )
    }
    val components = try {
        root["components"]?.jsonObject
    } catch (_: Exception) {
        null
    } ?: throw GradleException("Health endpoint components object is missing")

    val missing = requiredHealthComponents.filterNot(components::containsKey)
    val unhealthy = requiredHealthComponents.mapNotNull { name ->
        val status = try {
            components[name]?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull
        } catch (_: Exception) {
            null
        }
        if (status == "UP") null else "$name=${status ?: "missing"}"
    }
    if (missing.isNotEmpty() || unhealthy.isNotEmpty()) {
        val details = buildList {
            if (missing.isNotEmpty()) add("missing=${missing.joinToString()}")
            if (unhealthy.isNotEmpty()) add("not-UP=${unhealthy.joinToString()}")
        }.joinToString("; ")
        val expectedCount = requiredHealthComponents.size
        throw GradleException(
            "Health endpoint failed required $expectedCount/$expectedCount component contract: $details",
        )
    }
    return ValidatedHealth(httpStatus, requiredHealthComponents.toList(), buildIdentity)
}

fun healthCheck(
    host: String,
    user: String,
    port: Int,
    sslEnabled: Boolean,
    httpPort: Int,
    sslPort: Int,
    expectedBuildIdentity: String,
) {
    println("")
    println("=== Health Check ===")

    val healthProtocol = if (sslEnabled) {
        "https://127.0.0.1:$sslPort"
    } else {
        "http://127.0.0.1:$httpPort"
    }
    val pollFlags = if (sslEnabled) "-ksSf" else "-sSf"
    val fetchFlags = if (sslEnabled) "-ksS" else "-sS"

    print("  Waiting for TeamTalk Server ...")
    var retries = 0
    while (retries < 15) {
        val ready = remoteProbe(
            "probe TeamTalk health endpoint",
            host,
            user,
            "if curl $pollFlags --connect-timeout 3 --max-time 5 $healthProtocol/health " +
                ">/dev/null 2>&1; then exit 0; else exit 1; fi",
            port,
            timeoutMillis = 20_000L,
        )
        if (ready) {
            println(" OK")
            break
        }
        print(".")
        Thread.sleep(2000)
        retries++
    }
    if (retries == 15) {
        println(" TIMEOUT")
        throw GradleException(
            "SERVICE FAILED TO START - check logs: " +
                "ssh -p $port $user@$host 'journalctl -u teamtalk -n 50'",
        )
    }

    val healthOutput = remoteCaptureChecked(
        "fetch TeamTalk health report",
        host,
        user,
        "curl $fetchFlags --connect-timeout 3 --max-time 15 -o- " +
            "-w '\\n%{http_code}' $healthProtocol/health",
        port,
        timeoutMillis = 30_000L,
    )
    val validated = requireHealthyResponse(healthOutput, expectedBuildIdentity)
    println(
        "  All required components healthy " +
            "(${validated.components.size}/${requiredHealthComponents.size}):",
    )
    validated.components.forEach { println("    - $it: UP") }
    println("  Verified build identity: ${validated.buildIdentity}")
    println("  Health check passed (HTTP ${validated.httpStatus}, overall UP)")
    println("")
}

fun ensureDbUser(host: String, user: String, port: Int, deployPath: String, dbPassword: String) {
    requireCanonicalDeployPath(deployPath)
    requireActiveRemoteDeploymentGuard(host, user, port)
    if (dbPassword.isBlank() || dbPassword == "null") {
        throw GradleException("DATABASE_PASSWORD is missing before database provisioning")
    }
    println("  Ensuring database user 'teamtalk' is ready ...")
    val containerOutput = remoteCaptureChecked(
        "locate PostgreSQL container",
        host,
        user,
        "cd $deployPath && ${dockerComposeCmd()} ps -q postgres 2>/dev/null",
        port,
    )
    val containerIds = containerOutput.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    val containerId = containerIds.singleOrNull()?.takeIf { it.matches(Regex("[a-fA-F0-9]{12,64}")) }
        ?: throw GradleException(
            "Expected exactly one valid PostgreSQL container ID; database provisioning aborted",
        )

    val standardInput = databaseRoleProvisioningInput(dbPassword)
    try {
        remoteSensitiveStdinChecked(
            label = "configure TeamTalk database role",
            host = host,
            user = user,
            command = databaseRoleProvisioningCommand(containerId),
            standardInput = standardInput,
            port = port,
        )
    } finally {
        standardInput.fill(0)
    }
    println("  Database user 'teamtalk' ready")
}

/** 密码只通过 psql 在标准输入上的交互式变量提示提供。 */
internal fun databaseRoleProvisioningCommand(containerId: String): String {
    require(containerId.matches(Regex("[a-fA-F0-9]{12,64}"))) {
        "Invalid PostgreSQL container ID"
    }
    return "docker exec -i $containerId psql -X -v ON_ERROR_STOP=1 " +
        "-U teamtalk -d teamtalk"
}

/**
 * psql 的 `\\prompt` 将下一行当作数据消费，而不是元命令。随后 `:'name'` 展开
 * 会产生一个正确加引号的 SQL 字面量，同时凭据不会出现在 OS 参数、SSH 命令、
 * 环境变量或进程输出中。
 */
internal fun databaseRoleProvisioningInput(dbPassword: String): ByteArray {
    require(
        dbPassword.isNotBlank() && dbPassword != "null" &&
            dbPassword.none { it == '\u0000' || it == '\n' || it == '\r' },
    ) { "DATABASE_PASSWORD cannot be blank or contain line separators" }
    return buildString {
        append("\\prompt db_password\n")
        append(dbPassword).append('\n')
        append("ALTER ROLE teamtalk WITH LOGIN PASSWORD :'db_password';\n")
        append("GRANT ALL ON SCHEMA public TO teamtalk;\n")
        append("GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO teamtalk;\n")
        append("GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO teamtalk;\n")
        append("\\unset db_password\n")
    }.toByteArray(StandardCharsets.UTF_8).also { input ->
        require(input.size <= MAX_SENSITIVE_PROCESS_INPUT_BYTES) {
            "Database provisioning input exceeds the sensitive process input limit"
        }
    }
}
