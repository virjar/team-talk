package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.virjar.tk.app.ui.theme.Tk
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.app.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

private const val THREAD_PAGE_SIZE = 10

/**
 * 线程面板（MSG-01/F1）：根消息固定头部 + 回复列表（reverseLayout，最新在底）。
 * 数据全量来自服务端线程查询（权威），不写本地缓存；续页传最旧已载回复的
 * seq-1（窗口语义与历史查询一致：起点包含该 seq 本身）。宿主负责定位：
 * 宽窗为右缘抽屉，窄屏为全屏页；返回键由宿主的 TkBackHandler 关闭。
 */
@Composable
internal fun ChatThreadPanel(
    viewModel: ChatViewModel,
    root: Message,
    myUid: String,
    isPersonal: Boolean,
    content: MessageContentContext,
    selectableText: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // replies 维持“新→旧”；reverseLayout 下 index 0 落在底部，即最新回复在底部
    var replies by remember(root.chatId, root.serverSeq) { mutableStateOf<List<Message>>(emptyList()) }
    var loading by remember(root.chatId, root.serverSeq) { mutableStateOf(false) }
    var hasMore by remember(root.chatId, root.serverSeq) { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    suspend fun loadOlder() {
        if (loading || !hasMore) return
        val oldest = replies.lastOrNull()?.serverSeq
        if (oldest != null && oldest <= 1L) {
            hasMore = false
            return
        }
        val fromSeq = oldest?.let { it - 1L } ?: 0L
        loading = true
        try {
            val page = viewModel.loadThreadMessages(root.serverSeq, fromSeq, THREAD_PAGE_SIZE)
            if (page.isEmpty()) {
                hasMore = false
            } else {
                hasMore = page.size >= THREAD_PAGE_SIZE
                replies = replies + page
            }
        } finally {
            loading = false
        }
    }

    LaunchedEffect(root.chatId, root.serverSeq) { loadOlder() }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Tk.spacing.md, vertical = Tk.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("线程", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
        HorizontalDivider()
        // 根消息只读展示；回复、编辑等操作回主列表完成
        MessageBubble(
            msg = root,
            isMe = root.senderUid == myUid,
            isContinuation = false,
            showSenderName = !isPersonal && root.senderUid != myUid,
            showReadIndicator = false,
            content = content,
            selectableText = selectableText,
            onLongClick = {},
            modifier = Modifier.padding(vertical = Tk.spacing.xs),
        )
        HorizontalDivider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = rememberLazyListState(),
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(
                    count = replies.size,
                    key = { index -> replies[index].let { "${it.chatId}\u0000${it.clientMsgId}" } },
                ) { index ->
                    val msg = replies[index]
                    val older = replies.getOrNull(index + 1)
                    MessageBubble(
                        msg = msg,
                        isMe = msg.senderUid == myUid,
                        isContinuation = older != null && older.senderUid == msg.senderUid,
                        showSenderName = !isPersonal && msg.senderUid != myUid,
                        showReadIndicator = false,
                        content = content,
                        selectableText = selectableText,
                        onLongClick = {},
                        modifier = Modifier.padding(vertical = Tk.spacing.xs),
                    )
                }
                if (hasMore || loading) {
                    item(key = "thread-loader") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(Tk.spacing.sm),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp).testTag("chat.thread.loading"),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                TextButton(onClick = { scope.launch { loadOlder() } }) {
                                    Text("加载更早回复")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
