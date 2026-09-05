package com.virjar.tk.shared.client

import com.virjar.tk.protocol.model.*
import com.virjar.tk.protocol.payload.MessageAckPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** 一条等待镜像到服务端的本地会话草稿操作。[draft] 为 null 表示明确清空。 */
data class PendingConversationDraft(
    val chatId: String,
    val draft: String?,
    val generation: Long,
)

/** 一条尚未获得服务端成功应答的单调已读水位。 */
data class PendingConversationRead(
    val chatId: String,
    val readSeq: Long,
)

/** 一条尚未被无头消费者取走的持久消息；eventId 同时承担 replay 幂等键。 */
data class PendingBotMessage(
    val eventId: Long,
    val message: Message,
)

/**
 * 最后持久化的组织树。[revision] 是该快照的修订号；[snapshotKnown] 说明它是否达到缓存当前的
 * required revision。
 */
data class OrganizationUnitProjection(
    val snapshotKnown: Boolean,
    val revision: Long,
    val units: List<OrganizationUnit>,
) {
    companion object {
        val Unfetched = OrganizationUnitProjection(
            snapshotKnown = false,
            revision = 0L,
            units = emptyList(),
        )
    }
}

/**
 * 一个组织单元最后持久化的直属成员投影。
 *
 * [snapshotKnown] 独立于 [members]：权威的空快照是已知数据，而首次成功读取之前的空列表只是
 * 缓存未命中。精确的本地/事件变更可以贡献行，而不声称完整快照已被拉取。[revision] 是该单元
 * 最后的权威直属成员快照修订号。
 */
data class OrganizationMemberProjection(
    val snapshotKnown: Boolean,
    val members: List<OrganizationMember>,
    val revision: Long,
) {
    companion object {
        val Unfetched = OrganizationMemberProjection(
            snapshotKnown = false,
            members = emptyList(),
            revision = 0L,
        )
    }
}

/** SQLite 支撑的发送状态。两种终态结果都可以作为持久回执被查询。 */
enum class OutgoingMessageState(val code: Long) {
    PENDING(0),
    IN_FLIGHT(1),
    RETRY_WAIT(2),
    TERMINAL_FAILED(3),
    SUCCESS(4),
    ;

    companion object {
        fun fromCode(code: Long): OutgoingMessageState = entries.firstOrNull { it.code == code }
            ?: error("Unknown outgoing message state: $code")
    }
}

/**
 * 持久发送失败的稳定、经隐私审查的分类。
 *
 * [storageCode] 被持久化，绝不能重新编号。[apiCode] 与 [publicMessage] 可以安全地通过 SDK/Agent
 * 表面暴露；原始 transport/ACK 诊断仅保留在本地。
 */
enum class OutgoingFailureCode(
    val storageCode: Long,
    val apiCode: String,
    val publicMessage: String,
    /** 仅当持久结果证明旧请求未被接受时为 true。 */
    val allowsFreshClientMsgIdReplacement: Boolean,
) {
    ACK_TIMEOUT(1, "ack_timeout", "消息确认超时，将自动重试", false),
    TRANSPORT_UNAVAILABLE(2, "transport_unavailable", "消息通道暂不可用，将自动重试", false),
    RATE_LIMITED(3, "rate_limited", "发送过于频繁，将自动重试", true),
    SERVER_UNAVAILABLE(4, "server_unavailable", "消息服务暂不可用，将自动重试", true),
    AUTHENTICATION_REQUIRED(5, "authentication_required", "登录状态已失效，请重新登录后发送", true),
    REMOTE_REJECTED(6, "remote_rejected", "消息被服务端拒绝", true),
    CLIENT_VALIDATION(7, "client_validation", "消息内容不符合发送要求", true),
    ACK_IDENTITY_MISMATCH(8, "ack_identity_mismatch", "消息确认身份不一致", false),
    INVALID_ACK(9, "invalid_ack", "消息服务返回了无效确认", false),
    PROCESS_INTERRUPTED(10, "process_interrupted", "发送进程曾中断，将自动重试", false),
    SESSION_RETIRED(11, "session_retired", "当前账号会话已结束", false),
    UNEXPECTED_FAILURE(12, "unexpected_failure", "发送遇到异常，将自动重试", false),
    ;

    companion object {
        fun fromStorageCode(code: Long): OutgoingFailureCode = entries.firstOrNull {
            it.storageCode == code
        } ?: error("Unknown outgoing failure code: $code")
    }
}

