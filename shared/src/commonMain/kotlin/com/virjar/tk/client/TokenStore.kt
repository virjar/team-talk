package com.virjar.tk.client

/**
 * 登录态持久化接口（跨平台）。
 *
 * 存储认证成功后的 uid + refreshToken，使 app 重启后能自动登录。
 *
 * 每个平台 UI 认证根先通过 [claimOwner] 获取持久化世代。新根会接管已有登录态；
 * 旧 Activity/窗口中延迟到达的认证回调不得保存或清除新 owner 的凭据。
 * 各平台提供 actual 实现：
 * - Android: SharedPreferences（[com.virjar.tk.TokenStore]）
 * - Desktop: Properties 文件（[com.virjar.tk.DesktopTokenStore]）
 */
interface TokenStore {
    /** 原子接管当前持久化登录态，并返回新 owner 世代及可用的已存凭据。 */
    fun claimOwner(): TokenStoreOwner

    /**
     * 保存一次认证返回的新 refresh token。
     * @return 新快照；owner 已过期时返回 null，不能覆盖新 owner 的凭据。
     */
    fun save(ownerGeneration: Long, uid: String, refreshToken: String): StoredLogin?

    /** 仅当 uid/token/owner 世代仍与 [expected] 完全一致时清除。 */
    fun compareAndClear(expected: StoredLogin): Boolean

    /** 查询世代是否仍拥有存储；用于阻止旧 controller 清理进程级访问令牌。 */
    fun isCurrentOwner(ownerGeneration: Long): Boolean
}

/** 某个 TokenStore owner 当前可比较的持久化登录快照。 */
data class StoredLogin(
    val uid: String,
    val refreshToken: String,
    val ownerGeneration: Long,
)

/** [TokenStore.claimOwner] 的原子结果。 */
data class TokenStoreOwner(
    val generation: Long,
    val savedLogin: StoredLogin?,
)
