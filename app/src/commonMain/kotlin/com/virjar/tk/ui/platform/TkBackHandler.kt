package com.virjar.tk.ui.platform

import androidx.compose.runtime.Composable

/**
 * 平台返回手势适配层。
 *
 * Android 将它接到系统 Back；Desktop 没有等价的页面级系统返回，因此实际实现为空。
 * 业务页面仍显式提供返回动作，避免 common UI 依赖 Android API。
 */
@Composable
internal expect fun TkBackHandler(enabled: Boolean = true, onBack: () -> Unit)
