package com.virjar.tk.app.ui.bridge

import androidx.compose.runtime.staticCompositionLocalOf
import com.virjar.tk.protocol.body.EmbeddedAssetPresentation
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.app.ui.component.rich.PendingAssetJob
import com.virjar.tk.app.ui.component.rich.PendingAssetJobState

enum class EmbeddedAssetImportSource {
    DESKTOP_PICKER,
    DESKTOP_DROP,
    DESKTOP_CLIPBOARD,
    ANDROID_PICKER,
    ANDROID_CLIPBOARD,
}

/**
 * 临时性的平台选择。[localReference] 是 OS 本地的 path/content URI 令牌，绝不能持久化到
 * Markdown、消息体、文档或遥测数据中。
 */
data class EmbeddedAssetLocalSelection(
    val localReference: String,
    val displayName: String,
    val contentType: String,
    val size: Long,
    val presentation: EmbeddedAssetPresentation,
    val source: EmbeddedAssetImportSource,
    /** 仅对适配器持有的临时副本为 true，用户选择的原始文件永远不为 true。 */
    val deleteAfterImport: Boolean = false,
) {
    init {
        require(localReference.isNotBlank()) { "Local asset reference must not be blank" }
        require(displayName.isNotBlank()) { "Local asset display name must not be blank" }
        require(contentType.isNotBlank()) { "Local asset content type must not be blank" }
        require(size >= 0L) { "Local asset size must not be negative" }
    }
}

sealed interface EmbeddedAssetImportEvent {
    val job: PendingAssetJob

    data class StateChanged(
        override val job: PendingAssetJob,
        /** 出现在 LOCAL 帧上，让编辑器可以在上传开始前放置稳定的 URI。 */
        val placement: EmbeddedAssetImportPlacement? = null,
    ) : EmbeddedAssetImportEvent

    data class Ready(
        override val job: PendingAssetJob,
        val asset: EmbeddedAsset,
        val placement: EmbeddedAssetImportPlacement,
    ) : EmbeddedAssetImportEvent {
        init {
            require(job.state == PendingAssetJobState.READY) { "Ready event requires a ready job" }
            require(job.assetId == asset.assetId) { "Ready asset must belong to its pending job" }
        }
    }
}

/** 可持久化的放置元数据；刻意排除所有 OS 本地的 path/content URI。 */
data class EmbeddedAssetImportPlacement(
    val label: String,
    /** 防止刻意作为文件卡片插入的图片被 MIME 重新推断。 */
    val presentation: EmbeddedAssetPresentation,
) {
    init {
        require(label.isNotBlank()) { "Embedded asset label must not be blank" }
    }
}

fun interface EmbeddedAssetImportEventSink {
    /** 平台实现在其所属的 UI 调度器上发布事件。 */
    fun publish(event: EmbeddedAssetImportEvent)
}

/**
 * 聊天与文档编辑器共用的平台选择器/上传器边界。选择器和拖拽/剪贴板适配器都汇聚到
 * [import]，因此无论输入手势如何，预处理、上传和状态事件都保持一致。
 */
interface EmbeddedAssetImportGateway {
    /**
     * 同一时刻只有一个可见编辑器拥有实时事件。在另一个 owner 绑定期间到达的帧按 [ownerKey]
     * 保留，并且只在该 owner 回归时重放。
     */
    fun bind(
        ownerKey: String,
        sink: EmbeddedAssetImportEventSink,
        /** false 时保持完成帧继续流动，但拒绝选择器/拖拽/剪贴板导入。 */
        acceptNewImports: Boolean = true,
    ): EmbeddedAssetImportRegistration

    fun select(presentation: EmbeddedAssetPresentation)

    fun import(selection: EmbeddedAssetLocalSelection)

    /** 取消进行中的导入，或释放为重试而保留的失败导入。 */
    fun cancel(jobId: String): Boolean

    /** 重新开始失败的导入；重试进行期间重复点击仍是无害的成功空操作。 */
    fun retry(jobId: String): Boolean
}

/**
 * 某次导入启动时捕获的不透明租约。平台上传器必须通过同一租约发布每一帧；若在完成时查找
 * 当前绑定的 sink，就会让文档 A 中启动的上传在标签页切换后修改文档 B。
 */
