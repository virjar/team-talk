package com.virjar.tk.domain.message

import com.virjar.tk.body.GenericPayload
import com.virjar.tk.body.MessageBody
import com.virjar.tk.body.RichTextBody
import com.virjar.tk.body.buildRichTextBody
import com.virjar.tk.domain.attachment.AttachmentAccess
import com.virjar.tk.domain.attachment.AttachmentCatalog
import com.virjar.tk.domain.attachment.AttachmentService
import com.virjar.tk.domain.chat.ChatAccessDeniedException
import com.virjar.tk.domain.chat.ChatAccessPolicy
import com.virjar.tk.domain.chat.ChatAccessSource
import com.virjar.tk.domain.chat.ChatLifecycleGate
import com.virjar.tk.domain.chat.ChatMemberRepository
import com.virjar.tk.domain.chat.ChatRepository
import com.virjar.tk.domain.chat.ChatStore
import com.virjar.tk.domain.chat.InviteLinkRepository
import com.virjar.tk.domain.contact.ContactRepository
import com.virjar.tk.domain.contact.ContactStore
import com.virjar.tk.domain.conversation.ConversationRepository
import com.virjar.tk.domain.conversation.ConversationService
import com.virjar.tk.domain.event.EventPublisher
import com.virjar.tk.domain.user.UserRepository
import com.virjar.tk.domain.user.UserStore
import com.virjar.tk.model.Chat
import com.virjar.tk.model.ChatType
import com.virjar.tk.model.Member
import com.virjar.tk.model.Message
import com.virjar.tk.protocol.MessageType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageServiceLifecycleTest {
    @Test
    fun `source kick while forward waits on target revokes the source read`() = runTest {
        assertSourceRevocationWins { access -> access.kickSourceMember() }
    }

    @Test
    fun `source dissolve while forward waits on target revokes the source read`() = runTest {
        assertSourceRevocationWins { access -> access.dissolveSourceChat() }
    }

    @Test
    fun `forwarding inside the same chat acquires its lifecycle stripe once`() = runTest {
        val chatId = "same-source-and-target"
        val fixture = forwardFixture(sourceChatId = chatId, targetChatId = chatId)

        val forwarded = withTimeout(1_000) {
            fixture.service.forwardMessage(ACTOR_UID, chatId, SOURCE_SEQ, chatId)
        }

        assertEquals(chatId, forwarded.chatId)
        assertEquals(SOURCE_SEQ + 1, forwarded.serverSeq)
        assertTrue(forwarded.flags and Message.FLAG_FORWARDED != 0)
        assertEquals("forward source", (forwarded.body as RichTextBody).markdown)
    }

    @Test
    fun `forwarding an unknown generic message preserves opaque bytes`() = runTest {
        val body = GenericPayload(404, byteArrayOf(0, 7, 0, 8))
        val fixture = forwardFixture(
            sourceChatId = "generic-source",
            targetChatId = "generic-target",
            sourceMessageType = MessageType.GENERIC,
            sourceBody = body,
        )

        val forwarded = fixture.service.forwardMessage(
            ACTOR_UID,
            "generic-source",
            SOURCE_SEQ,
            "generic-target",
        )

        assertEquals(body, forwarded.body)
        assertEquals(body, fixture.storedMessage()?.body)
    }

    private suspend fun assertSourceRevocationWins(
        revoke: (MutableForwardAccessSource) -> Unit,
    ) = coroutineScope {
        // withChats locks by ascending stripe. Keeping target below source makes the forward wait
        // before acquiring source, so the competing source lifecycle mutation deterministically
        // commits first. The forward must then re-read authorization under both locks.
        val targetChatId = chatIdForStripe("target", stripe = 0)
        val sourceChatId = chatIdForStripe("source", stripe = TEST_STRIPES - 1)
        val gate = ChatLifecycleGate(stripeCount = TEST_STRIPES)
        val fixture = forwardFixture(sourceChatId, targetChatId, gate)
        val targetEntered = CompletableDeferred<Unit>()
        val releaseTarget = CompletableDeferred<Unit>()

        val targetHolder = async {
            gate.withChat(targetChatId) {
                targetEntered.complete(Unit)
                releaseTarget.await()
            }
        }
        targetEntered.await()

        val forward = async {
            runCatching {
                fixture.service.forwardMessage(ACTOR_UID, sourceChatId, SOURCE_SEQ, targetChatId)
            }
        }
        yield()

        gate.withChat(sourceChatId) { revoke(fixture.accessSource) }
        releaseTarget.complete(Unit)
        targetHolder.await()

        assertIs<ChatAccessDeniedException>(forward.await().exceptionOrNull())
        assertNull(fixture.storedMessage())
    }

    private fun forwardFixture(
        sourceChatId: String,
        targetChatId: String,
        gate: ChatLifecycleGate = ChatLifecycleGate(stripeCount = TEST_STRIPES),
        sourceMessageType: MessageType = MessageType.RICH_TEXT,
        sourceBody: MessageBody = buildRichTextBody("forward source"),
    ): ForwardFixture {
        val sourceMessage = Message(
            chatId = sourceChatId,
            clientMsgId = "source-message",
            serverSeq = SOURCE_SEQ,
            senderUid = ACTOR_UID,
            messageType = sourceMessageType.code,
            timestamp = 1,
            body = sourceBody,
        )
        val chats = linkedMapOf(
            sourceChatId to Chat(
                chatId = sourceChatId,
                chatType = ChatType.GROUP.code,
                maxSeq = SOURCE_SEQ,
            ),
            targetChatId to Chat(
                chatId = targetChatId,
                chatType = ChatType.GROUP.code,
                maxSeq = if (targetChatId == sourceChatId) SOURCE_SEQ else 0,
            ),
        )
        val accessSource = MutableForwardAccessSource(sourceChatId, targetChatId, chats)
        val access = ChatAccessPolicy(accessSource)
        val membersByChat = chats.keys.associateWith { chatId ->
            listOf(Member(uid = ACTOR_UID, chatId = chatId, role = 0))
        }
        val chatRepository = interfaceStub<ChatRepository> { method, args ->
            when (method) {
                "getChat", "getChatById" -> chats[args[0] as String]
                "getMemberUids" -> membersByChat[args[0] as String].orEmpty().map(Member::uid)
                "updateMaxSeq" -> Unit
                else -> UnhandledCall
            }
        }
        val memberRepository = interfaceStub<ChatMemberRepository> { method, args ->
            when (method) {
                "getMembers" -> membersByChat[args[0] as String].orEmpty()
                "getMemberUids" -> membersByChat[args[0] as String].orEmpty().map(Member::uid)
                "isMuted", "isMember" -> false
                else -> UnhandledCall
            }
        }
        val chatStore = ChatStore(
            repo = chatRepository,
            memberRepo = memberRepository,
            inviteRepo = interfaceStub<InviteLinkRepository>(),
        )
        val events = interfaceStub<EventPublisher> { method, _ ->
            when (method) {
                "emitEvent", "emitEvents", "emitTransient" -> Unit
                else -> UnhandledCall
            }
        }
        val conversationRepository = interfaceStub<ConversationRepository> { method, _ ->
            when (method) {
                "getConversation" -> null
                "upsertConversation", "markRead" -> Unit
                else -> UnhandledCall
            }
        }
        val conversationService = ConversationService(
            conversationRepo = conversationRepository,
            chatRepo = chatRepository,
            events = events,
            access = access,
            chatStore = chatStore,
            lifecycleGate = gate,
        )
        var storedMessage: Message? = null
        var projectionPending = false
        val messages = interfaceStub<MessageRepository> { method, args ->
            when (method) {
                "getMessage" -> {
                    val chatId = args[0] as String
                    val seq = args[1] as Long
                    when {
                        chatId == sourceChatId && seq == SOURCE_SEQ -> sourceMessage
                        storedMessage?.chatId == chatId && storedMessage?.serverSeq == seq -> storedMessage
                        else -> null
                    }
                }
                "storeMessage" -> {
                    storedMessage = args[0] as Message
                    projectionPending = true
                    storedMessage!!.serverSeq
                }
                "isProjectionPending" -> projectionPending
                "markProjectionComplete" -> {
                    projectionPending = false
                    Unit
                }
                else -> UnhandledCall
            }
        }
        val service = MessageService(
            messages = messages,
            chatStore = chatStore,
            access = access,
            events = events,
            conversationService = conversationService,
            search = interfaceStub<MessageSearch> { method, _ ->
                if (method == "indexMessage") Unit else UnhandledCall
            },
            attachmentService = AttachmentService(
                attachmentCatalog = interfaceStub<AttachmentCatalog>(),
                attachmentAccess = AttachmentAccess { _, _ -> true },
            ),
            users = UserStore(interfaceStub<UserRepository>()),
            contacts = ContactStore(interfaceStub<ContactRepository>()),
            lifecycleGate = gate,
        )
        return ForwardFixture(service, accessSource) { storedMessage }
    }

    private fun chatIdForStripe(prefix: String, stripe: Int): String =
        generateSequence(0) { it + 1 }
            .map { "$prefix-$it" }
            .first { (it.hashCode() and Int.MAX_VALUE) % TEST_STRIPES == stripe }

    private data class ForwardFixture(
        val service: MessageService,
        val accessSource: MutableForwardAccessSource,
        val storedMessage: () -> Message?,
    )

    private class MutableForwardAccessSource(
        private val sourceChatId: String,
        private val targetChatId: String,
        private val chats: Map<String, Chat>,
    ) : ChatAccessSource {
        private var sourceChatActive = true
        private var sourceMemberActive = true

        fun kickSourceMember() {
            sourceMemberActive = false
        }

        fun dissolveSourceChat() {
            sourceChatActive = false
            sourceMemberActive = false
        }

        override fun getChat(chatId: String): Chat? {
            if (chatId == sourceChatId && !sourceChatActive) return null
            return chats[chatId]
        }

        override fun getMember(chatId: String, uid: String): Member? {
            if (uid != ACTOR_UID || getChat(chatId) == null) return null
            if (chatId == sourceChatId && !sourceMemberActive) return null
            if (chatId != sourceChatId && chatId != targetChatId) return null
            return Member(uid = uid, chatId = chatId, role = 0)
        }
    }

    private companion object {
        const val ACTOR_UID = "forwarder"
        const val SOURCE_SEQ = 11L
        const val TEST_STRIPES = 8
    }
}

