package deployment

import java.io.File
import java.nio.file.Files
import org.gradle.api.GradleException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerRuntimePreflightTest {
    @Test
    fun `systemd overrides are parsed as values without leaking other environment entries`() {
        assertEquals(
            mapOf("JAVA_HOME" to "/opt/Java 21", "PATH" to "/opt/java/bin:/usr/bin"),
            parseServiceJavaEnvironment(
                "DATABASE_PASSWORD=$SECRET \"JAVA_HOME=/opt/Java 21\" " +
                    "'PATH=/opt/java/bin:/usr/bin' OTHER=ignored",
            ),
        )
        assertEquals(emptyMap(), parseServiceJavaEnvironment(""))
        val failure = assertFailsWith<IllegalArgumentException> {
            parseServiceJavaEnvironment("DATABASE_PASSWORD=\"$SECRET")
        }
        assertFalse(failure.message.orEmpty().contains(SECRET))
    }

    @Test
    fun `service Java21 drop-in passes even when the SSH environment still uses Java17`() = withFixture { root ->
        val pathJava = fakeJava(root, "path/bin", "17.0.20")
        val homeJava = fakeJava(root, "jdk/bin", "21.0.8")
        val serviceEnvironment = mapOf("JAVA_HOME" to homeJava.parentFile.parentFile.absolutePath)
        val sshJavaHome = pathJava.parentFile.parentFile.absolutePath
        assertEquals(
            21,
            requireSupportedServerJava(
                readVersion(pathJava, sshJavaHome, serviceEnvironment),
                "service runtime environment",
            ),
        )
    }

    @Test
    fun `service PATH override works independently of an old SSH JAVA_HOME`() = withFixture { root ->
        val systemJava = fakeJava(root, "system/bin", "17.0.20")
        val serviceJava = fakeJava(root, "service/bin", "21.0.8")
        val sshJavaHome = systemJava.parentFile.parentFile.absolutePath
        assertEquals(
            21,
            requireSupportedServerJava(
                readVersion(systemJava, sshJavaHome, mapOf("PATH" to serviceJava.parentFile.absolutePath)),
                "service runtime environment",
            ),
        )
    }

    @Test
    fun `old service default cannot borrow the SSH Java21 to pass preflight`() = withFixture { root ->
        val systemJava = fakeJava(root, "system/bin", "17.0.20")
        val sshJava = fakeJava(root, "ssh/bin", "21.0.8")
        val failure = assertFailsWith<GradleException> {
            requireSupportedServerJava(
                readVersion(systemJava, sshJava.parentFile.parentFile.absolutePath, sshPathJava = sshJava),
                "service runtime environment",
            )
        }
        assertTrue(failure.message.orEmpty().contains("Java 17 is too old"))
    }

    @Test
    fun `supported Java versions pass and legacy versions fail without exposing JVM options`() {
        listOf(
            "openjdk version \"21.0.8\" 2025-07-15 LTS" to 21,
            "java version \"21\" 2023-09-19 LTS" to 21,
            "openjdk version \"25-ea\" 2025-09-16" to 25,
        ).forEach { (version, expected) ->
            assertEquals(expected, requireSupportedServerJava(version, "test environment"))
        }
        listOf(
            "openjdk version \"17.0.20\" 2026-07-21" to 17,
            "java version \"1.8.0_452\"" to 8,
        ).forEach { (version, expected) ->
            val failure = assertFailsWith<GradleException> {
                requireSupportedServerJava("Picked up JAVA_TOOL_OPTIONS: $SECRET\n$version", "test environment")
            }
            assertTrue(failure.message.orEmpty().contains("Java $expected is too old"))
            assertTrue(failure.message.orEmpty().contains("before stopping TeamTalk"))
            assertFalse(failure.message.orEmpty().contains(SECRET))
        }
        listOf(null, "", SECRET, "openjdk version \"999999999999999999999\"").forEach { output ->
            val failure = assertFailsWith<GradleException> {
                requireSupportedServerJava(output, "test environment")
            }
            assertTrue(failure.message.orEmpty().contains("Install Java 21 or newer"))
            assertFalse(failure.message.orEmpty().contains(SECRET))
        }
    }

    @Test
    fun `launcher selection honors JAVA_HOME over PATH and fails for invalid JAVA_HOME`() = withFixture { root ->
        val pathJava = fakeJava(root, "path/bin", "17.0.20")
        val homeJava = fakeJava(root, "jdk/bin", "21.0.8")
        val javaHome = homeJava.parentFile.parentFile.absolutePath

        assertEquals(
            21,
            requireSupportedServerJava(readVersion(pathJava, "", mapOf("JAVA_HOME" to javaHome)), "test environment"),
        )
        assertFailsWith<GradleException> {
            requireSupportedServerJava(readVersion(pathJava, ""), "test environment")
        }
        assertEquals(null, readVersion(pathJava, "", mapOf("JAVA_HOME" to File(root, "missing-jdk").absolutePath)))
        assertEquals(null, readVersion(File(root, "missing/bin/java"), ""))
    }

    @Test
    fun `runtime command preserves launcher AIX Java selection`() = withFixture { root ->
        val pathJava = fakeJava(root, "path/bin", "21.0.8")
        fakeJava(root, "jdk/bin", "17.0.20")
        val aixJava = fakeJava(root, "jdk/jre/sh", "21.0.8")
        val javaHome = aixJava.parentFile.parentFile.parentFile.absolutePath
        assertEquals(
            21,
            requireSupportedServerJava(readVersion(pathJava, "", mapOf("JAVA_HOME" to javaHome)), "test environment"),
        )
    }

    private fun readVersion(
        systemJava: File,
        sshJavaHome: String,
        serviceEnvironment: Map<String, String> = emptyMap(),
        sshPathJava: File = systemJava,
    ): String? = runSensitiveCaptureProbe(
        ProcessSpec(
            label = "read fixture Java runtime",
            arguments = listOf(
                "/bin/sh", "-c",
                serverJavaVersionCommand(systemJava.parentFile.absolutePath, serviceEnvironment),
            ),
            outputMode = ProcessOutputMode.CAPTURE,
            timeoutMillis = 5_000L,
            environment = mapOf("PATH" to sshPathJava.parentFile.absolutePath, "JAVA_HOME" to sshJavaHome),
        ),
        allowedExitCodes = setOf(0, 1, 126, 127),
    )

    private fun fakeJava(root: File, relativeBin: String, version: String): File =
        File(root, "$relativeBin/java").apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\n[ \"${'$'}1\" = -version ] || exit 1\nprintf '%s\\n' 'openjdk version \"$version\"' >&2\n")
            check(setExecutable(true))
        }

    private fun withFixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("teamtalk-runtime-preflight-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}

private const val SECRET = "runtime-fixture-secret-must-not-be-logged"
