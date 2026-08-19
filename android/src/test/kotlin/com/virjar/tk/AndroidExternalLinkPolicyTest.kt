package com.virjar.tk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidExternalLinkPolicyTest {

    @Test
    fun `allows browser and mail links`() {
        assertEquals("https://im.virjar.com/docs?q=1#intro", safeExternalLinkOrNull("https://im.virjar.com/docs?q=1#intro"))
        assertEquals("HTTP://example.com/path", safeExternalLinkOrNull("HTTP://example.com/path"))
        assertEquals("mailto:user@example.com?subject=TeamTalk", safeExternalLinkOrNull("mailto:user@example.com?subject=TeamTalk"))
    }

    @Test
    fun `rejects app schemes local resources and malformed web links`() {
        listOf(
            "javascript:alert(1)",
            "intent://profile#Intent;scheme=teamtalk;end",
            "teamtalk://profile/u1",
            "file:///data/local/tmp/secret",
            "/relative/path",
            "https:///missing-host",
            "https://user:password@example.com/private",
            "mailto://unexpected-authority",
            "not a uri",
            "",
        ).forEach { url ->
            assertNull(safeExternalLinkOrNull(url), "should reject $url")
        }
    }
}
