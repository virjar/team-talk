package com.virjar.tk.server.integration

import com.virjar.tk.protocol.DocumentChangedPayload
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.model.DocumentComment
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.payload.InvokePayload
import com.virjar.tk.protocol.rpc.gen.DocumentCommentRpcContract
import com.virjar.tk.server.domain.command.ReliableCommandConflictException
import com.virjar.tk.server.domain.document.DocumentAccessDeniedException
import com.virjar.tk.server.domain.document.DocumentCommentService
import com.virjar.tk.server.domain.document.DocumentNotFoundException
import com.virjar.tk.server.domain.document.DocumentRevisionConflictException
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.server.infra.db.ExposedPgUnitOfWork
import com.virjar.tk.server.infra.db.PgUnitOfWorkHooks
import com.virjar.tk.server.infra.db.PgUnitOfWorkStage
import com.virjar.tk.server.infra.db.SyncEvents
import com.virjar.tk.server.infra.db.repository.ExposedDocumentCommentRepository
import com.virjar.tk.server.protocol.dispatcher.RpcDispatcher
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 隔离 PostgreSQL schema 内验证评论权限、可靠重放及评论与持久事件的同事务关系。 */
class DocumentCommentIntegrationTest {
    companion object {
        @JvmField @RegisterExtension val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env
    private val documents get() = ctx.documentService
    private val commentRepository = ExposedDocumentCommentRepository()
    private val comments get() = commentService()
    private fun id() = UUID.randomUUID().toString()

    @Test
    fun `creation replays its stable identity and rejects a different intent without duplicate events`() = runTest {
        val fixture = newDocument()
        val commentId = id()
        val created = comments.create(fixture.owner, fixture.spaceId, fixture.documentId, commentId, null, "原评论")
        val committedEvents = events(fixture.owner, fixture)
        assertEquals(1, committedEvents.size)
        assertTrue(created.sequence > 0)
        assertEquals(1L, created.revision)
        assertFalse(created.deleted)
        assertEquals(fixture.owner, created.authorUid)

        // 新服务对象从 PostgreSQL 找到同一创建事实，而非依赖进程内去重。
        val restarted = commentService(ExposedPgUnitOfWork(ctx.database, onEventsCommitted = {}))
        assertEquals(created, restarted.create(fixture.owner, fixture.spaceId, fixture.documentId, commentId, null, "原评论"))
        assertFailsWith<ReliableCommandConflictException> {
            restarted.create(fixture.owner, fixture.spaceId, fixture.documentId, commentId, null, "另一个意图")
        }
        val otherDocument = documents.createDocument(fixture.owner, fixture.spaceId, null, "另一文档", "正文")
        assertFailsWith<ReliableCommandConflictException> {
            restarted.create(fixture.owner, fixture.spaceId, otherDocument.documentId, commentId, null, "原评论")
        }
        assertEquals(listOf(created), comments.list(fixture.owner, fixture.spaceId, fixture.documentId, 0, 10).items)
        assertEquals(committedEvents, events(fixture.owner, fixture))
        assertTrue(comments.list(fixture.owner, fixture.spaceId, otherDocument.documentId, 0, 10).items.isEmpty())
    }

    @Test
    fun `replies require an existing parent in the same document and space`() = runTest {
        val fixture = newDocument()
        val parent = createComment(fixture, "父评论")
        val reply = comments.create(fixture.owner, fixture.spaceId, fixture.documentId, id(), parent.commentId, "回复")
        assertEquals(parent.commentId, reply.replyToId)
        val before = events(fixture.owner, fixture)
        val otherDocument = documents.createDocument(fixture.owner, fixture.spaceId, null, "另一文档", "正文")
        val otherSpace = documents.createSpace(fixture.owner, "另一空间", null)
        val otherSpaceDocument = documents.createDocument(fixture.owner, otherSpace.spaceId, null, "文档", "正文")

        val targets = listOf(
            fixture.spaceId to otherDocument.documentId,
            otherSpace.spaceId to otherSpaceDocument.documentId,
        )
        for ((spaceId, documentId) in targets) {
            val rejectedId = id()
            assertFailsWith<DocumentNotFoundException> {
                comments.create(fixture.owner, spaceId, documentId, rejectedId, parent.commentId, "跨文档回复")
            }
            assertNull(storedComment(rejectedId))
            assertTrue(comments.list(fixture.owner, spaceId, documentId, 0, 10).items.isEmpty())
        }
        val missingParentCommentId = id()
        assertFailsWith<DocumentNotFoundException> {
            comments.create(fixture.owner, fixture.spaceId, fixture.documentId, missingParentCommentId, id(), "不存在的回复")
        }
        assertNull(storedComment(missingParentCommentId))
        assertEquals(before, events(fixture.owner, fixture))
        assertEquals(listOf(reply, parent), comments.list(fixture.owner, fixture.spaceId, fixture.documentId, 0, 10).items)
    }

    @Test
    fun `viewers can discuss but only the author edits and space administrators can delete`() = runTest {
        val fixture = newDocument()
        val author = ctx.registerUser(uniqueUsername("dc-viewer"))
        val peer = ctx.registerUser(uniqueUsername("dc-peer"))
        val admin = ctx.registerUser(uniqueUsername("dc-admin"))
        var policyRevision = 1L
        for ((uid, role) in listOf(author to DocumentSpace.ROLE_VIEWER, peer to DocumentSpace.ROLE_VIEWER, admin to DocumentSpace.ROLE_ADMIN)) {
            policyRevision = documents.upsertGrant(fixture.owner, fixture.spaceId, 1, uid, role, false, policyRevision, id()).policyRevision
        }
        val created = comments.create(author, fixture.spaceId, fixture.documentId, id(), null, "只读成员的评论")
        assertEquals(listOf(created), comments.list(peer, fixture.spaceId, fixture.documentId, 0, 10).items)
        assertFailsWith<DocumentAccessDeniedException> {
            documents.updateDocument(author, fixture.spaceId, fixture.documentId, "不能修改正文", 1)
        }
        for (other in listOf(peer, admin, fixture.owner)) {
            assertFailsWith<DocumentAccessDeniedException> {
                comments.update(other, fixture.spaceId, fixture.documentId, created.commentId, "不能代改", created.revision)
            }
        }
        assertFailsWith<DocumentAccessDeniedException> {
            comments.delete(peer, fixture.spaceId, fixture.documentId, created.commentId, created.revision)
        }
        val updated = comments.update(author, fixture.spaceId, fixture.documentId, created.commentId, "作者修订", created.revision)
        val deleted = comments.delete(admin, fixture.spaceId, fixture.documentId, created.commentId, updated.revision)
        assertTrue(deleted.deleted)
        assertEquals("", deleted.body)
        assertEquals(author, deleted.authorUid)
        for (reader in listOf(fixture.owner, author, peer, admin)) {
            val notifications = events(reader, fixture)
            assertEquals(3, notifications.size, "每个有权限的成员每次变更只收到一个失效事件")
            assertTrue(notifications.all { it.revision == 0L && it.policyRevision == policyRevision })
        }
    }

    @Test
    fun `comment revisions are independent from document revisions and stale RPC writes return 409`() = runTest {
        val fixture = newDocument()
        val created = createComment(fixture, "评论 v1")
        assertEquals(1L, documents.getDocument(fixture.owner, fixture.spaceId, fixture.documentId).revision)
        val bodyUpdate = documents.updateDocument(fixture.owner, fixture.spaceId, fixture.documentId, "正文 v2", 1)
        assertEquals(created, storedComment(created.commentId))
        val updated = comments.update(fixture.owner, fixture.spaceId, fixture.documentId, created.commentId, "评论 v2", created.revision)
        val beforeRetry = events(fixture.owner, fixture)
        assertEquals(2L, updated.revision)
        assertEquals(bodyUpdate, documents.getDocument(fixture.owner, fixture.spaceId, fixture.documentId))
        assertEquals(updated, comments.update(fixture.owner, fixture.spaceId, fixture.documentId, created.commentId, "评论 v2", created.revision))
        assertEquals(beforeRetry, events(fixture.owner, fixture))

        val conflict = RpcDispatcher(ctx.rpcStubRegistry).dispatch(
            uid = fixture.owner,
            deviceId = "comment-test-device",
            deviceCredentialEpoch = 1L,
            sessionId = "comment-test-session",
            invoke = InvokePayload(
                1,
                DocumentCommentRpcContract.SERVICE,
                DocumentCommentRpcContract.M_UPDATE,
                DocumentCommentRpcContract.encodeUpdate(fixture.spaceId, fixture.documentId, created.commentId, "过期写入", created.revision),
            ),
        )
        assertEquals(409, conflict.status)
        assertFailsWith<DocumentRevisionConflictException> {
            comments.delete(fixture.owner, fixture.spaceId, fixture.documentId, created.commentId, created.revision)
        }
        assertEquals(updated, storedComment(created.commentId))
        assertEquals(beforeRetry, events(fixture.owner, fixture))
    }

    @Test
    fun `deletion retains a stable tombstone and replies while retries cannot resurrect content`() = runTest {
        val fixture = newDocument()
        val created = createComment(fixture, "将删除的正文")
        val reply = comments.create(fixture.owner, fixture.spaceId, fixture.documentId, id(), created.commentId, "保留回复")
        val deleted = comments.delete(fixture.owner, fixture.spaceId, fixture.documentId, created.commentId, created.revision)
        assertTrue(deleted.deleted)
        assertEquals("", deleted.body)
        assertEquals(created.revision + 1, deleted.revision)
        assertEquals(created.sequence, deleted.sequence)
        assertEquals(created.createdAt, deleted.createdAt)
        val committedEvents = events(fixture.owner, fixture)
        assertEquals(deleted, comments.delete(fixture.owner, fixture.spaceId, fixture.documentId, created.commentId, created.revision))
        assertEquals(deleted, comments.create(fixture.owner, fixture.spaceId, fixture.documentId, created.commentId, null, "将删除的正文"))
        assertFailsWith<DocumentRevisionConflictException> {
            comments.update(fixture.owner, fixture.spaceId, fixture.documentId, created.commentId, "不应复活", deleted.revision)
        }
        assertEquals(listOf(reply, deleted), comments.list(fixture.owner, fixture.spaceId, fixture.documentId, 0, 10).items)
        assertEquals(committedEvents, events(fixture.owner, fixture))
    }

    @Test
    fun `sequence pagination remains stable across edits tombstones and newer inserts`() = runTest {
        val fixture = newDocument()
        val created = (0 until 6).map { createComment(fixture, "评论 $it") }
        val first = comments.list(fixture.owner, fixture.spaceId, fixture.documentId, 0, 2)
        assertEquals(listOf(created[5], created[4]), first.items)
        assertEquals(created[4].sequence, first.nextBeforeSequence)

        val edited = comments.update(fixture.owner, fixture.spaceId, fixture.documentId, created[1].commentId, "较早评论已编辑", 1)
        val deleted = comments.delete(fixture.owner, fixture.spaceId, fixture.documentId, created[2].commentId, 1)
        val newest = createComment(fixture, "分页之后插入")
        val second = comments.list(fixture.owner, fixture.spaceId, fixture.documentId, first.nextBeforeSequence, 2)
        val third = comments.list(fixture.owner, fixture.spaceId, fixture.documentId, second.nextBeforeSequence, 2)
        assertEquals(listOf(created[3], deleted), second.items)
        assertEquals(created[2].sequence, second.nextBeforeSequence)
        assertEquals(listOf(edited, created[0]), third.items)
        assertEquals(0L, third.nextBeforeSequence)
        val paged = first.items + second.items + third.items
        assertEquals(created.reversed().map { it.commentId }, paged.map { it.commentId })
        assertEquals(created.reversed().map { it.sequence }, paged.map { it.sequence })
        assertEquals(newest, comments.list(fixture.owner, fixture.spaceId, fixture.documentId, 0, 2).items.first())
        assertTrue(comments.list(fixture.owner, fixture.spaceId, fixture.documentId, created[0].sequence, 2).items.isEmpty())
    }

    @Test
    fun `revocation rejects reads new writes and exact retries without sending later comments to the removed reader`() = runTest {
        val fixture = newDocument()
        val reader = ctx.registerUser(uniqueUsername("dc-revoked"))
        val grant = documents.upsertGrant(fixture.owner, fixture.spaceId, 1, reader, DocumentSpace.ROLE_VIEWER, false, 1, id())
        val created = comments.create(reader, fixture.spaceId, fixture.documentId, id(), null, "撤权前评论")
        documents.removeGrant(fixture.owner, fixture.spaceId, 1, reader, grant.policyRevision, id())
        val before = events(reader, fixture)
        val ownerBefore = events(fixture.owner, fixture)
        assertFailsWith<DocumentAccessDeniedException> { comments.list(reader, fixture.spaceId, fixture.documentId, 0, 10) }
        assertFailsWith<DocumentAccessDeniedException> {
            comments.create(reader, fixture.spaceId, fixture.documentId, id(), null, "撤权后新建")
        }
        assertFailsWith<DocumentAccessDeniedException> {
            comments.create(reader, fixture.spaceId, fixture.documentId, created.commentId, null, "撤权前评论")
        }
        assertFailsWith<DocumentAccessDeniedException> {
            comments.update(reader, fixture.spaceId, fixture.documentId, created.commentId, "撤权后编辑", created.revision)
        }
        assertFailsWith<DocumentAccessDeniedException> {
            comments.delete(reader, fixture.spaceId, fixture.documentId, created.commentId, created.revision)
        }
        assertEquals(created, storedComment(created.commentId))
        assertEquals(ownerBefore, events(fixture.owner, fixture))
        createComment(fixture, "只有当前成员可见")
        assertEquals(before, events(reader, fixture))
        assertEquals(ownerBefore.size + 1, events(fixture.owner, fixture).size)
    }

    @Test
    fun `deleting the document rejects comment reads and replay without removing the stored comment`() = runTest {
        val fixture = newDocument()
        val created = createComment(fixture, "随文档隐藏")
        documents.deleteNode(fixture.owner, fixture.spaceId, fixture.documentId, 1, id())
        val before = events(fixture.owner, fixture)
        assertFailsWith<DocumentNotFoundException> { comments.list(fixture.owner, fixture.spaceId, fixture.documentId, 0, 10) }
        assertFailsWith<DocumentNotFoundException> {
            comments.create(fixture.owner, fixture.spaceId, fixture.documentId, created.commentId, null, "随文档隐藏")
        }
        assertFailsWith<DocumentNotFoundException> {
            comments.update(fixture.owner, fixture.spaceId, fixture.documentId, created.commentId, "不能编辑", created.revision)
        }
        assertFailsWith<DocumentNotFoundException> {
            comments.delete(fixture.owner, fixture.spaceId, fixture.documentId, created.commentId, created.revision)
        }
        assertEquals(created, storedComment(created.commentId))
        assertEquals(before, events(fixture.owner, fixture))
    }

    @Test
    fun `rollback removes both comment mutation and all recipient events`() = runTest {
        val fixture = newDocument()
        val reader = ctx.registerUser(uniqueUsername("dc-rollback-reader"))
        documents.upsertGrant(fixture.owner, fixture.spaceId, 1, reader, DocumentSpace.ROLE_VIEWER, false, 1, id())
        val created = createComment(fixture, "已提交")
        val before = listOf(fixture.owner, reader).associateWith { events(it, fixture) }
        val failing = commentService(ExposedPgUnitOfWork(
            database = ctx.database,
            onEventsCommitted = {},
            hooks = PgUnitOfWorkHooks { if (it == PgUnitOfWorkStage.AFTER_EVENT_FLUSH_BEFORE_COMMIT) error("comment rollback") },
        ))
        val rejectedId = id()
        assertFailsWith<IllegalStateException> {
            failing.create(fixture.owner, fixture.spaceId, fixture.documentId, rejectedId, null, "不应提交")
        }
        assertNull(storedComment(rejectedId))
        assertFailsWith<IllegalStateException> {
            failing.update(fixture.owner, fixture.spaceId, fixture.documentId, created.commentId, "不应修改", created.revision)
        }
        assertEquals(created, storedComment(created.commentId))
        assertFailsWith<IllegalStateException> {
            failing.delete(fixture.owner, fixture.spaceId, fixture.documentId, created.commentId, created.revision)
        }
        assertEquals(created, storedComment(created.commentId))
        for ((uid, notifications) in before) assertEquals(notifications, events(uid, fixture))
    }

    @Test
    fun `a lost post commit wake leaves replayable events and retrying the create does not duplicate them`() = runTest {
        val fixture = newDocument()
        val commentId = id()
        val lostWake = commentService(ExposedPgUnitOfWork(
            database = ctx.database,
            onEventsCommitted = {},
            hooks = PgUnitOfWorkHooks { if (it == PgUnitOfWorkStage.AFTER_COMMIT_BEFORE_CALLBACKS) error("comment lost wake") },
        ))
        assertFailsWith<IllegalStateException> {
            lostWake.create(fixture.owner, fixture.spaceId, fixture.documentId, commentId, null, "提交后丢响应")
        }
        val committed = checkNotNull(storedComment(commentId))
        val notifications = events(fixture.owner, fixture)
        assertEquals(1, notifications.size)
        val restarted = commentService(ExposedPgUnitOfWork(ctx.database, onEventsCommitted = {}))
        assertEquals(committed, restarted.create(fixture.owner, fixture.spaceId, fixture.documentId, commentId, null, "提交后丢响应"))
        assertEquals(notifications, events(fixture.owner, fixture))
        val replayed = ctx.syncEventReader.getEventsAfter(fixture.owner, 0, 100)
            .filter { it.notifyType == NotifyType.DOCUMENT_CHANGED.code }
            .map { ProtoCodec.decode(DocumentChangedPayload, requireNotNull(it.payload)) }
            .filter { it.spaceId == fixture.spaceId && it.nodeId == fixture.documentId && it.kind == DocumentChangedPayload.COMMENTS_CHANGED }
        assertEquals(notifications, replayed)
        assertEquals(listOf(committed), restarted.list(fixture.owner, fixture.spaceId, fixture.documentId, 0, 10).items)
    }

    private fun commentService(unitOfWork: PgUnitOfWork = ctx.pgUnitOfWork) =
        DocumentCommentService(ctx.documentRepo, commentRepository, unitOfWork)

    private suspend fun newDocument(): Fixture {
        val owner = ctx.registerUser(uniqueUsername("dc-owner"))
        val space = documents.createSpace(owner, "评论空间", null)
        val document = documents.createDocument(owner, space.spaceId, null, "讨论文档", "正文 v1")
        return Fixture(owner, space.spaceId, document.documentId)
    }

    private suspend fun createComment(fixture: Fixture, body: String): DocumentComment =
        comments.create(fixture.owner, fixture.spaceId, fixture.documentId, id(), null, body)

    private suspend fun storedComment(commentId: String): DocumentComment? = ctx.pgUnitOfWork.read {
        commentRepository.find(transaction, commentId)?.comment
    }

    private fun events(uid: String, fixture: Fixture): List<DocumentChangedPayload> = transaction(ctx.database) {
        SyncEvents.selectAll().where {
            (SyncEvents.uid eq uid) and (SyncEvents.eventType eq NotifyType.DOCUMENT_CHANGED.code)
        }.orderBy(SyncEvents.streamSeq, SortOrder.ASC)
            .map { ProtoCodec.decode(DocumentChangedPayload, it[SyncEvents.payload]) }
            .filter { it.spaceId == fixture.spaceId && it.nodeId == fixture.documentId && it.kind == DocumentChangedPayload.COMMENTS_CHANGED }
    }

    private data class Fixture(val owner: String, val spaceId: String, val documentId: String)
}
