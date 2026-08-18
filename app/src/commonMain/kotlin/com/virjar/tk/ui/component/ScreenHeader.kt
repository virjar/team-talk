package com.virjar.tk.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.virjar.tk.ui.theme.Tk

/**
 * 子页面通用头部。固定 56dp、无抬升、底部发丝线，与主窗口的扁平壳层一致。
 *
 * 统一封装，避免每个 Screen 各自手写头部导致风格不一致。
 * onBack 非空显示返回箭头；标题用 Box 独立居中，不受左右操作宽度影响。
 *
 * @param title 标题文字
 * @param onBack 返回回调。null 时不渲染返回按钮
 * @param trailing 右侧操作槽（如"保存"/"确认"按钮），默认空
 */
@Composable
fun ScreenHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        androidx.compose.foundation.layout.Column {
            Box(modifier = Modifier.fillMaxWidth().height(56.dp)) {
                if (onBack != null) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.align(Alignment.CenterStart).size(48.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.Center),
                )
                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    verticalAlignment = Alignment.CenterVertically,
                    content = trailing,
                )
            }
            HorizontalDivider(color = Tk.colors.divider)
        }
    }
}
