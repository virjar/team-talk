package com.virjar.tk.server.domain.document

import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 单个文档在导出包内的落点；assetLinkPrefix 是该文档目录回退到包根 assets/ 的相对前缀。 */
internal data class DocumentSpaceExportDocument(
    val documentId: String,
    val nodeId: String,
    val title: String,
    val revision: Long,
    val zipPath: String,
    val assetLinkPrefix: String,
)

/** 空间导出的完整计划：树形落点与元数据；正文与资产字节在打包阶段按文档分批读取。 */
internal data class DocumentSpaceExportPlan(
    val spaceId: String,
    val spaceName: String,
    val exportedAtMillis: Long,
    val documents: List<DocumentSpaceExportDocument>,
)

/**
 * 把一个文档空间导出为 markdown 归档包。
 *
 * 结构：`<空间名>/` 下按文档树生成 `<名称>.md`，拥有子文档的节点同时充当同名目录；
 * 全部活跃内嵌资产（图片与文件）收敛到 `<空间名>/assets/<assetId>-<原始文件名>`，
 * markdown 内 `teamtalk-asset://asset/<id>` 链接统一改写为相对路径，解包后离线可用。
 * 缩略图属于派生对象，不参与导出。
 *
 * 授权：空间责任人（steward/OWNER）路径经 [DocumentCapability.EXPORT_SPACE] 能力闸门；
 * 超级管理员入口不做用户授权检查，直接按空间构建。
 */
