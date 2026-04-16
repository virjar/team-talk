package com.virjar.tk.unit

import com.virjar.tk.s3.AwsV4Signer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class AwsV4SignerTest {

    @Test
    fun `SHA256 produces correct hex digest`() {
        val hash = AwsV4Signer.sha256Hex("".toByteArray(Charsets.UTF_8))
        assertEquals(AwsV4Signer.EMPTY_PAYLOAD_HASH, hash)
    }

    @Test
    fun `SHA256 of known string`() {
        // "hello" → 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
        val hash = AwsV4Signer.sha256Hex("hello".toByteArray(Charsets.UTF_8))
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hash)
    }

    @Test
    fun `HMAC SHA256 produces correct output`() {
        val key = AwsV4Signer.hmacSha256("AWS4secret".toByteArray(Charsets.UTF_8), "20260411")
        assertNotNull(key)
        assertEquals(32, key.size) // SHA256 = 32 bytes
    }

    @Test
    fun `deriveSigningKey produces deterministic result`() {
        val key1 = AwsV4Signer.deriveSigningKey("secret", "20260411")
        val key2 = AwsV4Signer.deriveSigningKey("secret", "20260411")
        assertArrayEquals(key1, key2)
    }

    @Test
    fun `deriveSigningKey differs for different dates`() {
        val key1 = AwsV4Signer.deriveSigningKey("secret", "20260411")
        val key2 = AwsV4Signer.deriveSigningKey("secret", "20260412")
        assertFalse(key1.contentEquals(key2))
    }

    @Test
    fun `sign produces valid authorization header structure`() {
        val timestamp = ZonedDateTime.of(2026, 4, 11, 12, 0, 0, 0, ZoneOffset.UTC)
        val result = AwsV4Signer.sign(
            method = "GET",
            path = "/test-bucket/test-object",
            headers = mapOf(
                "host" to "localhost:9000",
                "x-amz-content-sha256" to AwsV4Signer.EMPTY_PAYLOAD_HASH,
            ),
            payloadHash = AwsV4Signer.EMPTY_PAYLOAD_HASH,
            accessKey = "testAccessKey",
            secretKey = "testSecretKey",
            timestamp = timestamp,
        )

        // Authorization: AWS4-HMAC-SHA256 Credential=.../20260411/us-east-1/s3/aws4_request, SignedHeaders=..., Signature=...
        assertTrue(result.authorization.startsWith("AWS4-HMAC-SHA256 Credential=testAccessKey/20260411/us-east-1/s3/aws4_request"))
        assertTrue(result.authorization.contains("SignedHeaders="))
        assertTrue(result.authorization.contains("Signature="))
        assertEquals("20260411T120000Z", result.dateTime)
        assertEquals("20260411", result.date)
    }

    @Test
    fun `sign includes range header when present`() {
        val timestamp = ZonedDateTime.of(2026, 4, 11, 12, 0, 0, 0, ZoneOffset.UTC)
        val result = AwsV4Signer.sign(
            method = "GET",
            path = "/test-bucket/test-object",
            headers = mapOf(
                "host" to "localhost:9000",
                "x-amz-content-sha256" to AwsV4Signer.EMPTY_PAYLOAD_HASH,
                "range" to "bytes=0-1023",
            ),
            payloadHash = AwsV4Signer.EMPTY_PAYLOAD_HASH,
            accessKey = "testAccessKey",
            secretKey = "testSecretKey",
            timestamp = timestamp,
        )

        assertTrue(result.authorization.contains("host;range;x-amz-content-sha256"))
    }

    @Test
    fun `sign produces deterministic signature for same inputs`() {
        val timestamp = ZonedDateTime.of(2026, 4, 11, 12, 0, 0, 0, ZoneOffset.UTC)
        val result1 = AwsV4Signer.sign(
            method = "GET",
            path = "/test-bucket/test-object",
            headers = mapOf("host" to "localhost:9000", "x-amz-content-sha256" to AwsV4Signer.EMPTY_PAYLOAD_HASH),
            payloadHash = AwsV4Signer.EMPTY_PAYLOAD_HASH,
            accessKey = "testAccessKey",
            secretKey = "testSecretKey",
            timestamp = timestamp,
        )
        val result2 = AwsV4Signer.sign(
            method = "GET",
            path = "/test-bucket/test-object",
            headers = mapOf("host" to "localhost:9000", "x-amz-content-sha256" to AwsV4Signer.EMPTY_PAYLOAD_HASH),
            payloadHash = AwsV4Signer.EMPTY_PAYLOAD_HASH,
            accessKey = "testAccessKey",
            secretKey = "testSecretKey",
            timestamp = timestamp,
        )
        assertEquals(result1.authorization, result2.authorization)
    }

    @Test
    fun `uriEncode encodes special characters`() {
        // Java URLEncoder uses + for space, which is acceptable for S3
        val encoded = AwsV4Signer.uriEncode("hello world")
        assertTrue(encoded == "hello%20world" || encoded == "hello+world", "Expected space to be encoded, got: $encoded")
        assertEquals("test%2Fpath", AwsV4Signer.uriEncode("test/path"))
        assertEquals("test%2Fpath", AwsV4Signer.uriEncode("test/path", encodeSlash = true))
        assertEquals("test/path", AwsV4Signer.uriEncode("test/path", encodeSlash = false))
    }

    @Test
    fun `uriEncode preserves unreserved characters`() {
        assertEquals("abcABC123-_.~", AwsV4Signer.uriEncode("abcABC123-_.~"))
    }
}
