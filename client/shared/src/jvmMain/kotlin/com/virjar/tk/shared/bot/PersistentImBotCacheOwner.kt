package com.virjar.tk.shared.bot

import com.virjar.tk.shared.client.LocalCache
import com.virjar.tk.shared.client.DeploymentIdentity
import com.virjar.tk.shared.client.JvmLocalCacheCorruptionPolicy
import com.virjar.tk.shared.client.createJvmLocalCache
import java.io.File

/**
 * JVM/headless ImBot 的账号缓存 owner。
 *
 * [dataDir] 只代表此 agent/产品实例；实际数据库由 JVM cache factory 放在
 * `deployments/<fingerprint>/datasets/<datasetId>/users/<uid>/` 下。open 发生在服务端认证返回 uid 之后，因此注册失败或尚未取得 uid
 * 时不会创建猜测用户名、随机前缀等污染目录。
 */
class PersistentImBotCacheOwner(
    private val dataDir: File,
) : ImBotCacheOwner {
    override fun open(
        deploymentIdentity: DeploymentIdentity,
        datasetId: String,
        uid: String,
    ): LocalCache {
        require(uid.isNotBlank()) { "authenticated uid must not be blank" }
        return createJvmLocalCache(
            deploymentIdentity = deploymentIdentity,
            datasetId = datasetId,
            uid = uid,
            dataDir = dataDir,
            corruptionPolicy = JvmLocalCacheCorruptionPolicy.FAIL_PRESERVING,
        )
    }
}
