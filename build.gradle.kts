plugins {
    kotlin("multiplatform") version "2.3.20" apply false
    kotlin("jvm") version "2.3.20" apply false
    kotlin("plugin.serialization") version "2.3.20" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("org.jetbrains.compose") version "1.10.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
    id("com.android.application") version "8.9.2" apply false
    id("com.android.library") version "8.9.2" apply false
    id("app.cash.sqldelight") version "2.3.2" apply false
}

extra.apply {
    set("kotlinVersion", "2.3.20")
    set("composeVersion", "1.10.0")
    set("agpVersion", "8.9.2")
    set("ktorVersion", "3.4.3")
    set("exposedVersion", "0.61.0")
    set("kotlinxSerializationVersion", "1.8.1")
    set("kotlinxCoroutinesVersion", "1.10.2")
    set("sqldelightVersion", "2.3.2")
    set("rocksdbVersion", "9.10.0")
    set("logbackVersion", "1.5.18")
    set("luceneVersion", "9.12.0")
    set("androidMinSdk", 26)
    set("androidTargetSdk", 35)
    set("androidCompileSdk", 36)
    set("packageVersion", "1.0.0")
}

// ── Profile 全量发现 ──

// 扫描所有 profile 文件
val allProfiles: Map<String, Map<String, String>> = file("gradle/profiles").listFiles()
    ?.filter { it.name.endsWith(".properties") }
    ?.associate { profileFile ->
        val name = profileFile.name.removeSuffix(".properties")
        val props = java.util.Properties().apply {
            profileFile.inputStream().use { load(it) }
        }
        name to props.map { it.key.toString() to it.value.toString() }.toMap()
    } ?: emptyMap()

if (allProfiles.isEmpty()) {
    throw GradleException("No profiles found in gradle/profiles/. Expected *.properties files.")
}

// 注册到 extra 供子模块消费
extra.set("allProfiles", allProfiles)
extra.set("profileNames", allProfiles.keys.toList())

// 活跃 profile（向后兼容 -PbuildProfile=xxx）
val profileName = findProperty("buildProfile")?.toString() ?: "dev"
if (!allProfiles.containsKey(profileName)) {
    throw GradleException(
        "Profile '$profileName' not found. Available: ${allProfiles.keys.joinToString(", ")}"
    )
}
extra.set("activeProfileName", profileName)

// 验证活跃 profile 必填字段
val activeProps = allProfiles[profileName]!!
val serverUrl = activeProps["serverUrl"]
    ?: throw GradleException("Profile '$profileName' must define 'serverUrl'")
if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
    throw GradleException("Profile '$profileName': serverUrl must start with http:// or https://")
}
activeProps["tcpHost"] ?: throw GradleException("Profile '$profileName' must define 'tcpHost'")
activeProps["tcpPort"] ?: throw GradleException("Profile '$profileName' must define 'tcpPort'")
activeProps["buildProfile"] ?: throw GradleException("Profile '$profileName' must define 'buildProfile'")

// 从 serverUrl 提取 HTTP 端口（默认 80/443），用于服务端 KTOR_PORT 配置
val serverHttpPort = try {
    val uri = java.net.URI(serverUrl)
    val explicit = uri.port
    if (explicit != -1) explicit else if (uri.scheme == "https") 443 else 80
} catch (_: Exception) { 8080 }
val defaultHttpPort = if (serverUrl.startsWith("https://")) 443 else 8080

// 注入活跃 profile 到 extra（向后兼容：子模块通过 rootProject.extra 读取）
activeProps.forEach { (k, v) -> extra.set(k, v) }

// 强制统一 Kotlin 依赖版本，防止 AGP 或其他插件引入低版本导致 metadata 冲突
subprojects {
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") {
                useVersion(rootProject.extra["kotlinVersion"] as String)
            }
        }
    }
}

// ── Extra 属性安全访问 ──

fun getExtra(key: String): String? {
    return try { extra.get(key) as? String } catch (_: Exception) { null }
}

// ── SSH 辅助函数 ──

fun remoteExec(host: String, user: String, cmd: String): Int {
    val pb = ProcessBuilder("ssh", "-o", "ConnectTimeout=10", "-o", "StrictHostKeyChecking=accept-new", "$user@$host", cmd)
        .redirectErrorStream(true)
    val proc = pb.start()
    proc.inputStream.bufferedReader().forEachLine { println("  $it") }
    return proc.waitFor()
}

fun remoteCheck(host: String, user: String, cmd: String): Boolean {
    val pb = ProcessBuilder("ssh", "-o", "ConnectTimeout=10", "-o", "StrictHostKeyChecking=accept-new", "$user@$host", cmd)
        .redirectErrorStream(true)
    val proc = pb.start()
    proc.inputStream.readBytes() // drain
    return proc.waitFor() == 0
}

