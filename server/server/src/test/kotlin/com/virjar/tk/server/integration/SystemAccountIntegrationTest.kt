package com.virjar.tk.server.integration

import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.server.domain.user.SystemAccountUids
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 内测反馈 T058：固定系统账号引导与系统私聊的幂等创建。 */
class SystemAccountIntegrationTest {
    companion object {
        @JvmField @RegisterExtension val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `boot bootstraps fixed system accounts idempotently`() = runTest {
        ctx.userService.ensureSystemAccounts()
        SystemAccountUids.DISPLAY_NAMES.forEach { (uid, name) ->
            val user = ctx.userService.getProfile(uid)
            assertEquals(name, user.name)
            assertEquals(com.virjar.tk.protocol.model.UserRole.SYSTEM, user.role)
        }
        // 二次引导幂等：不抛唯一约束冲突
        ctx.userService.ensureSystemAccounts()
    }

    @Test
    fun `system chat is created once per user and events fan out`() = runTest {
        ctx.userService.ensureSystemAccounts()
        val alice = ctx.registerUser(uniqueUsername("sys-alice"))

        val first = ctx.chatService.getOrCreateSystemChat(alice, SystemAccountUids.ASSISTANT)
        val second = ctx.chatService.getOrCreateSystemChat(alice, SystemAccountUids.ASSISTANT)
        assertEquals(first.chatId, second.chatId, "同用户同系统账号必须复用同一条私聊")

        // CHAT_CREATED 只在首次创建时发出一次（幂等重入不重复）
        val createdEvents = ctx.syncEventReader.getEventsAfter(alice, 0L, 1_000)
            .filter { it.notifyType == NotifyType.CHAT_CREATED.code }
            .map { com.virjar.tk.protocol.ProtoCodec.decode(com.virjar.tk.protocol.model.Chat, requireNotNull(it.payload)) }
            .filter { it.chatId == first.chatId }
        assertEquals(1, createdEvents.size)
    }

    @Test
    fun `unknown system uid is rejected`() = runTest {
        val alice = ctx.registerUser(uniqueUsername("sys-bad"))
        assertFailsWith<IllegalArgumentException> {
            ctx.chatService.getOrCreateSystemChat(alice, "sys_nope")
        }
    }

    private fun uniqueUsername(base: String): String = "$base-${UUID.randomUUID().toString().take(8)}"
}
