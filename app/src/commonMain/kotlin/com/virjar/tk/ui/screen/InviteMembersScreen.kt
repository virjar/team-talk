package com.virjar.tk.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.virjar.tk.ui.component.ScreenHeader
import kotlinx.coroutines.launch

@Composable
fun InviteMembersScreen(
    friendUids: List<String>,
    friendNames: Map<String, String>,
    onInvite: suspend (List<String>) -> Boolean,
    onBack: (() -> Unit)? = null,
) {
    var selected by remember { mutableStateOf(setOf<String>()) }
    var inviting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "邀请成员 (${selected.size})",
            onBack = onBack,
            trailing = {
                TextButton(
                    onClick = {
                        if (selected.isNotEmpty()) {
                            scope.launch {
                                inviting = true
                                val ok = onInvite(selected.toList())
                                inviting = false
                                if (ok) onBack?.invoke() else error = "邀请失败"
                            }
                        }
                    },
                    enabled = selected.isNotEmpty() && !inviting,
                ) { Text("邀请") }
            },
        )

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }

        LazyColumn {
            items(friendUids.size) { index ->
                val uid = friendUids[index]
                val name = friendNames[uid] ?: uid.take(12)
                ListItem(
                    headlineContent = { Text(name) },
                    leadingContent = {
                        Checkbox(
                            checked = uid in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + uid else selected - uid
                            },
                        )
                    },
                    modifier = Modifier.clickable {
                        selected = if (uid in selected) selected - uid else selected + uid
                    },
                )
            }
        }
    }
}
