package com.virjar.tk.desktop

import com.virjar.tk.desktop.env.DesktopDataDirectoryInputs
import com.virjar.tk.desktop.env.DesktopDataDirectoryPolicy
import com.virjar.tk.desktop.test.testHttpPort
import com.virjar.tk.shared.client.DeploymentIdentity
import com.virjar.tk.shared.client.JvmPrivateDataDirectory
import com.virjar.tk.shared.client.ServerConfig
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopDistributionIsolationTest {
    @Test
    fun `public paths stay unchanged and private paths follow installation identity`() {
        val home = File("distribution-test-home").absoluteFile
        val publicInputs = DesktopDataDirectoryInputs(
            osName = "Mac OS X",
            userHome = home,
            environment = emptyMap(),
            explicitDataDirectory = null,
            dataDirectoryName = "TeamTalk",
            linuxDataDirectoryName = "teamtalk",
        )
        val privateInputs = publicInputs.copy(
            dataDirectoryName = "com.example.staff",
            linuxDataDirectoryName = "com.example.staff",
        )
        for ((os, parent, publicName) in listOf(
            Triple("Mac OS X", "Library/Application Support", "TeamTalk"),
            Triple("Windows 11", "AppData/Local", "TeamTalk"),
            Triple("Linux", ".local/share", "teamtalk"),
        )) {
            assertEquals(
                home.resolve(parent).resolve(publicName),
                DesktopDataDirectoryPolicy.resolve(publicInputs.copy(osName = os)).dataDirectory,
            )
            assertEquals(
                home.resolve(parent).resolve("com.example.staff"),
                DesktopDataDirectoryPolicy.resolve(privateInputs.copy(osName = os)).dataDirectory,
            )
        }
        val explicit = home.resolve("diagnostic-profile")
        assertEquals(
            explicit,
            DesktopDataDirectoryPolicy.resolve(privateInputs.copy(explicitDataDirectory = explicit.path)).dataDirectory,
        )
    }

    @Test
    fun `two installation roots keep independent process locks and saved accounts`() {
        val fixture = Files.createTempDirectory("teamtalk-distribution-isolation")
        val publicRoot = Files.createTempDirectory(fixture, "public-").toFile()
        val privateRoot = Files.createTempDirectory(fixture, "private-").toFile()
        val publicLock = FileLocker(publicRoot)
        val privateLock = FileLocker(privateRoot)
        val duplicatePrivateLock = FileLocker(privateRoot)
        try {
            assertTrue(publicLock.tryLock())
            assertTrue(privateLock.tryLock())
            assertFalse(duplicatePrivateLock.tryLock())

            // Even the same account and server must not share refresh credentials across installs.
            val deployment = DeploymentIdentity.from("127.0.0.1", 5100, "http://127.0.0.1:8080")
            val publicStore = DesktopTokenStore(publicRoot, deployment)
            val privateStore = DesktopTokenStore(privateRoot, deployment)
            val dataset = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
            val publicLogin = assertNotNull(publicStore.save(
                publicStore.claimOwner().generation, "user-a", "public-refresh", dataset,
            ))
            val privateLogin = assertNotNull(privateStore.save(
                privateStore.claimOwner().generation, "user-a", "private-refresh", dataset,
            ))
            assertTrue(privateStore.compareAndClear(privateLogin))
            assertNull(DesktopTokenStore(privateRoot, deployment).claimOwner().savedLogin)
            val restored = assertNotNull(DesktopTokenStore(publicRoot, deployment).claimOwner().savedLogin)
            assertEquals(publicLogin.refreshToken, restored.refreshToken)
        } finally {
            duplicatePrivateLock.release()
            privateLock.release()
            publicLock.release()
            fixture.toFile().deleteRecursively()
        }
    }

    @Test
    fun `parallel desktop automation uses an explicit port without changing the default`() {
        assertEquals(18080, testHttpPort(null))
        assertEquals(18081, testHttpPort("18081"))
        for (invalid in listOf("", "0", "65536", "-1", "localhost:18081")) {
            assertFailsWith<IllegalArgumentException> { testHttpPort(invalid) }
        }
    }

    @Test
    fun `fixed private installation ignores historical custom server without deleting it`() {
        val directory = Files.createTempDirectory("teamtalk-fixed-server-").toFile()
        try {
            val file = JvmPrivateDataDirectory.openExisting(directory).atomicTextFile(fileName = "custom-server.properties")
            val previous = "https://other.example.com\nother.example.com\n5100"
            file.replaceText(previous)
            val packaged = ServerConfig("http://192.0.2.10", "192.0.2.10", 5100, "packaged-public-certificate")
            assertEquals(packaged, restoreDesktopServerConfig(directory, packaged, allowCustomServer = false))
            assertEquals(previous, file.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `custom server restoration preserves the packaged pin only for the same deployment`() {
        val directory = Files.createTempDirectory("teamtalk-custom-server-").toFile()
        try {
            val file = JvmPrivateDataDirectory.openExisting(directory).atomicTextFile(fileName = "custom-server.properties")
            val packaged = ServerConfig("http://192.0.2.10", "192.0.2.10", 5100, "new-packaged-public-certificate")
            file.replaceText("http://192.0.2.10/\n192.0.2.10\n5100")
            assertEquals(packaged, restoreDesktopServerConfig(directory, packaged, allowCustomServer = true))

            file.replaceText("https://other.example.com\nother.example.com\n5100")
            val restored = restoreDesktopServerConfig(directory, packaged, allowCustomServer = true)
            assertEquals("other.example.com", restored.tcpHost)
            assertNull(restored.tcpTlsCertificatePem)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `Desktop uses its compiled certificate when the launcher supplies no certificate argument`() {
        val runtime = ServerConfig("http://192.0.2.10", "192.0.2.10", 5100)
        val pem = "packaged public certificate\n"
        val packaged = Base64.getEncoder().encodeToString(pem.toByteArray(Charsets.UTF_8))
        assertEquals(runtime.copy(tcpTlsCertificatePem = pem), desktopDefaultServerConfig(runtime, packaged))
        assertEquals(runtime, desktopDefaultServerConfig(runtime, ""))
    }

    @Test
    fun `explicit runtime certificate takes precedence over the Desktop package certificate`() {
        val runtime = ServerConfig("http://192.0.2.10", "192.0.2.10", 5100, "explicit public certificate")
        assertEquals(runtime, desktopDefaultServerConfig(runtime, "unused fallback is not decoded"))
    }
}
