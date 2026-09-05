package com.virjar.tk.app.navigation.feature

import com.virjar.tk.protocol.body.OfficeRefBody
import com.virjar.tk.protocol.model.GroupFileEntry
import com.virjar.tk.shared.client.ClientSession
import com.virjar.tk.shared.log.AppLog
import java.util.UUID
import kotlinx.coroutines.CancellationException

enum class OfficeReferenceKind { DOCUMENT, GROUP_FILE }

/**
 * 会话级消息动作：收藏消息，以及加载和打开办公引用。
 *
 * 候选本身就是带完整身份的 [OfficeRefBody]，双端只展示和选择，不拼接、拆解 ID。
 * 这里的标题用于选择器；发送时服务端仍会生成权威预览。导航与会话协程由宿主提供。
 */
class MessageActionsFeature internal constructor(
    private val session: ClientSession,
    private val launchAction: (suspend () -> Unit) -> Boolean,
) {
    // 同一源消息失败后重试复用 operationId，成功后才移除；切换聊天不丢失待确认命令。
    private val pendingSaves = mutableMapOf<Pair<String, Long>, String>()

    fun save(srcChatId: String, srcSeq: Long, onResult: (Boolean) -> Unit = {}) {
        val launched = launchAction {
            try {
                val key = srcChatId to srcSeq
                val operationId = synchronized(pendingSaves) {
                    pendingSaves.getOrPut(key) { UUID.randomUUID().toString() }
                }
                session.messageRepo.saveMessage(srcChatId, srcSeq, operationId).getOrThrow()
                synchronized(pendingSaves) { pendingSaves.remove(key) }
                onResult(true)
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                AppLog.trace("MessageActionsFeature", "save failed: ${failure::class.simpleName}: ${failure.message}")
                onResult(false)
            }
        }
        if (!launched) onResult(false)
    }

    suspend fun loadReferenceCandidates(kind: OfficeReferenceKind, chatId: String): List<OfficeRefBody> = try {
        when (kind) {
            OfficeReferenceKind.DOCUMENT -> session.documentRepo.listRecentDocuments(20).getOrThrow().map { item ->
                OfficeRefBody(
                    refType = OfficeRefBody.REF_TYPE_DOCUMENT,
                    spaceId = item.spaceId,
                    targetId = item.documentId,
                    title = item.title.ifBlank { "未命名文档" },
                    subtitle = "文档",
                )
            }
            OfficeReferenceKind.GROUP_FILE -> session.groupFileRepo.list(chatId, null).getOrThrow()
                .filter { it.kind == GroupFileEntry.KIND_FILE }
                .map { entry ->
                    OfficeRefBody(
                        refType = OfficeRefBody.REF_TYPE_GROUP_FILE,
                        spaceId = chatId,
                        targetId = entry.entryId,
                        title = entry.name,
                        subtitle = listOfNotNull(
                            entry.attachment?.contentType?.substringAfterLast('/')?.uppercase(),
                            entry.attachment?.size?.toString(),
                        ).joinToString(" · ").ifBlank { "群文件" },
                    )
                }
        }
    } catch (failure: Throwable) {
        if (failure is CancellationException) throw failure
        emptyList()
    }

    /** 目标仍可读才交给平台导航；已删除或无权访问时保留聊天里的冻结预览。 */
    fun openReference(reference: OfficeRefBody, onOpen: () -> Unit, onDenied: (String) -> Unit) {
        val launched = launchAction {
            try {
                if (reference.isDocument) {
                    session.documentRepo.getDocument(reference.spaceId, reference.targetId).getOrThrow()
                } else {
                    session.groupFileRepo.getEntry(reference.spaceId, reference.targetId).getOrThrow()
                }
                onOpen()
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                onDenied("内容不可访问或已被删除")
            }
        }
        if (!launched) onDenied("会话已关闭")
    }
}
