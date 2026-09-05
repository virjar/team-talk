package com.virjar.tk.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.ContentScale
import com.virjar.tk.desktop.media.CachedImageContent
import com.virjar.tk.desktop.media.DesktopSessionResources
import com.virjar.tk.app.ui.bridge.IdentityImageMediaConfig
import com.virjar.tk.app.ui.bridge.LocalIdentityImageMediaConfig

/** 为整个 Desktop 会话 UI 安装一个经过认证的、本地优先的头像渲染器。 */
@Composable
internal fun DesktopIdentityImageProvider(
    resources: DesktopSessionResources,
    presentationGate: DesktopSessionPresentationGate,
    content: @Composable () -> Unit,
) {
    val media = remember(resources, presentationGate) {
        IdentityImageMediaConfig { attachment, modifier ->
            CachedImageContent(
                attachment = attachment,
                resources = resources,
                actionAdmission = presentationGate,
                modifier = modifier,
                contentScale = ContentScale.Crop,
                showStatus = false,
                contentDescription = "头像",
            )
        }
    }
    CompositionLocalProvider(LocalIdentityImageMediaConfig provides media, content = content)
}
