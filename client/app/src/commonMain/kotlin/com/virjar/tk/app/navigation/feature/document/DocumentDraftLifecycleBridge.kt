package com.virjar.tk.app.navigation.feature.document

import kotlinx.coroutines.CancellationException

/**
 * 参与草稿捕获屏障的那个确切组合编辑器帧的身份。
 */
internal data class DocumentDraftCaptureOwner(
    val tabId: String,
    val instanceId: Long,
    val recoveryId: String,
    val revision: Long?,
) {
    companion object {
        fun capture(tab: DocumentTabState) = DocumentDraftCaptureOwner(
            tabId = tab.tabId,
            instanceId = tab.instanceId,
            recoveryId = tab.recoveryId,
            revision = tab.revision,
        )
    }
}

internal sealed interface DocumentDraftCaptureOutcome {
    data object NoEditor : DocumentDraftCaptureOutcome
    data class Captured(val owner: DocumentDraftCaptureOwner?) : DocumentDraftCaptureOutcome
    data class Failed(val owner: DocumentDraftCaptureOwner?) : DocumentDraftCaptureOutcome
}

/**
 * 当前组合的文档编辑器与平台生命周期之间的同步桥接。
 *
 * Compose 拥有富编辑器状态，而 [DocumentWorkspaceFeature] 拥有可持久化的 tab 状态。
 * Android 的 `Activity.onStop` 不能等待之后的 composition/disposal 回调，因此活动编辑器
 * 注册一个动作，该动作首先把它最新的视觉/源码值发布回 feature。注册是 owner 安全的：
 * 在新编辑器挂载之后销毁旧编辑器，绝不能清除新编辑器的动作。
 */
class DocumentDraftLifecycleBridge {
    internal class Registration internal constructor(internal val id: Long)

    private data class Entry(
        val registration: Registration,
        val owner: DocumentDraftCaptureOwner?,
        val captureAndPublish: () -> Unit,
    )

    private val lock = Any()
    private var nextRegistrationId = 0L
    private var active: Entry? = null
    private var phase = DocumentDraftCapturePhase.OPEN
    private var retirementLeader: Thread? = null
    private var terminalCaptureSucceeded = true

    internal fun register(captureAndPublish: () -> Unit): Registration =
        registerEntry(owner = null, captureAndPublish = captureAndPublish)

    internal fun register(
        owner: DocumentDraftCaptureOwner,
        captureAndPublish: () -> Unit,
    ): Registration = registerEntry(owner, captureAndPublish)

    private fun registerEntry(
        owner: DocumentDraftCaptureOwner?,
        captureAndPublish: () -> Unit,
    ): Registration = synchronized(lock) {
        val nextId = (nextRegistrationId + 1L).takeUnless { it == 0L } ?: 1L
        nextRegistrationId = nextId
        val registration = Registration(nextId)
        if (phase == DocumentDraftCapturePhase.OPEN) {
            active = Entry(registration, owner, captureAndPublish)
        }
        return registration
    }

    internal fun unregister(registration: Registration) = synchronized(lock) {
        if (active?.registration === registration) active = null
    }

    /** 普通的编辑器发布；终止捕获可以在持有桥接锁的同时重入。 */
    internal fun publishIfOpen(action: () -> Unit): Boolean = synchronized(lock) {
        val admitted = phase == DocumentDraftCapturePhase.OPEN ||
            (phase == DocumentDraftCapturePhase.CLOSING && retirementLeader === Thread.currentThread())
        if (!admitted) return@synchronized false
        action()
        true
    }

    /** 只有当已挂载的编辑器发布失败时才返回 false；没有编辑器是一个有效状态。 */
    internal fun captureLatest(): Boolean = synchronized(lock) {
        if (phase == DocumentDraftCapturePhase.CLOSED) return@synchronized terminalCaptureSucceeded
        if (phase == DocumentDraftCapturePhase.CLOSING) {
            return@synchronized retirementLeader === Thread.currentThread() && captureActiveLocked()
        }
        captureActiveLocked()
    }

    /**
     * 同步捕获并报告哪个组合编辑器产生了该帧。权威屏障使用 owner 戳记来拒绝
     * 仍然为更旧 revision 组合着的编辑器。
     */
    internal fun captureLatestOutcome(): DocumentDraftCaptureOutcome = synchronized(lock) {
        if (phase == DocumentDraftCapturePhase.CLOSED) {
            return@synchronized if (terminalCaptureSucceeded) {
                DocumentDraftCaptureOutcome.NoEditor
            } else {
                DocumentDraftCaptureOutcome.Failed(owner = null)
            }
        }
        if (phase == DocumentDraftCapturePhase.CLOSING &&
            retirementLeader !== Thread.currentThread()
        ) {
            return@synchronized DocumentDraftCaptureOutcome.Failed(active?.owner)
        }
        val entry = active ?: return@synchronized DocumentDraftCaptureOutcome.NoEditor
        if (captureEntryLocked(entry)) {
            DocumentDraftCaptureOutcome.Captured(entry.owner)
        } else {
            DocumentDraftCaptureOutcome.Failed(entry.owner)
        }
    }

    /** Compose 销毁只原子地捕获并摘除它自己的那个确切的编辑器注册。 */
    internal fun captureAndUnregister(registration: Registration): Boolean = synchronized(lock) {
        val entry = active?.takeIf { it.registration === registration } ?: return@synchronized true
        if (phase != DocumentDraftCapturePhase.OPEN) return@synchronized terminalCaptureSucceeded
        try {
            captureEntryLocked(entry)
        } finally {
            if (active?.registration === registration) active = null
        }
    }

    /**
     * 终止 owner 边界：捕获最终编辑器帧，然后拒绝每一个迟到的 effect/dispose。
     * 在捕获期间持有 [lock] 使其嵌套的 [publishIfOpen] 成为唯一的 CLOSING 重入。
     */
    internal fun captureAndRetire(): Boolean = synchronized(lock) {
        when (phase) {
            DocumentDraftCapturePhase.CLOSED -> return@synchronized terminalCaptureSucceeded
            DocumentDraftCapturePhase.CLOSING -> {
                check(retirementLeader === Thread.currentThread()) {
                    "Document draft retirement cannot expose CLOSING outside its leader"
                }
                return@synchronized terminalCaptureSucceeded
            }
            DocumentDraftCapturePhase.OPEN -> {
                phase = DocumentDraftCapturePhase.CLOSING
                retirementLeader = Thread.currentThread()
            }
        }
        try {
            terminalCaptureSucceeded = captureActiveLocked()
            terminalCaptureSucceeded
        } finally {
            active = null
            retirementLeader = null
            phase = DocumentDraftCapturePhase.CLOSED
        }
    }

    private fun captureActiveLocked(): Boolean = active?.let(::captureEntryLocked) ?: true

    private fun captureEntryLocked(entry: Entry): Boolean = try {
        entry.captureAndPublish()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}

private enum class DocumentDraftCapturePhase { OPEN, CLOSING, CLOSED }
