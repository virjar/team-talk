@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.virjar.tk.app.ui.component

import platform.Foundation.*

internal actual fun isDisplayLetterOrDigit(codePoint: Int): Boolean {
    val scalar = codePoint.toUInt()
    // Foundation's letter set also includes combining marks; Java Character.isLetter does not.
    return (NSCharacterSet.letterCharacterSet.longCharacterIsMember(scalar) &&
        !NSCharacterSet.nonBaseCharacterSet.longCharacterIsMember(scalar)) ||
        NSCharacterSet.decimalDigitCharacterSet.longCharacterIsMember(scalar)
}
