package com.virjar.tk.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.app.ui.bridge.LocalIdentityImageMediaConfig

/**
 * 头像大图查看（内测反馈：头像在列表/资料页通常被缩小，点按查看原图是常规需求）。
 *
 * 图片经平台注入的认证身份图片加载器渲染（与列表头像同一下载链路），点击任意位置关闭。
 * attachment 为 null 或平台未接线时回退为大号字母占位。
 */
@Composable
fun AvatarViewerDialog(
    title: String,
    attachment: Attachment?,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(onClick = onDismiss)
                .testTag("avatar.viewer")
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val imageContent = LocalIdentityImageMediaConfig.current?.imageContent
            if (imageContent != null && attachment != null) {
                imageContent(
                    attachment,
                    Modifier
                        .fillMaxWidth(0.92f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .testTag("avatar.viewer.image"),
                )
            } else {
                AvatarPlaceholder(
                    name = title,
                    avatar = attachment,
                    size = 280,
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .aspectRatio(1f)
                        .testTag("avatar.viewer.image"),
                )
            }
            Text(
                "点击任意位置关闭",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