fun remoteOutput(host: String, user: String, cmd: String): String? {
    val pb = ProcessBuilder("ssh", "-o", "ConnectTimeout=10", "-o", "StrictHostKeyChecking=accept-new", "$user@$host", cmd)
        .redirectErrorStream(true)
    val proc = pb.start()
    val output = proc.inputStream.bufferedReader().readText().trim()
    return if (proc.waitFor() == 0) output else null
}

fun localExec(vararg args: String): Int {
    val pb = ProcessBuilder(*args).redirectErrorStream(true)
    val proc = pb.start()
    proc.inputStream.bufferedReader().forEachLine { println("  $it") }
    return proc.waitFor()
}

fun localExecSilent(vararg args: String): Int {
    val pb = ProcessBuilder(*args).redirectErrorStream(true)
    val proc = pb.start()
    proc.inputStream.readBytes() // drain
    return proc.waitFor()
}

fun genPassword(): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    return (1..32).map { chars.random() }.joinToString("")
}

// ── Secret 管理 ──

/**
 * 从远程 env.sh 提取 secrets（升级部署使用，远程 env.sh 是 source of truth）
 */
fun extractSecretsFromRemote(
    secretsFile: File,
    host: String,
    user: String,
    deployPath: String
): java.util.Properties? {
    val envContent = remoteOutput(host, user, "cat $deployPath/conf/env.sh 2>/dev/null")
        ?: remoteOutput(host, user, "cat $deployPath/env.sh 2>/dev/null")

    if (envContent != null) {
        val secrets = java.util.Properties()
        val keyPattern = Regex("""^(DATABASE_PASSWORD|JWT_SECRET|SSL_KEYSTORE_PASSWORD|SSL_PRIVATE_KEY_PASSWORD)="?([^"]*)"?\s*$""")
        for (line in envContent.lines()) {
            val match = keyPattern.find(line) ?: continue
            secrets.setProperty(match.groupValues[1], match.groupValues[2])
        }
        if (secrets.isNotEmpty()) {
            saveSecrets(secretsFile, secrets)
            println("  Extracted secrets from remote env.sh, saved to ${secretsFile.name}")
            return secrets
        }
    }
    return null
}

/**
 * 加载或生成 secrets（首次部署使用）
 */
fun loadOrGenerateSecrets(
    secretsFile: File,
    host: String,
    user: String,
    deployPath: String
): java.util.Properties {
    val secrets = java.util.Properties()

    // 1. 尝试从本地 .secrets 文件加载
    if (secretsFile.exists()) {
        secretsFile.inputStream().use { secrets.load(it) }
        println("  Loaded secrets from ${secretsFile.name}")
        return secrets
    }

    // 2. 尝试从远程 conf/env.sh 提取（首次部署但服务器已有配置的场景）
    val remote = extractSecretsFromRemote(secretsFile, host, user, deployPath)
    if (remote != null) return remote

    // 3. 全新部署：生成随机密码
    println("  Generating new secrets ...")
    secrets.setProperty("DATABASE_PASSWORD", genPassword())
    secrets.setProperty("JWT_SECRET", genPassword() + genPassword())
    secrets.setProperty("SSL_KEYSTORE_PASSWORD", genPassword())
    secrets.setProperty("SSL_PRIVATE_KEY_PASSWORD", genPassword())

    saveSecrets(secretsFile, secrets)
    println("  Generated new secrets, saved to ${secretsFile.name}")
    return secrets
}

fun saveSecrets(secretsFile: File, secrets: java.util.Properties) {
    secretsFile.parentFile.mkdirs()
    secretsFile.bufferedWriter().use { w ->
        w.write("# TeamTalk Secrets - profile: $profileName\n")
        w.write("# 此文件包含敏感信息，已加入 .gitignore\n\n")
        w.write("# Database\n")
        w.write("DATABASE_PASSWORD=${secrets.getProperty("DATABASE_PASSWORD")}\n\n")
        w.write("# Auth\n")
        w.write("JWT_SECRET=${secrets.getProperty("JWT_SECRET")}\n\n")
        w.write("# SSL\n")
        w.write("SSL_KEYSTORE_PASSWORD=${secrets.getProperty("SSL_KEYSTORE_PASSWORD")}\n")
        w.write("SSL_PRIVATE_KEY_PASSWORD=${secrets.getProperty("SSL_PRIVATE_KEY_PASSWORD")}\n")
    }
}

// ── env.sh 生成 ──

