package com.virjar.tk.server.e2e

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class PeerMediaMetadataTest {
    @Test
    fun `voice fixture declares its actual duration instead of twelve seconds`() {
        val file = File(checkNotNull(javaClass.getResource("/media/test_voice.mp3")).toURI())
        assertEquals(2, peerAudioDurationSeconds(file))
    }
}
