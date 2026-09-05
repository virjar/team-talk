package com.virjar.tk.desktop

import androidx.compose.runtime.mutableStateOf
import com.virjar.tk.shared.client.ClientSession
import com.virjar.tk.shared.client.SessionEndReason
import com.virjar.tk.app.navigation.feature.document.DocumentDraftOwnerKey
import com.virjar.tk.app.navigation.feature.document.DocumentDraftStore
import com.virjar.tk.app.ui.UiActionAdmission
import com.virjar.tk.shared.log.AppLog
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException

/**
 * Desktop 的同步认证会话退役边界。
 *
 * 导航图拥有会在 destroy 钩子里继续调用 LocalCache 的 ViewModel，而平台资源根拥有仍借用会话凭据的
 * 媒体任务。这两类 owner 都必须先退役，AuthController 才被允许静止 ClientSession 并关闭 LocalCache。
 * 控制器在其本地线性化点之前调用 [beforeSessionRetirement]，并且总是会在 finally 中调用
 * [afterSessionRetirement]。并发跟随者会加入 CLOSING，并且在平台 owner 与控制器的会话边界
 * 都完成之前不能返回。
 */
internal class DesktopAuthenticatedUiRetirement(
    private val destroyNavigation: () -> Unit,
    private val closePlatformResources: () -> Unit,
    private val closePresentation: () -> Unit = {},
    private val captureDocumentDrafts: () -> Unit = {},
    private val discardDocumentDrafts: () -> Unit = {},
    private val sealDocumentDrafts: () -> Unit = {},
    private val destroyNavigationDiscardingDrafts: () -> Unit = destroyNavigation,
) {
    private val lifecycleLock = ReentrantLock()
    private val presentationRetirementCompleted = lifecycleLock.newCondition()
    private val retirementCompleted = lifecycleLock.newCondition()
    private val ownerRetirementCompleted = lifecycleLock.newCondition()
    private val draftDiscardCompleted = lifecycleLock.newCondition()
    private val draftSealCompleted = lifecycleLock.newCondition()
    private var sessionPhase = DesktopAuthenticatedUiRetirementPhase.OPEN
    private var sessionLeaderThread: Thread? = null
    private var presentationPhase = DesktopAuthenticatedUiRetirementPhase.OPEN
    private var presentationLeaderThread: Thread? = null
    private var ownerPhase = DesktopAuthenticatedUiRetirementPhase.OPEN
    private var ownerLeaderThread: Thread? = null
    private var draftDiscardRequested = false
    private var draftDiscardPhase = DesktopAuthenticatedUiRetirementPhase.OPEN
    private var draftDiscardLeaderThread: Thread? = null
    private var draftSealPhase = DesktopAuthenticatedUiRetirementPhase.OPEN
    private var draftSealLeaderThread: Thread? = null
    private var terminalFailures = emptyList<Throwable>()
    private var terminalFatalFailure: Throwable? = null

    fun beforeSessionRetirement(reason: SessionEndReason = SessionEndReason.SHUTDOWN) {
        // Window 拥有独立的组合，可能在 AuthState 移除它之前收到最后一次 flow 失效通知。
        // 在任何 owner 清理或会话静止之前先撤销 presentation。
        retirePresentation()
        val leader = lifecycleLock.withLock {
            requestDraftDispositionLocked(reason)
            when (sessionPhase) {
                DesktopAuthenticatedUiRetirementPhase.OPEN -> {
                    sessionPhase = DesktopAuthenticatedUiRetirementPhase.CLOSING
                    sessionLeaderThread = Thread.currentThread()
                    true
                }
                DesktopAuthenticatedUiRetirementPhase.CLOSING -> {
                    // AuthController 不会重入自己的边界。保留这个守卫，让意外的同线程回调不会死锁，
                    // 同时仍让每个真正并发的跟随者都汇入终结完成。
                    if (sessionLeaderThread === Thread.currentThread()) return
                    while (sessionPhase == DesktopAuthenticatedUiRetirementPhase.CLOSING) {
                        retirementCompleted.awaitUninterruptibly()
                    }
                    false
                }
                DesktopAuthenticatedUiRetirementPhase.CLOSED -> false
            }
        }
        if (!leader) {
            ensureDraftDiscarded()
            lifecycleLock.withLock { terminalFatalFailure }?.let { throw it }
            return
        }

        retireUiOwners(reason)
    }

    /**
     * Compose/application 销毁只负责退役的 UI 资源这一半。它绝不能成为认证会话的 leader：
     * AuthController 可能稍后在另一个线程到达，并且仍需要先通过其 before 钩子才能关闭 ClientSession。
     */
    fun retireFromComposition(reason: SessionEndReason = SessionEndReason.SHUTDOWN) {
        retirePresentation()
        lifecycleLock.withLock { requestDraftDispositionLocked(reason) }
        retireUiOwners(reason)
        lifecycleLock.withLock { terminalFatalFailure }?.let { throw it }
    }

    /**
     * Presentation 撤销是第一个、且仅执行一次的退役边沿。生产门禁在发布 Compose 状态之前
     * 就先翻转其原子准入标志，因此即使发布出现缺陷，也不会留下敞开的业务入口。
     * 外壳失败也不能跳过 navigation/platform 清理。
     */
    private fun retirePresentation() {
        val leader = lifecycleLock.withLock {
            when (presentationPhase) {
                DesktopAuthenticatedUiRetirementPhase.OPEN -> {
                    presentationPhase = DesktopAuthenticatedUiRetirementPhase.CLOSING
                    presentationLeaderThread = Thread.currentThread()
                    true
                }
                DesktopAuthenticatedUiRetirementPhase.CLOSING -> {
                    check(presentationLeaderThread !== Thread.currentThread()) {
                        "Desktop presentation retirement cannot re-enter its own close callback"
                    }
                    while (presentationPhase == DesktopAuthenticatedUiRetirementPhase.CLOSING) {
                        presentationRetirementCompleted.awaitUninterruptibly()
                    }
                    false
                }
                DesktopAuthenticatedUiRetirementPhase.CLOSED -> false
            }
        }
        if (!leader) return

        var failure: Throwable? = null
        try {
            closePresentation()
        } catch (caught: Throwable) {
            failure = caught
        }
        lifecycleLock.withLock {
            failure?.let { recordTerminalFailuresLocked(listOf(it)) }
            presentationPhase = DesktopAuthenticatedUiRetirementPhase.CLOSED
            presentationLeaderThread = null
            presentationRetirementCompleted.signalAll()
        }
    }

    /** 即使 before 钩子已经认领，bridge 仍必须送达 after 钩子的 reason。 */
    fun observeSessionEndReason(reason: SessionEndReason) {
        val ownerAlreadyRetired = lifecycleLock.withLock {
            requestDraftDispositionLocked(reason)
            ownerPhase == DesktopAuthenticatedUiRetirementPhase.CLOSED
        }
        if (ownerAlreadyRetired) ensureDraftDiscarded()
    }

    private fun retireUiOwners(reason: SessionEndReason) {
        val leader = lifecycleLock.withLock {
            requestDraftDispositionLocked(reason)
            when (ownerPhase) {
                DesktopAuthenticatedUiRetirementPhase.OPEN -> {
                    ownerPhase = DesktopAuthenticatedUiRetirementPhase.CLOSING
                    ownerLeaderThread = Thread.currentThread()
                    true
                }
                DesktopAuthenticatedUiRetirementPhase.CLOSING -> {
                    if (ownerLeaderThread === Thread.currentThread()) return
                    while (ownerPhase == DesktopAuthenticatedUiRetirementPhase.CLOSING) {
                        ownerRetirementCompleted.awaitUninterruptibly()
                    }
                    false
                }
                DesktopAuthenticatedUiRetirementPhase.CLOSED -> false
            }
        }
        if (!leader) {
            ensureDraftDiscarded()
            return
        }

        val failures = mutableListOf<Throwable>()
        fun release(action: () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                failures += failure
            }
        }

        if (lifecycleLock.withLock { draftDiscardRequested }) {
            ensureDraftDiscarded()
            release(destroyNavigationDiscardingDrafts)
        } else {
            release(captureDocumentDrafts)
            if (lifecycleLock.withLock { draftDiscardRequested }) {
                ensureDraftDiscarded()
                release(destroyNavigationDiscardingDrafts)
            } else {
                release(destroyNavigation)
            }
        }
        release(closePlatformResources)
        // 丢弃请求可能在 capture/navigation/platform 清理运行期间到达。持久化边沿独立于
        // 先到先赢的导航销毁门，因此即使 preserve 赢得了最初的 owner 竞争，
        // 这最后一次检查也能让 delete 保持单调。
        ensureDraftDiscarded()
        lifecycleLock.withLock {
            recordTerminalFailuresLocked(failures)
            ownerPhase = DesktopAuthenticatedUiRetirementPhase.CLOSED
            ownerLeaderThread = null
            ownerRetirementCompleted.signalAll()
        }
    }

    fun afterSessionRetirement() {
        var completedNow = false
        val mayComplete = lifecycleLock.withLock {
            if (sessionPhase != DesktopAuthenticatedUiRetirementPhase.CLOSING) return@withLock false
            while (ownerPhase == DesktopAuthenticatedUiRetirementPhase.CLOSING) {
                ownerRetirementCompleted.awaitUninterruptibly()
            }
            true
        }
        // after 钩子同样是一条 reason 送达边沿。它的丢弃请求可能与 owner 最后一次 preserve 检查发生竞争，
        // 因此在发布会话完成之前先封存删除。
        if (mayComplete) {
            ensureDraftDiscarded()
            sealDraftRetirement()
        }
        val completion = lifecycleLock.withLock {
            if (sessionPhase != DesktopAuthenticatedUiRetirementPhase.CLOSING) {
                return@withLock terminalFailures to terminalFatalFailure
            }
            completedNow = true
            sessionPhase = DesktopAuthenticatedUiRetirementPhase.CLOSED
            sessionLeaderThread = null
            retirementCompleted.signalAll()
            terminalFailures to terminalFatalFailure
        }
        val (failures, initialFatal) = completion
        var fatal = initialFatal
        if (completedNow && failures.isNotEmpty()) {
            try {
                AppLog.fault(
                    "DesktopRetirement",
                    "Authenticated UI retirement completed with ${failures.size} cleanup failure(s)",
                    failures.first(),
                )
            } catch (diagnosticFailure: Throwable) {
                if (isFatalDesktopLifecycleFailure(diagnosticFailure)) {
                    val mergedFatal = mergeDesktopLifecycleFailures(fatal, diagnosticFailure)
                    failures.forEach { failure -> addSuppressedDesktopLifecycleFailure(mergedFatal, failure) }
                    fatal = mergedFatal
                    lifecycleLock.withLock { terminalFatalFailure = mergedFatal }
                }
            }
        }
        fatal?.let { throw it }
    }

    private fun requestDraftDispositionLocked(reason: SessionEndReason) {
        if (reason.desktopDocumentDraftRetirementPolicy() == DesktopDocumentDraftRetirementPolicy.DISCARD) {
            draftDiscardRequested = true
        }
    }

    /** 破坏性边沿只执行一次；preserve 永远不能降级它，也不能在之后运行 capture。 */
    private fun ensureDraftDiscarded() {
        val leader = lifecycleLock.withLock {
            if (!draftDiscardRequested) return
            when (draftDiscardPhase) {
                DesktopAuthenticatedUiRetirementPhase.OPEN -> {
                    draftDiscardPhase = DesktopAuthenticatedUiRetirementPhase.CLOSING
                    draftDiscardLeaderThread = Thread.currentThread()
                    true
                }
                DesktopAuthenticatedUiRetirementPhase.CLOSING -> {
                    if (draftDiscardLeaderThread === Thread.currentThread()) return
                    while (draftDiscardPhase == DesktopAuthenticatedUiRetirementPhase.CLOSING) {
                        draftDiscardCompleted.awaitUninterruptibly()
                    }
                    false
                }
                DesktopAuthenticatedUiRetirementPhase.CLOSED -> false
            }
        }
        if (!leader) return

        var failure: Throwable? = null
        try {
            discardDocumentDrafts()
        } catch (caught: Throwable) {
            failure = caught
        }
        lifecycleLock.withLock {
            failure?.let { recordTerminalFailuresLocked(listOf(it)) }
            draftDiscardPhase = DesktopAuthenticatedUiRetirementPhase.CLOSED
            draftDiscardLeaderThread = null
            draftDiscardCompleted.signalAll()
        }
    }

    /** 只有在最终会话 reason 确定之后，才释放 preserve 升级权限。 */
    private fun sealDraftRetirement() {
        val leader = lifecycleLock.withLock {
            when (draftSealPhase) {
                DesktopAuthenticatedUiRetirementPhase.OPEN -> {
                    draftSealPhase = DesktopAuthenticatedUiRetirementPhase.CLOSING
                    draftSealLeaderThread = Thread.currentThread()
                    true
                }
                DesktopAuthenticatedUiRetirementPhase.CLOSING -> {
                    if (draftSealLeaderThread === Thread.currentThread()) return
                    while (draftSealPhase == DesktopAuthenticatedUiRetirementPhase.CLOSING) {
                        draftSealCompleted.awaitUninterruptibly()
                    }
                    false
                }
                DesktopAuthenticatedUiRetirementPhase.CLOSED -> false
            }
        }
        if (!leader) return

        var failure: Throwable? = null
        try {
            sealDocumentDrafts()
        } catch (caught: Throwable) {
            failure = caught
        }
        lifecycleLock.withLock {
            failure?.let { recordTerminalFailuresLocked(listOf(it)) }
            draftSealPhase = DesktopAuthenticatedUiRetirementPhase.CLOSED
            draftSealLeaderThread = null
            draftSealCompleted.signalAll()
        }
    }

    private fun recordTerminalFailuresLocked(failures: List<Throwable>) {
        if (failures.isEmpty()) return
        terminalFailures = terminalFailures + failures
        terminalFatalFailure = collapseDesktopLifecycleFailures(terminalFailures)
            ?.takeIf(::isFatalDesktopLifecycleFailure)
    }
}

