package com.virjar.tk.protocol

import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.DocumentComment
import com.virjar.tk.protocol.model.DocumentCommentPage
import com.virjar.tk.protocol.model.DocumentContent
import com.virjar.tk.protocol.model.DocumentCreateResult
import com.virjar.tk.protocol.model.DocumentCustodyTransferResult
import com.virjar.tk.protocol.model.DocumentHomeItem
import com.virjar.tk.protocol.model.DocumentMoveResult
import com.virjar.tk.protocol.model.DocumentNode
import com.virjar.tk.protocol.model.DocumentPolicyMutationResult
import com.virjar.tk.protocol.model.DocumentPathSpine
import com.virjar.tk.protocol.model.DocumentRevision
import com.virjar.tk.protocol.model.DocumentRevisionPage
import com.virjar.tk.protocol.model.DocumentRevisionSummary
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceCreateResult
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import com.virjar.tk.protocol.model.DocumentSpaceGrantPage
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.model.DOCUMENT_NODE_SIBLING_ORDER
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.ProtocolCorruptionException
import com.virjar.tk.protocol.ProtocolEncodingException
import com.virjar.tk.protocol.payload.InvokePayload
import com.virjar.tk.protocol.rpc.gen.DocumentRpcContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DocumentModelTest {
    @Test
    fun `million UTF16 unit documents round trip through RPC and current and historical bodies`() {
        val spaceId = "00000000-0000-4000-8000-000000000101"
        val documentId = "00000000-0000-4000-8000-000000000102"
        // 固定产品边界，避免把 UTF-16 长度误改成 UTF-8 字节或 Unicode code point 数。
        val cases = listOf(
            Triple("ASCII", "x".repeat(1_000_000), 1_000_000),
            Triple("中文", "中".repeat(1_000_000), 3_000_000),
            Triple("emoji", "😀".repeat(500_000), 2_000_000),
        )
        cases.forEach { (label, markdown, utf8Bytes) ->
            assertEquals(1_000_000, markdown.length, label)
            assertEquals(utf8Bytes, markdown.encodeToByteArray().size, label)
            val content = DocumentContent(markdown)
            val request = InvokePayload(
                requestId = 1,
                serviceId = "document",
                methodId = 11,
                payload = DocumentRpcContract.encodeUpdateDocument(spaceId, documentId, content, 1L),
            )
            val decodedRequest = ProtoCodec.decode(InvokePayload, ProtoCodec.encode(request))
            ProtoCodec.withPayload(decodedRequest.payload) {
                assertEquals(spaceId, readRequiredString())
                assertEquals(documentId, readRequiredString())
                assertTrue(content == DocumentContent.readFrom(this), "$label RPC body was changed")
                assertEquals(1L, readVarLong())
            }

            val document = Document(
                documentId = documentId,
                spaceId = spaceId,
                title = label,
                markdown = markdown,
                createdBy = "u1",
                createdAt = 1L,
                updatedBy = "u1",
                updatedAt = 1L,
            )
            val revision = DocumentRevision(documentId, 1L, label, markdown, "u1", 1L)
            assertTrue(
                document == ProtoCodec.decode(Document, ProtoCodec.encode(document)),
                "$label current body was changed",
            )
            assertTrue(
                revision == ProtoCodec.decode(DocumentRevision, ProtoCodec.encode(revision)),
                "$label historical body was changed",
            )
            assertFailsWith<IllegalArgumentException>("$label must reject one extra UTF-16 unit") {
                DocumentRpcContract.encodeUpdateDocument(
                    spaceId, documentId, DocumentContent(markdown + "x"), 1L,
                )
            }
        }
    }

    @Test
    fun `document sibling order is immutable creation time then node id`() {
        val base = DocumentNode(
            nodeId = "node-b",
            spaceId = "space-1",
            parentId = null,
            hasChildren = false,
            name = "Alpha",
            revision = 1,
            createdBy = "u1",
            createdAt = 20,
            updatedBy = "u1",
            updatedAt = 20,
        )
        val older = base.copy(nodeId = "node-z", name = "Zulu", createdAt = 10)
        val sameMillisecondEarlierId = base.copy(nodeId = "node-a", name = "Zulu")
        val renamed = base.copy(name = "000", revision = 2, updatedAt = 30)

        assertEquals(
            listOf(older, sameMillisecondEarlierId, base),
            listOf(base, older, sameMillisecondEarlierId).sortedWith(DOCUMENT_NODE_SIBLING_ORDER),
        )
        assertEquals(
            listOf(sameMillisecondEarlierId, renamed),
            listOf(renamed, sameMillisecondEarlierId).sortedWith(DOCUMENT_NODE_SIBLING_ORDER),
        )
    }

    @Test
    fun `document models round trip`() {
        val space = DocumentSpace(
            spaceId = "space-1",
            name = "产品空间",
            description = "跨部门产品资料",
            myRole = DocumentSpace.ROLE_EDITOR,
            createdBy = "u1",
            createdAt = 1,
            updatedAt = 2,
            ownerPrincipalType = DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
            ownerPrincipalId = "unit-1",
            stewardUid = "u2",
            custodyRevision = 3,
            policyRevision = 4,
        )
        assertEquals(space, ProtoCodec.decode(DocumentSpace, ProtoCodec.encode(space)))
        assertEquals(
            DocumentSpaceCreateResult(space.spaceId, space),
            ProtoCodec.decode(
                DocumentSpaceCreateResult,
                ProtoCodec.encode(DocumentSpaceCreateResult(space.spaceId, space)),
            ),
        )
        assertEquals(
            DocumentSpaceCreateResult(space.spaceId, null),
            ProtoCodec.decode(
                DocumentSpaceCreateResult,
                ProtoCodec.encode(DocumentSpaceCreateResult(space.spaceId, null)),
            ),
        )

        val custody = DocumentCustodyTransferResult(
            spaceId = space.spaceId,
            ownerPrincipalType = space.ownerPrincipalType,
            ownerPrincipalId = space.ownerPrincipalId,
            stewardUid = space.stewardUid,
            custodyRevision = space.custodyRevision,
        )
        assertEquals(custody, ProtoCodec.decode(DocumentCustodyTransferResult, ProtoCodec.encode(custody)))

        val policy = DocumentPolicyMutationResult(
            spaceId = space.spaceId,
            policyRevision = space.policyRevision,
            effectiveRole = DocumentSpace.ROLE_ADMIN,
        )
        assertEquals(policy, ProtoCodec.decode(DocumentPolicyMutationResult, ProtoCodec.encode(policy)))

        val grant = DocumentSpaceGrant("space-1", DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT, "unit-1", DocumentSpace.ROLE_EDITOR, true, "产品部")
        assertEquals(grant, ProtoCodec.decode(DocumentSpaceGrant, ProtoCodec.encode(grant)))
        val grantPage = DocumentSpaceGrantPage(listOf(grant))
        assertEquals(grantPage, ProtoCodec.decode(DocumentSpaceGrantPage, ProtoCodec.encode(grantPage)))

        val node = DocumentNode("doc-parent", "space-1", null, true, "需求", "方案入口", 1, "u1", 1, "u1", 1)
        assertEquals(node, ProtoCodec.decode(DocumentNode, ProtoCodec.encode(node)))
        val moveResult = DocumentMoveResult(node, listOf("root", "parent"))
        assertEquals(moveResult, ProtoCodec.decode(DocumentMoveResult, ProtoCodec.encode(moveResult)))
        val childNode = node.copy(nodeId = "doc-child", parentId = node.nodeId, hasChildren = false)
        val spine = DocumentPathSpine(listOf(node, childNode))
        assertEquals(spine, ProtoCodec.decode(DocumentPathSpine, ProtoCodec.encode(spine)))

        val asset = EmbeddedAsset(
            assetId = "00000000-0000-4000-8000-000000000301",
            attachment = Attachment("u1/design.png", "design.png", "image/png", 12L),
            thumbnail = Attachment("u1/design-thumb.jpg", "thumb.jpg", "image/jpeg", 4L),
            width = 640,
            height = 480,
        )
        val markdown = "# v1\n![设计图](${EmbeddedAsset.uri(asset.assetId)})"
        val document = Document(
            "doc-1",
            "space-1",
            "folder-2",
            "设计说明",
            markdown,
            2,
            "u1",
            10,
            "u2",
            20,
            listOf("folder-1", "folder-2"),
            listOf(asset),
        )
        assertEquals(document, ProtoCodec.decode(Document, ProtoCodec.encode(document)))
        assertEquals(
            DocumentCreateResult(document.documentId, document),
            ProtoCodec.decode(
                DocumentCreateResult,
                ProtoCodec.encode(DocumentCreateResult(document.documentId, document)),
            ),
        )
        assertEquals(
            DocumentCreateResult(document.documentId, null),
            ProtoCodec.decode(
                DocumentCreateResult,
                ProtoCodec.encode(DocumentCreateResult(document.documentId, null)),
            ),
        )

        val revision = DocumentRevision("doc-1", 1, "设计说明", markdown, "u1", 10, listOf(asset))
        assertEquals(revision, ProtoCodec.decode(DocumentRevision, ProtoCodec.encode(revision)))

        val revisionSummary = DocumentRevisionSummary("doc-1", 1, "设计说明", 4, "u1", 10)
        assertEquals(revisionSummary, ProtoCodec.decode(DocumentRevisionSummary, ProtoCodec.encode(revisionSummary)))

        val revisionPage = DocumentRevisionPage(listOf(revisionSummary), nextBeforeRevision = 1)
        assertEquals(revisionPage, ProtoCodec.decode(DocumentRevisionPage, ProtoCodec.encode(revisionPage)))

        val homeItem = DocumentHomeItem(
            documentId = "doc-1",
            spaceId = "space-1",
            spaceName = "产品空间",
            title = "设计说明",
            excerpt = "方案摘要",
            createdBy = "u1",
            creatorName = "张三",
            createdAt = 10,
            updatedAt = 20,
            accessedAt = 30,
        )
        assertEquals(homeItem, ProtoCodec.decode(DocumentHomeItem, ProtoCodec.encode(homeItem)))
    }

    @Test
    fun `document grant page rejects an oversized count before allocation`() {
        val malformed = ProtoCodec.encodePayload {
            writeVarInt(DocumentSpaceGrant.MAX_GRANTS_PER_SPACE + 1)
        }

        assertFailsWith<ProtocolCorruptionException> {
            ProtoCodec.decode(DocumentSpaceGrantPage, malformed)
        }
    }

    @Test
    fun `document grant page rejects invalid roles and duplicate principals`() {
        val malformedRole = ProtoCodec.encodePayload {
            writeVarInt(1)
            writeString("space-1")
            writeVarInt(DocumentSpaceGrant.PRINCIPAL_USER)
            writeString("user-1")
            writeVarInt(DocumentSpace.ROLE_OWNER)
            writeBoolean(false)
            writeString("张三")
        }
        assertFailsWith<ProtocolCorruptionException> {
            ProtoCodec.decode(DocumentSpaceGrantPage, malformedRole)
        }

        val duplicate = DocumentSpaceGrant(
            spaceId = "space-1",
            principalType = DocumentSpaceGrant.PRINCIPAL_USER,
            principalId = "user-1",
            role = DocumentSpace.ROLE_VIEWER,
        )
        assertFailsWith<ProtocolEncodingException> {
            ProtoCodec.encode(DocumentSpaceGrantPage(listOf(duplicate, duplicate)))
        }
    }

    @Test
    fun `document policy result rejects invalid revisions and roles at the wire boundary`() {
        assertFailsWith<ProtocolEncodingException> {
            DocumentPolicyMutationResult("space-1", 0, DocumentSpace.ROLE_VIEWER)
        }
        val malformedRole = ProtoCodec.encodePayload {
            writeString("space-1")
            writeVarLong(1)
            writeVarInt(DocumentSpace.ROLE_OWNER + 1)
        }
        assertFailsWith<ProtocolCorruptionException> {
            ProtoCodec.decode(DocumentPolicyMutationResult, malformedRole)
        }
    }

    @Test
    fun `document node rejects malformed hasChildren boolean`() {
        val malformed = ProtoCodec.encodePayload {
            writeString("doc-1")
            writeString("space-1")
            writeString(null)
            writeByte(2)
        }

        assertFailsWith<ProtocolCorruptionException> {
            ProtoCodec.decode(DocumentNode, malformed)
        }
    }

    @Test
    fun `document path spine rejects a parent which denies its encoded child`() {
        val root = DocumentNode(
            "root",
            "space-1",
            null,
            false,
            "根文档",
            revision = 1,
            createdBy = "u1",
            createdAt = 1,
            updatedBy = "u1",
            updatedAt = 1,
        )
        val child = root.copy(nodeId = "child", parentId = root.nodeId)

        assertFailsWith<ProtocolEncodingException> {
            DocumentPathSpine(listOf(root, child))
        }
        val malformed = ProtoCodec.encodePayload {
            writeVarInt(2)
            root.writeTo(this)
            child.writeTo(this)
        }
        assertFailsWith<ProtocolCorruptionException> {
            ProtoCodec.decode(DocumentPathSpine, malformed)
        }
    }

    @Test
    fun `document revision page rejects an unbounded collection`() {
        val malformed = ProtoCodec.encodePayload {
            writeVarInt(DocumentRevisionPage.MAX_PAGE_SIZE + 1)
            writeVarLong(DocumentRevisionPage.END_CURSOR)
        }

        assertFailsWith<ProtocolCorruptionException> {
            ProtoCodec.decode(DocumentRevisionPage, malformed)
        }
    }

    @Test
    fun `document path spine rejects broken identity and parent chains`() {
        val root = DocumentNode("root", "space-1", null, true, "Root", "", 1, "u1", 1, "u1", 1)
        val child = root.copy(nodeId = "child", parentId = root.nodeId, hasChildren = false)

        assertFailsWith<ProtocolEncodingException> { DocumentPathSpine(emptyList()) }
        assertFailsWith<ProtocolEncodingException> {
            DocumentPathSpine(listOf(root.copy(parentId = "outside")))
        }
        assertFailsWith<ProtocolEncodingException> {
            DocumentPathSpine(listOf(root, child.copy(spaceId = "space-2")))
        }
        assertFailsWith<ProtocolEncodingException> {
            DocumentPathSpine(listOf(root, child.copy(parentId = "missing")))
        }
        assertFailsWith<ProtocolEncodingException> {
            DocumentPathSpine(listOf(root, child, child.copy(parentId = child.nodeId)))
        }
    }

    @Test
    fun `document path spine rejects an oversized wire count before allocation`() {
        val malformed = ProtoCodec.encodePayload {
            writeVarInt(DocumentPathSpine.MAX_NODES + 1)
        }

        assertFailsWith<ProtocolCorruptionException> {
            ProtoCodec.decode(DocumentPathSpine, malformed)
        }
    }

    @Test
    fun `document comments preserve replies revisions and deletion tombstones`() {
        val comment = sampleComment()
        listOf(
            comment,
            comment.copy(replyToId = "00000000-0000-4000-8000-000000000104", body = "回复\n第二行", revision = 8),
            comment.copy(body = "", revision = 9, deleted = true),
        ).forEach { value ->
            assertEquals(value, ProtoCodec.decode(DocumentComment, ProtoCodec.encode(value)))
        }
    }

    @Test
    fun `document comment body limits count UTF16 units for ASCII Chinese and emoji`() {
        listOf(
            "x".repeat(DocumentComment.MAX_BODY_LENGTH),
            "中".repeat(DocumentComment.MAX_BODY_LENGTH),
            "😀".repeat(DocumentComment.MAX_BODY_LENGTH / 2),
        ).forEach { body ->
            assertEquals(DocumentComment.MAX_BODY_LENGTH, body.length)
            val comment = sampleComment().copy(body = body)
            assertEquals(comment, ProtoCodec.decode(DocumentComment, ProtoCodec.encode(comment)))
            assertFailsWith<IllegalArgumentException> { ProtoCodec.encode(comment.copy(body = body + "x")) }
            assertFailsWith<IllegalArgumentException> {
                ProtoCodec.decode(DocumentComment, rawComment(comment.copy(body = body + "x")))
            }
        }
    }

    @Test
    fun `document comment decoding bounds identifiers author fields and encoded body bytes`() {
        val comment = sampleComment()
        listOf(
            comment.copy(commentId = "x".repeat(37)),
            comment.copy(spaceId = "x".repeat(37)),
            comment.copy(documentId = "x".repeat(37)),
            comment.copy(replyToId = "x".repeat(37)),
            comment.copy(authorUid = "x".repeat(65)),
            comment.copy(authorName = "x".repeat(513)),
            comment.copy(body = "x".repeat(DocumentComment.MAX_BODY_LENGTH * 4 + 1)),
        ).forEach { oversized ->
            assertFailsWith<ProtocolCorruptionException> { ProtoCodec.decode(DocumentComment, rawComment(oversized)) }
        }
    }

    @Test
    fun `document comment pages preserve terminal and continuation cursors at the page limit`() {
        val full = DocumentCommentPage(
            List(DocumentCommentPage.MAX_PAGE_SIZE) { index ->
                sampleComment().copy(commentId = index.toString().padStart(36, '0'), sequence = 1_000L - index)
            },
            901,
        )
        listOf(DocumentCommentPage(emptyList(), 0), DocumentCommentPage(listOf(sampleComment()), 0), full).forEach { page ->
            assertEquals(page, ProtoCodec.decode(DocumentCommentPage, ProtoCodec.encode(page)))
        }
        assertFailsWith<IllegalArgumentException> { ProtoCodec.encode(full.copy(items = full.items + sampleComment())) }
    }

    @Test
    fun `document comment pages reject oversized counts and truncated collections before allocation`() {
        for (count in listOf(DocumentCommentPage.MAX_PAGE_SIZE + 1, Int.MAX_VALUE, DocumentCommentPage.MAX_PAGE_SIZE)) {
            val malformed = ProtoCodec.encodePayload { writeVarInt(count) }
            assertFailsWith<ProtocolCorruptionException> { ProtoCodec.decode(DocumentCommentPage, malformed) }
        }
        val truncatedItem = ProtoCodec.encodePayload {
            writeVarInt(2)
            sampleComment().writeTo(this)
            writeVarLong(0)
        }
        assertFailsWith<ProtocolCorruptionException> { ProtoCodec.decode(DocumentCommentPage, truncatedItem) }
    }

    @Test
    fun `document comment and page reject truncation trailing bytes and malformed deletion flags`() {
        val comment = ProtoCodec.encode(sampleComment())
        assertFailsWith<ProtocolCorruptionException> { ProtoCodec.decode(DocumentComment, comment.copyOf(comment.size - 1)) }
        assertFailsWith<ProtocolCorruptionException> { ProtoCodec.decode(DocumentComment, comment + byteArrayOf(0)) }
        val invalidFlag = comment.copyOf().also { it[it.lastIndex] = 2 }
        assertFailsWith<ProtocolCorruptionException> { ProtoCodec.decode(DocumentComment, invalidFlag) }
        val page = ProtoCodec.encode(DocumentCommentPage(listOf(sampleComment()), 0))
        assertFailsWith<ProtocolCorruptionException> { ProtoCodec.decode(DocumentCommentPage, page.copyOf(page.size - 1)) }
        assertFailsWith<ProtocolCorruptionException> { ProtoCodec.decode(DocumentCommentPage, page + byteArrayOf(0)) }
    }

    private fun sampleComment() = DocumentComment(
        commentId = "00000000-0000-4000-8000-000000000101",
        spaceId = "00000000-0000-4000-8000-000000000102",
        documentId = "00000000-0000-4000-8000-000000000103",
        sequence = 900,
        authorUid = "comment-author",
        authorName = "评论作者",
        replyToId = null,
        body = "待讨论内容",
        revision = 7,
        createdAt = 1_000,
        updatedAt = 2_000,
        deleted = false,
    )

    /** 绕过模型写入校验，为解码预算构造真实 wire 字段。 */
    private fun rawComment(comment: DocumentComment): ByteArray = ProtoCodec.encodePayload {
        writeString(comment.commentId)
        writeString(comment.spaceId)
        writeString(comment.documentId)
        writeVarLong(comment.sequence)
        writeString(comment.authorUid)
        writeString(comment.authorName)
        writeString(comment.replyToId)
        writeString(comment.body)
        writeVarLong(comment.revision)
        writeVarLong(comment.createdAt)
        writeVarLong(comment.updatedAt)
        writeBoolean(comment.deleted)
    }
}
