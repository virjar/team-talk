package com.virjar.tk.protocol.http

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `/downloads/android.json` 的公开发行信息契约。
 *
 * 服务端和 Android 客户端共享该 DTO 与通道词汇；客户端只负责执行 HTTP，不拥有响应格式。
 * [channel] 缺省表示无收据的历史目录，不声明通道。
 */
@Serializable
data class AndroidReleaseManifest(
    val displayName: String = "Android",
    val version: String,
    val channel: String? = null,
    val filename: String,
    val url: String,
) {
    /** 通道展示名：快照/预览会标注，正式（stable 或旧服务端缺省）不额外标注。 */
    val channelLabel: String? get() = when (channel) {
        CHANNEL_SNAPSHOT -> "内测快照"
        CHANNEL_PREVIEW -> "预览版"
        else -> null
    }

    companion object {
        const val CHANNEL_STABLE = "stable"
        const val CHANNEL_PREVIEW = "preview"
        const val CHANNEL_SNAPSHOT = "snapshot"

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun decode(body: String): AndroidReleaseManifest? = runCatching {
            json.decodeFromString(serializer(), body)
        }.getOrNull()
    }
}
