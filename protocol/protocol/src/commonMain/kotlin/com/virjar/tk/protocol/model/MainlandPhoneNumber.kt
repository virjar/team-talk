package com.virjar.tk.protocol.model

/** 大陆手机号的产品口径：用户输入 11 位数字；旧版 +86 表达只在边界处兼容。 */
object MainlandPhoneNumber {
    const val LENGTH = 11
    const val FORMAT_MESSAGE = "请输入 11 位中国大陆手机号"
    private val pattern = Regex("1[3-9][0-9]{9}")

    fun normalizeOrNull(value: String?): String? {
        val number = value?.trim()?.removePrefix("+86") ?: return null
        return number.takeIf(pattern::matches)
    }

    /** null/空白表示清除，其他不合法输入必须明确拒绝，不能悄悄截断成另一个号码。 */
    fun normalize(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return requireNotNull(normalizeOrNull(value)) { FORMAT_MESSAGE }
    }

    fun display(value: String?): String = normalizeOrNull(value) ?: value.orEmpty()
}