/** 不可变规范载荷，加上其持久本地排序与重试元数据。 */
data class OutgoingMessage(
    val localOrdinal: Long,
    val message: Message,
    val state: OutgoingMessageState,
    val attemptCount: Long,
    val nextAttemptAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val serverSeq: Long? = null,
    val terminalCode: Int? = null,
    val completedAt: Long? = null,
    /** 经审查的分类；原始 transport 诊断绝不进入这个公开回执。 */
    val failureCode: OutgoingFailureCode? = null,
)

/** 只从可靠发件箱元数据派生的聚合诊断；规范消息载荷保持不透明。 */
data class OutgoingQueueSnapshot(
    val pendingOrInFlightCount: Long,
    val retryWaitCount: Long,
    val terminalFailedCount: Long,
    val oldestActiveAgeMs: Long?,
    val maxAttemptCount: Long,
)

/** 一个稳定的 `(chatId, clientMsgId)` 已绑定到另一个逻辑请求。 */
class OutgoingMessageConflictException(message: String) : IllegalArgumentException(message)

/**
 * 把恰好一个服务器历史响应应用到请求它的缓存代际的能力。
 *
 * 租约与缓存实例绑定，并捕获全局投影代际、chat 生命周期令牌、请求代际，以及待处理最新链或已提交
 * 历史锚点二者之一。Chat 生命周期令牌在同一缓存实例内绝不复用。调用方必须在启动 RPC 之前获取
 * 租约，并通过 [LocalCache.applyMessageHistoryPage] 提交响应。
 */
class MessageHistoryLease internal constructor(
    val chatId: String,
    internal val owner: Any,
    internal val globalGeneration: Long,
    internal val chatLifecycleGeneration: Long,
    internal val requestGeneration: Long,
    internal val historyChainGeneration: Long,
    internal val resetResidentWindow: Boolean,
)

/**
 * 一次仅常驻乐观编辑的精确预留。
 *
 * 租约与缓存实例绑定，对调用方不透明。在服务器接受之前，编辑刻意不写入 SQLite：进程崩溃必须
 * 暴露最后一个有服务器支撑的本地投影，而不是未确认的正文。[LocalCache.publishOptimisticMessageEdit]
 * 只有在 ViewModel 记录该租约用于退役之后才安装临时常驻覆盖层。
 */
interface OptimisticMessageEditLease

/**
 * 客户端本地缓存接口。
 * 具体实现由各平台提供（基于 SQLDelight）。
 *
 * 消息窗口由 [pager] 返回的精确租约拥有。调用方必须关闭 pager；实现不得
 * 按 chatId 推测 owner，也不得驱逐仍有活跃租约的窗口。
 */
interface LocalCache : LocalDocumentProjection {
    // ── 本地可靠业务命令 ──
    /** 唯一可能在无可见响应的情况下就已提交的 GUI 建群命令。 */
    fun getPendingGroupCreation(): PendingGroupCreationCommand?

    /** 在其首次或重复的 RPC 离开客户端之前，持久化完整的规范命令。 */
    fun replacePendingGroupCreation(command: PendingGroupCreationCommand)

    /** 条件确认或显式退役恰好一代命令。 */
    fun clearPendingGroupCreation(operationId: String): Boolean

    /** 原子地复用一次精确好友决策，或在其首次 RPC 之前持久化 [candidate]。 */
    fun preparePendingContactDecision(candidate: PendingContactDecision): PendingContactDecision

    fun getPendingContactDecisions(): List<PendingContactDecision>

    fun clearPendingContactDecision(operationId: String): Boolean

    /** 在 RPC 之前原子地复用一次精确的按 chat 邀请创建，或持久化 [candidate]。 */
    fun preparePendingInviteLinkCreation(candidate: PendingInviteLinkCreation): PendingInviteLinkCreation

    fun getPendingInviteLinkCreations(): List<PendingInviteLinkCreation>

    fun clearPendingInviteLinkCreation(operationId: String): Boolean

    /** 唯一可能在未到达 UI 的情况下就已提交的凭据变更。 */
    fun getPendingGroupBotCredentialCommand(): PendingGroupBotCredentialCommand?

