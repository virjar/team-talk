package deployment

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TcpTlsCertificatesTest {
    @Test
    fun `IP certificate persists across repeated builds and rejects another endpoint`() {
        val root = Files.createTempDirectory("teamtalk-tcp-certificate-test-").toFile()
        try {
            val directory = File(root, "tcp-tls")
            generateTcpTlsCertificate(directory, "192.0.2.10")
            val certificate = File(directory, "certificate.pem").readBytes()
            val key = File(directory, "private-key.pem").readBytes()
            val parsed = readTcpTlsCertificate(certificate.toString(Charsets.UTF_8))
            assertTrue(parsed.subjectAlternativeNames.any { it[0] == 7 && it[1] == "192.0.2.10" })
            parsed.verify(parsed.publicKey)
            generateTcpTlsCertificate(directory, "192.0.2.10")
            assertContentEquals(certificate, File(directory, "certificate.pem").readBytes())
            assertContentEquals(key, File(directory, "private-key.pem").readBytes())
            assertFailsWith<IllegalArgumentException> { generateTcpTlsCertificate(directory, "192.0.2.11") }
            File(directory, "private-key.pem").delete()
            assertFailsWith<IllegalArgumentException> { generateTcpTlsCertificate(directory, "192.0.2.10") }
            assertContentEquals(certificate, File(directory, "certificate.pem").readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `hostname certificate uses DNS SAN and invalid host does not create material`() {
        val root = Files.createTempDirectory("teamtalk-tcp-certificate-test-").toFile()
        try {
            val directory = File(root, "tcp-tls")
            assertFailsWith<IllegalArgumentException> { generateTcpTlsCertificate(directory, "999.1.2.3") }
            assertTrue(!directory.exists())
            generateTcpTlsCertificate(directory, "teamtalk.lan")
            requireTcpCertificateHost(readTcpTlsCertificate(File(directory, "certificate.pem").readText()), "teamtalk.lan")
        } finally {
            root.deleteRecursively()
        }
    }
}
