package release.publish

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitHubPublisherIntegrationTest {
    @Test
    fun `interrupted draft resumes identical assets and publishes exact checked in notes`() {
        GitHubFixture().use { github ->
            val request = github.publication()
            github.failNextAsset = "teamtalk-0.0.1-desktop.zip"
            assertFailsWith<IllegalArgumentException> { github.publisher.publish(request, "fixture-token") }
            assertTrue(github.release!!.getValue("draft").jsonPrimitive.boolean)
            assertEquals(setOf("TeamTalk-0.0.1-android.apk"), github.assets.values.map { it.name }.toSet())
            val result = github.publisher.publish(request, "fixture-token")
            assertFalse(result.alreadyPublished)
            assertEquals(request.notes, github.release!!.getValue("body").jsonPrimitive.content)
            assertFalse(github.release!!.getValue("draft").jsonPrimitive.boolean)
            assertTrue(github.release!!.getValue("prerelease").jsonPrimitive.boolean)
            assertEquals(1, github.successfulUploads.getValue("TeamTalk-0.0.1-android.apk"))
            assertTrue(github.publisher.publish(request, "fixture-token").alreadyPublished)
            assertEquals(2, github.assets.size)
        }
    }

    @Test
    fun `published asset or tag mismatch cannot rewrite an existing release`() {
        GitHubFixture().use { github ->
            val request = github.publication()
            github.publisher.publish(request, "fixture-token")
            request.assets.first().appendText("modified")
            assertFailsWith<IllegalArgumentException> { github.publisher.publish(request, "fixture-token") }
            assertEquals(2, github.assets.size)
            github.tagCommit = "b".repeat(40)
            assertFailsWith<IllegalArgumentException> { github.publisher.publish(request, "fixture-token") }
            assertEquals(1, github.createdReleases)
        }
    }

    @Test
    fun `an existing annotated tag is peeled and does not need tag creation permission`() {
        GitHubFixture().use { github ->
            github.tagCommit = "a".repeat(40)
            github.annotatedTag = true
            github.publisher.publish(github.publication().copy(createTag = false), "fixture-token")
            assertEquals(0, github.createdTags)
        }
    }

    @Test
    fun `private Enterprise without asset digests verifies bytes through the asset API`() {
        GitHubFixture().use { github ->
            github.includeDigest = false
            val request = github.publication()
            github.publisher.publish(request, "fixture-token")
            assertTrue(github.publisher.publish(request, "fixture-token").alreadyPublished)
            assertTrue(github.assetDownloads > 0)
        }
    }

    @Test
    fun `failed zero byte draft starter is replaced on retry`() {
        GitHubFixture().use { github ->
            val request = github.publication()
            github.failNextAsset = "teamtalk-0.0.1-desktop.zip"
            github.leaveStarterOnFailure = true
            assertFails { github.publisher.publish(request, "fixture-token") }
            github.publisher.publish(request, "fixture-token")
            assertTrue(github.starters.isEmpty())
            assertEquals(1, github.removedStarters)
            assertEquals(2, github.assets.size)
        }
    }
}

private class GitHubFixture : AutoCloseable {
    data class Asset(val id: Int, val name: String, val bytes: ByteArray)
    val directory: File = Files.createTempDirectory("teamtalk-github-publication-").toFile()
    val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val publisher: GitHubPublisher
    var tagCommit: String? = null
    var annotatedTag = false
    var release: JsonObject? = null
    var failNextAsset: String? = null
    var includeDigest = true
    var leaveStarterOnFailure = false
    var createdTags = 0
    var createdReleases = 0
    var assetDownloads = 0
    var removedStarters = 0
    val starters = mutableSetOf<Int>()
    val assets = linkedMapOf<Int, Asset>()
    val successfulUploads = mutableMapOf<String, Int>()
    private val nextAsset = AtomicInteger(1)

    init {
        server.createContext("/") { exchange ->
            try { handle(exchange) } catch (error: Exception) {
                exchange.respond(500, buildJsonObject { put("message", error.javaClass.simpleName) })
            } finally { exchange.close() }
        }
        server.start()
        val base = URI("http://127.0.0.1:${server.address.port}")
        publisher = GitHubPublisher(base, base)
    }

