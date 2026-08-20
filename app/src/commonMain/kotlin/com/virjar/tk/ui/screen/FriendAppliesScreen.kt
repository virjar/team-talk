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
import com.virjar.tk.model.ContactApplyRecord
import com.virjar.tk.ui.component.ScreenHeader
import kotlinx.coroutines.launch

internal fun friendApplyPeerUid(record: ContactApplyRecord): String =
    if (record.direction == ContactApplyRecord.DIRECTION_INCOMING) record.fromUid else record.toUid

internal fun friendApplyStatusText(record: ContactApplyRecord): String = when (record.status) {
    ContactApplyRecord.STATUS_PENDING -> if (record.direction == ContactApplyRecord.DIRECTION_OUTGOING) {
        "等待验证"
    } else {
        "待处理"
    }
    ContactApplyRecord.STATUS_ACCEPTED -> "已接受"
    ContactApplyRecord.STATUS_REJECTED -> "已拒绝"
    else -> "未知状态"
}

internal fun friendApplyDescription(record: ContactApplyRecord): String {
    val directionText = if (record.direction == ContactApplyRecord.DIRECTION_INCOMING) "收到的申请" else "发出的申请"
    val statusText = when (record.status) {
        ContactApplyRecord.STATUS_PENDING -> if (record.direction == ContactApplyRecord.DIRECTION_OUTGOING) {
            "等待对方验证"
        } else {
            "等待你处理"
        }
        ContactApplyRecord.STATUS_ACCEPTED -> if (record.direction == ContactApplyRecord.DIRECTION_OUTGOING) {
            "对方已接受"
        } else {
            "你已接受"
        }
        ContactApplyRecord.STATUS_REJECTED -> if (record.direction == ContactApplyRecord.DIRECTION_OUTGOING) {
            "对方已拒绝"
        } else {
            "你已拒绝"
        }
        else -> "状态未知"
    }
    return listOfNotNull(directionText, statusText, record.remark?.takeIf(String::isNotBlank)).joinToString(" · ")
}

internal fun canProcessFriendApply(record: ContactApplyRecord): Boolean =
    record.direction == ContactApplyRecord.DIRECTION_INCOMING &&
        record.status == ContactApplyRecord.STATUS_PENDING &&
        !record.token.isNullOrBlank()

@Composable
fun FriendAppliesScreen(
    records: List<ContactApplyRecord>,
    loading: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onAccept: suspend (token: String) -> Unit,
    onReject: suspend (token: String) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    var processingTokens by remember { mutableStateOf(emptySet<String>()) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "好友申请", onBack = onBack)

        when {
            records.isEmpty() && loading -> Box(
                Modifier.fillMaxSize().testTag("friendApply.loading"),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            records.isEmpty() -> Box(
                Modifier.fillMaxSize().testTag("friendApply.empty"),
                contentAlignment = Alignment.Center,
            ) {
                Text("暂无好友申请记录", style = MaterialTheme.typography.bodyLarge)
            }
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(records, key = ContactApplyRecord::id) { record ->
                    val displayName = record.peerUser?.name ?: friendApplyPeerUid(record).take(12)
                    val isProcessing = record.token?.let { it in processingTokens } == true

                    ListItem(
                        modifier = Modifier.testTag("friendApply.record.${record.id}"),
                        headlineContent = { Text(displayName) },
                        supportingContent = {
                            Text(friendApplyDescription(record), style = MaterialTheme.typography.bodySmall)
                        },
                        trailingContent = {
                            if (canProcessFriendApply(record)) {
                                Row {
                                    FilledTonalButton(
                                        onClick = {
                                            val token = record.token ?: return@FilledTonalButton
                                            if (token in processingTokens) return@FilledTonalButton
                                            processingTokens = processingTokens + token
                                            scope.launch {
                                                try {
                                                    onAccept(token)
                                                } finally {
                                                    processingTokens = processingTokens - token
                                                }
                                            }
                                        },
                                        enabled = !isProcessing,
                                        modifier = Modifier.testTag("friendApply.accept.${record.id}"),
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
                                            val token = record.token ?: return@OutlinedButton
                                            if (token in processingTokens) return@OutlinedButton
                                            processingTokens = processingTokens + token
                                            scope.launch {
                                                try {
                                                    onReject(token)
                                                } finally {
                                                    processingTokens = processingTokens - token
                                                }
                                            }
                                        },
                                        enabled = !isProcessing,
                                        modifier = Modifier.testTag("friendApply.reject.${record.id}"),
                                    ) { Text("拒绝") }
                                }
                            } else {
                                Text(friendApplyStatusText(record), style = MaterialTheme.typography.bodySmall)
                            }
                        },
                    )
                    HorizontalDivider()
                }

                if (hasMore || loading) {
                    item(key = "load-more") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            TextButton(
                                onClick = onLoadMore,
                                enabled = hasMore && !loading,
                                modifier = Modifier.testTag("friendApply.loadMore"),
                            ) {
                                if (loading) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Text("加载更多")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
