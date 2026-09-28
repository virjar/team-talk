package com.virjar.tk.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.virjar.tk.protocol.model.ChatType
import com.virjar.tk.app.ui.theme.Tk

/** Android 聊天页头只负责平台导航外壳，聊天内容语义仍由共享 ChatPanel 持有。 */
@Composable
internal fun AndroidChatHeader(
    title: String,
    chatType: Int,
    onBack: () -> Unit,
    onGroupDetail: () -> Unit,
    modifier: Modifier = Modifier,
    /** 1:1 通话入口（协议 minor 0.5）；null 时不显示（非私聊或未就绪）。 */
    onVoiceCall: (() -> Unit)? = null,
    onVideoCall: (() -> Unit)? = null,
) {
    val isGroup = isAndroidGroupChat(chatType)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Tk.dimens.headerHeight)
                    .padding(horizontal = Tk.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("chat.header.back"),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        modifier = Modifier.size(Tk.dimens.iconSize),
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(
                            if (isGroup) {
                                Modifier.clickable(
                                    role = Role.Button,
                                    onClick = onGroupDetail,
                                )
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = Tk.spacing.sm)
                        .testTag("chat.header.title"),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (!isGroup && onVoiceCall != null) {
                    IconButton(
                        onClick = onVoiceCall,
                        modifier = Modifier.testTag("chat.call.voice"),
                    ) {
                        Icon(
                            Icons.Filled.Call,
                            contentDescription = "语音通话",
                            tint = Tk.colors.secondaryText,
                            modifier = Modifier.size(Tk.dimens.iconSize),
                        )
                    }
                }
                if (!isGroup && onVideoCall != null) {
                    IconButton(
                        onClick = onVideoCall,
                        modifier = Modifier.testTag("chat.call.video"),
                    ) {
                        Icon(
                            Icons.Filled.VideoCall,
                            contentDescription = "视频通话",
                            tint = Tk.colors.secondaryText,
                            modifier = Modifier.size(Tk.dimens.iconSize),
                        )
                    }
                }
                if (isGroup) {
                    IconButton(
                        onClick = onGroupDetail,
                        modifier = Modifier.testTag("chat.group.detail"),
                    ) {
                        // 与 Desktop 群设置入口统一使用齿轮语义（T003）。
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "群设置",
                            tint = Tk.colors.secondaryText,
                            modifier = Modifier.size(Tk.dimens.iconSize),
                        )
                    }
                }
            }
            HorizontalDivider(color = Tk.colors.divider)
        }
    }
}

internal fun isAndroidGroupChat(chatType: Int): Boolean =
    ChatType.fromCode(chatType) == ChatType.GROUP
