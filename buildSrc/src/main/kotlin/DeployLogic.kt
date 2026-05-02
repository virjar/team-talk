/**
 * 服务端部署逻辑：env.sh 生成、SSL 证书处理、systemd 注册、健康检查、
 * 数据库用户管理、首次部署和升级部署。
 *
 * 从根 build.gradle.kts 提取。不依赖 Project 上下文，通过参数传入所有依赖。
 */

import java.io.File
import java.net.URI
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import org.gradle.api.GradleException

// ── env.sh 生成 ──

fun generateEnvShContent(
    secrets: Properties,
    sslEnabled: Boolean,
    sslPort: String,
    deployPath: String,
    profileName: String,
    httpPort: Int,
    tcpPort: String?,
    effectiveDefaultHttpPort: Int
): String {
    val lines = mutableListOf<String>()
    val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

    lines.add("# TeamTalk 运行配置 - profile: $profileName")
    lines.add("# 自动生成于 $now")
    lines.add("# 权限 600，请勿提交到版本控制")
    lines.add("# 修改后执行: systemctl restart teamtalk")
    lines.add("#")
    lines.add("# 仅包含与 application.conf 默认值不同的项目和敏感密码")
    lines.add("# 未列出的配置使用 application.conf 默认值:")
    lines.add("#   httpPort=8080, tcpPort=5100, database=127.0.0.1:5432/teamtalk")
    lines.add("")

    lines.add("# ── 服务端口 ──")
    if (httpPort != effectiveDefaultHttpPort) {
        lines.add("KTOR_PORT=$httpPort")
    }
    if (tcpPort != null && tcpPort != "5100") {
        lines.add("TCP_PORT=$tcpPort")
    }
    lines.add("")

    lines.add("# ── 数据库 ──")
    lines.add("DATABASE_USER=\"teamtalk\"")
    lines.add("DATABASE_PASSWORD=\"${secrets.getProperty("DATABASE_PASSWORD")}\"")
    lines.add("")

    lines.add("# ── 认证 ──")
    lines.add("JWT_SECRET=\"${secrets.getProperty("JWT_SECRET")}\"")
    lines.add("")

    if (sslEnabled) {
        lines.add("# ── SSL ──")
        lines.add("KTOR_SSL_PORT=$sslPort")
        lines.add("SSL_KEYSTORE=\"$deployPath/conf/ssl/teamtalk.p12\"")
        lines.add("SSL_KEYSTORE_PASSWORD=\"${secrets.getProperty("SSL_KEYSTORE_PASSWORD")}\"")
        lines.add("SSL_PRIVATE_KEY_PASSWORD=\"${secrets.getProperty("SSL_PRIVATE_KEY_PASSWORD")}\"")
        lines.add("")
    }

    return lines.joinToString("\n")
}

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
ExecStartPre=/bin/bash -c 'cd $deployPath && export DB_PASSWORD="$${'$'}{DATABASE_PASSWORD}" && docker compose up -d'
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
    localExecSilent("ssh", "-o", "ConnectTimeout=10", "$user@$host",
        "mv /tmp/teamtalk.service /etc/systemd/system/teamtalk.service")
    tmpSvc.delete()
    println("  systemd service registered")
}

// ── 健康检查 ──

