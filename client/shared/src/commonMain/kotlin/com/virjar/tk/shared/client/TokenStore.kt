package com.virjar.tk.shared.client

import kotlinx.coroutines.flow.StateFlow

/**
 * 登录态持久化接口（跨平台）。
 *
 * 存储认证成功后的 uid + refreshToken + datasetId，并与 canonical TCP+HTTP deployment 指纹绑定，使 app
 * 仅能在同一部署重启自动登录；旧格式或错部署凭据不得进入网络认证。
 *
 * 每个平台 UI 认证根先通过 [claimOwner] 获取持久化世代。新根会接管已有登录态；
 * 旧 Activity/窗口中延迟到达的认证回调不得保存或清除新 owner 的凭据。
 * 各平台提供 actual 实现：
 * - Android: SharedPreferences（[com.virjar.tk.android.TokenStore]）
 * - Desktop: Properties 文件（[com.virjar.tk.desktop.DesktopTokenStore]）
 */
interface TokenStore {
    /** 本存储实例接受的规范 TCP+HTTP 部署。 */
    val deploymentIdentity: DeploymentIdentity

    /**
     * [claimOwner] 变更的持久文件/偏好槽的进程内身份。共享同一凭据槽的不同部署也必须共享该
     * 命名空间，这样它们在 UI 根替换期间的阻塞 claim 不会乱序完成。
     */
    val ownerClaimNamespace: String get() = deploymentIdentity.fingerprint

    /**
     * [deploymentIdentity] 的进程可观察、单调拒绝事实。当旧 transport 观察到服务器拒绝时，
     * 后继 UI owner 可能已经存在，因此仅靠 [TokenStoreOwner] 的 claim 时刻快照不足以立即
     * 隔断该后继者。
     */
    val rejectedProtocolVersions: StateFlow<Set<Int>>

    /** 原子接管当前持久化登录态，并返回新 owner 世代及可用的已存凭据。 */
    fun claimOwner(): TokenStoreOwner

    /**
     * 确认并保存一次认证返回的 refresh bearer。后台 refresh 通常返回同值，实现可在完整 owner
     * CAS 后跳过冗余写盘；密码登录可能返回替换值，必须在发布认证身份前持久化。
     * @return 已准入快照；owner 已过期时返回 null，不能覆盖新 owner 的凭据。
     */
    fun save(
        ownerGeneration: Long,
        uid: String,
        refreshToken: String,
        datasetId: String,
    ): StoredLogin?

    /** 仅当 uid/token/owner 世代仍与 [expected] 完全一致时清除。 */
    fun compareAndClear(expected: StoredLogin): Boolean

    /** 查询世代是否仍拥有存储；用于阻止旧 controller 清理进程级访问令牌。 */
    fun isCurrentOwner(ownerGeneration: Long): Boolean

    /**
     * 单调记录当前 deployment 已明确拒绝的客户端协议版本，并保留凭据供新版本接管。
     * 这是 deployment + wire-version 事实，不属于账号 token owner；实现必须先发布到
     * [rejectedProtocolVersions]，再尝试持久化。同 deployment 的旧认证回调可以补写，
     * deployment 已切换时返回 false，但旧 deployment 的进程内观察者仍会收到事实。
     */
    fun markProtocolVersionRejected(protocolVersion: Int): Boolean
}

/** 某个 TokenStore owner 当前可比较的持久化登录快照。 */
data class StoredLogin(
    val uid: String,
    val refreshToken: String,
    val ownerGeneration: Long,
    val deploymentFingerprint: String,
    val datasetId: String,
) {
    init {
        com.virjar.tk.protocol.payload.SyncDatasetIdPolicy.requireValid(datasetId)
    }
}

/** [TokenStore.claimOwner] 的原子结果。 */
data class TokenStoreOwner(
    val generation: Long,
    val savedLogin: StoredLogin?,
    /** 当前 deployment 曾明确拒绝的客户端协议版本；新协议版本不会被旧栅栏误伤。 */
    val rejectedProtocolVersions: Set<Int> = emptySet(),
)
