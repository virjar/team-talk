package com.virjar.tk.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.ui.screen.TaskMaterialsUi
import com.virjar.tk.app.ui.screen.TaskWorkspaceScreen
import com.virjar.tk.app.ui.screen.loadMaterialDocumentCandidates
import com.virjar.tk.app.ui.screen.openMaterialDocument
import com.virjar.tk.desktop.media.DesktopSessionResources
import kotlinx.coroutines.launch

@Composable
internal fun DesktopTaskWorkspaceHost(
    nav: DesktopNav,
    presentationGate: DesktopSessionPresentationGate,
    resources: DesktopSessionResources,
) {
    val uiScope = rememberCoroutineScope()
    val downloads = remember(resources, presentationGate, uiScope) {
        DesktopFileDownloadController(
            resources = resources,
            uiScope = uiScope,
            actionAdmission = presentationGate,
            onDownloaded = DesktopExternalFileOpener::open,
            telemetry = nav.telemetry,
            telemetryPage = ClientUiPage.TASKS,
        )
    }
    DisposableEffect(downloads) { onDispose { downloads.close() } }
    // 离开或替换表单即关闭旧网关，原生选择器与迟到上传不保留给下一次编辑。
    val editorKey = nav.tasks.materialsEditorKey
    val imports = remember(resources, presentationGate, uiScope, editorKey) {
        DesktopEmbeddedAssetImportGateway(
            resources = resources,
            transfer = resources.fileTransfer,
            publishOnUi = { action -> uiScope.launch { presentationGate.runIfOpen(action) } },
        )
    }
    DisposableEffect(imports) { onDispose { imports.close() } }
    val materials = remember(nav, imports, downloads) {
        TaskMaterialsUi(
            imports = imports,
            fileDownloads = downloads,
            loadDocuments = nav.tasks::loadMaterialDocumentCandidates,
            openDocument = { reference ->
                nav.tasks.openMaterialDocument(reference) {
                    presentationGate.runIfOpen { nav.openDocument(reference.spaceId, reference.targetId) }
                }
            },
        )
    }
    TaskWorkspaceScreen(feature = nav.tasks, actionAdmission = presentationGate, materials = materials)
}
