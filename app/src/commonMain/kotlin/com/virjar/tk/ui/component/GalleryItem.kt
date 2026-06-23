package com.virjar.tk.ui.component

import com.virjar.tk.body.ImageBody
import com.virjar.tk.body.VideoBody
import com.virjar.tk.model.Message

/**
 * 媒体画廊项（图片或视频）。commonMain 共享，供全屏画廊使用。
 */
data class GalleryItem(val url: String, val type: String = "image") // "image" or "video"

/**
 * 从消息列表中提取图片+视频媒体项（用于全屏滑动浏览器）。
 *
 * 注意：视频项用 [VideoBody.url]（视频本体），不用 thumbnailUrl（缩略图是 JPEG，
 * ExoPlayer 无法播放）。缩略图用于消息列表预览，画廊里应播放完整视频。
 */
fun buildMediaList(messages: List<Message>): List<GalleryItem> =
    messages
        .filter { it.body is ImageBody || it.body is VideoBody }
        .map { msg ->
            val b = msg.body!!
            GalleryItem(
                url = when (b) {
                    is ImageBody -> b.url
                    is VideoBody -> b.url
                    else -> ""
                },
                type = if (b is VideoBody) "video" else "image",
            )
        }
