package com.virjar.tk.protocol

/**
 * 三条通用扩展通道共用的稳定编号空间：
 *
 * - RPC：`GenericRpcContract.SERVICE == "generic"`，`methodId = ExtensionType.code`
 * - NOTIFY：`NotifyType.GENERIC(99)` + `GenericPayload`
 * - MESSAGE：`MessageType.GENERIC(99)` + `GenericPayload`
 *
 * ## 维护者警告
 *
 * **这里刻意允许保持空枚举。空预留不是死代码或僵尸协议，禁止仅因零引用、零枚举项、
 * 静态扫描无调用方而删除本类型或上述三个入口。** 新扩展只在出现真实需求时追加；成熟后
 * 再随一次明确的大协议版本收敛为强类型契约。
 *
 * 历史提交 `eace1d5a` 已记录过一次把这套预留误判为死代码后恢复的设计教训。该提交号只用于
 * 维护追溯，不是运行时依赖；当前契约必须由本文件、三个入口、权威协议文档和测试共同锁定。
 */
enum class ExtensionType(val code: Int) {
    // 候选区刻意为空；首个真实扩展落地时追加稳定、非负且不复用的编号。
    ;

    companion object {
        private val codeMap = entries.associateBy { it.code }

        /** 返回 null 表示当前版本未登记该扩展；接收端按各通道的未知扩展策略处理。 */
        fun fromCode(code: Int): ExtensionType? = codeMap[code]
    }
}