    /** 首次 create/rotate HTTP 请求离开客户端之前的持久化屏障。 */
    fun preparePendingGroupBotCredentialCommand(
        command: PendingGroupBotCredentialCommand,
    ): PendingGroupBotCredentialCommand

    /** 在确认、已证明的终态或显式放弃之后，恰好退役一代。 */
    fun clearPendingGroupBotCredentialCommand(operationId: String): Boolean

    /** 原子地复用一次精确的语义群文件意图，或持久准入一个新代际。 */
    fun preparePendingGroupFileCommand(candidate: PendingGroupFileCommand): PendingGroupFileCommand

    fun getPendingGroupFileCommands(): List<PendingGroupFileCommand>

    /** 只条件退役已确认的命令代际。 */
    fun clearPendingGroupFileCommand(commandId: String): Boolean

    /** 原子地复用一次精确节点命令，或准入一个新的持久 move/rename 代际。 */
    fun preparePendingDocumentMoveCommand(
        candidate: PendingDocumentMoveCommand,
    ): PendingDocumentMoveCommand

    fun getPendingDocumentMoveCommands(): List<PendingDocumentMoveCommand>

    /** 只条件退役精确的已确认/已拒绝操作。 */
    fun clearPendingDocumentMoveCommand(operationId: String): Boolean

    // ── 用户 ──
    /** SQL 支撑的缓存执行主键读取，而不固定一个常驻 user 投影。 */
    fun getUser(uid: String): User?

    /** 在 SQL 支撑的缓存中，该按 key 常驻项只在该 flow 有活跃收集者时存在。 */
    fun observeUser(uid: String): Flow<User?>
    fun upsertUser(user: User)

    /**
     * 当一次临时 USER_UPDATED 有产品关系且可以物化时返回 true。SQL 支撑的缓存只可以在有界的、
     * best-effort 会话内存桥中保留未知用户；持久事件仍然无条件调用 [upsertUser]。
     */
    fun upsertTransientUserIfRelevant(user: User): Boolean {
        if (getUser(user.uid) == null) return false
        upsertUser(user)
        return true
    }

    /** 启动一次 latest-request-wins 的用户刷新，与 USER_UPDATED 建立隔断。 */
    fun beginUserSnapshot(uid: String): ProjectionSnapshotLease

    /** 只有当 [lease] 仍是该 uid 的当前请求时才应用 [user]。 */
    fun applyUserSnapshot(lease: ProjectionSnapshotLease, user: User): Boolean

    // ── 联系人 ──
    /** 活跃联系人是当前产品显式的账号级常驻实体列表。 */
    fun getContacts(): List<Contact>
    fun observeContacts(): Flow<List<Contact>>
    fun upsertContact(contact: Contact)
    fun deleteContact(friendUid: String)

    /**
     * 当前联系人投影的进程内代次。Repository 在发起好友全量请求前捕获它，
     * 用于防止请求期间到达的 CONTACT_ACCEPTED / CONTACT_DELETED 被迟到快照覆盖。
     */
    fun contactProjectionGeneration(): Long

    /**
     * 应用服务端的好友全量快照。
     *
     * 当 [expectedGeneration] 仍是当前代次时，快照会原子替换 SQLite 和内存投影，
     * 从而清理旧客户端误写的联系人。如果请求期间已有实时关系事件，则不执行
     * 删除，且只合并没有被更新事件触及的快照项。
     *
     * @return true 表示完成了全量替换；false 表示检测到并发变化并采用了安全合并。
     */
    fun applyContactSnapshot(expectedGeneration: Long, contacts: List<Contact>): Boolean

    // ── 聊天 ──
    /** SQL 支撑的缓存执行主键读取，而不固定一个常驻 Chat 投影。 */
    fun getChat(chatId: String): Chat?

    /** 在 SQL 支撑的缓存中，该按 key 常驻项只在该 flow 有活跃收集者时存在。 */
    fun observeChat(chatId: String): Flow<Chat?>
    fun upsertChat(chat: Chat)

    /** 启动一次 latest-request-wins 的 chat 刷新，与 CHAT_UPDATED/墓碑建立隔断。 */
    fun beginChatSnapshot(chatId: String): ProjectionSnapshotLease

    fun applyChatSnapshot(lease: ProjectionSnapshotLease, chat: Chat): Boolean

