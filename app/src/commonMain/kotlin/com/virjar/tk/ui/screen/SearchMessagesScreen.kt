package com.virjar.tk.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.virjar.tk.model.Message
import com.virjar.tk.ui.component.ScreenHeader
import kotlinx.coroutines.launch

@Composable
fun SearchMessagesScreen(
    chatName: String? = null,
    searchMessages: suspend (query: String) -> List<Message>,
    onMessageClick: (chatId: String, seq: Long) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    var query by remember { mutableStateOf("") }
    var hasSearched by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Message>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = chatName?.let { "在 $it 中搜索" } ?: "搜索消息", onBack = onBack)

        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                query, { query = it; hasSearched = false; error = null },
                label = { Text("搜索关键词") },
                modifier = Modifier.weight(1f).testTag("search.msg.query"),
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    if (query.isNotBlank()) {
                        scope.launch {
                            isSearching = true; hasSearched = true; error = null
                            try { results = searchMessages(query) }
                            catch (e: Exception) { error = e.message; results = emptyList() }
                            isSearching = false
                        }
                    }
                },
                modifier = Modifier.testTag("search.msg.submit"),
                enabled = query.isNotBlank() && !isSearching,
            ) { if (isSearching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("搜索") }
        }

        when {
            isSearching -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            hasSearched && results.isEmpty() -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text("未找到消息") }
            else -> LazyColumn {
                items(results, key = { it.clientMsgId }) { msg ->
                    val preview = com.virjar.tk.util.MessagePreview.preview(msg)
                    ListItem(
                        headlineContent = { Text(preview.take(100), maxLines = 2) },
                        supportingContent = { Text(msg.senderUid.take(8), style = MaterialTheme.typography.bodySmall) },
                        modifier = Modifier.clickable { onMessageClick(msg.chatId, msg.serverSeq) },
                    )
                }
            }
        }
    }
}
