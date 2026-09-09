package com.virjar.tk.app.ui.screen

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.mohamedrejeb.richeditor.model.RichTextState
import com.virjar.tk.app.ui.component.input.detectMentionQuery
import com.virjar.tk.app.ui.component.input.filterMentionCandidates
import com.virjar.tk.app.ui.component.input.pickMentionIntoRichState
import com.virjar.tk.app.ui.component.rich.DocumentMarkdownBlockCodec
import com.virjar.tk.app.ui.component.rich.RichEditorMarkdownCapability
import com.virjar.tk.protocol.model.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 文档 @ 提及的编辑器侧契约：@ 触发检测、候选过滤、写回 mention 链接，以及
 * `@[名](mention://uid)` 在文档块编解码与可视化能力检查下的保真性。
 */
class DocumentMentionCompletionTest {

    private val alice = User(uid = "uid-alice", username = "alice", name = "爱丽丝")
    private val bob = User(uid = "uid-bob", username = "bob", name = "Bob")

    private fun users(): List<User> = listOf(alice, bob)

    /** 模拟“在文末输入”：setMarkdown 后 fork 的 selection 仍在 0，需显式移到末尾。 */
    private fun typeAtEnd(state: RichTextState, text: String): TextFieldValue {
        state.selection = TextRange(state.annotatedString.text.length)
        state.replaceRange(state.selection.min, state.selection.min, text)
        return TextFieldValue(state.annotatedString.text, state.selection)
    }

    @Test
    fun `typing at after whitespace triggers mention query`() {
        val state = RichTextState()
        state.setMarkdown("请看正文")
        val field = typeAtEnd(state, " @alice")
        val query = assertNotNull(detectMentionQuery(field))
        assertEquals("alice", query.text)
    }

    @Test
    fun `at without preceding whitespace does not trigger`() {
        val state = RichTextState()
        state.setMarkdown("邮箱test")
        val field = typeAtEnd(state, "@alice")
        assertNull(detectMentionQuery(field))
    }

    @Test
    fun `candidate filter excludes self and matches by name or uid`() {
        val termAl = detectMentionQuery(TextFieldValue("@al", TextRange(3)))
        assertEquals(listOf("uid-alice"), filterMentionCandidates(users(), termAl, myUid = "uid-other").map { it.uid })

        // 自己不在候选里
        val empty = detectMentionQuery(TextFieldValue("@", TextRange(1)))
        assertEquals(listOf("uid-bob"), filterMentionCandidates(users(), empty, myUid = "uid-alice").map { it.uid })

        val byUid = filterMentionCandidates(
            users(),
            detectMentionQuery(TextFieldValue("@uid-bob", TextRange(8))),
            myUid = null,
        )
        assertEquals(listOf(bob.uid), byUid.map { it.uid })
    }

    @Test
    fun `picked mention serializes to mention link and survives block codec round trip`() {
        val state = RichTextState()
        state.setMarkdown("请看正文")
        val field = typeAtEnd(state, " @al")
        val query = assertNotNull(detectMentionQuery(field))
        pickMentionIntoRichState(state, query, field.selection.min, alice)

        val markdown = state.toMarkdown()
        assertTrue(markdown.contains("(mention://uid-alice)"), markdown)

        // mention 链接是普通 INLINE_LINK，不触发源码模式，块编解码往返无损
        assertFalse(RichEditorMarkdownCapability.inspect(markdown).requiresSourceMode, markdown)
        val encoded = DocumentMarkdownBlockCodec.encode(DocumentMarkdownBlockCodec.parse(markdown, emptyList()))
        assertTrue(encoded.contains("(mention://uid-alice)"), encoded)
        assertEquals(encoded, DocumentMarkdownBlockCodec.encode(DocumentMarkdownBlockCodec.parse(encoded, emptyList())))
    }

    @Test
    fun `mention inside quote block also survives round trip`() {
        val markdown = "> 引用 [Bob](mention://uid-bob) 到场"
        val encoded = DocumentMarkdownBlockCodec.encode(DocumentMarkdownBlockCodec.parse(markdown, emptyList()))
        assertTrue(encoded.contains("(mention://uid-bob)"), encoded)
    }
}
