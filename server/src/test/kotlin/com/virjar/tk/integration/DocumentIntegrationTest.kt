package com.virjar.tk.integration

import com.virjar.tk.domain.document.DocumentService
import com.virjar.tk.model.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DocumentIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `group documents enforce live membership and immutable revisions`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("document-owner"))
        val member = ctx.registerUser(uniqueUsername("document-member"))
        val outsider = ctx.registerUser(uniqueUsername("document-outsider"))
        val chat = ctx.chatService.createGroup("产品知识库", null, owner, listOf(member))

        val created = ctx.documentService.create(
            owner,
            Document.SCOPE_GROUP_CHAT,
            chat.chatId,
            "产品规格",
            "# v1\n第一版内容",
        )
        assertEquals(1, created.revision)
        assertEquals(listOf("产品规格"), ctx.documentService.list(member, Document.SCOPE_GROUP_CHAT, chat.chatId).map { it.title })
        assertEquals("# v1\n第一版内容", ctx.documentService.get(member, Document.SCOPE_GROUP_CHAT, chat.chatId, created.documentId).markdown)
        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.list(outsider, Document.SCOPE_GROUP_CHAT, chat.chatId)
        }

        val updated = ctx.documentService.update(
            member,
            Document.SCOPE_GROUP_CHAT,
            chat.chatId,
            created.documentId,
            "产品规格 2026",
            "# v2\n第二版内容",
            created.revision,
        )
        assertEquals(2, updated.revision)
        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.update(
                owner,
                Document.SCOPE_GROUP_CHAT,
                chat.chatId,
                created.documentId,
                "过期编辑",
                "不会覆盖",
                created.revision,
            )
        }
        assertEquals(
            listOf(2L, 1L),
            ctx.documentService.listRevisions(owner, Document.SCOPE_GROUP_CHAT, chat.chatId, created.documentId)
                .map { it.revision },
        )
        val v1 = ctx.documentService.getRevision(owner, Document.SCOPE_GROUP_CHAT, chat.chatId, created.documentId, 1)
        assertEquals("产品规格", v1.title)
        assertEquals("# v1\n第一版内容", v1.markdown)

        ctx.chatService.removeMember(owner, chat.chatId, member)
        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.get(member, Document.SCOPE_GROUP_CHAT, chat.chatId, created.documentId)
        }

        ctx.documentService.delete(owner, Document.SCOPE_GROUP_CHAT, chat.chatId, created.documentId, updated.revision)
        assertTrue(ctx.documentService.list(owner, Document.SCOPE_GROUP_CHAT, chat.chatId).isEmpty())
        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.get(owner, Document.SCOPE_GROUP_CHAT, chat.chatId, created.documentId)
        }
    }

    @Test
    fun `document scope and content boundaries are server owned`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("document-boundary-owner"))
        val peer = ctx.registerUser(uniqueUsername("document-boundary-peer"))
        val group = ctx.chatService.createGroup("边界验证", null, owner, emptyList())
        val personal = ctx.chatService.createPersonalChat(owner, peer)

        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.create(owner, Document.SCOPE_GROUP_CHAT, personal.chatId, "错误空间", "")
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.create(owner, 999, group.chatId, "未知空间", "")
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.create(owner, Document.SCOPE_GROUP_CHAT, group.chatId, "x".repeat(181), "")
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.create(
                owner,
                Document.SCOPE_GROUP_CHAT,
                group.chatId,
                "超长正文",
                "x".repeat(DocumentService.MAX_MARKDOWN_LENGTH + 1),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.create(owner, Document.SCOPE_GROUP_CHAT, group.chatId, "非法正文", "a\u0000b")
        }
    }

    @Test
    fun `only one concurrent edit can claim the same document revision`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("document-race-owner"))
        val member = ctx.registerUser(uniqueUsername("document-race-member"))
        val chat = ctx.chatService.createGroup("并发编辑", null, owner, listOf(member))
        val created = ctx.documentService.create(owner, Document.SCOPE_GROUP_CHAT, chat.chatId, "会议纪要", "初稿")

        val results = coroutineScope {
            listOf(owner, member).mapIndexed { index, actor ->
                async(Dispatchers.Default) {
                    runCatching {
                        ctx.documentService.update(
                            actor,
                            Document.SCOPE_GROUP_CHAT,
                            chat.chatId,
                            created.documentId,
                            "会议纪要-${index + 1}",
                            "并发版本 ${index + 1}",
                            created.revision,
                        )
                    }
                }
            }.awaitAll()
        }

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.isFailure })
        val current = ctx.documentService.get(owner, Document.SCOPE_GROUP_CHAT, chat.chatId, created.documentId)
        assertEquals(2, current.revision)
        assertEquals(2, ctx.documentRepo.listRevisions(created.documentId).size)
    }
}
