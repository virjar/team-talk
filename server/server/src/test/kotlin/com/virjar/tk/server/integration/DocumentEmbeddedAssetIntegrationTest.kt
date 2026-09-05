package com.virjar.tk.server.integration

import com.virjar.tk.server.domain.attachment.AttachmentLifecycleGate
import com.virjar.tk.server.domain.attachment.AttachmentReferences
import com.virjar.tk.server.domain.attachment.AttachmentRetentionConfig
import com.virjar.tk.server.domain.attachment.AttachmentRetentionService
import com.virjar.tk.server.infra.db.repository.ExposedDocumentAttachmentReferences
import com.virjar.tk.protocol.model.DocumentContent
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import com.virjar.tk.protocol.model.EmbeddedAsset
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentEmbeddedAssetIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `revision manifests retain history while live ACL and terminal GC govern every object`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("asset-space-owner"))
        val uploader = ctx.registerUser(uniqueUsername("asset-space-editor"))
        val space = ctx.documentService.createSpace(owner, "内嵌资产历史", null)
        ctx.documentService.upsertGrant(
            actorUid = owner,
            spaceId = space.spaceId,
            principalType = DocumentSpaceGrant.PRINCIPAL_USER,
            principalId = uploader,
            role = DocumentSpace.ROLE_EDITOR,
            includeDescendants = false,
            expectedPolicyRevision = ctx.currentDocumentPolicyRevision(space.spaceId),
            operationId = UUID.randomUUID().toString(),
        )

        val firstMain = store(uploader, "first.png", "image/png", "first-image")
        val firstThumbnail = store(uploader, "first-thumb.jpg", "image/jpeg", "first-thumb")
        val secondMain = store(uploader, "second.png", "image/png", "second-image")
        val firstAsset = EmbeddedAsset(
            assetId = UUID.randomUUID().toString(),
            attachment = requireNotNull(ctx.fileStore.getAttachment(firstMain)),
            thumbnail = requireNotNull(ctx.fileStore.getAttachment(firstThumbnail)),
            width = 640,
            height = 480,
        )
        val secondAsset = EmbeddedAsset(
            assetId = UUID.randomUUID().toString(),
            attachment = requireNotNull(ctx.fileStore.getAttachment(secondMain)),
            width = 1280,
            height = 720,
        )
        val documentId = UUID.randomUUID().toString()
        val created = ctx.documentService.createDocument(
            actorUid = uploader,
            documentId = documentId,
            spaceId = space.spaceId,
            parentId = null,
            title = "资产文档",
            content = DocumentContent(
                markdown = "![第一张](${EmbeddedAsset.uri(firstAsset.assetId)})",
                assets = listOf(firstAsset),
            ),
        )
        assertEquals(listOf(firstAsset), created.assets)

        val updated = ctx.documentService.updateDocument(
            actorUid = uploader,
            spaceId = space.spaceId,
            documentId = documentId,
            content = DocumentContent(
                markdown = "![第二张](${EmbeddedAsset.uri(secondAsset.assetId)})",
                assets = listOf(secondAsset),
            ),
            expectedRevision = created.revision,
        )
        assertEquals(2L, updated.revision)
        assertEquals(listOf(secondAsset), updated.assets)
        assertEquals(
            listOf(secondAsset),
            ctx.documentService.getDocument(owner, space.spaceId, documentId).assets,
        )
        assertEquals(
            listOf(firstAsset),
            ctx.documentService.getRevision(owner, space.spaceId, documentId, 1).assets,
        )
        assertEquals(
            listOf(secondAsset),
            ctx.documentService.getRevision(owner, space.spaceId, documentId, 2).assets,
        )

        val documentReferences = ExposedDocumentAttachmentReferences(ctx.database)
        val allPaths = setOf(firstMain, firstThumbnail, secondMain)
        assertEquals(allPaths, documentReferences.getReferencedPaths(allPaths))
        allPaths.forEach { path -> assertNotNull(ctx.fileStore.getMeta(path)?.businessBoundAt) }

        val retention = AttachmentRetentionService(
            files = ctx.fileStore,
            references = object : AttachmentReferences {
                override fun getChatIds(path: String): Set<String> = emptySet()
                override fun getReferencedPaths(paths: Set<String>): Set<String> =
                    documentReferences.getReferencedPaths(paths)
            },
            lifecycle = AttachmentLifecycleGate(),
            config = AttachmentRetentionConfig(
                unreferencedTtlMillis = 1,
                pageSize = 16,
                maxPagesPerRun = 2,
            ),
            wallClockMillis = { Long.MAX_VALUE / 4 },
        )
        assertEquals(0, retention.cleanupExpiredUnreferenced())
        assertTrue(ctx.attachmentAccess.canRead(owner, firstMain), "historical revision follows live space READ")
        assertTrue(ctx.attachmentAccess.canRead(owner, firstThumbnail))

        ctx.documentService.removeGrant(
            actorUid = owner,
            spaceId = space.spaceId,
            principalType = DocumentSpaceGrant.PRINCIPAL_USER,
            principalId = uploader,
            expectedPolicyRevision = ctx.currentDocumentPolicyRevision(space.spaceId),
            operationId = UUID.randomUUID().toString(),
        )
        assertFalse(
            ctx.attachmentAccess.canRead(uploader, firstMain),
            "the original uploader must lose the bypass after its document grant is revoked",
        )

        val uploaderSpace = ctx.documentService.createSpace(uploader, "重绑隔离验证", null)
        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.createDocument(
                actorUid = uploader,
                documentId = UUID.randomUUID().toString(),
                spaceId = uploaderSpace.spaceId,
                parentId = null,
                title = "不得重绑的旧资产",
                content = DocumentContent(
                    markdown = "![旧资产](${EmbeddedAsset.uri(firstAsset.assetId)})",
                    assets = listOf(firstAsset),
                ),
            )
        }

        ctx.documentService.deleteNode(
            actorUid = owner,
            spaceId = space.spaceId,
            nodeId = documentId,
            expectedRevision = updated.revision,
            operationId = UUID.randomUUID().toString(),
        )
        assertEquals(emptySet(), documentReferences.getReferencedPaths(allPaths))
        assertFalse(ctx.attachmentAccess.canRead(uploader, secondMain), "bound objects never become staging again")
        assertEquals(3, retention.cleanupExpiredUnreferenced())
        allPaths.forEach { path -> assertNull(ctx.fileStore.getAttachment(path)) }
    }

    private fun store(uid: String, name: String, contentType: String, body: String): String {
        val source = File.createTempFile("document-asset-", ".tmp").apply { writeText(body) }
        return try {
            ctx.fileStore.store(uid, name, contentType, source)
        } finally {
            source.delete()
        }
    }
}
