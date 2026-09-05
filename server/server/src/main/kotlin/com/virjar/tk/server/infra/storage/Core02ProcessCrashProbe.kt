package com.virjar.tk.server.infra.storage

import com.virjar.tk.server.domain.message.MessageProjectionHooks
import com.virjar.tk.server.domain.message.MessageProjectionOperation
import com.virjar.tk.server.domain.message.MessageProjectionStage
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal enum class Core02ProcessCrashBoundary(
    val clientMessagePrefix: String,
    val stage: MessageProjectionStage,
) {
    ROCKS_COMMITTED_BEFORE_PROJECTION(
        clientMessagePrefix = "core02-rocks-",
        stage = MessageProjectionStage.AFTER_PENDING_BEFORE_PROJECTION,
    ),
    POSTGRES_COMMITTED_BEFORE_OUTBOX_DELETE(
        clientMessagePrefix = "core02-postgres-",
        stage = MessageProjectionStage.AFTER_POSTGRES_BEFORE_OUTBOX_DELETE,
    ),
    OUTBOX_DELETED_BEFORE_MESSAGE_RETURN(
        clientMessagePrefix = "core02-outbox-",
        stage = MessageProjectionStage.AFTER_OUTBOX_DELETE_BEFORE_MESSAGE_RETURN,
    ),
}

/**
 * 针对三个 CORE-02 消息提交边界的打包进程探测。
 *
 * 操作员通过把消息的 chat id 写入
 * `data/acceptance/core02/<clientMsgId>.arm` 来武装一条精确的消息。当该消息到达由其
 * 客户端消息前缀选中的边界时，此进程原子地消耗该 arm，发布一个完整的命中
 * 标记，并等待验收装置 SIGKILL TeamTalk 进程。
 */
internal class Core02ProcessCrashProbe(
    private val directory: Path = Path.of("data", "acceptance", "core02"),
    private val blocker: () -> Unit = ::awaitProcessKill,
    private val pid: () -> Long = { ProcessHandle.current().pid() },
    private val invocationId: () -> String = { System.getenv("INVOCATION_ID").orEmpty() },
) : MessageProjectionHooks {
    override suspend fun hit(stage: MessageProjectionStage, operation: MessageProjectionOperation) {
        val message = operation.message
        val clientMsgId = message.clientMsgId
        val boundary = Core02ProcessCrashBoundary.entries.singleOrNull { candidate ->
            clientMsgId.startsWith(candidate.clientMessagePrefix)
        } ?: return
        if (
            stage != boundary.stage ||
            clientMsgId.any { it == '/' || it == '\\' || it.isISOControl() }
        ) {
            return
        }

        val arm = directory.resolve("$clientMsgId.arm")
        if (readExact(arm) != message.chatId) return

        val processId = pid()
        val processInvocationId = invocationId()
        if (processId <= 0L || !INVOCATION_ID_PATTERN.matches(processInvocationId)) return

        val claim = directory.resolve("$clientMsgId.claim")
        try {
            try {
                Files.move(arm, claim, ATOMIC_MOVE)
            } catch (_: NoSuchFileException) {
                return
            } catch (_: FileAlreadyExistsException) {
                return
            }
            if (Files.readString(claim) != message.chatId) return

            publishHitMarker(
                clientMsgId = clientMsgId,
                content = listOf(
                    "pid=$processId",
                    "invocationId=$processInvocationId",
                    "chatId=${message.chatId}",
                    "clientMsgId=$clientMsgId",
                    "stage=${boundary.stage.name}",
                ).joinToString("\n"),
            )
            blocker()
        } finally {
            Files.deleteIfExists(claim)
        }
    }

    private fun readExact(path: Path): String? = try {
        Files.readString(path)
    } catch (_: NoSuchFileException) {
        null
    }

    private fun publishHitMarker(clientMsgId: String, content: String) {
        val temporary = Files.createTempFile(directory, "$clientMsgId.hit-", ".tmp")
        try {
            Files.writeString(temporary, content)
            Files.move(temporary, directory.resolve("$clientMsgId.hit"), ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private companion object {
        val INVOCATION_ID_PATTERN = Regex("[0-9a-fA-F]{32}")

        fun awaitProcessKill(): Nothing = awaitCore02ProcessKillTimeout()
    }
}

private val core02ProcessKillLatch = CountDownLatch(1)
private val core02ProcessKillTimeoutNanos = TimeUnit.MINUTES.toNanos(5L)

/** 线程中断无法释放崩溃边界；只有其最终超时可以。 */
internal fun awaitCore02ProcessKillTimeout(
    timeoutNanos: Long = core02ProcessKillTimeoutNanos,
    nanoTime: () -> Long = System::nanoTime,
    await: (Long) -> Unit = { remainingNanos ->
        core02ProcessKillLatch.await(remainingNanos, TimeUnit.NANOSECONDS)
    },
): Nothing {
    require(timeoutNanos > 0L) { "CORE-02 process-kill timeout must be positive" }
    val startedAt = nanoTime()
    while (true) {
        val remainingNanos = timeoutNanos - (nanoTime() - startedAt)
        if (remainingNanos <= 0L) {
            throw IllegalStateException("CORE-02 crash probe was not SIGKILLed within 5 minutes")
        }
        try {
            await(remainingNanos)
        } catch (_: InterruptedException) {
            // 重新计算剩余的单调预算并继续等待。
        }
    }
}
