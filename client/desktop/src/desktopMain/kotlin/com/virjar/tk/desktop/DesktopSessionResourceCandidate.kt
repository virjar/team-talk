package com.virjar.tk.desktop

import com.virjar.tk.desktop.media.DesktopSessionResources
import java.io.Closeable

/**
 * 后台构造出的会话候选；尚未绑定 UI 退役钩子时，同时拥有媒体和草稿 writer。
 * 绑定以后媒体仍归安装器关闭，草稿则由带 SessionEndReason 的 UI 退役流程处置。
 */
internal class DesktopSessionResourceCandidate private constructor(
    val media: DesktopSessionResources,
    val documentDraftPersistence: DesktopDocumentDraftPersistence,
) : Closeable {
    /** 未交付的候选不能删除草稿；即使排空失败，也必须封存租约并关闭媒体。 */
    override fun close() {
        var failure: Throwable? = null
        try {
            check(documentDraftPersistence.retirePreservingDraft()) {
                "Unpublished Desktop document draft writer did not drain"
            }
        } catch (caught: Throwable) {
            failure = caught
        }
        try {
            documentDraftPersistence.sealPreservedDraft()
        } catch (caught: Throwable) {
            failure = mergeDesktopLifecycleFailures(failure, caught)
        }
        try {
            media.close()
        } catch (caught: Throwable) {
            failure = mergeDesktopLifecycleFailures(failure, caught)
        }
        failure?.let { throw it }
    }

    companion object {
        /** 后一项构造失败时，前一项还没有外部 owner，必须在这里释放。 */
        fun create(
            createMedia: () -> DesktopSessionResources,
            createDocumentDraftPersistence: () -> DesktopDocumentDraftPersistence,
        ): DesktopSessionResourceCandidate {
            val media = createMedia()
            return try {
                DesktopSessionResourceCandidate(media, createDocumentDraftPersistence())
            } catch (failure: Throwable) {
                val closeFailure = runCatching { media.close() }.exceptionOrNull()
                throw if (closeFailure == null) failure else mergeDesktopLifecycleFailures(failure, closeFailure)
            }
        }
    }
}
