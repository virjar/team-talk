package com.virjar.tk.app.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.virjar.tk.app.ui.theme.Tk

/** 计数来自服务端完整条件查询；查看提醒不会隐藏仍未完成的待办。 */
@Composable
fun TaskAttentionBanner(
    total: Long,
    overdue: Long,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    group: Boolean = false,
    stale: Boolean = false,
) {
    if (total <= 0) return
    val tag = if (group) "task.attention.group" else "task.attention.assigned"
    Surface(
        modifier = modifier.fillMaxWidth().testTag(tag),
        color = if (overdue > 0) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(Modifier.padding(horizontal = Tk.spacing.md, vertical = Tk.spacing.xs),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tk.spacing.sm)) {
            Column(Modifier.weight(1f)) {
                Text(if (group) "群内有 $total 项待办未完成" else "你有 $total 项待办未完成",
                    style = MaterialTheme.typography.labelLarge)
                if (overdue > 0 || stale) Text(
                    listOfNotNull(if (overdue > 0) "$overdue 项已逾期" else null, if (stale) "本地记录，联网后更新" else null).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            TextButton(onClick = onOpen, modifier = Modifier.testTag("$tag.open")) { Text("查看待办") }
        }
    }
}

/** 开始和截止是时间条件；前台定期核对，后台只依赖既有持久事件。 */
@Composable
fun TaskAttentionRefresh(
    attention: com.virjar.tk.app.navigation.feature.task.TaskAttentionState,
    active: Boolean,
) {
    androidx.compose.runtime.LaunchedEffect(attention, active) {
        if (active) while (true) {
            attention.refresh()
            kotlinx.coroutines.delay(30_000)
        }
    }
}