internal enum class DesktopDocumentDraftRetirementPolicy { DISCARD, PRESERVE }

internal fun SessionEndReason.desktopDocumentDraftRetirementPolicy():
    DesktopDocumentDraftRetirementPolicy = when (this) {
        // 清除被拒绝的凭据只是撤销服务器访问权限；删除 owner 级离线工作并不是用户的意图。
        // 只有显式的账号登出才会越过这条破坏性边沿。
        SessionEndReason.USER_LOGOUT -> DesktopDocumentDraftRetirementPolicy.DISCARD

        SessionEndReason.AUTH_REVOKED,
        SessionEndReason.PROCESS_REPLACED,
        SessionEndReason.PROTOCOL_UPGRADE,
        SessionEndReason.SHUTDOWN -> DesktopDocumentDraftRetirementPolicy.PRESERVE
    }

private enum class DesktopAuthenticatedUiRetirementPhase { OPEN, CLOSING, CLOSED }

/**
 * 由 application 与 Window 组合以及迟到的 UI 回调共享的按会话准入。
 * 原子标志是线性化点；Compose 状态让关闭对两个组合都可见，同时不暴露任何 ClientSession 业务 owner。
 */
internal class DesktopSessionPresentationGate : UiActionAdmission {
    private val open = AtomicBoolean(true)
    private val admissionLock = Any()
    private val observableOpen = mutableStateOf(true)

