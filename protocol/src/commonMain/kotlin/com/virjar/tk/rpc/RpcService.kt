package com.virjar.tk.rpc

/**
 * RPC IDL 注解与基础设施。
 *
 * ## IDL 规范（Kotlin interface 作为 IDL，KSP 生成双端代码）
 *
 * ```kotlin
 * @RpcService("message")                    // serviceId = 字符串（wire 直接传输）
 * interface MessageRpc {
 *     @RpcMethod(1)                         // 可省略：省略时按声明顺序 1,2,3... 分配
 *     suspend fun getHistory(chatId: String, fromSeq: Long, limit: Int): List<Message>
 *     suspend fun revokeMessage(chatId: String, serverSeq: Long)   // Unit 返回
 * }
 * ```
 *
 * ### 规则（违反 → KSP 编译错误）
 * - 方法必须是 `suspend`
 * - 参数类型白名单：String / Int / Long / Boolean / IProto 子类；禁止默认值
 * - 返回类型白名单：上述 + Unit / List<IProto 子类> / List<String>
 * - **methodId 稳定性**：新方法只追加到 interface 末尾；在中间插入方法必须显式
 *   `@RpcMethod(id)` 锁定，否则后续方法 id 整体漂移（wire 不兼容）
 *
 * ### 生成物（每 service 一个文件，rpc/processor 生成）
 * - `XxxRpcContract`：SERVICE/M_* 常量 + 每方法参数编解码（**唯一事实源**，两侧共用）
 * - `XxxRpcStub`（服务端）：abstract class(uid 成员)，dispatch 解码→调用实现→编码
 * - `XxxRpcProxy`（客户端）：实现 interface，encode→invoke→ensureSuccess→decode
 * - `RpcServiceRegistry`：双端注册表（客户端全自动；服务端 impl 工厂由 DI 注册）
 */

/** 声明一个 RPC 服务。name 即 wire 上的 serviceId（字符串，全局唯一）。 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class RpcService(val name: String)

/** 覆盖方法 id 分配（默认按 interface 声明顺序 1,2,3...）。 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class RpcMethod(val id: Int)