fun generateEnvShContent(
    secrets: java.util.Properties,
    sslEnabled: Boolean,
    sslPort: String,
    deployPath: String,
    httpPort: Int = defaultHttpPort,
    tcpPort: String? = null,
    effectiveDefaultHttpPort: Int = defaultHttpPort
): String {
    val lines = mutableListOf<String>()
    val now = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))

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
    host: String, user: String, deployPath: String,
    sslCert: String, sslKey: String, secrets: java.util.Properties
) {
    val certFile = file(sslCert)
    val keyFile = file(sslKey)
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

fun healthCheck(host: String, user: String, deployPath: String, sslEnabled: Boolean, httpPort: Int = defaultHttpPort) {
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
        println("  ╔══════════════════════════════════════════════════╗")
        println("  ║        !! COMPONENT HEALTH CHECK FAILED !!       ║")
        println("  ╚══════════════════════════════════════════════════╝")
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
    host: String, user: String, deployPath: String,
    secrets: java.util.Properties, sslEnabled: Boolean, sslPort: String,
    sslCert: String?, sslKey: String?,
    httpPort: Int = defaultHttpPort, tcpPort: String? = null,
    effectiveDefaultHttpPort: Int = defaultHttpPort
) {
    // Create directory structure
    remoteExec(host, user,
        "mkdir -p $deployPath/{data/pgdata,data/rocksdb,data/lucene-index,data/file-store/rocksdb,data/file-store/files,data/file-store/tmp,data/logs,conf/ssl,conf,static/downloads}")

    // Upload server distribution
    println("  Uploading server distribution ...")
    val distDir = file("server/build/install/teamtalk-server")
    localExec(
        "rsync", "-avz", "--delete",
        "--exclude=data", "--exclude=logs", "--exclude=env.sh",
        "--exclude=docker-compose.yml", "--exclude=conf/ssl", "--exclude=conf/env.sh",
        "-e", "ssh -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new",
        "$distDir/", "$user@$host:$deployPath/"
    )

    // Generate and upload env.sh
    println("  Generating env.sh ...")
    val envShContent = generateEnvShContent(secrets, sslEnabled, sslPort, deployPath, httpPort, tcpPort, effectiveDefaultHttpPort)
    uploadEnvSh(envShContent, host, user, deployPath)

    // SSL certificate
    if (sslEnabled && sslCert != null && sslKey != null) {
        handleSsl(host, user, deployPath, sslCert, sslKey, secrets)
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
    host: String, user: String, deployPath: String,
    secrets: java.util.Properties, sslEnabled: Boolean, sslPort: String,
    sslCert: String?, sslKey: String?,
    httpPort: Int = defaultHttpPort, tcpPort: String? = null,
    effectiveDefaultHttpPort: Int = defaultHttpPort
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
    val distDir = file("server/build/install/teamtalk-server")
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
    val envShContent = generateEnvShContent(secrets, sslEnabled, sslPort, deployPath, httpPort, tcpPort, effectiveDefaultHttpPort)
    uploadEnvSh(envShContent, host, user, deployPath)

    // Ensure database user exists with correct password
    ensureDbUser(host, user, deployPath, secrets.getProperty("DATABASE_PASSWORD"))

    // Update SSL certificate if provided
    if (sslEnabled && sslCert != null && sslKey != null) {
        println("  Updating SSL certificate ...")
        handleSsl(host, user, deployPath, sslCert, sslKey, secrets)
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

// ── 辅助函数：按 profile 首字母大写 ──

fun capitalize(s: String) = s.replaceFirstChar { it.uppercase() }

// ── 发布聚合任务（按 profile 注册） ──

allProfiles.forEach { (pn, _) ->
    val cap = capitalize(pn)

    tasks.register("build${cap}Release") {
        group = "release"
        description = "Build all release artifacts for profile: $pn"
        dependsOn(":server:buildServerDist", ":desktop:package${cap}DistributionForCurrentOS", ":android:assemble${cap}Release")
    }
}

// buildRelease 作为活跃 profile 的别名
tasks.register("buildRelease") {
    group = "release"
    description = "Build all release artifacts (alias for build${capitalize(profileName)}Release)"
    dependsOn("build${capitalize(profileName)}Release")
}

// ── 上传任务（按 profile 注册） ──

fun registerUploadTask(taskName: String, pn: String) {
    tasks.register(taskName) {
        group = "deploy"
        description = "Build and upload release artifacts for profile: $pn"
        dependsOn("build${capitalize(pn)}Release")

        doLast {
            val props = allProfiles[pn]!!
            val host = props["deploy.host"]
                ?: throw GradleException(
                    "$taskName: profile '$pn' does not define 'deploy.host'. " +
                        "Add deploy.host, deploy.user, deploy.path to your profile."
                )
            val user = props["deploy.user"] ?: "root"
            val path = props["deploy.path"] ?: "/opt/teamtalk"

            val remoteDir = "$path/static/downloads"
            println("Uploading to $user@$host:$remoteDir ...")

            remoteExec(host, user, "mkdir -p $remoteDir")

            // Upload desktop packages
            val desktopRename = mapOf("deb" to "TeamTalk-linux.deb", "msi" to "TeamTalk-windows.msi", "dmg" to "TeamTalk-macos.dmg")
            val desktopDir = file("desktop/build/compose/binaries/$pn")
            if (desktopDir.exists()) {
                desktopDir.walkTopDown()
                    .filter { it.isFile && (it.extension in desktopRename.keys) }
                    .forEach { pkg ->
                        val remoteName = desktopRename[pkg.extension] ?: pkg.name
                        println("  Uploading ${pkg.name} as $remoteName ...")
                        localExecSilent("scp", pkg.absolutePath, "$user@$host:$remoteDir/$remoteName")
                    }
            }

            // Upload Android APK
            val apkDir = file("android/build/outputs/apk/$pn/release")
            if (apkDir.exists()) {
                val apk = apkDir.walkTopDown()
                    .filter { it.isFile && it.name.endsWith("-release.apk") }
                    .firstOrNull()
                if (apk != null) {
                    println("  Uploading ${apk.name} ...")
                    localExecSilent("scp", apk.absolutePath, "$user@$host:$remoteDir/TeamTalk-android.apk")
                }
            }

            println("Upload complete. Download page: https://$host/")
        }
    }
}

allProfiles.keys.forEach { pn ->
    val cap = capitalize(pn)
    registerUploadTask("upload${cap}Release", pn)
}

// uploadRelease 作为活跃 profile 的别名
tasks.register("uploadRelease") {
    group = "deploy"
    description = "Upload release artifacts (alias for upload${capitalize(profileName)}Release)"
    dependsOn("upload${capitalize(profileName)}Release")
}

// ── 部署任务（按 profile 注册） ──

fun registerDeployTask(taskName: String, pn: String) {
    tasks.register(taskName) {
        group = "deploy"
        description = "Build and deploy server with profile: $pn"
        dependsOn(":server:buildServerDist")

        doLast {
            val props = allProfiles[pn]!!
            val host = props["deploy.host"]
                ?: throw GradleException(
                    "$taskName: profile '$pn' does not define 'deploy.host'. " +
                        "Add deploy.host, deploy.user, deploy.path to your profile."
                )
            val user = props["deploy.user"] ?: "root"
            val deployPath = props["deploy.path"] ?: "/opt/teamtalk"
            val sslEnabled = props["server.ssl.enabled"]?.toBoolean() ?: false
            val sslPort = props["server.ssl.port"] ?: "443"
            val sslCert = findProperty("sslCert")?.toString()
            val sslKey = findProperty("sslKey")?.toString()
            val tcpPort = props["tcpPort"]

            val url = props["serverUrl"]!!
            val port = try {
                val uri = java.net.URI(url)
                val explicit = uri.port
                if (explicit != -1) explicit else if (uri.scheme == "https") 443 else 80
            } catch (_: Exception) { 8080 }
            val effectiveDefaultHttpPort = if (url.startsWith("https://")) 443 else 8080

            println("")
            println("=== TeamTalk Deploy (profile: $pn) ===")
            println("  Target: $user@$host")
            println("  Path:   $deployPath")
            println("  HTTP:   port $port")
            println("  TCP:    port ${tcpPort ?: 5100}")
            println("  SSL:    ${if (sslEnabled) "enabled (port $sslPort)" else "disabled"}")
            println("")

            val isFirstDeploy = !remoteCheck(host, user, "test -d $deployPath/bin")

            val secretsFile = file("gradle/profiles/${pn}.secrets")
            val secrets = if (isFirstDeploy) {
                loadOrGenerateSecrets(secretsFile, host, user, deployPath)
            } else {
                extractSecretsFromRemote(secretsFile, host, user, deployPath)
                    ?: throw GradleException(
                        "Cannot extract secrets from remote env.sh. " +
                        "Check if $deployPath/conf/env.sh exists on the server."
                    )
            }

            if (isFirstDeploy) {
                println("=== First Deploy ===")
                deployNew(host, user, deployPath, secrets, sslEnabled, sslPort, sslCert, sslKey, port, tcpPort, effectiveDefaultHttpPort)
            } else {
                println("=== Upgrade ===")
                deployUpgrade(host, user, deployPath, secrets, sslEnabled, sslPort, sslCert, sslKey, port, tcpPort, effectiveDefaultHttpPort)
            }

            healthCheck(host, user, deployPath, sslEnabled, port)
        }
    }
}

allProfiles.keys.forEach { pn ->
    val cap = capitalize(pn)
    registerDeployTask("deployServer$cap", pn)
}

// deployServer 作为活跃 profile 的别名
tasks.register("deployServer") {
    group = "deploy"
    description = "Deploy server (alias for deployServer${capitalize(profileName)})"
    dependsOn("deployServer${capitalize(profileName)}")
}
