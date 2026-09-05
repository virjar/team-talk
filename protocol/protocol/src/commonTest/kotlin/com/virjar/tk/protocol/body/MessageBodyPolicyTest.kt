package com.virjar.tk.protocol.body

import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MessageBodyPolicyTest {

    @Test
    fun `typing is the only bodyless message accepted`() {
        val typing = Message(
            chatId = "chat-1",
            clientMsgId = "typing-1",
            senderUid = "sender-1",
            messageType = MessageType.TYPING.code,
            timestamp = 1,
            body = null,
        )
        assertEquals(typing, MessageBodyPolicy.canonicalize(typing))

        assertFailsWith<IllegalArgumentException> {
            MessageBodyPolicy.canonicalize(
                typing.copy(messageType = MessageType.RICH_TEXT.code),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MessageBodyPolicy.canonicalize(
                typing.copy(body = buildRichTextBody("伪造正文")),
            )
        }
    }

    @Test
    fun `rich text and reply share markdown safety budget`() {
        val oversized = "a".repeat(MessageBodyPolicy.MAX_MARKDOWN_LENGTH + 1)

        assertFailsWith<IllegalArgumentException> {
            MessageBodyPolicy.canonicalize(message(MessageType.RICH_TEXT, buildRichTextBody(oversized)))
        }
        assertFailsWith<IllegalArgumentException> {
            MessageBodyPolicy.canonicalize(
                message(MessageType.REPLY, ReplyBody("message-1", "user-1", content = oversized)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MessageBodyPolicy.validateMarkdown("#".repeat(MessageBodyPolicy.MAX_MARKDOWN_STRUCTURE_MARKERS + 1))
        }
        assertFailsWith<IllegalArgumentException> {
            MessageBodyPolicy.validateMarkdown(
                "\n".repeat(MessageBodyPolicy.MAX_MARKDOWN_LINES),
            )
        }
    }

    @Test
    fun `reply assets canonicalize endpoint URLs and first reference order`() {
        val firstId = "00000000-0000-4000-8000-000000000201"
        val secondId = "00000000-0000-4000-8000-000000000202"
        val first = EmbeddedAsset(
            assetId = firstId,
            attachment = Attachment(
                path = "/api/v1/files/owner/first.pdf",
                name = "first.pdf",
                contentType = "application/pdf",
                size = 11,
            ),
        )
        val second = EmbeddedAsset(
            assetId = secondId,
            attachment = Attachment(
                path = "https://sdk.example/api/v1/files/owner/second.png?download=1",
                name = "second.png",
                contentType = "image/png",
                size = 12,
            ),
        )
        val content = "![second](${EmbeddedAsset.uri(secondId)})\n[first](${EmbeddedAsset.uri(firstId)})"

        val canonical = MessageBodyPolicy.canonicalize(
            message(
                MessageType.REPLY,
                ReplyBody(
                    replyToMsgId = "message-1",
                    replyToSenderUid = "user-1",
                    content = content,
                    assets = listOf(first, second),
                ),
            ),
        ).body as ReplyBody

        assertEquals(listOf(secondId, firstId), canonical.assets.map(EmbeddedAsset::assetId))
        assertEquals(
            listOf("owner/second.png", "owner/first.pdf"),
            canonical.assets.map { it.attachment.path },
        )
    }

    @Test
    fun `blockquote nesting has an explicit recursion budget`() {
        val allowed = "> ".repeat(MessageBodyPolicy.MAX_BLOCK_QUOTE_NESTING) + "正文"
        assertEquals(allowed, MessageBodyPolicy.validateMarkdown(allowed))

        assertFailsWith<IllegalArgumentException> {
            MessageBodyPolicy.validateMarkdown(
                "> ".repeat(MessageBodyPolicy.MAX_BLOCK_QUOTE_NESTING + 1) + "正文",
            )
        }

        val fencedSource = "```text\n" + "> ".repeat(MessageBodyPolicy.MAX_BLOCK_QUOTE_NESTING + 1) + "源码\n```"
        assertEquals(fencedSource, MessageBodyPolicy.validateMarkdown(fencedSource))
    }

    @Test
    fun `tables have explicit column and rendered cell budgets`() {
        fun row(columns: Int, value: String) =
            "| " + List(columns) { value }.joinToString(" | ") + " |"

        val allowed = buildString {
            appendLine(row(MessageBodyPolicy.MAX_MARKDOWN_TABLE_COLUMNS, "标题"))
            appendLine(row(MessageBodyPolicy.MAX_MARKDOWN_TABLE_COLUMNS, "---"))
            repeat(30) { appendLine(row(MessageBodyPolicy.MAX_MARKDOWN_TABLE_COLUMNS, "值")) }
        }
        assertEquals(allowed, MessageBodyPolicy.validateMarkdown(allowed))

        assertFailsWith<IllegalArgumentException> {
            MessageBodyPolicy.validateMarkdown(
                row(MessageBodyPolicy.MAX_MARKDOWN_TABLE_COLUMNS + 1, "标题") + "\n" +
                    row(MessageBodyPolicy.MAX_MARKDOWN_TABLE_COLUMNS + 1, "---"),
            )
        }

        // 偶数个反斜杠不会转义竖线；不能借此把 33 列表头误判为单列并绕过预算。
        assertFailsWith<IllegalArgumentException> {
            val columns = MessageBodyPolicy.MAX_MARKDOWN_TABLE_COLUMNS + 1
            val header = List(columns) { "值" }.joinToString("\\\\|")
            MessageBodyPolicy.validateMarkdown(header + "\n" + row(columns, "---"))
        }

        assertFailsWith<IllegalArgumentException> {
            MessageBodyPolicy.validateMarkdown(
                buildString {
                    appendLine(row(2, "标题"))
                    appendLine(row(2, "---"))
                    repeat(MessageBodyPolicy.MAX_MARKDOWN_TABLE_CELLS / 2) { appendLine(row(2, "值")) }
                },
            )
        }

        val fencedTable = "```markdown\n" + row(40, "标题") + "\n" + row(40, "---") + "\n```"
        assertEquals(fencedTable, MessageBodyPolicy.validateMarkdown(fencedTable))
    }

    @Test
    fun `all body metadata has bounded validation`() {
        val oversizedShortText = "x".repeat(MessageBodyPolicy.MAX_SHORT_TEXT_LENGTH + 1)
        val oversizedIdentifier = "x".repeat(MessageBodyPolicy.MAX_IDENTIFIER_LENGTH + 1)

        assertFailsWith<IllegalArgumentException> {
            MessageBodyPolicy.canonicalize(
                message(
                    MessageType.REPLY,
                    ReplyBody("message-1", "user-1", replySnippet = oversizedShortText, content = "reply"),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MessageBodyPolicy.canonicalize(
                message(MessageType.LOCATION, LocationBody(31.2, 121.5, address = oversizedShortText)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MessageBodyPolicy.canonicalize(
                message(
                    MessageType.CARD,
                    CardBody(
                        "user-1",
                        "name",
                        Attachment(
                            path = "x".repeat(AttachmentPolicy.MAX_REFERENCE_LENGTH + 1),
                            name = "avatar.png",
                            contentType = "image/png",
                            size = 1,
                        ),
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MessageBodyPolicy.canonicalize(
                message(MessageType.FORWARD, ForwardBody(forwardFromMsgId = "message-1", forwardNote = oversizedShortText)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MessageBodyPolicy.canonicalize(
                message(MessageType.EDIT, EditBody("message-1", "x".repeat(MessageBodyPolicy.MAX_MARKDOWN_LENGTH + 1))),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MessageBodyPolicy.canonicalize(
                message(MessageType.REACTION, ReactionBody("message-1", "x".repeat(MessageBodyPolicy.MAX_EMOJI_LENGTH + 1))),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MessageBodyPolicy.canonicalize(
                message(MessageType.REVOKE, RevokeBody(oversizedIdentifier)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MessageBodyPolicy.canonicalize(
                message(
                    MessageType.FILE,
                    FileBody(
                        Attachment(
                            path = "user/file.bin",
                            name = "x".repeat(AttachmentPolicy.MAX_NAME_LENGTH + 1),
                            contentType = "application/octet-stream",
                            size = 1,
                        ),
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MessageBodyPolicy.canonicalize(
                message(
                    MessageType.MERGE_FORWARD,
                    MergeForwardBody(messageCount = MessageBodyPolicy.MAX_MERGE_FORWARD_MESSAGE_COUNT + 1),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MessageBodyPolicy.canonicalize(
                message(
                    MessageType.IMAGE,
                    ImageBody(
                        Attachment("user/image.png", "image.png", "image/png", 1),
                        width = MessageBodyPolicy.MAX_MEDIA_DIMENSION + 1,
                    ),
                ),
            )
        }
    }

    @Test
    fun `interactive card must be parseable and within schema budget`() {
        val valid = InteractiveCardBody.of(
            CardPayload(title = "构建通知", blocks = listOf(CardBlock.Text("构建通过"))),
        )
        assertEquals(valid, MessageBodyPolicy.canonicalize(message(MessageType.INTERACTIVE_CARD, valid)).body)

        assertFailsWith<IllegalArgumentException> {
            MessageBodyPolicy.canonicalize(
                message(MessageType.INTERACTIVE_CARD, InteractiveCardBody("{not-json")),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MessageBodyPolicy.canonicalize(
                message(
                    MessageType.INTERACTIVE_CARD,
                    InteractiveCardBody.of(
                        CardPayload(
                            blocks = List(MessageBodyPolicy.MAX_INTERACTIVE_CARD_BLOCKS + 1) {
                                CardBlock.Text("block")
                            },
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `all text sources used by search and conversation projection reject NUL`() {
        val nul = '\u0000'
        val invalidBodies = listOf(
            MessageType.RICH_TEXT to buildRichTextBody("rich${nul}text"),
            MessageType.INTERACTIVE_CARD to InteractiveCardBody.of(CardPayload(title = "card${nul}title")),
            MessageType.INTERACTIVE_CARD to InteractiveCardBody.of(
                CardPayload(blocks = listOf(CardBlock.Text("card${nul}block"))),
            ),
            MessageType.FILE to FileBody(
                Attachment("user/file.bin", "file${nul}name.bin", "application/octet-stream", 1),
            ),
            MessageType.LOCATION to LocationBody(31.2, 121.5, title = "location${nul}title"),
            MessageType.LOCATION to LocationBody(31.2, 121.5, address = "location${nul}address"),
            MessageType.CARD to CardBody("user-1", "contact${nul}name"),
            MessageType.REPLY to ReplyBody("message-1", "user-1", content = "reply${nul}content"),
            MessageType.REPLY to ReplyBody(
                "message-1",
                "user-1",
                replySnippet = "reply${nul}snippet",
                content = "",
            ),
            MessageType.FORWARD to ForwardBody(forwardNote = "forward${nul}note"),
            MessageType.MERGE_FORWARD to MergeForwardBody(title = "merge${nul}title"),
            MessageType.EDIT to EditBody("message-1", "edit${nul}content"),
        )

        invalidBodies.forEachIndexed { index, (type, body) ->
            assertFailsWith<IllegalArgumentException>("$type projection source #$index must reject NUL") {
                MessageBodyPolicy.canonicalize(message(type, body))
            }
        }
    }

    private fun message(type: MessageType, body: MessageBody) = Message(
        chatId = "chat-1",
        clientMsgId = "client-1",
        senderUid = "sender-1",
        messageType = type.code,
        timestamp = 1,
        body = body,
    )
}
