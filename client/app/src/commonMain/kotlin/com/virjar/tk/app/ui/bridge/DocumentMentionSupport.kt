package com.virjar.tk.app.ui.bridge

import androidx.compose.runtime.staticCompositionLocalOf
import com.virjar.tk.protocol.model.User

/**
 * 文档 @ 提及的平台供给：候选好友列表与“打开用户资料卡”入口。
 *
 * 与内嵌资产的平台网关同构——文档 UI 保持平台无关，由 Desktop/Android 宿主在装配点注入。
 * 未注入时（空实现）补全列表为空、预览点击 @ 不响应，编辑与保存不受影响。
 */
data class DocumentMentionSupport(
    val candidates: List<User> = emptyList(),
    val onMentionProfileOpen: (uid: String) -> Unit = {},
)

val LocalDocumentMentionSupport = staticCompositionLocalOf { DocumentMentionSupport() }
