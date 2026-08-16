package com.virjar.tk.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.virjar.tk.ui.theme.Tk

/** 头像配色（与设计令牌一致的家族色），按名称哈希稳定取色。 */
private val AvatarColors = listOf(
    0xFF3370FF, 0xFF00B89A, 0xFFFF7D00, 0xFFF54A45,
    0xFF7B61FF, 0xFF00A870, 0xFFE6294A, 0xFF3491FA,
)

/**
 * 通用头像：圆角方形（squircle，飞书特征）+ 首字母 + 哈希配色。
 *
 * @param name 用于取首字母和哈希配色的名称（建议传显示名）
 * @param url 真实头像地址（预留：后端 updateProfile 支持 avatar 后启用，当前忽略）
 */
@Composable
fun AvatarPlaceholder(
    name: String?,
    modifier: Modifier = Modifier,
    size: Int = 48,
    url: String? = null,
) {
    val initial = firstDisplayChar(name)
    val colorIdx = (name?.hashCode() ?: 0).let { Math.floorMod(it, AvatarColors.size) }
    val avatarColor = Color(AvatarColors[colorIdx])

    Box(
        modifier = modifier
            .size(size.dp)
            .clip(Tk.avatarShape(size.dp))
            .background(avatarColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * 从名称中提取第一个适合头像展示的字符。
 *
 * 跳过 emoji（surrogate pair）和不可见字符，优先取字母/数字/中文。
 * 全是 emoji/符号时返回 "?"。
 */
internal fun firstDisplayChar(name: String?): String {
    if (name.isNullOrBlank()) return "?"
    // 遍历 Unicode 码点（codePointAt 正确处理 surrogate pair）
    var i = 0
    while (i < name.length) {
        val cp = name.codePointAt(i)
        val charCount = Character.charCount(cp)
        // 字母、数字、中文（CJK）、其他常用文字字母
        if (Character.isLetterOrDigit(cp) || isCjkCodePoint(cp)) {
            return String(Character.toChars(cp))
        }
        i += charCount
    }
    return "?"
}

/** 是否是 CJK（中日韩）统一表意文字范围。 */
private fun isCjkCodePoint(cp: Int): Boolean =
    cp in 0x4E00..0x9FFF ||   // CJK 统一表意文字
        cp in 0x3400..0x4DBF ||  // CJK 扩展 A
        cp in 0x3000..0x303F     // CJK 符号和标点（含「」等）
