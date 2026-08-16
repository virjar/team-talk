package com.virjar.tk.ui.component.rich

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.unit.dp
import com.virjar.tk.body.CardBlock
import com.virjar.tk.body.CardPayload
import com.virjar.tk.ui.theme.Tk

/**
 * 静态交互卡片（三期渲染；按钮回调四期接入 cardAction RPC 后生效）。
 * 视觉语言与代码块一致：气泡 contentColor 12% 叠层圆角卡。
 */
@Composable
fun InteractiveCardView(card: CardPayload, modifier: Modifier = Modifier) {
    val contentColor = LocalContentColor.current
    Surface(
        modifier = modifier.widthIn(max = 280.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = contentColor.copy(alpha = 0.12f),
    ) {
        Column(Modifier.padding(Tk.spacing.md)) {
            card.title?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor,
                    modifier = Modifier.padding(bottom = Tk.spacing.xs),
                )
            }
            card.blocks.forEach { block ->
                when (block) {
                    is CardBlock.Text -> Text(
                        block.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.9f),
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}
