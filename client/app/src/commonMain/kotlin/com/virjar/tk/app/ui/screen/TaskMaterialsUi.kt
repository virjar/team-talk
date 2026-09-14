package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.virjar.tk.app.navigation.feature.task.TaskFeature
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportEvent
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportEventSink
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportGateway
import com.virjar.tk.app.ui.component.FileCardWithDownload
import com.virjar.tk.app.ui.component.FileDownloadController
import com.virjar.tk.app.ui.component.rich.PendingAssetJob
import com.virjar.tk.app.ui.component.rich.PendingAssetJobState
import com.virjar.tk.app.ui.theme.Tk
import com.virjar.tk.protocol.body.EmbeddedAssetPresentation
import com.virjar.tk.protocol.body.OfficeRefBody
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.TaskOptions
import kotlinx.coroutines.CancellationException

/** 平台拥有上传和下载资源；任务页面只选择材料并保存已完成上传的描述符。 */
class TaskMaterialsUi(
    val imports: EmbeddedAssetImportGateway,
    val fileDownloads: FileDownloadController,
    val loadDocuments: suspend () -> List<OfficeRefBody>,
    val openDocument: suspend (OfficeRefBody) -> Unit,
)

/** 与聊天引用使用相同的最近文档查询，读取失败保留为错误，不能伪装成空候选。 */
suspend fun TaskFeature.loadMaterialDocumentCandidates(): List<OfficeRefBody> =
    session.documentRepo.listRecentDocuments(20).getOrThrow().map { item ->
        OfficeRefBody(OfficeRefBody.REF_TYPE_DOCUMENT, item.spaceId, item.documentId,
            item.title.ifBlank { "未命名文档" }, "文档")
    }

suspend fun TaskFeature.openMaterialDocument(reference: OfficeRefBody, onOpen: () -> Unit) {
    require(reference.isDocument)
    session.documentRepo.getDocument(reference.spaceId, reference.targetId).getOrThrow()
    onOpen()
}

@Composable
internal fun TaskMaterialsEditor(
    editorKey: String,
    documentRefs: List<OfficeRefBody>,
    attachments: List<Attachment>,
    onDocumentRefsChange: (List<OfficeRefBody>) -> Unit,
    onAddAttachment: (Attachment) -> Unit,
    onRemoveAttachment: (Attachment) -> Unit,
    onUploadingChange: (Boolean) -> Unit,
    materials: TaskMaterialsUi,
    enabled: Boolean = true,
) {
    var choosingDocument by remember(editorKey) { mutableStateOf(false) }
    val jobs = remember(editorKey) { mutableStateMapOf<String, Pair<PendingAssetJob, String>>() }
    val completedJobs = remember(editorKey) { mutableSetOf<String>() }
    val addAttachment by rememberUpdatedState(onAddAttachment)
    val reportUploading by rememberUpdatedState(onUploadingChange)
    DisposableEffect(materials.imports, editorKey) {
        var disposed = false
        val registration = materials.imports.bind("task:$editorKey", EmbeddedAssetImportEventSink { event ->
            if (!disposed) {
                when (event) {
                    is EmbeddedAssetImportEvent.Ready -> {
                        jobs.remove(event.job.jobId)
                        if (completedJobs.add(event.job.jobId)) addAttachment(event.asset.attachment)
                    }
                    is EmbeddedAssetImportEvent.StateChanged -> {
                        if (event.job.state == PendingAssetJobState.CANCELLED) jobs.remove(event.job.jobId)
                        else if (event.job.jobId !in completedJobs) {
                            jobs[event.job.jobId] = event.job to
                                (event.placement?.label ?: jobs[event.job.jobId]?.second ?: "附件")
                        }
                    }
                }
                // 失败材料也需要显式重试或移除，不能保存一个看似完整的任务。
                reportUploading(jobs.isNotEmpty())
            }
        })
        onDispose {
            disposed = true
            registration.close()
            jobs.keys.toList().forEach(materials.imports::cancel)
            reportUploading(false)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Tk.spacing.sm), modifier = Modifier.testTag("task.materials.editor")) {
        Text("任务材料", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(Tk.spacing.sm)) {
            OutlinedButton(onClick = { choosingDocument = true }, enabled = enabled && documentRefs.size < TaskOptions.MAX_MATERIALS,
                modifier = Modifier.testTag("task.materials.addDocument")) { Text("关联文档") }
            OutlinedButton(onClick = { materials.imports.select(EmbeddedAssetPresentation.FILE) },
                enabled = enabled && attachments.size + jobs.size < TaskOptions.MAX_MATERIALS,
                modifier = Modifier.testTag("task.materials.addFile")) { Text("添加附件") }
        }
        TaskMaterialsContent(documentRefs, attachments, materials,
            onRemoveDocument = { reference: OfficeRefBody ->
                onDocumentRefsChange(documentRefs.filterNot { it.spaceId == reference.spaceId && it.targetId == reference.targetId })
            }.takeIf { enabled },
            onRemoveAttachment = onRemoveAttachment.takeIf { enabled })
        jobs.values.forEach { (job, name) ->
            Column(Modifier.fillMaxWidth().testTag("task.materials.upload.${job.jobId}")) {
                Text(name, style = MaterialTheme.typography.bodyMedium)
                if (job.state == PendingAssetJobState.FAILED) {
                    Text(job.failureReason ?: "上传失败", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { materials.imports.retry(job.jobId) }, enabled = enabled,
                        modifier = Modifier.testTag("task.materials.retry.${job.jobId}")) { Text("重试") }
                } else {
                    Text(if (job.state == PendingAssetJobState.UPLOADING) "上传中 ${(job.progress * 100).toInt()}%" else "正在准备附件…")
                }
                TextButton(onClick = { materials.imports.cancel(job.jobId) }, enabled = enabled,
                    modifier = Modifier.testTag("task.materials.cancel.${job.jobId}")) { Text("移除") }
            }
        }
    }
    if (choosingDocument) TaskDocumentPicker(
        materials = materials,
        onDismiss = { choosingDocument = false },
        onPick = { reference ->
            if (documentRefs.none { it.spaceId == reference.spaceId && it.targetId == reference.targetId }) {
                onDocumentRefsChange(documentRefs + reference)
            }
            choosingDocument = false
        },
    )
}

