package com.virjar.tk.server.infra.health

import com.virjar.tk.server.domain.telemetry.ClientTelemetryEventStore
import com.virjar.tk.server.domain.telemetry.TelemetryRetentionStatus
import com.virjar.tk.server.domain.message.MessageProjectionFailure
import com.virjar.tk.server.domain.message.MessageProjectionReadiness
import com.virjar.tk.server.domain.organization.OrganizationProjectionReadiness
import com.virjar.tk.server.infra.db.PostgresHealthProbePolicy
import com.virjar.tk.server.infra.search.SearchIndex
import com.virjar.tk.server.infra.storage.FileStore
import com.virjar.tk.server.infra.storage.MessageStore
import com.virjar.tk.server.infra.sync.SyncEventDispatcher
import com.virjar.tk.server.infra.sync.SyncEventDispatcherSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

@Serializable
data class ComponentHealth(
    val status: String,
    val detail: String? = null,
    val lastSuccessAt: Long? = null,
    val backlog: Boolean? = null,
    val overdue: Boolean? = null,
)

@Serializable
data class HealthResponse(
    val status: String,
    val components: Map<String, ComponentHealth>,
    val buildIdentity: String,
)

class HealthChecker internal constructor(
    private val database: Database,
    private val messageStore: MessageStore,
    private val searchIndex: SearchIndex,
    private val fileStore: FileStore,
    private val messageProjectionReadiness: MessageProjectionReadiness,
    private val organizationProjectionReadiness: OrganizationProjectionReadiness,
    private val syncEventDispatcher: SyncEventDispatcher,
    private val clientTelemetryEvents: ClientTelemetryEventStore,
    tcpProbeConfiguration: TcpHealthProbeConfiguration,
    private val buildIdentity: String = ServerBuildIdentity.current.buildIdentity,
) {
    init {
        require(buildIdentity.isNotBlank()) { "Server build identity must not be blank" }
    }

    private val singleFlight = HealthCheckSingleFlight()
    private val tcpProbe = TcpHealthProbe(tcpProbeConfiguration)

    suspend fun check(): HealthResponse = singleFlight.get(::checkFresh)

    private suspend fun checkFresh(): HealthResponse {
        val external = runConcurrentExternalHealthProbes(
            postgres = ::checkDatabase,
            managedChatProjection = ::checkManagedChatProjection,
            tcp = ::checkTcp,
        )
        val components = linkedMapOf(
            "postgres" to external.postgres,
            "rocksdb" to checkRocksDB(),
            "lucene" to checkLucene(),
            "message-projection" to checkMessageProjection(),
            "managed-chat-projection" to external.managedChatProjection,
            "sync-event-dispatcher" to syncEventDispatcherHealth(syncEventDispatcher.snapshot()),
            "client-telemetry" to runCatching {
                clientTelemetryHealth(
                    available = clientTelemetryEvents.isAvailable(),
                    retention = clientTelemetryEvents.retentionStatus(),
                )
            }.getOrElse {
                clientTelemetryHealth(available = false, retention = null)
            },
            "file-storage" to checkFileStorage(),
            "tcp" to external.tcp,
        )

        val overallStatus = readinessStatus(components)
        return HealthResponse(overallStatus, components, buildIdentity)
    }

    private suspend fun checkDatabase(): ComponentHealth =
        when (
            runTimedBlockingHealthProbe(PostgresHealthProbePolicy.OUTER_TIMEOUT_MILLIS) {
                transaction(database) {
                    PostgresHealthProbePolicy.run(this) {
                        check(exec("SELECT 1") { result ->
                            result.next() && result.getInt(1) == 1
                        } == true) { "PostgreSQL validation query returned no row" }
                    }
                }
            }
        ) {
            is BlockingProbeOutcome.Success -> ComponentHealth("UP")
            is BlockingProbeOutcome.Failure -> ComponentHealth("DOWN", "PostgreSQL probe failed")
            BlockingProbeOutcome.TimedOut -> ComponentHealth("DOWN", "PostgreSQL probe timed out")
        }

    private fun checkRocksDB(): ComponentHealth =
        if (messageStore.isRunning) ComponentHealth("UP")
        else ComponentHealth("DOWN", "RocksDB not initialized")

    private fun checkLucene(): ComponentHealth =
        if (searchIndex.isRunning) ComponentHealth("UP")
        else ComponentHealth("DOWN", "Lucene index not initialized")

    private fun checkMessageProjection(): ComponentHealth =
        messageProjectionHealth(messageProjectionReadiness.currentFailure())

    private suspend fun checkManagedChatProjection(): ComponentHealth =
        when (
            val outcome = runTimedBlockingHealthProbe(PostgresHealthProbePolicy.OUTER_TIMEOUT_MILLIS) {
                val pending = organizationProjectionReadiness.pendingCount()
                val failure = if (pending == 0L) null else organizationProjectionReadiness.currentFailure()
                pending to failure
            }
        ) {
            is BlockingProbeOutcome.Success -> {
                val (pending, failure) = outcome.value
                managedChatProjectionHealth(pending, failure != null)
            }
            is BlockingProbeOutcome.Failure ->
                ComponentHealth("DOWN", "Managed-chat readiness probe failed")
            BlockingProbeOutcome.TimedOut ->
                ComponentHealth("DOWN", "Managed-chat readiness probe timed out")
        }

    private fun checkFileStorage(): ComponentHealth =
        if (fileStore.isHealthy) ComponentHealth("UP")
        else ComponentHealth("DOWN", "FileStore not initialized")

    private suspend fun checkTcp(): ComponentHealth = tcpProbe.check()
}

