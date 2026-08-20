package com.virjar.tk.rpc

import com.virjar.tk.protocol.ExtensionType

/**
 * RPC 通道的通用扩展保留契约。
 *
 * 当前 RPC wire 的 serviceId 是字符串，因此入口是 [SERVICE]（`"generic"`），不是已经废弃的
 * `ServiceId.GENERIC(99)` 表达；methodId 直接使用 [ExtensionType.code]。
 *
 * **本对象只锁定 wire 名称和编号映射，不代表 dispatcher 已实现。** 当前没有扩展项，也没有为
 * `"generic"` 注册服务端 handler，调用会按未知 service 拒绝。首个真实 RPC 扩展落地时，必须在
 * 会话所有的服务注册/分发边界注册 handler；不得用全局可变单例保存跨会话处理器。
 */
object GenericRpcContract {
    const val SERVICE: String = "generic"

    /** RPC methodId 与扩展稳定编号完全相同，不另建第二套编号表。 */
    fun methodId(extensionType: ExtensionType): Int = extensionType.code
}
