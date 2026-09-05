package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import com.virjar.tk.protocol.model.OrganizationUnit
import com.virjar.tk.protocol.model.User
import com.virjar.tk.app.navigation.feature.document.MAX_DOCUMENT_GRANT_MEMBER_CANDIDATES
import com.virjar.tk.app.navigation.feature.document.MAX_DOCUMENT_GRANT_UNIT_CANDIDATES
import com.virjar.tk.app.navigation.feature.document.isDocumentGrantMemberSearchEligible

@Composable
internal fun CreateDocumentSpaceDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("documents.space.create.dialog"),
        title = { Text("新建文档空间") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("空间是企业文档的权限边界，可分别加入成员或整个部门。")
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text("空间名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("documents.space.name"),
                )
                OutlinedTextField(
                    description,
                    { description = it },
                    label = { Text("空间说明（可选）") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().testTag("documents.space.description"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, description.takeIf(String::isNotBlank)) },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag("documents.space.create.confirm"),
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun NameDialog(
    title: String,
    label: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value,
                { value = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("documents.name.input"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value) },
                enabled = value.isNotBlank(),
                modifier = Modifier.testTag("documents.name.confirm"),
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun DocumentSpaceManagementDialog(
    space: DocumentSpace,
    grants: List<DocumentSpaceGrant>,
    organizationUnits: List<OrganizationUnit>,
    organizationMemberCandidates: List<User>,
    organizationMemberQuery: String,
    organizationMemberSearchLoading: Boolean,
    organizationMemberSearchSubmitted: Boolean,
    organizationMemberSearchFailed: Boolean,
    onSearchOrganizationMembers: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String?) -> Unit,
    onArchive: () -> Unit,
    onUpsertGrant: (Int, String, Int, Boolean) -> Unit,
    onRemoveGrant: (DocumentSpaceGrant) -> Unit,
) {
    var name by remember(space.spaceId) { mutableStateOf(space.name) }
    var description by remember(space.spaceId) { mutableStateOf(space.description.orEmpty()) }
    var addType by remember { mutableIntStateOf(0) }
    var departmentQuery by remember(space.spaceId) { mutableStateOf("") }
    val grantedKeys = grants.mapTo(mutableSetOf()) { it.principalType to it.principalId }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("documents.space.settings.dialog"),
        title = { Text("空间设置") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 580.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("空间名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(description, { description = it }, label = { Text("空间说明") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("成员与部门", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(spaceRoleLabelForDialog(space.myRole), color = MaterialTheme.colorScheme.primary)
                }
                Text("部门授权可覆盖全部下级部门；角色按最高权限合并。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))

                grants.forEach { grant ->
                    ListItem(
                        leadingContent = {
                            Icon(
                                if (grant.principalType == DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT) Icons.Filled.Apartment else Icons.Filled.Person,
                                null,
                            )
                        },
                        headlineContent = { Text(grant.displayName ?: grant.principalId) },
                        supportingContent = {
                            Text(if (grant.includeDescendants) "包含下级部门" else if (grant.principalType == DocumentSpaceGrant.PRINCIPAL_USER) "成员" else "仅本部门")
                        },
                        trailingContent = {
                            Row {
                                TextButton(onClick = {
                                    val next = when (grant.role) {
                                        DocumentSpace.ROLE_VIEWER -> DocumentSpace.ROLE_EDITOR
                                        DocumentSpace.ROLE_EDITOR -> DocumentSpace.ROLE_ADMIN
                                        else -> DocumentSpace.ROLE_VIEWER
                                    }
                                    onUpsertGrant(grant.principalType, grant.principalId, next, grant.includeDescendants)
                                }) { Text(spaceRoleLabelForDialog(grant.role)) }
                                IconButton(onClick = { onRemoveGrant(grant) }) {
                                    Icon(Icons.Filled.DeleteOutline, contentDescription = "移除授权")
                                }
                            }
                        },
                    )
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        addType = DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT
                        departmentQuery = ""
                        onSearchOrganizationMembers("")
                    }, modifier = Modifier.weight(1f)) {
                        Text("添加部门")
                    }
                    OutlinedButton(onClick = {
                        addType = DocumentSpaceGrant.PRINCIPAL_USER
                        onSearchOrganizationMembers("")
                    }, modifier = Modifier.weight(1f)) {
                        Text("添加成员")
                    }
                }
                if (addType == DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT) {
                    Spacer(Modifier.height(8.dp))
                    Text("选择部门（默认可编辑并包含下级）")
                    OutlinedTextField(
                        value = departmentQuery,
                        onValueChange = { departmentQuery = it },
                        label = { Text("搜索部门") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("documents.grant.department.query"),
                    )
                    val options = documentGrantUnitCandidates(
                        units = organizationUnits,
                        grantedUnitIds = grantedKeys.asSequence()
                            .filter { it.first == DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT }
                            .mapTo(mutableSetOf()) { it.second },
                        query = departmentQuery,
                    )
                    options.forEach { (id, label) ->
                        ListItem(
                            modifier = Modifier.clickable {
                                onUpsertGrant(
                                    DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
                                    id,
                                    DocumentSpace.ROLE_EDITOR,
                                    true,
                                )
                                addType = 0
                            },
                            headlineContent = { Text(label) },
                            supportingContent = { Text(id) },
                        )
                    }
                    if (options.isEmpty()) Text("没有可添加的对象", modifier = Modifier.padding(16.dp))
                    if (options.size == MAX_DOCUMENT_GRANT_UNIT_CANDIDATES) {
                        Text(
                            if (departmentQuery.isBlank()) {
                                "仅显示前 30 个部门，可输入名称或 ID 继续查找"
                            } else {
                                "匹配部门较多，仅显示前 30 个，请继续缩小关键词"
                            },
                        )
                    }
                } else if (addType == DocumentSpaceGrant.PRINCIPAL_USER) {
                    Spacer(Modifier.height(8.dp))
                    Text("选择成员（默认可编辑）")
                    OutlinedTextField(
                        value = organizationMemberQuery,
                        onValueChange = onSearchOrganizationMembers,
                        label = { Text("搜索成员") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("documents.grant.member.query"),
                    )
                    when {
                        !isDocumentGrantMemberSearchEligible(organizationMemberQuery) -> Text(
                            "请输入至少 2 个汉字，或 3 个字母/数字",
                            modifier = Modifier.padding(16.dp),
                        )
                        organizationMemberSearchLoading -> Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                        organizationMemberSearchFailed -> Text(
                            "搜索失败，请修改关键词或重试",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(16.dp),
                        )
                        else -> {
                            val candidates = organizationMemberCandidates.asSequence()
                                .filter { user ->
                                    (DocumentSpaceGrant.PRINCIPAL_USER to user.uid) !in grantedKeys
                                }
                                .take(MAX_DOCUMENT_GRANT_MEMBER_CANDIDATES)
                                .toList()
                            candidates.forEach { user ->
                                ListItem(
                                    modifier = Modifier.clickable {
                                        onUpsertGrant(
                                            DocumentSpaceGrant.PRINCIPAL_USER,
                                            user.uid,
                                            DocumentSpace.ROLE_EDITOR,
                                            false,
                                        )
                                        onSearchOrganizationMembers("")
                                        addType = 0
                                    },
                                    headlineContent = { Text(user.name) },
                                    supportingContent = { Text("@${user.username}") },
                                )
                            }
                            if (organizationMemberSearchSubmitted && candidates.isEmpty()) {
                                Text("未找到可添加的成员", modifier = Modifier.padding(16.dp))
                            }
                        }
                    }
                }
                if (space.myRole == DocumentSpace.ROLE_OWNER) {
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    TextButton(onClick = onArchive, modifier = Modifier.testTag("documents.space.archive")) {
                        Text("归档空间", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = { onSave(name, description.takeIf(String::isNotBlank)) },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag("documents.space.settings.save"),
            ) { Text("保存空间信息") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 基于序列的本地渐进过滤：扫描可以检查树，但只有可见页会分配内存。 */
internal fun documentGrantUnitCandidates(
    units: List<OrganizationUnit>,
    grantedUnitIds: Set<String>,
    query: String,
    limit: Int = MAX_DOCUMENT_GRANT_UNIT_CANDIDATES,
): List<Pair<String, String>> {
    require(limit in 1..MAX_DOCUMENT_GRANT_UNIT_CANDIDATES)
    val normalized = query.trim()
    return units.asSequence()
        .filterNot { it.unitId in grantedUnitIds }
        .filter { unit ->
            normalized.isEmpty() ||
                unit.name.contains(normalized, ignoreCase = true) ||
                unit.unitId.contains(normalized, ignoreCase = true)
        }
        .take(limit)
        .map { it.unitId to it.name }
        .toList()
}

private fun spaceRoleLabelForDialog(role: Int): String = when (role) {
    DocumentSpace.ROLE_OWNER -> "所有者"
    DocumentSpace.ROLE_ADMIN -> "管理员"
    DocumentSpace.ROLE_EDITOR -> "可编辑"
    else -> "可查看"
}
