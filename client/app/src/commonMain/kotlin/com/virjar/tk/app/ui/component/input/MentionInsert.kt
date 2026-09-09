package com.virjar.tk.app.ui.component.input

import androidx.compose.ui.text.TextRange
import com.mohamedrejeb.richeditor.model.RichTextState
import com.virjar.tk.protocol.model.User

/**
 * mention 补全的共享工具：候选过滤、候选行构造与“光标处写回 mention 链接”。
 *
 * 聊天输入器（ChatScreen/ChatComposer）与文档块编辑器（DocumentRichRunEditor 等）
 * 共用同一条 `@[显示名](mention://uid)` 语法；本文件是它们之间唯一的逻辑落点，
 * 改动会同时影响两处产品语义。
 */

fun mentionDisplayName(user: User): String = user.name.ifBlank { user.username.ifBlank { user.uid } }

/** 按查询词过滤候选：排除自己，名字/username/uid 大小写不敏感包含；查询为空时全量给出。 */
fun filterMentionCandidates(
    candidates: List<User>,
    query: MentionQuery?,
    myUid: String?,
): List<User> {
    val term = query?.text.orEmpty().trim()
    return candidates
        .filter { it.uid != myUid }
        .filter { u ->
            term.isEmpty() || mentionDisplayName(u).contains(term, ignoreCase = true) ||
                u.username.contains(term, ignoreCase = true) || u.uid.contains(term)
        }
}

fun mentionAutoCompleteItems(candidates: List<User>): List<AutoCompleteItem> = candidates.map { u ->
    AutoCompleteItem(
        label = mentionDisplayName(u),
        hint = "@" + u.username.ifBlank { u.uid },
        payload = u.uid,
    )
}

/**
 * 把选中的候选以 mention 链接写回富文本光标处，并把光标落到插入文本之后。
 * 与聊天可视化分支语义一致：先替换 `@查询词`，再对刚写入的显示名区间挂 `mention://` 链接。
 */
fun pickMentionIntoRichState(
    state: RichTextState,
    query: MentionQuery,
    cursor: Int,
    user: User,
) {
    val displayName = mentionDisplayName(user)
    val displayText = "@$displayName "
    state.replaceRange(query.atIndex, cursor, displayText)
    state.addLinkToTextRange(
        url = "mention://${user.uid}",
        textRange = TextRange(query.atIndex + 1, query.atIndex + 1 + displayName.length),
    )
    state.selection = TextRange(query.atIndex + displayText.length)
}
