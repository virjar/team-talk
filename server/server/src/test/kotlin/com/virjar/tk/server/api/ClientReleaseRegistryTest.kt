package com.virjar.tk.server.api

import com.virjar.tk.protocol.http.ClientReleaseManifest
import com.virjar.tk.protocol.http.ClientUpdateCheckResponse
import com.virjar.tk.server.infra.clientrelease.ClientReleaseConflictException
import com.virjar.tk.server.infra.clientrelease.ClientReleaseService
import com.virjar.tk.server.infra.clientrelease.ClientReleaseUploadMetadata
import com.virjar.tk.server.infra.db.DatabaseFactory
import com.virjar.tk.server.infra.storage.ReleaseStore
import com.virjar.tk.server.testing.PostgresSchemaLease
import com.virjar.tk.server.runtime.HttpBlockingExecutor
import com.virjar.tk.server.runtime.installHttpBlockingBoundary
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 发布注册中心端到端：上传 → 检查更新 → manifest/制品下载 → 通道回滚/停用/kill-switch
 * → snapshot 覆盖语义 → android.json 兼容层。
 */
class ClientReleaseRegistryTest {

    private val publishToken = "test-publish-token-0123456789"

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun metadata(
        version: String,
        build: Long,
        channel: String,
        clientType: String = "desktop",
        platform: String = "macos",
        arch: String = "aarch64",
        installers: List<ClientReleaseUploadMetadata.InstallerSpec> = listOf(
            ClientReleaseUploadMetadata.InstallerSpec("TeamTalk-$version-$platform-$arch.zip", "macOS (Apple Silicon)"),
        ),
        minShellAbi: Int? = null,
    ) = ClientReleaseUploadMetadata(
        clientType = clientType,
        platform = platform,
        arch = arch,
        version = version,
        build = build,
        channel = channel,
        notes = "测试发布说明",
        minShellAbi = minShellAbi,
        installers = installers,
    )