    /**
     * 应用权威 chat 墓碑。实现原子删除每一个 chat 拥有的 SQLite 投影（chat、conversation/草稿
     * 可靠发件箱、成员、消息、outgoing 与 bot inbox），同时保留服务器结果仍不明确的账号级可靠命令，
     * 并把已观察的消息 Flow 保留为空常驻对象以供安全重放。
     */
    fun deleteChat(chatId: String)

    // ── 成员 ──
    /** SQL 支撑的缓存按一个 chat 联接，而不固定其列表或全局 User 表。 */
    fun getMembers(chatId: String): List<Member>

    /** 在 SQL 支撑的缓存中，一个 chat 的联接投影只在被活跃收集期间常驻。 */
    fun observeMembers(chatId: String): Flow<List<Member>>
    fun upsertMember(member: Member)
    fun removeMember(chatId: String, uid: String)

    /** 为一个 chat 启动一次精确成员列表刷新。 */
    fun beginMemberSnapshot(chatId: String): ProjectionSnapshotLease

    /**
     * 原子替换一个 chat 的成员投影。更新的请求、本地变更、chat 墓碑、reset 或缓存关闭会使
     * [lease] 过期，响应被丢弃。
     */
    fun applyMemberSnapshot(lease: ProjectionSnapshotLease, members: List<Member>): Boolean

    // ── 组织目录 ──
    /** 全量组织单元投影在 cache 打开时加载；不因此加载任何组织成员。 */
    fun getOrganizationUnitProjection(): OrganizationUnitProjection
    fun observeOrganizationUnitProjection(): Flow<OrganizationUnitProjection>

    /** 单调地使组织权威失效，同时保留过期的展示行。 */
    fun advanceOrganizationRequiredRevision(revision: Long): Long

    /** 本地事件/测试替身的精确变更入口；会使在途全量单元快照失效。 */
    fun upsertOrganizationUnit(unit: OrganizationUnit)
    fun deleteOrganizationUnit(unitId: String)

    /** 启动一次带 latest-request-wins/本地变更隔断的权威全单元刷新。 */
    fun beginOrganizationUnitSnapshot(): ProjectionSnapshotLease

    /** 原子替换组织单元，并清理已不存在节点的直属成员投影。 */
    fun applyOrganizationUnitSnapshot(
        lease: ProjectionSnapshotLease,
        units: List<OrganizationUnit>,
        revision: Long,
    ): Boolean

    /**
     * 直属成员按 unitId 读取；只有活跃观察期间才从 SQLite 进入常驻内存。
     * [OrganizationMemberProjection.snapshotKnown] 持久区分权威空快照与从未读取。
     */
    fun getOrganizationMemberProjection(unitId: String): OrganizationMemberProjection
    fun observeOrganizationMemberProjection(unitId: String): Flow<OrganizationMemberProjection>

    /**
     * 短生命周期读取多个节点的已持久直属成员，不创建这些节点的常驻观察流。
     * 仅供 recursive 目录的离线降级聚合；返回项仍保留各自真实 unitId。
     */
    fun getOrganizationMembersForUnits(unitIds: Set<String>): List<OrganizationMember>

    /** 本地事件/测试替身的精确变更入口；会使同节点在途直属成员快照失效。 */
    fun upsertOrganizationMember(member: OrganizationMember)
    fun removeOrganizationMember(unitId: String, uid: String)

    /** 为 [unitId] 启动一次精确、非递归的成员刷新。 */
    fun beginOrganizationMemberSnapshot(unitId: String): ProjectionSnapshotLease

    /**
     * 原子替换一个节点的直属成员。递归 RPC 结果不得传入本方法，否则会混淆直属成员语义。
     */
    fun applyOrganizationMemberSnapshot(
        lease: ProjectionSnapshotLease,
        members: List<OrganizationMember>,
        revision: Long,
    ): Boolean

    /**
     * 释放一次失败/取消的实体、组织、文档或回应投影请求，而不打扰更新的租约。
     * 即使响应已被应用或被隔断，在 `finally` 中调用也是安全的。
     */
    fun abandonProjectionSnapshot(lease: ProjectionSnapshotLease): Boolean

    // ── 消息 ──
    fun getMessages(chatId: String, limit: Int = 50): List<Message>
    /** 精确持久投影查找；调用方仍必须校验预期的服务器身份。 */
    fun findMessage(chatId: String, clientMsgId: String): Message?
    fun insertMessage(message: Message)

