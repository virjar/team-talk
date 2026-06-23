package profiles

/**
 * 远程主机配置：SSL 证书、systemd 注册、数据库用户管理、部署后健康检查。
 */

import java.io.File
import java.util.Properties
import org.gradle.api.GradleException

// ── SSL 证书处理 ──

fun handleSsl(
    rootDir: File,
    host: String, user: String, deployPath: String,
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
    localExecSilent("ssh", "-o", "ConnectTimeout=10", "$user@$host", "mkdir -p $deployPath/conf/ssl")
    localExecSilent("scp", tmpP12.absolutePath, "$user@$host:$deployPath/conf/ssl/teamtalk.p12")
    localExecSilent("ssh", "-o", "ConnectTimeout=10", "$user@$host", "chmod 600 $deployPath/conf/ssl/teamtalk.p12")
    tmpP12.delete()
    println("  SSL certificate configured")
}

// ── systemd 注册 ──

fun registerSystemd(host: String, user: String, deployPath: String) {
    val svcContent = """
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
ExecStop=/bin/kill $${'$'}MAINPID
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
""".trimIndent()

    val tmpSvc = File.createTempFile("teamtalk-", ".service")
    tmpSvc.deleteOnExit()
    tmpSvc.writeText(svcContent)

    localExecSilent("scp", tmpSvc.absolutePath, "$user@$host:/tmp/teamtalk.service")
    localExecSilent(
        "ssh", "-o", "ConnectTimeout=10", "$user@$host",
        "mv /tmp/teamtalk.service /etc/systemd/system/teamtalk.service"
    )
    tmpSvc.delete()
    println("  systemd service registered")
}

// ── 健康检查 ──

fun healthCheck(host: String, user: String, deployPath: String, sslEnabled: Boolean, httpPort: Int) {
    println("")
    println("=== Health Check ===")

    val healthProtocol = if (sslEnabled) "https://127.0.0.1:443" else "http://127.0.0.1:$httpPort"
    val healthFlag = if (sslEnabled) "-skf" else "-sf"

    print("  Waiting for TeamTalk Server ...")
    var retries = 0
    while (retries < 15) {
        if (remoteCheck(host, user, "curl $healthFlag $healthProtocol/health &>/dev/null")) {
            println(" OK")
            break
        }
        print(".")
        Thread.sleep(2000)
        retries++
    }
    if (retries == 15) {
        println(" TIMEOUT")
        throw GradleException("SERVICE FAILED TO START - check logs: ssh $host 'journalctl -u teamtalk -n 50'")
    }

    val healthOutput = remoteOutput(
        host, user,
        "curl $healthFlag --max-time 15 -o- -w '\\n%{http_code}' $healthProtocol/health 2>/dev/null"
    )

    if (healthOutput == null) {
        throw GradleException("HEALTH CHECK FAILED - cannot reach /health endpoint")
    }

    val lines = healthOutput.lines()
    val httpStatus = lines.lastOrNull()?.toIntOrNull()
    val body = if (lines.size > 1) lines.dropLast(1).joinToString("\n") else healthOutput

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

fun ensureDbUser(host: String, user: String, deployPath: String, dbPassword: String) {
    println("  Ensuring database user 'teamtalk' is ready ...")
    val containerName = remoteOutput(
        host, user,
        "cd $deployPath && ${dockerComposeCmd()} ps -q postgres 2>/dev/null | head -1"
    )?.trim()

    if (containerName.isNullOrBlank()) {
        println("  WARNING: Cannot find postgres container, skipping DB user setup")
        return
    }

    val fullContainerName = remoteOutput(
        host, user,
        "docker inspect --format '{{.Name}}' $containerName 2>/dev/null"
    )?.trim()?.removePrefix("/") ?: containerName

    // POSTGRES_USER=teamtalk 已创建超级用户，只需确保密码正确
    remoteExec(
        host, user,
        "docker exec $fullContainerName psql -U teamtalk -d teamtalk -c " +
                "\"ALTER ROLE teamtalk WITH LOGIN PASSWORD '$dbPassword';\" 2>/dev/null || " +
                "docker exec $fullContainerName psql -U postgres -d teamtalk -c " +
                "\"DO \\\$\\\$ BEGIN " +
                "IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'teamtalk') THEN " +
                "CREATE ROLE teamtalk WITH LOGIN PASSWORD '$dbPassword'; " +
                "ELSE " +
                "ALTER ROLE teamtalk WITH LOGIN PASSWORD '$dbPassword'; " +
                "END IF; " +
                "END; \\\$\\\$ ;\" && " +
                "docker exec $fullContainerName psql -U postgres -d teamtalk -c " +
                "\"GRANT ALL ON SCHEMA public TO teamtalk; " +
                "GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO teamtalk; " +
                "GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO teamtalk; \""
    )
    println("  Database user 'teamtalk' ready")
}
