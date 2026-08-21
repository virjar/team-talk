package deployment

/**
 * 远程主机配置：SSL 证书、systemd 注册、数据库用户管理、部署后健康检查。
 */

import java.io.File
import java.util.Base64
import java.util.Properties
import org.gradle.api.GradleException

// ── SSL 证书处理 ──

fun handleSsl(
    rootDir: File,
    host: String, user: String, port: Int, deployPath: String,
    sslCert: String, sslKey: String, secrets: Properties
) {
    val certFile = File(rootDir, sslCert)
    val keyFile = File(rootDir, sslKey)
    if (!certFile.exists()) throw GradleException("SSL certificate file not found: $sslCert")
    if (!keyFile.exists()) throw GradleException("SSL key file not found: $sslKey")

    val p12Password = secrets.getProperty("SSL_KEYSTORE_PASSWORD")
    val tmpP12 = File.createTempFile("teamtalk-ssl-", ".p12")
    tmpP12.deleteOnExit()

    println("  Converting PEM -> PKCS12 ...")
    val rc = localExecSilent(
        "openssl", "pkcs12", "-export",
        "-in", certFile.absolutePath,
        "-inkey", keyFile.absolutePath,
        "-out", tmpP12.absolutePath,
        "-name", "mykey",
        "-passout", "pass:$p12Password"
    )
    if (rc != 0) throw GradleException("Failed to convert PEM to PKCS12")

    println("  Uploading SSL certificate ...")
    localExecSilent("ssh", "-p", port.toString(), "-o", "ConnectTimeout=10", "$user@$host", "mkdir -p $deployPath/conf/ssl")
    localExecSilent("scp", "-P", port.toString(), tmpP12.absolutePath, "$user@$host:$deployPath/conf/ssl/teamtalk.p12")
    localExecSilent("ssh", "-p", port.toString(), "-o", "ConnectTimeout=10", "$user@$host", "chmod 600 $deployPath/conf/ssl/teamtalk.p12")
    tmpP12.delete()
    println("  SSL certificate configured")
}

// ── systemd 注册 ──

fun registerSystemd(host: String, user: String, port: Int, deployPath: String) {
    val svcContent = generateSystemdServiceContent(deployPath)

    val tmpSvc = File.createTempFile("teamtalk-", ".service")
    tmpSvc.deleteOnExit()
    tmpSvc.writeText(svcContent)

    localExecSilent("scp", "-P", port.toString(), tmpSvc.absolutePath, "$user@$host:/tmp/teamtalk.service")
    localExecSilent(
        "ssh", "-p", port.toString(), "-o", "ConnectTimeout=10", "$user@$host",
        "mv /tmp/teamtalk.service /etc/systemd/system/teamtalk.service"
    )
    tmpSvc.delete()
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

// ── 健康检查 ──

fun healthCheck(
    host: String,
    user: String,
    port: Int,
    sslEnabled: Boolean,
    httpPort: Int,
    sslPort: Int,
) {
    println("")
    println("=== Health Check ===")

    val healthProtocol = if (sslEnabled) "https://127.0.0.1:$sslPort" else "http://127.0.0.1:$httpPort"
    val healthFlag = if (sslEnabled) "-skf" else "-sf"

    print("  Waiting for TeamTalk Server ...")
    var retries = 0
    while (retries < 15) {
        if (remoteCheck(host, user, "curl $healthFlag $healthProtocol/health &>/dev/null", port)) {
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
                "ssh -p $port $user@$host 'journalctl -u teamtalk -n 50'"
        )
    }

    val healthOutput = remoteOutput(
        host, user,
        "curl $healthFlag --max-time 15 -o- -w '\\n%{http_code}' $healthProtocol/health 2>/dev/null",
        port
    )

    if (healthOutput == null) {
        throw GradleException("HEALTH CHECK FAILED - cannot reach /health endpoint")
    }

    val lines = healthOutput.lines()
    val httpStatus = lines.lastOrNull()?.toIntOrNull()
    val allUp = httpStatus == 200

    if (allUp) {
        println("  All components healthy:")
    } else {
        println("")
        println("  ++++++++++++++++++++++++++++++++++++++++++++++++++++")
        println("  +       !! COMPONENT HEALTH CHECK FAILED !!        +")
        println("  ++++++++++++++++++++++++++++++++++++++++++++++++++++")
        println("")
    }

    println("  Health check passed (HTTP $httpStatus)")
    println("")
}

// ── 确保数据库用户存在 ──

fun ensureDbUser(host: String, user: String, port: Int, deployPath: String, dbPassword: String) {
    println("  Ensuring database user 'teamtalk' is ready ...")
    val containerName = remoteOutput(
        host, user,
        "cd $deployPath && ${dockerComposeCmd()} ps -q postgres 2>/dev/null | head -1",
        port
    )?.trim()

    if (containerName.isNullOrBlank()) {
        println("  WARNING: Cannot find postgres container, skipping DB user setup")
        return
    }

    val fullContainerName = remoteOutput(
        host, user,
        "docker inspect --format '{{.Name}}' $containerName 2>/dev/null",
        port
    )?.trim()?.removePrefix("/") ?: containerName

    // docker-compose 以 POSTGRES_USER=teamtalk 初始化管理员账号。
    // 使用同一个账号修改密码和授权，避免依赖默认并不存在的 postgres 角色。
    val encodedPassword = Base64.getEncoder().encodeToString(dbPassword.toByteArray())
    val result = remoteExec(
        host, user,
        "docker exec $fullContainerName psql -U teamtalk -d teamtalk -c " +
                "\"DO \\\$\\\$ BEGIN " +
                "EXECUTE format('ALTER ROLE teamtalk WITH LOGIN PASSWORD %L', " +
                "convert_from(decode('$encodedPassword', 'base64'), 'UTF8')); " +
                "END \\\$\\\$; " +
                "GRANT ALL ON SCHEMA public TO teamtalk; " +
                "GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO teamtalk; " +
                "GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO teamtalk; \"",
        port
    )
    if (result != 0) {
        throw GradleException("Failed to configure database user 'teamtalk'")
    }
    println("  Database user 'teamtalk' ready")
}
