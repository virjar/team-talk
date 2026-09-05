package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.protocol.model.Document

/** move 和 revision 冲突工作流所需的共享工作区状态操作。 */
internal class DocumentWorkspaceMutationStatePort(
    val tabs: () -> List<DocumentTabState>,
    val replaceTabs: (List<DocumentTabState>) -> Unit,
    val selectedSpaceId: () -> String?,
    val activeTabId: () -> String?,
    val captureActiveDraft: (DocumentTabState) -> DocumentTabState?,
    val updateActiveLocation: (DocumentTabState) -> Unit,
    /** 在预期的 tab 投影于内存中可见之前，持久地准入它。 */
    val persistTabs: (List<DocumentTabState>) -> Boolean,
    val prepareDocumentBranches: (Document, Set<String?>) -> Unit,
    val expandParent: (String) -> Unit,
    val refreshHome: suspend () -> Unit,
)
