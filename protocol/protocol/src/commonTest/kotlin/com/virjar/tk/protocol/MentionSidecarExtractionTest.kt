package com.virjar.tk.protocol

import com.virjar.tk.protocol.body.buildRichTextBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 内测反馈 T056 回归：composer 桥接出的 `@[名](mention://uid)` 必须进 mentions sidecar。 */
class MentionSidecarExtractionTest {

    @Test
    fun protocolMentionSyntaxExtractsUidIncludingReservedAll() {
        val body = buildRichTextBody("请全体成员注意 @[所有人](mention://all) ")
        assertEquals(1, body.mentions.size)
        assertEquals("all", body.mentions.single().uid)

        val body2 = buildRichTextBody("你好 @[小B](mention://abc123) 看一下")
        assertEquals("abc123", body2.mentions.single().uid)
    }

    @Test
    fun bracketInnerAtDoesNotDoubleCountAndStillExtracts() {
        // 历史缺陷形态（T037 桥接曾产出）：@ 在括号内，提取不到。现在桥接已修正，
        // 但侧信道提取必须继续严格——非 mention 语法不得误报。
        val body = buildRichTextBody("看看 [@所有人](mention://all) 和 @[小B](mention://u1)")
        assertEquals(listOf("u1"), body.mentions.map { it.uid })
    }
}
