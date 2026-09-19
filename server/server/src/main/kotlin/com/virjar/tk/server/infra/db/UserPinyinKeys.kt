package com.virjar.tk.server.infra.db

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType

/**
 * 姓名拼音搜索键派生（内测 T062）。
 *
 * 全拼 = 逐字符小写串联（CJK 取常用读音，字母/数字原样），如「张三」→ `zhangsan`；
 * 首拼 = 每个 CJK 字取读音首字母、字母/数字原样，如「张三」→ `zs`。
 * 多音字取 pinyin4j 的第一个常用读音，v1 可接受；非 CJK 字母/数字原样保留。
 */
private val hanyuPinyinFormat = HanyuPinyinOutputFormat().apply {
    caseType = HanyuPinyinCaseType.LOWERCASE
    vCharType = HanyuPinyinVCharType.WITH_V
    toneType = HanyuPinyinToneType.WITHOUT_TONE
}

private fun readingsOf(char: Char): String? = runCatching {
    PinyinHelper.toHanyuPinyinStringArray(char, hanyuPinyinFormat)?.firstOrNull()
}.getOrNull()

internal fun derivePinyinFull(name: String): String = buildString {
    for (char in name) {
        if (!char.isLetterOrDigit()) continue
        if (char.code < 0x2E80) {
            append(char.lowercaseChar())
        } else {
            append(readingsOf(char) ?: char.lowercaseChar())
        }
    }
}

internal fun derivePinyinInitials(name: String): String = buildString {
    for (char in name) {
        if (!char.isLetterOrDigit()) continue
        if (char.code < 0x2E80) {
            append(char.lowercaseChar())
        } else {
            append(readingsOf(char)?.firstOrNull() ?: char.lowercaseChar())
        }
    }
}
