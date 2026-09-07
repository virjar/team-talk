package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.virjar.tk.app.ui.component.ScreenHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 搜索式邀请成员（T005）：以搜索为主要入口，不一次性铺开全部好友；
 * 候选排除当前群内已有成员；已选人选在切换搜索词时保留、可逐个移除。
 */
@Composable
fun InviteMembersScreen(
    friendUids: List<String>,
    friendNames: Map<String, String>,
    memberUids: Set<String>,
    onInvite: suspend (List<String>) -> Boolean,
    onBack: (() -> Unit)? = null,
) {
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var selection by remember { mutableStateOf(setOf<String>()) }
    var inviting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val eligibleUids = remember(friendUids, memberUids) {
        friendUids.filterNot { it in memberUids }.toSet()
    }
    // 成员或好友关系更新时，计数、chip 和提交立即使用同一份有效选择。
    // 同时收回旧选择，避免该用户以后退群时又无意恢复为已选；切换搜索词不清空选择。
    val selected = selection.intersect(eligibleUids)
    LaunchedEffect(eligibleUids) {
        selection = selection.intersect(eligibleUids)
    }
    val candidates = remember(eligibleUids, friendNames, query.text) {
        val text = query.text.trim()
        if (text.isEmpty()) {
            emptyList()
        } else {
            eligibleUids.asSequence()
                .filter { uid ->
                    val name = friendNames[uid].orEmpty()
                    name.contains(text, ignoreCase = true) || uid.contains(text)
                }
                .toList()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "邀请成员 (${selected.size})",
            onBack = onBack,
            trailing = {
                TextButton(
                    onClick = {
                        if (selected.isNotEmpty() && !inviting) {
                            // 点击时固定本次人选并上锁，不等待协程开始或按钮下一次重组。
                            val invitees = selected.toList()
                            inviting = true
                            error = null
                            scope.launch {
                                try {
                                    if (onInvite(invitees)) onBack?.invoke() else error = "邀请失败"
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    error = "邀请失败"
                                } finally {
                                    inviting = false
                                }
                            }
                        }
                    },
                    enabled = selected.isNotEmpty() && !inviting,
                    modifier = Modifier.testTag("invite.submit"),
                ) { Text("邀请") }
            },
        )

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜索好友名称或 UID") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.text.isNotEmpty()) {
                    IconButton(onClick = { query = TextFieldValue("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "清空搜索")
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("invite.search"),
        )

        // 已选人选：可查看、可移除；切换搜索词时保留。
        if (selected.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                selected.forEach { uid ->
                    InputChip(
                        selected = true,
                        onClick = { selection = selection - uid },
                        label = { Text(friendNames[uid] ?: uid.take(12)) },
                        trailingIcon = {
                            Icon(Icons.Filled.Close, contentDescription = "移除 ${friendNames[uid] ?: uid}", Modifier.size(16.dp))
                        },
                        modifier = Modifier.testTag("invite.selected.${uid.take(8)}"),
                    )
                }
            }
        }

        when {
            query.text.isBlank() -> InviteHint(
                "输入关键词搜索好友",
                "已在本群的好友不会出现在候选中",
                tag = "invite.hint.idle",
            )
            candidates.isEmpty() -> InviteHint(
                if (eligibleUids.isEmpty()) "没有可邀请的好友" else "没有匹配的好友",
                tag = "invite.hint.empty",
            )
            else -> LazyColumn {
                items(candidates, key = { it }) { uid ->
                    val name = friendNames[uid] ?: uid.take(12)
                    ListItem(
                        headlineContent = { Text(name) },
                        supportingContent = { Text(uid.take(16), style = MaterialTheme.typography.labelSmall) },
                        leadingContent = {
                            Checkbox(
                                checked = uid in selected,
                                onCheckedChange = { checked ->
                                    selection = if (checked) selection + uid else selection - uid
                                },
                            )
                        },
                        modifier = Modifier
                            .clickable {
                                selection = if (uid in selected) selection - uid else selection + uid
                            }
                            .testTag("invite.candidate.${uid.take(8)}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun InviteHint(title: String, detail: String? = null, tag: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (detail != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
