package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.virjar.tk.app.ui.bridge.ChatMediaConfig
import com.virjar.tk.app.ui.component.rich.EmbeddedAssetRenderScope
import com.virjar.tk.app.ui.component.rich.PendingAssetJob
import com.virjar.tk.app.ui.component.rich.embeddedAssetMarkdownReferences
import com.virjar.tk.app.ui.theme.Tk
import com.virjar.tk.protocol.body.EmbeddedAssetPresentation
import com.virjar.tk.protocol.model.EmbeddedAsset

internal data class ComposerImagePlacement(val ordinal: Int, val asset: EmbeddedAsset)

/** 只从当前 Markdown 与当前 sidecar 解析；删除引用后立即消失，代码字面量不会成为图片。 */
internal fun composerImagePlacements(
    markdown: String,
    assets: List<EmbeddedAsset>,
): List<ComposerImagePlacement> = runCatching {
    val scope = EmbeddedAssetRenderScope(assets)
    embeddedAssetMarkdownReferences(markdown)
        .filter { it.presentation == EmbeddedAssetPresentation.IMAGE }
        .mapIndexedNotNull { index, reference ->
            reference.assetId?.let { scope.resolve(it, EmbeddedAssetPresentation.IMAGE) }
                ?.let { ComposerImagePlacement(index + 1, it) }
        }
}.getOrDefault(emptyList())

/** BasicTextField 的「图」原子节点与卡片按正文顺序对应；图片仍走平台认证下载及本地缓存。 */
@Composable
internal fun ComposerImageCards(
    markdown: String,
    assets: List<EmbeddedAsset>,
    pendingJobs: List<PendingAssetJob>,
    media: ChatMediaConfig,
    onDiscard: (PendingAssetJob) -> Unit,
) {
    val images = remember(markdown, assets) { composerImagePlacements(markdown, assets) }
    if (images.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag("chat.composer.images")) {
        Text(
            "正文中的「图」按顺序对应下列图片",
            style = MaterialTheme.typography.labelSmall,
            color = Tk.colors.metaText,
            modifier = Modifier.padding(horizontal = Tk.spacing.md, vertical = Tk.spacing.xs),
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth().height(116.dp),
            contentPadding = PaddingValues(horizontal = Tk.spacing.md, vertical = Tk.spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Tk.spacing.sm),
        ) {
            items(images, key = { it.ordinal }) { placement ->
                val asset = placement.asset
                Surface(
                    modifier = Modifier.width(156.dp)
                        .testTag("chat.composer.image.${placement.ordinal}"),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column {
                        Box(Modifier.fillMaxWidth().height(76.dp)) {
                            media.imageContent(asset.thumbnail ?: asset.attachment, Modifier.fillMaxSize())
                            pendingJobs.firstOrNull { it.assetId == asset.assetId }?.let { job ->
                                Surface(
                                    modifier = Modifier.align(Alignment.TopEnd),
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                                ) {
                                    IconButton(
                                        onClick = { onDiscard(job) },
                                        modifier = Modifier.size(32.dp)
                                            .testTag("chat.composer.image.remove.${placement.ordinal}"),
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "移除第 ${placement.ordinal} 张图片",
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            "${placement.ordinal} · ${asset.attachment.name}",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = Tk.spacing.sm, vertical = Tk.spacing.xs),
                        )
                    }
                }
            }
        }
    }
}
