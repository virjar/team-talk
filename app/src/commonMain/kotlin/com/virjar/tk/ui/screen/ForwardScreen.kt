package com.virjar.tk.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.virjar.tk.model.Conversation
import com.virjar.tk.ui.component.ScreenHeader
import kotlinx.coroutines.launch

@Composable
fun ForwardScreen(
    conversations: List<Conversation>,
    onForward: suspend (chatId: String) -> Boolean,
    onBack: (() -> Unit)? = null,
) {
    var forwarding by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "转发到...", onBack = onBack)

        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        }

        LazyColumn {
            items(conversations, key = { it.chatId }) { conv ->
                val isForwarding = forwarding == conv.chatId
                ListItem(
                    headlineContent = { Text(conv.chatName ?: conv.chatId.take(16)) },
                    modifier = Modifier.clickable {
                        scope.launch {
                            forwarding = conv.chatId
                            error = null
                            val ok = onForward(conv.chatId)
                            forwarding = null
                            if (ok) onBack?.invoke() else error = "转发失败"
                        }
                    }.testTag("forward.item.${conv.chatId.take(12)}"),
                    trailingContent = {
                        if (isForwarding) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    },
                )
            }
        }
    }
}
