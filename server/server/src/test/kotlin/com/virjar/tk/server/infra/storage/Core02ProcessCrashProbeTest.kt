package com.virjar.tk.server.infra.storage

import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.server.domain.message.MessageOperationType
import com.virjar.tk.server.domain.message.MessageProjectionOperation
import com.virjar.tk.server.domain.message.MessageProjectionStage
import com.virjar.tk.server.domain.message.MessageProjectionTarget
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.MessageType
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Core02ProcessCrashProbeTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `boundaries map exact prefixes to the three physical stages`() {
        assertEquals(
            listOf(
                Triple(
                    Core02ProcessCrashBoundary.ROCKS_COMMITTED_BEFORE_PROJECTION,
                    "core02-rocks-",
                    MessageProjectionStage.AFTER_PENDING_BEFORE_PROJECTION,
                ),
                Triple(
                    Core02ProcessCrashBoundary.POSTGRES_COMMITTED_BEFORE_OUTBOX_DELETE,
                    "core02-postgres-",
                    MessageProjectionStage.AFTER_POSTGRES_BEFORE_OUTBOX_DELETE,
                ),
                Triple(
                    Core02ProcessCrashBoundary.OUTBOX_DELETED_BEFORE_MESSAGE_RETURN,
                    "core02-outbox-",
                    MessageProjectionStage.AFTER_OUTBOX_DELETE_BEFORE_MESSAGE_RETURN,
                ),
            ),
            Core02ProcessCrashBoundary.entries.map { boundary ->
                Triple(boundary, boundary.clientMessagePrefix, boundary.stage)
            },
        )
    }

    @Test
    fun `probe hits every boundary only at its mapped stage`() = runBlocking {
        val blocks = AtomicInteger()
        val probe = probe(blocker = { blocks.incrementAndGet() })

        Core02ProcessCrashBoundary.entries.forEachIndexed { index, boundary ->
            val clientMsgId = "${boundary.clientMessagePrefix}mapped-$index"
            val operation = operation(clientMsgId)
            arm(operation, CHAT_ID)

            probe.hit(boundary.stage, operation)

            assertFalse(Files.exists(armPath(clientMsgId)))
            assertFalse(Files.exists(claimPath(clientMsgId)))
            assertTrue(Files.readString(hitPath(clientMsgId)).contains("stage=${boundary.stage.name}"))
        }
        assertEquals(Core02ProcessCrashBoundary.entries.size, blocks.get())
    }

    @Test
    fun `probe ignores non-prefix wrong stages wrong chat and missing arm`() = runBlocking {
        val blocks = AtomicInteger()
        val probe = probe(blocker = { blocks.incrementAndGet() })

        val nonPrefix = operation("ordinary-message")
        arm(nonPrefix, CHAT_ID)
        probe.hit(MessageProjectionStage.AFTER_PENDING_BEFORE_PROJECTION, nonPrefix)

        val wrongStages = Core02ProcessCrashBoundary.entries.mapIndexed { index, boundary ->
            operation("${boundary.clientMessagePrefix}wrong-stage-$index").also { operation ->
                arm(operation, CHAT_ID)
                val wrongStage = Core02ProcessCrashBoundary.entries
                    .first { candidate -> candidate.stage != boundary.stage }
                    .stage
                probe.hit(wrongStage, operation)
            }
        }

        val wrongChat = operation("core02-rocks-wrong-chat")
        arm(wrongChat, "another-chat")
        probe.hit(MessageProjectionStage.AFTER_PENDING_BEFORE_PROJECTION, wrongChat)

        val missingArm = operation("core02-rocks-missing-arm")
        probe.hit(MessageProjectionStage.AFTER_PENDING_BEFORE_PROJECTION, missingArm)

        assertEquals(0, blocks.get())
        assertTrue(Files.exists(armPath(nonPrefix.message.clientMsgId)))
        wrongStages.forEach { operation ->
            assertTrue(Files.exists(armPath(operation.message.clientMsgId)))
            assertFalse(Files.exists(hitPath(operation.message.clientMsgId)))
        }
        assertTrue(Files.exists(armPath(wrongChat.message.clientMsgId)))
        assertFalse(Files.exists(hitPath(nonPrefix.message.clientMsgId)))
        assertFalse(Files.exists(hitPath(wrongChat.message.clientMsgId)))
        assertFalse(Files.exists(hitPath(missingArm.message.clientMsgId)))
    }

    @Test
    fun `probe atomically claims one arm and publishes complete hit marker before blocking`() = runBlocking {
        val operation = operation("core02-rocks-once")
        arm(operation, CHAT_ID)
        val blocks = AtomicInteger()
        val markerSeenByBlocker = AtomicReference<String?>()
        val probe = probe(
            blocker = {
                markerSeenByBlocker.set(Files.readString(hitPath(operation.message.clientMsgId)))
                blocks.incrementAndGet()
            },
        )

        listOf(
            async(Dispatchers.Default) {
                probe.hit(MessageProjectionStage.AFTER_PENDING_BEFORE_PROJECTION, operation)
            },
            async(Dispatchers.Default) {
                probe.hit(MessageProjectionStage.AFTER_PENDING_BEFORE_PROJECTION, operation)
            },
        ).awaitAll()

        val expectedMarker = listOf(
            "pid=$PID",
            "invocationId=$INVOCATION_ID",
            "chatId=$CHAT_ID",
            "clientMsgId=${operation.message.clientMsgId}",
            "stage=${MessageProjectionStage.AFTER_PENDING_BEFORE_PROJECTION.name}",
        ).joinToString("\n")
        assertEquals(1, blocks.get())
        assertFalse(Files.exists(armPath(operation.message.clientMsgId)))
        assertFalse(Files.exists(claimPath(operation.message.clientMsgId)))
        assertEquals(expectedMarker, markerSeenByBlocker.get())
        assertEquals(expectedMarker, Files.readString(hitPath(operation.message.clientMsgId)))
        assertNull(
            Files.list(temporaryDirectory).use { entries ->
                entries.filter { it.fileName.toString().contains(".tmp") }.findFirst().orElse(null)
            },
            "claim and marker temporary files must not survive a completed injected blocker",
        )
    }

    @Test
    fun `probe leaves arm untouched when process identity is invalid`() = runBlocking {
        val blocks = AtomicInteger()
        val invalidPid = operation("core02-rocks-invalid-pid")
        val shortInvocationId = operation("core02-rocks-short-invocation")
        val nonHexInvocationId = operation("core02-rocks-non-hex-invocation")
        listOf(invalidPid, shortInvocationId, nonHexInvocationId).forEach { arm(it, CHAT_ID) }

        probe(blocker = { blocks.incrementAndGet() }, processId = 0L)
            .hit(MessageProjectionStage.AFTER_PENDING_BEFORE_PROJECTION, invalidPid)
        probe(blocker = { blocks.incrementAndGet() }, processInvocationId = "0123456789abcdef")
            .hit(MessageProjectionStage.AFTER_PENDING_BEFORE_PROJECTION, shortInvocationId)
        probe(blocker = { blocks.incrementAndGet() }, processInvocationId = "g".repeat(32))
            .hit(MessageProjectionStage.AFTER_PENDING_BEFORE_PROJECTION, nonHexInvocationId)

        assertEquals(0, blocks.get())
        listOf(invalidPid, shortInvocationId, nonHexInvocationId).forEach { operation ->
            val clientMsgId = operation.message.clientMsgId
            assertEquals(CHAT_ID, Files.readString(armPath(clientMsgId)))
            assertFalse(Files.exists(claimPath(clientMsgId)))
            assertFalse(Files.exists(hitPath(clientMsgId)))
        }
    }

    @Test
    fun `process kill wait ignores interruption and fails only at its final timeout`() {
        var now = 0L
        val observedWaits = mutableListOf<Long>()

        val failure = assertFailsWith<IllegalStateException> {
            awaitCore02ProcessKillTimeout(
                timeoutNanos = 100L,
                nanoTime = { now },
                await = { remainingNanos ->
                    observedWaits += remainingNanos
                    if (observedWaits.size == 1) {
                        now = 25L
                        throw InterruptedException("test interrupt")
                    }
                    now = 100L
                },
            )
        }

        assertEquals(listOf(100L, 75L), observedWaits)
        assertTrue(failure.message.orEmpty().contains("not SIGKILLed"))
    }

    private fun probe(
        blocker: () -> Unit,
        processId: Long = PID,
        processInvocationId: String = INVOCATION_ID,
    ): Core02ProcessCrashProbe = Core02ProcessCrashProbe(
        directory = temporaryDirectory,
        blocker = blocker,
        pid = { processId },
        invocationId = { processInvocationId },
    )

    private fun arm(operation: MessageProjectionOperation, chatId: String) {
        Files.writeString(armPath(operation.message.clientMsgId), chatId)
    }

    private fun armPath(clientMsgId: String): Path = temporaryDirectory.resolve("$clientMsgId.arm")

    private fun claimPath(clientMsgId: String): Path = temporaryDirectory.resolve("$clientMsgId.claim")

    private fun hitPath(clientMsgId: String): Path = temporaryDirectory.resolve("$clientMsgId.hit")

    private fun operation(clientMsgId: String): MessageProjectionOperation {
        val message = Message(
            chatId = CHAT_ID,
            clientMsgId = clientMsgId,
            serverSeq = 1L,
            senderUid = "sender",
            messageType = MessageType.RICH_TEXT.code,
            timestamp = 1L,
            body = buildRichTextBody("core02 probe"),
        )
        return MessageProjectionOperation(
            projectionKey = MessageProjectionOperation.stableKey(message.chatId, message.serverSeq),
            operation = MessageOperationType.CREATE,
            revision = 1L,
            message = message,
            target = MessageProjectionTarget(chatType = 1, recipientUids = listOf("sender")),
        )
    }

    private companion object {
        const val CHAT_ID = "chat-core02"
        const val PID = 4242L
        const val INVOCATION_ID = "00000000000000000000000000000042"
    }
}
