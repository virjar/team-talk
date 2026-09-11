package com.virjar.tk.app.ui.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/** 真机回归：另一设备发送后，打开中的输入框必须消费清空，同时保留本机新输入。 */
class ChatDraftSynchronizationTest {
    @Test
    fun `unchanged external draft accepts updates and clearing`() {
        val sync = ChatDraftSync("old", null)
        assertEquals("new", sync.receive("new", "old", false))
        assertEquals("", sync.receive("", "new", false))
        assertNull(sync.receive("", "", false))
        val published = mutableListOf<String>()
        sync.publish("", published::add)
        assertEquals(emptyList(), published, "缓存更新不能被重新发布")
    }

    @Test
    fun `unloaded conversation does not clear restored text`() {
        val restored = ChatComposerContext(
            markdown = "restored", previousCachedDraft = "restored", lastPublishedDraft = "restored",
        )
        val sync = ChatDraftSync(null, restored)
        val published = mutableListOf<String>()
        assertNull(sync.receive(null, "restored", false))
        sync.publish("restored", published::add)
        assertEquals(emptyList(), published, "未加载时不能回写旧正文")
        assertEquals("", sync.receive("", "restored", false))
        assertEquals("late", ChatDraftSync(null, null).receive("late", "", false))
        assertNull(ChatDraftSync(null, null).receive("late", "local input", false))
    }

    @Test
    fun `local edits and explicit local clearing survive older cache updates`() {
        assertNull(ChatDraftSync("old", null).receive("remote", "", false))
        // 已发布但尚未在缓存中回显的输入，仍以旧的缓存值作为比较基线。
        val sync = ChatDraftSync("old", null)
        sync.publish("published input", {})
        assertNull(sync.receive("remote", "published input", false))
        assertNull(sync.receive("published input", "published input", false))
        assertEquals("", sync.receive("", "published input", false))
    }

    @Test
    fun `reply edit and asset contexts are not replaced by ordinary drafts`() {
        val sync = ChatDraftSync("old", null)
        assertNull(sync.receive("remote", "old", true))
        val restored = ChatComposerContext(
            markdown = "old", previousCachedDraft = sync.previousCachedDraft,
            lastPublishedDraft = sync.lastPublishedDraft,
        )
        val published = mutableListOf<String>()
        ChatDraftSync("remote", restored).publish("old", published::add)
        assertEquals(emptyList(), published, "保留的交互上下文不能在重新进入时覆盖远端")
    }

    @Test
    fun `navigation restores the observed cache baseline rather than assuming publication completed`() {
        val contexts = ChatComposerContextStore()
        contexts.save("chat", ChatComposerContext(
            markdown = "local input", previousCachedDraft = "old", lastPublishedDraft = "local input",
        ))
        val pending = requireNotNull(contexts.restore("chat"))
        assertNull(ChatDraftSync("", pending).receive("", pending.markdown, false))
    }

    @Test
    fun `clearing before local commit survives fast navigation`() {
        val contexts = ChatComposerContextStore()
        contexts.save("chat", ChatComposerContext(previousCachedDraft = "old"))
        val cleared = assertNotNull(contexts.restore("chat"))
        assertEquals("", cleared.markdown)
        assertNull(ChatDraftSync("old", cleared).receive("old", cleared.markdown, false))
        contexts.save("chat", ChatComposerContext(previousCachedDraft = ""))
        assertNull(contexts.restore("chat"), "缓存已追上清空后不再保留空上下文")
    }

    /** 真机回归（内测 T043）：冷恢复的草稿清空后不得被 baseline 复活为旧正文。 */
    @Test
    fun `cold hydrated baseline never resurrects a cleared editor`() {
        val context = com.virjar.tk.shared.client.ChatDraftSnapshot(chatId = "chat", markdown = "@")
            .toComposerContext()
        // 未编辑时保留持久化的原始写法；用户清空编辑器后快照必须是空，而不是旧草稿。
        assertEquals("@", context.visualBaseline.snapshot("@"))
        assertEquals("", context.visualBaseline.snapshot(""))
        // 编辑后的内容原样透传，不受 baseline 干扰。
        assertEquals("@小张", context.visualBaseline.snapshot("@小张"))
    }

    /** 空草稿快照的 baseline 恒等，恢复后清空仍是空。 */
    @Test
    fun `cold hydrated empty draft stays empty after restore`() {
        val context = com.virjar.tk.shared.client.ChatDraftSnapshot(chatId = "chat")
            .toComposerContext()
        assertEquals("", context.visualBaseline.snapshot(""))
    }
}
