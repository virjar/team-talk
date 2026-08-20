package com.virjar.tk.body

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer

/**
 * 富文本消息体（wire 协议，doc/05-clients/rich-content.md）。
 *
 * markdown 为唯一事实源；mentions 是侧信道（供 UI 直取/通知语义/免二次解析）；
 * plainText 是剥离语法的纯文本（服务端搜索索引与会话预览均用它，
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
                uid = buf.readRequiredString(MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_IDENTIFIER_LENGTH)),
                displayName = buf.readRequiredString(
                    MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_DISPLAY_NAME_LENGTH),
                ),
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
        /** 侧信道仅用于通知和定位；正文仍是唯一事实源，单条消息无需无限 mentions。 */
        const val MAX_MENTIONS = 1_000

        override fun readFrom(buf: PacketBuffer): RichTextBody {
            val markdown = buf.readRequiredString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_MARKDOWN_LENGTH),
            )
            // Mention 最短为两个空 String（各 2B）和两个 VarInt（各 1B）。先校验
            // count 再构造 List，避免极小帧用 Int.MAX_VALUE 触发巨量预分配。
            val mentionCount = buf.readCollectionSize(MAX_MENTIONS, 6, "rich-text mentions")
            val mentions = List(mentionCount) { Mention.readFrom(buf) }
            val plainText = buf.readRequiredString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_MARKDOWN_LENGTH),
            )
            return RichTextBody(markdown, mentions, plainText)
        }
    }
}

/** mention 内联语法：@[显示名](mention://uid) */
private const val COMMONMARK_ESCAPABLE_ASCII_PUNCTUATION = "!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

/**
 * CommonMark 的反斜杠转义只作用于 ASCII 标点；例如 `\\*` 解码为 `*`，
 * `\\a` 必须继续保持两个字符，不能把用户正文中的反斜杠静默吞掉。
 */
fun decodeCommonMarkPunctuationEscapes(text: String): String {
    if ('\\' !in text) return text
    return buildString(text.length) {
        var index = 0
        while (index < text.length) {
            val current = text[index]
            val escaped = text.getOrNull(index + 1)
            if (current == '\\' && escaped != null && escaped in COMMONMARK_ESCAPABLE_ASCII_PUNCTUATION) {
                append(escaped)
                index += 2
            } else {
                append(current)
                index++
            }
        }
    }
}

/** 构造 TeamTalk 权威 mention 语法，并保护显示名/uid 中会截断 Markdown 链接的标点。 */
fun buildMentionMarkdown(displayName: String, uid: String): String {
    require(uid.isNotBlank() && uid.none(Char::isWhitespace)) { "mention uid 不能为空或包含空白" }
    val label = displayName.ifBlank { uid }.escapeCommonMarkLinkLabel()
    val destination = uid.escapeCommonMarkLinkDestination()
    return "@[$label](mention://$destination)"
}

private fun String.escapeCommonMarkLinkLabel(): String = buildString(length) {
    this@escapeCommonMarkLinkLabel.forEach { char ->
        if (char == '\\' || char == '[' || char == ']') append('\\')
        append(char)
    }
}

private fun String.escapeCommonMarkLinkDestination(): String = buildString(length) {
    this@escapeCommonMarkLinkDestination.forEach { char ->
        if (char == '\\' || char == '(' || char == ')' || char == '<' || char == '>') append('\\')
        append(char)
    }
}

// `\\.` 作为一个原子跨过转义标点，避免 `\\]` / `\\)` 被误判为 label/destination 边界。
private val MENTION_SYNTAX = Regex("""@\[((?:\\.|[^\]\\])*)\]\(mention://((?:\\.|[^)\\\s])+)\)""")
private val LINK_SYNTAX = Regex("""(?<![!\\])\[((?:\\.|[^\]\\])*)\]\(((?:\\.|[^)\\\s])+)\)""")
private val IMAGE_SYNTAX = Regex("""!\[((?:\\.|[^\]\\])*)\]\(((?:\\.|[^)\\\s])+)\)""")

private data class ProtectedMarkdownEscapes(
    val text: String,
    val placeholderBase: Int,
) {
    fun restore(value: String): String = buildString(value.length) {
        value.forEach { char ->
            val punctuationIndex = char.code - placeholderBase
            append(
                if (punctuationIndex in COMMONMARK_ESCAPABLE_ASCII_PUNCTUATION.indices) {
                    COMMONMARK_ESCAPABLE_ASCII_PUNCTUATION[punctuationIndex]
                } else {
                    char
                }
            )
        }
    }
}

/** 先把字面标点替换为私用区占位符，防止后续剥离 Markdown 标记时把它当成语法。 */
private fun protectCommonMarkPunctuationEscapes(text: String): ProtectedMarkdownEscapes {
    val width = COMMONMARK_ESCAPABLE_ASCII_PUNCTUATION.length
    val base = generateSequence(0xE000) { previous ->
        (previous + width).takeIf { it + width <= 0xF8FF }
    }.first { candidate ->
        (candidate until candidate + width).none { code -> code.toChar() in text }
    }
    val protected = buildString(text.length) {
        var index = 0
        while (index < text.length) {
            val current = text[index]
            val escaped = text.getOrNull(index + 1)
            val punctuationIndex = escaped?.let(COMMONMARK_ESCAPABLE_ASCII_PUNCTUATION::indexOf) ?: -1
            if (current == '\\' && punctuationIndex >= 0) {
                append((base + punctuationIndex).toChar())
                index += 2
            } else {
                append(current)
                index++
            }
        }
    }
    return ProtectedMarkdownEscapes(protected, base)
}

/**
 * 由 markdown 源文本构造 RichTextBody：提取 mentions 侧信道 + 剥离语法生成 plainText。
 * 发送端唯一入口（客户端与 bot 共用），保证三字段一致性。
 */
fun buildRichTextBody(markdown: String): RichTextBody {
    val mentions = MENTION_SYNTAX.findAll(markdown).mapIndexed { _, m ->
        val uid = decodeCommonMarkPunctuationEscapes(m.groupValues[2])
        val name = decodeCommonMarkPunctuationEscapes(m.groupValues[1]).ifBlank { uid }
        RichTextBody.Mention(
            uid = uid,
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
    val protectedEscapes = protectCommonMarkPunctuationEscapes(text)
    text = protectedEscapes.text
    // 行内标记：先处理双字符标记，再处理单字符斜体，避免把 ** 拆成两组 *。
    text = text.replace(Regex("""(\*\*|__|~~|`)(.+?)\1""")) { it.groupValues[2] }
    text = text.replace(Regex("""(?<!\*)\*([^*\n]+)\*(?!\*)""")) { it.groupValues[1] }
    text = text.replace(Regex("""(?<!_)_([^_\n]+)_(?!_)""")) { it.groupValues[1] }
    // 块级：代码块栅栏/标题井号/引用符/列表标记（行首）
    text = text.replace(Regex("""^#{1,6}\s+""", RegexOption.MULTILINE), "")
    text = text.replace(Regex("""^>\s?""", RegexOption.MULTILINE), "")
    text = text.replace(Regex("""^([-*+]|\d+\.)\s+""", RegexOption.MULTILINE), "")
    text = text.replace(Regex("""^```.*$""", RegexOption.MULTILINE), "")

    return RichTextBody(markdown = markdown, mentions = mentions, plainText = protectedEscapes.restore(text))
}
