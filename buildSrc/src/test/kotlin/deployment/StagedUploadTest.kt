package deployment

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StagedUploadTest {
    @Test
    fun `staged rsync keeps partial files inside its destination for retry`() {
        val arguments = upgradeRsyncArguments(
            distDir = File("server-dist"),
            user = "deploy",
            host = "example.com",
            port = 2222,
            deployPath = "/opt/teamtalk/.release-00000000-0000-0000-0000-000000000001",
        )

        assertTrue("--partial-dir=$STAGED_UPLOAD_PARTIAL_DIRECTORY" in arguments)
        assertEquals(
            "deploy@example.com:/opt/teamtalk/.release-00000000-0000-0000-0000-000000000001/",
            arguments.last(),
        )
    }

    @Test
    fun `transport and protocol failures resume within the same loop`() {
        val failures = mutableListOf(
            ProcessExitException("fixture", 255, "ssh transport closed"),
            ProcessExitException("fixture", 12, "protocol stream interrupted"),
        )
        var attempts = 0
        val delays = mutableListOf<Long>()

        runResumableStagedUpload(
            label = "fixture upload",
            maxAttempts = 3,
            retryDelayMillis = 17L,
            sleep = { delays += it },
        ) {
            attempts++
            if (failures.isNotEmpty()) throw failures.removeAt(0)
        }

        assertEquals(3, attempts)
        assertEquals(listOf(17L, 17L), delays)
    }

    @Test
    fun `operation drain waits do not consume transfer attempts`() {
        val failures = ArrayDeque<ExternalProcessException>().apply {
            repeat(5) {
                add(ProcessExitException("fixture", DEPLOYMENT_OPERATION_CONFLICT_EXIT_CODE, ""))
            }
            add(ProcessExitException("fixture", 255, "ssh transport closed"))
        }
        var attempts = 0
        var nowNanos = 0L

        runResumableStagedUpload(
            label = "fixture upload",
            maxAttempts = 2,
            retryDelayMillis = 10L,
            operationDrainTimeoutMillis = 100L,
            sleep = { delay -> nowNanos += delay * 1_000_000L },
            nanoTime = { nowNanos },
        ) {
            attempts++
            failures.removeFirstOrNull()?.let { throw it }
        }

        assertEquals(7, attempts)
    }

    @Test
    fun `operation drain wait is bounded independently`() {
        var attempts = 0
        var nowNanos = 0L

        val failure = assertFailsWith<ProcessExitException> {
            runResumableStagedUpload(
                label = "fixture upload",
                maxAttempts = 5,
                retryDelayMillis = 10L,
                operationDrainTimeoutMillis = 30L,
                sleep = { delay -> nowNanos += delay * 1_000_000L },
                nanoTime = { nowNanos },
            ) {
                attempts++
                throw ProcessExitException(
                    "fixture",
                    DEPLOYMENT_OPERATION_CONFLICT_EXIT_CODE,
                    "",
                )
            }
        }

        assertEquals(DEPLOYMENT_OPERATION_CONFLICT_EXIT_CODE, failure.exitCode)
        assertEquals(4, attempts)
    }

    @Test
    fun `lost deployment owner aborts before another transfer attempt`() {
        var uploadAttempts = 0
        var ownerChecks = 0
        val ownerFailure = IllegalStateException("lease owner exited")

        val failure = assertFailsWith<IllegalStateException> {
            runResumableStagedUpload(
                label = "fixture upload",
                maxAttempts = 5,
                retryDelayMillis = 0L,
                sleep = {},
                requireOwner = {
                    ownerChecks++
                    if (ownerChecks > 1) throw ownerFailure
                },
            ) {
                uploadAttempts++
                throw ProcessExitException("fixture", 255, "ssh transport closed")
            }
        }

        assertTrue(failure === ownerFailure)
        assertEquals(1, uploadAttempts)
        assertEquals(2, ownerChecks)
    }

    @Test
    fun `recoverable staged upload retries are bounded`() {
        var attempts = 0
        val failure = assertFailsWith<ProcessExitException> {
            runResumableStagedUpload(
                label = "fixture upload",
                maxAttempts = 3,
                retryDelayMillis = 0L,
                sleep = {},
            ) {
                attempts++
                throw ProcessExitException("fixture", 10, "socket closed")
            }
        }

        assertEquals(10, failure.exitCode)
        assertEquals(3, attempts)
    }

    @Test
    fun `non recoverable rsync errors fail without retry`() {
        var attempts = 0
        val failure = assertFailsWith<ProcessExitException> {
            runResumableStagedUpload(
                label = "fixture upload",
                maxAttempts = 6,
                retryDelayMillis = 0L,
                sleep = {},
            ) {
                attempts++
                throw ProcessExitException("fixture", 23, "remote permission denied")
            }
        }

        assertEquals(23, failure.exitCode)
        assertEquals(1, attempts)
    }

    @Test
    fun `local upload timeout can resume but process startup failure cannot`() {
        assertTrue(isRecoverableStagedUploadFailure(ProcessTimeoutException("fixture", 1L)))
        assertFalse(
            isRecoverableStagedUploadFailure(
                ProcessStartException("fixture", IllegalStateException("missing rsync")),
            ),
        )
    }
}
