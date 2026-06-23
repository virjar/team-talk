package com.virjar.tk.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.virjar.tk.ui.component.ScreenHeader

@Composable
fun BlacklistScreen(
    blockedUsers: List<BlockedUser>,
    onUnblock: (uid: String) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "黑名单", onBack = onBack)

        if (blockedUsers.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("黑名单为空", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn {
                items(blockedUsers, key = { it.uid }) { user ->
                    ListItem(
                        headlineContent = { Text(user.name) },
                        trailingContent = {
                            OutlinedButton(onClick = { onUnblock(user.uid) }) { Text("移除") }
                        },
                    )
                }
            }
        }
    }
}

data class BlockedUser(val uid: String, val name: String)
