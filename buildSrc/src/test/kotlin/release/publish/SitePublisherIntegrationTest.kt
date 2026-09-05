package release.publish

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.KeyPair
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory

class SitePublisherIntegrationTest {
    @Test
    fun `site build number rejects downgrade reused counters changed same version and unversioned managed receipts`() {
        SftpFixture().use { fixture ->
            SitePublisher().publish(fixture.publication("0.0.2", 2), fixture.connection)
            assertFailsWith<IllegalArgumentException> {
                SitePublisher().publish(fixture.publication("0.0.1", 1), fixture.connection)
            }
            assertFailsWith<IllegalArgumentException> {
                SitePublisher().publish(fixture.publication("0.0.3", 2), fixture.connection)
            }
            assertFailsWith<IllegalArgumentException> {
                SitePublisher().publish(fixture.publication("0.0.2", 3), fixture.connection)
            }
            assertEquals("new android 0.0.2", File(fixture.downloads, "TeamTalk-android.apk").readText())
            val current = File(fixture.downloads, ".teamtalk-client-release.json")
            current.writeText(JsonObject(Json.parseToJsonElement(current.readText()).jsonObject - "releaseBuildNumber").toString())
            val missingCounter = assertFailsWith<IllegalStateException> {
                SitePublisher().publish(fixture.publication("0.0.3", 3), fixture.connection)
            }
            assertTrue(missingCounter.message.orEmpty().contains("migrate"))
        }
    }

    @Test
    fun `real SFTP publication preserves old site and unknown downloads and rejects changed version`() {
        SftpFixture().use { fixture ->
            fixture.installPreviousSite()
            val request = fixture.publication("0.0.1")
            val result = SitePublisher().publish(request, fixture.connection)
            assertFalse(result.alreadyPublished)
            assertEquals("new desktop 0.0.1", File(fixture.downloads, "desktop/download.html").readText())
            assertEquals("new android 0.0.1", File(fixture.downloads, "TeamTalk-android.apk").readText())
            assertEquals("private unrelated file", File(fixture.downloads, "customer.txt").readText())
            assertFalse(Files.isSymbolicLink(File(fixture.downloads, "desktop").toPath()))
            assertFalse(Files.isSymbolicLink(File(fixture.downloads, "TeamTalk-android.apk").toPath()))
            val history = File(fixture.downloads, ".teamtalk-client-releases")
            assertTrue(history.walkTopDown().any { it.name == "download.html" && it.readText() == "previous desktop" })
            assertTrue(history.walkTopDown().any { it.name == "TeamTalk-android.apk" && it.readText() == "previous android" })
            assertTrue(history.walkTopDown().any { it.name == "RELEASE_NOTES.md" && it.readText() == "人工发布说明 0.0.1" })
            assertTrue(SitePublisher().publish(request, fixture.connection).alreadyPublished)
            request.manifest.appendText("changed")
            assertFailsWith<IllegalArgumentException> { SitePublisher().publish(request, fixture.connection) }
            assertEquals("new desktop 0.0.1", File(fixture.downloads, "desktop/download.html").readText())
        }
    }

    @Test
    fun `failure between desktop and Android restores both old artifacts then retries`() {
        SftpFixture().use { fixture ->
            fixture.installPreviousSite()
            val request = fixture.publication("0.0.1")
            assertFailsWith<IllegalStateException> {
                SitePublisher { error("fixture interrupted between public switches") }.publish(request, fixture.connection)
            }
            assertEquals("previous desktop", File(fixture.downloads, "desktop/download.html").readText())
            assertEquals("previous android", File(fixture.downloads, "TeamTalk-android.apk").readText())
            assertFalse(File(fixture.downloads, ".teamtalk-client-transaction.json").exists())
            assertFalse(fixture.downloads.listFiles()!!.any { it.name.startsWith(".teamtalk-client-stage-") })
            SitePublisher().publish(request, fixture.connection)
            assertEquals("new android 0.0.1", File(fixture.downloads, "TeamTalk-android.apk").readText())
        }
    }

    @Test
    fun `separate publication connection cannot enter while first holds the lease`() {
        SftpFixture().use { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val request = fixture.publication("0.0.1")
            val first = CompletableFuture.supplyAsync {
                SitePublisher { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }.publish(request, fixture.connection)
            }
            try {
                assertTrue(entered.await(20, TimeUnit.SECONDS))
                assertFails { SitePublisher().publish(request, fixture.connection) }
            } finally { release.countDown() }
            first.get(20, TimeUnit.SECONDS)
            assertEquals("new desktop 0.0.1", File(fixture.downloads, "desktop/download.html").readText())
        }
    }

