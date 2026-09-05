package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.virjar.tk.app.ui.component.ScreenHeader
import com.virjar.tk.app.ui.component.SettingsGroupCard
import com.virjar.tk.app.ui.theme.Tk

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
                Text("黑名单为空", style = MaterialTheme.typography.bodyLarge, color = Tk.colors.secondaryText)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(blockedUsers, key = { it.uid }) { user ->
                    SettingsGroupCard {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                user.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(
                                onClick = { onUnblock(user.uid) },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            ) { Text("移除") }
                        }
                    }
                }
            }
        }
    }
}

data class BlockedUser(val uid: String, val name: String)
