package com.virjar.tk.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.virjar.tk.app.ui.theme.Tk

/**
 * 未读计数徽标：pill 形（高 16dp），unreadBadge 底白字，99+ 封顶。
 * 单数字为圆形，两位数自动拉伸为胶囊。
 */
@Composable
fun UnreadBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    if (count <= 0) return
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
            .background(Tk.colors.unreadBadge, CircleShape)
            .padding(horizontal = if (count > 9) 5.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else "$count",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
        )
    }
}
