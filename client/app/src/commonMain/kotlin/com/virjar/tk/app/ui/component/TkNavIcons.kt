package com.virjar.tk.app.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 一级导航专属矢量图标（内测反馈：通用图标里会话/文档/任务都是"矩形+横线"，辨识度差）。
 *
 * 四个剪影刻意拉开：圆气泡+圆点（会话）、人形（通讯录）、折角页+文字行（文档）、
 * 圆角板+对勾（任务）。每枚提供 filled（选中，实面+镂空细节）与 outlined
 * （未选中，描边+实心细节）两套，沿用 T009 的选中/未选中配对约定。
 * 手绘 PathData 以 24dp 网格为基准；描边 1.7、圆头圆角，与系统图标粗细一致。
 */
object TkNavIcons {
    /** 会话：圆气泡 + 三圆点（镂空）。 */
    val ChatsFilled: ImageVector by lazy {
        navIcon("TkChatsFilled") {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                bubble()
                dot(7.8f, 10.6f)
                dot(12f, 10.6f)
                dot(16.2f, 10.6f)
            }
        }
    }

    val ChatsOutlined: ImageVector by lazy {
        navIcon("TkChatsOutlined") {
            path(
                stroke = SolidColor(Color.Black), strokeLineWidth = STROKE_WIDTH,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            ) { bubble() }
            path(fill = SolidColor(Color.Black)) {
                dot(7.8f, 10.6f)
                dot(12f, 10.6f)
                dot(16.2f, 10.6f)
            }
        }
    }

    /** 通讯录：人形半身像。 */
    val ContactsFilled: ImageVector by lazy {
        navIcon("TkContactsFilled") {
            path(fill = SolidColor(Color.Black)) {
                head()
                shoulders()
            }
        }
    }

    val ContactsOutlined: ImageVector by lazy {
        navIcon("TkContactsOutlined") {
            path(
                stroke = SolidColor(Color.Black), strokeLineWidth = STROKE_WIDTH,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            ) { head() }
            path(
                stroke = SolidColor(Color.Black), strokeLineWidth = STROKE_WIDTH,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            ) { shouldersOutline() }
        }
    }

    /** 文档：折角页 + 两行文字（折角是与"任务板"区分的关键剪影）。 */
    val DocumentsFilled: ImageVector by lazy {
        navIcon("TkDocumentsFilled") {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                page()
                foldTriangle()
                textBar(11.85f)
                textBar(15.05f)
            }
        }
    }

    val DocumentsOutlined: ImageVector by lazy {
        navIcon("TkDocumentsOutlined") {
            path(
                stroke = SolidColor(Color.Black), strokeLineWidth = STROKE_WIDTH,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            ) { page() }
            path(
                stroke = SolidColor(Color.Black), strokeLineWidth = STROKE_WIDTH,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(13.6f, 3.5f)
                lineTo(13.6f, 8.04f)
                lineTo(16.8f, 8.04f)
            }
            path(fill = SolidColor(Color.Black)) {
                textBar(11.85f)
                textBar(15.05f)
            }
        }
    }

    /** 任务：圆角板 + 大对勾（镂空）。 */
    val TasksFilled: ImageVector by lazy {
        navIcon("TkTasksFilled") {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                board()
                checkRibbon()
            }
        }
    }

    val TasksOutlined: ImageVector by lazy {
        navIcon("TkTasksOutlined") {
            path(
                stroke = SolidColor(Color.Black), strokeLineWidth = STROKE_WIDTH,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            ) { board() }
            path(fill = SolidColor(Color.Black)) { checkRibbon() }
        }
    }

    private const val STROKE_WIDTH = 1.7f

    private fun navIcon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply(block).build()

    // ── 几何片段 ──

    /** 圆气泡 + 左下尾巴。 */
    private fun PathBuilder.bubble() {
        moveTo(12f, 3.2f)
        curveTo(7.4f, 3.2f, 3.4f, 6.4f, 3.4f, 10.6f)
        curveTo(3.4f, 12.9f, 4.5f, 14.9f, 6.2f, 16.3f)
        lineTo(6.2f, 19.6f)
        curveTo(6.2f, 20.1f, 6.8f, 20.4f, 7.3f, 20.1f)
        lineTo(10.6f, 17.9f)
        curveTo(11.05f, 17.97f, 11.5f, 18f, 12f, 18f)
        curveTo(16.6f, 18f, 20.6f, 14.8f, 20.6f, 10.6f)
        curveTo(20.6f, 6.4f, 16.6f, 3.2f, 12f, 3.2f)
        close()
    }

    private fun PathBuilder.dot(cx: Float, cy: Float, r: Float = 1.15f) {
        moveTo(cx + r, cy)
        arcToRelative(r, r, 0f, false, true, -2 * r, 0f)
        arcToRelative(r, r, 0f, false, true, 2 * r, 0f)
        close()
    }

    private fun PathBuilder.head() {
        moveTo(15.5f, 7.7f)
        arcToRelative(3.5f, 3.5f, 0f, false, true, -7f, 0f)
        arcToRelative(3.5f, 3.5f, 0f, false, true, 7f, 0f)
        close()
    }

    /** 实心肩部圆顶（底边封口）。 */
    private fun PathBuilder.shoulders() {
        moveTo(5.3f, 19.6f)
        curveTo(5.3f, 15.8f, 8.3f, 13.8f, 12f, 13.8f)
        curveTo(15.7f, 13.8f, 18.7f, 15.8f, 18.7f, 19.6f)
        close()
    }

    /** 描边肩部弧线（开口，不封底）。 */
    private fun PathBuilder.shouldersOutline() {
        moveTo(5.6f, 19.5f)
        curveTo(5.7f, 15.9f, 8.4f, 14.55f, 12f, 14.55f)
        curveTo(15.6f, 14.55f, 18.3f, 15.9f, 18.4f, 19.5f)
    }

    /** 折角页：右上角斜切。 */
    private fun PathBuilder.page() {
        moveTo(13.6f, 3.5f)
        lineTo(8.3f, 3.5f)
        curveTo(7.1f, 3.5f, 6.1f, 4.5f, 6.1f, 5.7f)
        lineTo(6.1f, 18.3f)
        curveTo(6.1f, 19.5f, 7.1f, 20.5f, 8.3f, 20.5f)
        lineTo(15.7f, 20.5f)
        curveTo(16.9f, 20.5f, 17.9f, 19.5f, 17.9f, 18.3f)
        lineTo(17.9f, 9.6f)
        close()
    }

    /** 折角缺口（与斜边围出的小三角）。 */
    private fun PathBuilder.foldTriangle() {
        moveTo(13.6f, 3.5f)
        lineTo(13.6f, 8.04f)
        lineTo(16.8f, 8.04f)
        close()
    }

    /** 圆头文字行：x 8.7..15.3，高 1.5。 */
    private fun PathBuilder.textBar(top: Float) {
        val mid = top + 0.75f
        moveTo(9.45f, top)
        lineTo(14.55f, top)
        arcTo(0.75f, 0.75f, 0f, false, true, 15.3f, mid)
        arcTo(0.75f, 0.75f, 0f, false, true, 14.55f, top + 1.5f)
        lineTo(9.45f, top + 1.5f)
        arcTo(0.75f, 0.75f, 0f, false, true, 8.7f, mid)
        arcTo(0.75f, 0.75f, 0f, false, true, 9.45f, top)
        close()
    }

    /** 任务板：圆角方形。 */
    private fun PathBuilder.board() {
        moveTo(6.4f, 4.2f)
        lineTo(17.6f, 4.2f)
        arcTo(2.2f, 2.2f, 0f, false, true, 19.8f, 6.4f)
        lineTo(19.8f, 17.6f)
        arcTo(2.2f, 2.2f, 0f, false, true, 17.6f, 19.8f)
        lineTo(6.4f, 19.8f)
        arcTo(2.2f, 2.2f, 0f, false, true, 4.2f, 17.6f)
        lineTo(4.2f, 6.4f)
        arcTo(2.2f, 2.2f, 0f, false, true, 6.4f, 4.2f)
        close()
    }

    /** 对勾折带（material check 等比缩放），单子路径可整体镂空。 */
    private fun PathBuilder.checkRibbon() {
        moveTo(10.02f, 14.93f)
        lineTo(7.19f, 12.1f)
        lineTo(6.22f, 13.05f)
        lineTo(10.02f, 16.86f)
        lineTo(18.18f, 8.7f)
        lineTo(17.22f, 7.74f)
        close()
    }
}
