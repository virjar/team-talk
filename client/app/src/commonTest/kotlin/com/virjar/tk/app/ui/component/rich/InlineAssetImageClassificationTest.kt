package com.virjar.tk.app.ui.component.rich

import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.EmbeddedAsset
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 内测第四期 T068：正文文本 + 行内 asset 图片引用的段落被整块降级为
 * "暂未建模的 Markdown 扩展块"错误卡。锁定分类行为，防止回归。
 */
class InlineAssetImageClassificationTest {

    private val assetId = "bdf05a1b-e63e-409d-b748-a4dca318b0f1"
    private val doc = """这是正文吗
可能是正文吧![微信图片\_20260928194315\_86\_163.jpg](teamtalk-asset://asset/$assetId)"""

    @Test
    fun `正文混行内图片不应进入源码模式`() {
        // 与 DocumentMarkdownBlockCodec.classify 的放行口径一致
        val cap = RichEditorMarkdownCapability.inspect(doc, allowCanonicalAssetImages = true)
        assertTrue(
            !cap.requiresSourceMode,
            "正文 + 行内 canonical 图片被判为不支持: ${cap.unsupportedFeatures}",
        )
    }

    @Test
    fun `正文混行内图片解析为富文本块`() {
        val blocks = DocumentMarkdownBlockCodec.parse(
            doc,
            listOf(asset(assetId)),
        )
        assertTrue(
            blocks.none { it is DocumentOpaqueRawBlock },
            "不应出现未建模错误卡, 实际: ${blocks.map { it.javaClass.simpleName }}",
        )
    }

    @Test
    fun `列表项内嵌图片不再整块降级为错误卡`() {
        // 内测第四期真实案例：无序列表第二项带行内 canonical 图片
        val list = """- 这是正文吗
- 可能是正文吧![微信图片\_20260928194315\_86\_163.jpg](teamtalk-asset://asset/$assetId)"""
        val blocks = DocumentMarkdownBlockCodec.parse(list, listOf(asset(assetId)))
        assertTrue(
            blocks.none { it is DocumentOpaqueRawBlock },
            "列表不应整体降级, 实际: ${blocks.map { it.javaClass.simpleName }}",
        )
    }

    @Test
    fun `外链图片仍降级为源码块`() {
        val external = "正文![外链](https://example.com/a.png)"
        assertTrue(
            DocumentMarkdownBlockCodec.parse(external).any { it is DocumentOpaqueRawBlock },
            "外链图片必须留在源码块",
        )
    }

    private fun asset(assetId: String) = EmbeddedAsset(
        assetId = assetId,
        attachment = Attachment(path = "wx/x.png", name = "微信图片.jpg", contentType = "image/jpeg", size = 1),
    )
}
