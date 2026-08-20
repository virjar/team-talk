package com.virjar.tk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidChatRouteNameTest {

    @Test
    fun `route name survives navigation decode and destination form decode`() {
        listOf(
            "研发 一组",
            "C++ 讨论",
            "50% & ready",
            "中文/English?",
        ).forEach { original ->
            val transport = encodeChatRouteName(original)
            // Navigation's Uri.decode pass turns %25 back into %, while leaving '+' alone.
            val destinationArgument = percentDecodeWithoutConvertingPlus(transport)

            assertEquals(original, decodeChatRouteName(destinationArgument))
        }
    }

    @Test
    fun `spaces and literal plus remain distinguishable`() {
        val route = Routes.chat("chat-1", "A+B C")
        val encodedName = route.substringAfter("name=").substringBefore("&type=")

        assertTrue(encodedName.contains("%252B"), route)
        assertEquals("A+B C", decodeChatRouteName(percentDecodeWithoutConvertingPlus(encodedName)))
    }

    @Test
    fun `legacy space argument is decoded at the destination boundary`() {
        assertEquals("研发 一组", decodeChatRouteName("研发+一组"))
        assertEquals("50% & ready", decodeChatRouteName("50%+&+ready"))
    }

    private fun percentDecodeWithoutConvertingPlus(value: String): String {
        val output = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            if (value[index] == '%' && index + 2 < value.length) {
                val code = value.substring(index + 1, index + 3).toIntOrNull(16)
                if (code != null) {
                    output.append(code.toChar())
                    index += 3
                    continue
                }
            }
            output.append(value[index])
            index++
        }
        return output.toString()
    }
}
