package com.virjar.tk.app.client

import com.virjar.tk.shared.client.AccountDataOwner
import com.virjar.tk.shared.client.AuthenticationFailure
import com.virjar.tk.shared.client.AuthenticationFailureKind
import com.virjar.tk.shared.client.DeploymentIdentity
import com.virjar.tk.shared.client.StoredLogin

/** 密码登录用服务器确认的身份；旧服务器的刷新拒绝可用这次凭据的精确账号范围。 */
internal fun bannedAccountDataOwner(
    deployment: DeploymentIdentity,
    failure: AuthenticationFailure?,
    saved: StoredLogin?,
): AccountDataOwner? {
    if (failure?.kind != AuthenticationFailureKind.ACCOUNT_BANNED) return null
    val uid = failure.accountUid
    val datasetId = failure.datasetId
    if (uid == null && datasetId == null) {
        return saved?.takeIf { it.deploymentFingerprint == deployment.fingerprint }?.let {
            AccountDataOwner(it.deploymentFingerprint, it.datasetId, it.uid)
        }
    }
    // 半份身份不是旧协议的空身份，不能静默借用另一个来源补齐破坏性操作的范围。
    if (uid == null || datasetId == null) return null
    // 显式密码登录在提交前已清除 saved；存在 saved 时响应必须属于这次 refresh 的固定账号。
    if (saved != null && (saved.deploymentFingerprint != deployment.fingerprint || saved.uid != uid)) return null
    return runCatching {
        AccountDataOwner(deployment.fingerprint, datasetId, uid)
    }.getOrNull()
}
