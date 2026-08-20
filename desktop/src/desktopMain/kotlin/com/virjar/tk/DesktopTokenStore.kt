package com.virjar.tk

import com.virjar.tk.client.StoredLogin
import com.virjar.tk.client.TokenStoreOwner
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * Desktop 登录态持久化（Properties 文件）。
 *
 * 对齐 Android [TokenStore]：存储认证成功后的 uid + refreshToken，
 * 使 Desktop 重启后能自动登录（直达主界面）。token 文件在 [dataDir]/auth.properties。
 *
 * 清除时机：用户主动登出、token 失效（AUTH_FAILED）。
 */
class DesktopTokenStore(dataDir: File) : com.virjar.tk.client.TokenStore {
    private val file = File(dataDir, "auth.properties")

    override fun claimOwner(): TokenStoreOwner = synchronized(PROCESS_LOCK) {
        val props = readProps() ?: Properties()
        val generation = nextOwnerGeneration(props.ownerGeneration())
        props.setProperty(KEY_OWNER_GENERATION, generation.toString())
        normalizeCredentials(props)
        writeProps(props)
        TokenStoreOwner(generation, props.toStoredLogin(generation))
    }

    override fun save(ownerGeneration: Long, uid: String, refreshToken: String): StoredLogin? =
        synchronized(PROCESS_LOCK) {
            require(uid.isNotBlank()) { "uid 不能为空" }
            require(refreshToken.isNotBlank()) { "refreshToken 不能为空" }
            val props = readProps() ?: Properties()
            if (props.ownerGeneration() != ownerGeneration) return@synchronized null
            props.setProperty(KEY_UID, uid)
            props.setProperty(KEY_TOKEN, refreshToken)
            writeProps(props)
            StoredLogin(uid, refreshToken, ownerGeneration)
        }

    override fun compareAndClear(expected: StoredLogin): Boolean = synchronized(PROCESS_LOCK) {
        val props = readProps() ?: return@synchronized false
        val matches = props.ownerGeneration() == expected.ownerGeneration &&
            props.getProperty(KEY_UID) == expected.uid &&
            props.getProperty(KEY_TOKEN) == expected.refreshToken
        if (!matches) return@synchronized false
        props.remove(KEY_UID)
        props.remove(KEY_TOKEN)
        writeProps(props)
        true
    }

    override fun isCurrentOwner(ownerGeneration: Long): Boolean = synchronized(PROCESS_LOCK) {
        readProps()?.ownerGeneration() == ownerGeneration
    }

    /** Temp + fsync + atomic replace: refresh token 轮换不能在进程退出时落回旧值。 */
    private fun writeProps(props: Properties) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temp).use { output ->
            props.store(output, "TeamTalk auth")
            output.flush()
            output.fd.sync()
        }
        try {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun readProps(): Properties? = try {
        if (file.exists()) Properties().apply { file.inputStream().use { load(it) } } else null
    } catch (_: Exception) {
        null
    }

    private fun Properties.ownerGeneration(): Long =
        getProperty(KEY_OWNER_GENERATION)?.toLongOrNull() ?: 0L

    private fun normalizeCredentials(props: Properties) {
        if (props.getProperty(KEY_UID).isNullOrBlank() || props.getProperty(KEY_TOKEN).isNullOrBlank()) {
            props.remove(KEY_UID)
            props.remove(KEY_TOKEN)
        }
    }

    private fun Properties.toStoredLogin(ownerGeneration: Long): StoredLogin? {
        val uid = getProperty(KEY_UID)?.takeIf { it.isNotBlank() } ?: return null
        val token = getProperty(KEY_TOKEN)?.takeIf { it.isNotBlank() } ?: return null
        return StoredLogin(uid, token, ownerGeneration)
    }

    companion object {
        private val PROCESS_LOCK = Any()
        private const val KEY_OWNER_GENERATION = "owner_generation"
        private const val KEY_UID = "uid"
        private const val KEY_TOKEN = "refresh_token"

        private fun nextOwnerGeneration(current: Long): Long =
            (current + 1L).takeUnless { it == 0L } ?: 1L
    }
}
