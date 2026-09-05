package com.virjar.tk.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowScope
import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.app.ui.screen.GlobalSearchField
import com.virjar.tk.app.ui.theme.Tk

/** Desktop 全局标题栏与连接状态展示。 */
@Composable
internal fun WindowScope.DesktopTitleBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchFocus: () -> Unit,
    focusNonce: Int,
    connectionState: ConnectionState,
    onToggleWindowZoom: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusNonce) {
        if (focusNonce > 0) focusRequester.requestFocus()
    }

    Surface(
        modifier = Modifier.fillMaxWidth().height(Tk.dimens.appBarHeight).testTag("app.titleBar"),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp,
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    WindowDraggableArea(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("app.titleBar.drag.left")
                            .onTitleBarDoubleClick(onToggleWindowZoom),
                    )
                    Row(
                        modifier = Modifier.fillMaxHeight().padding(start = 76.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "TeamTalk",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        )
                    }
                }

                GlobalSearchField(
                    query = query,
                    onQueryChange = onQueryChange,
                    onFocused = onSearchFocus,
                    focusRequester = focusRequester,
                    shortcutLabel = "⌘ K",
                    height = Tk.dimens.globalSearchHeight,
                    modifier = Modifier
                        .widthIn(min = 320.dp, max = 460.dp)
                        .weight(1.35f)
                        .testTag("action.search"),
                )

                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    WindowDraggableArea(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("app.titleBar.drag.right")
                            .onTitleBarDoubleClick(onToggleWindowZoom),
                    )
                    val statusLabel = when (connectionState) {
                        ConnectionState.AUTHENTICATED -> "在线"
                        ConnectionState.CONNECTING -> "连接中"
                        ConnectionState.CONNECTED -> "验证中"
                        ConnectionState.SYNCHRONIZING -> "同步中"
                        ConnectionState.AUTH_FAILED,
                        ConnectionState.DISCONNECTED -> "离线"
                    }
                    val statusColor = when (connectionState) {
                        ConnectionState.AUTHENTICATED -> Tk.colors.online
                        ConnectionState.CONNECTING,
                        ConnectionState.CONNECTED,
                        ConnectionState.SYNCHRONIZING -> MaterialTheme.colorScheme.primary
                        else -> Tk.colors.metaText
                    }
                    Row(
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = Tk.spacing.lg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(RoundedCornerShape(50))
                                .background(statusColor),
                        )
                        Spacer(Modifier.width(Tk.spacing.xs))
                        Text(
                            statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = Tk.colors.secondaryText,
                        )
                    }
                }
            }
            HorizontalDivider(color = Tk.colors.divider)
        }
    }
}
