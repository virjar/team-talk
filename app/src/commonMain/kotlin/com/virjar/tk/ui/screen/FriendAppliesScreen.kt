package com.virjar.tk.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.virjar.tk.ui.component.ScreenHeader
import com.virjar.tk.model.ContactApply
import kotlinx.coroutines.launch

@Composable
fun FriendAppliesScreen(
    applies: List<ContactApply>,
    onAccept: suspend (token: String) -> Unit,
    onReject: suspend (token: String) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    var processingToken by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "好友申请", onBack = onBack)

        if (applies.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无好友申请", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(applies, key = { it.id }) { apply ->
                    val displayName = apply.fromUser?.name ?: apply.fromUid.take(12)
                    val isProcessing = processingToken == apply.token

                    ListItem(
                        headlineContent = { Text(displayName) },
                        supportingContent = {
                            val remarkText = apply.remark
                            if (!remarkText.isNullOrEmpty()) {
                                Text(remarkText, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        trailingContent = {
                            when (apply.status) {
                                0 -> Row {
                                    FilledTonalButton(
                                        onClick = {
                                            val token = apply.token ?: return@FilledTonalButton
                                            processingToken = token
                                            scope.launch {
                                                onAccept(token)
                                                processingToken = null
                                            }
                                        },
                                        enabled = !isProcessing,
                                    ) {
                                        if (isProcessing) {
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        } else {
                                            Text("接受")
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    OutlinedButton(
                                        onClick = {
                                            val token = apply.token ?: return@OutlinedButton
                                            scope.launch { onReject(token) }
                                        },
                                        enabled = !isProcessing,
                                    ) { Text("拒绝") }
                                }
                                1 -> Text("已接受", style = MaterialTheme.typography.bodySmall)
                                2 -> Text("已拒绝", style = MaterialTheme.typography.bodySmall)
                            }
                        },
                    )
                }
            }
        }
    }
}
