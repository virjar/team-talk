package com.virjar.tk

import java.io.File
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

    /** 保存登录态。 */
    override fun save(uid: String, refreshToken: String) {
        val props = Properties().apply {
            setProperty(KEY_UID, uid)
            setProperty(KEY_TOKEN, refreshToken)
        }
        file.parentFile?.mkdirs()
        file.outputStream().use { props.store(it, "TeamTalk auth") }
    }

    /** 读取已保存的 uid（null = 未登录过或已登出）。 */
    override val savedUid: String? get() = readProps()?.getProperty(KEY_UID)

    /** 读取已保存的 refreshToken。 */
    override val savedToken: String? get() = readProps()?.getProperty(KEY_TOKEN)

    /** 是否有已保存的登录态。 */
    override fun hasSavedLogin(): Boolean = !savedUid.isNullOrEmpty() && !savedToken.isNullOrEmpty()

    /** 清除登录态（登出 / token 失效）。 */
    override fun clear() {
        file.delete()
    }

    private fun readProps(): Properties? = try {
        if (file.exists()) Properties().apply { file.inputStream().use { load(it) } } else null
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val KEY_UID = "uid"
        private const val KEY_TOKEN = "refresh_token"
    }
}
