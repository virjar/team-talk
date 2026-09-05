package com.virjar.tk.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.UserAvatarPolicy
import com.virjar.tk.app.ui.bridge.LocalIdentityImageMediaConfig
import com.virjar.tk.app.ui.theme.Tk

internal data class AvatarFallbackStyle(
    val background: Color,
    val foreground: Color,
)

/** 降低饱和度的家族色；每组前景/背景都为小字留出足够对比度。 */
internal val LightAvatarFallbackStyles = listOf(
    AvatarFallbackStyle(Color(0xFFDCE7F8), Color(0xFF27466B)),
    AvatarFallbackStyle(Color(0xFFD9ECE7), Color(0xFF20594F)),
    AvatarFallbackStyle(Color(0xFFF3E5D3), Color(0xFF6B4423)),
    AvatarFallbackStyle(Color(0xFFF2DDDF), Color(0xFF6B3138)),
    AvatarFallbackStyle(Color(0xFFE7E0F3), Color(0xFF493568)),
    AvatarFallbackStyle(Color(0xFFDCEBDD), Color(0xFF31583A)),
    AvatarFallbackStyle(Color(0xFFF1DEE6), Color(0xFF65364A)),
    AvatarFallbackStyle(Color(0xFFDDEAF0), Color(0xFF2D5365)),
)

internal val DarkAvatarFallbackStyles = listOf(
    AvatarFallbackStyle(Color(0xFF33445C), Color(0xFFEAF1FB)),
    AvatarFallbackStyle(Color(0xFF294A43), Color(0xFFE4F4EF)),
    AvatarFallbackStyle(Color(0xFF5A4532), Color(0xFFFFF0DD)),
    AvatarFallbackStyle(Color(0xFF5A383E), Color(0xFFFCE8EA)),
    AvatarFallbackStyle(Color(0xFF473C5D), Color(0xFFF2ECFC)),
    AvatarFallbackStyle(Color(0xFF334A38), Color(0xFFE9F5EA)),
    AvatarFallbackStyle(Color(0xFF593947), Color(0xFFF9E8EF)),
    AvatarFallbackStyle(Color(0xFF344A55), Color(0xFFE8F4F8)),
)

/**
 * 通用头像：圆角方形（squircle，飞书特征）+ 首字母 + 哈希配色。
 *
 * @param name 用于取首字母和哈希配色的名称（建议传显示名）
 * [avatar] 只接受已是 canonical FileStore 相对路径的图片描述符。平台渲染器下载、校验并
 * 原子缓存成功后覆盖占位；加载中、离线未命中或解码失败时始终保留占位。
 */
@Composable
fun AvatarPlaceholder(
    name: String?,
    modifier: Modifier = Modifier,
    size: Int = 48,
    avatar: Attachment? = null,
) {
    val initial = firstDisplayChar(name)
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val fallbackStyle = avatarFallbackStyle(name, darkTheme)

    Box(
        modifier = modifier
            .size(size.dp)
            .clip(Tk.avatarShape(size.dp))
            .background(fallbackStyle.background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.titleMedium,
            color = fallbackStyle.foreground,
            fontWeight = FontWeight.SemiBold,
        )
        acceptedAvatarAttachmentOrNull(avatar)?.let { accepted ->
            LocalIdentityImageMediaConfig.current?.imageContent?.invoke(
                accepted,
                Modifier.fillMaxSize(),
            )
        }
    }
}

/** 在任何平台渲染器解析或下载不可信引用之前即失败关闭（fail closed）。 */
internal fun acceptedAvatarAttachmentOrNull(attachment: Attachment?): Attachment? {
    attachment ?: return null
    return runCatching { UserAvatarPolicy.requireCanonical(attachment) }.getOrNull()
}

internal fun avatarFallbackStyle(name: String?, darkTheme: Boolean): AvatarFallbackStyle {
    val palette = if (darkTheme) DarkAvatarFallbackStyles else LightAvatarFallbackStyles
    val colorIndex = Math.floorMod(name?.hashCode() ?: 0, palette.size)
    return palette[colorIndex]
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
