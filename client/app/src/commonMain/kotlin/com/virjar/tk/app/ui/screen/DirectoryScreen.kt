package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.virjar.tk.shared.client.FriendPresence
import com.virjar.tk.protocol.model.Contact
import com.virjar.tk.protocol.model.OrganizationMember
import com.virjar.tk.protocol.model.OrganizationUnit
import com.virjar.tk.app.ui.component.AvatarPlaceholder
import com.virjar.tk.app.ui.theme.Tk

private enum class DirectorySection { ORGANIZATION, FRIENDS }

/** 通讯录的产品级入口：组织目录是主体，好友关系是并列的个人关系视图。 */
@Composable
fun DirectoryScreen(
    contacts: List<Contact>,
    friendPresenceByUid: Map<String, FriendPresence> = emptyMap(),
    units: List<OrganizationUnit>,
    members: List<OrganizationMember>,
    selectedUnitId: String?,
    organizationInitialized: Boolean,
    organizationUnitSnapshotKnown: Boolean,
    organizationLoading: Boolean,
    organizationMemberSnapshotKnown: Boolean,
    organizationMembersLoading: Boolean,
    onUnitClick: (String) -> Unit,
    onGroupClick: (chatId: String, chatName: String) -> Unit,
    onUserClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    pendingApplyCount: Int = 0,
    onFriendApplies: (() -> Unit)? = null,
    showAlphabetIndex: Boolean = true,
) {
    var sectionName by rememberSaveable { mutableStateOf(DirectorySection.ORGANIZATION.name) }
    val section = DirectorySection.valueOf(sectionName)

    Column(modifier = modifier.fillMaxSize()) {
        DirectorySwitcher(
            selected = section,
            pendingApplyCount = pendingApplyCount,
            onSelect = { sectionName = it.name },
        )
        when (section) {
            DirectorySection.ORGANIZATION -> OrganizationDirectory(
                units = units,
                members = members,
                selectedUnitId = selectedUnitId,
                initialized = organizationInitialized,
                unitSnapshotKnown = organizationUnitSnapshotKnown,
                loading = organizationLoading,
                memberSnapshotKnown = organizationMemberSnapshotKnown,
                membersLoading = organizationMembersLoading,
                onUnitClick = onUnitClick,
                onGroupClick = onGroupClick,
                onUserClick = onUserClick,
            )

            DirectorySection.FRIENDS -> ContactsListScreen(
                contacts = contacts,
                friendPresenceByUid = friendPresenceByUid,
                onContactClick = onUserClick,
                modifier = Modifier.weight(1f),
                pendingApplyCount = pendingApplyCount,
                onFriendApplies = onFriendApplies,
                showAlphabetIndex = showAlphabetIndex,
            )
        }
    }
}

@Composable
private fun DirectorySwitcher(
    selected: DirectorySection,
    pendingApplyCount: Int,
    onSelect: (DirectorySection) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Tk.spacing.lg, vertical = Tk.spacing.sm),
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(modifier = Modifier.padding(3.dp)) {
            DirectorySection.entries.forEach { item ->
                val active = item == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(item) }
                        .testTag("directory.${item.name.lowercase()}")
                        .background(
                            if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(7.dp),
                        )
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when (item) {
                            DirectorySection.ORGANIZATION -> "组织架构"
                            DirectorySection.FRIENDS -> if (pendingApplyCount > 0) "好友 · $pendingApplyCount" else "好友"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) MaterialTheme.colorScheme.onSurface else Tk.colors.secondaryText,
                    )
                }
            }
        }
    }
}

