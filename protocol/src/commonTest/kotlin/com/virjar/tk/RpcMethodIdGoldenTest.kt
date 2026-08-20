package com.virjar.tk

import com.virjar.tk.rpc.gen.AuthRpcContract
import com.virjar.tk.rpc.gen.ChatRpcContract
import com.virjar.tk.rpc.gen.ContactRpcContract
import com.virjar.tk.rpc.gen.ConversationRpcContract
import com.virjar.tk.rpc.gen.DeviceRpcContract
import com.virjar.tk.rpc.gen.DocumentRpcContract
import com.virjar.tk.rpc.gen.GroupFileRpcContract
import com.virjar.tk.rpc.gen.MessageRpcContract
import com.virjar.tk.rpc.gen.OrganizationRpcContract
import com.virjar.tk.rpc.gen.UserRpcContract
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * RPC methodId golden 锁定：wire 稳定性的最后一道防线。
 *
 * 生成物在 build/（gitignore），每个 IDL 方法都用必填 `@RpcMethod` 显式编号；本表是
 * **手写的已发布契约**，任何 id 变化必须显式改这里 + 递增 PROTOCOL_VERSION。
 * processor 在编译期拦截缺失、重复和非法 id；本测试锁定当前 wire 数值。
 */
class RpcMethodIdGoldenTest {

    @Test
    fun `auth methodId 稳定`() {
        assertEquals(1, AuthRpcContract.M_LOGOUT)
        assertEquals(2, AuthRpcContract.M_UPDATE_PASSWORD)
    }

    @Test
    fun `user methodId 稳定`() {
        assertEquals(1, UserRpcContract.M_GET_PROFILE)
        assertEquals(2, UserRpcContract.M_UPDATE_PROFILE)
        assertEquals(3, UserRpcContract.M_SEARCH)
    }

    @Test
    fun `contact methodId 稳定`() {
        assertEquals(1, ContactRpcContract.M_LIST)
        assertEquals(2, ContactRpcContract.M_APPLY)
        assertEquals(3, ContactRpcContract.M_ACCEPT)
        assertEquals(4, ContactRpcContract.M_REJECT)
        assertEquals(5, ContactRpcContract.M_DELETE)
        assertEquals(6, ContactRpcContract.M_SET_REMARK)
        assertEquals(7, ContactRpcContract.M_BLACKLIST)
        assertEquals(8, ContactRpcContract.M_REMOVE_FROM_BLACKLIST)
        assertEquals(9, ContactRpcContract.M_LIST_PENDING_APPLIES)
        assertEquals(10, ContactRpcContract.M_LIST_BLACKLIST)
        assertEquals(11, ContactRpcContract.M_LIST_APPLY_RECORDS)
        assertEquals(12, ContactRpcContract.M_GET_PENDING_APPLY)
    }

    @Test
    fun `chat methodId 稳定`() {
        assertEquals(1, ChatRpcContract.M_CREATE_PERSONAL)
        assertEquals(2, ChatRpcContract.M_CREATE_GROUP)
        assertEquals(3, ChatRpcContract.M_GET)
        assertEquals(4, ChatRpcContract.M_UPDATE)
        assertEquals(5, ChatRpcContract.M_DELETE)
        assertEquals(6, ChatRpcContract.M_ADD_MEMBERS)
        assertEquals(7, ChatRpcContract.M_REMOVE_MEMBERS)
        assertEquals(8, ChatRpcContract.M_GET_MEMBERS)
        assertEquals(9, ChatRpcContract.M_TRANSFER_OWNER)
        assertEquals(10, ChatRpcContract.M_SET_ROLE)
        assertEquals(11, ChatRpcContract.M_MUTE_MEMBER)
        assertEquals(12, ChatRpcContract.M_UNMUTE_MEMBER)
        assertEquals(13, ChatRpcContract.M_MUTE_ALL)
        assertEquals(14, ChatRpcContract.M_UNMUTE_ALL)
        assertEquals(15, ChatRpcContract.M_CREATE_INVITE_LINK)
        assertEquals(16, ChatRpcContract.M_LIST_INVITE_LINKS)
        assertEquals(17, ChatRpcContract.M_REVOKE_INVITE_LINK)
        assertEquals(18, ChatRpcContract.M_JOIN_BY_INVITE)
        assertEquals(19, ChatRpcContract.M_GET_INVITE_INFO)
        assertEquals(20, ChatRpcContract.M_LEAVE_GROUP)
    }

