package com.virjar.tk.ui.component

import com.virjar.tk.body.ImageBody
import com.virjar.tk.body.VideoBody
import com.virjar.tk.model.Message

/**
 * 媒体画廊项（图片或视频）。commonMain 共享，供全屏画廊使用。
 */
enum class GalleryMediaType { IMAGE, VIDEO }

data class GalleryItem(val path: String, val type: GalleryMediaType)

/**
 * 从消息列表中提取图片+视频媒体项（用于全屏滑动浏览器）。
 *
 * 注意：视频项使用主附件 path（视频本体），不用 thumbnail（缩略图是 JPEG，
 * ExoPlayer 无法播放）。缩略图用于消息列表预览，画廊里应播放完整视频。
 */
fun buildMediaList(messages: List<Message>): List<GalleryItem> =
    messages
        .filter { message ->
            (message.body is ImageBody || message.body is VideoBody) &&
                attachmentRenderMode(message) == AttachmentRenderMode.REMOTE_CONTENT
        }
        .map { msg ->
            val b = msg.body!!
            GalleryItem(
                path = when (b) {
                    is ImageBody -> b.attachment.path
                    is VideoBody -> b.attachment.path
                    else -> ""
                },
                type = if (b is VideoBody) GalleryMediaType.VIDEO else GalleryMediaType.IMAGE,
            )
        }
