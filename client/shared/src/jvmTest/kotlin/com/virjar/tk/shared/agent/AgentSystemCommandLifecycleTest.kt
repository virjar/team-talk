package com.virjar.tk.shared.agent

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AgentSystemCommandLifecycleTest {

    @Test
    fun `fixed system commands never delegate executable selection to callers`() {
        val invocations = mutableListOf<Invocation>()
        val processes = ArrayDeque(
            listOf(
                ScriptedProcess(stdout = "0\n"),
                ScriptedProcess(stdout = "501\n"),
                ScriptedProcess(stdout = "502\n"),
                ScriptedProcess(stdout = "agent-user\n"),
                ScriptedProcess(),
                ScriptedProcess(),
                ScriptedProcess(),
            ),
        )
        val commands = commands(invocations) { processes.removeFirst() }

        assertEquals(0, commands.currentUid())
        assertEquals(
            AgentUnixIdentity("agent-user", uid = 501, gid = 502, primaryGroupName = "agent-user"),
            commands.resolveServiceIdentity("agent-user"),
        )
        commands.stopAgentService()
        commands.disableAgentService()
        commands.reloadSystemdManager()

        assertEquals(
            listOf(
                Invocation(listOf("/usr/bin/id", "-u"), captureStdout = true),
                Invocation(listOf("/usr/bin/id", "-u", "agent-user"), captureStdout = true),
                Invocation(listOf("/usr/bin/id", "-g", "agent-user"), captureStdout = true),
                Invocation(listOf("/usr/bin/id", "-gn", "agent-user"), captureStdout = true),
                Invocation(listOf("/usr/bin/systemctl", "stop", "tt-agent"), captureStdout = false),
                Invocation(listOf("/usr/bin/systemctl", "disable", "tt-agent"), captureStdout = false),
                Invocation(listOf("/usr/bin/systemctl", "daemon-reload"), captureStdout = false),
            ),
            invocations,
        )
    }

    @Test
    fun `identity stdout is drained concurrently bounded and never copied into failures`() {
        // 无界实现会把它截断成一个有效 uid。因此这个失败也
        // 证明了：在整条管道被排空的同时，超出捕获预算的字节会被注意到。
        val process = DrainGatedProcess(prefix = "0")
        val commands = commands { process }

        assertFailsWith<AgentSystemCommandException> {
            commands.currentUid()
        }

        assertTrue(process.drained.await(1, TimeUnit.SECONDS))
        assertFalse(process.destroyed)
    }

    @Test
    fun `captured identity output is never copied into failures`() {
        val process = ScriptedProcess(stdout = "private-marker")
        val commands = commands { process }

        val failure = assertFailsWith<AgentSystemCommandException> {
            commands.currentUid()
        }

        assertFalse(failure.message.orEmpty().contains("private-marker"))
    }

    @Test
    fun `timeout terminates child and closes every process pipe`() {
        val process = ScriptedProcess(waitSteps = listOf(WaitStep.RETURN_FALSE, WaitStep.RETURN_TRUE))
        val commands = commands { process }

        assertFailsWith<AgentSystemCommandTimeoutException> {
            commands.currentUid()
        }

        assertTrue(process.destroyed)
        assertFalse(process.destroyedForcibly)
        assertTrue(process.stdin.closed)
        assertTrue(process.stdout.closed)
        assertTrue(process.stderr.closed)
    }

    @Test
    fun `timeout escalates to force when graceful termination does not finish`() {
        val process = ScriptedProcess(
            waitSteps = listOf(
                WaitStep.RETURN_FALSE,
                WaitStep.RETURN_FALSE,
                WaitStep.RETURN_TRUE,
            ),
        )
        val commands = commands { process }

        assertFailsWith<AgentSystemCommandTimeoutException> {
            commands.currentUid()
        }

        assertTrue(process.destroyed)
        assertTrue(process.destroyedForcibly)
        assertFalse(process.isAlive)
    }

    @Test
    fun `interrupted wait cleans child and preserves original interruption`() {
        val process = ScriptedProcess(waitSteps = listOf(WaitStep.INTERRUPT, WaitStep.RETURN_TRUE))
        val commands = commands { process }

        try {
            val failure = assertFailsWith<InterruptedException> {
                commands.currentUid()
            }
            assertEquals("test interruption", failure.message)
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(process.destroyed)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `fatal process failure is cleaned and rethrown unchanged`() {
        val fatal = AssertionError("fatal marker")
        val process = ScriptedProcess(
            waitSteps = listOf(WaitStep.FATAL, WaitStep.RETURN_TRUE),
            fatal = fatal,
        )
        val commands = commands { process }

        val thrown = assertFailsWith<AssertionError> {
            commands.currentUid()
        }

        assertSame(fatal, thrown)
        assertTrue(process.destroyed)
    }

    @Test
    fun `systemctl failure is redacted and cannot be mistaken for success`() {
        val process = ScriptedProcess(stdout = "credential-like-output", exitCode = 9)
        val commands = commands { process }

        val failure = assertFailsWith<AgentSystemCommandException> {
            commands.stopAgentService()
        }

        assertFalse(failure.message.orEmpty().contains("credential-like-output"))
        assertTrue(process.stdout.closed)
    }

    @Test
    fun `uninstall is linux root only and refuses a symlink before mutation`() {
        withTempDirectory { directory ->
            val unit = directory.resolve("tt-agent.service")
            val target = directory.resolve("target.service")
            Files.writeString(target, "unit")
            Files.createSymbolicLink(unit, target.fileName)
            val commands = RecordingSystemCommands()

            assertFailsWith<IllegalArgumentException> {
                AgentServiceUninstaller("macOS", unit, commands).uninstall()
            }
            assertTrue(commands.events.isEmpty())
            assertFailsWith<IllegalArgumentException> {
                AgentServiceUninstaller(
                    "Linux",
                    unit,
                    RecordingSystemCommands(uid = 501),
                ).uninstall()
            }
            assertFailsWith<IllegalArgumentException> {
                AgentServiceUninstaller("Linux", unit, commands).uninstall()
            }
            assertEquals(listOf("uid"), commands.events)
            assertTrue(Files.isSymbolicLink(unit))
        }
    }

    @Test
    fun `absent unit is idempotent but still retries manager reload`() {
        withTempDirectory { directory ->
            val unit = directory.resolve("missing.service")
            val commands = RecordingSystemCommands()

            assertEquals(
                AgentServiceUninstallResult.ALREADY_ABSENT,
                AgentServiceUninstaller("Linux", unit, commands).uninstall(),
            )

            assertEquals(listOf("uid", "reload"), commands.events)
            assertFalse(commands.events.contains("stop"))
            assertFalse(commands.events.contains("disable"))

            val failingReload = RecordingSystemCommands(failureAt = "reload")
            assertFailsWith<AgentSystemCommandException> {
                AgentServiceUninstaller("Linux", unit, failingReload).uninstall()
            }
            assertEquals(listOf("uid", "reload"), failingReload.events)
        }
    }

    @Test
    fun `stop and disable failures preserve unit and short circuit later steps`() {
        listOf(
            "stop" to listOf("uid", "stop"),
            "disable" to listOf("uid", "stop", "disable"),
        ).forEach { (failurePoint, expectedEvents) ->
            withTempDirectory { directory ->
                val unit = directory.resolve("tt-agent.service")
                Files.writeString(unit, "unit")
                val commands = RecordingSystemCommands(failureAt = failurePoint)

                assertFailsWith<AgentSystemCommandException> {
                    AgentServiceUninstaller("Linux", unit, commands).uninstall()
                }

                assertEquals(expectedEvents, commands.events)
                assertTrue(Files.isRegularFile(unit))
            }
        }
    }

    @Test
    fun `successful uninstall completes checked sequence before reporting success`() {
        withTempDirectory { directory ->
            val unit = directory.resolve("tt-agent.service")
            Files.writeString(unit, "unit")
            val commands = RecordingSystemCommands()

            assertEquals(
                AgentServiceUninstallResult.UNINSTALLED,
                AgentServiceUninstaller("Linux", unit, commands).uninstall(),
            )

            assertEquals(listOf("uid", "stop", "disable", "reload"), commands.events)
            assertFalse(Files.exists(unit))
        }
    }

    @Test
    fun `reload failure reports partial completion and absent retry converges`() {
        withTempDirectory { directory ->
            val unit = directory.resolve("tt-agent.service")
            Files.writeString(unit, "unit")
            val commands = RecordingSystemCommands(failureAt = "reload")
            val uninstaller = AgentServiceUninstaller("Linux", unit, commands)

            val failure = assertFailsWith<AgentSystemCommandException> {
                uninstaller.uninstall()
            }

            assertEquals("systemd unit was removed but manager reload failed", failure.message)
            assertEquals(listOf("uid", "stop", "disable", "reload"), commands.events)
            assertFalse(Files.exists(unit))

            commands.failureAt = null
            assertEquals(AgentServiceUninstallResult.ALREADY_ABSENT, uninstaller.uninstall())
            assertEquals(
                listOf("uid", "stop", "disable", "reload", "uid", "reload"),
                commands.events,
            )
        }
    }

    private fun commands(
        invocations: MutableList<Invocation> = mutableListOf(),
        process: () -> Process,
    ): JvmAgentSystemCommands = JvmAgentSystemCommands(
        timeouts = AgentSystemCommandTimeouts(
            identityMillis = 2_000,
            systemctlMillis = 2_000,
            terminationGraceMillis = 100,
            outputDrainMillis = 1_000,
        ),
        processStarter = AgentProcessStarter { arguments, captureStdout ->
            invocations += Invocation(arguments, captureStdout)
            process()
        },
    )

    private fun withTempDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("agent-service-uninstall-")
        try {
            block(directory)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private data class Invocation(
        val arguments: List<String>,
        val captureStdout: Boolean,
    )

    private enum class WaitStep {
        RETURN_TRUE,
        RETURN_FALSE,
        INTERRUPT,
        FATAL,
    }

    private class ScriptedProcess(
        stdout: String = "",
        private val exitCode: Int = 0,
        waitSteps: List<WaitStep> = listOf(WaitStep.RETURN_TRUE),
        private val fatal: AssertionError = AssertionError("fatal"),
    ) : Process() {
        val stdin = TrackingOutputStream()
        val stdout = TrackingInputStream(ByteArrayInputStream(stdout.toByteArray()))
        val stderr = TrackingInputStream(ByteArrayInputStream(ByteArray(0)))
        private val waitSteps = ArrayDeque(waitSteps)

        @Volatile
        private var alive = true
        var destroyed = false
            private set
        var destroyedForcibly = false
            private set

        override fun getOutputStream(): OutputStream = stdin

        override fun getInputStream(): InputStream = stdout

        override fun getErrorStream(): InputStream = stderr

        override fun waitFor(): Int {
            alive = false
            return exitCode
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            return when (waitSteps.pollFirst() ?: WaitStep.RETURN_TRUE) {
                WaitStep.RETURN_TRUE -> true.also { alive = false }
                WaitStep.RETURN_FALSE -> false
                WaitStep.INTERRUPT -> throw InterruptedException("test interruption")
                WaitStep.FATAL -> throw fatal
            }
        }

        override fun exitValue(): Int = exitCode

        override fun destroy() {
            destroyed = true
        }

        override fun destroyForcibly(): Process {
            destroyedForcibly = true
            return this
        }

        override fun isAlive(): Boolean = alive
    }

    private class DrainGatedProcess(prefix: String) : Process() {
        val drained = CountDownLatch(1)
        private val bytes = (prefix + " ".repeat(4_096)).toByteArray()
        private val source = object : InputStream() {
            private var index = 0

            override fun read(): Int {
                if (index >= bytes.size) {
                    drained.countDown()
                    return -1
                }
                return bytes[index++].toInt() and 0xff
            }

            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                if (index >= bytes.size) {
                    drained.countDown()
                    return -1
                }
                val count = minOf(length, bytes.size - index)
                bytes.copyInto(target, offset, index, index + count)
                index += count
                return count
            }
        }
        val stdin = TrackingOutputStream()
        val stdout = TrackingInputStream(source)
        val stderr = TrackingInputStream(ByteArrayInputStream(ByteArray(0)))
        var destroyed = false
            private set
        private var alive = true

        override fun getOutputStream(): OutputStream = stdin

        override fun getInputStream(): InputStream = stdout

        override fun getErrorStream(): InputStream = stderr

        override fun waitFor(): Int {
            check(drained.await(1, TimeUnit.SECONDS))
            alive = false
            return 0
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            return drained.await(timeout, unit).also { if (it) alive = false }
        }

        override fun exitValue(): Int = 0

        override fun destroy() {
            destroyed = true
            alive = false
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }

        override fun isAlive(): Boolean = alive
    }

    private class TrackingInputStream(delegate: InputStream) : InputStream() {
        private val delegate = delegate
        var closed = false
            private set

        override fun read(): Int = delegate.read()

        override fun read(target: ByteArray, offset: Int, length: Int): Int =
            delegate.read(target, offset, length)

        override fun close() {
            closed = true
            delegate.close()
        }
    }

    private class TrackingOutputStream : ByteArrayOutputStream() {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class RecordingSystemCommands(
        private val uid: Int = 0,
        var failureAt: String? = null,
    ) : AgentSystemCommands {
        val events = mutableListOf<String>()

        override fun currentUid(): Int = uid.also { events += "uid" }

        override fun resolveServiceIdentity(user: String): AgentUnixIdentity? = error("not used")

        override fun stopAgentService() = record("stop")

        override fun disableAgentService() = record("disable")

        override fun reloadSystemdManager() = record("reload")

        private fun record(event: String) {
            events += event
            if (failureAt == event) throw AgentSystemCommandException("$event failed")
        }
    }
}
