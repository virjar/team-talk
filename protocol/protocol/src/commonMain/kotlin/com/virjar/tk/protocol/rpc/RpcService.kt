package com.virjar.tk.protocol.rpc

/**
 * RPC IDL 注解与基础设施。
 *
 * ## IDL 规范（Kotlin interface 作为 IDL，KSP 生成双端代码）
 *
 * ```kotlin
 * @RpcService("message")                    // serviceId = 字符串（wire 直接传输）
 * interface MessageRpc {
 *     @RpcMethod(1)                         // 必填：显式锁定当前协议基线的 methodId
 *     suspend fun getHistory(chatId: String, fromSeq: Long, limit: Int): List<Message>
 *     suspend fun revokeMessage(chatId: String, serverSeq: Long)   // Unit 返回
 * }
 * ```
 *
 * ### 规则（违反 → KSP 编译错误）
 * - 方法必须是 `suspend`
 * - 参数类型白名单：String / Int / Long / Boolean / IProto 子类；禁止默认值
 * - 返回类型白名单：上述 + Unit / List<IProto 子类> / List<String>
 * - 每个方法必须显式声明唯一、正数的 `@RpcMethod(id)`；声明顺序不参与 wire 编号
 * - 已发布版本退役的编号用 `@RpcReservedMethodIds` 保留，processor 拒绝在当前基线重新分配
 * - `@SinceProtocol(minor)` / `@RemovedInProtocol(minor)` 是唯一版本窗来源；方法继承服务标注，
 *   未标注的最初基线类型视为 minor 0。新增方法必须显式标注较新的 minor。
 * - removed 是排他的停止支持点；minimum 到达该点时必须移除实现并保留 ID，不能复用编号。
 * - wire-baseline.tsv 冻结本 major 的 ID、IDL 参数/返回类型及 IProto 构造字段结构；
 *   它不证明手写 codec 方法体未改语义，关键线格式仍需维护 golden 测试。
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

/** 保留已退役的 wire methodId；发布后的编号不得在同一服务的兼容演进中重新分配。 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class RpcReservedMethodIds(vararg val ids: Int)

/** 显式分配 wire methodId；每个 RPC 方法都必须声明。 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class RpcMethod(val id: Int)
