package com.virjar.tk.ui.component.rich

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Markdown 解析器（IM 消息子集）单测。
 * 核心保证：普通文本零变形（视觉不变的根基）+ 常用语法结构正确 + 未闭合语法保序不丢字。
 */
class MarkdownParseTest {

    @Test
    fun `纯文本零变形`() {
        val blocks = MdParser.parse("你好，这是一条普通消息 123")
        assertEquals(1, blocks.size)
        val p = blocks[0] as MdBlock.Paragraph
        assertEquals(listOf(MdSpan.Text("你好，这是一条普通消息 123")), p.spans)
    }

    @Test
    fun `粗体斜体删除线`() {
        val p = MdParser.parse("**粗体** *斜体* ~~删除~~")[0] as MdBlock.Paragraph
        assertEquals(
            listOf(
                MdSpan.Styled("粗体", bold = true),
                MdSpan.Text(" "),
                MdSpan.Styled("斜体", italic = true),
                MdSpan.Text(" "),
                MdSpan.Styled("删除", strike = true),
            ),
            p.spans,
        )
    }

    @Test
    fun `行内代码`() {
        val p = MdParser.parse("运行 `gradlew test` 即可")[0] as MdBlock.Paragraph
        assertTrue(p.spans.any { it is MdSpan.Styled && it.code && it.text == "gradlew test" })
    }

    @Test
    fun `链接与mention`() {
        val p = MdParser.parse("见 [文档](https://im.virjar.com) 和 @[设计测试员](mention://23ezOP9D)")[0] as MdBlock.Paragraph
        assertEquals(MdSpan.Link("文档", "https://im.virjar.com"), p.spans.filterIsInstance<MdSpan.Link>().single())
        assertEquals(
            MdSpan.Mention("23ezOP9D", "设计测试员"),
            p.spans.filterIsInstance<MdSpan.Mention>().single(),
        )
    }

    @Test
    fun `mention uid 含特殊字符`() {
        val p = MdParser.parse("@[张三](mention://dQ3KUFf7)")[0] as MdBlock.Paragraph
        assertEquals(MdSpan.Mention("dQ3KUFf7", "张三"), p.spans.filterIsInstance<MdSpan.Mention>().single())
    }

    @Test
    fun `CommonMark 标点转义在文本链接和 mention 中解码`() {
        val p = MdParser.parse(
            """字面 \* \[文本\] \\ \q [文档 \[v2\]](https://im.virjar.com/a\(b\)) @[研发\]组](mention://uid-1)"""
        )[0] as MdBlock.Paragraph

        assertEquals(
            MdSpan.Link("文档 [v2]", "https://im.virjar.com/a(b)"),
            p.spans.filterIsInstance<MdSpan.Link>().single(),
        )
        assertEquals(
            MdSpan.Mention("uid-1", "研发]组"),
            p.spans.filterIsInstance<MdSpan.Mention>().single(),
        )
        val ordinaryText = p.spans.filterIsInstance<MdSpan.Text>().joinToString("") { it.text }
        assertTrue("字面 * [文本] \\ \\q" in ordinaryText)
    }

    @Test
    fun `代码块带语言`() {
        val blocks = MdParser.parse("```kotlin\nval a = 1\n```")
        val fence = blocks.filterIsInstance<MdBlock.CodeFence>().single()
        assertEquals("kotlin", fence.lang)
        assertEquals("val a = 1", fence.code)
    }

    @Test
    fun `列表与引用`() {
        val blocks = MdParser.parse("- 项目一\n- 项目二\n\n> 引用内容")
        val items = blocks.filterIsInstance<MdBlock.ListItem>()
        assertEquals(2, items.size)
        assertTrue(items.all { it.kind == MdListKind.Unordered && it.depth == 0 && it.markerText == "•" })
        val quote = blocks.filterIsInstance<MdBlock.Quote>().single()
        assertEquals(
            listOf(MdSpan.Text("引用内容")),
            (quote.blocks[0] as MdBlock.Paragraph).spans,
        )
    }

    @Test
    fun `有序列表保留源码序号`() {
        val items = MdParser.parse("7. 七\n8. 八\n11. 十一")
            .filterIsInstance<MdBlock.ListItem>()

        assertEquals(listOf(MdListKind.Ordered, MdListKind.Ordered, MdListKind.Ordered), items.map { it.kind })
        assertEquals(listOf(7, 8, 11), items.map { it.number })
        assertEquals(listOf("7.", "8.", "11."), items.map { it.markerText })
        assertEquals(listOf(0, 0, 0), items.map { it.depth })
    }

    @Test
    fun `混合嵌套列表保留类型层级和内容边界`() {
        val items = MdParser.parse(
            """
            3. 父项
                - 子项
                    7. 孙项
            4. 末项
            """.trimIndent()
        ).filterIsInstance<MdBlock.ListItem>()

        assertEquals(
            listOf(
                Triple(MdListKind.Ordered, 0, 3),
                Triple(MdListKind.Unordered, 1, null),
                Triple(MdListKind.Ordered, 2, 7),
                Triple(MdListKind.Ordered, 0, 4),
            ),
            items.map { Triple(it.kind, it.depth, it.number) },
        )
        assertEquals(listOf("父项", "子项", "孙项", "末项"), items.map { it.plainText() })
    }

