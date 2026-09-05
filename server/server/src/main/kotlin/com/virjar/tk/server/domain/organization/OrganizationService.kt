package com.virjar.tk.server.domain.organization

import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.server.domain.user.UserRepository
import com.virjar.tk.protocol.model.OrganizationCapacityPolicy
import com.virjar.tk.protocol.model.OrganizationMember
import com.virjar.tk.protocol.model.OrganizationMemberPage
import com.virjar.tk.protocol.model.OrganizationMemberPageRequest
import com.virjar.tk.protocol.model.OrganizationPagePolicy
import com.virjar.tk.protocol.model.OrganizationUnit
import com.virjar.tk.protocol.model.OrganizationUnitPage
import com.virjar.tk.protocol.model.OrganizationUnitPageRequest
import com.virjar.tk.protocol.PacketBuffer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.Base64
import kotlin.coroutines.coroutineContext

/**
 * 单组织命令服务。
 *
 * 每次事实变更、全局修订推进与期望的受管聊天状态都在一个 PostgreSQL 工作单元中提交。
 * 投影被刻意设计成第二个、带修订围栏的事务：崩溃会留下一条持久化的挂起行，且访问
 * 默认拒绝（fail closed），直到启动/运行时排空把它应用。
 */
class OrganizationService(
    private val repository: OrganizationRepository,
    private val users: UserRepository,
    private val unitOfWork: PgUnitOfWork,
    private val projector: OrganizationManagedChatProjector,
    private val changes: OrganizationChangePublisher,
) {
    private val logger = LoggerFactory.getLogger(OrganizationService::class.java)
    fun listUnitPage(request: OrganizationUnitPageRequest): OrganizationUnitPage {
        val cursor = OrganizationUnitCursorCodec.decode(request.cursor)
        val page = repository.listUnitPage(
            expectedRevision = cursor?.revision,
            after = cursor?.anchor,
            pageSize = OrganizationUnitPage.MAX_PAGE_SIZE,
        )
        return OrganizationUnitPage(
            revision = page.revision,
            items = page.items,
            nextCursor = page.nextAnchor?.let { anchor ->
                OrganizationUnitCursorCodec.encode(page.revision, anchor)
            },
            snapshotChanged = page.snapshotChanged,
        )
    }

    fun listMemberPage(request: OrganizationMemberPageRequest): OrganizationMemberPage {
        val cursor = OrganizationMemberCursorCodec.decode(request.cursor)
        if (cursor != null) {
            require(cursor.rootUnitId == request.unitId && cursor.recursive == request.recursive) {
                "组织成员分页游标与查询范围不匹配"
            }
            require(request.recursive || cursor.anchor.unitId == request.unitId) {
                "直属成员分页游标越出组织节点"
            }
        }
        val page = repository.listMemberPage(
            rootUnitId = request.unitId,
            recursive = request.recursive,
            expectedRevision = cursor?.revision,
            after = cursor?.anchor,
            pageSize = OrganizationMemberPage.MAX_PAGE_SIZE,
        )
        val usersByUid = users.findByUids(page.items.mapTo(linkedSetOf()) { it.uid })
        val items = page.items.map { member ->
            member.copy(user = usersByUid[member.uid])
        }
        return OrganizationMemberPage(
            revision = page.revision,
            items = items,
            nextCursor = page.nextAnchor?.let { anchor ->
                OrganizationMemberCursorCodec.encode(
                    revision = page.revision,
                    rootUnitId = request.unitId,
                    recursive = request.recursive,
                    anchor = anchor,
                )
            },
            snapshotChanged = page.snapshotChanged,
        )
    }

    /** 管理端/测试用的有界内部收集器；终端 RPC 客户端使用 [listUnitPage]。 */
    fun listUnits(): List<OrganizationUnit> = collectStableSnapshot(
        maximumItems = OrganizationCapacityPolicy.MAX_ACTIVE_UNITS,
        kind = "组织节点",
    ) { cursor ->
        val page = listUnitPage(OrganizationUnitPageRequest(cursor))
        SnapshotPage(page.revision, page.items, page.nextCursor, page.snapshotChanged)
    }

    /** 管理端/测试用的有界内部收集器；没有网络响应包含整个列表。 */
    fun listMembers(unitId: String, recursive: Boolean): List<OrganizationMember> {
        val maximumItems = if (recursive) {
            OrganizationCapacityPolicy.MAX_MEMBERSHIP_RELATIONS
        } else {
            OrganizationCapacityPolicy.MAX_MEMBERS_PER_UNIT
        }
        return collectStableSnapshot(maximumItems, "组织成员关系") { cursor ->
            val page = listMemberPage(OrganizationMemberPageRequest(unitId, recursive, cursor))
            SnapshotPage(page.revision, page.items, page.nextCursor, page.snapshotChanged)
        }
    }

    suspend fun createUnit(
        parentId: String?,
        name: String,
        leaderUid: String?,
        sortOrder: Int = 0,
        enableGroup: Boolean = false,
    ): OrganizationUnit {
        validateName(name)
        validateSortOrder(sortOrder)
        val result = executeCommand {
            repository.createUnit(
                transaction,
                OrganizationUnit(
                    unitId = UUID.randomUUID().toString(),
                    parentId = parentId,
                    name = name.trim(),
                    leaderUid = leaderUid,
                    sortOrder = sortOrder,
                ),
                enableGroup,
            )
        }
        return result.value
    }

    suspend fun updateUnit(
        unitId: String,
        parentId: String?,
        name: String,
        leaderUid: String?,
        sortOrder: Int,
    ): OrganizationUnit {
        validateName(name)
        validateSortOrder(sortOrder)
        val result = executeCommand {
            repository.updateUnit(transaction, unitId, parentId, name.trim(), leaderUid, sortOrder)
        }
        return result.value
    }

    suspend fun archiveUnit(unitId: String) {
        executeCommand { repository.archiveUnit(transaction, unitId) }
    }

    suspend fun assignMember(
        unitId: String,
        uid: String,
        title: String?,
        primary: Boolean,
    ): OrganizationMember {
        val displayUser = users.findByUid(uid) ?: throw IllegalArgumentException("用户不存在: $uid")
        val member = OrganizationMember(
            unitId = unitId,
            uid = uid,
            title = title?.trim()?.takeIf(String::isNotEmpty),
            primary = primary,
            joinedAt = System.currentTimeMillis(),
        )
        val result = executeCommand { repository.assignMember(transaction, member) }
        return result.value.copy(user = displayUser)
    }

    suspend fun removeMember(unitId: String, uid: String) {
        executeCommand { repository.removeMember(transaction, unitId, uid) }
    }

    suspend fun enableDepartmentGroup(unitId: String): OrganizationUnit {
        return executeCommand { repository.enableGroup(transaction, unitId) }.value
    }

    suspend fun disableDepartmentGroup(unitId: String): OrganizationUnit {
        return executeCommand { repository.disableGroup(transaction, unitId) }.value
    }

    /** 启动/管理排空。包含延迟毒化行，使启动永远不会报告就绪。 */
    suspend fun reconcileAllManagedGroups(): List<String> =
        projector.drainPending(includeDeferred = true).failures.sorted()

    /** 运行时排空。失败的行保持其持久化退避，而不是每个 tick 都重试。 */
    suspend fun reconcileDueManagedGroups(): List<String> =
        projector.drainPending(includeDeferred = false).failures.sorted()

    private suspend fun project(tasks: List<ManagedChatProjectionTask>) {
        tasks.forEach { projector.project(it) }
    }

    /** 一旦获准进入，事实提交与尽力而为的投影调度就是一个终结性阶段。 */
    private suspend fun <T> executeCommand(
        command: com.virjar.tk.server.domain.transaction.PgWriteScope.() -> OrganizationCommandResult<T>,
    ): OrganizationCommandResult<T> {
        coroutineContext.ensureActive()
        return withContext(NonCancellable) {
            val result = unitOfWork.write(command)
            try {
                changes.publish(result.revision)
            } catch (failure: Exception) {
                // PostgreSQL 已经提交。一个瞬态提示绝不能把这条成功的管理命令变成表面上的
                // 失败；在线客户端也会在其下一个就绪边界自行刷新。
                logger.warn(
                    "Failed to publish committed organization revision={}; reconnect refresh remains authoritative",
                    result.revision,
                    failure,
                )
            }
            project(result.projections)
            result
        }
    }

    private fun validateName(name: String) {
        require(name.isNotBlank()) { "组织节点名称不能为空" }
        require(name.trim().length <= 120) { "组织节点名称不能超过 120 个字符" }
    }

    private fun validateSortOrder(sortOrder: Int) {
        require(sortOrder >= 0) { "sortOrder 不能为负数" }
    }

    private fun <T> collectStableSnapshot(
        maximumItems: Int,
        kind: String,
        load: (String?) -> SnapshotPage<T>,
    ): List<T> {
        repeat(MAX_SNAPSHOT_ATTEMPTS) {
            val collected = ArrayList<T>()
            val seenCursors = hashSetOf<String>()
            var cursor: String? = null
            var revision: Long? = null
            var restart = false
            while (true) {
                val page = load(cursor)
                if (page.snapshotChanged || (revision != null && revision != page.revision)) {
                    restart = true
                    break
                }
                if (revision == null) revision = page.revision
                if (page.items.size > maximumItems - collected.size) {
                    throw IllegalStateException("$kind 快照超过硬上限 $maximumItems")
                }
                collected += page.items
                val next = page.nextCursor ?: return collected
                if (next == cursor || !seenCursors.add(next)) {
                    throw IllegalStateException("$kind 分页游标未推进")
                }
                cursor = next
            }
            if (!restart) error("Unreachable organization snapshot state")
        }
        throw IllegalStateException("$kind 快照在 $MAX_SNAPSHOT_ATTEMPTS 次尝试内持续变化")
    }

    private data class SnapshotPage<T>(
        val revision: Long,
        val items: List<T>,
        val nextCursor: String?,
        val snapshotChanged: Boolean,
    )

    private companion object {
        const val MAX_SNAPSHOT_ATTEMPTS = 3
    }
}

