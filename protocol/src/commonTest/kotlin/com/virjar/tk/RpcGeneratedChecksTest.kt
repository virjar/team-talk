package com.virjar.tk

import com.virjar.tk.rpc.gen.RpcServiceRegistry
import kotlin.test.Test

/**
 * 生成物自检：全部 RPC Contract 的参数编解码 round-trip。
 * 由 rpc-processor 生成的 verifyRoundTrip 聚合（新增 service 自动纳入）。
 */
class RpcGeneratedChecksTest {
    @Test
    fun `全部生成物参数编解码 round-trip`() {
        RpcServiceRegistry.verifyAll()
    }
}
