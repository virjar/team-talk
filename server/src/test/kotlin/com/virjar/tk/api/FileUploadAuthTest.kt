package com.virjar.tk.api

import com.virjar.tk.domain.attachment.AttachmentAccess
import com.virjar.tk.domain.auth.AccessTokenValidator
import com.virjar.tk.infra.storage.FileStore
import com.virjar.tk.repository.FileOps
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
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
 * 上传接口鉴权：Bearer accessToken（权威凭据校验），X-Uid 伪造通道已封死。
 */
class FileUploadAuthTest {

    private fun testFileStore(): FileStore = FileStore(
        File("/tmp/tk-upload-auth-${System.nanoTime()}/rocksdb").absolutePath,
        File("/tmp/tk-upload-auth-${System.nanoTime()}/files").absolutePath,
    ).also { it.init() }

    private fun Application.installTestFileRoutes(
        fileStore: FileStore,
        accessTokens: AccessTokenValidator,
        access: AttachmentAccess = AttachmentAccess { _, _ -> true },
    ) {
        monitor.subscribe(ApplicationStopped) {
            fileStore.close()
        }
        routing { fileRoutes(fileStore, accessTokens, access) }
    }

    @Test
    fun `无 token 上传被拒 401`() = testApplication {
        val fileStore = testFileStore()
        application { installTestFileRoutes(fileStore, TestAccessTokenValidator()) }
        val resp = client.post("/api/v1/files/upload")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
        assertTrue(resp.bodyAsText().contains("token"))
    }

    @Test
    fun `伪造 X-Uid 不再被接受`() = testApplication {
        val fileStore = testFileStore()
        application { installTestFileRoutes(fileStore, TestAccessTokenValidator()) }
        val resp = client.post("/api/v1/files/upload") {
            header("X-Uid", "victim-uid")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status, "X-Uid 通道必须已封死")
    }

    @Test
    fun `有效 accessToken 可上传`() = testApplication {
        val access = "valid-upload-token"
        val accessTokens = TestAccessTokenValidator.single(access, "real-uid", "dev-1")
        val fileStore = testFileStore()
        application { installTestFileRoutes(fileStore, accessTokens) }
        val resp = client.post("/api/v1/files/upload") {
            header(HttpHeaders.Authorization, "Bearer $access")
            setBody(MultiPartFormDataContent(formData {
                append("file", byteArrayOf(1, 2, 3), Headers.build {
                    append(HttpHeaders.ContentType, "application/octet-stream")
                    append(HttpHeaders.ContentDisposition, "filename=\"t.bin\"")
                })
            }))
        }
        val responseBody = resp.bodyAsText()
        assertEquals(HttpStatusCode.OK, resp.status, "有效 token 上传应成功: $responseBody")
        val result = FileOps.parseUploadResult(responseBody)
        assertEquals("t.bin", result.file.name)
        assertEquals("application/octet-stream", result.file.contentType)
        assertEquals(3, result.file.size)
        assertTrue(result.file.path.startsWith("real-uid/"), "响应含当前用户相对 path: $responseBody")
    }

    @Test
    fun `无效 token 被拒 401`() = testApplication {
        val fileStore = testFileStore()
        application { installTestFileRoutes(fileStore, TestAccessTokenValidator()) }
        val resp = client.post("/api/v1/files/upload") {
            header(HttpHeaders.Authorization, "Bearer forged-token")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `下载必须携带有效 token 且通过附件授权`() = testApplication {
        val accessToken = "valid-download-token"
        val accessTokens = TestAccessTokenValidator.single(accessToken, "reader", "dev-1")
        val fileStore = testFileStore()
        val source = File.createTempFile("tk-download-auth", ".txt").apply { writeText("secret") }
        val path = fileStore.store("owner", "secret.txt", "text/plain", source)
        application {
            installTestFileRoutes(
                fileStore,
                accessTokens,
                AttachmentAccess { uid, requestedPath -> uid == "reader" && requestedPath == path },
            )
        }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/files/$path").status)
        assertEquals(
            HttpStatusCode.OK,
            client.get("/api/v1/files/$path") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }.status,
        )
    }

    @Test
    fun `已认证但无附件权限返回 403`() = testApplication {
        val accessToken = "valid-denied-token"
        val accessTokens = TestAccessTokenValidator.single(accessToken, "reader", "dev-1")
        val fileStore = testFileStore()
        val source = File.createTempFile("tk-download-denied", ".txt").apply { writeText("secret") }
        val path = fileStore.store("owner", "secret.txt", "text/plain", source)
        application { installTestFileRoutes(fileStore, accessTokens, AttachmentAccess { _, _ -> false }) }

        val response = client.get("/api/v1/files/$path") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }
}
