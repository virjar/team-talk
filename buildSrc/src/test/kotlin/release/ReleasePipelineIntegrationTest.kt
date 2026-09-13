package release

import com.android.apksig.ApkSigner
import com.sun.net.httpserver.HttpServer
import deployment.DeploymentConfig
import deployment.ProcessOutputMode
import deployment.ProcessSpec
import deployment.runCheckedProcess
import kotlinx.serialization.json.*
import release.publish.ClientReleasePublisher
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.*

/** Real APK signing, archive sealing and multipart HTTP share the producer's directory layout. */
class ReleasePipelineIntegrationTest {
    @Test
    fun `assembled bundle can be verified reused and uploaded without changing payload paths`() {
        val root = Files.createTempDirectory("teamtalk-release-pipeline-").toFile()
        val identity = BundleIdentity(ReleaseVersion("0.0.2", 2, 0, 3, 0), "a".repeat(40),
            DeploymentConfig("https://im.example.com", "im.example.com:5100", "im.example.com"), "snapshot", "b".repeat(64), 42)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            val shells = File(root, "shells")
            val payloads = File(root, "payloads")
            ReleaseBundle.DESKTOP_TARGETS.forEach { key ->
                zip(File(payloads, "$key/payload.zip"), mapOf(
                    "payload.properties" to "version=0.0.2\nbuild=42\nminShellAbi=1\nbuildIdentity=${identity.buildIdentity}\n",
                    "lib/app.jar" to "application bytes for $key",
                ))
                val extension = if (key.startsWith("linux")) "tar.gz" else "zip"
                File(shells, "$key/TeamTalk-$key.$extension").apply { parentFile.mkdirs(); writeText("shell") }
                val installerExtension = when {
                    key.startsWith("windows") -> "exe"
                    key.startsWith("linux") -> "deb"
                    else -> null
                }
                installerExtension?.let {
                    File(shells, "$key/installer/TeamTalk-$key.$it").apply { parentFile.mkdirs(); writeText("installer") }
                    File(shells, "$key/installer/build-script.nsi").writeText("build input, not an installer")
                }
            }
            val firstPayload = File(payloads, "macos-aarch64/payload.zip")
            val originalPayload = firstPayload.readBytes()
            zip(firstPayload, mapOf("payload.properties" to
                "version=0.0.2\nbuild=42\nminShellAbi=1\nbuildIdentity=0.0.2+wrong-source\n"))
            assertFailsWith<IllegalArgumentException> {
                ReleaseBundle.verifyDesktopArtifacts(shells, payloads, identity)
            }
            firstPayload.writeBytes(originalPayload)
            val apkDir = File(root, "android").apply { mkdirs() }
            val unsigned = File(root, "unsigned.apk")
            writeAndroidApkFixture(unsigned, identity)
            signApk(root, unsigned, File(apkDir, "client.apk"))
            File(apkDir, "output-metadata.json").writeText(buildJsonObject {
                put("applicationId", identity.client.androidApplicationId)
                putJsonArray("elements") { add(buildJsonObject {
                    put("versionName", identity.version.name); put("versionCode", 3); put("outputFile", "client.apk")
                }) }
            }.toString())
            val serverZip = File(root, "server.zip")
            zip(serverZip, mapOf("server/teamtalk-release.properties" to
                "artifactType=server-distribution\nbuildIdentity=${identity.buildIdentity}\n"))
            val headlessDir = File(root, "headless")
            writeHeadlessDistributionFixture(headlessDir, identity.version, identity.buildIdentity)
            val headlessZip = File(root, "headless.zip")
            HeadlessDistribution.archive(headlessDir, headlessZip, identity.version, identity.buildIdentity)
            val bundle = ReleaseBundle.assemble(File(root, "sealed"), identity, shells, payloads, apkDir,
                serverZip, "# Notes\n", "# Commits\n", headlessZip)
            ReleaseBundle.verify(bundle, identity, "# Notes\n")
            assertEquals(4, ReleaseBundle.assets(bundle).size)
            val publishedAssets = ReleaseBundle.assets(bundle) + ReleaseBundle.desktopArtifacts(bundle)
            assertEquals(publishedAssets.size, publishedAssets.map { it.name }.distinct().size)
            assertTrue(ReleaseBundle.desktopArtifacts(bundle).none { it.extension == "nsi" })
            assertEquals(bundle, ReleaseBundle.assemble(bundle, identity, shells, payloads, apkDir,
                serverZip, "# Notes\n", "# Commits\n", headlessZip))

            val uploads = mutableListOf<JsonObject>()
            server.createContext("/api/v1/client/releases") { exchange ->
                try {
                    assertEquals("test-publish-token", exchange.requestHeaders.getFirst("X-Publish-Token"))
                    val boundary = exchange.requestHeaders.getFirst("Content-Type").substringAfter("boundary=")
                    val bytes = exchange.requestBody.use { it.readBytes() }
                    val headerEnd = bytes.toString(Charsets.ISO_8859_1).indexOf("\r\n\r\n") + 4
                    val suffix = "\r\n--$boundary--\r\n".toByteArray()
                    val upload = File(root, "received.zip").apply { writeBytes(bytes.copyOfRange(headerEnd, bytes.size - suffix.size)) }
                    ZipFile(upload).use { archive ->
                        val metadata = archive.getInputStream(archive.getEntry("release.json")).use {
                            Json.parseToJsonElement(it.reader().readText()).jsonObject
                        }
                        assertEquals(identity.buildIdentity, metadata.getValue("buildIdentity").jsonPrimitive.content)
                        if (metadata.getValue("clientType").jsonPrimitive.content == "desktop") {
                            assertNotNull(archive.getEntry("payload/lib/app.jar"))
                            assertNull(archive.getEntry("payload/\$lib/app.jar"))
                            assertNull(archive.getEntry("payload/payload.properties"))
                            assertFalse(archive.entries().asSequence().any { it.name.endsWith(".nsi") })
                        }
                        uploads += metadata
                    }
                    exchange.sendResponseHeaders(200, 2)
                    exchange.responseBody.use { it.write("{}".toByteArray()) }
                } catch (failure: Throwable) {
                    val message = failure.toString().toByteArray()
                    exchange.sendResponseHeaders(500, message.size.toLong())
                    exchange.responseBody.use { it.write(message) }
                } finally { exchange.close() }
            }
            server.start()
            val result = ClientReleasePublisher("http://127.0.0.1:${server.address.port}", "test-publish-token")
                .publish(bundle, identity)
            assertEquals(6, result.uploaded.size)
            assertEquals(6, uploads.size)
            assertEquals(listOf("desktop", "desktop", "desktop", "desktop", "android", "headless"),
                uploads.map { it.getValue("clientType").jsonPrimitive.content })
        } finally {
            server.stop(0)
            root.deleteRecursively()
        }
    }

    private fun zip(file: File, entries: Map<String, String>) {
        file.parentFile.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
            }
        }
    }

    private fun signApk(root: File, unsigned: File, signed: File) {
        val store = File(root, "test.p12")
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool"
        runCheckedProcess(ProcessSpec("Create disposable APK test signer", listOf(
            File(System.getProperty("java.home"), "bin/$executable").absolutePath,
            "-genkeypair", "-keystore", store.path, "-storetype", "PKCS12", "-storepass", "changeit",
            "-keypass", "changeit", "-alias", "test", "-keyalg", "RSA", "-dname", "CN=TeamTalk Test", "-validity", "1",
        ), timeoutMillis = 30_000, outputMode = ProcessOutputMode.CAPTURE))
        val keys = KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, "changeit".toCharArray()) } }
        val signer = ApkSigner.SignerConfig.Builder("test", keys.getKey("test", "changeit".toCharArray()) as PrivateKey,
            listOf(keys.getCertificate("test") as X509Certificate)).build()
        ApkSigner.Builder(listOf(signer)).setInputApk(unsigned).setOutputApk(signed).build().sign()
    }
}
