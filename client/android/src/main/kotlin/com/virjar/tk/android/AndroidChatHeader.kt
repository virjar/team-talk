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
import androidx.compose.material.icons.filled.Info
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

                if (isGroup) {
                    IconButton(
                        onClick = onGroupDetail,
                        modifier = Modifier.testTag("chat.group.detail"),
                    ) {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = "群聊详情",
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
