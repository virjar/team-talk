package release

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProtocolReleasePolicyTest {
    @Test
    fun `publication requires consolidation and an explicit frozen record`() = fixture { root ->
        writeDevelopment(root, 0, 4, original + "rpc\tmessage/2\t4\t-\tactive\tsearch(text:String):String\n")
        val failure = assertFailsWith<IllegalStateException> { prepare(root, "0.0.1", 1, 0, 4) }
        assertTrue(failure.message.orEmpty().contains("consolidating unpublished"))

        writeDevelopment(root, 0, 1, original + "rpc\tmessage/2\t1\t-\tactive\tsearch(text:String):String\n")
        assertFailsWith<IllegalStateException> { verify(root, "0.0.1", 1, 0, 1) }
        val frozen = prepare(root, "0.0.1", 1, 0, 1)
        assertEquals(frozen, verify(root, "0.0.1", 1, 0, 1))
        assertEquals(frozen, prepare(root, "0.0.1", 1, 0, 1))
        assertTrue(File(root, "protocol/protocol/releases/0.0.0/wire-baseline.tsv").isFile)
    }

    @Test
    fun `UI releases keep the protocol version and never overwrite earlier releases`() = fixture { root ->
        prepare(root, "0.0.1", 1, 0, 0)
        assertEquals("0.0.1", ProtocolReleasePolicy.latest(root).releaseVersion)
        assertFailsWith<IllegalStateException> { prepare(root, "0.0.0", 2, 0, 0) }
        assertFailsWith<IllegalStateException> { prepare(root, "0.0.2", 1, 0, 0) }
        writeDevelopment(root, 0, 1, original)
        assertFailsWith<IllegalStateException> { prepare(root, "0.0.2", 2, 0, 1) }
    }

    @Test
    fun `published contracts and tombstones can be replaced only in a new major`() = fixture { root ->
        writeDevelopment(root, 0, 1, original.replace("chatId:String", "chatId:Long"))
        assertFailsWith<IllegalStateException> { prepare(root, "0.0.1", 1, 0, 1) }
        writeDevelopment(root, 0, 1, "")
        assertFailsWith<IllegalStateException> { prepare(root, "0.0.1", 1, 0, 1) }

        writeDevelopment(root, 0, 1, original.replace("\t-\tactive", "\t1\tactive"))
        prepare(root, "0.0.1", 1, 0, 1)
        writeDevelopment(root, 0, 1, original.replace("\t-\tactive", "\t1\tretired"))
        prepare(root, "0.0.2", 2, 0, 1, 1)
        writeDevelopment(root, 0, 1, original)
        assertFailsWith<IllegalStateException> { prepare(root, "0.0.3", 3, 0, 1, 1) }

        writeDevelopment(root, 1, 0, original.replace("chatId:String", "chatId:Long"))
        prepare(root, "0.0.3", 3, 1, 0)
        assertTrue(File(root, "protocol/protocol/releases/0.0.1/wire-baseline.tsv").isFile)
        assertEquals(1, ProtocolReleasePolicy.latest(root).protocolMajor)
    }

    @Test
    fun `frozen history corruption is detected without GitHub`() = fixture { root ->
        File(root, "protocol/protocol/releases/0.0.0/wire-baseline.tsv").appendText("# edited\n")
        val failure = assertFailsWith<IllegalStateException> { ProtocolReleasePolicy.latest(root) }
        assertTrue(failure.message.orEmpty().contains("hash mismatch"))
    }

    @Test
    fun `private distribution freezes wire without changing the public product release`() = fixture { root ->
        val zero = File(root, "protocol/protocol/releases/0.0.0/release.properties").readText()
        writeDevelopment(root, 0, 1, original + addition)
        assertFailsWith<IllegalStateException> { ProtocolContractPolicy.verify(root, 0, 1, 0) }
        val frozen = ProtocolContractPolicy.prepare(root, 0, 1, 0)
        assertEquals(frozen, ProtocolContractPolicy.verify(root, 0, 1, 0))
        assertEquals(frozen, ProtocolContractPolicy.prepare(root, 0, 1, 0))
        assertEquals("0.0.0", ProtocolReleasePolicy.latest(root).releaseVersion)
        assertEquals(zero, File(root, "protocol/protocol/releases/0.0.0/release.properties").readText())
        assertTrue(frozen.wireBaseline.invariantSeparatorsPath.endsWith("contracts/0.1/wire-baseline.tsv"))

        writeDevelopment(root, 0, 1, original + addition.replace("text:String", "text:Long"))
        assertFailsWith<IllegalStateException> { ProtocolContractPolicy.prepare(root, 0, 1, 0) }
        assertFailsWith<IllegalStateException> { prepare(root, "0.0.1", 1, 0, 1) }
        writeDevelopment(root, 0, 0, original)
        assertFailsWith<IllegalStateException> { ProtocolContractPolicy.prepare(root, 0, 0, 0) }
        assertFailsWith<IllegalStateException> { prepare(root, "0.0.1", 1, 0, 0) }
    }

    @Test
    fun `a later product release adopts all already distributed private minors`() = fixture { root ->
        writeDevelopment(root, 0, 1, original + addition)
        ProtocolContractPolicy.prepare(root, 0, 1, 0)
        writeDevelopment(root, 0, 2, original + addition + "rpc\tmessage/3\t2\t-\tactive\tfind(id:String):String\n")
        ProtocolContractPolicy.prepare(root, 0, 2, 0)
        val release = prepare(root, "0.0.1", 1, 0, 2)
        assertEquals(2, release.protocolMinor)
        assertEquals(release, verify(root, "0.0.1", 1, 0, 2))
        assertEquals(2, prepare(root, "0.0.2", 2, 0, 2).protocolMinor)
        assertEquals(2, ProtocolContractPolicy.latest(root).protocolMinor)
    }

    @Test
    fun `private lifecycle reservations apply to later product retirement`() = fixture { root ->
        writeDevelopment(root, 0, 1, original.replace("\t-\tactive", "\t1\tactive"))
        ProtocolContractPolicy.prepare(root, 0, 1, 0)
        writeDevelopment(root, 0, 1, original.replace("\t-\tactive", "\t1\tretired"))
        prepare(root, "0.0.1", 1, 0, 1, 1)
        assertEquals(1, ProtocolContractPolicy.latest(root).minimumProtocolMinor)
        writeDevelopment(root, 0, 1, original)
        assertFailsWith<IllegalStateException> { prepare(root, "0.0.2", 2, 0, 1, 1) }
    }

    @Test
    fun `private contract preparation consolidates increments and detects corrupted history`() = fixture { root ->
        writeDevelopment(root, 0, 4, original + addition.replace("\t1\t-", "\t4\t-"))
        assertFailsWith<IllegalStateException> { ProtocolContractPolicy.prepare(root, 0, 4, 0) }
        assertTrue(!File(root, "protocol/protocol/contracts/0.4").exists())
        writeDevelopment(root, 0, 1, original + addition)
        val frozen = ProtocolContractPolicy.prepare(root, 0, 1, 0)
        frozen.wireBaseline.appendText("# changed\n")
        assertFailsWith<IllegalStateException> { ProtocolContractPolicy.latest(root) }
        assertFailsWith<IllegalStateException> { ProtocolReleasePolicy.latest(root) }
    }

    private fun prepare(root: File, version: String, build: Int, major: Int, minor: Int, minimum: Int = 0) =
        ProtocolReleasePolicy.prepare(root, version, build, major, minor, minimum)
    private fun verify(root: File, version: String, build: Int, major: Int, minor: Int, minimum: Int = 0) =
        ProtocolReleasePolicy.verify(root, version, build, major, minor, minimum)

    private fun writeDevelopment(root: File, major: Int, minor: Int, entries: String) {
        File(root, "protocol/protocol/wire-baseline.tsv").apply {
            parentFile.mkdirs()
            writeText("# TeamTalk wire schema v1\nmajor=$major\nminor=$minor\n$entries")
        }
    }

    private fun fixture(action: (File) -> Unit) {
        val root = Files.createTempDirectory("teamtalk-protocol-release-test").toFile()
        try {
            writeDevelopment(root, 0, 0, original)
            val frozen = File(root, "protocol/protocol/releases/0.0.0").apply { mkdirs() }
            val schema = File(root, "protocol/protocol/wire-baseline.tsv").copyTo(File(frozen, "wire-baseline.tsv"))
            val hash = MessageDigest.getInstance("SHA-256").digest(schema.readBytes()).joinToString("") { "%02x".format(it) }
            File(frozen, "release.properties").writeText("""
                releaseVersion=0.0.0
                releaseBuildNumber=0
                protocolMajor=0
                protocolMinor=0
                minimumProtocolMinor=0
                wireSchemaSha256=$hash
            """.trimIndent() + "\n")
            action(root)
        } finally {
            root.deleteRecursively()
        }
    }

    companion object {
        private const val original = "rpc\tmessage/1\t0\t-\tactive\thistory(chatId:String):String\n"
        private const val addition = "rpc\tmessage/2\t1\t-\tactive\tsearch(text:String):String\n"
    }
}
