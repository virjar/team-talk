package com.virjar.tk.protocol

import com.virjar.tk.protocol.payload.AccountBannedPayload
import kotlin.test.Test
import kotlin.test.assertFailsWith

class AccountBannedPayloadTest {
    @Test
    fun `a cleanup terminal must carry a nonblank uid and a canonical dataset`() {
        for ((uid, dataset) in listOf(
            " " to "11111111-1111-4111-8111-111111111111",
            "user-1" to null,
            "user-1" to "arbitrary-path",
        )) {
            val bytes = ProtoCodec.encodePayload {
                writeString(uid)
                writeString(dataset)
                writeString(null)
            }
            assertFailsWith<ProtocolCorruptionException> {
                ProtoCodec.decode(AccountBannedPayload, bytes)
            }
        }
    }
}