    fun publication(): GitHubPublication {
        val apk = File(directory, "TeamTalk-0.0.1-android.apk").apply { writeText("sealed apk") }
        val desktop = File(directory, "teamtalk-0.0.1-desktop.zip").apply { writeText("complete archived desktop site") }
        return GitHubPublication(
            repository = "test/teamtalk",
            version = "0.0.1",
            sourceCommit = "a".repeat(40),
            notes = "# TeamTalk 0.0.1\n\n人工说明：修复资料迁移。\n\n- 保留准确的换行与中文。\n",
            assets = listOf(apk, desktop),
            createTag = true,
        )
    }

    private fun handle(exchange: HttpExchange) {
        check(exchange.requestHeaders.getFirst("Authorization") == "Bearer fixture-token")
        val path = exchange.requestURI.path.removePrefix("/repos/test/teamtalk")
        val method = exchange.requestMethod
        when {
            path == "/git/ref/tags/v0.0.1" -> {
                if (tagCommit == null) exchange.respond(404)
                else exchange.respond(200, buildJsonObject { put("object", buildJsonObject {
                    put("type", if (annotatedTag) "tag" else "commit")
                    put("sha", if (annotatedTag) "c".repeat(40) else tagCommit!!)
                }) })
            }
            path == "/git/tags/${"c".repeat(40)}" -> exchange.respond(200, buildJsonObject {
                put("object", buildJsonObject { put("type", "commit"); put("sha", tagCommit!!) })
            })
            path == "/git/refs" && method == "POST" -> {
                tagCommit = exchange.json().getValue("sha").jsonPrimitive.content
                createdTags++
                exchange.respond(201)
            }
            path == "/releases/tags/v0.0.1" -> exchange.respond(if (release == null) 404 else 200, release ?: JsonObject(emptyMap()))
            path == "/releases" && method == "GET" -> exchange.respond(200, JsonArray(listOfNotNull(release)))
            path == "/releases" && method == "POST" -> {
                createdReleases++
                release = JsonObject(exchange.json() + ("id" to JsonPrimitive(42)))
                exchange.respond(201, release!!)
            }
            path == "/releases/42" && method == "PATCH" -> {
                release = JsonObject(release!! + exchange.json())
                exchange.respond(200, release!!)
            }
            path == "/releases/42/assets" && method == "GET" -> exchange.respond(200, JsonArray(assets.values.map(::describe)))
            path == "/releases/42/assets" && method == "POST" -> {
                val name = URLDecoder.decode(exchange.requestURI.rawQuery.substringAfter("name="), Charsets.UTF_8)
                if (name == failNextAsset) {
                    failNextAsset = null
                    if (leaveStarterOnFailure) {
                        val starter = Asset(nextAsset.getAndIncrement(), name, byteArrayOf())
                        assets[starter.id] = starter
                        starters += starter.id
                    }
                    exchange.respond(503)
                } else {
                    val asset = Asset(nextAsset.getAndIncrement(), name, exchange.requestBody.readBytes())
                    assets[asset.id] = asset
                    successfulUploads[name] = (successfulUploads[name] ?: 0) + 1
                    exchange.respond(201, describe(asset))
                }
            }
            path.startsWith("/releases/assets/") -> {
                val id = path.substringAfterLast('/').toInt()
                if (method == "DELETE") {
                    check(starters.remove(id))
                    assets.remove(id)
                    removedStarters++
                    exchange.sendResponseHeaders(204, -1)
                } else {
                    val asset = assets.getValue(id)
                    assetDownloads++
                    exchange.sendResponseHeaders(200, asset.bytes.size.toLong())
                    exchange.responseBody.write(asset.bytes)
                }
            }
            else -> exchange.respond(404)
        }
    }

    private fun describe(asset: Asset): JsonObject = buildJsonObject {
        put("id", asset.id)
        put("name", asset.name)
        put("state", if (asset.id in starters) "starter" else "uploaded")
        put("size", asset.bytes.size)
        if (includeDigest) put("digest", "sha256:" + asset.bytes.inputStream().use(::sha256))
    }

    override fun close() {
        server.stop(0)
        directory.deleteRecursively()
    }

    private fun HttpExchange.json(): JsonObject = requestBody.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
    private fun HttpExchange.respond(status: Int, value: JsonElement = JsonObject(emptyMap())) {
        val bytes = value.toString().toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.write(bytes)
    }
}