    /**
     * 为乐观编辑预留一条已确认的常驻消息，而不发布或持久化它。同一消息至多有一个活跃预留。
     *
     * 当精确消息不再常驻/当前或正在被编辑时返回 null。
     */
    fun reserveOptimisticMessageEdit(message: Message): OptimisticMessageEditLease?

    /**
     * 只有当没有权威/本地投影变更取代它时才发布一次已预留的编辑。在服务器事件/历史页替换它之前，
     * 覆盖层保持仅内存。
     */
    fun publishOptimisticMessageEdit(lease: OptimisticMessageEditLease): Boolean

    /** 释放一次成功的预留，同时保留其常驻覆盖层。 */
    fun commitOptimisticMessageEdit(lease: OptimisticMessageEditLease): Boolean

    /**
     * 释放一次失败的预留，并且只有在发布之后没有更新的同消息投影获胜时才恢复之前的常驻值。
     */
    fun rollbackOptimisticMessageEdit(lease: OptimisticMessageEditLease): Boolean

    /** 在发送准入之前原子持久化乐观消息与不可变可靠发件箱载荷。 */
    fun enqueueOutgoingMessage(
        message: Message,
        now: Long,
        requestFingerprint: ByteArray? = null,
    ): OutgoingMessage

    /** 返回持久 active/failed/success 回执，可选校验请求指纹。 */
    fun getOutgoingMessage(
        chatId: String,
        clientMsgId: String,
        requestFingerprint: ByteArray? = null,
    ): OutgoingMessage?

    /** 回执优先的稳定失败查找，GC 之后有精确失败投影回退。 */
    fun findOutgoingFailureCode(chatId: String, clientMsgId: String): OutgoingFailureCode?

    /** 仅元数据的队列健康快照；实现不得 decode 出站载荷。 */
    fun outgoingQueueSnapshot(now: Long): OutgoingQueueSnapshot

    /**
     * 原子移除一个精确的终态失败本地可靠发件箱/投影对。
     * 对缺失、非终态、外部 owner 或权威投影返回 false。
     */
    fun discardTerminalFailure(ownerUid: String, chatId: String, clientMsgId: String): Boolean

    /**
     * 用 [replacement] 原子替换一个精确的终态失败本地可靠发件箱/投影对。替代者必须使用全新的
     * clientMsgId。缺失的已 GC 失败回执可以从精确的失败乐观投影恢复；任何权威投影按失败关闭处理。
     */
    fun replaceTerminalFailure(
        ownerUid: String,
        chatId: String,
        clientMsgId: String,
        replacement: Message,
        now: Long,
        requestFingerprint: ByteArray? = null,
    ): OutgoingMessage?

    /**
     * 恢复 IN_FLIGHT，只修复活跃/失败的乐观投影。SUCCESS 是状态回执；其权威消息只能来自服务器
     * 回显/历史重放。
     */
    fun recoverOutgoingMessages(now: Long): List<OutgoingMessage>

    /**
     * 仅 worker 的启动修复。与 [recoverOutgoingMessages] 不同，这从不物化有界诊断回执列表，
     * 因此不会 decode 成功载荷。
     */
    fun recoverOutgoingState(now: Long)

    /** 返回最旧的非终态行而不改变它。 */
    fun peekNextOutgoingMessage(): OutgoingMessage?

    /** 原子认领最旧的就绪行。未来的队头行刻意阻塞更新的行。 */
    fun claimNextOutgoingMessage(now: Long): OutgoingMessage?

    /** 持久化一次可恢复失败与其下一次符合条件的尝试。 */
    fun markOutgoingMessageRetry(
        localOrdinal: Long,
        error: String,
        nextAttemptAt: Long,
        now: Long,
        failureCode: OutgoingFailureCode = OutgoingFailureCode.UNEXPECTED_FAILURE,
    )

    /** 原子标记投影消息失败，并保留终态可靠发件箱诊断。 */
    fun markOutgoingMessageTerminalFailed(
        localOrdinal: Long,
        error: String,
        now: Long,
        terminalCode: Int? = null,
        failureCode: OutgoingFailureCode = OutgoingFailureCode.REMOTE_REJECTED,
    )

    /** 原子应用权威 ACK，并保留一个有界 SUCCESS 回执。 */
    fun completeOutgoingMessage(localOrdinal: Long, ack: MessageAckPayload, now: Long)

