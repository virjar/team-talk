package com.virjar.tk.protocol.dispatcher

import com.virjar.tk.protocol.ExtensionType

/**
 * RPC 扩展处理器接口。每个扩展类型实现此接口，注册到 [GenericRpcRegistry]。
 *
 * 扩展 RPC 通过 ServiceId.GENERIC(99) + methodId(=ExtensionType.code) 进入，
 * methodId 被解释为 extensionType 进行路由分发。
 */
interface GenericRpcHandler {
    /**
     * @param uid 调用者 UID
     * @param data 扩展的 opaque 载荷字节（可为 null）
     * @return 响应字节（可为空 ByteArray(0) 表示无返回值）
     */
    suspend fun handle(uid: String, data: ByteArray?): ByteArray
}

/**
 * RPC 扩展路由注册表。服务端启动时注册各扩展处理器。
 *
 * 用法：
 * ```
 * GenericRpcRegistry.register(ExtensionType.MY_FEATURE, MyFeatureHandler())
 * ```
 *
 * 新增扩展类型只需：1) 在 ExtensionType 枚举追加值；2) 实现 GenericRpcHandler；3) 注册。
 * 不需要修改 RpcDispatcher 或已有 RouteHandler。
 */
object GenericRpcRegistry {
    private val handlers = mutableMapOf<Int, GenericRpcHandler>()

    fun register(type: ExtensionType, handler: GenericRpcHandler) {
        handlers[type.code] = handler
    }

    suspend fun dispatch(uid: String, extensionType: Int, data: ByteArray?): ByteArray {
        val handler = handlers[extensionType]
            ?: throw IllegalArgumentException("Unknown generic RPC extension: $extensionType")
        return handler.handle(uid, data)
    }
}

/**
 * RPC 服务路由：ServiceId.GENERIC 入口。
 * methodId 被解释为 ExtensionType.code，payload 直接作为 opaque data 传给处理器。
 */
class GenericRouteHandler {
    suspend fun route(uid: String, methodId: Int, payload: ByteArray?): ByteArray {
        // methodId = ExtensionType.code，payload = opaque data
        return GenericRpcRegistry.dispatch(uid, methodId, payload)
    }
}
