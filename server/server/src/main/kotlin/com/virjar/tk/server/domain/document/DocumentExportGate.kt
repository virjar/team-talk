package com.virjar.tk.server.domain.document

/**
 * 文档空间导出的后台开关。缺省关闭；由超级管理员经管理 API 翻转，
 * 同时约束空间责任人（steward）入口与超级管理员入口之外的一切未来导出路径。
 */
interface DocumentExportGate {
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean, updatedBy: String)
}
