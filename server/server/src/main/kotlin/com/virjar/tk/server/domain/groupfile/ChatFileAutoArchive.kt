package com.virjar.tk.server.domain.groupfile

import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.GroupFileEntry
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * 群聊文件自动归档（原始需求：往聊天里丢的文件自动进入群文件空间）。
 *
 * 消息提交路径在 ACK 前同步调用 [capture]：群文件条目与消息引用同一物理附件，
 * 两侧引用独立计数——从群空间删除条目不影响聊天下载，撤回消息也不影响空间副本。
 * 归档是 best-effort：任何失败（配额满、发送者已离群、名称冲突重试用尽）只记日志，
 * 绝不让消息发送失败；commandId/entryId 由 chatId+seq+附件序号派生，重试幂等。
 */
class ChatFileAutoArchive(
    private val groupFiles: GroupFileService,
) {
    private val logger = LoggerFactory.getLogger(ChatFileAutoArchive::class.java)

    /**
     * 把一条已提交群消息中的非图片附件归档到固定文件夹。
     * 非群聊在入口或成员校验处快速失败并被吞掉，调用方无需预先判断聊天类型。
     */
    suspend fun capture(chatId: String, senderUid: String, serverSeq: Long, attachments: List<Attachment>) {
        val candidates = attachments.filter { it.contentType.isBlank() || !it.contentType.startsWith("image/", ignoreCase = true) }
        if (candidates.isEmpty()) return
        val folder = ensureChatFilesFolder(chatId, senderUid) ?: return
        candidates.forEachIndexed { index, attachment ->
            try {
                captureSingle(chatId, senderUid, serverSeq, index, attachment, folder)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.warn(
                    "群文件自动归档跳过一个附件：chatId={}, seq={}, attachment={}",
                    chatId, serverSeq, attachment.name, failure,
                )
            }
        }
    }

    private suspend fun captureSingle(
        chatId: String,
        senderUid: String,
        serverSeq: Long,
        index: Int,
        attachment: Attachment,
        folder: GroupFileEntry,
    ) {
        val entryId = derivedUuid("auto-file", chatId, serverSeq, index)
        val commandId = derivedUuid("auto-cmd", chatId, serverSeq, index)
        // 同名冲突用递增序号重试；文件夹清单在每次尝试前重新读取，容忍并发归档。
        val stem = nameStem(attachment.name)
        val extension = extensionOf(attachment.name)
        var attempt = 0
        while (true) {
            val candidateName = if (attempt == 0) {
                attachment.name
            } else {
                "$stem ($attempt)" + extension
            }
            val taken = groupFiles.list(senderUid, chatId, folder.entryId)
                .any { it.name == candidateName }
            if (!taken) {
                try {
                    groupFiles.createFile(
                        actorUid = senderUid,
                        entryId = entryId.toString(),
                        commandId = commandId.toString(),
                        chatId = chatId,
                        parentId = folder.entryId,
                        name = candidateName,
                        declared = attachment,
                    )
                    return
                } catch (conflict: SiblingNameConflictException) {
                    // 只有同名冲突才换名重试；其余（配额、成员资格等）按跳过处理由上层记录。
                }
            }
            attempt += 1
            check(attempt <= MAX_NAME_DEDUP_ATTEMPTS) { "群文件自动归档重命名重试用尽: ${attachment.name}" }
        }
    }

    /** 惰性创建固定归档文件夹；已存在（幂等重试或用户自建同名目录）时复用。 */
    private suspend fun ensureChatFilesFolder(chatId: String, senderUid: String): GroupFileEntry? {
        return try {
            groupFiles.createFolder(
                actorUid = senderUid,
                entryId = derivedUuid("auto-folder", chatId, 0L).toString(),
                commandId = derivedUuid("auto-folder-cmd", chatId, 0L).toString(),
                chatId = chatId,
                parentId = null,
                name = CHAT_FILES_FOLDER_NAME,
            )
        } catch (failure: Exception) {
            // 幂等重试命中回执时 createFolder 直接返回既有目录；名称冲突说明用户自建了
            // 同名目录——复用它，保持“聊天文件都在同一处”的产品语义。
            groupFiles.list(senderUid, chatId, null)
                .firstOrNull { it.kind == GroupFileEntry.KIND_FOLDER && it.name == CHAT_FILES_FOLDER_NAME }
                ?: throw failure
        }
    }

    private fun nameStem(name: String): String {
        val shortened = shortened(name)
        val dot = shortened.lastIndexOf('.')
        return if (dot > 0) shortened.substring(0, dot) else shortened
    }

    private fun extensionOf(name: String): String {
        val dot = shortened(name).lastIndexOf('.')
        return if (dot > 0) shortened(name).substring(dot) else ""
    }

    /** 条目名称上限 180 字符：保扩展名截断词干，避免归档因超长附件名整体失败。 */
    private fun shortened(name: String): String = if (name.length <= MAX_ENTRY_NAME_LENGTH) name else name.take(MAX_ENTRY_NAME_LENGTH)

    private fun derivedUuid(scope: String, chatId: String, serverSeq: Long, index: Int = 0): UUID =
        UUID.nameUUIDFromBytes("$scope:$chatId:$serverSeq:$index".toByteArray(Charsets.UTF_8))

    companion object {
        /** 固定归档文件夹名；管理员从这里把重要文件移动到有组织的目录。 */
        const val CHAT_FILES_FOLDER_NAME = "聊天文件"
        private const val MAX_ENTRY_NAME_LENGTH = 180
        private const val MAX_NAME_DEDUP_ATTEMPTS = 50
    }
}
