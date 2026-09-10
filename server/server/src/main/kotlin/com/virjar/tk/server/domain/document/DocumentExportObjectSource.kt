package com.virjar.tk.server.domain.document

import java.io.OutputStream

/**
 * 导出资产字节源：attachment_path 指向的已发布对象。
 * [copyExportObject] 把对象内容拷入目标流；对象不存在或不可读返回 false，且不得写入任何字节。
 */
interface DocumentExportObjectSource {
    fun hasExportObject(path: String): Boolean
    suspend fun copyExportObject(path: String, out: OutputStream): Boolean
}
