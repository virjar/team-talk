package com.virjar.tk.app.ui.component.input

import androidx.compose.runtime.mutableStateOf

/**
 * 最近使用表情的会话级 FIFO 队列（5 个槽位）。
 *
 * 每次用户选择一个 emoji 时调用 [record]；最近使用的排在最前，去重后截断到 5 个。
 * 会话级（内存）：不做持久化，重启后为空，符合"本次会话内常用的排前面"的预期。
 */
object RecentEmojis {
    private const val MAX = 5
    private val _emojis = mutableStateOf<List<String>>(emptyList())
    val emojis: List<String> get() = _emojis.value

    fun record(emoji: String) {
        val list = _emojis.value.toMutableList()
        list.remove(emoji)
        list.add(0, emoji)
        _emojis.value = list.take(MAX)
    }
}
