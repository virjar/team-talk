package com.virjar.tk.integration

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.condition.EnabledIf
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Message API 集成测试。
 * 消息拉取端点已迁移到 TCP（HISTORY_LOAD），仅保留 revoke/edit 的 HTTP 测试。
 */
@EnabledIf("isIntegrationTestsEnabled")
class MessageApiTest {

    companion object {
        @JvmStatic
        fun isIntegrationTestsEnabled(): Boolean =
            System.getenv("RUN_INTEGRATION_TESTS")?.toBoolean() == true
    }

    private val json = Json { ignoreUnknownKeys = true }
}
