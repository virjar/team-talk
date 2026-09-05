package com.virjar.tk.server.domain.message

import com.virjar.tk.protocol.body.MessageBody
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.server.domain.attachment.AttachmentAccess
import com.virjar.tk.server.domain.attachment.AttachmentCatalog
import com.virjar.tk.server.domain.attachment.AttachmentService
import com.virjar.tk.server.domain.chat.ChatAccessDeniedException
import com.virjar.tk.server.domain.chat.ChatAccess
import com.virjar.tk.server.domain.chat.ChatAccessSnapshot
import com.virjar.tk.server.domain.chat.ChatAccessSource
import com.virjar.tk.server.domain.chat.ChatLifecycleGate
import com.virjar.tk.server.domain.chat.ChatMemberRepository
import com.virjar.tk.server.domain.chat.MessageAdmission
import com.virjar.tk.server.domain.chat.MessageAdmissionFacts
import com.virjar.tk.server.domain.chat.ChatRepository
import com.virjar.tk.server.domain.chat.ChatStore
import com.virjar.tk.server.domain.chat.ChatService
import com.virjar.tk.server.domain.chat.RequiredChatParticipants
import com.virjar.tk.server.domain.chat.UnmanagedChatPolicy
import com.virjar.tk.server.domain.chat.InviteLinkRepository
import com.virjar.tk.server.domain.contact.ContactRepository
import com.virjar.tk.server.domain.document.DocumentRepository
import com.virjar.tk.server.domain.document.DocumentService
import com.virjar.tk.server.domain.groupfile.GroupFileRepository
import com.virjar.tk.server.domain.groupfile.GroupFileService
import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.server.domain.transaction.PgReadScope
import com.virjar.tk.server.domain.transaction.PgWriteScope
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.domain.user.UserRepository
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.ChatType
import com.virjar.tk.protocol.model.Member
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.NotifyType
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

    private suspend fun assertSourceRevocationWins(
        revoke: (MutableForwardAccessSource) -> Unit,
    ) = coroutineScope {
        // withChats 按 stripe 升序加锁。让 target 排在 source 之前，会使转发在获取 source
        // 锁之前先等待，从而竞争中的 source 生命周期变更确定性地先提交。
        // 转发随后必须在两把锁下重新读取授权。
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
        val access = ChatAccess(accessSource)
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
                "admitMessage" -> {
                    val chatId = args[1] as String
                    val senderUid = args[2] as String
                    val members = membersByChat[chatId].orEmpty()
                    @Suppress("UNCHECKED_CAST")
                    val afterChatLocked = args[4] as () -> Unit
                    @Suppress("UNCHECKED_CAST")
                    val authorize = args[5] as (MessageAdmissionFacts) -> Unit
                    afterChatLocked()
                    authorize(
                        MessageAdmissionFacts(
                            chat = chats.getValue(chatId).copy(memberCount = members.size),
                            sender = members.firstOrNull { it.uid == senderUid },
                            senderMuted = false,
                            activeMemberUids = members.map(Member::uid),
                        ),
                    )
                    MessageAdmission(
                        chatType = chats.getValue(chatId).chatType,
                        recipientUids = members.map(Member::uid),
                    )
                }
                else -> UnhandledCall
            }
        }
        val chatStore = ChatStore(
            repo = chatRepository,
            memberRepo = memberRepository,
            inviteRepo = interfaceStub<InviteLinkRepository>(),
        )
        var storedMessage: Message? = null
        var pendingOperation: MessageProjectionOperation? = null
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
                "appendMessage" -> {
                    val candidate = args[0] as Message
                    val message = candidate.copy(
                        serverSeq = chats.getValue(candidate.chatId).maxSeq + 1L,
                    )
                    storedMessage = message
                    pendingOperation = MessageProjectionOperation(
                        projectionKey = MessageProjectionOperation.stableKey(
                            message.chatId,
                            message.serverSeq,
                        ),
                        operation = MessageOperationType.CREATE,
                        revision = 1L,
                        message = message,
                        target = (args[2] as MessageProjectionTarget).canonical(),
                    )
                    message
                }
                "getPendingProjectionOperations" -> listOfNotNull(pendingOperation)
                "isProjectionPending" -> pendingOperation == args[0]
                "markProjectionComplete" -> {
                    if (pendingOperation == args[0]) pendingOperation = null
                    Unit
                }
                else -> UnhandledCall
            }
        }
        val search = interfaceStub<MessageSearch> { method, _ ->
            if (method == "applyProjection") true else UnhandledCall
        }
        val users = interfaceStub<UserRepository>()
        val contacts = interfaceStub<ContactRepository>()
        val projector = MessageProjector(
            messages = messages,
            chatStore = chatStore,
            projectionRepository = MessageProjectionRepository { _, operation, _ ->
                MessageProjectionApplyResult(
                    applied = true,
                    recipients = operation.target.recipientUids.map { MessageProjectionRecipient(it, null) },
                )
            },
            unitOfWork = ImmediatePgUnitOfWork,
            projectionReadiness = MessageProjectionReadiness(),
            search = search,
            lifecycleGate = gate,
            managedChats = UnmanagedChatPolicy,
            reactionRepository = null,
            projectionHooks = MessageProjectionHooks.None,
        )
        val service = MessageService(
            messages = messages,
            chatStore = chatStore,
            access = access,
            officeRefs = OfficeRefResolver(
                documents = DocumentService(interfaceStub<DocumentRepository>(), ImmediatePgUnitOfWork),
                groupFiles = GroupFileService(
                    repository = interfaceStub<GroupFileRepository>(),
                    access = access,
                    attachments = interfaceStub<AttachmentCatalog>(),
                    unitOfWork = ImmediatePgUnitOfWork,
                    chatStore = chatStore,
                ),
            ),
            projector = projector,
            unitOfWork = ImmediatePgUnitOfWork,
            search = search,
            attachmentService = AttachmentService(
                attachmentCatalog = interfaceStub<AttachmentCatalog>(),
                attachmentAccess = AttachmentAccess { _, _ -> true },
            ),
            users = users,
            contacts = contacts,
            chatService = ChatService(
                chatStore = chatStore,
                access = access,
                users = users,
                managedChats = UnmanagedChatPolicy,
                contacts = contacts,
                requiredParticipants = interfaceStub<RequiredChatParticipants>(),
                lifecycleGate = gate,
                unitOfWork = ImmediatePgUnitOfWork,
            ),
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

        private fun getChat(chatId: String): Chat? {
            if (chatId == sourceChatId && !sourceChatActive) return null
            return chats[chatId]
        }

        private fun getMember(chatId: String, uid: String): Member? {
            if (uid != ACTOR_UID || getChat(chatId) == null) return null
            if (chatId == sourceChatId && !sourceMemberActive) return null
            if (chatId != sourceChatId && chatId != targetChatId) return null
            return Member(uid = uid, chatId = chatId, role = 0)
        }

        override suspend fun load(chatId: String, memberUids: Set<String>): ChatAccessSnapshot =
            ChatAccessSnapshot(
                chat = getChat(chatId),
                members = memberUids.mapNotNull { uid -> getMember(chatId, uid) },
            )

        override suspend fun listAccessibleChatIds(uid: String): Set<String> = chats.keys
            .filterTo(linkedSetOf()) { chatId -> getMember(chatId, uid) != null }

        override suspend fun <T> read(
            chatId: String,
            memberUids: Set<String>,
            includeAllMembers: Boolean,
            block: (ChatAccessSnapshot) -> T,
        ): T = block(load(chatId, if (includeAllMembers) setOf(ACTOR_UID) else memberUids))

        override suspend fun <T> readAccessibleChatIds(uid: String, block: (Set<String>) -> T): T =
            block(listAccessibleChatIds(uid))
    }

    private companion object {
        const val ACTOR_UID = "forwarder"
        const val SOURCE_SEQ = 11L
        const val TEST_STRIPES = 8
    }
}

private object ImmediatePgUnitOfWork : PgUnitOfWork {
    private object ReadTransaction : PgReadTransactionContext
    private object WriteTransaction : PgWriteTransactionContext

    override suspend fun <T> read(block: PgReadScope.() -> T): T = block(
        object : PgReadScope {
            override val transaction: PgReadTransactionContext = ReadTransaction
        },
    )

    override suspend fun <T> write(block: PgWriteScope.() -> T): T = block(
        object : PgWriteScope {
            override val transaction: PgWriteTransactionContext = WriteTransaction
            override fun appendEvent(uid: String, notifyType: NotifyType, payload: IProto) = Unit
            override fun afterCommit(action: () -> Unit) = action()
        },
    )
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