    val isOpen: Boolean get() = observableOpen.value && open.get()

    override fun runIfOpen(action: () -> Unit): Boolean {
        if (!open.get()) return false
        return synchronized(admissionLock) {
            if (!open.get()) return@synchronized false
            action()
            true
        }
    }

    fun close(): Boolean {
        val claimed = open.compareAndSet(true, false)
        // CAS 是拒绝点，但每个关闭者（包括 CAS 失败者）都必须汇入已被获胜者接纳的同步 handler。
        // 在这里提前返回会让并发退役在该 handler 仍在使用 owner 时就把它们销毁。
        synchronized(admissionLock) {}
        observableOpen.value = false
        return claimed
    }
}

/**
 * 在 AuthController 之前创建的稳定 bridge，在会话级 Desktop owner 就绪后绑定。
 * 进行中的绑定在对应的 after 钩子到达之前，会在 Compose 销毁后继续存活。
 */
internal class DesktopAuthenticatedUiRetirementBridge {
    private val bindings =
        DesktopRetirementBindingRegistry<ClientSession, DesktopAuthenticatedUiRetirement>()

    fun bind(session: ClientSession, retirement: DesktopAuthenticatedUiRetirement): Closeable =
        bindings.bind(session, retirement)

    fun beforeSessionRetirement(session: ClientSession, reason: SessionEndReason) {
        bindings.claim(session)?.beforeSessionRetirement(reason)
    }

