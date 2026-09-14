package com.virjar.tk.server.api

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InviteLandingRoutesTest {
    @Test
    fun `public invitation page is static and copies the browser fragment without requesting group data`() = testApplication {
        application { routing { inviteLandingRoutes() } }
        val page = client.get("/invite")
        assertEquals(HttpStatusCode.OK, page.status)
        assertEquals("no-referrer", page.headers["Referrer-Policy"])
        val html = page.bodyAsText()
        assertTrue(html.contains("通过邀请加入群聊"))
        assertTrue(html.contains("href=\"downloads\""))
        assertTrue(html.contains("location.hash"))
        assertTrue(html.contains("location.href"))
        assertTrue(html.contains("navigator.clipboard.writeText(field.value)"))
        assertFalse(html.contains("fetch("))
        assertFalse(html.contains("XMLHttpRequest"))
        assertEquals(HttpStatusCode.NotFound, client.get("/invite/").status)
        // 即使有人误用 query，服务器也不解析、反射或查询这个值。
        assertEquals(html, client.get("/invite?token=untrusted-token").bodyAsText())
    }
}
