package com.virjar.tk.server.domain.message

import com.virjar.tk.protocol.body.OfficeRefBody
import com.virjar.tk.server.domain.document.DocumentAccessDeniedException
import com.virjar.tk.server.domain.document.DocumentNotFoundException
import com.virjar.tk.server.domain.document.DocumentService
import com.virjar.tk.server.domain.groupfile.GroupFileService

/**
 * 首次发送时读取文档或群文件，用权威预览覆盖客户端声明；对象不可读则在消息 ACK 前失败。
 * 转发不经过本类，冻结快照原样复制；打开时仍由各域读入口校验当前状态。
 */
class OfficeRefResolver(
    private val documents: DocumentService,
    private val groupFiles: GroupFileService,
) {

    suspend fun resolve(uid: String, body: OfficeRefBody): OfficeRefBody = when (body.refType) {
        OfficeRefBody.REF_TYPE_DOCUMENT -> {
            val document = try {
                documents.getDocument(uid, body.spaceId, body.targetId)
            } catch (denied: DocumentAccessDeniedException) {
                throw IllegalArgumentException("无权引用该文档")
            } catch (missing: DocumentNotFoundException) {
                throw IllegalArgumentException("引用的文档不存在或已删除")
            }
            body.copy(
                title = document.title.ifBlank { "未命名文档" },
                subtitle = "文档",
            )
        }
        OfficeRefBody.REF_TYPE_GROUP_FILE -> {
            val entry = try {
                groupFiles.getEntry(uid, body.spaceId, body.targetId)
            } catch (denied: IllegalArgumentException) {
                // 群文件域以业务异常表达"不是群成员/条目不存在"，统一收敛为引用失败。
                throw IllegalArgumentException("引用的群文件不存在或无权访问")
            }
            body.copy(
                title = entry.name,
                subtitle = listOfNotNull(
                    entry.attachment?.contentType?.substringAfterLast('/')?.uppercase(),
                    entry.attachment?.size?.toString(),
                ).joinToString(" · ").ifBlank { "群文件" },
            )
        }
        else -> throw IllegalArgumentException("office ref 类型非法")
    }
}
