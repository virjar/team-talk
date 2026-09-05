package com.virjar.tk.protocol.body

import com.virjar.tk.protocol.IProto

/**
 * 消息体封闭层次。所有直接实现都必须位于 body 包，新增类型时编译器会提示
 * 需要同步处理的穷举 when；wire 解码入口统一登记在 [MessageBodyRegistry]。
 */
sealed interface MessageBody : IProto
