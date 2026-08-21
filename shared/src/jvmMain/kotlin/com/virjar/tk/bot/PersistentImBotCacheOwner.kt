package com.virjar.tk.bot

import com.virjar.tk.client.LocalCache
import com.virjar.tk.client.DeploymentIdentity
import com.virjar.tk.client.createDesktopLocalCache
import java.io.File

/**
 * JVM/headless ImBot 的账号缓存 owner。
 *
 * [dataDir] 只代表此 agent/产品实例；实际数据库由 Desktop cache factory 放在
 * `deployments/<fingerprint>/users/<uid>/` 下。open 发生在服务端认证返回 uid 之后，因此注册失败或尚未取得 uid
 * 时不会创建猜测用户名、随机前缀等污染目录。
 */
class PersistentImBotCacheOwner(
    private val dataDir: File,
) : ImBotCacheOwner {
    override fun open(deploymentIdentity: DeploymentIdentity, uid: String): LocalCache {
        require(uid.isNotBlank()) { "authenticated uid must not be blank" }
        return createDesktopLocalCache(deploymentIdentity, uid, dataDir)
    }
}
