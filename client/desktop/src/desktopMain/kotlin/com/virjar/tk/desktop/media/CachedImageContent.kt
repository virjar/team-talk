package com.virjar.tk.desktop.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.virjar.tk.desktop.DesktopImageCodec
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.app.ui.UiActionAdmission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * 缓存感知图片渲染（doc/05-clients/rich-content.md）：
 * 1. 本地缓存命中 → 直接解码渲染（零网络）
 * 2. 未命中 → 下载（进度 UI）→ 渲染；缩略图气泡默认走此路径（小文件秒下）
 * 3. 画廊原图按需：同一组件，进度条为大覆盖层样式
 *
 * @param progressOverlay true=画廊式大进度覆盖（原图按需下载），false=气泡式小指示
 * @param contentScale 调用场景的展示策略；消息与文档默认完整展示，明确的封面场景才传 Crop。
 * @param showStatus false 时加载/失败保持透明，供已有稳定占位的头像等小图使用。
 */
@Composable
internal fun CachedImageContent(
    attachment: Attachment,
    resources: DesktopSessionResources,
    actionAdmission: UiActionAdmission,
    modifier: Modifier = Modifier,
    progressOverlay: Boolean = false,
    contentScale: ContentScale = ContentScale.Fit,
    showStatus: Boolean = true,
    contentDescription: String = "图片",
) {
    // produceState 在 producer 的 key 变化时保持其 State 对象。把整个 producer 的作用域绑定到
    // 不可变的媒体请求上，这样被复用的聊天/分页槽位绝不会在新附件还在下载时
    // 绘制上一个附件。
    key(attachment.path, attachment.size, attachment.contentType, resources, actionAdmission) {
        CachedImageRequestContent(
            attachment = attachment,
            resources = resources,
            actionAdmission = actionAdmission,
            modifier = modifier,
            progressOverlay = progressOverlay,
            contentScale = contentScale,
            showStatus = showStatus,
            contentDescription = contentDescription,
        )
    }
}

@Composable
private fun CachedImageRequestContent(
    attachment: Attachment,
    resources: DesktopSessionResources,
    actionAdmission: UiActionAdmission,
    modifier: Modifier,
    progressOverlay: Boolean,
    contentScale: ContentScale,
    showStatus: Boolean,
    contentDescription: String,
) {
    val state = produceState<CachedImageState>(
        CachedImageState.Loading(0f),
        attachment,
        resources,
        actionAdmission,
    ) {
        value = CachedImageState.Loading(0f)
        val publicationGate: ((() -> Unit) -> Boolean) = { publication ->
            var delivered = false
            actionAdmission.runIfOpen {
                if (resources.canDeliverUiResult()) {
                    publication()
                    delivered = true
                }
            }
            delivered
        }
        val progressHandoff = DesktopMediaProgressHandoff(
            ownerScope = this,
            publicationGate = publicationGate,
            publish = { progress -> value = CachedImageState.Loading(progress) },
        )
        try {
            val bitmap = loadDesktopImageWithSingleRefresh { forceRefresh ->
                val lease = resources.mediaCache.cachedLease(attachment, forceRefresh)
                    ?: resources.mediaCache.ensureDownloadedLease(attachment) { p ->
                        progressHandoff.offer(p)
                    }
                lease.use {
                    withContext(Dispatchers.IO) {
                        DesktopImageCodec.decode(it.file, resources.diagnostics)
                    }
                }
            }
            val result = if (bitmap == null) {
                CachedImageState.Failed("decode failed")
            } else {
                resources.ensureOpen()
                CachedImageState.Ready(bitmap)
            }
            publicationGate { value = result }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            publicationGate {
                value = CachedImageState.Failed(e.message ?: "download failed")
            }
        } finally {
            progressHandoff.close()
        }
    }


    when (val s = state.value) {
        is CachedImageState.Loading -> if (showStatus) {
            Box(modifier, contentAlignment = Alignment.Center) {
                if (progressOverlay) {
                    CircularProgressIndicator(color = Color.White)
                    Text(
                        "${(s.progress * 100).toInt()}%",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }
        is CachedImageState.Ready -> Image(
            bitmap = s.bitmap,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
        is CachedImageState.Failed -> if (showStatus) {
            Box(modifier, contentAlignment = Alignment.Center) {
                Text("加载失败", color = Color.White)
            }
        }
    }
}

/** 同样大小的损坏缓存条目只会获得一次权威替换，绝不会进入重试循环。 */
internal suspend fun <T> loadDesktopImageWithSingleRefresh(
    load: suspend (forceRefresh: Boolean) -> T?,
): T? = load(false) ?: load(true)

private sealed interface CachedImageState {
    data class Loading(val progress: Float) : CachedImageState
    data class Ready(val bitmap: ImageBitmap) : CachedImageState
    data class Failed(val reason: String) : CachedImageState
}
