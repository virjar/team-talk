package com.virjar.tk.server.infra.db

import kotlin.test.Test
import kotlin.test.assertEquals

class UserPinyinKeysTest {
    @Test
    fun `common CJK names derive full and initials keys`() {
        assertEquals("zhangsan", derivePinyinFull("张三"))
        assertEquals("zs", derivePinyinInitials("张三"))
        assertEquals("ouyangxiu", derivePinyinFull("欧阳修"))
        assertEquals("oyx", derivePinyinInitials("欧阳修"))
        assertEquals("lisiming", derivePinyinFull("李四明"))
        assertEquals("lsm", derivePinyinInitials("李四明"))
    }

    @Test
    fun `mixed latin and CJK keep letters in place`() {
        assertEquals("alexwang", derivePinyinFull("Alex王"))
        assertEquals("alexw", derivePinyinInitials("Alex王"))
        assertEquals("zhangsan01", derivePinyinFull("张三01"))
        assertEquals("zs01", derivePinyinInitials("张三01"))
    }

    @Test
    fun `separators are dropped and unknown chars fall back to themselves`() {
        assertEquals("wangxiaoming", derivePinyinFull(" 王小明 "))
        assertEquals("wxm", derivePinyinInitials(" 王小明 "))
    }
}
