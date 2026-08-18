package com.virjar.tk.ui.component

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.virjar.tk.model.Attachment

/**
 * 文件附件下载状态（消息气泡数据源）。
 *
 * 架构约束：文件附件只走服务端文件存储，消息体存相对 path；完整 URL 仅为
 * 客户端/外部 SDK 对接形态。下载由客户端拼 base，服务端发送时校验存在性
 * （发送成功 = 附件必在）。
 */
sealed class FileDownloadState {
    /** 未下载（大文件初始态：等待用户点击） */
    data object Idle : FileDownloadState()
    /** 下载中（progress 0..1；-1 = 未知总长无法计算百分比） */
    data class Downloading(val progress: Float) : FileDownloadState()
    /** 已下载（本地就绪，点击即系统打开） */
    data object Done : FileDownloadState()
    /** 下载失败（点击重试） */
    data class Failed(val reason: String? = null) : FileDownloadState()
}

/**
 * 平台注入的文件下载控制器（桌面：media/ 目录缓存；Android：cacheDir/downloads）。
 *
 * 状态观察：[states] 是 Compose 可观察 map；[ensure] 在 FileCard 首次组合时
 * 调用（磁盘命中 → Done，否则 Idle 并入表）。
 */
interface FileDownloadController {

    val states: SnapshotStateMap<String, FileDownloadState>

    /** 状态表初始化（磁盘缓存命中判定）。FileCard LaunchedEffect(url) 调用。 */
    fun ensure(attachment: Attachment)

    /** 开始下载（未开始时；小文件自动下载与用户点击共用）。 */
    fun download(attachment: Attachment)

    /** 点击行为：已下载 → 系统打开；否则下载，完成后自动打开。 */
    fun openOrDownload(attachment: Attachment)

    /** 释放平台下载 scope；所有者（聊天页面）销毁时调用。 */
    fun close()

    companion object {
        /** ≤ 此值的附件收到即静默下载（微信式体验：小文件点开零等待）。 */
        const val AUTO_DOWNLOAD_LIMIT: Long = 1024 * 1024
    }
}

/** 平台下载控制器注入点（ChatPanel 提供，FileCard 消费；null = 旧 onMediaClick 路径）。 */
val LocalFileDownloads = staticCompositionLocalOf<FileDownloadController?> { null }