    fun afterSessionRetirement(session: ClientSession, reason: SessionEndReason) {
        val completion = bindings.complete(session) ?: return
        completeDesktopAuthenticatedUiRetirement(completion, reason)
    }
}

/** 转发每个钩子的 reason；after 先到的兜底路径也会补建缺失的 before 边界。 */
internal fun completeDesktopAuthenticatedUiRetirement(
    completion: DesktopRetirementBindingRegistry.Completion<DesktopAuthenticatedUiRetirement>,
    reason: SessionEndReason,
) {
    completion.owner.observeSessionEndReason(reason)
    if (completion.wasClaimed) {
        completion.owner.afterSessionRetirement()
        return
    }

    var failure: Throwable? = null
    try {
        completion.owner.beforeSessionRetirement(reason)
    } catch (caught: Throwable) {
        failure = caught
    } finally {
        try {
            completion.owner.afterSessionRetirement()
        } catch (caught: Throwable) {
            failure = mergeDesktopLifecycleFailures(failure, caught)
        }
    }
    failure?.let { throw it }
}

/**
 * 以 identity 为键、进程内进行 Compose 销毁与 AuthController 退役之间的交接。
 *
 * AuthController 串行化会话替换，因此最多只可能存在活跃会话与一个已分离或在途的前驱会话。
 * 强制这一上限会在出现所有权 bug 时大声失败，而不是泄漏 owner 或悄悄丢弃旧会话稍后送达的退役 reason。
 */
