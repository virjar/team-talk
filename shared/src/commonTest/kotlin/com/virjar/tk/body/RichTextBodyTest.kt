package com.virjar.tk.body

import com.virjar.tk.protocol.PacketBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RichTextBody wire 契约 + 工厂一致性（doc/10-rich-messaging §3）。
 */
class RichTextBodyTest {

    @Test
    fun `wire round-trip 保真`() {
        val body = RichTextBody(
            markdown = "你好 **世界** @[设计测试员](mention://23ezOP9D)",
            mentions = listOf(RichTextBody.Mention("23ezOP9D", "设计测试员", 11, 22)),
            plainText = "你好 世界 @设计测试员",
        )
        val byteBuf = io.netty.buffer.Unpooled.buffer()
        body.writeTo(PacketBuffer(byteBuf))

        val decoded = RichTextBody.readFrom(PacketBuffer(byteBuf))
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
    fun `空与普通文本`() {
        val plain = buildRichTextBody("普通消息 user_avatar.png")
        assertEquals(0, plain.mentions.size)
        assertEquals("普通消息 user_avatar.png", plain.plainText)

        // 纯文本不该被升级为 RICH_TEXT（发送判定）
        assertTrue(!looksRichMarkdown("普通消息 user_avatar.png 价格 100 元"))
        assertTrue(looksRichMarkdown("**加粗**"))
        assertTrue(looksRichMarkdown("@[名字](mention://uid)"))
        assertTrue(looksRichMarkdown("见 [文档](https://im.virjar.com)"))
        assertTrue(looksRichMarkdown("# 标题"))
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

        val byteBuf = io.netty.buffer.Unpooled.buffer()
        body.writeTo(PacketBuffer(byteBuf))
        val decoded = InteractiveCardBody.readFrom(PacketBuffer(byteBuf))

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