    @Test
    fun `message methodId 稳定`() {
        assertEquals(1, MessageRpcContract.M_GET_HISTORY)
        assertEquals(2, MessageRpcContract.M_SEARCH)
        assertEquals(3, MessageRpcContract.M_REVOKE)
        assertEquals(4, MessageRpcContract.M_EDIT)
        assertEquals(5, MessageRpcContract.M_FORWARD)
        assertEquals(6, MessageRpcContract.M_MARK_READ)
    }

    @Test
    fun `conversation methodId 稳定`() {
        assertEquals(1, ConversationRpcContract.M_LIST)
        assertEquals(2, ConversationRpcContract.M_SYNC)
        assertEquals(3, ConversationRpcContract.M_SET_DRAFT)
        assertEquals(4, ConversationRpcContract.M_SET_PIN)
        assertEquals(5, ConversationRpcContract.M_SET_MUTE)
        assertEquals(6, ConversationRpcContract.M_DELETE)
    }

    @Test
    fun `device methodId 稳定`() {
        assertEquals(1, DeviceRpcContract.M_LIST_DEVICES)
        assertEquals(2, DeviceRpcContract.M_KICK_DEVICE)
    }

    @Test
    fun `organization methodId 稳定`() {
        assertEquals(1, OrganizationRpcContract.M_LIST_UNITS)
        assertEquals(2, OrganizationRpcContract.M_LIST_MEMBERS)
    }

    @Test
    fun `group file methodId 稳定`() {
        assertEquals(1, GroupFileRpcContract.M_LIST)
        assertEquals(2, GroupFileRpcContract.M_CREATE_FOLDER)
        assertEquals(3, GroupFileRpcContract.M_CREATE_FILE)
        assertEquals(4, GroupFileRpcContract.M_ADD_VERSION)
        assertEquals(5, GroupFileRpcContract.M_LIST_VERSIONS)
        assertEquals(6, GroupFileRpcContract.M_RENAME)
        assertEquals(7, GroupFileRpcContract.M_DELETE)
    }

    @Test
    fun `document methodId 稳定`() {
        assertEquals(1, DocumentRpcContract.M_LIST_SPACES)
        assertEquals(2, DocumentRpcContract.M_CREATE_SPACE)
        assertEquals(3, DocumentRpcContract.M_UPDATE_SPACE)
        assertEquals(4, DocumentRpcContract.M_ARCHIVE_SPACE)
        assertEquals(5, DocumentRpcContract.M_LIST_GRANTS)
        assertEquals(6, DocumentRpcContract.M_UPSERT_GRANT)
        assertEquals(7, DocumentRpcContract.M_REMOVE_GRANT)
        assertEquals(8, DocumentRpcContract.M_LIST_NODES)
        assertEquals(9, DocumentRpcContract.M_CREATE_FOLDER)
        assertEquals(10, DocumentRpcContract.M_CREATE_DOCUMENT)
        assertEquals(11, DocumentRpcContract.M_GET_DOCUMENT)
        assertEquals(12, DocumentRpcContract.M_UPDATE_DOCUMENT)
        assertEquals(13, DocumentRpcContract.M_MOVE_NODE)
        assertEquals(14, DocumentRpcContract.M_DELETE_NODE)
        assertEquals(15, DocumentRpcContract.M_LIST_REVISIONS)
        assertEquals(16, DocumentRpcContract.M_GET_REVISION)
        assertEquals(17, DocumentRpcContract.M_LIST_RECENT_DOCUMENTS)
        assertEquals(18, DocumentRpcContract.M_LIST_RECENTLY_CREATED_DOCUMENTS)
    }

    @Test
    fun `serviceId 字符串稳定`() {
        assertEquals("auth", AuthRpcContract.SERVICE)
        assertEquals("user", UserRpcContract.SERVICE)
        assertEquals("contact", ContactRpcContract.SERVICE)
        assertEquals("chat", ChatRpcContract.SERVICE)
        assertEquals("message", MessageRpcContract.SERVICE)
        assertEquals("conversation", ConversationRpcContract.SERVICE)
        assertEquals("device", DeviceRpcContract.SERVICE)
        assertEquals("organization", OrganizationRpcContract.SERVICE)
        assertEquals("groupFile", GroupFileRpcContract.SERVICE)
        assertEquals("document", DocumentRpcContract.SERVICE)
    }

    // 占位 map 防误用（golden 以字面断言为准）
    @Suppress("unused")
    private val unused: Unit = Unit
}
