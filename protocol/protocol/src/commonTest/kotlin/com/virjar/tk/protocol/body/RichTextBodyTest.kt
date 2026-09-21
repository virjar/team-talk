package com.virjar.tk.protocol.body

import com.virjar.tk.protocol.PacketBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RichTextBody wire 契约 + 工厂一致性（doc/05-clients/rich-content.md）。
 */
class RichTextBodyTest {

    @Test
    fun `mention 构造器转义链接边界且派生字段可还原`() {
        val markdown = buildMentionMarkdown("研发]平台\\组", "uid(研发)")
        val body = buildRichTextBody(markdown)

        assertEquals("@[研发\\]平台\\\\组](mention://uid\\(研发\\))", markdown)
        assertEquals("研发]平台\\组", body.mentions.single().displayName)
        assertEquals("uid(研发)", body.mentions.single().uid)
        assertEquals("@研发]平台\\组", body.plainText)
    }

    @Test
    fun `wire round-trip 保真`() {
        val body = RichTextBody(
            markdown = "你好 **世界** @[设计测试员](mention://23ezOP9D)",
            mentions = listOf(RichTextBody.Mention("23ezOP9D", "设计测试员", 11, 22)),
            plainText = "你好 世界 @设计测试员",
        )
        val buffer = PacketBuffer()
        body.writeTo(buffer)

        val decoded = RichTextBody.readFrom(PacketBuffer(buffer.toByteArray()))
        assertEquals(body, decoded)
    }

    @Test
    fun `工厂提取 mentions 与 plainText`() {
        val md = "**评审**：@[张三](mention://dQ3KUFf7) 请看 [文档](https://im.virjar.com) 和 `代码`"
        val body = buildRichTextBody(md)

        val m = body.mentions.single()
        assertEquals("dQ3KUFf7", m.uid)
        assertEquals("张三", m.displayName)
        // mention 区间指向 markdown 中的完整链接语法
        assertEquals("@[张三](mention://dQ3KUFf7)", md.substring(m.offset, m.offset + m.length))

        // plainText：语法剥离、mention 转可见名、链接留 label
        assertEquals("评审：@张三 请看 文档 和 代码", body.plainText)
    }

    @Test
    fun `工厂处理多 mention 与块级语法`() {
        val md = "## 标题\n\n- @[甲](mention://uid1) 项一\n- @[乙](mention://uid2) 项二\n\n> 引 @[丙](mention://uid3)"
        val body = buildRichTextBody(md)
        assertEquals(3, body.mentions.size)
        assertEquals(listOf("uid1", "uid2", "uid3"), body.mentions.map { it.uid })
        assertTrue("标题" in body.plainText)
        assertTrue("@甲 项一" in body.plainText)
        assertTrue("引 @丙" in body.plainText)
        assertTrue(!body.plainText.contains("##"))
        assertTrue(!body.plainText.contains("mention://"))
    }

    @Test
    fun `普通文本也是合法 Markdown 消息`() {
        val plain = buildRichTextBody("普通消息 user_avatar.png")
        assertEquals(0, plain.mentions.size)
        assertEquals("普通消息 user_avatar.png", plain.plainText)
        assertEquals("普通消息 user_avatar.png", plain.markdown)
    }

    @Test
    fun `plainText 剥离单标记斜体但保留普通下划线`() {
        val body = buildRichTextBody("*star italic* _underscore italic_ user_avatar.png")
        assertEquals("star italic underscore italic user_avatar.png", body.plainText)
    }

    @Test
    fun `plainText 链接跳过受限前字符但继续查找候选内部的合法链接`() {
        val cases = listOf(
            "[label](target)" to "label",
            """\[label](target)""" to "[label](target)",
            """\\[label](target)""" to """\[label](target)""",
            """\[[inner](target)](outer)""" to "[inner](outer)",
            """\[\[[deep](target)](outer)](last)""" to "[[deep](outer)](last)",
            "[[inner](target)](outer)" to "[inner](outer)",
            """\[literal](target) [visible](target)""" to "[literal](target) visible",
        )
        cases.forEach { (markdown, expected) ->
            assertEquals(expected, buildRichTextBody(markdown).plainText, markdown)
            assertEquals(expected, richTextDisplayText(markdown), markdown)
        }
    }

    @Test
    fun `plainText 斜体拒绝候选后仍可从其结束标记找到合法匹配`() {
        val cases = listOf(
            "**outer*inner*" to "**outerinner",
            "__outer_inner_" to "__outerinner",
            "**outer*inner* *later*" to "**outerinner later",
            "__outer_inner_ _later_" to "__outerinner later",
            "a*b*c a_b_c" to "abc abc",
            "*left**right*" to "*left**right*",
            "_left__right_" to "_left__right_",
            "*line\nbreak* _line\nbreak_" to "*line\nbreak* _line\nbreak_",
            "***mixed*** ___mixed___" to "mixed mixed",
        )
        cases.forEach { (markdown, expected) ->
            assertEquals(expected, buildRichTextBody(markdown).plainText, markdown)
            assertEquals(expected, richTextDisplayText(markdown), markdown)
        }
    }