private object UnhandledCall

@Suppress("UNCHECKED_CAST")
private inline fun <reified T : Any> interfaceStub(
    crossinline handler: (method: String, args: Array<out Any?>) -> Any? = { _, _ -> UnhandledCall },
): T {
    val interfaceType = T::class.java
    return Proxy.newProxyInstance(interfaceType.classLoader, arrayOf(interfaceType)) { proxy, method, nullableArgs ->
        val args = nullableArgs ?: emptyArray()
        when (method.name) {
            "toString" -> "Stub<${interfaceType.simpleName}>"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args.firstOrNull()
            else -> handler(method.name, args).let { answer ->
                if (answer !== UnhandledCall) answer else defaultReturnValue(method.returnType)
            }
        }
    } as T
}

private fun defaultReturnValue(type: Class<*>): Any? = when {
    type == java.lang.Void.TYPE -> null
    type == java.lang.Boolean.TYPE -> false
    type == java.lang.Byte.TYPE -> 0.toByte()
    type == java.lang.Short.TYPE -> 0.toShort()
    type == java.lang.Integer.TYPE -> 0
    type == java.lang.Long.TYPE -> 0L
    type == java.lang.Float.TYPE -> 0f
    type == java.lang.Double.TYPE -> 0.0
    List::class.java.isAssignableFrom(type) -> emptyList<Any>()
    Set::class.java.isAssignableFrom(type) -> emptySet<Any>()
    else -> null
}
