package com.virjar.tk.server.integration

import com.virjar.tk.protocol.model.DocumentContent
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.server.domain.document.DocumentAccessDeniedException
import com.virjar.tk.server.domain.document.DocumentExportPolicy
import com.virjar.tk.server.domain.document.DocumentSpaceExportService
import com.virjar.tk.server.infra.db.repository.ExposedDocumentAttachmentReferences
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentSpaceExportIntegrationTest {
    companion object {
        @JvmField @RegisterExtension val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    private fun exportService() = DocumentSpaceExportService(
        repository = ctx.documentRepo,
        unitOfWork = ctx.pgUnitOfWork,
        fileStore = ctx.fileStore,
        exportPolicy = exportPolicy(),
    )

    private fun exportPolicy() = DocumentExportPolicy(ctx.database)

    private fun store(uid: String, name: String, contentType: String, body: String): String {
        val source = File.createTempFile("export-asset-", ".tmp").apply { writeText(body) }
        return try {
            ctx.fileStore.store(uid, name, contentType, source)
        } finally {
            source.delete()
        }
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return entries
    }

    @Test
    fun `steward export builds folder tree rewrites asset links and packs asset bytes`() = runTest {
        val steward = ctx.registerUser(uniqueUsername("export-steward"))
        val member = ctx.registerUser(uniqueUsername("export-member"))
        val space = ctx.documentService.createSpace(steward, "团队手册: v1", null)

        val imagePath = store(steward, "架构图.png", "image/png", "PNG-BYTES-图片")
        val references = ExposedDocumentAttachmentReferences(ctx.database)
        val imageAsset = EmbeddedAsset(
            assetId = UUID.randomUUID().toString(),
            attachment = requireNotNull(ctx.fileStore.getAttachment(imagePath)),
        )
        val child = ctx.documentService.createDocument(
            actorUid = steward,
            documentId = UUID.randomUUID().toString(),
            spaceId = space.spaceId,
            parentId = null,
            title = "架构总览",
            content = DocumentContent(
                "# 架构\n![架构图](${EmbeddedAsset.uri(imageAsset.assetId)})",
                listOf(imageAsset),
            ),
        )
        val grandChild = ctx.documentService.createDocument(
            actorUid = steward, documentId = UUID.randomUUID().toString(),
            spaceId = space.spaceId, parentId = child.documentId,
            title = "部署细节", content = DocumentContent("无图正文", emptyList()),
        )
        ctx.documentService.upsertGrant(
            actorUid = steward,
            spaceId = space.spaceId,
            principalType = DocumentSpaceGrant.PRINCIPAL_USER,
            principalId = member,
            role = DocumentSpace.ROLE_ADMIN,
            includeDescendants = false,
            expectedPolicyRevision = ctx.currentDocumentPolicyRevision(space.spaceId),
            operationId = UUID.randomUUID().toString(),
        )

        val policy = exportPolicy()
        policy.setEnabled(true, "fixture-admin")
        assertTrue(policy.isEnabled())

        val service = exportService()
        // 空间 ADMIN 授权不是唯一管理员：无 EXPORT_SPACE 能力。
        assertFailsWith<DocumentAccessDeniedException> {
            service.buildStewardPlan(member, space.spaceId)
        }
        // 超级管理员入口不受用户授权约束。
        val adminPlan = assertNotNull(service.buildAdminPlan(space.spaceId))
        assertEquals(listOf("团队手册 v1/架构总览.md", "团队手册 v1/架构总览/部署细节.md"),
            adminPlan.documents.map { it.zipPath })
        assertEquals("assets", adminPlan.documents[0].assetLinkPrefix)
        assertEquals("../assets", adminPlan.documents[1].assetLinkPrefix)

        val plan = assertNotNull(service.buildStewardPlan(steward, space.spaceId))
        val zipBytes = ByteArrayOutputStream().also { service.writeZip(plan, it) }.toByteArray()
        val entries = unzip(zipBytes)

        assertEquals(
            setOf(
                "团队手册 v1/manifest.json",
                "团队手册 v1/架构总览.md",
                "团队手册 v1/架构总览/部署细节.md",
                "团队手册 v1/assets/${imageAsset.assetId}-架构图.png",
            ),
            entries.keys,
        )
        val exportedDoc = entries.getValue("团队手册 v1/架构总览.md").decodeToString()
        assertTrue("![架构图](assets/${imageAsset.assetId}-架构图.png)" in exportedDoc, exportedDoc)
        assertFalse(exportedDoc.contains("teamtalk-asset://"))
        assertEquals(
            "无图正文",
            entries.getValue("团队手册 v1/架构总览/部署细节.md").decodeToString(),
        )
        assertEquals("PNG-BYTES-图片", entries.getValue("团队手册 v1/assets/${imageAsset.assetId}-架构图.png").decodeToString())
        val manifest = entries.keys.first { it.endsWith("manifest.json") }
        assertTrue(entries.getValue(manifest).decodeToString().contains("\"generator\": \"teamtalk-document-export/1\""))

        // 释放资产保留引用，避免测试环境的保留回收干扰断言。
        references.getReferencedPaths(setOf(imagePath))
    }

    @Test
    fun `sibling name collisions are deduplicated and disabled policy refuses steward export`() = runTest {
        val steward = ctx.registerUser(uniqueUsername("export-dedup-steward"))
        val space = ctx.documentService.createSpace(steward, "重复名", null)
        ctx.documentService.createDocument(steward, UUID.randomUUID().toString(), space.spaceId, null, "同名", "第一")
        ctx.documentService.createDocument(steward, UUID.randomUUID().toString(), space.spaceId, null, "同名?", "第二")
        ctx.documentService.createDocument(steward, UUID.randomUUID().toString(), space.spaceId, null, "同名", "第三")

        val policy = exportPolicy()
        policy.setEnabled(false, "fixture-admin")
        assertTrue(!policy.isEnabled())
        val service = exportService()
        // 开关关闭时责任人入口被整体停用。
        assertFailsWith<DocumentAccessDeniedException> {
            service.buildStewardPlan(steward, space.spaceId)
        }
        // 开关关闭只影响责任人入口；超级管理员仍可按空间构建。
        val plan = assertNotNull(service.buildAdminPlan(space.spaceId))
        assertEquals(
            listOf("重复名/同名.md", "重复名/同名 (2).md", "重复名/同名 (3).md"),
            plan.documents.map { it.zipPath },
        )
        val zipBytes = ByteArrayOutputStream().also { service.writeZip(plan, it) }.toByteArray()
        val entryNames = unzip(zipBytes).keys
        assertTrue(entryNames.containsAll(listOf("重复名/同名.md", "重复名/同名 (2).md", "重复名/同名 (3).md")),
            "actual entries: $entryNames")
        // 服务层开关闭合后责任人入口恢复可用（模拟管理员重新打开）。
        policy.setEnabled(true, "fixture-admin")
        assertNotNull(service.buildStewardPlan(steward, space.spaceId))
    }

    private fun assertFalse(b: Boolean) = kotlin.test.assertFalse(b)
}