@Composable
private fun OrganizationDirectory(
    units: List<OrganizationUnit>,
    members: List<OrganizationMember>,
    selectedUnitId: String?,
    initialized: Boolean,
    unitSnapshotKnown: Boolean,
    loading: Boolean,
    memberSnapshotKnown: Boolean,
    membersLoading: Boolean,
    onUnitClick: (String) -> Unit,
    onGroupClick: (chatId: String, chatName: String) -> Unit,
    onUserClick: (String) -> Unit,
) {
    when (val placeholder = organizationDirectoryPlaceholder(
        hasUnits = units.isNotEmpty(),
        initialized = initialized,
        snapshotKnown = unitSnapshotKnown,
    )) {
        OrganizationDirectoryPlaceholder.INITIALIZING,
        OrganizationDirectoryPlaceholder.NOT_CACHED,
        OrganizationDirectoryPlaceholder.EMPTY,
        -> OrganizationEmptyState(placeholder, loading)

        null -> {
            val rows = remember(units) { flattenOrganization(units) }
            LazyColumn(modifier = Modifier.fillMaxSize().testTag("organization.directory")) {
                item(key = "directory.hint") {
                    Text(
                        "选择部门查看成员",
                        style = MaterialTheme.typography.labelMedium,
                        color = Tk.colors.metaText,
                        modifier = Modifier.padding(horizontal = Tk.spacing.lg, vertical = Tk.spacing.sm),
                    )
                }
                rows.forEach { row ->
                    item(key = "unit.${row.unit.unitId}") {
                        DepartmentRow(
                            row = row,
                            selected = row.unit.unitId == selectedUnitId,
                            onClick = onUnitClick,
                            onGroupClick = onGroupClick,
                        )
                    }
                    if (row.unit.unitId == selectedUnitId) {
                        val placeholder = organizationMemberPlaceholder(
                            hasMembers = members.isNotEmpty(),
                            snapshotKnown = memberSnapshotKnown,
                            loading = membersLoading,
                        )
                        if (placeholder != null) {
                            item(key = "unit.${row.unit.unitId}.${placeholder.name}") {
                                OrganizationMemberPlaceholderRow(placeholder, row.depth)
                            }
                        } else {
                            items(members, key = { "${row.unit.unitId}.${it.uid}" }) { member ->
                                OrganizationMemberRow(member, row.depth, onUserClick)
                            }
                        }
                    }
                }
            }
        }
    }
}

internal enum class OrganizationDirectoryPlaceholder(
    val message: String,
    val detail: String,
    val testTag: String,
) {
    INITIALIZING(
        message = "正在读取组织目录…",
        detail = "正在加载本机保存的组织架构",
        testTag = "organization.directory.initializing",
    ),
    NOT_CACHED(
        message = "组织目录尚未缓存",
        detail = "连接后将自动同步组织架构",
        testTag = "organization.directory.cache-miss",
    ),
    EMPTY(
        message = "组织架构尚未配置",
        detail = "管理员可在管理后台建立部门与部门群",
        testTag = "organization.directory.empty",
    ),
}

/** 非空的持久化行始终优先，即使更新的服务端修订已使它们过期（stale）。 */
internal fun organizationDirectoryPlaceholder(
    hasUnits: Boolean,
    initialized: Boolean,
    snapshotKnown: Boolean,
): OrganizationDirectoryPlaceholder? = when {
    hasUnits -> null
    !initialized -> OrganizationDirectoryPlaceholder.INITIALIZING
    !snapshotKnown -> OrganizationDirectoryPlaceholder.NOT_CACHED
    else -> OrganizationDirectoryPlaceholder.EMPTY
}

internal enum class OrganizationMemberPlaceholder(
    val message: String,
    val testTag: String,
) {
    LOADING("正在加载部门成员…", "organization.members.loading"),
    NOT_CACHED("部门成员尚未缓存", "organization.members.cache-miss"),
    EMPTY("该部门暂无成员", "organization.members.empty"),
}

/** 所选部门空内容展示的稳定优先级。 */
internal fun organizationMemberPlaceholder(
    hasMembers: Boolean,
    snapshotKnown: Boolean,
    loading: Boolean,
): OrganizationMemberPlaceholder? = when {
    hasMembers -> null
    loading -> OrganizationMemberPlaceholder.LOADING
    !snapshotKnown -> OrganizationMemberPlaceholder.NOT_CACHED
    else -> OrganizationMemberPlaceholder.EMPTY
}

@Composable
private fun OrganizationMemberPlaceholderRow(
    placeholder: OrganizationMemberPlaceholder,
    depth: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(placeholder.testTag)
            .padding(start = (56 + depth * 16).dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (placeholder == OrganizationMemberPlaceholder.LOADING) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(Tk.spacing.sm))
        }
        Text(
            placeholder.message,
            style = MaterialTheme.typography.bodySmall,
            color = Tk.colors.metaText,
        )
    }
}

internal data class UnitRow(val unit: OrganizationUnit, val depth: Int)

