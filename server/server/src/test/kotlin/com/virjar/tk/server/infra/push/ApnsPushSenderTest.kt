package com.virjar.tk.server.infra.push

import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.body.FileBody
import com.virjar.tk.protocol.body.ImageBody
import com.virjar.tk.protocol.body.OfficeRefBody
import com.virjar.tk.protocol.body.buildMentionMarkdown
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.ChatType
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.model.MentionPolicy
import com.virjar.tk.protocol.model.Message
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

    @Test fun `sender uses formatted alert title body and badge when populated`() = runBlocking {
        val config = apnsFixtureConfiguration()
        OemPushSender(OemPushConfiguration(apns = config)).use { owner ->
            val custom = OemPushNotification(
                vendor = ApnsEnvironment.PRODUCTION.channel,
                registrationId = "ab".repeat(32),
                deploymentFingerprint = "f".repeat(64),
                datasetId = "dataset",
                uid = "user",
                chatId = "chat",
                jobKey = "job",
                registeredAt = 1_000,
                deliveredSeq = 9L,
                alertTitle = "研发群",
                alertBody = "[有人@你] 张三 发来一张[图片]",
                badgeCount = 5,
            )
            val result = owner.sendApnsPush(custom, clock = { 1_700_000_000_000 }) { request ->
                val body = Json.parseToJsonElement(requestBody(request)).jsonObject
                val aps = body.getValue("aps").jsonObject
                val alert = aps.getValue("alert").jsonObject
                assertEquals("研发群", alert["title"]?.jsonPrimitive?.content)
                assertEquals("[有人@你] 张三 发来一张[图片]", alert["body"]?.jsonPrimitive?.content)
                assertEquals(5, aps["badge"]?.jsonPrimitive?.int)
                assertEquals("chat", aps["thread-id"]?.jsonPrimitive?.content)
                response(200)
            }
            assertTrue(result.accepted)
        }
    }

    @Test fun `formatter obeys sender_only preview and generic privacy modes across message types`() {
        val secretText = "机密口令-998877"
        val secretDocName = "高管薪酬表.xlsx"
        val richTextMsg = Message(
            chatId = "chat-1",
            clientMsgId = "c-1",
            senderUid = "sender-1",
            messageType = MessageType.RICH_TEXT.code,
            timestamp = 1_000L,
            body = buildRichTextBody("${buildMentionMarkdown("李四", "u-target")} $secretText"),
        )
        val embeddedAssetId = "11111111-1111-4111-8111-111111111111"
        val embeddedImgAsset = EmbeddedAsset(
            assetId = embeddedAssetId,
            attachment = Attachment(path = "a/b.png", name = "secret.png", contentType = "image/png", size = 100L),
            width = 100,
            height = 100,
        )
        val pureEmbeddedImageMsg = Message(
            chatId = "chat-2",
            clientMsgId = "c-2",
            senderUid = "sender-1",
            messageType = MessageType.RICH_TEXT.code,
            timestamp = 2_000L,
            body = buildRichTextBody("![图](${EmbeddedAsset.uri(embeddedAssetId)})", listOf(embeddedImgAsset)),
        )
        val fileMsg = Message(
            chatId = "chat-1",
            clientMsgId = "c-3",
            senderUid = "sender-1",
            messageType = MessageType.FILE.code,
            timestamp = 3_000L,
            body = FileBody(Attachment(path = "f/x.xlsx", name = secretDocName, contentType = "application/octet-stream", size = 200L)),
        )
        val docMsg = Message(
            chatId = "chat-2",
            clientMsgId = "c-4",
            senderUid = "sender-1",
            messageType = MessageType.OFFICE_REF.code,
            timestamp = 4_000L,
            body = OfficeRefBody(OfficeRefBody.REF_TYPE_DOCUMENT, "space-1", "doc-1", "机密文档标题"),
        )
        val allMentionMsg = Message(
            chatId = "chat-2",
            clientMsgId = "c-5",
            senderUid = "sender-1",
            messageType = MessageType.IMAGE.code,
            timestamp = 5_000L,
            body = ImageBody(Attachment(path = "a/i.png", name = "i.png", contentType = "image/png", size = 10L)),
        )
        val allMentionTextMsg = Message(
            chatId = "chat-2",
            clientMsgId = "c-6",
            senderUid = "sender-1",
            messageType = MessageType.RICH_TEXT.code,
            timestamp = 6_000L,
            body = buildRichTextBody("${buildMentionMarkdown("所有人", MentionPolicy.ALL)} 请查收"),
        )

        val personalSenderOnly = ApnsNotificationFormatter.format(
            ApnsContentMode.SENDER_ONLY, "TeamTalk", ChatType.PERSONAL.code, null, "老王", "u-target", richTextMsg,
        )
        assertEquals("老王", personalSenderOnly.title)
        assertEquals("发来一条消息", personalSenderOnly.body)
        assertFalse(personalSenderOnly.body.contains(secretText))

        val personalFileSenderOnly = ApnsNotificationFormatter.format(
            ApnsContentMode.SENDER_ONLY, "TeamTalk", ChatType.PERSONAL.code, null, "老王", "u-target", fileMsg,
        )
        assertEquals("发来一个[文件]", personalFileSenderOnly.body)
        assertFalse(personalFileSenderOnly.body.contains(secretDocName))

        val groupMentionSenderOnly = ApnsNotificationFormatter.format(
            ApnsContentMode.SENDER_ONLY, "TeamTalk", ChatType.GROUP.code, "研发群", "张三", "u-target", richTextMsg,
        )
        assertEquals("研发群", groupMentionSenderOnly.title)
        assertEquals("[有人@你] 张三 发来一条消息", groupMentionSenderOnly.body)
        assertFalse(groupMentionSenderOnly.body.contains(secretText))

        val groupEmbeddedImg = ApnsNotificationFormatter.format(
            ApnsContentMode.SENDER_ONLY, "TeamTalk", ChatType.GROUP.code, "研发群", "张三", "u-target", pureEmbeddedImageMsg,
        )
        assertEquals("张三 发来一张[图片]", groupEmbeddedImg.body)

        val groupDoc = ApnsNotificationFormatter.format(
            ApnsContentMode.SENDER_ONLY, "TeamTalk", ChatType.GROUP.code, "研发群", "张三", "u-target", docMsg,
        )
        assertEquals("张三 分享了一篇[文档]", groupDoc.body)

        val groupAll = ApnsNotificationFormatter.format(
            ApnsContentMode.SENDER_ONLY, "TeamTalk", ChatType.GROUP.code, "研发群", "张三", "u-target", allMentionTextMsg,
        )
        assertEquals("[@所有人] 张三 发来一条消息", groupAll.body)

        val groupPlainImage = ApnsNotificationFormatter.format(
            ApnsContentMode.SENDER_ONLY, "TeamTalk", ChatType.GROUP.code, "研发群", "张三", "u-target", allMentionMsg,
        )
        assertEquals("张三 发来一张[图片]", groupPlainImage.body)

        val previewPersonal = ApnsNotificationFormatter.format(
            ApnsContentMode.PREVIEW, "TeamTalk", ChatType.PERSONAL.code, null, "老王", "u-target", fileMsg,
        )
        assertEquals("老王", previewPersonal.title)
        assertEquals("[文件] $secretDocName", previewPersonal.body)

        val generic = ApnsNotificationFormatter.format(
            ApnsContentMode.GENERIC, "TeamTalk", ChatType.GROUP.code, "研发群", "张三", "u-target", richTextMsg,
        )
        assertEquals("TeamTalk", generic.title)
        assertEquals(OEM_PUSH_CONTENT, generic.body)
    }

    @Test fun `apns configuration reads content mode from environment and defaults to sender_only`() {
        val pem = apnsFixturePrivateKey()
        fun env(mode: String?): Map<String, String> = buildMap {
            put("APNS_PUSH_ENABLED", "true")
            put("APNS_PUSH_TEAM_ID", "ABCDEFGHIJ")
            put("APNS_PUSH_KEY_ID", "KLMNOPQRST")
            put("APNS_PUSH_BUNDLE_ID", "com.example.app")
            put("APNS_PUSH_TITLE", "TeamTalk")
            put("APNS_PUSH_PRIVATE_KEY_BASE64", Base64.getEncoder().encodeToString(pem.toByteArray()))
            if (mode != null) put("APNS_PUSH_CONTENT_MODE", mode)
        }
        assertEquals(ApnsContentMode.SENDER_ONLY, ApnsPushConfiguration.fromEnvironment { env(null)[it] }?.contentMode)
        assertEquals(ApnsContentMode.PREVIEW, ApnsPushConfiguration.fromEnvironment { env("preview")[it] }?.contentMode)
        assertEquals(ApnsContentMode.GENERIC, ApnsPushConfiguration.fromEnvironment { env("generic")[it] }?.contentMode)
        assertFailsWith<IllegalArgumentException> {
            ApnsPushConfiguration.fromEnvironment { env("invalid_mode")[it] }
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
