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
    set("ktorVersion", "3.1.3")
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

// ── Profile 加载与校验 ──

val profileName = findProperty("buildProfile")?.toString() ?: "dev"
val profileFile = file("gradle/profiles/${profileName}.properties")

if (!profileFile.exists()) {
    val available = file("gradle/profiles").listFiles()
        ?.filter { it.name.endsWith(".properties") }
        ?.joinToString(", ") { it.name.removeSuffix(".properties") }
        ?: "none"
    throw GradleException("Profile '$profileName' not found. Available: $available")
}

val profileProps = java.util.Properties().apply {
    profileFile.inputStream().use { load(it) }
}

// 必填字段校验
val serverUrl = profileProps.getProperty("serverUrl")
    ?: throw GradleException("Profile '$profileName' must define 'serverUrl'")
if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
    throw GradleException("Profile '$profileName': serverUrl must start with http:// or https://")
}
profileProps.getProperty("tcpHost")
    ?: throw GradleException("Profile '$profileName' must define 'tcpHost'")
profileProps.getProperty("tcpPort")
    ?: throw GradleException("Profile '$profileName' must define 'tcpPort'")
profileProps.getProperty("buildProfile")
    ?: throw GradleException("Profile '$profileName' must define 'buildProfile'")

// 注入到 extra（子模块通过 rootProject.extra 读取）
profileProps.forEach { (k, v) -> extra.set(k.toString(), v) }

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

    // 2. 尝试从远程 conf/env.sh 提取（升级场景）
    val envContent = remoteOutput(host, user, "cat $deployPath/conf/env.sh 2>/dev/null")
        ?: remoteOutput(host, user, "cat $deployPath/env.sh 2>/dev/null")

    if (envContent != null) {
        println("  Extracting secrets from remote env.sh ...")
        val keyPattern = Regex("""^(DATABASE_PASSWORD|JWT_SECRET|MINIO_ACCESS_KEY|MINIO_SECRET_KEY|SSL_KEYSTORE_PASSWORD|SSL_PRIVATE_KEY_PASSWORD)="?([^"]*)"?\s*$""")
        for (line in envContent.lines()) {
            val match = keyPattern.find(line) ?: continue
            secrets.setProperty(match.groupValues[1], match.groupValues[2])
        }
        if (secrets.isNotEmpty()) {
            saveSecrets(secretsFile, secrets)
            println("  Saved extracted secrets to ${secretsFile.name}")
            return secrets
        }
    }

    // 3. 全新部署：生成随机密码
    println("  Generating new secrets ...")
    secrets.setProperty("DATABASE_PASSWORD", genPassword())
    secrets.setProperty("JWT_SECRET", genPassword() + genPassword())
    secrets.setProperty("MINIO_ACCESS_KEY", "teamtalk-" + genPassword().take(8))
    secrets.setProperty("MINIO_SECRET_KEY", genPassword())
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
        w.write("# MinIO\n")
        w.write("MINIO_ACCESS_KEY=${secrets.getProperty("MINIO_ACCESS_KEY")}\n")
        w.write("MINIO_SECRET_KEY=${secrets.getProperty("MINIO_SECRET_KEY")}\n\n")
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
    deployPath: String
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
    lines.add("#   minio=127.0.0.1:9000, minio.bucket=teamtalk")
    lines.add("")

    lines.add("# ── 数据库 ──")
    lines.add("DATABASE_USER=\"teamtalk\"")
    lines.add("DATABASE_PASSWORD=\"${secrets.getProperty("DATABASE_PASSWORD")}\"")
    lines.add("")

    lines.add("# ── 认证 ──")
    lines.add("JWT_SECRET=\"${secrets.getProperty("JWT_SECRET")}\"")
    lines.add("")

    lines.add("# ── MinIO ──")
    lines.add("MINIO_ACCESS_KEY=\"${secrets.getProperty("MINIO_ACCESS_KEY")}\"")
    lines.add("MINIO_SECRET_KEY=\"${secrets.getProperty("MINIO_SECRET_KEY")}\"")
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
ExecStartPre=/bin/bash -c 'cd $deployPath && export DB_PASSWORD="$${'$'}{DATABASE_PASSWORD}" && export MINIO_ACCESS_KEY="$${'$'}{MINIO_ACCESS_KEY}" && export MINIO_SECRET_KEY="$${'$'}{MINIO_SECRET_KEY}" && docker compose up -d'
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