class EmbeddedAssetImportBinding internal constructor(
    /** 暴露给平台适配器的稳定编辑器身份，必须能跨越 OS 选择器跳转存活。 */
    val ownerKey: String,
    internal val generation: Long,
    internal val sink: EmbeddedAssetImportEventSink,
    internal val acceptNewImports: Boolean,
)

/**
 * 平台网关共享的线程安全单存活 owner 路由器。上传绝不会发布到不同的 owner。其每个任务的最新
 * 帧按原始 owner 保留，并在该 owner 重新绑定时重放，因此文档标签页切换既不会污染新标签页，
 * 也不会让旧标签页永久缺失 READY 描述符。
 */
class EmbeddedAssetImportBindingRouter {
    private val lock = Any()
    private var nextGeneration = 0L
    private var current: EmbeddedAssetImportBinding? = null
    private var closed = false
    private val buffered = linkedMapOf<BufferedKey, BufferedEvent>()
    private val bufferedOwnerOrder = linkedSetOf<String>()
    private val placementDelivered = linkedSetOf<BufferedKey>()

    fun bind(
        ownerKey: String,
        sink: EmbeddedAssetImportEventSink,
        acceptNewImports: Boolean = true,
    ): EmbeddedAssetImportRegistration {
        require(ownerKey.isNotBlank() && ownerKey.length <= MAX_OWNER_KEY_LENGTH) {
            "Embedded asset import owner key is invalid"
        }
        val binding = synchronized(lock) {
            check(!closed) { "Embedded asset import router is closed" }
            EmbeddedAssetImportBinding(
                ownerKey = ownerKey,
                generation = ++nextGeneration,
                sink = sink,
                acceptNewImports = acceptNewImports,
            ).also { next ->
                current = next
                replayBufferedLocked(next)
            }
        }
        return EmbeddedAssetImportRegistration {
            synchronized(lock) {
                if (current === binding) current = null
            }
        }
    }

    fun capture(): EmbeddedAssetImportBinding? = synchronized(lock) { current }

    /** 新的平台选择与既有上传的投递相互独立地受控。 */
    fun captureForImport(): EmbeddedAssetImportBinding? = synchronized(lock) {
        current?.takeIf(EmbeddedAssetImportBinding::acceptNewImports)
    }

    fun isCurrent(binding: EmbeddedAssetImportBinding): Boolean =
        synchronized(lock) { current === binding }

