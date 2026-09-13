package com.virjar.tk.server.integration

import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.model.GroupAvatar
import com.virjar.tk.protocol.model.MentionPolicy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 内测反馈 T053：群头像的设置、成员可见授权、清除与持久事件。 */
class GroupAvatarIntegrationTest {
    companion object {
        @JvmField @RegisterExtension val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    private fun stagingAvatar(uid: String, label: String) =
        ctx.fileStore.store(
            uid = uid,
            fileName = "$label.png",
            contentType = "image/png",
            inputStream = ByteArrayInputStream("group-avatar-$label".encodeToByteArray()),
        ).let { path -> requireNotNull(ctx.fileStore.getAttachment(path)) }

    @Test
    fun `owner sets and clears group avatar with member events`() = runTest {
        val alice = ctx.registerUser(uniqueUsername("gav-alice"))
        val bob = ctx.registerUser(uniqueUsername("gav-bob"))
        val chat = ctx.chatService.createGroup(id(), "头像群", null, alice, listOf(bob))

        val avatar = stagingAvatar(alice, "one")
        ctx.chatService.setGroupAvatar(alice, GroupAvatar(chatId = chat.chatId, attachment = avatar))

        assertEquals(listOf(avatar.path), ctx.chatService.getGroupAvatars(alice, listOf(chat.chatId))
            .map { it.attachment?.path })
        assertEquals(avatar, ctx.chatService.getGroupAvatars(bob, listOf(chat.chatId)).single().attachment)

        // 群外人员取不到。
        val outsider = ctx.registerUser(uniqueUsername("gav-out"))
        assertTrue(ctx.chatService.getGroupAvatars(outsider, listOf(chat.chatId)).isEmpty())

        // 持久事件：alice 与 bob 都收到 GROUP_AVATAR_SYNC。
        fun avatarEvents(uid: String) = ctx.syncEventReader.getEventsAfter(uid, 0L, 1_000)
            .filter { it.notifyType == NotifyType.GROUP_AVATAR_SYNC.code }
        for (uid in listOf(alice, bob)) {
            val events = avatarEvents(uid)
            assertEquals(1, events.size, "$uid 应收到一条群头像事件")
            val payload = com.virjar.tk.protocol.ProtoCodec.decode(
                GroupAvatar, requireNotNull(events.single().payload),
            )
            assertEquals(chat.chatId, payload.chatId)
            assertEquals(avatar.path, payload.attachment?.path)
        }

        // 清除：成员事件 payload attachment=null，本地条目回 null。
        ctx.chatService.setGroupAvatar(alice, GroupAvatar(chatId = chat.chatId, attachment = null))
        assertNull(ctx.chatService.getGroupAvatars(bob, listOf(chat.chatId)).single().attachment)
        assertEquals(2, avatarEvents(bob).size)
    }

    @Test
    fun `non admin member and managed group are rejected`() = runTest {
        val alice = ctx.registerUser(uniqueUsername("gav2-alice"))
        val bob = ctx.registerUser(uniqueUsername("gav2-bob"))
        val chat = ctx.chatService.createGroup(id(), "权限群", null, alice, listOf(bob))

        val bobAvatar = stagingAvatar(bob, "bob-upload")
        assertFailsWith<IllegalArgumentException> {
            ctx.chatService.setGroupAvatar(bob, GroupAvatar(chatId = chat.chatId, attachment = bobAvatar))
        }

        // 非本人上传：owner 也不能绑定别人的 staging。
        val foreign = stagingAvatar(bob, "foreign")
        assertFailsWith<IllegalArgumentException> {
            ctx.chatService.setGroupAvatar(alice, GroupAvatar(chatId = chat.chatId, attachment = foreign))
        }
    }

    private fun id() = UUID.randomUUID().toString()
}