fun healthCheck(host: String, user: String, deployPath: String, sslEnabled: Boolean) {
    println("")
    println("=== Health Check ===")

    val healthProtocol = if (sslEnabled) "https://127.0.0.1:443" else "http://127.0.0.1:8080"
    val healthFlag = if (sslEnabled) "-skf" else "-sf"

    print("  Waiting for TeamTalk Server ...")
    var retries = 0
    while (retries < 15) {
        if (remoteCheck(host, user, "curl $healthFlag $healthProtocol/ping &>/dev/null")) {
            println(" OK ($healthProtocol)")
            break
        }
        print(".")
        Thread.sleep(2000)
        retries++
    }
    if (retries == 15) println(" TIMEOUT")

    // TCP check
    if (remoteCheck(host, user, "nc -zv 127.0.0.1 5100 &>/dev/null")) {
        println("  TCP 5100: OK")
    } else {
        println("  TCP 5100: NOT READY")
    }

    // PostgreSQL check
    val pgContainer = deployPath.substringAfterLast("/")
    if (remoteCheck(host, user,
        "docker exec ${pgContainer}-postgres-1 pg_isready -U teamtalk &>/dev/null || " +
        "docker exec teamtalk-postgres-1 pg_isready -U teamtalk &>/dev/null"
    )) {
        println("  PostgreSQL: OK")
    } else {
        println("  PostgreSQL: NOT READY")
    }

    // MinIO check
    if (remoteCheck(host, user, "curl -sf http://127.0.0.1:9000/minio/health/live &>/dev/null")) {
        println("  MinIO: OK")
    } else {
        println("  MinIO: NOT READY")
    }
    println("")
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
    sslCert: String?, sslKey: String?
) {
    // Create directory structure
    remoteExec(host, user,
        "mkdir -p $deployPath/{data/pgdata,data/minio,data/rocksdb,data/lucene-index,data/logs,conf/ssl,conf,static/downloads}")

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
    val envShContent = generateEnvShContent(secrets, sslEnabled, sslPort, deployPath)
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
      POSTGRES_PASSWORD: $${'$'}{DB_PASSWORD}
      POSTGRES_DB: teamtalk
    volumes:
      - $deployPath/data/pgdata:/var/lib/postgresql/data

  minio:
    image: minio/minio
    command: server /data --console-address ":9001"
    restart: always
    healthcheck:
      test: ["CMD", "mc", "ready", "local"]
      interval: 10s
      timeout: 5s
      retries: 5
    ports:
      - "127.0.0.1:9000:9000"
      - "0.0.0.0:9001:9001"
    environment:
      MINIO_ROOT_USER: $${'$'}{MINIO_ACCESS_KEY}
      MINIO_ROOT_PASSWORD: $${'$'}{MINIO_SECRET_KEY}
    volumes:
      - $deployPath/data/minio:/data
""".trimIndent()
    val tmpDc = File.createTempFile("teamtalk-dc-", ".yml")
    tmpDc.deleteOnExit()
    tmpDc.writeText(dcContent)
    localExecSilent("scp", tmpDc.absolutePath, "$user@$host:$deployPath/docker-compose.yml")
    tmpDc.delete()

    // Start Docker infrastructure
    println("  Starting PostgreSQL + MinIO ...")
    remoteExec(host, user,
        "cd $deployPath && " +
        "set -a && source conf/env.sh && set +a && " +
        "export DB_PASSWORD=\"\$DATABASE_PASSWORD\" && " +
        "export MINIO_ACCESS_KEY=\"\$MINIO_ACCESS_KEY\" && " +
        "export MINIO_SECRET_KEY=\"\$MINIO_SECRET_KEY\" && " +
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

    // Wait for MinIO
    print("  Waiting for MinIO ...")
    retries = 0
    while (retries < 15) {
        if (remoteCheck(host, user, "curl -sf http://127.0.0.1:9000/minio/health/live &>/dev/null")) {
            println(" OK")
            break
        }
        print(".")
        Thread.sleep(2000)
        retries++
    }
    if (retries == 15) println(" TIMEOUT")

    // Create MinIO bucket
    println("  Initializing MinIO bucket ...")
    remoteExec(host, user,
        "set -a && source $deployPath/conf/env.sh && set +a && " +
        "docker run --rm --network host --entrypoint='' minio/mc " +
        "sh -c \"mc alias set local http://127.0.0.1:9000 \$MINIO_ACCESS_KEY \$MINIO_SECRET_KEY && " +
        "mc mb --ignore-existing local/teamtalk\" 2>/dev/null || true")

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
    sslCert: String?, sslKey: String?
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

    // Regenerate env.sh with proper format
    val envShContent = generateEnvShContent(secrets, sslEnabled, sslPort, deployPath)
    uploadEnvSh(envShContent, host, user, deployPath)

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

// ── 发布聚合任务 ──

tasks.register("buildRelease") {
    group = "release"
    description = "Build all release artifacts using the current profile"
    dependsOn(":server:buildServerDist", ":desktop:packageDistributionForCurrentOS", ":android:assembleRelease")
}

tasks.register("uploadRelease") {
    group = "release"
    description = "Build and upload release artifacts to the deploy server via SSH/SCP"
    dependsOn("buildRelease")

    doLast {
        val host = getExtra("deploy.host")
            ?: throw GradleException(
                "uploadRelease: profile '$profileName' does not define 'deploy.host'. " +
                    "Add deploy.host, deploy.user, deploy.path to your profile."
            )
        val user = getExtra("deploy.user") ?: "root"
        val path = getExtra("deploy.path") ?: "/opt/teamtalk"

        val remoteDir = "$path/static/downloads"
        println("Uploading to $user@$host:$remoteDir ...")

        remoteExec(host, user, "mkdir -p $remoteDir")

        // Upload desktop packages
        val desktopDir = file("desktop/build/compose/binaries/main")
        if (desktopDir.exists()) {
            desktopDir.walkTopDown()
                .filter { it.isFile && (it.extension in listOf("deb", "msi", "dmg")) }
                .forEach { pkg ->
                    println("  Uploading ${pkg.name} ...")
                    localExecSilent("scp", pkg.absolutePath, "$user@$host:$remoteDir/${pkg.name}")
                }
        }

        // Upload Android APK
        val apkDir = file("android/build/outputs/apk/release")
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

// ── 部署任务 ──

tasks.register("deployServer") {
    group = "release"
    description = "Build and deploy server to remote host via SSH"
    dependsOn(":server:buildServerDist")

    doLast {
        val host = getExtra("deploy.host")
            ?: throw GradleException(
                "deployServer: profile '$profileName' does not define 'deploy.host'. " +
                    "Add deploy.host, deploy.user, deploy.path to your profile."
            )
        val user = getExtra("deploy.user") ?: "root"
        val deployPath = getExtra("deploy.path") ?: "/opt/teamtalk"
        val sslEnabled = getExtra("server.ssl.enabled")?.toBoolean() ?: false
        val sslPort = getExtra("server.ssl.port") ?: "443"
        val sslCert = findProperty("sslCert")?.toString()
        val sslKey = findProperty("sslKey")?.toString()

        println("")
        println("=== TeamTalk Deploy (profile: $profileName) ===")
        println("  Target: $user@$host")
        println("  Path:   $deployPath")
        println("  SSL:    ${if (sslEnabled) "enabled (port $sslPort)" else "disabled"}")
        println("")

        // 1. Load or generate secrets
        val secretsFile = file("gradle/profiles/${profileName}.secrets")
        val secrets = loadOrGenerateSecrets(secretsFile, host, user, deployPath)

        // 2. Detect first deploy vs upgrade
        val isFirstDeploy = !remoteCheck(host, user, "test -d $deployPath/bin")

        if (isFirstDeploy) {
            println("=== First Deploy ===")
            deployNew(host, user, deployPath, secrets, sslEnabled, sslPort, sslCert, sslKey)
        } else {
            println("=== Upgrade ===")
            deployUpgrade(host, user, deployPath, secrets, sslEnabled, sslPort, sslCert, sslKey)
        }

        // 3. Health check
        healthCheck(host, user, deployPath, sslEnabled)
    }
}
