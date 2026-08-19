package com.virjar.tk.ui.component.rich

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DocumentMarkdownBlockCodecTest {

    @Test
    fun `空文档和纯空白文档仍提供可编辑富文本块`() {
        listOf("", "  \n\r\n").forEach { markdown ->
            val blocks = DocumentMarkdownBlockCodec.parse(markdown)

            assertEquals(1, blocks.size)
            assertIs<DocumentRichRun>(blocks.single())
            assertFalse(blocks.single().dirty)
            assertEquals(markdown, (blocks.single() as DocumentRichRun).markdown)
            assertEquals(markdown, DocumentMarkdownBlockCodec.encode(blocks))
        }
    }

    @Test
    fun `混合文档按块投影且未编辑时逐字节往返`() {
        val markdown = """
            开场 **正文**

            > 重要引用
            > 第二行

            ```kotlin
            val answer = 42
            ```

            | 姓名 | 部门 |
            | :--- | ---: |
            | 张三 | 研发 |

            <section data-kind="raw">保留</section>
        """.trimIndent() + "\n"

        val blocks = DocumentMarkdownBlockCodec.parse(markdown)

        assertEquals(
            listOf(
                DocumentRichRun::class,
                DocumentQuoteBlock::class,
                DocumentCodeFenceBlock::class,
                DocumentGfmTableBlock::class,
                DocumentOpaqueRawBlock::class,
            ),
            blocks.map { it::class },
        )
        assertEquals(markdown, blocks.joinToString("") { it.originalMarkdown })
        assertEquals(markdown, DocumentMarkdownBlockCodec.encode(blocks))
        assertEquals(blocks.map { it.key }, DocumentMarkdownBlockCodec.parse(markdown).map { it.key })
    }

    @Test
    fun `编辑普通富文本块不会规范化相邻高级块`() {
        val markdown = """
            旧正文

            >   保留非常规空格

            ```` kotlin
            val raw = "```"
            ````

            | A\|B | C |
            | :---- | ----: |
            | 1 | 2 |
        """.trimIndent()
        val parsed = DocumentMarkdownBlockCodec.parse(markdown)
        val quoteRaw = parsed.filterIsInstance<DocumentQuoteBlock>().single().originalMarkdown
        val codeRaw = parsed.filterIsInstance<DocumentCodeFenceBlock>().single().originalMarkdown
        val tableRaw = parsed.filterIsInstance<DocumentGfmTableBlock>().single().originalMarkdown
        val edited = parsed.map { block ->
            if (block is DocumentRichRun) block.copy(markdown = "新正文", dirty = true) else block
        }

        val encoded = DocumentMarkdownBlockCodec.encode(edited)

        assertTrue(encoded.startsWith("新正文"))
        assertTrue(quoteRaw in encoded)
        assertTrue(codeRaw in encoded)
        assertTrue(tableRaw in encoded)
    }

    @Test
    fun `引用块暴露去掉外层标记的 Markdown 并可独立编码`() {
        val markdown = "> **结论**\r\n>\r\n> - 条目\r\n> > 嵌套"
        val quote = assertIs<DocumentQuoteBlock>(DocumentMarkdownBlockCodec.parse(markdown).single())

        assertEquals("**结论**\r\n\r\n- 条目\r\n> 嵌套", quote.innerMarkdown)

        val encoded = DocumentMarkdownBlockCodec.encode(
            listOf(quote.copy(innerMarkdown = "新结论\r\n\r\n1. 第一项", dirty = true))
        )
        assertEquals("> 新结论\r\n>\r\n> 1. 第一项", encoded)
    }

    @Test
    fun `代码围栏保留语言正文和围栏偏好且避免正文提前闭合`() {
        val markdown = "```kotlin\nval answer = 42\n```"
        val code = assertIs<DocumentCodeFenceBlock>(DocumentMarkdownBlockCodec.parse(markdown).single())

        assertEquals("kotlin", code.language)
        assertEquals("val answer = 42", code.code)
        assertEquals('`', code.fenceChar)
        assertEquals(3, code.fenceLength)

        val encoded = DocumentMarkdownBlockCodec.encode(
            listOf(code.copy(code = "println(42)\n````", dirty = true))
        )
        assertEquals("`````kotlin\nprintln(42)\n````\n`````", encoded)
    }

    @Test
    fun `波浪线围栏和 CRLF 在局部编辑后继续使用原风格`() {
        val markdown = "  ~~~~sql\r\nselect 1;\r\n  ~~~~\r\n"
        val code = assertIs<DocumentCodeFenceBlock>(DocumentMarkdownBlockCodec.parse(markdown).single())

        assertEquals('~', code.fenceChar)
        assertEquals(4, code.fenceLength)
        assertEquals("  ", code.openingIndent)
        assertEquals("\r\n", code.lineEnding)
        assertEquals("\r\n", code.terminalLineEnding)

        assertEquals(
            "  ~~~~sql\r\nselect 2;\r\n  ~~~~\r\n",
            DocumentMarkdownBlockCodec.encode(listOf(code.copy(code = "select 2;", dirty = true))),
        )
    }

    @Test
    fun `编辑代码不会丢失完整 info string 和正文末尾空行`() {
        val markdown = "```kotlin title=\"示例\"\nval answer = 42\n```"
        val code = assertIs<DocumentCodeFenceBlock>(DocumentMarkdownBlockCodec.parse(markdown).single())

        assertEquals("kotlin", code.language)
        assertEquals("kotlin title=\"示例\"", code.infoString)

        val encoded = DocumentMarkdownBlockCodec.encode(
            listOf(code.copy(code = "val answer = 43\n", dirty = true))
        )
        assertEquals(
            "```kotlin title=\"示例\"\nval answer = 43\n\n```",
            encoded,
        )
        assertEquals(
            "val answer = 43\n",
            assertIs<DocumentCodeFenceBlock>(DocumentMarkdownBlockCodec.parse(encoded).single()).code,
        )
    }

    @Test
    fun `修改语言只替换 info string 首个 token 并保留其余属性`() {
        val markdown = "```kotlin {linenos=true} title=\"示例\"\nprintln(42)\n```\n"
        val code = assertIs<DocumentCodeFenceBlock>(DocumentMarkdownBlockCodec.parse(markdown).single())

        assertEquals("kotlin {linenos=true} title=\"示例\"", code.infoString)
        assertEquals(
            "```java {linenos=true} title=\"示例\"\nprintln(42)\n```\n",
            DocumentMarkdownBlockCodec.encode(listOf(code.copy(language = "java", dirty = true))),
        )
    }

    @Test
    fun `未闭合围栏不会把四空格缩进的 fence 正文误判为结束`() {
        val markdown = "```text\n第一行\n    ```\n仍是正文"
        val code = assertIs<DocumentCodeFenceBlock>(DocumentMarkdownBlockCodec.parse(markdown).single())

        assertEquals("第一行\n    ```\n仍是正文", code.code)
        assertEquals(markdown, DocumentMarkdownBlockCodec.encode(listOf(code)))
    }

    @Test
    fun `缩进代码块编辑后规范化为安全围栏`() {
        val markdown = "    val first = 1\n    val second = 2"
        val code = assertIs<DocumentCodeFenceBlock>(DocumentMarkdownBlockCodec.parse(markdown).single())

        assertEquals("val first = 1\nval second = 2", code.code)
        assertEquals(
            "```\nval changed = true\n```",
            DocumentMarkdownBlockCodec.encode(listOf(code.copy(code = "val changed = true", dirty = true))),
        )
    }

    @Test
    fun `GFM 表格解析表头对齐和转义管道并可视化编辑后规范编码`() {
        val markdown = """
            | 名称 | 说明\|备注 | 状态 |
            | :--- | :---: | ---: |
            | TeamTalk | **内部** | 可用 |
            | 文档 | `Markdown` | 建设中 |
        """.trimIndent()
        val table = assertIs<DocumentGfmTableBlock>(DocumentMarkdownBlockCodec.parse(markdown).single())

        assertEquals(listOf("名称", "说明\\|备注", "状态"), table.headers)
        assertEquals(
            listOf(DocumentTableAlignment.LEFT, DocumentTableAlignment.CENTER, DocumentTableAlignment.RIGHT),
            table.alignments,
        )
        assertEquals(listOf("TeamTalk", "**内部**", "可用"), table.rows.first())

        val changed = table.copy(
            headers = table.headers.toMutableList().also { it[0] = "产品|项目" },
            rows = table.rows + listOf(listOf("办公", "跨部门", "进行中")),
            dirty = true,
        )
        assertEquals(
            """
                | 产品\|项目 | 说明\|备注 | 状态 |
                | :--- | :---: | ---: |
                | TeamTalk | **内部** | 可用 |
                | 文档 | `Markdown` | 建设中 |
                | 办公 | 跨部门 | 进行中 |
            """.trimIndent(),
            DocumentMarkdownBlockCodec.encode(listOf(changed)),
        )
    }

    @Test
    fun `表格代码 span 中的管道遵守 GFM 反斜杠转义契约`() {
        val markdown = """
            | 类型 | 表达式 | 说明 |
            | --- | --- | --- |
            | 单反引号 | `left\|right` | 两侧值 |
            | 双反引号 | ``left`\|right`` | 包含反引号 |
        """.trimIndent()
        val table = assertIs<DocumentGfmTableBlock>(DocumentMarkdownBlockCodec.parse(markdown).single())

        assertEquals(3, table.rows[0].size)
        assertEquals("`left\\|right`", table.rows[0][1])
        assertEquals(3, table.rows[1].size)
        assertEquals("``left`\\|right``", table.rows[1][1])

        val edited = table.copy(
            rows = table.rows.toMutableList().also { rows ->
                rows[0] = rows[0].toMutableList().also { it[2] = "已确认" }
            },
            dirty = true,
        )
        assertEquals(
            """
                | 类型 | 表达式 | 说明 |
                | --- | --- | --- |
                | 单反引号 | `left\|right` | 已确认 |
                | 双反引号 | ``left`\|right`` | 包含反引号 |
            """.trimIndent(),
            DocumentMarkdownBlockCodec.encode(listOf(edited)),
        )
    }

    @Test
    fun `表格编码器会转义代码 span 内新输入的裸管道`() {
        val table = DocumentGfmTableBlock(
            key = "table-with-code",
            headers = listOf("类型", "表达式"),
            rows = listOf(listOf("范围", "`left|right`")),
        )

        val encoded = DocumentMarkdownBlockCodec.encode(listOf(table))

        assertEquals(
            "| 类型 | 表达式 |\n| --- | --- |\n| 范围 | `left\\|right` |",
            encoded,
        )
        val reparsed = assertIs<DocumentGfmTableBlock>(DocumentMarkdownBlockCodec.parse(encoded).single())
        assertEquals(listOf("范围", "`left\\|right`"), reparsed.rows.single())
    }

    @Test
    fun `不支持的扩展只退化为局部源码块`() {
        val markdown = "正文\n\n![架构图](/files/a.png)\n\n结尾"
        val blocks = DocumentMarkdownBlockCodec.parse(markdown)
        val raw = blocks.filterIsInstance<DocumentOpaqueRawBlock>().single()

        assertEquals(setOf(DocumentMarkdownUnsupportedFeature.IMAGE), raw.features)
        assertEquals("![架构图](/files/a.png)", raw.rawMarkdown)
        assertEquals(2, blocks.filterIsInstance<DocumentRichRun>().size)

        val edited = blocks.map {
            if (it == raw) raw.copy(rawMarkdown = "![新图](/files/b.png)", dirty = true) else it
        }
        assertEquals("正文\n\n![新图](/files/b.png)\n\n结尾", DocumentMarkdownBlockCodec.encode(edited))
    }

    @Test
    fun `非常规有序列表保持局部原文而不是污染整篇文档`() {
        val markdown = "前言\n\n7. 七\n8. 八\n\n后记"
        val blocks = DocumentMarkdownBlockCodec.parse(markdown)

        val raw = blocks.filterIsInstance<DocumentOpaqueRawBlock>().single()
        assertTrue(DocumentMarkdownUnsupportedFeature.NON_CANONICAL_ORDERED_LIST in raw.features)
        assertEquals(markdown, DocumentMarkdownBlockCodec.encode(blocks))
    }

    @Test
    fun `UI 可直接构造新块并由 codec 输出权威 Markdown`() {
        val blocks = listOf<DocumentMarkdownBlock>(
            DocumentRichRun(key = "new-rich", markdown = "设计说明"),
            DocumentQuoteBlock(key = "new-quote", innerMarkdown = "先评审再发布", leadingMarkdown = "\n\n"),
            DocumentCodeFenceBlock(
                key = "new-code",
                language = "kotlin",
                code = "fun main() = Unit",
                leadingMarkdown = "\n\n",
            ),
            DocumentGfmTableBlock(
                key = "new-table",
                headers = listOf("负责人", "状态"),
                alignments = listOf(DocumentTableAlignment.LEFT, DocumentTableAlignment.CENTER),
                rows = listOf(listOf("张三", "进行中")),
                leadingMarkdown = "\n\n",
            ),
        )

        assertEquals(
            """
                设计说明

                > 先评审再发布

                ```kotlin
                fun main() = Unit
                ```

                | 负责人 | 状态 |
                | :--- | :---: |
                | 张三 | 进行中 |
            """.trimIndent(),
            DocumentMarkdownBlockCodec.encode(blocks),
        )
    }
}