    @Test
    fun `an accepted mutation keeps the lease after transport loss until it finishes then the next release recovers`() {
        SftpFixture().use { fixture ->
            fixture.installPreviousSite()
            val request = fixture.publication("0.0.1")
            val entered = CountDownLatch(1)
            val completeMutation = CountDownLatch(1)
            val pauseOnce = AtomicBoolean(true)
            fixture.beforeMutation = { operation, _, target ->
                if (operation == "REPLACE" && target.endsWith("/.teamtalk-client-release.json") && pauseOnce.compareAndSet(true, false)) {
                    entered.countDown()
                    check(completeMutation.await(20, TimeUnit.SECONDS))
                }
            }
            val interrupted = CompletableFuture.supplyAsync { SitePublisher().publish(request, fixture.connection) }
            try {
                assertTrue(entered.await(20, TimeUnit.SECONDS))
                fixture.disconnectTransports()
                assertTrue(File(fixture.downloads, ".teamtalk-client-transaction.json").exists())
                // The already-read replacement may still complete, so another writer must not acquire yet.
                assertFails { SitePublisher().publish(request, fixture.connection) }
            } finally { completeMutation.countDown() }
            assertFails { interrupted.get(20, TimeUnit.SECONDS) }
            fixture.awaitReleasedLease()
            SitePublisher().publish(fixture.publication("0.0.2", 2), fixture.connection)
            assertFalse(File(fixture.downloads, ".teamtalk-client-transaction.json").exists())
            assertEquals("new desktop 0.0.2", File(fixture.downloads, "desktop/download.html").readText())
            assertEquals("new android 0.0.2", File(fixture.downloads, "TeamTalk-android.apk").readText())
        }
    }

    @Test
    fun `unknown host key fails before writing any public artifacts`() {
        SftpFixture().use { fixture ->
            val wrong = File(fixture.local, "wrong-known-hosts").apply { writeText("unknown.invalid " + PublicKeyEntry.toString(fixture.userKey.public) + "\n") }
            assertFails { SitePublisher().publish(fixture.publication("0.0.1"), fixture.connection.copy(knownHosts = wrong)) }
            assertFalse(File(fixture.downloads, "desktop").exists())
        }
    }

    @Test
    fun `OpenSSH Ed25519 identity works without local SSH binaries`() {
        SftpFixture(KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256)).use { fixture ->
            SitePublisher().publish(fixture.publication("0.0.1"), fixture.connection)
            assertEquals("new android 0.0.1", File(fixture.downloads, "TeamTalk-android.apk").readText())
        }
    }
}

