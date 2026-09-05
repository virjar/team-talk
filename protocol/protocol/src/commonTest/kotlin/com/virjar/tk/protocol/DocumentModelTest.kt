package com.virjar.tk.protocol

import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.Document
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DocumentModelTest {
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
}
