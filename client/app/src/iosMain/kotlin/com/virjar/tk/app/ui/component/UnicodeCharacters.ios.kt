@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.virjar.tk.app.ui.component

import platform.Foundation.*

internal actual fun isDisplayLetterOrDigit(codePoint: Int): Boolean {
    val scalar = codePoint.toUInt()
    // Foundation 的字母集还包含组合附加记号；Java 的 Character.isLetter 不包含。
    return (NSCharacterSet.letterCharacterSet.longCharacterIsMember(scalar) &&
        !NSCharacterSet.nonBaseCharacterSet.longCharacterIsMember(scalar)) ||
        NSCharacterSet.decimalDigitCharacterSet.longCharacterIsMember(scalar)
}