internal class DesktopRetirementBindingRegistry<K : Any, O : Any>(
    private val maxRetainedBindings: Int = MAX_RETAINED_BINDINGS,
) {
    internal data class Completion<O : Any>(
        val owner: O,
        val wasClaimed: Boolean,
    )

    private enum class State { ACTIVE, DETACHED, IN_FLIGHT }

    private class Binding<K : Any, O : Any>(
        val key: K,
        val owner: O,
        var state: State,
        var attachmentCount: Int,
    )

    private val lock = Any()
    private val retained = mutableListOf<Binding<K, O>>()
    private var active: Binding<K, O>? = null

    init {
        require(maxRetainedBindings >= 2) { "Desktop retirement handoff must retain a predecessor" }
    }

    fun bind(key: K, owner: O): Closeable {
        val binding = synchronized(lock) {
            retained.firstOrNull { it.key === key }?.let { existing ->
                require(existing.owner === owner) {
                    "One Desktop session cannot bind two authenticated UI owners"
                }
                check(existing.state != State.IN_FLIGHT) {
                    "A retiring Desktop session cannot rebind its authenticated UI owner"
                }
                check(active == null || active === existing) {
                    "A detached Desktop predecessor cannot replace the active session"
                }
                existing.state = State.ACTIVE
                existing.attachmentCount += 1
                active = existing
                return@synchronized existing
            }
            check(retained.size < maxRetainedBindings) {
                "Desktop session retirement handoff capacity was exceeded"
            }
            active?.let { previous ->
                previous.state = State.DETACHED
                active = null
            }
            Binding(key, owner, State.ACTIVE, attachmentCount = 1).also { created ->
                retained += created
                active = created
            }
        }
        val closed = AtomicBoolean(false)
        return Closeable {
            if (closed.compareAndSet(false, true)) detach(binding)
        }
    }

    fun claim(key: K): O? = synchronized(lock) {
        val binding = retained.firstOrNull { it.key === key && it.state == State.IN_FLIGHT }
            ?: active?.takeIf { it.key === key }
            ?: retained.firstOrNull { it.key === key && it.state == State.DETACHED }
            ?: return@synchronized null
        if (binding.state != State.IN_FLIGHT) {
            if (active === binding) active = null
            binding.state = State.IN_FLIGHT
        }
        binding.owner
    }

    fun complete(key: K): Completion<O>? = synchronized(lock) {
        val binding = retained.firstOrNull { it.key === key && it.state == State.IN_FLIGHT }
            ?: active?.takeIf { it.key === key }
            ?: retained.firstOrNull { it.key === key && it.state == State.DETACHED }
            ?: return@synchronized null
        val wasClaimed = binding.state == State.IN_FLIGHT
        if (active === binding) active = null
        retained.remove(binding)
        Completion(binding.owner, wasClaimed)
    }

    private fun detach(binding: Binding<K, O>) = synchronized(lock) {
        if (binding !in retained || binding.attachmentCount == 0) return@synchronized
        binding.attachmentCount -= 1
        if (binding.attachmentCount == 0 && binding.state == State.ACTIVE) {
            binding.state = State.DETACHED
            if (active === binding) active = null
        }
    }

    private companion object {
        const val MAX_RETAINED_BINDINGS = 2
    }
}

