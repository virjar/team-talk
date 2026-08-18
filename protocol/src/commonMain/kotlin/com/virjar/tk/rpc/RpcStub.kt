package com.virjar.tk.rpc

/**
 * 服务端 Stub 基类。生成的 `XxxRpcStub` 继承本类。
 *
 * uid（认证用户）收敛为成员——服务端实现类的方法签名不含 uid，
 * 通过 `this.uid` 获取调用者上下文（AIDL 风格）。
 */
abstract class RpcStub(val uid: String) {
    /** 方法派发：解码参数 → 调用实现方法 → 编码返回值。由生成器实现。 */
    abstract suspend fun dispatch(methodId: Int, payload: ByteArray?): ByteArray
}
