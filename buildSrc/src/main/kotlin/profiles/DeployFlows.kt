package profiles

/**
 * 服务端部署流程入口：
 * - [deployNew]: 首次部署（包含 Docker 基础设施搭建 + systemd 注册）
 * - [deployUpgrade]: 升级部署（保留数据卷，备份旧版本）
 *
 * 不依赖 Project 上下文，通过参数传入所有依赖。
 */

import java.io.File
import java.util.Properties
import org.gradle.api.GradleException

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
    remoteExec(
        host, user,
        "mkdir -p $deployPath/{data/pgdata,data/rocksdb,data/lucene-index,data/file-store/rocksdb,data/file-store/files,data/file-store/tmp,data/logs,conf/ssl,conf,static/downloads}"
    )

    println("  Uploading server distribution ...")
    val distDir = File(rootDir, "server/build/install/teamtalk-server")
    localExec(
        "rsync", "-avz", "--delete",
        "--exclude=data", "--exclude=logs", "--exclude=env.sh",
        "--exclude=docker-compose.yml", "--exclude=conf/ssl", "--exclude=conf/env.sh",
        "-e", "ssh -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new",
        "$distDir/", "$user@$host:$deployPath/"
    )

    println("  Generating env.sh ...")
    val envShContent = generateEnvShContent(
        secrets,
        sslEnabled,
        sslPort,
        deployPath,
        profileName,
        httpPort,
        tcpPort,
        effectiveDefaultHttpPort
    )
    uploadEnvSh(envShContent, host, user, deployPath)

    if (sslEnabled && sslCert != null && sslKey != null) {
        handleSsl(rootDir, host, user, deployPath, sslCert, sslKey, secrets)
    }

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

    println("  Starting PostgreSQL ...")
    remoteExec(
        host, user,
        "cd $deployPath && " +
                "set -a && source conf/env.sh && set +a && " +
                "export DB_PASSWORD=\"\$DATABASE_PASSWORD\" && " +
                "docker compose up -d"
    )

    print("  Waiting for PostgreSQL ...")
    val pgContainer = deployPath.substringAfterLast("/")
    var retries = 0
    while (retries < 30) {
        if (remoteCheck(
                host, user,
                "docker exec ${pgContainer}-postgres-1 pg_isready -U teamtalk &>/dev/null || " +
                        "docker exec teamtalk-postgres-1 pg_isready -U teamtalk &>/dev/null"
            )
        ) {
            println(" OK")
            break
        }
        print(".")
        Thread.sleep(2000)
        retries++
    }
    if (retries == 30) throw GradleException("PostgreSQL startup timeout")

    ensureDbUser(host, user, deployPath, secrets.getProperty("DATABASE_PASSWORD"))

    println("  Registering systemd service ...")
    registerSystemd(host, user, deployPath)

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
    if (remoteCheck(host, user, "systemctl is-active --quiet teamtalk 2>/dev/null")) {
        println("  Stopping TeamTalk Server ...")
        remoteExec(host, user, "systemctl stop teamtalk || true")
    }

    println("  Cleaning residual processes ...")
    remoteExec(host, user, "pkill -f 'com.virjar.tk.ApplicationKt' 2>/dev/null || true")
    Thread.sleep(1000)

    println("  Backing up current version ...")
    remoteExec(host, user, "rm -rf ${deployPath}.bak && cp -r $deployPath ${deployPath}.bak || true")

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

    println("  Checking env.sh location ...")
    val hasNewEnvSh = remoteCheck(host, user, "test -f $deployPath/conf/env.sh")
    val hasOldEnvSh = remoteCheck(host, user, "test -f $deployPath/env.sh")
    if (!hasNewEnvSh && hasOldEnvSh) {
        println("  Migrating env.sh -> conf/env.sh ...")
        remoteExec(host, user, "cp $deployPath/env.sh $deployPath/conf/env.sh && chmod 600 $deployPath/conf/env.sh")
    }

    println("  Syncing port configuration from profile ...")
    val envShContent = generateEnvShContent(
        secrets,
        sslEnabled,
        sslPort,
        deployPath,
        profileName,
        httpPort,
        tcpPort,
        effectiveDefaultHttpPort
    )
    uploadEnvSh(envShContent, host, user, deployPath)

    ensureDbUser(host, user, deployPath, secrets.getProperty("DATABASE_PASSWORD"))

    if (sslEnabled && sslCert != null && sslKey != null) {
        println("  Updating SSL certificate ...")
        handleSsl(rootDir, host, user, deployPath, sslCert, sslKey, secrets)
    }

    println("  Re-registering systemd service ...")
    registerSystemd(host, user, deployPath)

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
