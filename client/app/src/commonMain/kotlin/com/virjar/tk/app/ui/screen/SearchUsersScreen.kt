package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.virjar.tk.app.ui.component.ScreenHeader
import com.virjar.tk.protocol.model.User
import kotlinx.coroutines.launch

/**
 * 搜索关键词最短限制（与服务端 UserService 规则一致）：纯汉字 ≥2，含字母/数字 ≥3。
 * 防止短词穷举遍历注册用户。
 */
fun meetsSearchKeywordMinimum(query: String): Boolean {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return false
    val cjkCount = trimmed.count { it.code in 0x4E00..0x9FFF }
    val min = if (cjkCount == trimmed.length) 2 else 3
    return trimmed.length >= min
}

@Composable
fun SearchUsersScreen(
    searchUsers: suspend (query: String) -> List<User>,
    onUserClick: (uid: String) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    var query by remember { mutableStateOf("") }
    var hasSearched by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<User>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val requestGate = remember { LatestSearchRequestGate() }

    DisposableEffect(requestGate) {
        onDispose { requestGate.invalidate() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "搜索用户", onBack = onBack)

        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    requestGate.invalidate()
                    query = it
                    hasSearched = false
                    results = emptyList()
                    isSearching = false
                    error = null
                },
                label = { Text("用户名或手机号") },
                modifier = Modifier.weight(1f).testTag("search.query"),
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    if (meetsSearchKeywordMinimum(query)) {
                        val token = requestGate.begin(query)
                        isSearching = true
                        hasSearched = true
                        error = null
                        scope.launch {
                            executeLatestSearch(
                                gate = requestGate,
                                token = token,
                                search = searchUsers,
                                onSuccess = { results = it },
                                onFailure = {
                                    error = it.message
                                    results = emptyList()
                                },
                                onFinished = { isSearching = false },
                            )
                        }
                    }
                },
                modifier = Modifier.testTag("search.submit"),
                enabled = meetsSearchKeywordMinimum(query) && !isSearching,
            ) {
                if (isSearching) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("搜索")
                }
            }
        }

        when {
            isSearching -> {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            error != null -> {
                Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }
            hasSearched && results.isEmpty() -> {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("未找到用户", style = MaterialTheme.typography.bodyLarge)
                }
            }
            else -> {
                LazyColumn {
                    items(results, key = { it.uid }) { user ->
                        ListItem(
                            headlineContent = { Text(user.name) },
                            supportingContent = { Text("@${user.username}") },
                            modifier = Modifier
                                .semantics(mergeDescendants = true) { }
                                .clickable { onUserClick(user.uid) }
                                .testTag("search.result.${user.uid.take(8)}"),
                        )
                    }
                }
            }
        }
    }
}
