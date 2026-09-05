package deployment

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Properties
import org.gradle.api.GradleException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TlsDeploymentPreflightTest {
    @Test
    fun `certificate and key properties must be a non-blank pair`() = withTempDirectory { root ->
        val certificate = File(root, "certificate.pem").apply { writeText("certificate") }
        val privateKey = File(root, "private key.pem").apply { writeText("private key") }

        listOf(
            "certificate.pem" to null,
            null to "private key.pem",
            "" to "private key.pem",
            "certificate.pem" to "  ",
        ).forEach { (cert, key) ->
            val failure = assertFailsWith<GradleException> {
                validateLocalTlsPemFiles(root, cert, key)
            }
            assertTrue(failure.message.orEmpty().contains("sslCert"))
            assertTrue(failure.message.orEmpty().contains("sslKey"))
        }

        val relative = validateLocalTlsPemFiles(root, "certificate.pem", "private key.pem")
        assertEquals(certificate, relative?.certificate)
        assertEquals(privateKey, relative?.privateKey)

        val absolute = validateLocalTlsPemFiles(
            File(root, "unrelated-root"),
            certificate.absolutePath,
            privateKey.absolutePath,
        )
        assertEquals(certificate, absolute?.certificate)
        assertEquals(privateKey, absolute?.privateKey)
    }

    @Test
    fun `provided TLS paths must resolve to regular files`() = withTempDirectory { root ->
        File(root, "certificate.pem").writeText("certificate")
        File(root, "key-directory").mkdir()

        val missingKey = assertFailsWith<GradleException> {
            validateLocalTlsPemFiles(root, "certificate.pem", "missing-key.pem")
        }
        assertTrue(missingKey.message.orEmpty().contains("SSL key file"))

        val directoryKey = assertFailsWith<GradleException> {
            validateLocalTlsPemFiles(root, "certificate.pem", "key-directory")
        }
        assertTrue(directoryKey.message.orEmpty().contains("not a regular file"))
    }

    @Test
    fun `first HTTPS deploy requires PEM files while upgrade may retain PKCS12`() =
        withTempDirectory { root ->
            val certificate = File(root, "certificate.pem").apply { writeText("certificate") }
            val privateKey = File(root, "private-key.pem").apply { writeText("private key") }
            val pemFiles = TlsPemFiles(certificate, privateKey)

            val missing = assertFailsWith<GradleException> {
                requireTlsPemFilesForDeployment(
                    sslEnabled = true,
                    isFirstDeploy = true,
                    pemFiles = null,
                )
            }
            assertTrue(missing.message.orEmpty().contains("sslCert"))
            assertTrue(missing.message.orEmpty().contains("sslKey"))

            requireTlsPemFilesForDeployment(true, true, pemFiles)
            requireTlsPemFilesForDeployment(true, false, null)
            requireTlsPemFilesForDeployment(false, true, null)
            assertFailsWith<GradleException> {
                requireTlsPemFilesForDeployment(false, false, pemFiles)
            }

            val retainedCheck = retainedTlsKeystoreCheckCommand("/opt/teamtalk")
            assertTrue(retainedCheck.contains("test -s /opt/teamtalk/conf/ssl/teamtalk.p12"))
            assertTrue(retainedCheck.contains("test -r /opt/teamtalk/conf/ssl/teamtalk.p12"))
            assertTrue(retainedCheck.contains("-storepass:file /dev/stdin"))
            assertTrue(retainedCheck.contains("-alias mykey"))
            assertTrue(retainedCheck.contains("-certreq"))
            assertTrue(retainedCheck.contains("-file /dev/null"))
        }

    @Test
    fun `retained PKCS12 validation opens the private key and rejects wrong password or corruption`() =
        withTempDirectory { root ->
            if (File.separatorChar != '/') return@withTempDirectory
            val deployRoot = File(root, "deploy").apply { mkdirs() }
            val sslDirectory = File(deployRoot, "conf/ssl").apply { mkdirs() }
            val keystore = File(sslDirectory, "teamtalk.p12")
            val password = "fixture-keystore-password"
            val wrongPassword = "fixture-wrong-password"
            val keytool = File(System.getProperty("java.home"), "bin/keytool")
            assertTrue(keytool.canExecute(), "Gradle JDK must provide keytool")

            localChecked(
                label = "create retained TLS test fixture",
                arguments = listOf(
                    keytool.absolutePath,
                    "-genkeypair",
                    "-alias", "mykey",
                    "-keyalg", "RSA",
                    "-keysize", "2048",
                    "-validity", "1",
                    "-dname", "CN=TeamTalk Deployment Test",
                    "-storetype", "PKCS12",
                    "-keystore", keystore.absolutePath,
                    "-storepass:env", "TEAMTALK_TEST_P12_PASSWORD",
                    "-keypass:env", "TEAMTALK_TEST_P12_PASSWORD",
                    "-noprompt",
                ),
                outputMode = ProcessOutputMode.DISCARD,
                environment = mapOf("TEAMTALK_TEST_P12_PASSWORD" to password),
            )
            val command = retainedTlsKeystoreCheckCommand(
                deployRoot.absolutePath,
                keytool.absolutePath,
            )
            assertFalse(command.contains(password))
            assertFalse(command.contains(wrongPassword))

            fun validate(candidate: String) = runSensitiveStdinCheckedProcess(
                ProcessSpec(
                    label = "validate retained TLS fixture",
                    arguments = listOf("/bin/sh", "-c", command),
                    outputMode = ProcessOutputMode.DISCARD,
                ),
                "$candidate\n".toByteArray(StandardCharsets.UTF_8),
            )

            assertEquals(0, validate(password).exitCode)
            val wrongPasswordFailure = assertFailsWith<SensitiveProcessExitException> {
                validate(wrongPassword)
            }
            assertFailureDoesNotContain(wrongPasswordFailure, wrongPassword)

            keystore.writeText("not a PKCS12 keystore")
            val corruptFailure = assertFailsWith<SensitiveProcessExitException> {
                validate(password)
            }
            assertFailureDoesNotContain(corruptFailure, password)
        }

    @Test
    fun `generated TLS secrets use one PKCS12 password and mismatches fail`() {
        val generated = Properties()
        ensureFirstDeploymentSecretsComplete(generated)
        val generatedPassword = requireNotNull(generated.getProperty("SSL_KEYSTORE_PASSWORD"))
        assertTrue(generatedPassword.isNotBlank())
        assertEquals(generatedPassword, generated.getProperty("SSL_PRIVATE_KEY_PASSWORD"))

        val existingKeystorePassword = Properties().apply {
            setProperty("SSL_KEYSTORE_PASSWORD", "existing-password")
        }
        ensureFirstDeploymentSecretsComplete(existingKeystorePassword)
        assertEquals(
            "existing-password",
            existingKeystorePassword.getProperty("SSL_PRIVATE_KEY_PASSWORD"),
        )

        val existingPrivateKeyPassword = Properties().apply {
            setProperty("SSL_PRIVATE_KEY_PASSWORD", "existing-password")
        }
        ensureFirstDeploymentSecretsComplete(existingPrivateKeyPassword)
        assertEquals(
            "existing-password",
            existingPrivateKeyPassword.getProperty("SSL_KEYSTORE_PASSWORD"),
        )

        val mismatched = Properties().apply {
            setProperty("SSL_KEYSTORE_PASSWORD", "store-password")
            setProperty("SSL_PRIVATE_KEY_PASSWORD", "key-password")
        }
        ensureFirstDeploymentSecretsComplete(mismatched)
        assertEquals("store-password", mismatched.getProperty("SSL_KEYSTORE_PASSWORD"))
        assertEquals("key-password", mismatched.getProperty("SSL_PRIVATE_KEY_PASSWORD"))
        val failure = assertFailsWith<GradleException> {
            requireCompatibleTlsPasswords(mismatched)
        }
        assertTrue(failure.message.orEmpty().contains("must be identical"))

        val invalidPasswordPairs = listOf(
            "" to "password",
            "null" to "password",
            "password" to "  ",
        )
        invalidPasswordPairs.forEach { (keystorePassword, privateKeyPassword) ->
            val invalid = Properties().apply {
                setProperty("SSL_KEYSTORE_PASSWORD", keystorePassword)
                setProperty("SSL_PRIVATE_KEY_PASSWORD", privateKeyPassword)
            }
            assertFailsWith<GradleException> { requireCompatibleTlsPasswords(invalid) }
        }
    }

    @Test
    fun `TLS preparation converts and validates locally without exposing password in argv`() =
        withTempDirectory { root ->
            val certificate = File(root, "certificate.pem").apply { writeText("certificate") }
            val privateKey = File(root, "private-key.pem").apply { writeText("private key") }
            val output = File(root, "prepared.p12")
            val secrets = matchingTlsSecrets("keystore-password")
            var observedCommand: List<String> = emptyList()
            var observedEnvironment: Map<String, String> = emptyMap()
            var verifiedPassword: String? = null

            val prepared = prepareTlsKeystore(
                pemFiles = TlsPemFiles(certificate, privateKey),
                secrets = secrets,
                createTemporaryFile = { output },
                runCommand = { command, environment ->
                    observedCommand = command
                    observedEnvironment = environment
                    output.writeBytes(byteArrayOf(1, 2, 3))
                    0
                },
                verifyKeystore = { file, password ->
                    assertEquals(output, file)
                    verifiedPassword = password
                },
            )

            assertEquals(output, prepared)
            assertTrue("openssl" in observedCommand)
            assertTrue("env:TEAMTALK_SSL_KEYSTORE_PASSWORD" in observedCommand)
            assertFalse(observedCommand.any { it.contains("keystore-password") })
            assertEquals(
                "keystore-password",
                observedEnvironment["TEAMTALK_SSL_KEYSTORE_PASSWORD"],
            )
            assertEquals("keystore-password", verifiedPassword)
        }

    @Test
    fun `TLS preparation failure deletes temporary keystore`() = withTempDirectory { root ->
        val certificate = File(root, "certificate.pem").apply { writeText("certificate") }
        val privateKey = File(root, "private-key.pem").apply { writeText("private key") }
        val output = File(root, "failed.p12")

        assertFailsWith<GradleException> {
            prepareTlsKeystore(
                pemFiles = TlsPemFiles(certificate, privateKey),
                secrets = matchingTlsSecrets("password"),
                createTemporaryFile = { output },
                runCommand = { _, _ ->
                    output.writeBytes(byteArrayOf(1))
                    1
                },
                verifyKeystore = { _, _ -> error("must not verify a failed conversion") },
            )
        }
        assertFalse(output.exists())

        assertFailsWith<GradleException> {
            prepareTlsKeystore(
                pemFiles = TlsPemFiles(certificate, privateKey),
                secrets = matchingTlsSecrets("password"),
                createTemporaryFile = { output },
                runCommand = { _, _ ->
                    output.writeBytes(byteArrayOf(1))
                    0
                },
                verifyKeystore = { _, _ ->
                    throw GradleException("invalid PKCS12")
                },
            )
        }
        assertFalse(output.exists())

        val missingPassword = Properties()
        var temporaryFileCreated = false
        assertFailsWith<GradleException> {
            prepareTlsKeystore(
                pemFiles = TlsPemFiles(certificate, privateKey),
                secrets = missingPassword,
                createTemporaryFile = {
                    temporaryFileCreated = true
                    output
                },
            )
        }
        assertFalse(temporaryFileCreated)
        assertNull(missingPassword.getProperty("SSL_KEYSTORE_PASSWORD"))
    }

    private fun matchingTlsSecrets(password: String): Properties = Properties().apply {
        setProperty("SSL_KEYSTORE_PASSWORD", password)
        setProperty("SSL_PRIVATE_KEY_PASSWORD", password)
    }

    private fun assertFailureDoesNotContain(failure: Throwable, sensitive: String) {
        val messages = generateSequence(failure) { it.cause }.mapNotNull(Throwable::message)
        assertTrue(messages.none { sensitive in it })
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("teamtalk-tls-preflight-").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
