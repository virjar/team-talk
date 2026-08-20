package com.virjar.tk.ui.screen

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.virjar.tk.http.GroupBotCredentials
import com.virjar.tk.http.GroupBotSummary
import com.virjar.tk.ui.component.ScreenHeader

/** 群内受控通知机器人。平台壳只负责导航，凭据不会进入 SavedState 或持久缓存。 */
@Composable
fun GroupBotsScreen(
    chatId: String,
    serverUrl: String,
    bots: List<GroupBotSummary>,
    loading: Boolean,
    error: String?,
    canCreate: Boolean,
    creating: Boolean,
    operationBotId: String?,
    credentials: GroupBotCredentials?,
    onRefresh: () -> Unit,
    onCreate: (name: String) -> Unit,
    onRotate: (botId: String) -> Unit,
    onRemove: (botId: String) -> Unit,
    onDismissCredentials: () -> Unit,
    onBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
) {
    var showCreate by remember(chatId) { mutableStateOf(false) }
    var rotateTarget by remember(chatId) { mutableStateOf<GroupBotSummary?>(null) }
    var removeTarget by remember(chatId) { mutableStateOf<GroupBotSummary?>(null) }

    if (showCreate) {
        CreateGroupBotDialog(
            creating = creating,
            onDismiss = { if (!creating) showCreate = false },
            onCreate = { name ->
                showCreate = false
                onCreate(name)
            },
        )
    }
    rotateTarget?.let { bot ->
        AlertDialog(
            onDismissRequest = { rotateTarget = null },
            title = { Text("轮换“${bot.name}”的 Token？") },
            text = { Text("旧 Token 会立即失效。新 Token 只显示一次，请先准备好安全保存位置。") },
            confirmButton = {
                TextButton(
                    onClick = { rotateTarget = null; onRotate(bot.botId) },
                    modifier = Modifier.testTag("group.bot.rotate.confirm"),
                ) { Text("继续轮换") }
            },
            dismissButton = { TextButton(onClick = { rotateTarget = null }) { Text("取消") } },
        )
    }
    removeTarget?.let { bot ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("移除“${bot.name}”？") },
            text = { Text("机器人会立即失去向本群发送通知的权限，现有 Token 也会失效。历史消息不会删除。") },
            confirmButton = {
                TextButton(
                    onClick = { removeTarget = null; onRemove(bot.botId) },
                    modifier = Modifier.testTag("group.bot.remove.confirm"),
                ) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("取消") } },
        )
    }
    credentials?.let { value ->
        GroupBotCredentialsDialog(
            serverUrl = serverUrl,
            credentials = value,
            onDismiss = onDismissCredentials,
        )
    }

    Column(Modifier.fillMaxSize().testTag("group.bots.screen")) {
        ScreenHeader(
            title = "群机器人",
            onBack = onBack,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onRefresh, enabled = !loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新机器人")
                    }
                    if (canCreate) {
                        IconButton(
                            onClick = { showCreate = true },
                            enabled = !creating,
                            modifier = Modifier.testTag("group.bots.add"),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "添加机器人")
                        }
                    }
                    if (onBack == null && onClose != null) {
                        IconButton(onClick = onClose, modifier = Modifier.testTag("group.bots.close")) {
                            Icon(Icons.Filled.Close, contentDescription = "关闭机器人")
                        }
                    }
                }
            },
        )

        Card(Modifier.fillMaxWidth().padding(12.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("让外部系统向本群发通知", fontWeight = FontWeight.SemiBold)
                Text(
                    "TeamTalk 会生成已绑定本群的入站通知地址和一次性 Bot Token。它适合 CI、监控和审批系统，不需要再填写群 ID 或第三方 API Key。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "所有群成员都可以添加机器人；创建者管理自己的 Token，群管理员可移除不再需要的机器人。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                loading && bots.isEmpty() -> CircularProgressIndicator(
                    Modifier.align(Alignment.Center).testTag("group.bots.loading"),
                )
                error != null && bots.isEmpty() -> GroupBotsError(
                    message = error,
                    onRetry = onRefresh,
                    modifier = Modifier.align(Alignment.Center),
                )
                bots.isEmpty() -> GroupBotsEmpty(
                    canCreate = canCreate,
                    onAdd = { showCreate = true },
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    if (error != null) {
                        item("error") {
                            Text(
                                error,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    items(bots, key = { it.botId }) { bot ->
                        GroupBotRow(
                            bot = bot,
                            endpoint = absoluteBotEndpoint(serverUrl, bot.apiPath),
                            canRotate = bot.canRotateToken,
                            canRemove = bot.canRemove,
                            operationInProgress = operationBotId == bot.botId,
                            onRotate = { rotateTarget = bot },
                            onRemove = { removeTarget = bot },
                        )
                        HorizontalDivider(Modifier.padding(start = 56.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateGroupBotDialog(
    creating: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加通知机器人") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("创建后会立即加入当前群，并生成只显示一次的 Token。")
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 100) name = it },
                    singleLine = true,
                    label = { Text("机器人名称") },
                    placeholder = { Text("例如：发布通知") },
                    modifier = Modifier.fillMaxWidth().testTag("group.bots.create.name"),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name.trim()) },
                enabled = name.isNotBlank() && !creating,
                modifier = Modifier.testTag("group.bots.create.confirm"),
            ) {
                if (creating) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("创建并生成 Token")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !creating) { Text("取消") } },
    )
}

@Composable
private fun GroupBotRow(
    bot: GroupBotSummary,
    endpoint: String,
    canRotate: Boolean,
    canRemove: Boolean,
    operationInProgress: Boolean,
    onRotate: () -> Unit,
    onRemove: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var menuOpen by remember(bot.botId) { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.testTag("group.bot.${bot.botId.take(8)}"),
        leadingContent = { Icon(Icons.Filled.SmartToy, contentDescription = null) },
        headlineContent = { Text(bot.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (bot.status == 1) "运行中" else "已停用",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (bot.status == 1) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(endpoint, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                if (!bot.groupManaged) {
                    Text("由系统管理员管理", style = MaterialTheme.typography.labelSmall)
                } else {
                    Text(
                        when {
                            bot.createdByMe && bot.lastUsedAt == null -> "由我创建 · 尚未调用 · Token 遗失时请轮换"
                            bot.createdByMe -> "由我创建 · 已产生调用 · Token 遗失时请轮换"
                            bot.lastUsedAt == null -> "由群成员创建 · 尚未调用"
                            else -> "由群成员创建 · 已产生调用"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        trailingContent = {
            when {
                operationInProgress -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else -> Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.testTag("group.bot.${bot.botId.take(8)}.more"),
                    ) { Icon(Icons.Filled.MoreVert, contentDescription = "机器人操作") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("复制入站通知 URL") },
                            onClick = {
                                clipboard.setText(AnnotatedString(endpoint))
                                menuOpen = false
                            },
                            modifier = Modifier.testTag("group.bot.${bot.botId.take(8)}.copyUrl"),
                        )
                        if (canRotate) {
                            DropdownMenuItem(
                                text = { Text("轮换 Token") },
                                onClick = { menuOpen = false; onRotate() },
                            )
                        }
                        if (canRemove) {
                            DropdownMenuItem(
                                text = { Text("移除机器人", color = MaterialTheme.colorScheme.error) },
                                onClick = { menuOpen = false; onRemove() },
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun GroupBotCredentialsDialog(
    serverUrl: String,
    credentials: GroupBotCredentials,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val endpoint = absoluteBotEndpoint(serverUrl, credentials.bot.apiPath)
    val curl = botCurlExample(endpoint, credentials.webhookToken)
    AlertDialog(
        // 凭据只显示一次，必须由用户显式确认；点击外部或系统 Back 不应误丢。
        onDismissRequest = {},
        modifier = Modifier.testTag("group.bots.credentials"),
        title = { Text("请立即保存机器人凭据") },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "服务端只保存 Token 的哈希。关闭后无法找回，遗失时只能轮换。请勿发送到群聊或写入源码。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                CredentialField("TeamTalk 入站通知 URL", endpoint) {
                    clipboard.setText(AnnotatedString(endpoint))
                }
                Text(
                    "该 URL 已绑定当前群，请求正文只需要提供 Markdown。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CredentialField("Bearer Token", credentials.webhookToken) {
                    clipboard.setText(AnnotatedString(credentials.webhookToken))
                }
                Text("调用示例", fontWeight = FontWeight.SemiBold)
                SelectionContainer {
                    Text(
                        curl,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(curl)) },
                    modifier = Modifier.fillMaxWidth().testTag("group.bots.credentials.copyExample"),
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("复制完整调用示例")
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, modifier = Modifier.testTag("group.bots.credentials.saved")) {
                Text("我已安全保存")
            }
        },
    )
}

@Composable
private fun CredentialField(label: String, value: String, onCopy: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            SelectionContainer {
                Text(
                    value,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
            }
            IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, contentDescription = "复制$label") }
        }
    }
}

@Composable
private fun GroupBotsError(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(24.dp).testTag("group.bots.error"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(message, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onRetry, modifier = Modifier.testTag("group.bots.retry")) { Text("重试") }
    }
}

@Composable
private fun GroupBotsEmpty(canCreate: Boolean, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(24.dp).testTag("group.bots.empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.SmartToy, contentDescription = null, Modifier.size(42.dp))
        Text("本群暂未添加机器人")
        if (canCreate) Button(onClick = onAdd) { Text("添加机器人") }
    }
}

internal fun absoluteBotEndpoint(serverUrl: String, apiPath: String): String =
    serverUrl.trimEnd('/') + "/" + apiPath.trimStart('/')

internal fun botCurlExample(endpoint: String, token: String): String =
    """curl -X POST '$endpoint' \
  -H 'Authorization: Bearer $token' \
  -H 'Content-Type: application/json' \
  -d '{"markdown":"## 构建完成\n\n版本已发布。"}'"""