    private fun uploadZip(
        metadata: ClientReleaseUploadMetadata,
        payload: Map<String, ByteArray>,
        installerBytes: ByteArray,
    ): File {
        val file = File.createTempFile("client-release-upload-", ".zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("release.json"))
            zip.write(Json.encodeToString(ClientReleaseUploadMetadata.serializer(), metadata).toByteArray())
            zip.closeEntry()
            payload.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry("payload/$path"))
                zip.write(bytes)
                zip.closeEntry()
            }
            metadata.installers.forEach { spec ->
                zip.putNextEntry(ZipEntry("installers/${spec.filename}"))
                zip.write(installerBytes)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun service(database: com.virjar.tk.server.infra.db.PostgresDatabase, storeRoot: File) =
        ClientReleaseService(
            database = database.database,
            store = ReleaseStore(storeRoot),
            audit = { _, _, _, _ -> },
            publishTokenSha256 = sha256Hex(publishToken.toByteArray()),
        )

    @Test
    fun `上传发布后检查更新 manifest 与制品下载闭环`() {
        PostgresSchemaLease.open().use { lease ->
            DatabaseFactory.create(
                jdbcUrl = lease.jdbcUrl,
                user = lease.user,
                password = lease.password,
                maxPoolSize = 4,
            ).use { database ->
                val storeRoot = Files.createTempDirectory("release-store-").toFile()
                val staging = Files.createTempDirectory("release-staging-").toFile()
                val downloads = Files.createTempDirectory("legacy-downloads-").toFile()
                val releaseService = service(database, storeRoot)
                val auth = testAdminSecurity()
                val zip = uploadZip(
                    metadata("0.0.3", 5, "stable", minShellAbi = 1),
                    payload = mapOf("lib/app.jar" to "app-jar-bytes".toByteArray(), "lib/shared.jar" to "shared-jar-bytes".toByteArray()),
                    installerBytes = "installer-bytes".toByteArray(),
                )
                try {
                    testApplication {
                        application {
                            install(ContentNegotiation) { json() }
                            routing {
                                clientUpdateRoutes(releaseService, auth, staging)
                                clientDownloadRoutes(downloads, releaseService)
                            }
                        }

                        // 无凭据上传被拒。
                        val unauth = client.post("/api/v1/client/releases") {
                            setBody(
                                MultiPartFormDataContent(
                                    formData {
                                        append("release", zip.readBytes(), headersOf(HttpHeaders.ContentDisposition, "filename=\"release.zip\""))
                                    },
                                ),
                            )
                        }
                        assertEquals(HttpStatusCode.Unauthorized, unauth.status)

                        // CI 令牌上传成功。
                        val uploaded = client.post("/api/v1/client/releases") {
                            header("X-Publish-Token", publishToken)
                            setBody(
                                MultiPartFormDataContent(
                                    formData {
                                        append("release", zip.readBytes(), headersOf(HttpHeaders.ContentDisposition, "filename=\"release.zip\""))
                                    },
                                ),
                            )
                        }
                        assertEquals(HttpStatusCode.OK, uploaded.status, uploaded.bodyAsText())

                        // 旧版本客户端收到 UPDATE_AVAILABLE。
                        val check = client.get(
                            "/api/v1/client/updates/check?client=desktop&platform=macos&arch=aarch64&channel=stable&version=0.0.2&build=4",
                        )
                        assertEquals(HttpStatusCode.OK, check.status)
                        val decision = Json.decodeFromString(ClientUpdateCheckResponse.serializer(), check.bodyAsText())
                        assertEquals("UPDATE_AVAILABLE", decision.status)
                        val info = assertNotNull(decision.release)
                        assertEquals("0.0.3", info.version)
                        assertEquals(5, info.build)
                        assertEquals(2, info.fileCount)
                        assertNotNull(info.manifestUrl)
                        assertEquals(1, info.installers.size)

                        // manifest 内容寻址正确。
                        val manifestText = client.get(info.manifestUrl!!)
                        assertEquals(HttpStatusCode.OK, manifestText.status)
                        val manifest = Json.decodeFromString(ClientReleaseManifest.serializer(), manifestText.bodyAsText())
                        assertEquals(2, manifest.files.size)
                        val appJar = manifest.files.first { it.path == "lib/app.jar" }
                        assertEquals(sha256Hex("app-jar-bytes".toByteArray()), appJar.sha256)

                        // 制品可下载且支持 Range（断点续传契约）。
                        val artifact = client.get(appJar.url)
                        assertEquals(HttpStatusCode.OK, artifact.status)
                        assertEquals("app-jar-bytes", artifact.bodyAsText())
                        // 下载探测必须和真实 GET 使用同一 CAS 文件，且 HEAD 不返回正文。
                        for (url in listOf(appJar.url, info.installers.single().url)) {
                            val downloaded = client.get(url)
                            val head = client.head(url) { header(HttpHeaders.Range, "bytes=0-2") }
                            assertEquals(HttpStatusCode.OK, head.status, url)
                            assertEquals("", head.bodyAsText(), url)
                            for (header in listOf(
                                HttpHeaders.ContentLength, HttpHeaders.ContentType, HttpHeaders.ETag,
                                HttpHeaders.CacheControl, HttpHeaders.AcceptRanges,
                            )) {
                                assertNotNull(downloaded.headers[header], "$url $header")
                                assertEquals(downloaded.headers[header], head.headers[header], "$url $header")
                            }
                            assertNull(head.headers[HttpHeaders.ContentRange], url)
                        }
                        // 安装器直链带发布文件名的 Content-Disposition（浏览器默认把 URL 哈希当文件名，
                        // Android 会得到无 .apk 后缀的文件）；payload 文件不设置。
                        val installerDisposition = client.get(info.installers.single().url)
                            .headers[HttpHeaders.ContentDisposition]
                        assertNotNull(installerDisposition)
                        assertEquals(
                            "attachment; filename=\"${info.installers.single().filename}\"",
                            installerDisposition,
                        )
                        assertNull(client.get(appJar.url).headers[HttpHeaders.ContentDisposition])
                        for (sha in listOf("0".repeat(64), "not-a-digest")) {
                            val url = "/api/v1/client/files/$sha"
                            assertEquals(HttpStatusCode.NotFound, client.get(url).status, url)
                            assertEquals(HttpStatusCode.NotFound, client.head(url).status, url)
                        }
                        val ranged = client.get(appJar.url) { header(HttpHeaders.Range, "bytes=0-2") }
                        assertEquals(HttpStatusCode.PartialContent, ranged.status)
                        assertEquals("app", ranged.bodyAsText())

                        // 已是最新版本。
                        val fresh = client.get(
                            "/api/v1/client/updates/check?client=desktop&platform=macos&arch=aarch64&channel=stable&version=0.0.3&build=5",
                        )
                        assertEquals("UP_TO_DATE", Json.decodeFromString(ClientUpdateCheckResponse.serializer(), fresh.bodyAsText()).status)

                        // 壳 ABI 不足时要求换首装包。
                        val shellLagged = client.get(
                            "/api/v1/client/updates/check?client=desktop&platform=macos&arch=aarch64&channel=stable&version=0.0.2&build=4&shellAbi=0",
                        )
                        val shellDecision = Json.decodeFromString(ClientUpdateCheckResponse.serializer(), shellLagged.bodyAsText())
                        assertEquals("SHELL_UPDATE_REQUIRED", shellDecision.status)
                    }
                } finally {
                    zip.delete()
                    storeRoot.deleteRecursively()
                    staging.deleteRecursively()
                    downloads.deleteRecursively()
                }
            }
        }
    }

    @Test
    fun `通道回滚停用与 kill-switch 生效`() {
        PostgresSchemaLease.open().use { lease ->
            DatabaseFactory.create(jdbcUrl = lease.jdbcUrl, user = lease.user, password = lease.password, maxPoolSize = 4)
                .use { database ->
                    val storeRoot = Files.createTempDirectory("release-store-").toFile()
                    val staging = Files.createTempDirectory("release-staging-").toFile()
                    val releaseService = service(database, storeRoot)
                    val auth = testAdminSecurity()
                    val v1 = uploadZip(metadata("0.0.3", 5, "stable"), mapOf("lib/app.jar" to "v1".toByteArray()), "i1".toByteArray())
                    val v2 = uploadZip(metadata("0.0.3", 6, "stable"), mapOf("lib/app.jar" to "v2".toByteArray()), "i2".toByteArray())
                    try {
                        releaseService.importRelease("ci", v1)
                        val v2Id = releaseService.importRelease("ci", v2)
                        testApplication {
                            val token = auth.login("admin", "test-only-password")!!
                            application {
                                install(ContentNegotiation) { json() }
                                routing {
                                    clientUpdateRoutes(releaseService, auth, staging)
                                    route("/api/admin") {
                                        installAdminAuthorization(auth)
                                        adminClientReleaseRoutes(releaseService)
                                    }
                                }
                            }

                            fun checkUrl(version: String, build: Long) =
                                "/api/v1/client/updates/check?client=desktop&platform=macos&arch=aarch64&channel=stable&version=$version&build=$build"

                            suspend fun checkStatus(version: String, build: Long): Pair<String, Long?> {
                                val text = client.get(checkUrl(version, build)).bodyAsText()
                                val decision = Json.decodeFromString(ClientUpdateCheckResponse.serializer(), text)
                                return decision.status to decision.release?.build
                            }

                            // 最新发布生效。
                            assertEquals("UPDATE_AVAILABLE" to 6L, checkStatus("0.0.2", 1))

                            // 回滚通道到 v1：客户端被指令降级。
                            val releases = releaseService.listReleases("desktop", null, 10, 0)
                            val v1Row = releases.first { it.build == 5L }
                            val rollback = client.put("/api/admin/client-channels/desktop/macos/aarch64/stable") {
                                header(HttpHeaders.Authorization, "Bearer $token")
                                contentType(io.ktor.http.ContentType.Application.Json)
                                setBody("""{"releaseId": ${v1Row.id}}""")
                            }
                            assertEquals(HttpStatusCode.OK, rollback.status, rollback.bodyAsText())
                            assertEquals("UPDATE_AVAILABLE" to 5L, checkStatus("0.0.3", 6))

                            // 停用 v2 并回退到 v1 后，通道继续可用。
                            val disabled = client.post("/api/admin/client-releases/$v2Id/disable") {
                                header(HttpHeaders.Authorization, "Bearer $token")
                                contentType(io.ktor.http.ContentType.Application.Json)
                                setBody("""{"fallbackReleaseId": ${v1Row.id}}""")
                            }
                            assertEquals(HttpStatusCode.OK, disabled.status)
                            assertEquals("UPDATE_AVAILABLE" to 5L, checkStatus("0.0.2", 1))

                            // kill-switch：通道禁用后一切客户端拿到 CHANNEL_DISABLED。
                            val switchedOff = client.post("/api/admin/client-channels/desktop/macos/aarch64/stable/enabled") {
                                header(HttpHeaders.Authorization, "Bearer $token")
                                contentType(io.ktor.http.ContentType.Application.Json)
                                setBody("""{"enabled": false}""")
                            }
                            assertEquals(HttpStatusCode.OK, switchedOff.status)
                            assertEquals("CHANNEL_DISABLED" to null, checkStatus("0.0.2", 1))

                            // 管理列表必须通过真实 HTTP 序列化，不能只验证未鉴权分支。
                            for (path in listOf("client-releases", "client-channels")) {
                                val listing = client.get("/api/admin/$path") {
                                    header(HttpHeaders.Authorization, "Bearer $token")
                                }
                                assertEquals(HttpStatusCode.OK, listing.status, listing.bodyAsText())
                                Json.parseToJsonElement(listing.bodyAsText())
                            }
                            assertNull(releaseService.publicDownloads().targets.single().channels.single().release)

                            // 未携带管理会话的管理请求被拒。
                            val noSession = client.get("/api/admin/client-releases")
                            assertEquals(HttpStatusCode.Unauthorized, noSession.status)
                        }
                    } finally {
                        v1.delete()
                        v2.delete()
                        storeRoot.deleteRecursively()
                        staging.deleteRecursively()
                    }
                }
        }
    }

    @Test
    fun `原字节发布可重试且同号 snapshot 保留各源码负载` () {
        PostgresSchemaLease.open().use { lease ->
            DatabaseFactory.create(jdbcUrl = lease.jdbcUrl, user = lease.user, password = lease.password, maxPoolSize = 4)
                .use { database ->
                    val storeRoot = Files.createTempDirectory("release-store-").toFile()
                    val releaseService = service(database, storeRoot)
                    val stableZip = uploadZip(metadata("0.0.3", 5, "stable"), mapOf("lib/app.jar" to "a".toByteArray()), "p".toByteArray())
                    val snapshotA = uploadZip(metadata("0.0.3", 7, "snapshot").copy(buildIdentity = "source-a"), mapOf("lib/app.jar" to "snap-a".toByteArray()), "p".toByteArray())
                    val snapshotB = uploadZip(metadata("0.0.3", 7, "snapshot").copy(buildIdentity = "source-b"), mapOf("lib/app.jar" to "snap-b".toByteArray()), "p".toByteArray())
                    try {
                        val stableId = releaseService.importRelease("ci", stableZip)
                        assertEquals(stableId, releaseService.importRelease("ci", stableZip))
                        val changedStable = uploadZip(metadata("0.0.3", 5, "stable"),
                            mapOf("lib/app.jar" to "changed".toByteArray()), "p".toByteArray())
                        try {
                            assertFailsWith<ClientReleaseConflictException> { releaseService.importRelease("ci", changedStable) }
                        } finally { changedStable.delete() }
                        val a = releaseService.importRelease("ci", snapshotA)
                        val b = releaseService.importRelease("ci", snapshotB)
                        assertNotEquals(a, b)
                        assertEquals(3, releaseService.listReleases("desktop", null, 10, 0).size)
                        assertEquals(sha256Hex("snap-a".toByteArray()), releaseService.manifest(a)!!.files.single().sha256)
                        assertEquals(sha256Hex("snap-b".toByteArray()), releaseService.manifest(b)!!.files.single().sha256)
                        val query = ClientReleaseService.CheckQuery("desktop", "macos", "aarch64", "snapshot", "0.0.3", 7, 1, "source-a")
                        assertEquals("UPDATE_AVAILABLE", releaseService.check(query).status)
                        assertEquals("UP_TO_DATE", releaseService.check(query.copy(buildIdentity = "source-b")).status)
                        val downloads = releaseService.publicDownloads()
                        val current = downloads.targets.single().channels.single { it.channel == "snapshot" }.release!!
                        val snapshots = downloads.history.filter { it.channel == "snapshot" }
                        assertEquals(setOf("source-a", "source-b"), snapshots.map { it.buildIdentity }.toSet())
                        assertEquals(current.buildIdentity, snapshots.single { it.buildIdentity == "source-b" }.buildIdentity)
                        assertNull(downloads.history.single { it.channel == "stable" }.buildIdentity)
                        releaseService.setChannelEnabled("admin", "desktop", "macos", "aarch64", "snapshot", false)
                        assertEquals(b, releaseService.importRelease("ci", snapshotB))
                        assertEquals("CHANNEL_DISABLED", releaseService.check(query).status)

                    } finally {
                        stableZip.delete()
                        snapshotA.delete()
                        snapshotB.delete()
                        storeRoot.deleteRecursively()
                    }
                }
        }
    }

    @Test
    fun `超过50MiB的真实上传可重试且跨通道晋级复用发布`() {
        PostgresSchemaLease.open().use { lease ->
            DatabaseFactory.create(jdbcUrl = lease.jdbcUrl, user = lease.user, password = lease.password, maxPoolSize = 4)
                .use { database ->
                    val root = Files.createTempDirectory("registry-large-upload-").toFile()
                    val releaseService = service(database, File(root, "store"))
                    val auth = testAdminSecurity()
                    val meta = metadata("0.0.3", 7, "snapshot").copy(buildIdentity = "source-a")
                    val large = File(root, "large.zip")
                    ZipOutputStream(large.outputStream().buffered()).use { zip ->
                        zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
                        zip.putNextEntry(ZipEntry("release.json"))
                        zip.write(Json.encodeToString(ClientReleaseUploadMetadata.serializer(), meta).toByteArray())
                        zip.closeEntry()
                        zip.putNextEntry(ZipEntry("installers/${meta.installers.single().filename}"))
                        val chunk = ByteArray(1024 * 1024) { (it % 251).toByte() }
                        repeat(52) { zip.write(chunk) }
                        zip.closeEntry()
                    }
                    assertTrue(large.length() > 50L * 1024 * 1024)
                    val crossChannel = uploadZip(meta.copy(channel = "stable"), emptyMap(), "installer".toByteArray())
                    val stable = uploadZip(metadata("0.0.4", 8, "stable").copy(buildIdentity = "source-b"), emptyMap(), "b".toByteArray())
                    val changedStable = uploadZip(metadata("0.0.4", 8, "stable").copy(buildIdentity = "source-c"), emptyMap(), "c".toByteArray())
                    val httpExecutor = HttpBlockingExecutor(workerCount = 2, queueCapacity = 4)
                    try {
                        testApplication {
                            val token = auth.login("admin", "test-only-password")!!
                            application {
                                installHttpBlockingBoundary(httpExecutor)
                                install(ContentNegotiation) { json() }
                                routing {
                                    clientUpdateRoutes(releaseService, auth, File(root, "staging"))
                                    route("/api/admin") {
                                        installAdminAuthorization(auth)
                                        adminClientReleaseRoutes(releaseService)
                                    }
                                }
                            }
                            suspend fun upload(file: File) = client.post("/api/v1/client/releases") {
                                header("X-Publish-Token", publishToken)
                                setBody(streamingUpload(file))
                            }
                            val imported = upload(large)
                            assertEquals(HttpStatusCode.OK, imported.status, imported.bodyAsText())
                            val id = releaseService.listReleases("desktop", null, 10, 0).single().id
                            val retry = upload(large)
                            assertEquals(HttpStatusCode.OK, retry.status, retry.bodyAsText())
                            assertEquals(1, releaseService.listReleases("desktop", null, 10, 0).size)
                            val conflict = upload(crossChannel)
                            assertEquals(HttpStatusCode.Conflict, conflict.status, conflict.bodyAsText())
                            val promoted = client.put("/api/admin/client-channels/desktop/macos/aarch64/stable") {
                                header(HttpHeaders.Authorization, "Bearer $token")
                                contentType(ContentType.Application.Json)
                                setBody("{\"releaseId\":$id}")
                            }
                            assertEquals(HttpStatusCode.OK, promoted.status, promoted.bodyAsText())
                            val decision = Json.decodeFromString(ClientUpdateCheckResponse.serializer(), client.get(
                                "/api/v1/client/updates/check?client=desktop&platform=macos&arch=aarch64&channel=stable",
                            ).bodyAsText())
                            assertEquals(id, decision.release?.id)
                            assertEquals("stable", decision.release?.channel)
                            val stableResponse = upload(stable)
                            assertEquals(HttpStatusCode.OK, stableResponse.status, stableResponse.bodyAsText())
                            val changedResponse = upload(changedStable)
                            assertEquals(HttpStatusCode.Conflict, changedResponse.status, changedResponse.bodyAsText())
                        }
                    } finally {
                        httpExecutor.close()
                        crossChannel.delete()
                        stable.delete()
                        changedStable.delete()
                        root.deleteRecursively()
                    }
                }
        }
    }

    /** 按 HTTP multipart 语法逐块发送文件，测试自身也不把安装包读入堆内存。 */
    private fun streamingUpload(file: File): OutgoingContent.WriteChannelContent {
        val boundary = "teamtalk-release-stream"
        val prefix = ("--$boundary\r\nContent-Disposition: form-data; name=\"release\"; filename=\"release.zip\"\r\n" +
            "Content-Type: application/zip\r\n\r\n").toByteArray()
        val suffix = "\r\n--$boundary--\r\n".toByteArray()
        return object : OutgoingContent.WriteChannelContent() {
            override val contentType = ContentType.MultiPart.FormData.withParameter("boundary", boundary)
            override val contentLength = prefix.size + file.length() + suffix.size
            override suspend fun writeTo(channel: ByteWriteChannel) {
                channel.writeFully(prefix)
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        channel.writeFully(buffer, 0, count)
                    }
                }
                channel.writeFully(suffix)
            }
        }
    }

    @Test
    fun `android json 与 APK 别名由注册中心供数且保留 legacy 兜底`() {
        PostgresSchemaLease.open().use { lease ->
            DatabaseFactory.create(jdbcUrl = lease.jdbcUrl, user = lease.user, password = lease.password, maxPoolSize = 4)
                .use { database ->
                    val storeRoot = Files.createTempDirectory("release-store-").toFile()
                    val staging = Files.createTempDirectory("release-staging-").toFile()
                    val downloads = Files.createTempDirectory("legacy-downloads-").toFile()
                    val releaseService = service(database, storeRoot)
                    val auth = testAdminSecurity()
                    val apkZip = uploadZip(
                        metadata(
                            "0.0.3", 5, "stable",
                            clientType = "android",
                            platform = "android",
                            arch = "any",
                            installers = listOf(ClientReleaseUploadMetadata.InstallerSpec("TeamTalk-0.0.3-5-android.apk", "Android 安装包")),
                        ),
                        payload = emptyMap(),
                        installerBytes = "apk-bytes".toByteArray(),
                    )
                    try {
                        testApplication {
                            application {
                                install(ContentNegotiation) { json() }
                                routing {
                                    clientUpdateRoutes(releaseService, auth, staging)
                                    clientDownloadRoutes(downloads, releaseService)
                                }
                            }

                            // 无注册数据时的 legacy 兜底：裸 APK（无收据）→ version=null。
                            downloads.resolve(ANDROID_DOWNLOAD_ALIAS).writeText("legacy-apk")
                            val legacy = client.get("/downloads/android.json")
                            assertEquals(HttpStatusCode.OK, legacy.status)
                            assertTrue(legacy.bodyAsText().contains("\"version\":null"), legacy.bodyAsText())

                            // 注册中心接管后返回新身份。
                            val uploaded = client.post("/api/v1/client/releases") {
                                header("X-Publish-Token", publishToken)
                                setBody(
                                    MultiPartFormDataContent(
                                        formData {
                                            append("release", apkZip.readBytes(), headersOf(HttpHeaders.ContentDisposition, "filename=\"release.zip\""))
                                        },
                                    ),
                                )
                            }
                            assertEquals(HttpStatusCode.OK, uploaded.status, uploaded.bodyAsText())
                            val manifestJson = client.get("/downloads/android.json")
                            assertEquals(HttpStatusCode.OK, manifestJson.status)
                            val text = manifestJson.bodyAsText()
                            assertTrue(text.contains("\"version\":\"0.0.3\""), text)
                            assertTrue(text.contains("\"channel\":\"stable\""), text)

                            // 别名下载返回注册中心的 APK 字节。
                            val alias = client.get("/downloads/$ANDROID_DOWNLOAD_ALIAS")
                            assertEquals(HttpStatusCode.OK, alias.status)
                            assertEquals("apk-bytes", alias.bodyAsText())
                            assertEquals(
                                "application/vnd.android.package-archive",
                                alias.headers[HttpHeaders.ContentType],
                            )
                            assertEquals("attachment; filename=\"TeamTalk-0.0.3-5-android.apk\"", alias.headers[HttpHeaders.ContentDisposition])

                            // 精确文件名同样可下载。
                            val exact = client.get("/downloads/TeamTalk-0.0.3-5-android.apk")
                            assertEquals(HttpStatusCode.OK, exact.status)

                            // HEAD 只回元数据。
                            val head = client.head("/downloads/$ANDROID_DOWNLOAD_ALIAS")
                            assertEquals(HttpStatusCode.OK, head.status)
                            assertEquals("9", head.headers[HttpHeaders.ContentLength])
                            releaseService.setChannelEnabled("admin", "android", "android", "any", "stable", false)
                            for (path in listOf("android.json", ANDROID_DOWNLOAD_ALIAS, "TeamTalk-0.0.3-5-android.apk")) {
                                assertEquals(HttpStatusCode.NotFound, client.get("/downloads/$path").status)
                            }
                            assertEquals(HttpStatusCode.NotFound, client.head("/downloads/$ANDROID_DOWNLOAD_ALIAS").status)
                        }
                    } finally {
                        apkZip.delete()
                        storeRoot.deleteRecursively()
                        staging.deleteRecursively()
                        downloads.deleteRecursively()
                    }
                }
        }
    }
}
