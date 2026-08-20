package com.virjar.tk.ui.screen

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
import com.virjar.tk.model.Contact
import com.virjar.tk.model.OrganizationMember
import com.virjar.tk.model.OrganizationUnit
import com.virjar.tk.ui.component.AvatarPlaceholder
import com.virjar.tk.ui.theme.Tk

private enum class DirectorySection { ORGANIZATION, FRIENDS }

/** 通讯录的产品级入口：组织目录是主体，好友关系是并列的个人关系视图。 */
@Composable
fun DirectoryScreen(
    contacts: List<Contact>,
    units: List<OrganizationUnit>,
    members: List<OrganizationMember>,
    selectedUnitId: String?,
    organizationLoading: Boolean,
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
                loading = organizationLoading,
                onUnitClick = onUnitClick,
                onGroupClick = onGroupClick,
                onUserClick = onUserClick,
            )

            DirectorySection.FRIENDS -> ContactsListScreen(
                contacts = contacts,
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
    loading: Boolean,
    onUnitClick: (String) -> Unit,
    onGroupClick: (chatId: String, chatName: String) -> Unit,
    onUserClick: (String) -> Unit,
) {
    when {
        loading && units.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }

        units.isEmpty() -> OrganizationEmptyState()

        else -> {
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
                        if (members.isEmpty()) {
                            item(key = "unit.${row.unit.unitId}.empty") {
                                Text(
                                    "该部门暂无成员",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Tk.colors.metaText,
                                    modifier = Modifier.padding(start = (56 + row.depth * 16).dp, top = 6.dp, bottom = 10.dp),
                                )
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

private data class UnitRow(val unit: OrganizationUnit, val depth: Int)

private fun flattenOrganization(units: List<OrganizationUnit>): List<UnitRow> {
    val children = units.groupBy { it.parentId }
    val result = mutableListOf<UnitRow>()
    val visited = mutableSetOf<String>()
    fun append(unit: OrganizationUnit, depth: Int) {
        if (!visited.add(unit.unitId)) return
        result += UnitRow(unit, depth)
        children[unit.unitId].orEmpty()
            .sortedWith(compareBy<OrganizationUnit> { it.sortOrder }.thenBy { it.name })
            .forEach { append(it, depth + 1) }
    }
    children[null].orEmpty()
        .sortedWith(compareBy<OrganizationUnit> { it.sortOrder }.thenBy { it.name })
        .forEach { append(it, 0) }
    units.filterNot { it.unitId in visited }.forEach { append(it, 0) }
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
        AvatarPlaceholder(name = displayName, size = 32)
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
private fun OrganizationEmptyState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(Tk.spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Icon(
                Icons.Filled.People,
                contentDescription = null,
                modifier = Modifier.padding(18.dp).size(30.dp),
                tint = Tk.colors.secondaryText,
            )
        }
        Spacer(Modifier.size(Tk.spacing.md))
        Text("组织架构尚未配置", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.size(Tk.spacing.xs))
        Text(
            "管理员可在管理后台建立部门与部门群",
            style = MaterialTheme.typography.bodySmall,
            color = Tk.colors.metaText,
        )
    }
}
