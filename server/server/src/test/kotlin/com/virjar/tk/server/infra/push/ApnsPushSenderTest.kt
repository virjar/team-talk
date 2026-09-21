package com.virjar.tk.server.infra.push

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import kotlin.test.*

internal fun apnsFixtureConfiguration(bundleId: String = "com.example.teamtalk") = ApnsPushConfiguration(
    "ABCDEFGHIJ", "KLMNOPQRST", bundleId, "TeamTalk", apnsFixturePrivateKey(), ApnsEnvironment.entries.toSet(),
)

private fun apnsFixturePrivateKey(): String {
    val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    return "-----BEGIN PRIVATE KEY-----\n${Base64.getEncoder().encodeToString(pair.private.encoded)}\n-----END PRIVATE KEY-----"
}

class ApnsPushSenderTest {
    @Test fun `provider JWT is verifiable ES256 cached for fifty minutes and never exposes key material`() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n${Base64.getEncoder().encodeToString(pair.private.encoded)}\n-----END PRIVATE KEY-----"
        val configuration = ApnsPushConfiguration("ABCDEFGHIJ", "KLMNOPQRST", "com.example.app", "TeamTalk", pem)
        val cache = ApnsProviderToken(configuration)
        val first = cache.get(1_700_000_000)
        val segments = first.split('.')
        val decoder = Base64.getUrlDecoder()
        assertEquals("ES256", Json.parseToJsonElement(decoder.decode(segments[0]).decodeToString()).jsonObject["alg"]?.jsonPrimitive?.content)
        val claims = Json.parseToJsonElement(decoder.decode(segments[1]).decodeToString()).jsonObject
        assertEquals("ABCDEFGHIJ", claims["iss"]?.jsonPrimitive?.content)
        assertEquals(1_700_000_000L, claims["iat"]?.jsonPrimitive?.long)
        val signature = decoder.decode(segments[2])
        assertEquals(64, signature.size)
        assertTrue(Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initVerify(pair.public); update("${segments[0]}.${segments[1]}".toByteArray()); verify(signature)
        })
        assertEquals(first, cache.get(1_700_002_999))
        val second = cache.get(1_700_003_000)
        assertNotEquals(first, second)
        cache.invalidate(first)
        assertEquals(second, cache.get(1_700_003_001), "a stale failure cannot discard a newer JWT")
        cache.invalidate(second)
        assertNotEquals(second, cache.get(1_700_003_002))
        assertFalse(configuration.toString().contains("PRIVATE KEY"))
    }

    @Test fun `request uses exact environment topic identity and generic alert over HTTP2`() = runBlocking {
        val config = apnsFixtureConfiguration()
        OemPushSender(OemPushConfiguration(apns = config)).use { owner ->
            for (environment in ApnsEnvironment.entries) {
                val result = owner.sendApnsPush(notification(environment), clock = { 1_700_000_000_000 }) { request ->
                    assertEquals(environment.endpoint.host, request.uri().host)
                    assertEquals("https", request.uri().scheme)
                    assertEquals("/3/device/${"ab".repeat(32)}", request.uri().path)
                    assertEquals(HttpClient.Version.HTTP_2, request.version().orElseThrow())
                    assertEquals(config.bundleId, request.headers().firstValue("apns-topic").orElseThrow())
                    assertEquals("alert", request.headers().firstValue("apns-push-type").orElseThrow())
                    assertEquals("10", request.headers().firstValue("apns-priority").orElseThrow())
                    assertEquals("job", request.headers().firstValue("apns-collapse-id").orElseThrow())
                    assertTrue(request.headers().firstValue("authorization").orElseThrow().startsWith("bearer "))
                    val body = Json.parseToJsonElement(requestBody(request)).jsonObject
                    assertEquals("f".repeat(64), body["deploymentFingerprint"]?.jsonPrimitive?.content)
                    assertEquals("dataset", body["datasetId"]?.jsonPrimitive?.content)
                    assertEquals("user", body["uid"]?.jsonPrimitive?.content)
                    assertEquals("chat", body["chatId"]?.jsonPrimitive?.content)
                    assertEquals(OEM_PUSH_CONTENT, body["aps"]?.jsonObject?.get("alert")?.jsonObject?.get("body")?.jsonPrimitive?.content)
                    response(200)
                }
                assertTrue(result.accepted)
            }
        }
    }

    @Test fun `invalid device token retires registration while old invalidation auth and rate limits retry`() = runBlocking {
        OemPushSender(OemPushConfiguration(apns = apnsFixtureConfiguration())).use { owner ->
            suspend fun result(status: Int, reason: String, timestamp: Long? = null) =
                owner.sendApnsPush(notification()) { response(status, buildJsonObject {
                    put("reason", reason); timestamp?.let { put("timestamp", it) }
                }.toString()) }
            assertTrue(result(400, "BadDeviceToken").invalidRegistration)
            assertTrue(result(400, "DeviceTokenNotForTopic").invalidRegistration)
            assertTrue(result(410, "Unregistered", 2_000).invalidRegistration)
            assertFalse(result(410, "Unregistered", 999).invalidRegistration)
            assertFalse(result(403, "InvalidProviderToken").invalidRegistration)
            assertFalse(result(429, "TooManyRequests").accepted)
            val headers = mutableListOf<String>()
            repeat(2) {
                owner.sendApnsPush(notification(), clock = { 1_700_000_000_000 + it * 1_000L }) { request ->
                    headers += request.headers().firstValue("authorization").orElseThrow()
                    response(403, """{"reason":"ExpiredProviderToken"}""")
                }
            }
            assertNotEquals(headers[0], headers[1])
            assertFalse(owner.sendApnsPush(notification()) {
                OemPushHttpExchange.Responded(200, byteArrayOf(), HttpClient.Version.HTTP_1_1)
            }.accepted)
            assertFailsWith<CancellationException> {
                owner.sendApnsPush(notification()) { throw CancellationException("fixture") }
            }
            owner.close()
            assertFailsWith<IllegalStateException> { owner.sendApnsPush(notification()) { response(200) } }
        }
    }

    private fun notification(environment: ApnsEnvironment = ApnsEnvironment.PRODUCTION) = OemPushNotification(
        environment.channel, "ab".repeat(32), "f".repeat(64), "dataset", "user", "chat", "job", registeredAt = 1_000,
    )

    private fun response(status: Int, body: String = "") =
        OemPushHttpExchange.Responded(status, body.toByteArray(), HttpClient.Version.HTTP_2)

    private fun requestBody(request: HttpRequest): String {
        val done = CompletableFuture<String>()
        val bytes = ByteArrayOutputStream()
        request.bodyPublisher().orElseThrow().subscribe(object : Flow.Subscriber<ByteBuffer> {
            override fun onSubscribe(subscription: Flow.Subscription) = subscription.request(Long.MAX_VALUE)
            override fun onNext(item: ByteBuffer) {
                val part = ByteArray(item.remaining()); item.get(part); bytes.write(part)
            }
            override fun onError(throwable: Throwable) { done.completeExceptionally(throwable) }
            override fun onComplete() { done.complete(bytes.toString(Charsets.UTF_8)) }
        })
        return done.get(5, TimeUnit.SECONDS)
    }
}
