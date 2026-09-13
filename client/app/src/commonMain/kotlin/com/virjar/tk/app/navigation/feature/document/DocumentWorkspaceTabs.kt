package com.virjar.tk.app.navigation.feature.document

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 驻留正文和活动选择的唯一状态；准入、恢复与持久化仍由相应工作流负责。 */
internal class DocumentWorkspaceTabs {
    private data class State(
        val items: List<DocumentTabState> = emptyList(),
        val activeTabId: String? = null,
    )

    private var state by mutableStateOf(State())

    val items: List<DocumentTabState> get() = state.items
    val activeTabId: String? get() = state.activeTabId
    val activeTab: DocumentTabState? get() = items.firstOrNull { it.tabId == activeTabId }

    fun activate(tabId: String?) {
        state = state.copy(activeTabId = tabId)
    }

    fun replace(items: List<DocumentTabState>) = publish(items, activeTabId)

    /** 同时替换正文和选择，避免激活或移除标签时暂时暴露已退役的活动正文。 */
    fun publish(items: List<DocumentTabState>, activeTabId: String?) {
        // 普通生产者使用更小的运行目标；直接 merge/restoration 也必须遵守
        // 从持久化容量推导的绝对上限。
        check(items.size <= MAX_RECOVERED_DOCUMENT_TABS) {
            "Document resident tabs exceeded the absolute recovery ceiling"
        }
        check(reservedDocumentBodyChars(items, activeInstanceId = null) <=
            MAX_RECOVERED_DOCUMENT_BODY_CHARS) {
            "Document resident bodies exceeded the absolute recovery ceiling"
        }
        state = State(items, activeTabId)
    }
}