/** 一次性诊断保持可观察，而无需把消息服务移出轮换。 */
internal fun readinessStatus(components: Map<String, ComponentHealth>): String =
    if (components
            .filterKeys { it !in NON_CRITICAL_HEALTH_COMPONENTS }
            .values
            .all { it.status == "UP" }
    ) {
        "UP"
    } else {
        "DOWN"
    }

private val NON_CRITICAL_HEALTH_COMPONENTS = setOf("client-telemetry")

internal data class TcpHealthProbeConfiguration(
    val connectHost: String,
    val port: Int,
    val security: TcpHealthProbeSecurity,
) {
    init {
        require(connectHost.isNotBlank()) { "TCP health probe host must not be blank" }
        require(port in 1..65535) { "TCP health probe port must be in 1..65535" }

    }

    companion object {
        fun plaintext(
            port: Int,
            connectHost: String = "127.0.0.1",
        ): TcpHealthProbeConfiguration = TcpHealthProbeConfiguration(
            connectHost = connectHost,
            port = port,
            security = TcpHealthProbeSecurity.Plaintext,
        )
    }
}

internal sealed interface TcpHealthProbeSecurity {
    class Tls(val socketFactory: SSLSocketFactory) : TcpHealthProbeSecurity
    data object Plaintext : TcpHealthProbeSecurity
}

internal class TcpHealthProbe(
    private val configuration: TcpHealthProbeConfiguration,
) {
    suspend fun check(): ComponentHealth =
        when (
            runTimedBlockingHealthProbe(TCP_PROBE_OUTER_TIMEOUT_MILLIS) {
                when (val security = configuration.security) {
                    is TcpHealthProbeSecurity.Tls -> probeTls(security.socketFactory)
                    TcpHealthProbeSecurity.Plaintext -> probePlaintext()
                }
            }
        ) {
            is BlockingProbeOutcome.Success -> ComponentHealth("UP")
            is BlockingProbeOutcome.Failure -> ComponentHealth("DOWN", "TCP transport probe failed")
            BlockingProbeOutcome.TimedOut -> ComponentHealth("DOWN", "TCP transport probe timed out")
        }

    private fun probePlaintext() {
        Socket().use { socket ->
            socket.connect(
                InetSocketAddress(configuration.connectHost, configuration.port),
                TCP_CONNECT_TIMEOUT_MILLIS,
            )
        }
    }

    private fun probeTls(socketFactory: SSLSocketFactory) {
        val socket = socketFactory.createSocket() as SSLSocket
        socket.use {
            it.useClientMode = true
            it.soTimeout = TCP_CONNECT_TIMEOUT_MILLIS
            it.connect(
                InetSocketAddress(configuration.connectHost, configuration.port),
                TCP_CONNECT_TIMEOUT_MILLIS,
            )
            it.startHandshake()
            check(it.session.peerCertificates.isNotEmpty()) { "TCP TLS peer did not present a certificate" }
        }
    }
}

/**
 * 把并发公共健康检查的放大限制为一次活跃评估，同时不在该评估完成后
 * 缓存过期的就绪结果。
 *
 * 共享的 deferred 无父级，因此取消一个等待的调用方不会取消
 * 另一个调用方拥有的评估。拥有者仍在其自己的结构化作用域中运行 [refresh]；
 * 其取消会被传播，并让之后的请求从干净状态重试。
 */
internal class HealthCheckSingleFlight {
    private val active = AtomicReference<CompletableDeferred<HealthResponse>?>(null)

    suspend fun get(refresh: suspend () -> HealthResponse): HealthResponse {
        while (true) {
            active.get()?.let { observed ->
                try {
                    return observed.await()
                } catch (_: CancellationException) {
                    // await 可被取消，但不会取消此无父级 deferred。若此
                    // 调用方仍处于活跃状态，说明 refresh 拥有者已被取消；重试拥有权。
                    currentCoroutineContext().ensureActive()
                }
            }
            val owned = CompletableDeferred<HealthResponse>()
            if (!active.compareAndSet(null, owned)) continue

            try {
                val response = refresh()
                active.compareAndSet(owned, null)
                owned.complete(response)
                return response
            } catch (cancelled: CancellationException) {
                active.compareAndSet(owned, null)
                owned.completeExceptionally(cancelled)
                throw cancelled
            } catch (failure: Throwable) {
                active.compareAndSet(owned, null)
                owned.completeExceptionally(failure)
                throw failure
            } finally {
                active.compareAndSet(owned, null)
            }
        }
    }
}

