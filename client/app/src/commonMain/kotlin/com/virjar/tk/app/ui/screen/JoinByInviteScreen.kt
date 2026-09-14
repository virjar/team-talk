package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.virjar.tk.app.ui.component.AvatarPlaceholder
import com.virjar.tk.app.ui.component.ScreenHeader
import com.virjar.tk.app.ui.theme.Tk
import com.virjar.tk.protocol.model.InvitePreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 先预览邀请，再把同一次查看的输入交给加入操作；解析与授权由 SDK 和服务端完成。 */
@Composable
fun JoinByInviteScreen(
    onPreview: suspend (String) -> InvitePreview,
    onJoin: suspend (String) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var preview by remember { mutableStateOf<InvitePreview?>(null) }
    var previewInput by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val busy = loading || joining

    Column(Modifier.fillMaxSize().testTag("invite.join")) {
        ScreenHeader(title = "通过邀请加入群聊", onBack = onBack)
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "粘贴收到的完整邀请链接或邀请码，先查看群聊信息，再确认加入。",
                style = MaterialTheme.typography.bodyMedium,
                color = Tk.colors.secondaryText,
            )
            OutlinedTextField(
                value = input,
                onValueChange = {
                    if (!loading && !joining) {
                        input = it
                        preview = null
                        previewInput = ""
                        error = null
                    }
                },
                label = { Text("邀请链接或邀请码") },
                enabled = !busy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("invite.join.input"),
            )
            Button(
                onClick = {
                    if (!loading && !joining && input.isNotBlank()) {
                        val requestedInput = input
                        loading = true
                        error = null
                        preview = null
                        scope.launch {
                            try {
                                preview = onPreview(requestedInput)
                                previewInput = requestedInput
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                error = failure.message?.takeIf(String::isNotBlank) ?: "查看邀请失败，请重试"
                            } finally {
                                loading = false
                            }
                        }
                    }
                },
                enabled = !busy && input.isNotBlank(),
                modifier = Modifier.testTag("invite.join.preview"),
            ) { Text("查看群聊") }
            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp).testTag("invite.join.loading"), strokeWidth = 2.dp)
                    Text("正在查看邀请…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("invite.join.error"))
            }
            preview?.let { current ->
                OutlinedCard(modifier = Modifier.fillMaxWidth().testTag("invite.join.result")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (current.chatId != null) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                AvatarPlaceholder(name = current.name)
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        current.name.orEmpty().ifBlank { "群聊" },
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.testTag("invite.join.group.name"),
                                    )
                                    Text(
                                        "${current.memberCount} 位成员",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Tk.colors.secondaryText,
                                        modifier = Modifier.testTag("invite.join.group.memberCount"),
                                    )
                                }
                            }
                        }
                        if (current.alreadyJoined) {
                            Text("你已加入这个群聊", modifier = Modifier.testTag("invite.join.alreadyJoined"))
                        }
                        Text(
                            invitePreviewStatus(current.status),
                            color = if (current.status == InvitePreview.VALID) Tk.colors.secondaryText
                            else MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("invite.join.status"),
                        )
                        if (current.alreadyJoined || current.status == InvitePreview.VALID) {
                            Button(
                                onClick = {
                                    if (!loading && !joining && preview === current && input == previewInput) {
                                        val confirmedInput = previewInput
                                        joining = true
                                        error = null
                                        scope.launch {
                                            try {
                                                onJoin(confirmedInput)
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (failure: Exception) {
                                                error = failure.message?.takeIf(String::isNotBlank)
                                                    ?: if (current.alreadyJoined) "打开群聊失败，请重试" else "加入群聊失败，请重试"
                                            } finally {
                                                joining = false
                                            }
                                        }
                                    }
                                },
                                enabled = !busy,
                                modifier = Modifier.testTag("invite.join.confirm"),
                            ) {
                                if (joining) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                else Text(if (current.alreadyJoined) "打开群聊" else "确认加入")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun invitePreviewStatus(status: Int): String = when (status) {
    InvitePreview.VALID -> "邀请链接有效"
    InvitePreview.NOT_FOUND -> "邀请不存在，请检查链接或邀请码"
    InvitePreview.REVOKED -> "邀请链接已撤销"
    InvitePreview.EXPIRED -> "邀请链接已过期"
    InvitePreview.EXHAUSTED -> "邀请链接的使用次数已用完"
    InvitePreview.GROUP_UNAVAILABLE -> "群聊已不可用"
    else -> "邀请暂不可用，请重新查看"
}
