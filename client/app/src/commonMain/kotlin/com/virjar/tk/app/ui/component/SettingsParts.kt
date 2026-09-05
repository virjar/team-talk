package com.virjar.tk.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.virjar.tk.app.ui.theme.Tk
import com.virjar.tk.app.ui.theme.ThemeMode
import com.virjar.tk.app.ui.theme.TkTheme

/** 可选的自动化语义标识：null 时不挂 testTag。 */
private fun Modifier.tag(name: String?): Modifier =
    if (name != null) then(Modifier.testTag(name)) else this

/**
 * 设置体系共享零件（Android 全屏页与 Desktop 设置模态共用）：
 * 分组标签、分组卡片、图标入口行、填充式输入框、危险动作按钮、主题分段选择器。
 *
 * 视觉语言：层级来自表面与留白（背景上的白色分组卡片），品牌蓝只用于动作与选中态。
 * Android 在页面背景上使用这些零件，Desktop 在设置模态的 background 底色上使用同一套。
 */

/** 分组标题（如"账号""安全"），弱化为 meta 小字。 */
@Composable
fun SettingsSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Tk.colors.metaText,
        modifier = modifier.padding(start = 4.dp, bottom = 4.dp),
    )
}

/** 分组卡片：surface 白底 + 12dp 圆角，内部条目自带留白。 */
@Composable
fun SettingsGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(4.dp), content = content)
    }
}

/**
 * 图标 + 标题 + 描述的设置入口行；hover 显示令牌灰底（桌面）。
 * [tag] 用于自动化语义树；未传时不挂 testTag。
 */
@Composable
fun SettingsEntryRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    showChevron: Boolean = true,
    tag: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val hoverInteraction = remember { MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .hoverable(hoverInteraction)
            .background(if (hovered) Tk.colors.hover else Color.Transparent)
            .clickable(
                interactionSource = hoverInteraction,
                indication = null,
                onClick = onClick,
            )
            .tag(tag)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Tk.colors.secondaryText,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (description != null) {
                Spacer(Modifier.height(1.dp))
                Text(description, style = MaterialTheme.typography.bodySmall, color = Tk.colors.secondaryText)
            }
        }
        trailing()
        if (showChevron) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = Tk.colors.metaText,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 危险动作按钮（退出登录）：红色描边卡片，hover 加深边框。 */
@Composable
fun SettingsDangerAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tag: String? = null,
) {
    val hoverInteraction = remember { MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .hoverable(hoverInteraction)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.error.copy(alpha = if (hovered) 1f else 0.45f),
                shape = MaterialTheme.shapes.medium,
            )
            .clickable(
                interactionSource = hoverInteraction,
                indication = null,
                onClick = onClick,
            )
            .tag(tag),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** 底部主操作按钮（保存/确认），全宽。 */
@Composable
fun SettingsPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tag: String? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(),
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .tag(tag),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * 填充式输入框（飞书风格）：surfaceVariant 底、8dp 圆角、聚焦时主色描边。
 * 封装统一的设置表单输入观感，避免逐屏堆叠默认描边输入框。
 */
@Composable
fun TkFormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    enabled: Boolean = true,
    tag: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        enabled = enabled,
        visualTransformation = visualTransformation,
        shape = MaterialTheme.shapes.small,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            unfocusedBorderColor = Color.Transparent,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            disabledBorderColor = Color.Transparent,
        ),
        modifier = modifier
            .fillMaxWidth()
            .tag(tag),
    )
}

/**
 * 主题模式分段选择器（跟随系统/浅色/深色），切换立即生效。
 * 选中态使用品牌蓝（Tk.colors.selected），避免 M3 默认 secondaryContainer 的青绿撞色；
 * 段内不做图标占位，保证文字居中且不贴边。
 */
@Composable
fun ThemeSegmentedSelector(modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        val colors = SegmentedButtonDefaults.colors(
            activeContainerColor = Tk.colors.selected,
            activeContentColor = MaterialTheme.colorScheme.primary,
            activeBorderColor = MaterialTheme.colorScheme.primary,
        )
        ThemeMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = TkTheme.mode == mode,
                onClick = { TkTheme.set(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size),
                colors = colors,
                icon = {},
                modifier = Modifier.testTag("settings.appearance.${mode.name}"),
            ) {
                Text(mode.label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** 圆形小图标按钮（模态/卡片右上角关闭等），统一 36dp 命中区域。 */
@Composable
fun SettingsIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .tag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(18.dp))
    }
}