fun healthCheck(host: String, user: String, deployPath: String, sslEnabled: Boolean, httpPort: Int) {
    println("")
    println("=== Health Check ===")

    val healthProtocol = if (sslEnabled) "https://127.0.0.1:443" else "http://127.0.0.1:$httpPort"
    val healthFlag = if (sslEnabled) "-skf" else "-sf"

    // 等待 HTTP 服务启动
    print("  Waiting for TeamTalk Server ...")
    var retries = 0
    while (retries < 15) {
        if (remoteCheck(host, user, "curl $healthFlag $healthProtocol/ping &>/dev/null")) {
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

    // 调用增强 /health 端点获取组件级状态（加超时保护，避免 DB 连不上时 /health 卡住）
    val healthOutput = remoteOutput(host, user,
        "curl $healthFlag --max-time 15 -o- -w '\\n%{http_code}' $healthProtocol/health 2>/dev/null"
    )

    if (healthOutput == null) {
        throw GradleException("HEALTH CHECK FAILED - cannot reach /health endpoint")
    }

    // 分离 HTTP 状态码和 body
    val lines = healthOutput.lines()
    val httpStatus = lines.lastOrNull()?.toIntOrNull()
    val body = if (lines.size > 1) lines.dropLast(1).joinToString("\n") else healthOutput

    val componentNames = listOf("postgres", "file-storage", "rocksdb", "lucene", "tcp")
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

    val failedComponents = mutableListOf<String>()
    for (component in componentNames) {
        val statusPattern = Regex(""""$component"\s*:\s*\{"status"\s*:\s*"(\w+)"(?:,"detail"\s*:\s*"((?:[^"\\]|\\.)*)")?""")
        val match = statusPattern.find(body)
        if (match != null) {
            val componentStatus = match.groupValues[1]
            val detail = match.groupValues.getOrNull(2)
            if (componentStatus == "UP") {
                println("    $component: UP")
            } else {
                println("    >>> $component: DOWN <<<")
                failedComponents.add(component)
                if (detail != null && detail.isNotEmpty()) {
                    println("        Error: $detail")
                }
            }
        } else {
            println("    $component: UNKNOWN")
            failedComponents.add(component)
        }
    }

    if (!allUp) {
        println("")
        println("  Check logs: ssh $host 'journalctl -u teamtalk -n 50'")
        throw GradleException("HEALTH CHECK FAILED - components DOWN: ${failedComponents.joinToString(", ")}")
    }
    println("")
}

// ── 确保数据库用户存在 ──

fun ensureDbUser(host: String, user: String, deployPath: String, dbPassword: String) {
    println("  Ensuring database user 'teamtalk' exists ...")
    // 先尝试找到 postgres 容器名称
    val containerName = remoteOutput(host, user,
        "cd $deployPath && docker compose ps -q postgres 2>/dev/null | head -1"
    )?.trim()

    if (containerName.isNullOrBlank()) {
        println("  WARNING: Cannot find postgres container, skipping DB user setup")
        return
    }

    val fullContainerName = remoteOutput(host, user,
        "docker inspect --format '{{.Name}}' $containerName 2>/dev/null"
    )?.trim()?.removePrefix("/") ?: containerName

    // 创建用户（如果不存在）并设置密码，授予所有权限
    remoteExec(host, user,
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
        "GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO teamtalk; " +
        "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL PRIVILEGES ON TABLES TO teamtalk; " +
        "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL PRIVILEGES ON SEQUENCES TO teamtalk;\""
    )
    println("  Database user 'teamtalk' ready")
}

// ── 上传 env.sh 到远程 ──

fun uploadEnvSh(envShContent: String, host: String, user: String, deployPath: String) {
    val tmpEnv = File.createTempFile("teamtalk-env-", ".sh")
    tmpEnv.deleteOnExit()
    tmpEnv.writeText(envShContent)

    localExecSilent("scp", tmpEnv.absolutePath, "$user@$host:$deployPath/conf/env.sh")
    localExecSilent("ssh", "-o", "ConnectTimeout=10", "$user@$host", "chmod 600 $deployPath/conf/env.sh")
    tmpEnv.delete()
    println("  conf/env.sh uploaded (mode 600)")
}

// ── 首次部署 ──

fun deployNew(
    rootDir: File,
    host: String, user: String, deployPath: String,
    secrets: Properties, sslEnabled: Boolean, sslPort: String,
    sslCert: String?, sslKey: String?,
    profileName: String,
    httpPort: Int, tcpPort: String?,
    effectiveDefaultHttpPort: Int
) {
    // Create directory structure
    remoteExec(host, user,
        "mkdir -p $deployPath/{data/pgdata,data/rocksdb,data/lucene-index,data/file-store/rocksdb,data/file-store/files,data/file-store/tmp,data/logs,conf/ssl,conf,static/downloads}")

    // Upload server distribution
    println("  Uploading server distribution ...")
    val distDir = File(rootDir, "server/build/install/teamtalk-server")
    localExec(
        "rsync", "-avz", "--delete",
        "--exclude=data", "--exclude=logs", "--exclude=env.sh",
        "--exclude=docker-compose.yml", "--exclude=conf/ssl", "--exclude=conf/env.sh",
        "-e", "ssh -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new",
        "$distDir/", "$user@$host:$deployPath/"
    )

    // Generate and upload env.sh
    println("  Generating env.sh ...")
    val envShContent = generateEnvShContent(secrets, sslEnabled, sslPort, deployPath, profileName, httpPort, tcpPort, effectiveDefaultHttpPort)
    uploadEnvSh(envShContent, host, user, deployPath)

    // SSL certificate
    if (sslEnabled && sslCert != null && sslKey != null) {
        handleSsl(rootDir, host, user, deployPath, sslCert, sslKey, secrets)
    }

    // Generate docker-compose.yml
    println("  Configuring Docker infrastructure ...")
    val dcContent = """
services:
  postgres:
    image: postgres:16-alpine
    restart: always
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U teamtalk"]
      interval: 5s
      timeout: 3s
      retries: 10
    ports:
      - "127.0.0.1:5432:5432"
    environment:
      POSTGRES_USER: teamtalk
      POSTGRES_PASSWORD: ${'$'}{DB_PASSWORD}
      POSTGRES_DB: teamtalk
    volumes:
      - $deployPath/data/pgdata:/var/lib/postgresql/data
""".trimIndent()
    val tmpDc = File.createTempFile("teamtalk-dc-", ".yml")
    tmpDc.deleteOnExit()
    tmpDc.writeText(dcContent)
    localExecSilent("scp", tmpDc.absolutePath, "$user@$host:$deployPath/docker-compose.yml")
    tmpDc.delete()

    // Start Docker infrastructure
    println("  Starting PostgreSQL ...")
    remoteExec(host, user,
        "cd $deployPath && " +
        "set -a && source conf/env.sh && set +a && " +
        "export DB_PASSWORD=\"\$DATABASE_PASSWORD\" && " +
        "docker compose up -d")

    // Wait for PostgreSQL
    print("  Waiting for PostgreSQL ...")
    val pgContainer = deployPath.substringAfterLast("/")
    var retries = 0
    while (retries < 30) {
        if (remoteCheck(host, user,
            "docker exec ${pgContainer}-postgres-1 pg_isready -U teamtalk &>/dev/null || " +
            "docker exec teamtalk-postgres-1 pg_isready -U teamtalk &>/dev/null"
        )) {
            println(" OK")
            break
        }
        print(".")
        Thread.sleep(2000)
        retries++
    }
    if (retries == 30) throw GradleException("PostgreSQL startup timeout")

    // Ensure database user exists with correct password
    ensureDbUser(host, user, deployPath, secrets.getProperty("DATABASE_PASSWORD"))

    // Register systemd service
    println("  Registering systemd service ...")
    registerSystemd(host, user, deployPath)

    // Start TeamTalk
    println("  Starting TeamTalk Server ...")
    remoteExec(host, user, "systemctl daemon-reload && systemctl enable teamtalk && systemctl start teamtalk")

    println("")
    println("========================================")
    println("       TeamTalk First Deploy Complete!")
    println("========================================")
    println("")
    println("  Deploy path:     $deployPath")
    println("  Secrets saved:   gradle/profiles/${profileName}.secrets")
    if (sslEnabled) {
        println("  SSL:             enabled (port $sslPort)")
    } else {
        println("  SSL:             disabled (HTTP port 8080)")
    }
    println("")
}

// ── 升级部署 ──

fun deployUpgrade(
    rootDir: File,
    host: String, user: String, deployPath: String,
    secrets: Properties, sslEnabled: Boolean, sslPort: String,
    sslCert: String?, sslKey: String?,
    profileName: String,
    httpPort: Int, tcpPort: String?,
    effectiveDefaultHttpPort: Int
) {
    // Stop server
    if (remoteCheck(host, user, "systemctl is-active --quiet teamtalk 2>/dev/null")) {
        println("  Stopping TeamTalk Server ...")
        remoteExec(host, user, "systemctl stop teamtalk || true")
    }

    // Kill orphan processes
    println("  Cleaning residual processes ...")
    remoteExec(host, user, "pkill -f 'com.virjar.tk.ApplicationKt' 2>/dev/null || true")
    Thread.sleep(1000)

    // Backup
    println("  Backing up current version ...")
    remoteExec(host, user, "rm -rf ${deployPath}.bak && cp -r $deployPath ${deployPath}.bak || true")

    // Upload new version
    println("  Uploading new version ...")
    val distDir = File(rootDir, "server/build/install/teamtalk-server")
    localExec(
        "rsync", "-avz",
        "--exclude=data", "--exclude=logs", "--exclude=env.sh",
        "--exclude=docker-compose.yml", "--exclude=.pid",
        "--exclude=conf/ssl", "--exclude=conf/env.sh",
        "-e", "ssh -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new",
        "$distDir/", "$user@$host:$deployPath/"
    )

    // Migrate env.sh from old location to conf/env.sh
    println("  Checking env.sh location ...")
    val hasNewEnvSh = remoteCheck(host, user, "test -f $deployPath/conf/env.sh")
    val hasOldEnvSh = remoteCheck(host, user, "test -f $deployPath/env.sh")
    if (!hasNewEnvSh && hasOldEnvSh) {
        println("  Migrating env.sh -> conf/env.sh ...")
        remoteExec(host, user, "cp $deployPath/env.sh $deployPath/conf/env.sh && chmod 600 $deployPath/conf/env.sh")
    }

    // 升级部署：secrets 从远程 env.sh 提取（不变），端口配置从 profile 同步
    println("  Syncing port configuration from profile ...")
    val envShContent = generateEnvShContent(secrets, sslEnabled, sslPort, deployPath, profileName, httpPort, tcpPort, effectiveDefaultHttpPort)
    uploadEnvSh(envShContent, host, user, deployPath)

    // Ensure database user exists with correct password
    ensureDbUser(host, user, deployPath, secrets.getProperty("DATABASE_PASSWORD"))

    // Update SSL certificate if provided
    if (sslEnabled && sslCert != null && sslKey != null) {
        println("  Updating SSL certificate ...")
        handleSsl(rootDir, host, user, deployPath, sslCert, sslKey, secrets)
    }

    // Re-register systemd
    println("  Re-registering systemd service ...")
    registerSystemd(host, user, deployPath)

    // Start server
    println("  Starting TeamTalk Server ...")
    remoteExec(host, user, "systemctl daemon-reload && systemctl enable teamtalk && systemctl start teamtalk")

    println("")
    println("========================================")
    println("       TeamTalk Upgrade Complete!")
    println("========================================")
    println("")
    println("  Backup: ${deployPath}.bak")
    println("")
}

// ── 从 serverUrl 提取 HTTP 端口 ──

fun extractHttpPort(serverUrl: String): Int {
    return try {
        val uri = URI(serverUrl)
        val explicit = uri.port
        if (explicit != -1) explicit else if (uri.scheme == "https") 443 else 80
    } catch (_: Exception) { 8080 }
}

fun effectiveDefaultHttpPort(serverUrl: String): Int {
    return if (serverUrl.startsWith("https://")) 443 else 8080
}
