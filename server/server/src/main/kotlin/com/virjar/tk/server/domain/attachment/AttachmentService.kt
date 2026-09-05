package com.virjar.tk.server.domain.attachment

import com.virjar.tk.protocol.body.AttachmentBody
import com.virjar.tk.protocol.body.AttachmentPolicy
import com.virjar.tk.protocol.body.MessageBodyPolicy
import com.virjar.tk.protocol.body.ReplyBody
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.model.Message

/**
 * 附件领域的唯一校验入口。
 *
 * SDK 负责结构和路径格式；这里以 FileStore 为权威，验证主文件与缩略图真实存在，
 * 并要求 name/contentType/size 与上传结果完全一致。调用者还必须已经拥有读取权：
 * 自己上传，或属于一个已经引用该附件的活跃会话。最终持久化的是 FileStore 返回的
 * 描述符，避免客户端声明字段进入消息历史或抢先引用他人未发送的上传。
 */
class AttachmentService(
    private val attachmentCatalog: AttachmentCatalog,
    private val attachmentAccess: AttachmentAccess,
) {
    /** 持久化从上传暂存到业务绑定对象的单调转换。 */
    fun markReferenced(message: Message) {
        val paths = AttachmentPolicy.attachments(message).map { it.path }
        if (paths.isNotEmpty()) attachmentCatalog.markBusinessBound(paths)
    }

    suspend fun resolve(message: Message, actorUid: String): Message {
        // 所有消息先做通用 body/type 校验；Markdown 的派生字段也在此重建。
        // 附件消息随后再做路径格式与 FileStore 存在性校验。
        val canonical = MessageBodyPolicy.canonicalize(AttachmentPolicy.canonicalize(message))
        return when (val body = canonical.body) {
            is AttachmentBody -> {
                val attachment = resolve(body.attachment, actorUid)
                val thumbnail = body.thumbnail?.let { resolve(it, actorUid) }
                canonical.copy(body = body.withAttachments(attachment, thumbnail))
            }
            is RichTextBody -> {
                val assets = resolve(body.assets, actorUid)
                canonical.copy(body = buildRichTextBody(body.markdown, assets))
            }
            is ReplyBody -> canonical.copy(body = body.copy(assets = resolve(body.assets, actorUid)))
            else -> canonical
        }
    }

    private suspend fun resolve(assets: List<EmbeddedAsset>, actorUid: String): List<EmbeddedAsset> =
        assets.map { asset ->
            asset.copy(
                attachment = resolve(asset.attachment, actorUid),
                thumbnail = asset.thumbnail?.let { resolve(it, actorUid) },
            )
        }

    private suspend fun resolve(declared: Attachment, actorUid: String): Attachment {
        val actual = attachmentAccess.readAuthorized(actorUid, declared.path) { canonicalPath ->
            attachmentCatalog.getAttachment(canonicalPath)
        } ?: throw IllegalArgumentException("附件不存在或无权使用: path=${declared.path}")
        require(actual == declared) {
            "附件元数据不匹配: path=${declared.path}"
        }
        return actual
    }
}