    /** 对显式账号/会话退役，终态取消所有非终态行。 */
    fun cancelOutgoingMessages(reason: String, now: Long)

    /**
     * 在调用服务器之前开始一次历史请求。
     *
     * 最新页请求（[resetResidentWindow] = true）预留一条待处理链，并使所有更早的最新请求失效。
     * 在它的完整页被应用之前，它不会成为已提交锚点。更旧页请求只绑定当前已提交锚点，绝不只是
     * 待处理的最新链；没有已提交锚点时其最终应用是过期的。
     */
    fun beginMessageHistoryLease(
        chatId: String,
        resetResidentWindow: Boolean,
    ): MessageHistoryLease

    /**
     * 如果 [lease] 仍然指名当前 cache/chat 生命周期、请求与历史链，则原子应用一个服务器历史响应。
     *
     * 实现必须在一个事务中校验并持久化完整页。过期租约返回 false，不触及 SQLite 或常驻 flow；
     * 畸形页数据抛出异常并整体回滚该页。实时 [insertMessage] 刻意独立于此隔断。
     */
    fun applyMessageHistoryPage(
        lease: MessageHistoryLease,
        messages: List<Message>,
    ): Boolean

    /**
     * 放弃一次失败或取消的请求。只有仍然当前的精确租约可以清空其通道；放弃一个已被取代的租约
     * 是 no-op。特别地，放弃最新会释放其待处理链而不改变最后已提交锚点。
     */
    fun abandonMessageHistoryLease(lease: MessageHistoryLease): Boolean
    fun updateMessage(chatId: String, clientMsgId: String, serverSeq: Long)
    fun updateMessageStatus(chatId: String, clientMsgId: String, sendStatus: Int)
    /** 变换更新（上传进度等纯 UI 状态，只更新驻留窗口不落库）。 */
    fun updateMessageInMemory(chatId: String, clientMsgId: String, transform: (Message) -> Message)

    /**
     * 创建消息分页器。首次返回最近 [windowSize] 条消息，
     * 调用 [MessagePager.loadMore] 向上加载更老消息。
     *
     * 返回值是独立、幂等关闭的窗口租约。同 chat 的多个租约共享一个驻留窗口；
     * 租约存活期间 [MessagePager.messages] 必须持续收敛。调用 [MessagePager.close]
     * 会终止该精确租约的观察流，不影响同 chat 的其他 owner。
     *
     * 实现侧最多保留 [MAX_ACTIVE_CHATS] 个窗口，且只能 LRU 淘汰零租约窗口。
     * 达到上限且全部活跃时抛出 [MessageWindowCapacityExceededException]，不得静默
     * 拆离活跃 collector。
     */
    fun pager(chatId: String, windowSize: Int = DEFAULT_MESSAGE_WINDOW): MessagePager

    // ── 表情回应 ──
    /** 按持久事件顺序应用幂等 delta，并使该 chat 的在途回应快照失效。 */
    fun applyMessageReactionDelta(payload: com.virjar.tk.protocol.MessageReactionEventPayload)

    /** RPC 之前取得该 chat 的快照租约；更新请求、delta、消息清理或 reset 会使其失效。 */
    fun beginMessageReactionSnapshot(chatId: String): ProjectionSnapshotLease

    /**
     * 原子替换 [fromSeq, toSeq] 完整闭区间。未返回的 seq 表示没有回应，空列表也必须清理旧行；
     * 不影响区间外或其他 chat。每个返回 seq 必须唯一且位于区间内。过期租约返回 false，不写入。
     */
    fun applyMessageReactionSnapshot(
        lease: ProjectionSnapshotLease,
        chatId: String,
        fromSeq: Long,
        toSeq: Long,
        summaries: List<com.virjar.tk.protocol.model.MessageReactionSummary>,
    ): Boolean

    /** 清理一条消息（撤回/删除）的回应投影。 */
    fun clearMessageReactions(chatId: String, serverSeq: Long)

    /** 观察一个 chat 内 seq → 聚合分组的当前投影；观察期间持续收敛。 */
    fun observeMessageReactions(chatId: String): Flow<Map<Long, List<com.virjar.tk.protocol.model.MessageReactionGroup>>>

    // ── 会话 ──
    fun getConversations(): List<Conversation>
    fun observeConversations(): Flow<List<Conversation>>
    fun upsertConversation(conv: Conversation)
    fun deleteConversation(chatId: String)

