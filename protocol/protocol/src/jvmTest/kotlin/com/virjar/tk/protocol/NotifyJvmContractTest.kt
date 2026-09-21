package com.virjar.tk.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

/** JVM 服务端的反射校验；跨平台 wire 往返仍在 commonTest 执行。 */
class NotifyJvmContractTest {
    @Test
    fun `companion类名解析 - 契约表 reader 去后缀后等于样例类名`() {
        val samples = NotifyContractTest()
        for ((type, reader) in NotifyContracts.payloads) {
            val expected = NotifyContracts.expectedPayloadClassName(type, reader::class.java.name)
            val actual = samples.sampleOf(type)::class.java.name
            assertEquals(expected, actual, "契约 $type 的 reader 与 payload 类名不一致")
        }
    }
}
