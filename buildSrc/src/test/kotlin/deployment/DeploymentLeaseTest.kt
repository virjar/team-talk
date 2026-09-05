package deployment

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import org.gradle.api.GradleException
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeploymentLeaseTest {
    @Test
    fun `lease command uses session flock generation fencing and operation drain`() {
        val paths = remoteDeploymentLockPaths("/opt/teamtalk")
        val token = "00000000-0000-0000-0000-000000000001"
        val command = deploymentLeaseRemoteCommand(paths, token)
        val rsyncProgram = deploymentRsyncProgramPath(paths.owner, token)

        assertEquals(remoteDeploymentLockPath("/opt/teamtalk"), paths.owner)
        assertTrue(paths.operations.endsWith(".lock.operations"))
        assertTrue(paths.generation.endsWith(".lock.generation"))
        assertEquals(3, setOf(paths.owner, paths.operations, paths.generation).size)
        assertTrue(command.contains("command -v flock"))
        assertTrue(command.contains("flock -n -E 73 -x"))
        assertTrue(command.contains(paths.owner))
        assertTrue(command.contains("flock -n -E 74 -x"))
        assertTrue(command.contains(paths.operations))
        assertTrue(command.contains(paths.generation))
        assertTrue(command.contains(rsyncProgram))
        assertFalse(rsyncProgram.contains(token))
        assertFalse(
            rsyncProgram == deploymentRsyncProgramPath(
                paths.owner,
                "00000000-0000-0000-0000-000000000002",
            ),
        )
        assertTrue(command.contains(DEPLOYMENT_LEASE_READY_MARKER))
        assertTrue(command.contains("exec cat >/dev/null"))
        assertTrue(command.indexOf(token) < command.indexOf("-E 74"))
        assertTrue(command.indexOf("-E 74") < command.indexOf(rsyncProgram))
        assertTrue(command.indexOf(rsyncProgram) < command.indexOf(DEPLOYMENT_LEASE_READY_MARKER))
        assertFalse(command.contains("mkdir"))
        assertFalse(command.contains("rmdir"))
        assertFailsWith<IllegalArgumentException> {
            RemoteDeploymentLockPaths("/tmp/owner", paths.operations, paths.generation)
        }
        assertFailsWith<IllegalArgumentException> {
            deploymentLeaseRemoteCommand(paths, "not-a-generation")
        }
    }

    @Test
    fun `guard fences every remote command and rsync against the active generation`() {
        val paths = remoteDeploymentLockPaths("/opt/teamtalk")
        var ownerChecks = 0
        val guard = RemoteDeploymentCommandGuard(
            host = "deploy.example.com",
            user = "deploy",
            port = 2222,
            ownerLockPath = paths.owner,
            operationLockPath = paths.operations,
            generationPath = paths.generation,
            generationToken = "00000000-0000-0000-0000-000000000002",
            requireOwnerSession = { ownerChecks++ },
        )
        val original = "printf '%s\\n' 'deployment command'"

        withRemoteDeploymentCommandGuard(guard) {
            val ssh = remoteCommandArguments(
                "deploy.example.com",
                "deploy",
                2222,
                original,
            )
            val wrapped = ssh.last()
            assertTrue("ServerAliveInterval=5" in ssh)
            assertTrue("ServerAliveCountMax=3" in ssh)
            assertTrue(wrapped.startsWith("flock -n -E 76 -x"))
            assertTrue(wrapped.contains(paths.operations))
            assertTrue(wrapped.contains(paths.generation))
            assertTrue(wrapped.contains(paths.owner))
            assertTrue(wrapped.contains("teamtalk_owner_state"))
            assertTrue(wrapped.contains(remoteShellQuote(original)))

            val rsync = upgradeRsyncArguments(
                distDir = File("server-dist"),
                user = "deploy",
                host = "deploy.example.com",
                port = 2222,
                deployPath = "/opt/teamtalk",
            )
            val rsyncProgram = rsync.single { it.startsWith("--rsync-path=") }
            val expectedProgram = deploymentRsyncProgramPath(
                paths.owner,
                "00000000-0000-0000-0000-000000000002",
            )
            assertEquals("--rsync-path=/bin/sh $expectedProgram", rsyncProgram)
            assertTrue(expectedProgram.startsWith("/run/lock/"))
            assertFalse(expectedProgram.any(Char::isWhitespace))
            assertFalse(expectedProgram.any { it in "'\\\"$;" })
            assertEquals(
                listOf("/bin/sh", expectedProgram),
                rsyncProgram.removePrefix("--rsync-path=").split(' '),
            )
            assertTrue(rsync.contains(sshTransportCommand(2222)))

            assertFailsWith<GradleException> {
                remoteCommandArguments("other.example.com", "deploy", 2222, "true")
            }
            assertFailsWith<GradleException> {
                localChecked("unguarded scp", listOf("scp", "source", "target"))
            }
            assertFailsWith<GradleException> {
                localChecked("unguarded rsync", listOf("rsync", "source", "target"))
            }
        }

        assertTrue(ownerChecks >= 1)
        assertEquals(
            "true",
            remoteCommandArguments("deploy.example.com", "deploy", 2222, "true").last(),
        )
    }

    @Test
    fun `nested owner guard and rsync commands remain valid POSIX shell`() {
        val paths = remoteDeploymentLockPaths("/opt/teamtalk")
        val token = "00000000-0000-0000-0000-000000000003"
        val guard = RemoteDeploymentCommandGuard(
            host = "deploy.example.com",
            user = "deploy",
            port = 2222,
            ownerLockPath = paths.owner,
            operationLockPath = paths.operations,
            generationPath = paths.generation,
            generationToken = token,
            requireOwnerSession = {},
        )
        val original = "printf '%s\\n' \"value with ' quote\"\nprintf done"
        val commands = listOf(
            deploymentLeaseRemoteCommand(paths, token),
            guard.wrapRemoteCommand(original),
            deploymentRsyncWrapperScript(paths, token),
        )

        commands.forEach { command ->
            val process = ProcessBuilder("sh", "-n", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.readBytes().toString(StandardCharsets.UTF_8)
            assertEquals(0, process.waitFor(), output)
        }
    }

    @Test
    fun `failed deployment action cannot leak its guard into a reused Gradle thread`() {
        val paths = remoteDeploymentLockPaths("/opt/teamtalk")
        val guard = RemoteDeploymentCommandGuard(
            host = "deploy.example.com",
            user = "deploy",
            port = 2222,
            ownerLockPath = paths.owner,
            operationLockPath = paths.operations,
            generationPath = paths.generation,
            generationToken = "00000000-0000-0000-0000-000000000004",
            requireOwnerSession = {},
        )

        assertFailsWith<IllegalStateException> {
            withRemoteDeploymentCommandGuard(guard) {
                throw IllegalStateException("injected deployment failure")
            }
        }
        assertEquals(
            "true",
            remoteCommandArguments("deploy.example.com", "deploy", 2222, "true").last(),
        )
    }

    @Test
    fun `stdin EOF releases a ready lease process without explicit remote cleanup`() {
        val session = ManagedReadyProcess.start(
            ProcessSpec(
                label = "local lease lifecycle fixture",
                arguments = listOf(
                    "sh",
                    "-c",
                    "printf '%s\\n' '$DEPLOYMENT_LEASE_READY_MARKER'; exec cat >/dev/null",
                ),
                timeoutMillis = 2_000L,
                outputMode = ProcessOutputMode.CAPTURE,
            ),
            readyMarker = DEPLOYMENT_LEASE_READY_MARKER,
            closeTimeoutMillis = 2_000L,
        )

        assertTrue(session.isRunning())
        session.close()
        assertFalse(session.isRunning())
        session.close()
    }

    @Test
    fun `stubborn lease process is forcibly closed after its bounded EOF grace period`() {
        val process = ControllableProcess(
            output = "$DEPLOYMENT_LEASE_READY_MARKER\n",
            initiallyAlive = true,
            completesOnWait = false,
        )
        val session = ManagedReadyProcess.start(
            ProcessSpec(
                label = "stubborn lease fixture",
                arguments = listOf("fake"),
                timeoutMillis = 1_000L,
                outputMode = ProcessOutputMode.CAPTURE,
            ),
            readyMarker = DEPLOYMENT_LEASE_READY_MARKER,
            closeTimeoutMillis = 1L,
            startProcess = { process },
        )

        session.close()
        assertTrue(process.standardInputClosed)
        assertTrue(process.destroyForciblyCalled)
        assertFalse(session.isRunning())
    }

    @Test
    fun `readiness timeout kills the local control process`() {
        val process = ControllableProcess(
            output = "",
            initiallyAlive = true,
            completesOnWait = false,
            outputRemainsOpen = true,
        )
        assertFailsWith<ProcessTimeoutException> {
            ManagedReadyProcess.start(
                ProcessSpec(
                    label = "lease readiness timeout fixture",
                    arguments = listOf("fake"),
                    timeoutMillis = 1L,
                    outputMode = ProcessOutputMode.CAPTURE,
                ),
                readyMarker = DEPLOYMENT_LEASE_READY_MARKER,
                startProcess = { process },
            )
        }
        assertTrue(process.destroyForciblyCalled)
    }

    @Test
    fun `concurrent owner and unfinished operation fail before readiness`() {
        listOf(
            73 to "already holds",
            74 to "still finishing",
        ).forEach { (exitCode, expectedMessage) ->
            val elapsed = measureTimeMillis {
                val failure = assertFailsWith<GradleException> {
                    RemoteDeploymentLease.acquire(
                        host = "deploy.example.com",
                        user = "deploy",
                        port = 2222,
                        deployPath = "/opt/teamtalk",
                        startProcess = {
                            ControllableProcess(
                                output = "",
                                exitCode = exitCode,
                                initiallyAlive = false,
                                completesOnWait = true,
                            )
                        },
                    )
                }
                assertTrue(failure.message.orEmpty().contains(expectedMessage))
            }
            assertTrue(elapsed < 5_000L, "lock conflict should fail before the SSH startup timeout")
        }
    }

    private class ControllableProcess(
        output: String,
        private val exitCode: Int = 0,
        initiallyAlive: Boolean,
        private val completesOnWait: Boolean,
        outputRemainsOpen: Boolean = false,
    ) : Process() {
        private val processOutput: InputStream = if (outputRemainsOpen) {
            object : InputStream() {
                override fun read(): Int {
                    while (alive) Thread.sleep(5L)
                    return -1
                }
            }
        } else {
            ByteArrayInputStream(output.toByteArray(StandardCharsets.UTF_8))
        }
        private val processError = ByteArrayInputStream(ByteArray(0))
        private val standardInput = object : ByteArrayOutputStream() {
            override fun close() {
                standardInputClosed = true
                super.close()
            }
        }
        @Volatile
        private var alive = initiallyAlive
        var standardInputClosed = false
            private set
        var destroyForciblyCalled = false
            private set

        override fun getOutputStream(): OutputStream = standardInput
        override fun getInputStream(): InputStream = processOutput
        override fun getErrorStream(): InputStream = processError
        override fun waitFor(): Int = exitCode
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = completesOnWait || !alive
        override fun exitValue(): Int {
            if (alive) throw IllegalThreadStateException("still running")
            return exitCode
        }

        override fun destroy() = Unit

        override fun destroyForcibly(): Process {
            destroyForciblyCalled = true
            alive = false
            return this
        }

        override fun isAlive(): Boolean = alive
    }
}
