package com.virjar.tk.protocol

import com.virjar.tk.protocol.body.AttachmentPolicy
import com.virjar.tk.protocol.body.CardBody
import com.virjar.tk.protocol.body.EditBody
import com.virjar.tk.protocol.body.ForwardBody
import com.virjar.tk.protocol.body.InteractiveCardBody
import com.virjar.tk.protocol.body.LocationBody
import com.virjar.tk.protocol.body.MergeForwardBody
import com.virjar.tk.protocol.body.MessageBodyPolicy
import com.virjar.tk.protocol.body.ReactionBody
import com.virjar.tk.protocol.body.ReplyBody
import com.virjar.tk.protocol.body.RevokeBody
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.payload.AuthPayloadPolicy
import com.virjar.tk.protocol.payload.AuthRequestPayload
import com.virjar.tk.protocol.payload.InvokePayload
import com.virjar.tk.protocol.payload.MAX_RPC_ENVELOPE_BODY_BYTES
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.protocol.payload.SyncBatchPayload
import com.virjar.tk.protocol.telemetry.ConnectionTraceContextPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PacketBufferSecurityTest {

    @Test
    fun `primitive values retain network byte order and signed semantics`() {
        val writer = PacketBuffer().apply {
            writeByte(0xFE)
            writeShort(-2)
            writeInt(Int.MIN_VALUE)
            writeLong(Long.MIN_VALUE + 7)
        }

        assertTrue(
            writer.toByteArray().take(7).toByteArray().contentEquals(
                byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0xFE.toByte(), 0x80.toByte(), 0, 0, 0),
            ),
        )
        val reader = PacketBuffer(writer.toByteArray())
        assertEquals(0xFE, reader.readByte())
        assertEquals(-2, reader.readShort())
        assertEquals(Int.MIN_VALUE, reader.readInt())
        assertEquals(Long.MIN_VALUE + 7, reader.readLong())
        reader.requireExhausted()
    }

    @Test
    fun `sync batch rejects oversized event count before allocating entries`() {
        val writer = PacketBuffer().apply {
            writeVarInt(SyncBatchPayload.MAX_EVENTS + 1)
        }
        assertFailsWith<ProtocolCorruptionException> {
            SyncBatchPayload.readFrom(PacketBuffer(writer.toByteArray()))
        }
    }

    @Test
    fun `sync batch prefix observes wire budget and exposes standalone fallback`() {
        val events = (1L..3L).map { eventId ->
            NotifyPayload(
                eventId = eventId,
                notifyType = NotifyType.MESSAGE_RECV.code,
                payload = ByteArray(17) { eventId.toByte() },
            )
        }
        val oneEventBytes = SyncBatchPayload.eventWireSize(events.first()).toInt()
        assertTrue(
            SyncBatchPayload.boundedPrefix(events, maximumWireBytes = oneEventBytes).isEmpty(),
            "a standalone event may fit even when the batch count byte does not",
        )

        val firstTwoBudget = 1 + events.take(2).sumOf { SyncBatchPayload.eventWireSize(it) }.toInt()
        assertEquals(
            listOf(1L, 2L),
            SyncBatchPayload.boundedPrefix(events, maximumWireBytes = firstTwoBudget).map { it.eventId },
        )
    }

    @Test
    fun `varints reject overflow and continuation beyond their wire width`() {
        assertEquals(Int.MAX_VALUE, buffer(0xff, 0xff, 0xff, 0xff, 0x07).readVarInt())
        assertFailsWith<ProtocolCorruptionException> {
            buffer(0xff, 0xff, 0xff, 0xff, 0x0f).readVarInt()
        }
        assertFailsWith<ProtocolCorruptionException> {
            buffer(0x80, 0x80, 0x80, 0x80, 0x80).readVarInt()
        }
        assertFailsWith<ProtocolCorruptionException> {
            buffer(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80).readVarLong()
        }
    }

    @Test
    fun `varints only accept their shortest canonical encoding`() {
        assertEquals(128, buffer(0x80, 0x01).readVarInt())
        assertEquals(128L, buffer(0x80, 0x01).readVarLong())

        listOf(
            intArrayOf(0x80, 0x00),
            intArrayOf(0x81, 0x00),
            intArrayOf(0xff, 0x00),
        ).forEach { bytes ->
            assertFailsWith<ProtocolCorruptionException> { buffer(*bytes).readVarInt() }
            assertFailsWith<ProtocolCorruptionException> { buffer(*bytes).readVarLong() }
        }
    }

    @Test
    fun `varint writers reject negative values`() {
        val writer = PacketBuffer()
        assertFailsWith<IllegalArgumentException> { writer.writeVarInt(-1) }
        assertFailsWith<IllegalArgumentException> { writer.writeVarLong(-1) }
        assertEquals(0, writer.toByteArray().size)
    }

    @Test
    fun `boolean values and presence markers are canonical`() {
        assertFalse(buffer(0).readBoolean("test flag"))
        assertTrue(buffer(1).readBoolean("test flag"))
        assertFailsWith<ProtocolCorruptionException> { buffer(2).readBoolean("test flag") }

        val writer = PacketBuffer()
        writer.writeBoolean(false)
        writer.writeBoolean(true)
        assertTrue(writer.toByteArray().contentEquals(byteArrayOf(0, 1)))
    }

    @Test
    fun `strings reject malformed UTF-8 and null required values`() {
        assertFailsWith<ProtocolCorruptionException> {
            buffer(1, 2, 0xc0, 0xaf).readString()
        }
        assertFailsWith<ProtocolCorruptionException> {
            buffer(1, 1, 0x80).readRequiredString(fieldName = "required")
        }
        assertFailsWith<ProtocolCorruptionException> {
            buffer(0).readRequiredString(fieldName = "required")
        }

        val writer = PacketBuffer()
        assertFailsWith<kotlin.text.CharacterCodingException> {
            writer.writeString("\uD800")
        }
        assertEquals(0, writer.toByteArray().size)
    }

    @Test
    fun `length delimited values reject impossible allocation before creating arrays`() {
        val declaredIntMax = intArrayOf(1, 0xff, 0xff, 0xff, 0xff, 0x07)
        assertFailsWith<ProtocolCorruptionException> { buffer(*declaredIntMax).readString() }
        assertFailsWith<ProtocolCorruptionException> { buffer(*declaredIntMax).readBytes() }
    }

    @Test
    fun `tiny rich text payload cannot preallocate an unbounded mentions list`() {
        val writer = PacketBuffer()
        writer.writeString("")
        writer.writeVarInt(Int.MAX_VALUE)

        assertFailsWith<ProtocolCorruptionException> {
            RichTextBody.readFrom(PacketBuffer(writer.toByteArray()))
        }
    }

    @Test
    fun `tiny reply payload cannot preallocate an unbounded embedded asset list`() {
        val writer = PacketBuffer().apply {
            writeString("message-1")
            writeString("user-1")
            writeString(null)
            writeString(null)
            writeString("")
            writeVarInt(Int.MAX_VALUE)
        }

        assertFailsWith<ProtocolCorruptionException> {
            ReplyBody.readFrom(PacketBuffer(writer.toByteArray()))
        }
    }

    @Test
    fun `message envelope and every string based body reject oversized wire fields before allocation`() {
        assertDeclaredStringRejected(MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_CHAT_ID_LENGTH)) {
            Message.readFrom(it)
        }
        assertDeclaredStringRejected(MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_MARKDOWN_LENGTH)) {
            RichTextBody.readFrom(it)
        }
        assertDeclaredStringRejected(
            MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_INTERACTIVE_CARD_JSON_LENGTH),
        ) {
            InteractiveCardBody.readFrom(it)
        }
        assertDeclaredStringRejected(MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_IDENTIFIER_LENGTH)) {
            ReplyBody.readFrom(it)
        }
        assertDeclaredStringRejected(MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_IDENTIFIER_LENGTH)) {
            ForwardBody.readFrom(it)
        }
        assertDeclaredStringRejected(MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_DISPLAY_NAME_LENGTH)) {
            MergeForwardBody.readFrom(it)
        }
        assertDeclaredStringRejected(MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_IDENTIFIER_LENGTH)) {
            RevokeBody.readFrom(it)
        }
        assertDeclaredStringRejected(MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_IDENTIFIER_LENGTH)) {
            EditBody.readFrom(it)
        }
        assertDeclaredStringRejected(MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_IDENTIFIER_LENGTH)) {
            ReactionBody.readFrom(it)
        }
        assertDeclaredStringRejected(MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_IDENTIFIER_LENGTH)) {
            CardBody.readFrom(it)
        }
        assertDeclaredStringRejected(MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_COORDINATE_TEXT_LENGTH)) {
            LocationBody.readFrom(it)
        }
        assertDeclaredStringRejected(MessageBodyPolicy.utf8WireLimit(AttachmentPolicy.MAX_REFERENCE_LENGTH)) {
            Attachment.readFrom(it)
        }
    }

    @Test
    fun `message body presence marker is strictly boolean`() {
        val writer = PacketBuffer()
        writer.writeString("chat")
        writer.writeString("client-message")
        writer.writeVarLong(0)
        writer.writeString("sender")
        writer.writeByte(MessageType.TYPING.code)
        writer.writeVarLong(0)
        writer.writeVarInt(0)
        writer.writeByte(2)

        assertFailsWith<ProtocolCorruptionException> {
            Message.readFrom(PacketBuffer(writer.toByteArray()))
        }
    }

    @Test
    fun `invoke payload rejects more than four mebibytes from a tiny declaration`() {
        val oversizedBody = ByteArray(InvokePayload.MAX_INVOKE_PAYLOAD_BYTES + 1)
        val outbound = PacketBuffer()
        assertFailsWith<ProtocolEncodingException> {
            InvokePayload(1, "service", 1, oversizedBody).writeTo(outbound)
        }
        assertEquals(0, outbound.toByteArray().size)

        val writer = PacketBuffer()
        writer.writeVarInt(1)
        writer.writeString("service")
        writer.writeVarInt(1)
        writer.writeByte(1)
        writer.writeVarInt(InvokePayload.MAX_INVOKE_PAYLOAD_BYTES + 1)

        assertFailsWith<ProtocolCorruptionException> {
            InvokePayload.readFrom(PacketBuffer(writer.toByteArray()))
        }
    }

    @Test
    fun `response payload reserves space for its outer envelope`() {
        val oversizedBody = ByteArray(MAX_RPC_ENVELOPE_BODY_BYTES + 1)
        val outbound = PacketBuffer()

        assertFailsWith<ProtocolEncodingException> {
            ResponsePayload(requestId = 1, status = 0, payload = oversizedBody).writeTo(outbound)
        }
        assertEquals(0, outbound.toByteArray().size)

        val writer = PacketBuffer()
        writer.writeVarInt(1)
        writer.writeVarInt(0)
        writer.writeByte(1)
        writer.writeVarInt(MAX_RPC_ENVELOPE_BODY_BYTES + 1)
        assertFailsWith<ProtocolCorruptionException> {
            ResponsePayload.readFrom(PacketBuffer(writer.toByteArray()))
        }
    }

    @Test
    fun `every auth request string has a strict wire budget`() {
        val limits = listOf(
            AuthPayloadPolicy.MAX_USERNAME_LENGTH,
            AuthPayloadPolicy.MAX_PASSWORD_LENGTH,
            AuthPayloadPolicy.MAX_NAME_LENGTH,
            AuthPayloadPolicy.MAX_TOKEN_LENGTH,
            AuthPayloadPolicy.MAX_DEVICE_ID_LENGTH,
            AuthPayloadPolicy.MAX_DEVICE_NAME_LENGTH,
            AuthPayloadPolicy.MAX_DEVICE_MODEL_LENGTH,
        ).map(AuthPayloadPolicy::utf8WireLimit) + ConnectionTraceContextPolicy.MAX_TOKEN_LENGTH

        limits.indices.forEach { oversizedField ->
            val writer = PacketBuffer()
            writer.writeByte(AuthRequestPayload.PREAMBLE_HIGH)
            writer.writeByte(AuthRequestPayload.PREAMBLE_LOW)
            writer.writeByte(ProtocolLimits.AUTH_PREAMBLE_MARKER.toInt())
            writer.writeByte(0x01)
            writer.writeVarInt(0)
            limits.take(7).forEachIndexed { field, maximumBytes ->
                if (field == oversizedField) {
                    writer.writeByte(1)
                    writer.writeVarInt(maximumBytes + 1)
                } else {
                    // deviceId（索引 4）非空；其余可选字段用 null 保持 wire 最小。
                    writer.writeString(if (field == 4) "device" else null)
                }
            }
            writer.writeVarInt(0)
            if (oversizedField == 7) {
                writer.writeByte(1)
                writer.writeVarInt(ConnectionTraceContextPolicy.MAX_TOKEN_LENGTH + 1)
            } else {
                writer.writeString("correlation-token-1")
            }
            writer.writeVarLong(1L)

            assertFailsWith<ProtocolCorruptionException>("auth field $oversizedField must be bounded") {
                AuthRequestPayload.readFrom(PacketBuffer(writer.toByteArray()))
            }
        }
    }

    @Test
    fun `auth wire rejects missing unsafe or non-positive connection identity`() {
        fun encoded(correlationId: String?, generation: Long): ByteArray = PacketBuffer().apply {
            writeByte(AuthRequestPayload.PREAMBLE_HIGH)
            writeByte(AuthRequestPayload.PREAMBLE_LOW)
            writeByte(ProtocolLimits.AUTH_PREAMBLE_MARKER.toInt())
            writeByte(0x01)
            writeVarInt(2)
            repeat(4) { writeString(null) }
            writeString("device-1")
            writeString(null)
            writeString(null)
            writeVarInt(0)
            writeString(correlationId)
            writeVarLong(generation)
        }.toByteArray()

        listOf(
            encoded(null, 1L),
            encoded("unsafe.correlation.token", 1L),
            encoded("correlation-token-1", 0L),
        ).forEach { payload ->
            assertFailsWith<ProtocolCorruptionException> {
                AuthRequestPayload.readFrom(PacketBuffer(payload))
            }
        }
    }

    @Test
    fun `auth character limits are enforced symmetrically on write and read`() {
        val oversizedName = "n".repeat(AuthPayloadPolicy.MAX_NAME_LENGTH + 1)
        val oversizedDeviceName = "d".repeat(AuthPayloadPolicy.MAX_DEVICE_NAME_LENGTH + 1)

        listOf(
            AuthRequestPayload(
                authType = 1,
                username = "valid-user",
                password = "password123",
                name = oversizedName,
                deviceId = "device-1",
                correlationId = "correlation-token-1",
                connectionGeneration = 1L,
            ),
            AuthRequestPayload(
                authType = 1,
                username = "valid-user",
                password = "password123",
                name = "Valid User",
                deviceId = "device-1",
                deviceName = oversizedDeviceName,
                correlationId = "correlation-token-1",
                connectionGeneration = 1L,
            ),
        ).forEach { payload ->
            val outbound = PacketBuffer()
            assertFailsWith<ProtocolEncodingException> { payload.writeTo(outbound) }
            assertEquals(0, outbound.toByteArray().size)
        }

        assertFailsWith<ProtocolCorruptionException> {
            AuthRequestPayload.readFrom(PacketBuffer(rawRegisterAuth(name = oversizedName)))
        }
        assertFailsWith<ProtocolCorruptionException> {
            AuthRequestPayload.readFrom(
                PacketBuffer(rawRegisterAuth(name = "Valid User", deviceName = oversizedDeviceName)),
            )
        }

        val boundary = AuthRequestPayload(
            authType = 1,
            username = "valid-user",
            password = "password123",
            name = "n".repeat(AuthPayloadPolicy.MAX_NAME_LENGTH),
            deviceId = "device-1",
            deviceName = "d".repeat(AuthPayloadPolicy.MAX_DEVICE_NAME_LENGTH),
            deviceModel = "m".repeat(AuthPayloadPolicy.MAX_DEVICE_MODEL_LENGTH),
            correlationId = "correlation-token-1",
            connectionGeneration = 1L,
        )
        val boundaryWriter = PacketBuffer()
        boundary.writeTo(boundaryWriter)
        val encoded = boundaryWriter.toByteArray()
        assertEquals(boundary, AuthRequestPayload.readFrom(PacketBuffer(encoded)))
    }

    private fun rawRegisterAuth(
        name: String,
        deviceName: String? = null,
        deviceModel: String? = null,
    ): ByteArray = PacketBuffer().apply {
        writeByte(AuthRequestPayload.PREAMBLE_HIGH)
        writeByte(AuthRequestPayload.PREAMBLE_LOW)
        writeByte(ProtocolLimits.AUTH_PREAMBLE_MARKER.toInt())
        writeByte(0x01)
        writeVarInt(1)
        writeString("valid-user")
        writeString("password123")
        writeString(name)
        writeString(null)
        writeString("device-1")
        writeString(deviceName)
        writeString(deviceModel)
        writeVarInt(0)
        writeString("correlation-token-1")
        writeVarLong(1L)
    }.toByteArray()

    private fun assertDeclaredStringRejected(maximumBytes: Int, reader: (PacketBuffer) -> Any?) {
        val writer = PacketBuffer()
        writer.writeByte(1)
        writer.writeVarInt(maximumBytes + 1)
        assertFailsWith<ProtocolCorruptionException> {
            reader(PacketBuffer(writer.toByteArray()))
        }
    }

    private fun buffer(vararg bytes: Int): PacketBuffer =
        PacketBuffer(ByteArray(bytes.size) { index -> bytes[index].toByte() })
}
