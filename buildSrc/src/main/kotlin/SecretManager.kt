/**
 * Secret 管理：加载、生成、保存、从远程提取敏感配置。
 *
 * 从根 build.gradle.kts 提取。不依赖 Project 上下文。
 */

import java.io.File
import java.util.Properties

/**
 * 从远程 env.sh 提取 secrets（升级部署使用，远程 env.sh 是 source of truth）。
 */
fun extractSecretsFromRemote(
    secretsFile: File,
    host: String,
    user: String,
    deployPath: String,
    profileName: String
): Properties? {
    val envContent = remoteOutput(host, user, "cat $deployPath/conf/env.sh 2>/dev/null")
        ?: remoteOutput(host, user, "cat $deployPath/env.sh 2>/dev/null")

    if (envContent != null) {
        val secrets = Properties()
        val keyPattern = Regex("""^(DATABASE_PASSWORD|JWT_SECRET|SSL_KEYSTORE_PASSWORD|SSL_PRIVATE_KEY_PASSWORD)="?([^"]*)"?\s*$""")
        for (line in envContent.lines()) {
            val match = keyPattern.find(line) ?: continue
            secrets.setProperty(match.groupValues[1], match.groupValues[2])
        }
        if (secrets.isNotEmpty()) {
            saveSecrets(secretsFile, secrets, profileName)
            println("  Extracted secrets from remote env.sh, saved to ${secretsFile.name}")
            return secrets
        }
    }
    return null
}

/**
 * 加载或生成 secrets（首次部署使用）。
 */
fun loadOrGenerateSecrets(
    secretsFile: File,
    host: String,
    user: String,
    deployPath: String,
    profileName: String
): Properties {
    val secrets = Properties()

    // 1. 尝试从本地 .secrets 文件加载
    if (secretsFile.exists()) {
        secretsFile.inputStream().use { secrets.load(it) }
        println("  Loaded secrets from ${secretsFile.name}")
        return secrets
    }

    // 2. 尝试从远程 conf/env.sh 提取（首次部署但服务器已有配置的场景）
    val remote = extractSecretsFromRemote(secretsFile, host, user, deployPath, profileName)
    if (remote != null) return remote

    // 3. 全新部署：生成随机密码
    println("  Generating new secrets ...")
    secrets.setProperty("DATABASE_PASSWORD", genPassword())
    secrets.setProperty("JWT_SECRET", genPassword() + genPassword())
    secrets.setProperty("SSL_KEYSTORE_PASSWORD", genPassword())
    secrets.setProperty("SSL_PRIVATE_KEY_PASSWORD", genPassword())

    saveSecrets(secretsFile, secrets, profileName)
    println("  Generated new secrets, saved to ${secretsFile.name}")
    return secrets
}

fun saveSecrets(secretsFile: File, secrets: Properties, profileName: String) {
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
