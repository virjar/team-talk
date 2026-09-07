package deployment

import org.gradle.api.GradleException
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidSigningResolverTest {
    @Test
    fun `public builds keep the trial identity unless a custom keystore is selected`() = fixture { root ->
        val signing = resolve(root, environment = mapOf(
            "TEAMTALK_ANDROID_KEY_ALIAS" to "unused-alias",
            "TEAMTALK_ANDROID_STORE_PASSWORD" to "unused-password",
        ))
        assertEquals(File(root, "teamtalk-dev.jks"), signing.storeFile)
        assertEquals("teamtalk", signing.keyAlias)
        assertEquals("teamtalk", signing.storePassword)
        assertEquals("teamtalk", signing.keyPassword)
    }

    @Test
    fun `DSL owns the keystore and alias while environment passwords override local properties`() = fixture { root ->
        val keystore = File(root, "customer.jks").apply { writeText("AGP validates keystore bytes") }
        localProperties(root, mapOf(
            "release.storeFile" to "unused-local.jks", "release.keyAlias" to "unused-local-alias",
            "release.storePassword" to "local-store", "release.keyPassword" to "local-key",
        ))
        val signing = resolve(root, AndroidSigningConfig("customer.jks", "customer"), mapOf(
            "TEAMTALK_ANDROID_KEYSTORE" to "unused-environment.jks",
            "TEAMTALK_ANDROID_KEY_ALIAS" to "unused-environment-alias",
            "TEAMTALK_ANDROID_STORE_PASSWORD" to "environment-store",
            "TEAMTALK_ANDROID_KEY_PASSWORD" to "environment-key",
        ))
        assertEquals(keystore, signing.storeFile)
        assertEquals("customer", signing.keyAlias)
        assertEquals("environment-store", signing.storePassword)
        assertEquals("environment-key", signing.keyPassword)
    }

    @Test
    fun `legacy signing keeps environment precedence and falls back only when the key password is absent`() = fixture { root ->
        val localStore = File(root, "local.jks").apply { writeText("local fixture") }
        val environmentStore = File(root, "environment.jks").apply { writeText("environment fixture") }
        val properties = mapOf(
            "release.storeFile" to "local.jks", "release.keyAlias" to "local-alias",
            "release.storePassword" to "local-store", "release.keyPassword" to "local-key",
        )
        localProperties(root, properties)
        val local = resolve(root)
        assertEquals(localStore, local.storeFile)
        assertEquals("local-alias", local.keyAlias)
        assertEquals("local-store", local.storePassword)
        assertEquals("local-key", local.keyPassword)

        val environment = mapOf(
            "TEAMTALK_ANDROID_KEYSTORE" to environmentStore.absolutePath,
            "TEAMTALK_ANDROID_KEY_ALIAS" to "environment-alias",
            "TEAMTALK_ANDROID_STORE_PASSWORD" to "environment-store",
        )
        val mixed = resolve(root, environment = environment)
        assertEquals(environmentStore, mixed.storeFile)
        assertEquals("environment-alias", mixed.keyAlias)
        assertEquals("environment-store", mixed.storePassword)
        assertEquals("local-key", mixed.keyPassword)

        localProperties(root, properties - "release.keyPassword")
        assertEquals("environment-store", resolve(root, environment = environment).keyPassword)
        assertEquals("local-store", resolve(root).keyPassword)
    }

    @Test
    fun `an explicit custom keystore never falls back to the trial identity when inputs are missing`() = fixture { root ->
        val path = "customer.jks"
        val configured = AndroidSigningConfig(path, "customer")
        assertFailsWith<IllegalArgumentException> { resolve(root, configured) }
        File(root, path).writeText("AGP validates keystore bytes")
        assertTrue(assertFailsWith<GradleException> { resolve(root, configured) }.message!!.contains("storePassword"))
        assertTrue(assertFailsWith<GradleException> {
            resolve(root, environment = mapOf(
                "TEAMTALK_ANDROID_KEYSTORE" to path,
                "TEAMTALK_ANDROID_STORE_PASSWORD" to "fixture-store",
            ))
        }.message!!.contains("keyAlias"))
    }

    private fun resolve(
        root: File,
        configured: AndroidSigningConfig? = null,
        environment: Map<String, String> = emptyMap(),
    ) = resolveAndroidSigning(
        configured, File(root, "teamtalk-dev.jks"), File(root, "local.properties"), environment::get,
    ) { path -> File(path).let { if (it.isAbsolute) it else File(root, path) } }

    private fun localProperties(root: File, values: Map<String, String>) {
        File(root, "local.properties").writer().use { output ->
            Properties().apply { putAll(values) }.store(output, null)
        }
    }

    private fun fixture(action: (File) -> Unit) {
        val root = Files.createTempDirectory("teamtalk-signing-").toFile()
        try { action(root) } finally { root.deleteRecursively() }
    }
}
