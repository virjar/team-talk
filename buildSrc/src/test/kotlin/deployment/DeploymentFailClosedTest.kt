package deployment

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Properties
import java.util.concurrent.TimeUnit
import org.gradle.api.GradleException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeploymentFailClosedTest {
    @Test
    fun `protocol preflight reads only the floor and never executes remote env contents`() =
        withTempDirectory { root ->
            val marker = File(root, "must-not-execute")
            val env = File(root, "conf/env.sh").apply { parentFile.mkdirs() }
            val unrelated = "DATABASE_PASSWORD='private-test-secret'\n" +
                "ADMIN_PASSWORD=${'$'}(touch ${marker.absolutePath})\n" +
                "# MINIMUM_PROTOCOL_MINOR=9\n"
            val command = remoteMinimumProtocolMinorReadCommand(root.absolutePath)
            fun read(): String = runCheckedProcess(
                ProcessSpec(
                    "read minimum minor from fixture env",
                    listOf("sh", "-c", command),
                    timeoutMillis = 5_000L,
                    outputMode = ProcessOutputMode.CAPTURE,
                ),
            ).output

            env.writeText(unrelated)
            assertEquals("", read())
            env.writeText(unrelated + "MINIMUM_PROTOCOL_MINOR=3\n")
            assertEquals("MINIMUM_PROTOCOL_MINOR=3", read().trimEnd('\n'))
            env.writeText(unrelated + "export MINIMUM_PROTOCOL_MINOR=3\n")
            assertEquals("INVALID_MINIMUM_PROTOCOL_MINOR", read().trimEnd('\n'))
            env.writeText(unrelated + "MINIMUM_PROTOCOL_MINOR=${'$'}(touch ${marker.absolutePath})\n")
            assertEquals("INVALID_MINIMUM_PROTOCOL_MINOR", read().trimEnd('\n'))
            assertFalse(marker.exists())
        }

    @Test
    fun `health response requires HTTP 200 overall UP and every required component UP`() {
        val valid = requireHealthyResponse("${healthJson()}\n200", TEST_BUILD_IDENTITY)
        assertEquals(200, valid.httpStatus)
        assertEquals(requiredHealthComponents.toList(), valid.components)
        assertEquals(TEST_BUILD_IDENTITY, valid.buildIdentity)

        assertFailsWith<GradleException> {
            requireHealthyResponse("${healthJson()}\n503", TEST_BUILD_IDENTITY)
        }
        assertFailsWith<GradleException> { requireHealthyResponse("not-json\n200", TEST_BUILD_IDENTITY) }
        assertFailsWith<GradleException> {
            requireHealthyResponse("${healthJson(overall = "DOWN")}\n200", TEST_BUILD_IDENTITY)
        }
        assertFailsWith<GradleException> {
            requireHealthyResponse("${healthJson(omitted = "tcp")}\n200", TEST_BUILD_IDENTITY)
        }
        assertFailsWith<GradleException> {
            requireHealthyResponse(
                "${healthJson(overrides = mapOf("lucene" to "DOWN"))}\n200",
                TEST_BUILD_IDENTITY,
            )
        }
        assertFailsWith<GradleException> {
            requireHealthyResponse("${healthJson(buildIdentity = "another-build")}\n200", TEST_BUILD_IDENTITY)
        }
    }

    @Test
    fun `required artifact rejects missing empty and ambiguous selections`() = withTempDirectory { root ->
        assertFailsWith<GradleException> { requireArtifact(null, "APK") }
        val empty = File(root, "empty.apk").apply { createNewFile() }
        assertFailsWith<GradleException> { requireArtifact(empty, "APK") }

        val artifactDir = File(root, "artifacts").apply { mkdirs() }
        val one = File(artifactDir, "teamtalk-release.apk").apply { writeBytes(byteArrayOf(1)) }
        assertEquals(
            one,
            requireSingleArtifact(artifactDir, "APK") { it.extension == "apk" },
        )
        File(artifactDir, "another-release.apk").writeBytes(byteArrayOf(2))
        assertFailsWith<GradleException> {
            requireSingleArtifact(artifactDir, "APK") { it.extension == "apk" }
        }
        assertFailsWith<GradleException> {
            requireSingleArtifact(File(root, "missing"), "APK") { true }
        }
    }

    @Test
    fun `secret replacement is owner only and refuses a symbolic link target`() =
        withTempDirectory { root ->
            val secretFile = File(root, "deployment.secrets")
            saveSecrets(secretFile, completeSecrets())
            val store = Files.getFileStore(secretFile.toPath())
            if (store.supportsFileAttributeView("posix")) {
                assertEquals(
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(secretFile.toPath()),
                )
                val target = File(root, "unrelated").apply { writeText("unchanged") }
                val link = File(root, "linked-secrets").toPath()
                Files.createSymbolicLink(link, target.toPath())
                assertFailsWith<GradleException> {
                    saveSecrets(link.toFile(), completeSecrets())
                }
                assertEquals("unchanged", target.readText())
            }
        }

    @Test
    fun `existing partial secret file persists every generated credential`() =
        withTempDirectory { root ->
            val secretFile = File(root, "deployment.secrets").apply {
                writeText("DATABASE_PASSWORD=existing-database-password\n")
            }
            val loaded = loadOrGenerateFirstDeploymentSecrets(secretFile)
            assertEquals("existing-database-password", loaded.getProperty("DATABASE_PASSWORD"))
            val persisted = Properties().apply {
                secretFile.reader(StandardCharsets.UTF_8).use { load(it) }
            }
            listOf(
                "DATABASE_PASSWORD",
                "SSL_KEYSTORE_PASSWORD",
                "SSL_PRIVATE_KEY_PASSWORD",
                "ADMIN_USER",
                "ADMIN_PASSWORD",
            ).forEach { key -> assertTrue(persisted.getProperty(key).isNotBlank(), key) }
        }

    @Test
    fun `upgrade secrets require every key exactly once`() {
        val complete = canonicalUpgradeEnv()
        val parsed = parseRequiredUpgradeSecrets(complete)
        requiredDeploymentSecretKeys.forEach { key ->
            assertEquals(upgradeSecretValues.getValue(key), parsed.getProperty(key))
        }

        requiredDeploymentSecretKeys.forEach { omitted ->
            val failure = assertFailsWith<IllegalArgumentException>(omitted) {
                parseRequiredUpgradeSecrets(
                    complete.lineSequence()
                        .filterNot { it.startsWith("$omitted=") }
                        .joinToString("\n"),
                )
            }
            assertTrue(failure.message.orEmpty().contains(omitted))
        }

        val duplicateValue = "duplicate-secret-value"
        requiredDeploymentSecretKeys.forEach { duplicateKey ->
            val failure = assertFailsWith<IllegalArgumentException>(duplicateKey) {
                parseRequiredUpgradeSecrets(
                    "$complete\n$duplicateKey=${posixShellQuote(duplicateValue)}",
                )
            }
            assertTrue(failure.message.orEmpty().contains("Duplicate"))
            assertFailureDoesNotContain(failure, duplicateValue)
        }
    }

    @Test
    fun `upgrade secrets reject empty and malformed related assignments without exposing values`() {
        listOf("''", "'   '", "'null'").forEach { encoded ->
            val failure = assertFailsWith<IllegalArgumentException> {
                parseRequiredUpgradeSecrets(
                    canonicalUpgradeEnv(databaseAssignment = "DATABASE_PASSWORD=$encoded"),
                )
            }
            assertFailureDoesNotContain(failure, encoded)
        }

        val marker = "must-not-appear-in-errors"
        listOf(
            "export DATABASE_PASSWORD=${posixShellQuote(marker)}",
            " DATABASE_PASSWORD=${posixShellQuote(marker)}",
            "DATABASE_PASSWORD = ${posixShellQuote(marker)}",
            "DATABASE_PASSWORD=$marker",
            "DATABASE_PASSWORD='$marker",
            "DATABASE_PASSWORD=${posixShellQuote(marker)} trailing",
        ).forEach { malformed ->
            val failure = assertFailsWith<IllegalArgumentException> {
                parseRequiredUpgradeSecrets(canonicalUpgradeEnv(databaseAssignment = malformed))
            }
            assertFailureDoesNotContain(failure, marker)
        }
    }

    @Test
    fun `checked runner rejects nonzero and probe rejects undeclared exit code`() {
        val spec = ProcessSpec(
            label = "fake command",
            arguments = listOf("fake"),
            timeoutMillis = 1_000L,
            outputMode = ProcessOutputMode.CAPTURE,
        )
        val exitFailure = assertFailsWith<ProcessExitException> {
            runCheckedProcess(spec) { StubProcess(exitCode = 7, output = "failure detail") }
        }
        assertEquals(7, exitFailure.exitCode)
        assertTrue(exitFailure.message.orEmpty().contains("failure detail"))
        assertFalse(runProcessProbe(spec) { StubProcess(exitCode = 1) })
        assertFailsWith<ProcessExitException> {
            runProcessProbe(spec) { StubProcess(exitCode = 2) }
        }
    }

    @Test
    fun `sensitive capture failure never exposes captured values`() {
        val marker = "captured-secret-must-not-leak"
        val failure = assertFailsWith<SensitiveProcessExitException> {
            runSensitiveCaptureProbe(
                ProcessSpec(
                    label = "read sensitive fixture",
                    arguments = listOf("fake"),
                    outputMode = ProcessOutputMode.CAPTURE,
                ),
            ) { StubProcess(exitCode = 2, output = marker) }
        }

        assertEquals(2, failure.exitCode)
        assertFailureDoesNotContain(failure, marker)
    }

    @Test
    fun `sensitive capture rejects an oversized prefix instead of validating only its tail`() {
        val discardedPrefixSecret = "discarded-prefix-secret-must-not-leak"
        val oversizedRemoteEnv = buildString {
            append("DATABASE_PASSWORD=")
                .append(posixShellQuote(discardedPrefixSecret))
                .append('\n')
            append("P".repeat(70 * 1024)).append('\n')
            append(canonicalUpgradeEnv())
        }

        val failure = assertFailsWith<SensitiveProcessOutputLimitException> {
            runSensitiveCaptureProbe(
                ProcessSpec(
                    label = "read oversized sensitive fixture",
                    arguments = listOf("fake"),
                    outputMode = ProcessOutputMode.CAPTURE,
                ),
            ) { StubProcess(exitCode = 0, output = oversizedRemoteEnv) }
        }

        assertFailureDoesNotContain(failure, discardedPrefixSecret)
    }

    @Test
    fun `sensitive stdin is bounded absent from argv and redacted from every failure`() {
        val marker = "stdin-secret-must-never-enter-arguments-or-errors"
        val input = "$marker\n".toByteArray(StandardCharsets.UTF_8)
        val successfulProcess = StubProcess(exitCode = 0)
        var observedArguments: List<String> = emptyList()

        val result = runSensitiveStdinCheckedProcess(
            ProcessSpec(
                label = "consume sensitive stdin",
                arguments = listOf("fake", "--fixed-argument"),
                outputMode = ProcessOutputMode.DISCARD,
            ),
            input,
        ) { builder ->
            observedArguments = builder.command().toList()
            successfulProcess
        }
        assertEquals(0, result.exitCode)
        assertEquals("", result.output)
        assertFalse(observedArguments.any { marker in it })
        assertEquals("$marker\n", successfulProcess.standardInputText())

        val exitFailure = assertFailsWith<SensitiveProcessExitException> {
            runSensitiveStdinCheckedProcess(
                ProcessSpec(
                    label = "reject sensitive stdin",
                    arguments = listOf("fake"),
                    outputMode = ProcessOutputMode.DISCARD,
                ),
                input,
            ) { StubProcess(exitCode = 9, output = marker) }
        }
        assertFailureDoesNotContain(exitFailure, marker)

        val ioFailure = assertFailsWith<SensitiveProcessIoException> {
            runSensitiveStdinCheckedProcess(
                ProcessSpec(
                    label = "fail sensitive stdin write",
                    arguments = listOf("fake"),
                    outputMode = ProcessOutputMode.DISCARD,
                ),
                input,
            ) {
                StubProcess(
                    exitCode = 0,
                    standardInputSink = object : OutputStream() {
                        override fun write(value: Int) {
                            throw IOException(marker)
                        }
                    },
                )
            }
        }
        assertFailureDoesNotContain(ioFailure, marker)

        val startFailure = assertFailsWith<SensitiveProcessIoException> {
            runSensitiveStdinCheckedProcess(
                ProcessSpec(
                    label = "fail sensitive stdin process start",
                    arguments = listOf("fake"),
                    outputMode = ProcessOutputMode.DISCARD,
                ),
                input,
            ) { throw IOException(marker) }
        }
        assertFailureDoesNotContain(startFailure, marker)

        assertFailsWith<IllegalArgumentException> {
            runSensitiveStdinCheckedProcess(
                ProcessSpec(
                    label = "reject oversized sensitive stdin",
                    arguments = listOf("fake"),
                    outputMode = ProcessOutputMode.DISCARD,
                ),
                ByteArray(MAX_SENSITIVE_PROCESS_INPUT_BYTES + 1),
            ) { error("oversized input must fail before process start") }
        }
    }

    @Test
    fun `database role password is present only in bounded psql stdin`() {
        val password = "database-stdin-secret-'-\$-`-\\"
        val command = databaseRoleProvisioningCommand("0123456789abcdef")
        val script = databaseRoleProvisioningInput(password).toString(StandardCharsets.UTF_8)

        assertFalse(password in command)
        assertFalse(command.contains("base64", ignoreCase = true))
        assertTrue(command.contains("docker exec -i"))
        assertTrue(script.contains("\\prompt db_password\n$password\n"))
        assertTrue(script.contains("PASSWORD :'db_password'"))
    }

    @Test
    fun `runner timeout forcibly terminates process`() {
        val process = StubProcess(exitCode = 0, completes = false)
        assertFailsWith<ProcessTimeoutException> {
            executeProcess(
                ProcessSpec("hanging command", listOf("fake"), timeoutMillis = 1L),
            ) { process }
        }
        assertTrue(process.destroyed)
    }

    private fun completeSecrets(): Properties = Properties().apply {
        setProperty("DATABASE_PASSWORD", "database")
        setProperty("SSL_KEYSTORE_PASSWORD", "tls")
        setProperty("SSL_PRIVATE_KEY_PASSWORD", "tls")
        setProperty("ADMIN_USER", "admin")
        setProperty("ADMIN_PASSWORD", "admin-password")
    }

    private fun canonicalUpgradeEnv(
        databaseAssignment: String =
            "DATABASE_PASSWORD=${posixShellQuote(upgradeSecretValues.getValue("DATABASE_PASSWORD"))}",
    ): String = buildList {
        add("# unrelated generated deployment configuration")
        add("KTOR_PORT=8080")
        add(databaseAssignment)
        requiredDeploymentSecretKeys
            .filterNot { it == "DATABASE_PASSWORD" }
            .forEach { key -> add("$key=${posixShellQuote(upgradeSecretValues.getValue(key))}") }
    }.joinToString("\n")

    private fun assertFailureDoesNotContain(failure: Throwable, sensitive: String) {
        val messages = generateSequence(failure) { it.cause }.mapNotNull(Throwable::message)
        assertTrue(messages.none { sensitive in it })
    }

    private fun healthJson(
        overall: String = "UP",
        overrides: Map<String, String> = emptyMap(),
        omitted: String? = null,
        buildIdentity: String = TEST_BUILD_IDENTITY,
    ): String {
        val components = requiredHealthComponents
            .filterNot { it == omitted }
            .joinToString(",") { name ->
                "\"$name\":{\"status\":\"${overrides[name] ?: "UP"}\"}"
            }
        return "{\"status\":\"$overall\",\"buildIdentity\":\"$buildIdentity\"," +
            "\"components\":{$components}}"
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("teamtalk-deployment-fail-closed-").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private class StubProcess(
        private val exitCode: Int,
        output: String = "",
        private val completes: Boolean = true,
        standardInputSink: OutputStream? = null,
    ) : Process() {
        private val input = ByteArrayInputStream(output.toByteArray(StandardCharsets.UTF_8))
        private val error = ByteArrayInputStream(ByteArray(0))
        private val receivedStandardInput = ByteArrayOutputStream()
        private val outputStream = standardInputSink ?: receivedStandardInput
        var destroyed: Boolean = false
            private set

        override fun getOutputStream(): OutputStream = outputStream
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = error
        override fun waitFor(): Int = exitCode
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = completes
        override fun exitValue(): Int {
            if (!completes && !destroyed) throw IllegalThreadStateException("still running")
            return exitCode
        }

        override fun destroy() {
            // 模拟一个忽略优雅终止的进程。
        }

        override fun destroyForcibly(): Process {
            destroyed = true
            return this
        }

        override fun isAlive(): Boolean = !completes && !destroyed

        fun standardInputText(): String =
            receivedStandardInput.toByteArray().toString(StandardCharsets.UTF_8)
    }
}

private const val TEST_BUILD_IDENTITY = "1.0.7+0123456789abcdef0123456789abcdef01234567"

private val upgradeSecretValues = mapOf(
    "DATABASE_PASSWORD" to "database-upgrade-secret",
    "SSL_KEYSTORE_PASSWORD" to "tls-upgrade-secret",
    "SSL_PRIVATE_KEY_PASSWORD" to "tls-upgrade-secret",
    "ADMIN_USER" to "upgrade-admin",
    "ADMIN_PASSWORD" to "admin-upgrade-secret",
)
