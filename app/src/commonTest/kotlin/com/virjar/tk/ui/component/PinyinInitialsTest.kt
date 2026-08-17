package com.virjar.tk.ui.component

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * GB2312 分区表拼音首字母（通讯录分组）。
 */
class PinyinInitialsTest {

    @Test
    fun `common chinese surnames map to expected initials`() {
        assertEquals('Z', PinyinInitials.initialOf("张三"))
        assertEquals('W', PinyinInitials.initialOf("王五"))
        assertEquals('L', PinyinInitials.initialOf("李四"))
        assertEquals('C', PinyinInitials.initialOf("陈晨"))
        assertEquals('L', PinyinInitials.initialOf("刘备"))
        assertEquals('Z', PinyinInitials.initialOf("中文名"))
    }

    @Test
    fun `latin and digit names`() {
        assertEquals('A', PinyinInitials.initialOf("alice"))
        assertEquals('B', PinyinInitials.initialOf("Bob"))
        assertEquals('#', PinyinInitials.initialOf("12306"))
    }

    @Test
    fun `leading emoji and symbols are skipped`() {
        // 首字符是 emoji/符号：取其后第一个可用字符
        assertEquals('Z', PinyinInitials.initialOf("🤖张三"))
        assertEquals('A', PinyinInitials.initialOf("★alice"))
        // 全部不可用 → #
        assertEquals('#', PinyinInitials.initialOf("🤖🚀"))
        assertEquals('#', PinyinInitials.initialOf(""))
    }
}
