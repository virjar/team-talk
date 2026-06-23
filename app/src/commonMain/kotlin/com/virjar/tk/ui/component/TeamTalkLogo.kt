package com.virjar.tk.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * TeamTalk 品牌 Logo ——「团队节点网络」。
 *
 * 用 Compose 矢量绘制（与 design/logo/svg/logo-main.svg 一致）：4 个节点用线连接排成 T 字形，
 * 中央焦点节点最大。无需位图资源，无损缩放，复用 AppTheme 品牌色。
 *
 * @param size logo 整体边长
 * @param tint 节点/连线颜色（默认白色，用于品牌蓝背景上）
 */
@Composable
fun TeamTalkLogo(
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    tint: Color = Color.White,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val s = this.size.minDimension    // 画布边长（px）
            // logo 几何参数（与 SVG 一致，坐标系 0..1024 映射到 0..s）
            val p = { v: Float -> v / 1024f * s }
            val strokeW = p(22f)
            val line = p(22f)              // stroke width
            val nodeR = p(58f)             // 普通节点半径
            val focusR = p(76f)            // 中央焦点节点半径

            // 坐标
            val left = Offset(p(316f), p(372f))
            val right = Offset(p(708f), p(372f))
            val center = Offset(p(512f), p(372f))
            val bottom = Offset(p(512f), p(708f))

            // 连接线（T 字形骨架）
            drawLine(tint, left, right, strokeWidth = line)
            drawLine(tint, center, bottom, strokeWidth = line)

            // 普通节点
            listOf(left, right, bottom).forEach { o -> drawCircle(tint, nodeR, o) }
            // 中央焦点节点（最大）
            drawCircle(tint, focusR, center)
        }
    }
}