    @Test
    fun `标题分级`() {
        val blocks = MdParser.parse("# 一级\n## 二级\n### 三级")
        assertEquals(1, (blocks[0] as MdBlock.Heading).level)
        assertEquals(2, (blocks[1] as MdBlock.Heading).level)
        assertEquals(3, (blocks[2] as MdBlock.Heading).level)
        assertEquals(listOf(MdSpan.Text("一级")), (blocks[0] as MdBlock.Heading).spans)
        assertEquals(listOf(MdSpan.Text("二级")), (blocks[1] as MdBlock.Heading).spans)
        assertEquals(listOf(MdSpan.Text("三级")), (blocks[2] as MdBlock.Heading).spans)
    }

    @Test
    fun `嵌套样式`() {
        val p = MdParser.parse("**粗体包含 `代码`**")[0] as MdBlock.Paragraph
        val styled = p.spans.filterIsInstance<MdSpan.Styled>()
        // 嵌套代码段保留粗体标记
        assertTrue(styled.any { it.code && it.bold && it.text == "代码" })
        // 外层粗体文本完整（TEXT 与空格是相邻 span，渲染等价拼接断言）
        val boldText = styled.filter { it.bold && !it.code }.joinToString("") { it.text }
        assertEquals("粗体包含 ", boldText)
    }

    @Test
    fun `未闭合语法保序不丢字`() {
        val raw = "价格 **5 星 和 _下划线"
        val p = MdParser.parse(raw)[0] as MdBlock.Paragraph
        val text = p.spans.joinToString("") {
            when (it) {
                is MdSpan.Text -> it.text
                is MdSpan.Styled -> it.text
                else -> "?"
            }
        }
        // 语法未闭合时必须原样呈现全部字符（丢字是渲染事故）
        assertEquals(raw, text)
    }

    @Test
    fun `普通下划线不误伤`() {
        // IM 常见：文件名/变量名含下划线，不应被解析为斜体
        val p = MdParser.parse("文件 user_avatar.png 已上传")[0] as MdBlock.Paragraph
        assertEquals(listOf(MdSpan.Text("文件 user_avatar.png 已上传")), p.spans)
    }

    @Test
    fun `多行消息分块`() {
        val blocks = MdParser.parse("第一段\n\n第二段")
        assertEquals(2, blocks.filterIsInstance<MdBlock.Paragraph>().size)
    }

    @Test
    fun `mention 不重复渲染 @ 符号`() {
        // `@[名](mention://uid)` 的 @ 是链接前置文本叶子，Mention 再补 @ 会得到 "@@名"（曾现 bug）
        val p = MdParser.parse("你好 @[张三](mention://uid1) 看看")[0] as MdBlock.Paragraph
        val mentionIdx = p.spans.indexOfFirst { it is MdSpan.Mention }
        assertTrue(mentionIdx > 0)
        val before = p.spans[mentionIdx - 1]
        val beforeText = when (before) { is MdSpan.Text -> before.text; is MdSpan.Styled -> before.text; else -> "" }
        assertTrue(beforeText != "@", "Mention 前不应残留独立 @ 节点: $before")
        assertEquals(MdSpan.Mention("uid1", "张三"), p.spans[mentionIdx])
    }

    @Test
    fun `全角标点后的 mention 不双 @`() {
        // 真实消息场景：全角标点后 parser 把 @ 并进前段文本（"完成！@"），非独立叶子
        val p = MdParser.parse("**富文本二期**完成！@[TestUser uidesign2](mention://dQ3KUFf7) 请验收")[0] as MdBlock.Paragraph
        val mentionIdx = p.spans.indexOfFirst { it is MdSpan.Mention }
        val joined = p.spans.joinToString("") {
            when (it) { is MdSpan.Text -> it.text; is MdSpan.Styled -> it.text; else -> "@NAME" }
        }
        // 拼接结果只允许出现一个 @（mention 位以占位符计）
        val rendered = joined.replace("@NAME", "@TestUser uidesign2")
        assertEquals(1, rendered.count { it == '@' }, "渲染拼接: $rendered")
    }

    @Test
    fun `列表项不泄漏列表标记`() {
        // `- ` 是结构不是内容，不得出现在 spans 文本里（曾泄漏为 "• - 第一项"）
        val blocks = MdParser.parse("- 第一项\n- 第二项")
        val items = blocks.filterIsInstance<MdBlock.ListItem>()
        assertEquals(2, items.size)
        items.forEach { item ->
            val text = item.spans.joinToString("") {
                when (it) { is MdSpan.Text -> it.text; is MdSpan.Styled -> it.text; else -> "" }
            }
            assertTrue(!text.contains("- "), "列表标记泄漏: $text")
        }
    }

    private fun MdBlock.ListItem.plainText(): String = spans.joinToString("") {
        when (it) {
            is MdSpan.Text -> it.text
            is MdSpan.Styled -> it.text
            is MdSpan.Link -> it.label
            is MdSpan.Mention -> "@${it.name}"
        }
    }
}
