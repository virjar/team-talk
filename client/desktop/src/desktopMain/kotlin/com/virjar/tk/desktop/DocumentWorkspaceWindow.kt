package com.virjar.tk.desktop

import com.virjar.tk.app.identity.ClientIdentity

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.virjar.tk.app.ui.AppTheme
import com.virjar.tk.app.ui.screen.DocumentWorkspaceHost
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportGateway
import com.virjar.tk.app.ui.bridge.EmbeddedAssetMediaConfig
import com.virjar.tk.app.navigation.feature.document.DocumentWorkspaceFeature
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.ui.component.LocalScreenHeaderLeadingInset
import com.virjar.tk.protocol.model.User

/** 处理文件选择、文件拖放与二进制剪贴板导入的 Desktop 文档外壳。 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DesktopDocumentWorkspaceHost(
    workspace: DocumentWorkspaceFeature,
    presentationGate: DesktopSessionPresentationGate,
    embeddedAssetImports: EmbeddedAssetImportGateway,
    embeddedAssetMedia: EmbeddedAssetMediaConfig,
    mentionCandidates: List<User> = emptyList(),
    onMentionProfileOpen: ((String) -> Unit)? = null,
    detached: Boolean = false,
    onDetach: (() -> Unit)? = null,
) {
    val dropTarget = remember(embeddedAssetImports, presentationGate) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                var accepted = false
                presentationGate.runIfOpen {
                    val data = event.dragData()
                    if (data is DragData.FilesList) {
                        accepted = importDesktopDroppedAssetUris(
                            data.readFiles(),
                            embeddedAssetImports,
                        )
                    }
                }
                return accepted
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || event.key != Key.V ||
                    (!event.isMetaPressed && !event.isCtrlPressed)
                ) {
                    return@onPreviewKeyEvent false
                }
                var consumed = false
                presentationGate.runIfOpen {
                    consumed = importDesktopClipboardAsset(embeddedAssetImports)
                }
                consumed
            }
            .dragAndDropTarget(
                shouldStartDragAndDrop = { presentationGate.isOpen },
                target = dropTarget,
            ),
    ) {
        DocumentWorkspaceHost(
            workspace = workspace,
            actionAdmission = presentationGate,
            embeddedAssetImports = embeddedAssetImports,
            embeddedAssetMedia = embeddedAssetMedia,
            mentionCandidates = mentionCandidates,
            onMentionProfileOpen = onMentionProfileOpen,
            detached = detached,
            mobileSingleDocumentMode = false,
            onDetach = onDetach,
        )
    }
}

/**
 * Desktop 文档专用窗口。它显示期间是文档导航的唯一宿主；主窗口只保留承接态，
 * 关闭或收回后再由主窗口接续同一个 session-scoped 工作台状态与未保存草稿。
 */
@Composable
internal fun DocumentWorkspaceWindow(
    nav: DesktopNav,
    presentationGate: DesktopSessionPresentationGate,
    embeddedAssetImports: EmbeddedAssetImportGateway,
    embeddedAssetMedia: EmbeddedAssetMediaConfig,
    onClose: () -> Unit,
) {
    if (!presentationGate.isOpen || !nav.acceptsRendering) return

    // 独立文档窗口的 @ 资料卡是窗口内模态：uid 由本窗口持有，不写回主窗口的
    // nav.profileUid，避免两扇窗口同时弹同一张资料卡。
    var mentionProfileUid by remember { mutableStateOf<String?>(null) }
    val contacts by nav.contactViewModel.contacts.collectAsState()

    val integratedMacTitleBar = remember {
        System.getProperty("os.name").contains("Mac", ignoreCase = true)
    }
    Window(
        onCloseRequest = onClose,
        title = if (integratedMacTitleBar) "" else "${ClientIdentity.DISPLAY_NAME} 文档",
        state = rememberWindowState(width = 1280.dp, height = 820.dp),
    ) {
        if (!presentationGate.isOpen || !nav.acceptsRendering) return@Window
        DesktopWindowTelemetry(
            window = window,
            page = ClientUiPage.DOCUMENT_WINDOW,
            telemetry = nav.telemetry,
            disposalExitReason = {
                desktopWindowDisposalExitReason(presentationGate.isOpen)
            },
        )
        TestServiceBridge.registerWindowWithId("documents", window)
        DisposableEffect(Unit) {
            onDispose { TestServiceBridge.unregisterWindow("documents") }
        }
        setTeamTalkIcon()
        DisposableEffect(window, integratedMacTitleBar) {
            if (integratedMacTitleBar) window.applyMacImmersiveChrome()
            onDispose { }
        }
        AppTheme {
            CompositionLocalProvider(
                LocalScreenHeaderLeadingInset provides if (integratedMacTitleBar) 72.dp else 0.dp,
            ) {
                Surface(Modifier.fillMaxSize()) {
                    DesktopDocumentWorkspaceHost(
                        workspace = nav.documents,
                        presentationGate = presentationGate,
                        embeddedAssetImports = embeddedAssetImports,
                        embeddedAssetMedia = embeddedAssetMedia,
                        mentionCandidates = contacts.mapNotNull { it.user },
                        onMentionProfileOpen = presentationGate.guard { uid: String -> mentionProfileUid = uid },
                        detached = true,
                    )
                }
            }
        }
        mentionProfileUid?.let { uid ->
            key(uid) {
                DesktopUserProfileDialog(
                    uid = uid,
                    nav = nav,
                    ownerWindow = window,
                    presentationGate = presentationGate,
                    onDismiss = presentationGate.guard { mentionProfileUid = null },
                )
            }
        }
    }
}
