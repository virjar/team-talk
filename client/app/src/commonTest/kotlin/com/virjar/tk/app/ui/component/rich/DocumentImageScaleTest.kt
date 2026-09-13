package com.virjar.tk.app.ui.component.rich

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 内测反馈 T055：alt 分级后缀的解析与改写。 */
class DocumentImageScaleTest {

    @Test
    fun parsesKnownLevels() {
        assertEquals(null, DocumentImageScale.parseScale("photo"))
        assertEquals(null, DocumentImageScale.parseScale("photo|原图"))
        assertEquals(0.7f, DocumentImageScale.parseScale("photo|70%"))
        assertEquals(0.5f, DocumentImageScale.parseScale("photo|50%"))
        assertEquals(0.2f, DocumentImageScale.parseScale("photo|20%"))
        assertEquals(0.7f, DocumentImageScale.parseScale("photo|70%"))
    }

    @Test
    fun unknownSuffixStaysOriginal() {
        assertEquals(null, DocumentImageScale.parseScale("photo|33%"))
        // 未知分级不是合法后缀，strip 不动它（保持惰性原样）
        assertEquals("photo|33%", DocumentImageScale.stripScale("photo|33%"))
    }

    @Test
    fun withScaleReplacesOnlyTheSuffix() {
        assertEquals("photo|70%", DocumentImageScale.withScale("photo", 0.7f))
        assertEquals("photo|50%", DocumentImageScale.withScale("photo|70%", 0.5f))
        assertEquals("photo", DocumentImageScale.withScale("photo|70%", null))
        // 基础 label 本身含 |（非分级形态）时不被误吞
        assertEquals("a|b|20%", DocumentImageScale.withScale("a|b", 0.2f))
    }

    @Test
    fun rewriteLabelInSourceTouchesOnlyTheImageLabel() {
        val source = "![截图|70%](teamtalk-asset://asset/abc)"
        assertEquals(
            "![截图|20%](teamtalk-asset://asset/abc)",
            DocumentImageScale.rewriteLabelInSource(source, "截图|20%"),
        )
        assertEquals(
            "![截图](teamtalk-asset://asset/abc)",
            DocumentImageScale.rewriteLabelInSource(source, "截图"),
        )
        // 无图片语法的原文原样返回
        assertEquals("plain", DocumentImageScale.rewriteLabelInSource("plain", "x"))
    }
}