/**
 * 会话级 Desktop owner，有意闭合循环的生命周期边界：
 * [DesktopNav] 把认证过期回报给将要销毁它的退役 owner。
 */
internal class DesktopAuthenticatedUiOwner(
    session: ClientSession,
    dataDir: File,
    val presentationGate: DesktopSessionPresentationGate,
    closePlatformResources: () -> Unit,
    requestAuthExpired: () -> Unit,
    requestHttpAuthExpired: (rejectedAccessToken: String) -> Unit,
) {
    private val documentDraftOwnerKey = DocumentDraftOwnerKey(
        deploymentFingerprint = session.deploymentIdentity.fingerprint,
        datasetId = session.datasetId,
        uid = session.ownerUid,
    )
    private val documentDraftPersistence = DesktopDocumentDraftPersistence(
        dataDir = dataDir,
        ownerKey = documentDraftOwnerKey,
    )
    private val documentDrafts = DocumentDraftStore(documentDraftPersistence)
    val navigation = DesktopNav(
        session = session,
        documentDrafts = documentDrafts,
        onAuthExpired = requestAuthExpired,
        onHttpAuthExpired = requestHttpAuthExpired,
    )
    val retirement = DesktopAuthenticatedUiRetirement(
        destroyNavigation = {
            navigation.destroy(clearComposerContexts = true, clearDocumentDrafts = false)
        },
        destroyNavigationDiscardingDrafts = {
            navigation.destroy(clearComposerContexts = true, clearDocumentDrafts = true)
        },
        closePlatformResources = closePlatformResources,
        closePresentation = { presentationGate.close() },
        captureDocumentDrafts = {
            var captureFailure: Throwable? = null
            try {
                check(navigation.documents.retireDraftCapture()) {
                    "Desktop document draft capture failed"
                }
            } catch (failure: Throwable) {
                captureFailure = failure
                throw failure
            } finally {
                if (!documentDraftPersistence.retirePreservingDraft()) {
                    val flushFailure = IllegalStateException("Desktop document draft flush failed")
                    val primary = captureFailure
                    if (primary == null) {
                        throw flushFailure
                    } else if (primary !== flushFailure) {
                        primary.addSuppressed(flushFailure)
                    }
                }
            }
        },
        discardDocumentDrafts = {
            check(documentDraftPersistence.retireAndDelete()) {
                "Desktop document draft deletion failed"
            }
        },
        sealDocumentDrafts = documentDraftPersistence::sealPreservedDraft,
    )

    fun activateHttpAuthExpiredDelivery() {
        navigation.activateHttpAuthExpiredDelivery()
    }
}

private fun isFatalDesktopLifecycleFailure(failure: Throwable): Boolean =
    failure is CancellationException || failure !is Exception

private fun mergeDesktopLifecycleFailures(primary: Throwable?, additional: Throwable): Throwable {
    if (primary == null || primary === additional) return additional
    return if (!isFatalDesktopLifecycleFailure(primary) && isFatalDesktopLifecycleFailure(additional)) {
        addSuppressedDesktopLifecycleFailure(additional, primary)
        additional
    } else {
        addSuppressedDesktopLifecycleFailure(primary, additional)
        primary
    }
}

private fun collapseDesktopLifecycleFailures(failures: List<Throwable>): Throwable? {
    if (failures.isEmpty()) return null
    val primary = failures.firstOrNull(::isFatalDesktopLifecycleFailure) ?: failures.first()
    failures.forEach { failure -> addSuppressedDesktopLifecycleFailure(primary, failure) }
    return primary
}

private fun addSuppressedDesktopLifecycleFailure(primary: Throwable, additional: Throwable) {
    if (primary !== additional && primary.suppressed.none { it === additional }) {
        primary.addSuppressed(additional)
    }
}
