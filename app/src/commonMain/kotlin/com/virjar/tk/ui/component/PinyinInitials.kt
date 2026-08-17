package com.virjar.tk.ui.component

/**
 * 名称 → 拼音首字母（通讯录字母分组）。
 *
 * GB2312 一级汉字（3755 个）按拼音序排列，按 GBK 编码分区映射首字母——
 * 纯 JVM 实现（Charset GBK 双平台可用），无需引入 pinyin 库。多音字取
 * 常用读音（分区表的固有近似，通讯录分组可接受）。
 */
object PinyinInitials {

    // 各字母段起始 GBK 编码（锚点：啊B0A1 芭B0C5 擦B2C1 搭B4EE 蛾B6EA 发B7A2
    // 噶B8C1 哈B9FE 击BBF7 喀BFA6 垃C0AC 妈C2E8 拿C4C3 哦C5B6 啪C5BE 期C6DA
    // 然C8BB 撒C8F6 塌CBFA 挖CDDA 昔CEF4 压D1B9 匝D4D1；降序匹配第一个 ≤code 的段）
    private val sections = listOf(
        0xB0A1 to 'A', 0xB0C5 to 'B', 0xB2C1 to 'C', 0xB4EE to 'D', 0xB6EA to 'E',
        0xB7A2 to 'F', 0xB8C1 to 'G', 0xB9FE to 'H', 0xBBF7 to 'J', 0xBFA6 to 'K',
        0xC0AC to 'L', 0xC2E8 to 'M', 0xC4C3 to 'N', 0xC5B6 to 'O', 0xC5BE to 'P',
        0xC6DA to 'Q', 0xC8BB to 'R', 0xC8F6 to 'S', 0xCBFA to 'T', 0xCDDA to 'W',
        0xCEF4 to 'X', 0xD1B9 to 'Y', 0xD4D1 to 'Z',
    ).sortedByDescending { it.first }

    private const val GBK_MAX = 0xF7FE  // GB2312 汉字区上界

    /** 取名称的首字母：跳过 emoji/符号，数字归 `#`，无可用字符返回 `#`。 */
    fun initialOf(name: String): Char {
        name.forEach { ch ->
            initialOfChar(ch)?.let { return it }
        }
        return '#'
    }

    fun initialOfChar(ch: Char): Char? {
        if (ch in 'a'..'z') return ch.uppercaseChar()
        if (ch in 'A'..'Z') return ch
        if (ch in '0'..'9') return '#'
        if (ch.code < 0x3400) return null  // 符号/emoji 等跳过
        return try {
            val bytes = ch.toString().toByteArray(charset("GBK"))
            if (bytes.size != 2) return null  // 不可编码（替换为 '?' 单字节）
            val code = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
            if (code > GBK_MAX) null
            else sections.firstOrNull { code >= it.first }?.second
        } catch (_: Exception) {
            null
        }
    }
}
