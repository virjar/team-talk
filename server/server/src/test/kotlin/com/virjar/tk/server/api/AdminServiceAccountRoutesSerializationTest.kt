package com.virjar.tk.server.api

import com.virjar.tk.server.integration.IntegrationTestExtension
import com.virjar.tk.server.runtime.ServiceBroadcastRuntime
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 管理台服务号路由的响应契约：ServiceBroadcastEntry 必须能经 kotlinx 序列化输出。
 * 曾因缺 @Serializable 导致 POST /broadcasts 响应 500、台账非空时 GET 同样失败
 * （空列表不需要元素序列化器，恰好掩盖问题）——本测试挂载真实路由与真实服务回归该行为。
 */
class AdminServiceAccountRoutesSerializationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `ledger response serializes broadcast entries over admin service routes`() = testApplication {
        // launch 传空操作：只写台账行验证响应序列化，不派发业务广播。
        ctx.serviceAccountMessages.createBroadcast("管理台序列化回归广播", "test", {})

        application {
            install(ContentNegotiation) { json() }
            routing {
                route("/api/admin") {
                    adminServiceAccountRoutes(
                        ctx.serviceAccountMessages,
                        ServiceBroadcastRuntime(runBroadcast = {}),
                    )
                }
            }
        }

        val ledger = client.get("/api/admin/service/broadcasts")
        assertEquals(HttpStatusCode.OK, ledger.status)
        val entries = Json.parseToJsonElement(ledger.bodyAsText())
            .jsonObject.getValue("broadcasts").jsonArray
        assertTrue(entries.isNotEmpty())
        val entry = entries.first().jsonObject
        assertEquals("管理台序列化回归广播", entry.getValue("markdown").jsonPrimitive.content)
        assertTrue(entry.getValue("broadcastId").jsonPrimitive.content.isNotEmpty())
        assertEquals("test", entry.getValue("createdBy").jsonPrimitive.content)
    }
}