private data class OrganizationUnitCursor(
    val revision: Long,
    val anchor: OrganizationUnitPageAnchor,
)

private data class OrganizationMemberCursor(
    val revision: Long,
    val rootUnitId: String,
    val recursive: Boolean,
    val anchor: OrganizationMemberPageAnchor,
)

private object OrganizationUnitCursorCodec {
    private const val MAX_DECODED_BYTES = 64
    private const val INVALID_CURSOR = "组织节点分页游标无效"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(revision: Long, anchor: OrganizationUnitPageAnchor): String = encodeCursor(
        revision = revision,
        strings = listOf(anchor.unitId),
        recursive = null,
        maximumBytes = MAX_DECODED_BYTES,
        encoder = encoder,
    )

    fun decode(encoded: String?): OrganizationUnitCursor? {
        if (encoded == null) return null
        return try {
            val decoded = decodeCursor(encoded, MAX_DECODED_BYTES, decoder, encoder)
            require(decoded.recursive == null && decoded.strings.size == 1) { INVALID_CURSOR }
            OrganizationUnitCursor(decoded.revision, OrganizationUnitPageAnchor(decoded.strings.single()))
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException(INVALID_CURSOR)
        }
    }
}

private object OrganizationMemberCursorCodec {
    private const val MAX_DECODED_BYTES = 160
    private const val INVALID_CURSOR = "组织成员分页游标无效"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(
        revision: Long,
        rootUnitId: String,
        recursive: Boolean,
        anchor: OrganizationMemberPageAnchor,
    ): String = encodeCursor(
        revision = revision,
        strings = listOf(rootUnitId, anchor.unitId, anchor.uid),
        recursive = recursive,
        maximumBytes = MAX_DECODED_BYTES,
        encoder = encoder,
    )

