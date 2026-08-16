package com.virjar.tk.body

import com.virjar.tk.model.MessageBody
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer

/**
 * 富文本消息体（wire 协议，doc/10-rich-messaging §3）。
 *
 * markdown 为唯一事实源；mentions 是侧信道（供 UI 直取/通知语义/免二次解析）；
 * plainText 是剥离语法的纯文本（服务端搜索索引、会话预览、旧端 fallback 均用它，
 * 语法符号与 mention 链接语法不进搜索）。
 *
 * @ 的内联语法：`@[显示名](mention://uid)` —— 标准 markdown 链接语法，任何工具可解析。
 */
data class RichTextBody(
    val markdown: String,
    val mentions: List<Mention> = emptyList(),
    val plainText: String,
) : MessageBody {

    data class Mention(
        val uid: String,
        val displayName: String,
        /** markdown 中 mention 链接语法的起始偏移（字符） */
        val offset: Int,
        /** mention 完整链接语法 `@[名](mention://uid)` 的长度（字符） */
        val length: Int,
    ) : IProto {
        override fun writeTo(buf: PacketBuffer) {
            buf.writeString(uid)
            buf.writeString(displayName)
            buf.writeVarInt(offset)
            buf.writeVarInt(length)
        }

        companion object : IProtoReader<Mention> {
            override fun readFrom(buf: PacketBuffer): Mention = Mention(
                uid = buf.readString()!!,
                displayName = buf.readString()!!,
                offset = buf.readVarInt(),
                length = buf.readVarInt(),
            )
        }
    }

    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(markdown)
        buf.writeVarInt(mentions.size)
        mentions.forEach { it.writeTo(buf) }
        buf.writeString(plainText)
    }

    companion object : IProtoReader<RichTextBody> {
        override fun readFrom(buf: PacketBuffer): RichTextBody {
            val markdown = buf.readString()!!
            val mentions = (1..buf.readVarInt()).map { Mention.readFrom(buf) }
            val plainText = buf.readString()!!
            return RichTextBody(markdown, mentions, plainText)
        }
    }
}

/** mention 内联语法：@[显示名](mention://uid) */
private val MENTION_SYNTAX = Regex("""@\[([^\]]*)\]\(mention://([^)\s]+)\)""")
private val LINK_SYNTAX = Regex("""(?<!!)\[([^\]]*)\]\(([^)\s]+)\)""")
private val IMAGE_SYNTAX = Regex("""!\[([^\]]*)\]\(([^)\s]+)\)""")

/**
 * 由 markdown 源文本构造 RichTextBody：提取 mentions 侧信道 + 剥离语法生成 plainText。
 * 发送端唯一入口（客户端与 bot 共用），保证三字段一致性。
 */
fun buildRichTextBody(markdown: String): RichTextBody {
    val mentions = MENTION_SYNTAX.findAll(markdown).mapIndexed { _, m ->
        val name = m.groupValues[1].ifBlank { m.groupValues[2] }
        RichTextBody.Mention(
            uid = m.groupValues[2],
            displayName = name,
            offset = m.range.first,
            length = m.value.length, // 完整链接语法区间（替换/定位用）
        )
    }.toList()

    var text = markdown
    // 剥离顺序：先图片（含 ! 前缀，先于普通链接）、再 mention/链接、后行内标记
    text = text.replace(IMAGE_SYNTAX) { "[图片]" }
    text = text.replace(MENTION_SYNTAX) { "@${it.groupValues[1]}" }
    text = text.replace(LINK_SYNTAX) { it.groupValues[1] }
    // 行内标记：粗体/斜体/删除线/行内代码的包裹符号
    text = text.replace(Regex("""(\*\*|__|~~|`)(.+?)\1""")) { it.groupValues[2] }
    // 块级：代码块栅栏/标题井号/引用符/列表标记（行首）
    text = text.replace(Regex("""^#{1,6}\s+""", RegexOption.MULTILINE), "")
    text = text.replace(Regex("""^>\s?""", RegexOption.MULTILINE), "")
    text = text.replace(Regex("""^([-*+]|\d+\.)\s+""", RegexOption.MULTILINE), "")
    text = text.replace(Regex("""^```.*$""", RegexOption.MULTILINE), "")

    return RichTextBody(markdown = markdown, mentions = mentions, plainText = text)
}

/**
 * 是否按富文本消息发送：文本包含 markdown 语法特征（客户端与 bot 共用的发送判定）。
 * 无特征时仍发 TEXT（老端直接可读，避免无谓的类型升级）。
 */
fun looksRichMarkdown(text: String): Boolean {
    if ("mention://" in text) return true
    if ("**" in text || "~~" in text || '`' in text) return true
    if (LINK_SYNTAX.containsMatchIn(text)) return true
    if (text.startsWith("#") || text.startsWith("> ")) return true
    return false
}