    /**
     * 为一次服务端会话全量请求分配唯一代次。必须在发起 RPC 之前调用。
     *
     * 代次同时为请求期间到达的 CHAT / CONVERSATION 实时事件建立边界，
     * 使迟到的旧快照不能删除刚创建的会话，也不能复活刚删除的会话。
     */
    fun beginConversationSnapshot(): Long

    /**
     * 原子收敛服务端会话全量快照。
     *
     * 返回项会被合并；服务端不再返回、且在 [snapshotGeneration] 之后没有
     * 实时变化的本地会话会按 [deleteConversation] 等价语义删除（包括草稿
     * outbox）。Chat 并不属于该 RPC 的权威范围，因此不会仅凭会话缺失而删除。
     *
     * @return true 表示该快照没有遇到更新代次；false 表示它已整体过期，
     * 或部分 chat 因请求期间发生变化而被安全跳过。
     */
    fun applyConversationSnapshot(
        snapshotGeneration: Long,
        conversations: List<Conversation>,
    ): Boolean

    /**
     * 原子推进本地已读投影并把合并后的最大水位写入持久 outbox。
     * 进入聊天页或显式标记时立即调用，不等待网络或通知回环。
     */
    fun enqueueConversationRead(chatId: String, readSeq: Long): Long

    /** 返回待镜像水位；同一 chat 永远至多一条且只增不减。 */
    fun getPendingConversationReads(): List<PendingConversationRead>

    /** 精确读取一个 chat 的待镜像水位；热路径不扫描完整 outbox。 */
    fun getPendingConversationRead(chatId: String): PendingConversationRead?

    /** 仅确认不大于 [readSeq] 的当前水位；并发产生的更大水位继续保留。 */
    fun markConversationReadMirrored(chatId: String, readSeq: Long)

    /** 更新对方已读位置（READ_SYNC 通知触发）。 */
    fun updatePeerReadSeq(chatId: String, peerReadSeq: Long)

    // ── 群共享文件投影（CONTENT-01） ──
    /** GROUP_FILE_CHANGED UPSERT delta；重复/迟到低 revision 是无操作。 */
    fun applyGroupFileUpsert(entry: com.virjar.tk.protocol.model.GroupFileEntry)

    /** GROUP_FILE_CHANGED DELETE delta；墓穴行阻挡迟到 UPSERT 复活。 */
    fun applyGroupFileDelete(chatId: String, entryId: String, tombstoneRevision: Long, updatedBy: String, updatedAt: Long)

    /** 目录页快照原子替换该 parent 的全部行。 */
    fun replaceGroupFileDirectory(chatId: String, parentId: String?, entries: List<com.virjar.tk.protocol.model.GroupFileEntry>)

    /** 读取一个目录的当前活动条目。 */
    fun activeGroupFileEntries(chatId: String, parentId: String?): List<com.virjar.tk.protocol.model.GroupFileEntry>

    /** 观察一个目录的活动条目；delta 与快照写入实时发布。 */
    fun observeGroupFileEntries(chatId: String, parentId: String?): Flow<List<com.virjar.tk.protocol.model.GroupFileEntry>>

    /** 403/404 或 reset：原子删除该群投影。 */
    fun purgeGroupFileProjection(chatId: String)

    // ── 持久事件同步 ──
    /** 读取原子的 dataset/cursor 权威元组，缓存绑定之前为 null。 */
    fun getSyncState(): ServerProjectionSyncState?

    /** 把新缓存绑定到一个 dataset；已有的不同绑定按失败关闭处理。 */
    fun bindSyncDataset(datasetId: String): ServerProjectionSyncState

    /** 只有当 [expectedDatasetId] 仍然拥有该投影时才推进。 */
    fun advanceSyncCursor(expectedDatasetId: String, eventId: Long): ServerProjectionSyncState

    /**
     * 从一个精确同步权威原子重定位紧凑服务器投影与游标。本地可靠发件箱、草稿/已读、bot 投递历史、
     * 组织与文档被保留。
     */
    fun applyServerProjectionCheckpoint(
        expectedDatasetId: String,
        expectedCursor: Long,
        checkpoint: ServerProjectionCheckpoint,
    ): ServerProjectionSyncState

