package com.virjar.tk.ui.component.input

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import com.virjar.tk.ui.theme.Tk

/**
 * @ 补全（doc/05-clients/rich-content.md）。
 *
 * 选中候选后向输入框写入完整链接语法 `@[名](mention://uid) `；输入框用
 * [MentionVisualTransformation] 把语法**视觉折叠**为 `@名` 高亮块——显示干净，
 * 发送原文（RICH_TEXT 工厂零转换）。Slack/Discord 的"语法在、显示美"模式。
 */

/** mention 内联语法（与 shared buildRichTextBody 的正则保持一致） */
private val MENTION_SYNTAX = Regex("""@\[([^\]]*)\]\(mention://([^)\s]+)\)""")

// ── 光标上下文检测 ──

/** @ 触发的补全查询：光标前最近的 `@`（前须为行首/空白），@ 与光标之间为查询词 */
data class MentionQuery(val atIndex: Int, val text: String)

/** 行首 `/` 触发的指令补全查询 */
data class SlashQuery(val text: String)

fun detectMentionQuery(field: TextFieldValue): MentionQuery? {
    val text = field.text
    val pos = field.selection.min
    // 光标前最近一个 @（限制在同段内：中间不能有空白/换行）
    var at = -1
    for (i in pos - 1 downTo 0) {
        val c = text[i]
        if (c == '@') { at = i; break }
        if (c == ' ' || c == '\n' || c == '\t') return null
    }
    if (at < 0) return null
    // @ 前须为行首或空白（邮箱/代码中的 @ 不触发）
    if (at > 0) {
        val prev = text[at - 1]
        if (prev != ' ' && prev != '\n' && prev != '\t') return null
    }
    return MentionQuery(atIndex = at, text = text.substring(at + 1, pos))
}

fun detectSlashQuery(field: TextFieldValue): SlashQuery? {
    val text = field.text
    val pos = field.selection.min
    // 光标必须在本行内且行首是 /，token 中无空白
    val lineStart = text.lastIndexOf('\n', (pos - 1).coerceAtLeast(0)).let { if (pos == 0) 0 else it + 1 }
    if (lineStart >= text.length || text.getOrNull(lineStart) != '/') return null
    val token = text.substring(lineStart + 1, pos)
    if (token.contains(' ') || token.contains('\n')) return null
    return SlashQuery("/$token")
}

// ── 视觉折叠：@[名](mention://uid) → @名 高亮块 ──

/** @param highlightColor mention 高亮色（构造于 composable 上下文，transform 内不可读主题） */
class MentionVisualTransformation(
    private val highlightColor: androidx.compose.ui.graphics.Color,
    private val highlightBg: androidx.compose.ui.graphics.Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val original = text.text
        val matches = MENTION_SYNTAX.findAll(original).toList()
        if (matches.isEmpty()) return TransformedText(text, OffsetMapping.Identity)

        data class Seg(val origStart: Int, val origEnd: Int, val transStart: Int, val transEnd: Int)

        val segments = mutableListOf<Seg>()
        val transformed: AnnotatedString = buildAnnotatedString {
            var orig = 0
            for (m in matches) {
                val before = original.substring(orig, m.range.first)
                append(before)
                val name = m.groupValues[1].ifBlank { m.groupValues[2] }
                val shown = "@$name"
                val transStart = length
                pushStyle(
                    SpanStyle(
                        color = highlightColor,
                        background = highlightBg,
                        fontWeight = FontWeight.Medium,
                    )
                )
                append(shown)
                pop()
                segments += Seg(m.range.first, m.range.last + 1, transStart, length)
                orig = m.range.last + 1
            }
            append(original.substring(orig))
        }

        return TransformedText(
            transformed,
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int {
                    var delta = 0
                    for (s in segments) {
                        when {
                            offset >= s.origEnd -> delta += s.transEnd - s.transStart - (s.origEnd - s.origStart)
                            offset > s.origStart -> return s.transStart + (offset - s.origStart).coerceAtMost(s.transEnd - s.transStart) + delta
                            else -> return offset + delta
                        }
                    }
                    return offset + delta
                }

                override fun transformedToOriginal(offset: Int): Int {
                    for (s in segments) {
                        when {
                            offset >= s.transEnd -> { /* continue with delta applied below */ }
                            offset >= s.transStart -> {
                                // 折叠段内：光标落在段首/段尾（段中不可停留）
                                val within = offset - s.transStart
                                return if (within < (s.transEnd - s.transStart) / 2) s.origStart else s.origEnd
                            }
                            else -> return offset + (s.origStart - s.transStart)
                        }
                    }
                    // 尾部：减去累计折叠差
                    var delta = 0
                    for (s in segments) delta += (s.origEnd - s.origStart) - (s.transEnd - s.transStart)
                    return offset + delta
                }
            },
        )
    }
}

// ── 补全覆盖层（内嵌展开式：出现在输入行上方，不遮挡消息列表） ──

@Composable
fun AutoCompleteOverlay(
    title: String,
    items: List<AutoCompleteItem>,
    onPick: (AutoCompleteItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = Tk.spacing.xs,
        tonalElevation = Tk.spacing.xs,
        shape = MaterialTheme.shapes.small,
    ) {
        Column {
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = Tk.colors.metaText,
                modifier = Modifier.padding(horizontal = Tk.spacing.md, vertical = Tk.spacing.xs),
            )
            items.take(5).forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Tk.spacing.xxl + Tk.spacing.xs)
                        .clickable(onClick = { onPick(item) }),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(item.label, style = MaterialTheme.typography.bodyMedium)
                    if (item.hint != null) {
                        Spacer(Modifier.width(Tk.spacing.sm))
                        Text(item.hint, style = MaterialTheme.typography.labelSmall, color = Tk.colors.metaText)
                    }
                }
            }
        }
    }
}

data class AutoCompleteItem(val label: String, val hint: String? = null, val payload: String)

// ── / 指令注册表（本地指令；未识别的 / 消息原样透传，未来服务端/bot 解析） ──

data class SlashCommand(val command: String, val desc: String, val expansion: String? = null)

val SlashCommands = listOf(
    SlashCommand("/shrug", "在消息末尾追加 ¯\\_(ツ)_/¯", " ¯\\_(ツ)_/¯"),
    SlashCommand("/todo", "插入待办模板", "- [ ] 事项一\n- [ ] 事项二"),
    SlashCommand("/code", "插入代码块", "```\n\n```"),
)

/** 展开指令：返回替换整行输入的文本（null=透传原样发送） */
fun expandSlashCommand(rawLine: String): String? {
    val spec = SlashCommands.firstOrNull { it.command == rawLine.trim().split(" ").firstOrNull() } ?: return null
    return when {
        spec.expansion == null -> null
        spec.command == "/shrug" -> {
            // /shrug msg → msg ¯\_(ツ)_/¯；裸 /shrug → ¯\_(ツ)_/¯
            val rest = rawLine.trim().removePrefix("/shrug").trim()
            (rest.ifEmpty { "¯\\_(ツ)_/¯" }) + if (rest.isNotEmpty()) " ¯\\_(ツ)_/¯" else ""
        }
        else -> spec.expansion
    }
}
