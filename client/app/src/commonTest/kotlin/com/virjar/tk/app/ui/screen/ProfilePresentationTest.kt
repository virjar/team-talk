package com.virjar.tk.app.ui.screen

import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.ChatType
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.model.UserOrganizationPath
import com.virjar.tk.protocol.model.UserOrganizationSummary
import com.virjar.tk.shared.AppError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfilePresentationTest {
    @Test
    fun `friend remarks change personal identity without overwriting canonical user or group names`() {
        val user = User(uid = "peer", username = "account", name = "显示名", revision = 2)
        val conversation = Conversation(chatId = "chat", chatType = ChatType.PERSONAL.code, peerUid = "peer", peerRevision = 1)
        assertEquals("备注名", conversationIdentityPresentation(conversation, user, "备注名").name)
        assertEquals("显示名", conversationIdentityPresentation(conversation, user, "").name)
        assertEquals("显示名", user.name)
        assertEquals("群聊", conversationIdentityPresentation(conversation.copy(chatType = ChatType.GROUP.code, chatName = "群聊", peerRevision = null), null, "备注名").name)
    }

    @Test
    fun `profile conflict retains the business cause while infrastructure failures stay readable`() {
        assertEquals("手机号已被使用", profileSaveFailureMessage(AppError.Business(400, "手机号已被使用")))
        assertEquals("保存失败，请稍后重试", profileSaveFailureMessage(AppError.Unknown(IllegalStateException("database details"))))
    }

    @Test
    fun `organization peers can start personal chats from profile without friendship`() {
        val membership = UserOrganizationSummary(
            uid = "peer",
            paths = listOf(
                UserOrganizationPath(unitId = "unit-1", unitName = "研发部", pathNames = listOf("公司", "研发部")),
            ),
        )
        assertTrue(canStartPersonalChatFromProfile("peer", "me", isFriend = false, organizationSummary = membership))
        assertTrue(canStartPersonalChatFromProfile("peer", "me", isFriend = true, organizationSummary = null))
    }

    @Test
    fun `personal chat entry stays friend-only for visitors and users outside the organization`() {
        val noMembership = UserOrganizationSummary(uid = "peer", paths = emptyList())
        // 访客视角（T001 403）与归属加载失败都返回 null，不能放大私聊入口
        assertFalse(canStartPersonalChatFromProfile("peer", "me", isFriend = false, organizationSummary = null))
        assertFalse(canStartPersonalChatFromProfile("peer", "me", isFriend = false, organizationSummary = noMembership))
        // 其他账号的残留归属投影不构成目标用户的组织事实
        val staleMembership = UserOrganizationSummary(
            uid = "other",
            paths = listOf(UserOrganizationPath(unitId = "unit-1", unitName = "研发部", pathNames = listOf("公司", "研发部"))),
        )
        assertFalse(canStartPersonalChatFromProfile("peer", "me", isFriend = false, organizationSummary = staleMembership))
        // 自己的资料页不提供发消息
        val ownMembership = UserOrganizationSummary(
            uid = "me",
            paths = listOf(UserOrganizationPath(unitId = "unit-1", unitName = "研发部", pathNames = listOf("公司", "研发部"))),
        )
        assertFalse(canStartPersonalChatFromProfile("me", "me", isFriend = false, organizationSummary = ownMembership))
    }
}