internal data class ExternalProbeHealth(
    val postgres: ComponentHealth,
    val managedChatProjection: ComponentHealth,
    val tcp: ComponentHealth,
)

/** 并发执行独立的慢探测，使总延迟由最慢的探测界定。 */
internal suspend fun runConcurrentExternalHealthProbes(
    postgres: suspend () -> ComponentHealth,
    managedChatProjection: suspend () -> ComponentHealth,
    tcp: suspend () -> ComponentHealth,
): ExternalProbeHealth = coroutineScope {
    val postgresResult = async { postgres() }
    val managedChatProjectionResult = async { managedChatProjection() }
    val tcpResult = async { tcp() }
    ExternalProbeHealth(
        postgres = postgresResult.await(),
        managedChatProjection = managedChatProjectionResult.await(),
        tcp = tcpResult.await(),
    )
}

/** 只映射公共安全的快照；分发器保留的 Throwable 绝不到达 HTTP。 */
internal fun syncEventDispatcherHealth(snapshot: SyncEventDispatcherSnapshot): ComponentHealth =
    if (snapshot.live && snapshot.ready) {
        ComponentHealth("UP")
    } else {
        ComponentHealth("DOWN", snapshot.detail ?: "Durable sync dispatcher is unavailable")
    }

/** 健康信息是公开的：投影键与保留的异常文本留在服务器自有日志中。 */
internal fun messageProjectionHealth(failure: MessageProjectionFailure?): ComponentHealth =
    if (failure == null) {
        ComponentHealth("UP")
    } else {
        ComponentHealth("DOWN", "Message projection recovery is pending")
    }

/** 公共健康只暴露有界的生命周期事实；路径与保留的写入器失败保持私有。 */
internal fun clientTelemetryHealth(
    available: Boolean,
    retention: TelemetryRetentionStatus? = null,
): ComponentHealth {
    val safeRetention = retention ?: TelemetryRetentionStatus(
        lastSuccessAt = null,
        backlog = true,
        overdue = true,
    )
    val status = when {
        !available -> "DOWN"
        safeRetention.overdue -> "DOWN"
        else -> "UP"
    }
    val detail = when {
        !available -> "Client telemetry event store is unavailable"
        safeRetention.overdue -> "Client telemetry retention is overdue"
        else -> null
    }
    return ComponentHealth(
        status = status,
        detail = detail,
        lastSuccessAt = safeRetention.lastSuccessAt,
        backlog = safeRetention.backlog,
        overdue = safeRetention.overdue,
    )
}

/** 只暴露生命周期状态；unit id、版本、尝试次数与数据库错误均为内部信息。 */
internal fun managedChatProjectionHealth(pending: Long, failed: Boolean): ComponentHealth {
    require(pending >= 0L) { "Managed-chat projection pending count cannot be negative" }
    return when {
        pending == 0L -> ComponentHealth("UP")
        failed -> ComponentHealth("DOWN", "Managed-chat projection recovery has failed")
        else -> ComponentHealth("DOWN", "Managed-chat projection recovery is pending")
    }
}

internal sealed interface BlockingProbeOutcome<out T> {
    data class Success<T>(val value: T) : BlockingProbeOutcome<T>
    data class Failure(val cause: Exception) : BlockingProbeOutcome<Nothing>
    data object TimedOut : BlockingProbeOutcome<Nothing>
}

/** 中断协作式阻塞调用，同时保留 HTTP 调用方拥有的取消。 */
internal suspend fun <T> runTimedBlockingHealthProbe(
    timeoutMillis: Long,
    block: () -> T,
): BlockingProbeOutcome<T> {
    require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
    return withContext(Dispatchers.IO) {
        // 截止时间属于阻塞 I/O 边界，不属于调用方的分发器。
        // 特别地，虚拟时间测试分发器绝不能在实际 JDBC worker
        // 刚被调度时就推进此超时。
        withTimeoutOrNull(timeoutMillis) {
            try {
                BlockingProbeOutcome.Success(runInterruptible { block() })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                BlockingProbeOutcome.Failure(failure)
            }
        } ?: BlockingProbeOutcome.TimedOut
    }
}

private const val TCP_CONNECT_TIMEOUT_MILLIS = 3_000
private const val TCP_PROBE_OUTER_TIMEOUT_MILLIS = 4_000L
