package com.virjar.tk.app.ui.component

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.virjar.tk.protocol.model.Attachment

/**
 * 文件附件下载状态（消息气泡数据源）。
 *
 * 架构约束：文件附件只走服务端文件存储，消息体存相对 path；完整 URL 仅为
 * 客户端/外部 SDK 对接形态。下载由客户端拼 base，服务端发送时校验存在性
 * （发送成功 = 附件必在）。
 */
sealed class FileDownloadState {
    /** 正在异步探测平台本地缓存；完成前不得据此发起自动下载。 */
    data object Checking : FileDownloadState()
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
    /** 该 controller owner 的每个 UI 视图共享的一份有界自动下载历史。 */
    val automaticDownloadLedger: AutomaticFileDownloadLedger

    /**
     * 启动首次缓存探测。异步实现必须先发布 [FileDownloadState.Checking]，再收敛到
     * [FileDownloadState.Done] 或 [FileDownloadState.Idle]；同步实现可直接发布最终状态。
     */
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

/**
 * 在一个下载控制器 owner 的完整生命周期内只认领一次小文件自动下载。
 *
 * 缓存探测完成前状态为 [FileDownloadState.Checking]，命中为 [FileDownloadState.Done]；
 * 两者都不能触网。只有首次明确 miss（[FileDownloadState.Idle]）可以启动下载，后续即使状态
 * 回到 Idle 也保留人工重试语义。容量耗尽后，新路径保持手动下载，不淘汰旧认领记录；这样聊天
 * pager 暂时移除再恢复旧消息时不会重新触发自动下载。
 */
class AutomaticFileDownloadLedger(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val lock = Any()
    private val claimedPaths = mutableSetOf<String>()

    init {
        require(maxEntries > 0) { "Automatic download ledger capacity must be positive" }
    }

    fun claim(attachment: Attachment, state: FileDownloadState?): Boolean {
        if (attachment.path.isBlank() ||
            attachment.size > FileDownloadController.AUTO_DOWNLOAD_LIMIT ||
            state !is FileDownloadState.Idle
        ) {
            return false
        }
        return synchronized(lock) {
            if (attachment.path in claimedPaths || claimedPaths.size >= maxEntries) {
                false
            } else {
                claimedPaths.add(attachment.path)
            }
        }
    }

    internal val claimedEntryCount: Int
        get() = synchronized(lock) { claimedPaths.size }

    companion object {
        /** 远高于单个常驻聊天页的规模，同时保持 owner 生命周期的硬上限。 */
        const val DEFAULT_MAX_ENTRIES: Int = 1_024
    }
}

/** 平台下载控制器注入点；每个已认证聊天 owner 必须显式提供。 */
val LocalFileDownloads = staticCompositionLocalOf<FileDownloadController> {
    error("Authenticated chat file download owner is missing")
}
