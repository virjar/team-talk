package com.virjar.tk.ios

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.virjar.tk.app.navigation.MainTab
import com.virjar.tk.app.ui.bridge.*
import com.virjar.tk.app.ui.component.ScreenHeader
import com.virjar.tk.app.ui.screen.*
import com.virjar.tk.protocol.body.OfficeRefBody
import kotlinx.coroutines.*

@Composable
internal fun IosDocumentWorkspaceHost(ui: IosSessionUi, mobileExitCoordinator: MobileDocumentExitCoordinator,
    onExitDocuments: () -> Unit, onEditingActive: (Boolean) -> Unit) {
    val contacts by ui.data.contactViewModel.contacts.collectAsState()
    var gallery by remember(ui) { mutableStateOf<IosGalleryRequest?>(null) }
    Box(Modifier.fillMaxSize().imePadding()) {
        DocumentWorkspaceHost(ui.data.documents, ui.data.uiActionAdmission,
            onExitDocuments = onExitDocuments, mobileSingleDocumentMode = true,
            mobileExitCoordinator = mobileExitCoordinator, embeddedAssetImports = ui.imports,
            embeddedAssetMedia = EmbeddedAssetMediaConfig(ui.files,
                imageContent = { attachment, modifier -> IosAttachmentImage(attachment, ui.media, modifier) },
                onPasteEmbeddedAsset = { ui.native.importClipboard(ui.imports) }),
            mentionCandidates = contacts.mapNotNull { it.user }, onMentionProfileOpen = ui.navigation::profile,
            onOpenImageGallery = { items, index -> gallery = IosGalleryRequest(items, index) },
            onMobileEditingActive = onEditingActive)
    }
    gallery?.let { IosGallery(it, ui.media, ui.files) { gallery = null } }
}

@Composable
internal fun IosTaskWorkspaceHost(ui: IosSessionUi, onOpenDocument: (OfficeRefBody) -> Unit) {
    val onOpen by rememberUpdatedState(onOpenDocument)
    val materials = remember(ui) { TaskMaterialsUi(ui.imports, ui.files,
        loadDocuments = ui.data.tasks::loadMaterialDocumentCandidates,
        openDocument = { reference -> ui.data.tasks.openMaterialDocument(reference) { onOpen(reference) } }) }
    DisposableEffect(ui) {
        val remove = ui.navigation.backDispatcher.register {
            if (!ui.data.tasks.handleBack()) ui.navigation.home(MainTab.CONVERSATIONS)
            true
        }
        onDispose { remove() }
    }
    TaskWorkspaceScreen(ui.data.tasks, ui.data.uiActionAdmission, compactMode = true,
        materials = materials, modifier = Modifier.imePadding())
}

@Composable
internal fun IosStorageScreen(ui: IosSessionUi) {
    val scope = rememberCoroutineScope()
    var bytes by remember { mutableLongStateOf(0L) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ui) { bytes = ui.media.cacheBytes() }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("本地存储", onBack = ui.navigation::back)
        Text("附件缓存：${bytes / (1024 * 1024)} MB")
        Text("清理会移除未在使用的已下载媒体。聊天记录、草稿和待发送附件会保留。")
        Button(enabled = !working, onClick = {
            scope.launch {
                working = true; error = null
                try { ui.media.clearUnleasedCache(); ui.files.states.clear(); bytes = ui.media.cacheBytes() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "清理失败，请稍后重试" }
                finally { working = false }
            }
        }) { Text(if (working) "正在清理…" else "清理媒体缓存") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