/** Real Apache SSHD transport and SFTP filesystem; the remote flock command alone is modelled in JVM. */
private class SftpFixture(
    val userKey: KeyPair = KeyUtils.generateKeyPair(KeyPairProvider.SSH_RSA, 2048),
) : AutoCloseable {
    private val temporary = Files.createTempDirectory("teamtalk-sftp-publication-").toFile()
    val local = File(temporary, "local").apply { mkdirs() }
    private val remote = File(temporary, "remote").apply { mkdirs() }
    val downloads = File(remote, "opt/teamtalk/static/downloads")
    private val server = SshServer.setUpDefaultServer()
    private val leaseHeld = AtomicBoolean()
    var beforeMutation: (String, String, String) -> Unit = { _, _, _ -> }
    val connection: SiteConnection

    init {
        val privateKey = File(local, "id_test")
        privateKey.outputStream().use { OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(userKey, "publication-fixture", null, it) }
        val hostKeys = SimpleGeneratorHostKeyProvider(File(local, "host-key").toPath()).apply { algorithm = "RSA" }
        server.host = "127.0.0.1"
        server.port = 0
        server.keyPairProvider = hostKeys
        server.publickeyAuthenticator = org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { _, key, _ -> KeyUtils.compareKeys(userKey.public, key) }
        server.fileSystemFactory = VirtualFileSystemFactory(remote.toPath())
        server.subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
        server.commandFactory = CommandFactory { _, command ->
            check(command.startsWith("flock -n '") && command.contains(".teamtalk-client-release.lock"))
            FixtureFlockCommand(leaseHeld, remote.toPath()) { operation, source, target -> beforeMutation(operation, source, target) }
        }
        server.start()
        val knownHosts = File(local, "known_hosts").apply {
            writeText("[127.0.0.1]:${server.port} " + PublicKeyEntry.toString(hostKeys.loadKeys(null).first().public) + "\n")
        }
        connection = SiteConnection("127.0.0.1", server.port, "fixture", "/opt/teamtalk/static/downloads", privateKey, knownHosts)
    }

    fun installPreviousSite() {
        File(downloads, "desktop").mkdirs()
        File(downloads, "desktop/download.html").writeText("previous desktop")
        File(downloads, "TeamTalk-android.apk").writeText("previous android")
        File(downloads, "customer.txt").writeText("private unrelated file")
    }

    fun publication(version: String, buildNumber: Int = 1): SitePublication {
        val desktop = File(local, "desktop-$version").apply { mkdirs() }
        File(desktop, "download.html").writeText("new desktop $version")
        File(desktop, "TeamTalk-$version.zip").writeText("new desktop package $version")
        val apk = File(local, "TeamTalk-$version-android.apk").apply { writeText("new android $version") }
        val manifest = File(local, "release-manifest.json").apply { writeText("{\"version\":\"$version\"}") }
        val notes = File(local, "RELEASE_NOTES.md").apply { writeText("人工发布说明 $version") }
        return SitePublication(desktop, apk, version, buildNumber, manifest, listOf(manifest, notes))
    }

    fun disconnectTransports() {
        server.activeSessions.forEach { it.close(true).await(10_000) }
    }

    fun awaitReleasedLease() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (leaseHeld.get() && System.nanoTime() < deadline) Thread.sleep(10)
        check(!leaseHeld.get()) { "Fixture lease did not release after transport loss" }
    }

    override fun close() {
        server.stop(true)
        temporary.deleteRecursively()
    }
}

private class FixtureFlockCommand(
    private val held: AtomicBoolean,
    private val remoteRoot: Path,
    private val beforeMutation: (String, String, String) -> Unit,
) : Command {
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private lateinit var callback: ExitCallback
    private val owns = AtomicBoolean()

    override fun setInputStream(input: InputStream) { this.input = input }
    override fun setOutputStream(output: OutputStream) { this.output = output }
    override fun setErrorStream(error: OutputStream) = Unit
    override fun setExitCallback(callback: ExitCallback) { this.callback = callback }

    override fun start(channel: ChannelSession, env: Environment) {
        thread(name = "publication-fixture-flock", isDaemon = true) {
            if (!held.compareAndSet(false, true)) { callback.onExit(1); return@thread }
            owns.set(true)
            try {
                output.write("TEAMTALK_CLIENT_RELEASE_LOCKED\n".toByteArray())
                output.flush()
                val commands = input.bufferedReader(Charsets.UTF_8)
                while (true) {
                    val command = commands.readLine() ?: break
                    val fields = command.split('\t')
                    check(fields.size == 3)
                    val source = path(fields[1])
                    beforeMutation(fields[0], fields[1], fields[2])
                    val result = runCatching {
                        when (fields[0]) {
                            "MOVE" -> {
                                val target = path(fields[2])
                                check(!Files.exists(target, LinkOption.NOFOLLOW_LINKS))
                                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
                            }
                            "REPLACE" -> Files.move(source, path(fields[2]), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                            "REMOVE", "RMDIR" -> Files.delete(source)
                            else -> error("Unknown fixture publication operation")
                        }
                    }.fold({ 0 }, { 73 })
                    output.write("TEAMTALK_RESULT:$result\n".toByteArray())
                    output.flush()
                }
            } catch (_: Exception) {
                // Closing the transport terminates the fixture command just as EOF releases remote flock.
            } finally {
                release()
                callback.onExit(0)
            }
        }
    }

    override fun destroy(channel: ChannelSession) {
        // A received operation owns the lease until the command loop has finished it. Disconnect only
        // interrupts the next read, matching flock's inherited lock descriptor on the real Linux host.
        input.close()
    }

    private fun release() { if (owns.compareAndSet(true, false)) held.set(false) }

    private fun path(value: String): Path {
        check(value.startsWith("/opt/teamtalk/static/downloads/"))
        val path = remoteRoot.resolve(value.removePrefix("/")).normalize()
        check(path.startsWith(remoteRoot))
        return path
    }
}