    @Test
    fun `正文长度上限的纯文本逐字保留`() {
        val markdown = "a".repeat(MessageBodyPolicy.MAX_MARKDOWN_LENGTH)
        assertEquals(markdown, buildRichTextBody(markdown).plainText)
        assertEquals(markdown, richTextDisplayText(markdown))
    }

    @Test
    fun `CommonMark 标点转义在派生字段中解码且非标点转义保留`() {
        val markdown = """字面 \* \[文本\] \\ \q @[研发\]组](mention://uid-1) [文档 \[v2\]](https://im.virjar.com/a\(b\))"""
        val body = buildRichTextBody(markdown)

        val mention = body.mentions.single()
        assertEquals("uid-1", mention.uid)
        assertEquals("研发]组", mention.displayName)
        assertEquals(
            "@[研发\\]组](mention://uid-1)",
            markdown.substring(mention.offset, mention.offset + mention.length),
        )
        assertEquals("字面 * [文本] \\ \\q @研发]组 文档 [v2]", body.plainText)
    }

    @Test
    fun `CommonMark 解码器不吞非标点反斜杠`() {
        assertEquals("* ] \\ \\q", decodeCommonMarkPunctuationEscapes("""\* \] \\ \q"""))
    }

    @Test
    fun `消息策略重建派生字段并拒绝类型错配`() {
        val declared = RichTextBody(
            markdown = "**可信源** @[张三](mention://u1)",
            mentions = emptyList(),
            plainText = "伪造的搜索文本",
        )
        val message = com.virjar.tk.protocol.model.Message(
            chatId = "chat", clientMsgId = "client", senderUid = "sender",
            messageType = com.virjar.tk.protocol.MessageType.RICH_TEXT.code,
            timestamp = 1L, body = declared,
        )

        val canonical = MessageBodyPolicy.canonicalize(message).body as RichTextBody
        assertEquals("可信源 @张三", canonical.plainText)
        assertEquals("u1", canonical.mentions.single().uid)

        kotlin.test.assertFailsWith<IllegalArgumentException> {
            MessageBodyPolicy.canonicalize(message.copy(messageType = com.virjar.tk.protocol.MessageType.FILE.code))
        }
    }
}


/** 交互卡片 wire 契约 + payload JSON 稳定性 */
class InteractiveCardBodyTest {

    @Test
    fun `card wire round-trip 与 payload 还原`() {
        val card = CardPayload(
            title = "构建通知",
            blocks = listOf(CardBlock.Text("构建通过"), CardBlock.Text("耗时 3m")),
        )
        val body = InteractiveCardBody.of(card)

        val buffer = PacketBuffer()
        body.writeTo(buffer)
        val decoded = InteractiveCardBody.readFrom(PacketBuffer(buffer.toByteArray()))

        assertEquals(body, decoded)
        val decodedCard = decoded.toCard()
        assertEquals(card, decodedCard)
    }

    @Test
    fun `非法 payload 降级 null 而非崩溃`() {
        val body = InteractiveCardBody(payloadJson = "{not-json")
        assertEquals(null, body.toCard())
    }
}


/** 强类型图片附件 round-trip。 */
class ImageBodyProtocolTest {

    private fun encode(body: com.virjar.tk.protocol.body.ImageBody): ByteArray {
        val buffer = com.virjar.tk.protocol.PacketBuffer()
        body.writeTo(buffer)
        return buffer.toByteArray()
    }

    @Test
    fun `新编码带缩略图 round-trip`() {
        val body = com.virjar.tk.protocol.body.ImageBody(
            attachment = com.virjar.tk.protocol.model.Attachment("u/im.png", "im.png", "image/png", 12345),
            width = 800,
            height = 600,
            thumbnail = com.virjar.tk.protocol.model.Attachment("u/im.thumb.jpg", "im.thumb.jpg", "image/jpeg", 1234),
        )
        val decoded = com.virjar.tk.protocol.body.ImageBody.readFrom(com.virjar.tk.protocol.PacketBuffer(encode(body)))
        assertEquals(body, decoded)
    }

    @Test
    fun `null 缩略图 round-trip`() {
        val body = com.virjar.tk.protocol.body.ImageBody(
            com.virjar.tk.protocol.model.Attachment("u/im.png", "im.png", "image/png", 1),
            1,
            1,
            thumbnail = null,
        )
        val decoded = com.virjar.tk.protocol.body.ImageBody.readFrom(com.virjar.tk.protocol.PacketBuffer(encode(body)))
        assertEquals(null, decoded.thumbnail)
    }
}
