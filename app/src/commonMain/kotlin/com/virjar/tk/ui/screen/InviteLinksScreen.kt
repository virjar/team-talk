package com.virjar.tk.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.virjar.tk.ui.component.ScreenHeader
import kotlinx.coroutines.launch

@Composable
fun InviteLinksScreen(
    links: List<InviteLink>,
    onCreateLink: suspend () -> String?,
    onRevokeLink: (token: String) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "邀请链接",
            onBack = onBack,
            trailing = {
                TextButton(
                    onClick = {
                        scope.launch {
                            creating = true
                            onCreateLink()
                            creating = false
                        }
                    },
                    enabled = !creating,
                ) { if (creating) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("创建") }
            },
        )

        if (links.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无邀请链接", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn {
                items(links.size) { index ->
                    val link = links[index]
                    ListItem(
                        headlineContent = { Text(link.token.take(16) + "...") },
                        supportingContent = {
                            Text("已用 ${link.useCount}/${link.maxUses} 次", style = MaterialTheme.typography.bodySmall)
                        },
                        trailingContent = {
                            if (!link.revoked) {
                                OutlinedButton(onClick = { onRevokeLink(link.token) }) { Text("撤销") }
                            } else {
                                Text("已撤销", style = MaterialTheme.typography.bodySmall)
                            }
                        },
                    )
                }
            }
        }
    }
}

data class InviteLink(
    val token: String,
    val maxUses: Int,
    val useCount: Int,
    val revoked: Boolean,
)
