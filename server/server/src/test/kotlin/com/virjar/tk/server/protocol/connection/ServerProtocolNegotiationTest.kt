package com.virjar.tk.server.protocol.connection

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.ProtocolLimits
import com.virjar.tk.protocol.ProtocolRange
import com.virjar.tk.protocol.ProtocolVersion
import com.virjar.tk.protocol.payload.AuthRequestPayload
import com.virjar.tk.protocol.payload.AuthResponsePayload
import com.virjar.tk.protocol.payload.ProtocolNegotiateRequestPayload
import com.virjar.tk.protocol.payload.ProtocolNegotiateResponsePayload
import com.virjar.tk.server.e2e.TcpE2eEnvironment
import com.virjar.tk.server.protocol.ServerProtocolConfiguration
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real TCP framing and production ImAgent, using only the fixture's isolated PostgreSQL schema. */
class ServerProtocolNegotiationTest {
    @Test
    fun `server negotiates the highest shared minor and rejects incompatible windows before auth`() {
        val server = ProtocolRange(0, 2, 4)
        TcpE2eEnvironment(ServerProtocolConfiguration(server)).use { environment ->
            Peer(environment.tcpPort).use { peer ->
                peer.send(14, ProtocolNegotiateRequestPayload(ProtocolRange(0, 0, 3)))
                val response = peer.receive(15, ProtocolNegotiateResponsePayload)
                assertEquals(ProtocolNegotiateResponsePayload.CODE_OK, response.code)
                assertEquals(server, response.server)
                assertEquals(ProtocolVersion(0, 3), response.negotiated)

                // Credentials are accepted only after negotiation, and still use the unchanged AUTH body.
                peer.send(PacketType.AUTH.code, registration())
                val authenticated = peer.receive(PacketType.AUTH_RESP.code, AuthResponsePayload)
                assertEquals(AuthResponsePayload.CODE_OK, authenticated.code)
                assertNotNull(authenticated.uid)
                assertEquals(environment.syncDatasetId, authenticated.datasetId)
            }

            listOf(
                ProtocolRange(1, 0, 4) to ProtocolNegotiateResponsePayload.CODE_MAJOR_UNSUPPORTED,
                ProtocolRange(0, 0, 1) to ProtocolNegotiateResponsePayload.CODE_CLIENT_TOO_OLD,
                ProtocolRange(0, 5, 6) to ProtocolNegotiateResponsePayload.CODE_SERVER_TOO_OLD,
            ).forEach { (client, expectedCode) ->
                Peer(environment.tcpPort).use { peer ->
                    peer.send(14, ProtocolNegotiateRequestPayload(client))
                    val response = peer.receive(15, ProtocolNegotiateResponsePayload)
                    assertEquals(expectedCode, response.code)
                    assertEquals(server, response.server)
                    assertNull(response.negotiated)
                    peer.assertClosed()
                }
            }
        }
    }

    @Test
    fun `legacy auth gets upgrade denial while corrupt auth and renegotiation close`() {
        TcpE2eEnvironment().use { environment ->
            Peer(environment.tcpPort).use { peer ->
                peer.send(PacketType.AUTH.code, registration())
                val response = peer.receive(PacketType.AUTH_RESP.code, AuthResponsePayload)
                assertEquals(AuthResponsePayload.CODE_VERSION_UNSUPPORTED, response.code)
                assertTrue(response.reason.orEmpty().contains("upgrade"))
                assertNull(response.uid)
                assertNull(response.accessToken)
                peer.assertClosed()
            }
            Peer(environment.tcpPort).use { peer ->
                // The third AUTH preamble byte is a fixed marker, not a compatibility version.
                val malformed = ProtoCodec.encode(registration()).also { it[2] = 1 }
                peer.send(PacketType.AUTH.code, malformed)
                peer.assertClosed()
            }
            Peer(environment.tcpPort).use { peer ->
                val request = ProtocolNegotiateRequestPayload()
                peer.send(14, request)
                assertEquals(
                    ProtocolNegotiateResponsePayload.CODE_OK,
                    peer.receive(15, ProtocolNegotiateResponsePayload).code,
                )
                peer.send(14, request)
                peer.assertClosed()
            }
        }
    }

    private fun registration(): AuthRequestPayload {
        val identity = UUID.randomUUID().toString()
        return AuthRequestPayload(
            authType = 1,
            username = "negotiation-${identity.take(12)}",
            password = "password123",
            name = "Negotiation test",
            deviceId = "negotiation-device",
            deviceName = "Negotiation test",
            correlationId = identity,
            connectionGeneration = 1L,
        )
    }

    private class Peer(port: Int) : AutoCloseable {
        private val socket = Socket("127.0.0.1", port).apply { soTimeout = 10_000 }
        private val input = DataInputStream(socket.getInputStream())
        private val output = DataOutputStream(socket.getOutputStream())

        fun send(type: Int, payload: IProto) {
            send(type, ProtoCodec.encode(payload))
        }

        fun send(type: Int, bytes: ByteArray) {
            output.writeByte(type)
            output.writeInt(bytes.size)
            output.write(bytes)
            output.flush()
        }

        fun <T : IProto> receive(type: Int, reader: IProtoReader<T>): T {
            assertEquals(type, input.readUnsignedByte())
            val length = input.readInt()
            require(length in 0..ProtocolLimits.MAX_UNAUTHENTICATED_PAYLOAD_SIZE)
            val bytes = ByteArray(length)
            input.readFully(bytes)
            return ProtoCodec.decode(reader, bytes)
        }

        fun assertClosed() = assertEquals(-1, input.read())

        override fun close() = socket.close()
    }
}
