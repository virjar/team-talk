package com.virjar.tk.server.runtime

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 拥有服务器启动期间获取的进程资源。
 *
 * 资源按获取的逆序恰好释放一次。普通的损坏关闭器不会
 * 阻止其余原生句柄与线程池被释放，尤其是在
 * 回退部分完成的启动时。显式依赖屏障不同：若其
 * worker 尚未实际静默，排空 fail closed 并让更旧的依赖保持打开，使
 * 忽略取消的操作无法使用已关闭的 JDBC 或原生句柄。并发的
 * 关闭调用方等待那一次排空并观察其精确的终结结果；它们绝不会把
 * 进行中或失败的关闭误认为成功。
 */
internal class ServerResourceOwner(
    private val onCloseFailure: (name: String, error: Throwable) -> Unit,
) : AutoCloseable {
    private data class OwnedResource(
        val name: String,
        val close: () -> Unit,
        val dependenciesMayClose: (() -> Boolean)? = null,
    )

    private data class ResourceCloseResult(
        val observations: List<CloseObservation>,
        val dependenciesMayClose: Boolean,
    )

    private data class CloseObservation(
        val failure: ServerResourceCloseFailure,
        val diagnosticFailure: Throwable?,
    )

    private enum class Lifecycle {
        OPEN,
        CLOSING,
        CLOSED,
    }

    private val lifecycleLock = ReentrantLock()
    private val closedCondition = lifecycleLock.newCondition()
    private val resources = ArrayDeque<OwnedResource>()
    private var lifecycle = Lifecycle.OPEN
    private var closingThread: Thread? = null
    private var terminalFailure: Throwable? = null

    fun own(name: String, close: () -> Unit) {
        register(OwnedResource(name, close))
    }

    /**
     * 拥有一个 worker 类资源，其更旧的依赖在它真正终止之前是不安全的。
     *
     * [close] 仍必须使用有界等待。它返回或抛出之后，[dependenciesMayClose] 是
     * 权威的静默事实。False 会永久停止此拥有者的逆序排空，并
     * 把关闭失败发布给每个调用方；进程关闭路径随后可以 fail-stop，
     * 而不会在活跃 worker 下释放依赖。
     */
    fun <T> ownDependencyBarrier(
        name: String,
        resource: T,
        close: (T) -> Unit,
        dependenciesMayClose: (T) -> Boolean,
    ): T {
        register(
            OwnedResource(
                name = name,
                close = { close(resource) },
                dependenciesMayClose = { dependenciesMayClose(resource) },
            ),
        )
        return resource
    }

    private fun register(resource: OwnedResource) {
        val accepted = lifecycleLock.withLock {
            if (lifecycle == Lifecycle.OPEN) {
                resources.addLast(resource)
                true
            } else {
                false
            }
        }
        if (!accepted) closeLateResourceAndReject(resource)
    }

    fun <T> own(name: String, resource: T, close: (T) -> Unit): T {
        own(name) { close(resource) }
        return resource
    }

    override fun close() {
        if (!beginCloseOrAwaitTerminal()) return

        val observations = mutableListOf<CloseObservation>()
        var completedFailure: Throwable? = null
        try {
            while (true) {
                val resource = lifecycleLock.withLock {
                    if (resources.isEmpty()) null else resources.removeLast()
                } ?: break
                val result = closeResource(resource, enforceDependencyBarrier = true)
                observations.addAll(result.observations)
                if (!result.dependenciesMayClose) break
            }
            completedFailure = collapseCloseFailures(observations)
        } catch (unexpected: Throwable) {
            // 即使失败聚合本身出错，也防止跟随者永远等待。
            // 资源关闭与诊断失败已在上面捕获，因此这不是
            // 普通的致命错误路径。
            completedFailure = unexpected
        } finally {
            lifecycleLock.withLock {
                terminalFailure = completedFailure
                closingThread = null
                lifecycle = Lifecycle.CLOSED
                closedCondition.signalAll()
            }
        }
        completedFailure?.let { throw it }
    }

    /** 只对被选执行排空的调用方返回 true。 */
    private fun beginCloseOrAwaitTerminal(): Boolean = lifecycleLock.withLock {
        when (lifecycle) {
            Lifecycle.OPEN -> {
                lifecycle = Lifecycle.CLOSING
                closingThread = Thread.currentThread()
                true
            }

            Lifecycle.CLOSING -> {
                check(closingThread !== Thread.currentThread()) {
                    "Server resource close cannot recursively wait for its own drain"
                }
                while (lifecycle == Lifecycle.CLOSING) closedCondition.awaitUninterruptibly()
                terminalFailure?.let { throw it }
                false
            }

            Lifecycle.CLOSED -> {
                terminalFailure?.let { throw it }
                false
            }
        }
    }

    private fun closeLateResourceAndReject(resource: OwnedResource): Nothing {
        // 被拒绝的资源从未进入此拥有者的依赖顺序，因此其屏障不能
        // 支配正在被排空的资源。它仍然被立即关闭。
        val observations = closeResource(resource, enforceDependencyBarrier = false).observations
        val rejected = IllegalStateException("Server resource ownership is already closing or closed")
        if (observations.isEmpty()) throw rejected

        val errors = observations.flatMap { observation ->
            observation.errorsInOccurrenceOrder()
        }
        val fatal = errors.firstOrNull(Throwable::isFatalRuntimeFailure)
        if (fatal != null) {
            errors.forEach(fatal::addSuppressedDistinct)
            fatal.addSuppressedDistinct(rejected)
            throw fatal
        }
        errors.forEach(rejected::addSuppressedDistinct)
        throw rejected
    }

    private fun closeResource(
        resource: OwnedResource,
        enforceDependencyBarrier: Boolean,
    ): ResourceCloseResult {
        val observations = mutableListOf<CloseObservation>()
        try {
            resource.close()
        } catch (closeFailure: Throwable) {
            observations += observeFailure(resource.name, closeFailure)
        }

        val dependencyBarrier = resource.dependenciesMayClose
        if (!enforceDependencyBarrier || dependencyBarrier == null) {
            return ResourceCloseResult(observations, dependenciesMayClose = true)
        }

        val quiesced = try {
            dependencyBarrier()
        } catch (failure: Throwable) {
            observations += observeFailure(resource.name, failure)
            false
        }
        if (!quiesced && observations.isEmpty()) {
            observations += observeFailure(
                resource.name,
                ServerResourceDependencyStillActiveException(resource.name),
            )
        }
        return ResourceCloseResult(observations, dependenciesMayClose = quiesced)
    }

    private fun observeFailure(name: String, failure: Throwable): CloseObservation {
        var diagnosticFailure: Throwable? = null
        try {
            onCloseFailure(name, failure)
        } catch (diagnostic: Throwable) {
            if (diagnostic !== failure) diagnosticFailure = diagnostic
        }
        return CloseObservation(
            failure = ServerResourceCloseFailure(name, failure),
            diagnosticFailure = diagnosticFailure,
        )
    }

    private fun collapseCloseFailures(observations: List<CloseObservation>): Throwable? {
        if (observations.isEmpty()) return null

        val fatal = observations.asSequence()
            .flatMap { it.errorsInOccurrenceOrder().asSequence() }
            .firstOrNull(Throwable::isFatalRuntimeFailure)
        if (fatal != null) {
            observations.forEach { observation ->
                observation.errorsInOccurrenceOrder().forEach(fatal::addSuppressedDistinct)
            }
            return fatal
        }

        observations.forEach { observation ->
            observation.diagnosticFailure?.let {
                observation.failure.error.addSuppressedDistinct(it)
            }
        }
        return ServerResourceCloseException(observations.map(CloseObservation::failure))
    }

    private fun CloseObservation.errorsInOccurrenceOrder(): List<Throwable> =
        diagnosticFailure?.let { listOf(failure.error, it) } ?: listOf(failure.error)
}

internal data class ServerResourceCloseFailure(
    val resourceName: String,
    val error: Throwable,
)

internal class ServerResourceDependencyStillActiveException(
    resourceName: String,
) : IllegalStateException(
    "$resourceName did not quiesce; dependent server resources remain open",
)

internal class ServerResourceCloseException(
    val failures: List<ServerResourceCloseFailure>,
) : IllegalStateException(
    "Failed to close ${failures.size} server resource(s): " +
        failures.joinToString { it.resourceName },
    failures.first().error,
) {
    init {
        failures.drop(1).forEach { addSuppressedDistinct(it.error) }
    }
}
