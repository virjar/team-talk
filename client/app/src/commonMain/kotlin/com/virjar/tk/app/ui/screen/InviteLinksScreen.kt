package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.virjar.tk.app.ui.component.ScreenHeader
import com.virjar.tk.app.ui.platform.rememberClipboardTextWriter
import com.virjar.tk.app.ui.theme.Tk
import com.virjar.tk.protocol.model.InviteLink
import com.virjar.tk.shared.repository.GroupInviteLinks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun InviteLinksScreen(
    links: List<InviteLink>,
    serverBaseUrl: String,
    onCreateLink: suspend () -> String?,
    onRevokeLink: (token: String) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val scope = rememberCoroutineScope()
    val copyText = rememberClipboardTextWriter()
    LaunchedEffect(links) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000)
        }
    }

    Column(modifier = Modifier.fillMaxSize().testTag("invite.links")) {
        ScreenHeader(
            title = "群邀请链接",
            onBack = onBack,
            trailing = {
                TextButton(
                    onClick = {
                        if (!creating) {
                            creating = true
                            error = null
                            scope.launch {
                                try {
                                    if (onCreateLink() == null) error = "创建邀请链接失败，请重试"
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    error = failure.message?.takeIf(String::isNotBlank) ?: "创建邀请链接失败，请重试"
                                } finally {
                                    creating = false
                                }
                            }
                        }
                    },
                    enabled = !creating,
                    modifier = Modifier.testTag("invite.links.create"),
                ) {
                    if (creating) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("创建")
                }
            },
        )
        Text(
            "复制链接发给对方，对方点击聊天中的链接，或复制后回到应用，即可查看群聊并确认加入。撤销后链接立即失效。",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = Tk.colors.secondaryText,
        )
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp).testTag("invite.links.error"),
            )
        }
        if (links.isEmpty()) {
            Box(Modifier.fillMaxSize().testTag("invite.links.empty"), contentAlignment = Alignment.Center) {
                Text("暂无邀请链接", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(links, key = { it.token }) { link ->
                    val url = GroupInviteLinks.create(serverBaseUrl, link.token)
                    val status = when {
                        link.revokedAt > 0 -> "已撤销"
                        link.expiresAt > 0 && link.expiresAt <= now -> "已过期"
                        link.maxUses > 0 && link.useCount >= link.maxUses -> "使用次数已用完"
                        else -> "可使用"
                    }
                    val tag = "invite.link.${link.token}"
                    OutlinedCard(modifier = Modifier.fillMaxWidth().testTag(tag)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(link.name.ifBlank { "群邀请链接" }, style = MaterialTheme.typography.titleMedium)
                            Text(status, style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("$tag.status"))
                            SelectionContainer {
                                Text(
                                    url,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.testTag("$tag.url"),
                                )
                            }
                            Text(
                                if (link.maxUses == 0) "已用 ${link.useCount} 次 · 不限次数"
                                else "已用 ${link.useCount}/${link.maxUses} 次",
                                style = MaterialTheme.typography.bodySmall,
                                color = Tk.colors.secondaryText,
                                modifier = Modifier.testTag("$tag.usage"),
                            )
                            Text(
                                if (link.expiresAt <= 0) "长期有效"
                                else "有效期至 ${inviteExpiryLabel(link.expiresAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Tk.colors.secondaryText,
                                modifier = Modifier.testTag("$tag.expires"),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { copyText(url) }, modifier = Modifier.testTag("$tag.copy")) {
                                    Text("复制链接")
                                }
                                if (link.revokedAt <= 0) {
                                    TextButton(onClick = { onRevokeLink(link.token) }, modifier = Modifier.testTag("$tag.revoke")) {
                                        Text("撤销")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun inviteExpiryLabel(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(INVITE_EXPIRY_FORMAT)

private val INVITE_EXPIRY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