    /** 在持有围栏期间调用 sink，使 close/rebind 无法在投递中途插队。 */
    fun publish(
        binding: EmbeddedAssetImportBinding,
        event: EmbeddedAssetImportEvent,
    ): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        val target = current
        if (target?.ownerKey != binding.ownerKey) {
            bufferLocked(binding.ownerKey, event)
            return@synchronized true
        }
        target.sink.publish(event)
        recordDirectDeliveryLocked(binding.ownerKey, event)
        true
    }

    fun close() {
        synchronized(lock) {
            closed = true
            current = null
            buffered.clear()
            bufferedOwnerOrder.clear()
            placementDelivered.clear()
        }
    }

    private fun replayBufferedLocked(binding: EmbeddedAssetImportBinding) {
        val keys = buffered.keys.filter { it.ownerKey == binding.ownerKey }
        keys.forEach { key ->
            val pending = buffered[key] ?: return@forEach
            when (val event = pending.event) {
                is EmbeddedAssetImportEvent.StateChanged -> {
                    val placement = pending.placement.takeUnless { key in placementDelivered }
                    binding.sink.publish(event.copy(placement = placement))
                    if (placement != null) recordPlacementDeliveredLocked(key)
                }
                is EmbeddedAssetImportEvent.Ready -> {
                    val placement = pending.placement.takeUnless { key in placementDelivered }
                    if (placement != null) {
                        // READY 只发布描述符。Placement 保持为显式的 StateChanged 帧，
                        // 因此被用户删除的引用绝不会被迟到的 READY 事件重新插入。
                        binding.sink.publish(
                            EmbeddedAssetImportEvent.StateChanged(
                                job = event.job,
                                placement = placement,
                            ),
                        )
                        recordPlacementDeliveredLocked(key)
                    }
                    binding.sink.publish(event)
                }
            }
            buffered.remove(key)
            if (eventIsTerminal(pending.event)) placementDelivered.remove(key)
        }
        bufferedOwnerOrder.remove(binding.ownerKey)
    }

    private fun bufferLocked(
        ownerKey: String,
        event: EmbeddedAssetImportEvent,
    ) {
        if (ownerKey !in bufferedOwnerOrder && bufferedOwnerOrder.size >= MAX_BUFFERED_OWNERS) {
            bufferedOwnerOrder.firstOrNull()?.let(::evictOwnerLocked)
        }
        bufferedOwnerOrder.remove(ownerKey)
        bufferedOwnerOrder.add(ownerKey)
        val key = BufferedKey(ownerKey, event.job.jobId)
        val previous = buffered[key]
        buffered[key] = BufferedEvent(
            event = event,
            placement = event.placementOrNull() ?: previous?.placement,
        )
        while (buffered.size > MAX_BUFFERED_EVENTS) {
            val eldest = buffered.keys.firstOrNull() ?: break
            buffered.remove(eldest)
            placementDelivered.remove(eldest)
            if (buffered.keys.none { it.ownerKey == eldest.ownerKey }) {
                bufferedOwnerOrder.remove(eldest.ownerKey)
            }
        }
    }

    private fun recordDirectDeliveryLocked(
        ownerKey: String,
        event: EmbeddedAssetImportEvent,
    ) {
        val key = BufferedKey(ownerKey, event.job.jobId)
        if (event is EmbeddedAssetImportEvent.StateChanged && event.placement != null) {
            recordPlacementDeliveredLocked(key)
        }
        if (eventIsTerminal(event)) placementDelivered.remove(key)
    }

    private fun recordPlacementDeliveredLocked(key: BufferedKey) {
        placementDelivered.remove(key)
        placementDelivered.add(key)
        while (placementDelivered.size > MAX_TRACKED_PLACEMENTS) {
            placementDelivered.firstOrNull()?.let(placementDelivered::remove)
        }
    }

    private fun evictOwnerLocked(ownerKey: String) {
        buffered.keys.filter { it.ownerKey == ownerKey }.forEach { key ->
            buffered.remove(key)
            placementDelivered.remove(key)
        }
        bufferedOwnerOrder.remove(ownerKey)
    }

    private data class BufferedKey(
        val ownerKey: String,
        val jobId: String,
    )

    private data class BufferedEvent(
        val event: EmbeddedAssetImportEvent,
        val placement: EmbeddedAssetImportPlacement?,
    )

    private companion object {
        const val MAX_OWNER_KEY_LENGTH = 256
        const val MAX_BUFFERED_OWNERS = 32
        const val MAX_BUFFERED_EVENTS = 256
        const val MAX_TRACKED_PLACEMENTS = 512
    }
}

private fun EmbeddedAssetImportEvent.placementOrNull(): EmbeddedAssetImportPlacement? = when (this) {
    is EmbeddedAssetImportEvent.StateChanged -> placement
    is EmbeddedAssetImportEvent.Ready -> placement
}

/** 只有初始状态帧可以创建 Markdown 语法；READY 只解析 sidecar。 */
fun EmbeddedAssetImportEvent.markdownPlacementOrNull(): EmbeddedAssetImportPlacement? =
    (this as? EmbeddedAssetImportEvent.StateChanged)?.placement

private fun eventIsTerminal(event: EmbeddedAssetImportEvent): Boolean =
    event.job.state == PendingAssetJobState.READY ||
        event.job.state == PendingAssetJobState.CANCELLED

/** 供活动文档编辑器消费的可选平台导入能力。 */
val LocalEmbeddedAssetImportGateway = staticCompositionLocalOf<EmbeddedAssetImportGateway?> { null }

fun interface EmbeddedAssetImportRegistration {
    fun close()
}

data class EmbeddedAssetImportSnapshot(
    val jobs: List<PendingAssetJob> = emptyList(),
    val assets: List<EmbeddedAsset> = emptyList(),
)

/** 纯 reducer；UI owner 可以持久化 [assets]，无需保留 OS 本地的选择令牌。 */
fun EmbeddedAssetImportSnapshot.reduce(event: EmbeddedAssetImportEvent): EmbeddedAssetImportSnapshot {
    val nextJobs = jobs
        .filterNot { it.jobId == event.job.jobId }
        .plus(event.job)
    val nextAssets = when (event) {
        is EmbeddedAssetImportEvent.StateChanged -> assets
        is EmbeddedAssetImportEvent.Ready -> assets
            .filterNot { it.assetId == event.asset.assetId }
            .plus(event.asset)
    }
    return copy(jobs = nextJobs, assets = nextAssets)
}