    /**
     * 原子删除当前账号的全部服务器事件投影并把同步游标恢复为 0。
     * outgoing、会话草稿、已读 outbox、GUI 建群与群文件命令是本地可靠事实，必须保留并重叠加到重放投影。
     * 独立的文档草稿存储不属于 LocalCache，不受此操作影响。
     */
    fun resetServerProjection(datasetId: String)

    // ── 无头可靠 inbox ──
    /** INSERT OR IGNORE：同一持久事件重放不得产生重复业务投递。 */
    fun enqueueBotMessage(eventId: Long, message: Message)

    /** 按 eventId 返回最早一条未消费消息。 */
    fun peekBotMessage(): PendingBotMessage?

    /** 消费确认；更新精确 eventId 的 acked 状态，历史 delivery 仍可按 cursor 查询。 */
    fun ackBotMessage(eventId: Long, now: Long)

    /** 全局 eventId 正序分页；chatId 仅过滤，不改变 cursor 的含义。 */
    fun listBotMessageDeliveries(afterEventId: Long, chatId: String?, limit: Int): List<PendingBotMessage>

    /** 当前持久 delivery 日志的全局 eventId 高水位；空日志返回 0。 */
    fun maxBotMessageEventId(): Long

    /**
     * 精确更新单条会话的草稿（null = 明确清除），并原子写入镜像 outbox。
     *
     * 返回本次操作的本地 generation。镜像 RPC 只能条件确认同一 generation，
     * 防止迟到的旧请求把更新的草稿误标为已同步。
     */
    fun setConversationDraft(chatId: String, draft: String?): Long

    /** 返回尚未收到成功 RPC 应答的草稿操作，用于启动/重连重试。 */
    fun getPendingConversationDrafts(): List<PendingConversationDraft>

    /** 精确读取一个 chat 的待镜像草稿；热路径不扫描完整 outbox。 */
    fun getPendingConversationDraft(chatId: String): PendingConversationDraft?

    /** 仅当 [generation] 仍是该会话最新操作时，标记 RPC 已成功。 */
    fun markConversationDraftMirrored(chatId: String, generation: Long)

    /**
     * 使未完成的历史租约失效，并释放本缓存拥有的平台 SQL driver。没有 driver 的实现仍必须提供
     * 租约失效边界。
     */
    fun close()

    companion object {
        /** 单聊消息内存窗口大小（最近 N 条） */
        const val DEFAULT_MESSAGE_WINDOW = 100

        /** 同时驻留内存的最大聊天数（LRU 淘汰） */
        const val MAX_ACTIVE_CHATS = 20

        /** 单个同步消息短读的硬上限；短读不创建 resident window。 */
        const val MAX_MESSAGE_READ_LIMIT = 1_000
    }
}

/**
 * 消息分页器。观察内存窗口中的消息，支持向上翻页加载更老消息。
 *
 * 生命周期：由 [LocalCache.pager] 创建，ViewModel 持有。ViewModel 销毁时必须
 * 调用 [close]。关闭是幂等的，会终止该租约的 [messages]收集；已关闭 pager 不能继续分页。
 */
interface MessagePager {
    /** 当前内存窗口中的消息（按时间倒序，最新在前）。 */
    val messages: Flow<List<Message>>

    /** 是否还有更老的消息可加载。 */
    val hasMore: StateFlow<Boolean>

    /**
     * 向上加载更老的一页消息。同步操作，更新 [messages] 和 [hasMore]。
     *
     * [MessagePageLoadResult.RemoteRequired] 表示一个有界、以服务器为锚的窗口裁剪了已确认历史。
     * 调用方必须立即在返回游标之前拉取权威页；该响应链之外的普通 SQLite 行不是安全替代品。
     */
    fun loadMore(pageSize: Int = DEFAULT_PAGE_SIZE): MessagePageLoadResult

    /** 精确释放这一个 pager 租约；重复关闭是无害的。 */
    fun close()

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
        const val MAX_PAGE_SIZE = 200
        const val MAX_WINDOW_SIZE = 200
    }
}

/** 所有 resident window 都有活跃 owner 时的明确容量拒绝。 */
class MessageWindowCapacityExceededException(
    val capacity: Int,
) : IllegalStateException("All $capacity resident message windows have active leases")

sealed interface MessagePageLoadResult {
    data object LocalLoaded : MessagePageLoadResult
    data object Exhausted : MessagePageLoadResult
    data class RemoteRequired(val beforeServerSeq: Long) : MessagePageLoadResult
}