internal class DocumentSpaceExportService(
    private val repository: DocumentRepository,
    private val unitOfWork: PgUnitOfWork,
    private val objects: DocumentExportObjectSource,
    private val exportGate: DocumentExportGate,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
) {
    private val accessControl = DocumentAccessControl(repository, unitOfWork)

    /** 空间责任人入口：后台开关关闭或非 OWNER（含空间 ADMIN 授权）一律拒绝。 */
    suspend fun buildStewardPlan(actorUid: String, spaceId: String): DocumentSpaceExportPlan? {
        if (!exportGate.isEnabled()) throw DocumentAccessDeniedException("文档空间导出未开放")
        return accessControl.readAuthorized(actorUid, spaceId, DocumentCapability.EXPORT_SPACE) { space, _ ->
            planFromSpace(transaction, space)
        }
    }

    /** 超级管理员入口：跳过用户授权；findSpace 只返回活跃空间。 */
    suspend fun buildAdminPlan(spaceId: String): DocumentSpaceExportPlan? = unitOfWork.read {
        val space = repository.findSpace(transaction, spaceId) ?: return@read null
        planFromSpace(transaction, space)
    }

    /**
     * 按计划打包。正文与资产在打包期间按文档分批短事务读取，内存只持有单个文档；
     * 每个空间的资产文件按 assetId 全局去重，只落盘一次。
     */
    suspend fun writeZip(plan: DocumentSpaceExportPlan, out: OutputStream) {
        val assetsOnDisk = mutableSetOf<String>()
        val missingAssets = mutableListOf<String>()
        // ZipOutputStream 关闭会连带关闭底层流；响应通道只能由 Ktor 关闭。
        val zip = ZipOutputStream(NonClosingOutputStream(out.buffered()))
        zip.use {
            plan.documents.forEach { entry ->
                val document = unitOfWork.read {
                    repository.findDocument(transaction, plan.spaceId, entry.documentId)
                } ?: return@forEach
                val assets = document.assets
                zip.putNextEntry(ZipEntry(entry.zipPath))
                zip.write(
                    rewriteAssetLinks(document.markdown, entry.assetLinkPrefix, assets)
                        .toByteArray(Charsets.UTF_8),
                )
                zip.closeEntry()
                assets.forEach { asset ->
                    // 跨文档去重：同一资产只落盘一次；链接改写永远指向同一个 assets 路径。
                    if (!assetsOnDisk.add(asset.assetId)) return@forEach
                    if (!objects.hasExportObject(asset.attachment.path)) {
                        missingAssets.add(asset.assetId)
                        return@forEach
                    }
                    zip.putNextEntry(ZipEntry("${plan.spaceName}/assets/${assetEntryName(asset)}"))
                    check(objects.copyExportObject(asset.attachment.path, zip)) { "asset vanished during export: ${asset.assetId}" }
                    zip.closeEntry()
                }
            }

            zip.putNextEntry(ZipEntry("${plan.spaceName}/manifest.json"))
            zip.write(manifestJson(plan, assetsOnDisk.size, missingAssets).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    private fun planFromSpace(
        transaction: PgReadTransactionContext,
        space: DocumentSpace,
    ): DocumentSpaceExportPlan {
        val spaceFolder = sanitizePathSegment(space.name) ?: "document-space"
        val usedByDirectory = mutableMapOf<String, MutableSet<String>>()
        fun uniqueName(directory: String, raw: String): String {
            val base = sanitizePathSegment(raw) ?: "untitled"
            val used = usedByDirectory.getOrPut(directory) { mutableSetOf() }
            if (used.add(base.lowercase())) return base
            var counter = 2
            while (!used.add("$base ($counter)".lowercase())) counter++
            return "$base ($counter)"
        }

        val documents = mutableListOf<DocumentSpaceExportDocument>()
        fun walk(parentId: String?, parentDirectory: String) {
            repository.listNodes(transaction, space.spaceId, parentId = parentId).forEach { node ->
                val name = uniqueName(parentDirectory, node.name)
                val childDirectory = "$parentDirectory/$name"
                documents += DocumentSpaceExportDocument(
                    documentId = node.nodeId,
                    nodeId = node.nodeId,
                    title = node.name,
                    revision = node.revision,
                    zipPath = "$childDirectory.md",
                    // 文档所在目录段数 = 斜杠数 + 1；回退 (段数-1) 级即包根 assets。
                    assetLinkPrefix = "../".repeat(childDirectory.count { it == '/' } - 1) + "assets",
                )
                walk(node.nodeId, childDirectory)
            }
        }
        walk(parentId = null, parentDirectory = spaceFolder)
        return DocumentSpaceExportPlan(
            spaceId = space.spaceId,
            spaceName = spaceFolder,
            exportedAtMillis = wallClockMillis(),
            documents = documents,
        )
    }

    private fun rewriteAssetLinks(
        markdown: String,
        assetLinkPrefix: String,
        assets: List<EmbeddedAsset>,
    ): String {
        if (ASSET_URI !in markdown) return markdown
        val byId = assets.associateBy { it.assetId }
        return ASSET_URI_REGEX.replace(markdown) { match ->
            val assetId = match.value.removePrefix(ASSET_URI)
            val asset = byId[assetId] ?: return@replace match.value
            "$assetLinkPrefix/${assetEntryName(asset)}"
        }
    }

    private fun assetEntryName(asset: EmbeddedAsset): String {
        val fileName = sanitizePathSegment(asset.attachment.name) ?: "attachment"
        return "${asset.assetId}-$fileName"
    }

    private fun manifestJson(plan: DocumentSpaceExportPlan, assetCount: Int, missingAssets: List<String>): String {
        val payload = DocumentSpaceExportManifest(
            generator = MANIFEST_GENERATOR,
            spaceId = plan.spaceId,
            spaceName = plan.spaceName,
            exportedAt = plan.exportedAtMillis,
            documentCount = plan.documents.size,
            assetCount = assetCount,
            missingAssetIds = missingAssets,
            documents = plan.documents.map {
                DocumentSpaceExportManifestEntry(
                    nodeId = it.nodeId,
                    documentId = it.documentId,
                    title = it.title,
                    revision = it.revision,
                    path = it.zipPath,
                )
            },
        )
        return manifestJsonFormat.encodeToString(DocumentSpaceExportManifest.serializer(), payload)
    }

    companion object {
        const val ASSET_URI = EmbeddedAsset.URI_PREFIX
        const val MANIFEST_GENERATOR = "teamtalk-document-export/1"
        private val ASSET_URI_REGEX =
            Regex("${Regex.escape(ASSET_URI)}[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val manifestJsonFormat = Json { prettyPrint = true; encodeDefaults = true }

        /** 文件系统/zip 安全段：剥离路径分隔符与控制字符，收口长度。 */
        internal fun sanitizePathSegment(raw: String): String? {
            val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|\u0000-\u001f]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim('.', ' ')
            return cleaned.takeIf { it.isNotEmpty() }?.take(120)
        }
    }
}

/**
 * ZipOutputStream 关闭会连带关闭底层流；响应通道只能由 Ktor 关闭。
 * close 时冲刷但不关闭底层，保证缓冲层（如 BufferedOutputStream）的数据落地。
 */
private class NonClosingOutputStream(private val delegate: OutputStream) : OutputStream() {
    override fun write(b: Int) = delegate.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)
    override fun flush() = delegate.flush()
    override fun close() = delegate.flush()
}

@Serializable
private data class DocumentSpaceExportManifestEntry(
    val nodeId: String,
    val documentId: String,
    val title: String,
    val revision: Long,
    val path: String,
)

@Serializable
private data class DocumentSpaceExportManifest(
    val generator: String,
    val spaceId: String,
    val spaceName: String,
    val exportedAt: Long,
    val documentCount: Int,
    val assetCount: Int,
    val missingAssetIds: List<String>,
    val documents: List<DocumentSpaceExportManifestEntry>,
)
