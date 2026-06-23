package com.virjar.tk.client

/**
 * 登录态持久化接口（跨平台）。
 *
 * 存储认证成功后的 uid + refreshToken，使 app 重启后能自动登录。
 * 各平台提供 actual 实现：
 * - Android: SharedPreferences（[com.virjar.tk.TokenStore]）
 * - Desktop: Properties 文件（[com.virjar.tk.DesktopTokenStore]）
 */
interface TokenStore {
    /** 保存登录态。 */
    fun save(uid: String, refreshToken: String)

    /** 读取已保存的 uid（null = 未登录过或已登出）。 */
    val savedUid: String?

    /** 读取已保存的 refreshToken。 */
    val savedToken: String?

    /** 是否有已保存的登录态。 */
    fun hasSavedLogin(): Boolean

    /** 清除登录态（登出 / token 失效 / 被踢）。 */
    fun clear()
}
