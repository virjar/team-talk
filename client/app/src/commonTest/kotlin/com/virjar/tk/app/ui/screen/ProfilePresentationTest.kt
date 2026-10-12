package com.virjar.tk.app.ui.screen

import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.ChatType
import com.virjar.tk.protocol.model.User
import com.virjar.tk.shared.AppError
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
