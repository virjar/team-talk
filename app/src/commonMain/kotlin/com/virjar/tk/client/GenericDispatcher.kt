package com.virjar.tk.client

import com.virjar.tk.protocol.ExtensionType
import com.virjar.tk.protocol.payload.GenericPayload
import com.virjar.tk.protocol.PacketBuffer
import io.netty.buffer.Unpooled

/**
 * 推送扩展处理器接口。
 *
 * 当服务端通过 NotifyType.GENERIC(99) 推送扩展事件时，
 * 客户端 EventProcessor 解析 GenericPayload，按 extensionType 分发到注册的处理器。
 */
interface GenericNotifyHandler {
    fun handle(data: ByteArray?)
}

/**
 * 消息扩展处理器接口。
 *
 * 当收到 MessageType.GENERIC(99) 的消息时，
 * 客户端按 extensionType 分发到注册的处理器。
 */
interface GenericMessageHandler {
    fun handle(data: ByteArray?)
}

/**
 * 客户端通用扩展分发注册表。
 * 包含推送扩展和消息扩展两个注册表。
 *
 * 用法：
 * ```
 * GenericDispatcher.registerNotify(ExtensionType.MY_FEATURE, MyNotifyHandler())
 * GenericDispatcher.registerMessage(ExtensionType.MY_FEATURE, MyMsgHandler())
 * ```
 *
 * 新增扩展类型只需：1) 在 ExtensionType 枚举追加值；2) 实现处理器；3) 注册。
 * 不需要修改 EventProcessor 或已有分发逻辑。
 */
object GenericDispatcher {
    private val notifyHandlers = mutableMapOf<Int, GenericNotifyHandler>()
    private val messageHandlers = mutableMapOf<Int, GenericMessageHandler>()

    fun registerNotify(type: ExtensionType, handler: GenericNotifyHandler) {
        notifyHandlers[type.code] = handler
    }

    fun registerMessage(type: ExtensionType, handler: GenericMessageHandler) {
        messageHandlers[type.code] = handler
    }

    /** 分发推送扩展。未注册的扩展静默忽略（前向兼容）。 */
    fun dispatchNotify(extensionType: Int, data: ByteArray?) {
        notifyHandlers[extensionType]?.handle(data)
    }

    /** 分发消息扩展。未注册的扩展静默忽略（前向兼容）。 */
    fun dispatchMessage(extensionType: Int, data: ByteArray?) {
        messageHandlers[extensionType]?.handle(data)
    }
}