@Composable
internal fun TaskMaterialsContent(
    documentRefs: List<OfficeRefBody>,
    attachments: List<Attachment>,
    materials: TaskMaterialsUi?,
    onRemoveDocument: ((OfficeRefBody) -> Unit)? = null,
    onRemoveAttachment: ((Attachment) -> Unit)? = null,
) {
    var openingDocument by remember(documentRefs) { mutableStateOf<OfficeRefBody?>(null) }
    var openError by remember(documentRefs) { mutableStateOf<String?>(null) }
    LaunchedEffect(materials, openingDocument) {
        val reference = openingDocument ?: return@LaunchedEffect
        openError = null
        try { materials?.openDocument?.invoke(reference) }
        catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            openError = "文档不可访问或已被删除"
        } finally { openingDocument = null }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Tk.spacing.sm), modifier = Modifier.testTag("task.materials")) {
        openError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("task.materials.error")) }
        documentRefs.forEach { reference ->
            Row(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { openingDocument = reference },
                    enabled = materials != null, modifier = Modifier.weight(1f).testTag("task.materials.document.${reference.targetId}")) {
                    Text(reference.title.ifBlank { "未命名文档" })
                }
                onRemoveDocument?.let { remove ->
                    TextButton(onClick = { remove(reference) }, modifier = Modifier.testTag("task.materials.removeDocument.${reference.targetId}")) { Text("移除") }
                }
            }
        }
        attachments.forEach { attachment ->
            Row(Modifier.fillMaxWidth()) {
                if (materials != null) FileCardWithDownload(materials.fileDownloads, attachment,
                    modifier = Modifier.weight(1f).testTag("task.materials.file.${attachment.path}"))
                else Text(attachment.name, modifier = Modifier.weight(1f))
                onRemoveAttachment?.let { remove ->
                    TextButton(onClick = { remove(attachment) }, modifier = Modifier.testTag("task.materials.removeFile.${attachment.path}")) { Text("移除") }
                }
            }
        }
    }
}

@Composable
private fun TaskDocumentPicker(materials: TaskMaterialsUi, onDismiss: () -> Unit, onPick: (OfficeRefBody) -> Unit) {
    var candidates by remember { mutableStateOf<List<OfficeRefBody>?>(null) }
    var error by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(materials, attempt) {
        error = false
        try { candidates = materials.loadDocuments() }
        catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            error = true
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("关联最近文档") },
        text = {
            Column {
                when {
                    error -> {
                        Text("文档加载失败，请重试", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { attempt++ }, modifier = Modifier.testTag("task.materials.documents.retry")) { Text("重试") }
                    }
                    candidates == null -> CircularProgressIndicator()
                    candidates!!.isEmpty() -> Text("暂无最近文档，请先在文档栏目打开需要关联的文档")
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = Tk.dimens.listItemHeight * 5)
                        .testTag("task.materials.documents")) {
                        items(candidates!!, key = { "${it.spaceId}:${it.targetId}" }) { reference ->
                            Text(reference.title, modifier = Modifier.fillMaxWidth()
                                .clickable { onPick(reference) }.padding(vertical = Tk.spacing.sm)
                                .testTag("task.materials.pickDocument.${reference.targetId}"))
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
