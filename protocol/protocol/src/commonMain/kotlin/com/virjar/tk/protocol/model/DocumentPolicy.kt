package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.body.MarkdownAssetPolicy

/** 客户端与服务端指纹边界共享的 canonical 文档命令规则。 */
object DocumentPolicy {
    const val MAX_SPACE_NAME_LENGTH = 120
    const val MAX_DESCRIPTION_LENGTH = 500
    const val MAX_NODE_NAME_LENGTH = 180
    const val MAX_MARKDOWN_LENGTH = 1_000_000
    const val MAX_EXCERPT_LENGTH = 160
    const val EMPTY_DOCUMENT_EXCERPT = "空白文档"

    fun normalizeSpaceName(value: String): String =
        normalizeName(value, MAX_SPACE_NAME_LENGTH, "空间名称")

    fun normalizeNodeName(value: String): String =
        normalizeName(value, MAX_NODE_NAME_LENGTH, "名称")

    fun normalizeDescription(value: String?): String? = value
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.also {
            require(it.length <= MAX_DESCRIPTION_LENGTH) {
                "空间说明不能超过 $MAX_DESCRIPTION_LENGTH 个字符"
            }
            require('\u0000' !in it) { "空间说明包含非法字符" }
        }

    /** 结构化渲染预算仍属服务端关注点；这里只是共享的线格式信封。 */
    fun validateMarkdownEnvelope(value: String): String {
        require(value.length <= MAX_MARKDOWN_LENGTH) {
            "文档正文不能超过 $MAX_MARKDOWN_LENGTH 个字符"
        }
        require('\u0000' !in value) { "文档正文包含非法字符" }
        return value
    }

    /** 构建持久层投影与客户端共用的 canonical 紧凑预览。 */
    fun markdownExcerpt(
        markdown: String,
        assets: List<EmbeddedAsset> = emptyList(),
    ): String = MarkdownAssetPolicy.replaceReferencesForPlainText(markdown, assets).lineSequence()
        .map(String::trim)
        .firstOrNull(String::isNotEmpty)
        ?.trimStart('#', '>', '-', '*', '+', '`', ' ')
        ?.take(MAX_EXCERPT_LENGTH)
        ?.ifBlank { EMPTY_DOCUMENT_EXCERPT }
        ?: EMPTY_DOCUMENT_EXCERPT

    private fun normalizeName(value: String, limit: Int, label: String): String {
        val normalized = value.trim()
        require(normalized.isNotEmpty()) { "$label 不能为空" }
        require(normalized.length <= limit) { "$label 不能超过 $limit 个字符" }
        require(normalized.none { it.code < 32 }) { "$label 包含非法字符" }
        return normalized
    }
}
