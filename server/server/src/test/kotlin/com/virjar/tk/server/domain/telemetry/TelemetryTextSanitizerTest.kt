package com.virjar.tk.server.domain.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelemetryTextSanitizerTest {
    @Test
    fun `server redacts secrets and identity material independently of the client`() {
        val raw = """
            request https://example.test/private?token=visible
            file /Users/example/private/chat.txt and C:\\Users\\example\\secret.txt
            authorization: Bearer synthetic-authorization-value api_key=client-secret-value
            jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijklmnopqrstuvwxyz
            phone 13800138000 email person@example.test
            uid 123e4567-e89b-42d3-a456-426614174000 opaque AbCdEfGhIjKlMnOpQrStUvWx12345678
        """.trimIndent()

        val sanitized = sanitizeTelemetryDiagnosticText(raw)

        listOf(
            "example.test",
            "/Users/example",
            "C:\\Users",
            "client-secret-value",
            "synthetic-authorization-value",
            "eyJhbGci",
            "13800138000",
            "person@example.test",
            "123e4567",
            "AbCdEfGh",
        ).forEach { secret -> assertFalse(sanitized.contains(secret, ignoreCase = true), secret) }
        assertTrue(sanitized.contains("[credential-redacted]"))
        assertTrue(sanitized.contains("[phone-redacted]"))
    }

    @Test
    fun `ordinary bounded diagnostic context remains searchable`() {
        val sanitized = sanitizeTelemetryDiagnosticText(
            "Desktop media cache failed after window focus changed",
        )

        assertTrue(sanitized.contains("media cache failed"))
        assertTrue(sanitized.contains("window focus changed"))
    }

    @Test
    fun `runtime and stable fields retain legitimate facts but reject identity channels`() {
        assertEquals("Mac OS X", sanitizeTelemetryRuntimeText("Mac OS X", 128))
        assertEquals("unknown", sanitizeTelemetryRuntimeText("https://private.example/device", 128))
        assertEquals("unknown", sanitizeTelemetryRuntimeText("13800138000", 128))
        assertEquals("unknown", sanitizeTelemetryRuntimeText("AbCdEfGhIjKlMnOpQrStUvWx12345678", 128))
        assertEquals("connection_state", sanitizeTelemetryStableText("connection_state"))
        assertEquals(
            "redacted",
            sanitizeTelemetryStableText("123e4567-e89b-42d3-a456-426614174000"),
        )
        assertEquals(
            "redacted",
            sanitizeTelemetryStableText("AbCdEfGhIjKlMnOpQrStUvWx12345678"),
        )
    }

    @Test
    fun `structured credentials formatted phones paths and invisible format characters are removed`() {
        val sanitized = sanitizeTelemetryDiagnosticText(
            "普通中文诊断文案 成功/失败 com.example.Service.handle pkg/Class " +
                "{\"client_secret\":\"short-synthetic-value\",\"refresh_token\":\"refresh-synthetic-value\"} " +
                "phones 138-0013-8000 and +1 (202) 555-0199 " +
                "paths \\\\synthetic-host\\private-share\\note.txt ../private/note.txt " +
                "/private-note.txt C:\\private-note.txt " +
                "to\u200Bken=zero-width-synthetic-value bidi\u202Etext",
        )

        listOf(
            "short-synthetic-value",
            "refresh-synthetic-value",
            "138-0013-8000",
            "+1 (202) 555-0199",
            "synthetic-host",
            "../private/note.txt",
            "/private-note.txt",
            "C:\\private-note.txt",
            "zero-width-synthetic-value",
        ).forEach { privateMaterial -> assertFalse(sanitized.contains(privateMaterial), privateMaterial) }
        assertFalse('\u200B' in sanitized)
        assertFalse('\u202E' in sanitized)
        assertTrue(sanitized.contains("普通中文诊断文案"))
        assertTrue(sanitized.contains("成功/失败"))
        assertTrue(sanitized.contains("com.example.Service.handle"))
        assertTrue(sanitized.contains("pkg/Class"))
        assertEquals("Linux", sanitizeTelemetryRuntimeText("Li\u202Enux", 128))
        assertEquals("connection_state", sanitizeTelemetryStableText("connection\u200B_state"))
    }
}
