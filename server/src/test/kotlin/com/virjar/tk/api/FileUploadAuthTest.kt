package com.virjar.tk.api

import com.virjar.tk.domain.auth.TokenStore
import com.virjar.tk.infra.storage.FileStore
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.client.request.forms.*
import io.ktor.http.content.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 上传接口鉴权：Bearer accessToken（TokenStore 校验），X-Uid 伪造通道已封死。
 */
class FileUploadAuthTest {

    private fun testFileStore(): FileStore = FileStore(
        File("/tmp/tk-upload-auth-${System.nanoTime()}/rocksdb").absolutePath,
        File("/tmp/tk-upload-auth-${System.nanoTime()}/files").absolutePath,
    ).also { it.init() }

    @Test
    fun `无 token 上传被拒 401`() = testApplication {
        val tokenStore = TokenStore(File("/tmp/tk-upload-auth-tokens-${System.nanoTime()}").absolutePath)
        application { routing { fileRoutes(testFileStore(), tokenStore) } }
        val resp = client.post("/api/v1/files/upload")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
        assertTrue(resp.bodyAsText().contains("token"))
    }

    @Test
    fun `伪造 X-Uid 不再被接受`() = testApplication {
        val tokenStore = TokenStore(File("/tmp/tk-upload-auth-tokens2-${System.nanoTime()}").absolutePath)
        application { routing { fileRoutes(testFileStore(), tokenStore) } }
        val resp = client.post("/api/v1/files/upload") {
            header("X-Uid", "victim-uid")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status, "X-Uid 通道必须已封死")
    }

    @Test
    fun `有效 accessToken 可上传`() = testApplication {
        val tokenStore = TokenStore(File("/tmp/tk-upload-auth-tokens3-${System.nanoTime()}").absolutePath)
        val (access, _) = tokenStore.generateTokens("real-uid", "dev-1", 0)
        application { routing { fileRoutes(testFileStore(), tokenStore) } }
        val resp = client.post("/api/v1/files/upload") {
            header(HttpHeaders.Authorization, "Bearer $access")
            setBody(MultiPartFormDataContent(formData {
                append("file", byteArrayOf(1, 2, 3), Headers.build {
                    append(HttpHeaders.ContentType, "application/octet-stream")
                    append(HttpHeaders.ContentDisposition, "filename=\"t.bin\"")
                })
            }))
        }
        assertEquals(HttpStatusCode.OK, resp.status, "有效 token 上传应成功: ${resp.bodyAsText()}")
        assertTrue(resp.bodyAsText().contains("\"path\""), "响应含 path: ${resp.bodyAsText()}")
    }

    @Test
    fun `无效 token 被拒 401`() = testApplication {
        val tokenStore = TokenStore(File("/tmp/tk-upload-auth-tokens4-${System.nanoTime()}").absolutePath)
        application { routing { fileRoutes(testFileStore(), tokenStore) } }
        val resp = client.post("/api/v1/files/upload") {
            header(HttpHeaders.Authorization, "Bearer forged-token")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }
}
