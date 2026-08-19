package com.virjar.tk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopExternalLinkPolicyTest {

    @Test
    fun `allows browser and mail links`() {
        assertEquals(
            "https://im.virjar.com/docs?q=1#intro",
            safeDesktopExternalLinkOrNull("https://im.virjar.com/docs?q=1#intro"),
        )
        assertEquals("HTTP://example.com/path", safeDesktopExternalLinkOrNull("HTTP://example.com/path"))
        assertEquals(
            "mailto:user@example.com?subject=TeamTalk",
            safeDesktopExternalLinkOrNull("mailto:user@example.com?subject=TeamTalk"),
        )
    }

    @Test
    fun `rejects local custom credential and hostless links`() {
        listOf(
            "file:///tmp/secret",
            "smb://fileserver/private",
            "teamtalk://profile/u1",
            "javascript:alert(1)",
            "https://user:password@example.com/private",
            "https:///missing-host",
            "mailto://unexpected-authority",
            "/relative/path",
            "not a uri",
            "",
        ).forEach { url ->
            assertNull(safeDesktopExternalLinkOrNull(url), "should reject $url")
        }
    }
}
