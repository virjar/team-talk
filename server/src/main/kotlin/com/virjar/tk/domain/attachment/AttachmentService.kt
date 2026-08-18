package com.virjar.tk.domain.attachment

import com.virjar.tk.body.AttachmentBody
import com.virjar.tk.body.AttachmentPolicy
import com.virjar.tk.body.MessageBodyPolicy
import com.virjar.tk.model.Attachment
import com.virjar.tk.model.Message

/**
 * 附件领域的唯一校验入口。
 *
 * SDK 负责结构和路径格式；这里以 FileStore 为权威，验证主文件与缩略图真实存在，
 * 并要求 name/contentType/size 与上传结果完全一致。最终持久化的是 FileStore 返回的
 * 描述符，避免客户端声明字段进入消息历史。
 */
class AttachmentService(
    private val attachmentCatalog: AttachmentCatalog,
) {
    fun resolve(message: Message): Message {
        // 所有消息先做通用 body/type 校验；Markdown 的派生字段也在此重建。
        // 附件消息随后再做路径格式与 FileStore 存在性校验。
        val canonical = AttachmentPolicy.canonicalize(MessageBodyPolicy.canonicalize(message))
        val body = canonical.body as? AttachmentBody ?: return canonical
        val attachment = resolve(body.attachment)
        val thumbnail = body.thumbnail?.let(::resolve)
        return canonical.copy(body = body.withAttachments(attachment, thumbnail))
    }

    private fun resolve(declared: Attachment): Attachment {
        val actual = attachmentCatalog.getAttachment(declared.path)
            ?: throw IllegalArgumentException("附件不存在或已失效: ${declared.path}")
        require(actual == declared) {
            "附件元数据不匹配: path=${declared.path}"
        }
        return actual
    }
}
