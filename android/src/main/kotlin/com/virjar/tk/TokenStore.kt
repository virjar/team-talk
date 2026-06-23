package com.virjar.tk

import android.content.Context
import android.content.SharedPreferences

/**
 * 登录态持久化（SharedPreferences）。
 *
 * 存储认证成功后的 uid + refreshToken，使 app 重启后能自动登录（直达主界面），
 * 而非每次都走登录页。refreshToken 有效期 30 天（服务端 TokenStore）。
 *
 * 清除时机：用户主动登出、token 失效（AUTH_FAILED）、被踢下线。
 */
class TokenStore(context: Context) : com.virjar.tk.client.TokenStore {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("teamtalk_auth", Context.MODE_PRIVATE)

    /** 保存登录态。 */
    override fun save(uid: String, refreshToken: String) {
        prefs.edit().putString(KEY_UID, uid).putString(KEY_TOKEN, refreshToken).apply()
    }

    /** 读取已保存的 uid（null = 未登录过或已登出）。 */
    override val savedUid: String? get() = prefs.getString(KEY_UID, null)

    /** 读取已保存的 refreshToken。 */
    override val savedToken: String? get() = prefs.getString(KEY_TOKEN, null)

    /** 是否有已保存的登录态。 */
    override fun hasSavedLogin(): Boolean = !savedUid.isNullOrEmpty() && !savedToken.isNullOrEmpty()

    /** 清除登录态（登出 / token 失效 / 被踢）。 */
    override fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_UID = "uid"
        private const val KEY_TOKEN = "refresh_token"
    }
}
