package com.virjar.tk.android

import com.virjar.tk.shared.client.AndroidLocalCacheStorageCloseException
import com.virjar.tk.shared.client.ClientSession
import com.virjar.tk.shared.client.DeploymentIdentity
import com.virjar.tk.shared.client.LocalCacheStorageCompactionException
import com.virjar.tk.shared.client.LocalCacheStorageCompactionFailure
import com.virjar.tk.shared.client.LocalCacheStorageCompactionReport
import com.virjar.tk.shared.client.SessionEndReason
import com.virjar.tk.shared.client.SessionLifecyclePhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class AndroidStorageMaintenancePhase {
    IDLE, PREPARING, READY, RUNNING, FINISHED, RETIREMENT_FAILED,
}

internal data class AndroidStorageMaintenanceState(
    val phase: AndroidStorageMaintenancePhase = AndroidStorageMaintenancePhase.IDLE,
    val result: LocalCacheStorageCompactionReport? = null,
    val errorMessage: String? = null,
)

/**
 * Application owns this one maintenance operation, independently of Activity and authenticated UI
 * scopes. A non-IDLE state keeps authentication out of composition until the offline driver closes.
 * Nothing is persisted: process death uses SQLite recovery on the next normal open, never auto-VACUUM.
 */
internal class AndroidStorageMaintenanceOwner(
    private val deploymentIdentity: DeploymentIdentity,
    private val compactStorage: (DeploymentIdentity, String, String) -> LocalCacheStorageCompactionReport,
) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(AndroidStorageMaintenanceState())
    val state: StateFlow<AndroidStorageMaintenanceState> = mutableState.asStateFlow()
    private var retiringOwner: Any? = null
    private var target: Target? = null

    /** No database work here: changing the root surface first triggers ordinary SHUTDOWN retirement. */
    fun request(session: ClientSession): Boolean = beginRequest(
        session, session.deploymentIdentity, session.datasetId, session.ownerUid, session.isBusinessActive,
    )

    /** The platform boundary supplies an exact owner identity; this step itself never borrows SQLite. */
    internal fun beginRequest(
        owner: Any,
        identity: DeploymentIdentity,
        datasetId: String,
        uid: String,
        active: Boolean,
    ): Boolean = synchronized(lock) {
        if (mutableState.value.phase != AndroidStorageMaintenancePhase.IDLE ||
            !active || identity != deploymentIdentity
        ) return@synchronized false
        retiringOwner = owner
        target = Target(datasetId, uid)
        mutableState.value = AndroidStorageMaintenanceState(AndroidStorageMaintenancePhase.PREPARING)
        true
    }

    /** The existing AuthController invokes this only after its entire retirement body has returned. */
    fun onSessionRetired(session: ClientSession, reason: SessionEndReason) = completeRetirement(
        session,
        reason == SessionEndReason.SHUTDOWN && session.endReason == SessionEndReason.SHUTDOWN &&
            session.lifecyclePhase == SessionLifecyclePhase.CLOSED && session.resourceRetirementFailure == null,
    )

    internal fun completeRetirement(owner: Any, closedSuccessfully: Boolean) = synchronized(lock) {
        if (retiringOwner !== owner ||
            mutableState.value.phase != AndroidStorageMaintenancePhase.PREPARING
        ) return@synchronized
        retiringOwner = null
        mutableState.value = if (closedSuccessfully) {
            AndroidStorageMaintenanceState(AndroidStorageMaintenancePhase.READY)
        } else {
            retirementFailedState()
        }
    }

    fun onRetirementFailed(session: ClientSession) = completeRetirement(session, closedSuccessfully = false)

    /** There is deliberately no Activity cancellation or synchronous join of blocking SQLite work. */
    fun compact(): Boolean {
        val selected = synchronized(lock) {
            if (mutableState.value.phase !in setOf(
                    AndroidStorageMaintenancePhase.READY, AndroidStorageMaintenancePhase.FINISHED,
                )
            ) return false
            val selected = checkNotNull(target)
            mutableState.value = AndroidStorageMaintenanceState(AndroidStorageMaintenancePhase.RUNNING)
            selected
        }
        scope.launch {
            val completed = try {
                val report = compactStorage(deploymentIdentity, selected.datasetId, selected.uid)
                AndroidStorageMaintenanceState(AndroidStorageMaintenancePhase.FINISHED, result = report)
            } catch (_: AndroidLocalCacheStorageCloseException) {
                // Do not let a new authentication owner overlap a driver that failed to close.
                retirementFailedState()
            } catch (failure: LocalCacheStorageCompactionException) {
                AndroidStorageMaintenanceState(
                    AndroidStorageMaintenancePhase.FINISHED,
                    errorMessage = failure.reason.userMessage(),
                )
            } catch (failure: Throwable) {
                if (failure is CancellationException || failure !is Exception) {
                    synchronized(lock) { mutableState.value = retirementFailedState() }
                    throw failure
                }
                AndroidStorageMaintenanceState(
                    AndroidStorageMaintenancePhase.FINISHED,
                    errorMessage = LocalCacheStorageCompactionFailure.STORAGE_IO_FAILED.userMessage(),
                )
            }
            synchronized(lock) { mutableState.value = completed }
        }
        return true
    }

    /** Only explicit return from a completed/unused maintenance surface starts normal authentication. */
    fun finish(): Boolean = synchronized(lock) {
        if (mutableState.value.phase != AndroidStorageMaintenancePhase.READY &&
            mutableState.value.phase != AndroidStorageMaintenancePhase.FINISHED
        ) return@synchronized false
        target = null
        mutableState.value = AndroidStorageMaintenanceState()
        true
    }

    private data class Target(val datasetId: String, val uid: String)

    private fun retirementFailedState() = AndroidStorageMaintenanceState(
        AndroidStorageMaintenancePhase.RETIREMENT_FAILED,
        errorMessage = "本地资源未能正常关闭，暂时无法重新打开工作区。请关闭应用后重新打开。",
    )
}

private fun LocalCacheStorageCompactionFailure.userMessage(): String = when (this) {
    LocalCacheStorageCompactionFailure.UNSUPPORTED_STORAGE -> "当前本地存储不支持整理。"
    LocalCacheStorageCompactionFailure.QUARANTINE_REQUIRES_DISPOSITION ->
        "发现保留的隔离副本。请先导出并明确处置隔离副本，再整理数据库。"
    LocalCacheStorageCompactionFailure.DATABASE_IN_USE -> "数据库仍在使用中，请稍后重试。"
    LocalCacheStorageCompactionFailure.UNSUPPORTED_SCHEMA_VERSION -> "数据库版本不适用于当前应用，无法整理。"
    LocalCacheStorageCompactionFailure.INTEGRITY_CHECK_FAILED -> "数据库完整性检查未通过，请先保全本地资料。"
    LocalCacheStorageCompactionFailure.DATABASE_SIZE_LIMIT -> "数据库超过 64 MiB 的整理上限，请联系维护者处理。"
    LocalCacheStorageCompactionFailure.INSUFFICIENT_FREE_SPACE -> "可用存储空间不足，请释放设备空间后重试。"
    LocalCacheStorageCompactionFailure.CHECKPOINT_INCOMPLETE -> "数据库收尾未完成，请稍后重试。"
    LocalCacheStorageCompactionFailure.STORAGE_IO_FAILED -> "无法完成本地数据库整理，请稍后重试。"
}