internal fun flattenOrganization(units: List<OrganizationUnit>): List<UnitRow> {
    val children = units.groupBy { it.parentId }
    val result = mutableListOf<UnitRow>()
    val visited = mutableSetOf<String>()

    fun appendIteratively(starts: List<OrganizationUnit>, startDepth: Int) {
        val pending = ArrayDeque<UnitRow>()
        starts.asReversed().forEach { pending.addLast(UnitRow(it, startDepth)) }
        while (pending.isNotEmpty()) {
            val row = pending.removeLast()
            if (!visited.add(row.unit.unitId)) continue
            result += row
            children[row.unit.unitId].orEmpty()
                .sortedWith(compareBy<OrganizationUnit> { it.sortOrder }.thenBy { it.name })
                .asReversed()
                .forEach { child -> pending.addLast(UnitRow(child, row.depth + 1)) }
        }
    }

    appendIteratively(
        children[null].orEmpty()
            .sortedWith(compareBy<OrganizationUnit> { it.sortOrder }.thenBy { it.name }),
        startDepth = 0,
    )
    // 对损坏的本地部分 projection 的防御性兜底。权威快照是完整的单根树，
    // 但乐观/离线行仍必须在不递归的情况下渲染。
    units.forEach { unit ->
        if (unit.unitId !in visited) appendIteratively(listOf(unit), startDepth = 0)
    }
    return result
}

@Composable
private fun DepartmentRow(
    row: UnitRow,
    selected: Boolean,
    onClick: (String) -> Unit,
    onGroupClick: (chatId: String, chatName: String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .clickable { onClick(row.unit.unitId) }
            .testTag("organization.unit.${row.unit.unitId.take(8)}")
            .padding(start = (16 + row.depth * 16).dp, end = Tk.spacing.lg, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Apartment,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary else Tk.colors.secondaryText,
        )
        Spacer(Modifier.width(Tk.spacing.sm))
        Text(
            row.unit.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (row.depth == 0) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${row.unit.directMemberCount} 人",
            style = MaterialTheme.typography.labelMedium,
            color = Tk.colors.metaText,
            modifier = Modifier.testTag("organization.unit.${row.unit.unitId.take(8)}.memberCount"),
        )
        row.unit.groupChatId?.let { chatId ->
            IconButton(
                onClick = { onGroupClick(chatId, "${row.unit.name}部门群") },
                modifier = Modifier
                    .size(32.dp)
                    .testTag("organization.group.${chatId.take(8)}"),
            ) {
                Icon(
                    Icons.Filled.Forum,
                    contentDescription = "进入${row.unit.name}部门群",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun OrganizationMemberRow(member: OrganizationMember, depth: Int, onClick: (String) -> Unit) {
    val displayName = member.user?.name ?: member.user?.username ?: member.uid
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable { onClick(member.uid) }
            .testTag("organization.member.${member.uid.take(8)}")
            .padding(start = (44 + depth * 16).dp, end = Tk.spacing.lg, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarPlaceholder(name = displayName, avatar = member.user?.avatar, size = 32)
        Spacer(Modifier.width(Tk.spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val metadata = listOfNotNull(member.title, if (member.primary) "主部门" else null).joinToString(" · ")
            if (metadata.isNotEmpty()) {
                Text(metadata, style = MaterialTheme.typography.bodySmall, color = Tk.colors.secondaryText)
            }
        }
    }
}

@Composable
private fun OrganizationEmptyState(
    placeholder: OrganizationDirectoryPlaceholder,
    loading: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxSize().testTag(placeholder.testTag).padding(Tk.spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (placeholder == OrganizationDirectoryPlaceholder.INITIALIZING ||
            (placeholder == OrganizationDirectoryPlaceholder.NOT_CACHED && loading)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Icon(
                    Icons.Filled.People,
                    contentDescription = null,
                    modifier = Modifier.padding(18.dp).size(30.dp),
                    tint = Tk.colors.secondaryText,
                )
            }
        }
        Spacer(Modifier.size(Tk.spacing.md))
        Text(placeholder.message, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.size(Tk.spacing.xs))
        Text(
            placeholder.detail,
            style = MaterialTheme.typography.bodySmall,
            color = Tk.colors.metaText,
        )
    }
}