    fun decode(encoded: String?): OrganizationMemberCursor? {
        if (encoded == null) return null
        return try {
            val decoded = decodeCursor(encoded, MAX_DECODED_BYTES, decoder, encoder)
            require(decoded.recursive != null && decoded.strings.size == 3) { INVALID_CURSOR }
            OrganizationMemberCursor(
                revision = decoded.revision,
                rootUnitId = decoded.strings[0],
                recursive = decoded.recursive,
                anchor = OrganizationMemberPageAnchor(decoded.strings[1], decoded.strings[2]),
            )
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException(INVALID_CURSOR)
        }
    }
}

private data class DecodedOrganizationCursor(
    val revision: Long,
    val strings: List<String>,
    val recursive: Boolean?,
)

private fun encodeCursor(
    revision: Long,
    strings: List<String>,
    recursive: Boolean?,
    maximumBytes: Int,
    encoder: Base64.Encoder,
): String {
    require(revision >= 0L)
    strings.forEach(::requireCursorId)
    val raw = PacketBuffer().apply {
        writeByte(ORGANIZATION_CURSOR_FORMAT_VERSION)
        writeVarLong(revision)
        writeVarInt(strings.size)
        strings.forEach(::writeString)
        writeBoolean(recursive != null)
        if (recursive != null) writeBoolean(recursive)
    }.toByteArray()
    check(raw.size <= maximumBytes) { "Organization cursor encoding exceeded its budget" }
    return encoder.encodeToString(raw).also(OrganizationPagePolicy::requireOpaqueCursor)
}

private fun decodeCursor(
    encoded: String,
    maximumBytes: Int,
    decoder: Base64.Decoder,
    encoder: Base64.Encoder,
): DecodedOrganizationCursor {
    OrganizationPagePolicy.requireOpaqueCursor(encoded)
    val raw = decoder.decode(encoded)
    require(raw.size <= maximumBytes)
    val buffer = PacketBuffer(raw)
    require(buffer.readByte() == ORGANIZATION_CURSOR_FORMAT_VERSION)
    val revision = buffer.readVarLong()
    require(revision >= 0L)
    val count = buffer.readCollectionSize(
        maximum = 3,
        minimumBytesPerEntry = 2,
        fieldName = "organization cursor keys",
    )
    val strings = List(count) {
        buffer.readRequiredString(
            maxByteLength = OrganizationPagePolicy.MAX_ID_CHARACTERS,
            fieldName = "organization cursor key",
        ).also(::requireCursorId)
    }
    val recursive = if (buffer.readBoolean("organization cursor recursive presence")) {
        buffer.readBoolean("organization cursor recursive")
    } else {
        null
    }
    buffer.requireExhausted("organization cursor")
    require(encoder.encodeToString(raw) == encoded)
    return DecodedOrganizationCursor(revision, strings, recursive)
}

private fun requireCursorId(id: String) {
    OrganizationPagePolicy.requireResourceId(id, "Organization cursor key")
}

private const val ORGANIZATION_CURSOR_FORMAT_VERSION = 1
