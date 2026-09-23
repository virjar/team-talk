package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.virjar.tk.app.ui.component.MessagePreview
import com.virjar.tk.app.ui.component.ScreenHeader
import com.virjar.tk.app.ui.platform.hourMinute
import com.virjar.tk.app.ui.platform.localUiDateTime
import com.virjar.tk.app.ui.platform.yearMonthDay
import com.virjar.tk.app.ui.theme.Tk
import com.virjar.tk.app.viewmodel.ChatHistoryCategory
import com.virjar.tk.app.viewmodel.ChatHistoryDateRange
import com.virjar.tk.app.viewmodel.ChatHistoryScanController
import com.virjar.tk.app.viewmodel.ChatHistoryScanState
import com.virjar.tk.app.viewmodel.ChatHistorySenderOption
import com.virjar.tk.app.viewmodel.chatHistoryDateRangeFromPicker
import com.virjar.tk.app.viewmodel.chatHistoryPredicate
import com.virjar.tk.app.viewmodel.messageHistoryLink
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.model.Message
import kotlinx.coroutines.delay

/** 分类入口图标；结果行复用。 */
internal fun chatHistoryCategoryIcon(category: ChatHistoryCategory): ImageVector = when (category) {
    ChatHistoryCategory.CHAT_RECORDS -> Icons.AutoMirrored.Filled.Chat
    ChatHistoryCategory.MEDIA -> Icons.Filled.Image
    ChatHistoryCategory.FILES -> Icons.Filled.Folder
    ChatHistoryCategory.LINKS -> Icons.Filled.Link
}

private fun messageIcon(message: Message): ImageVector = when (message.messageType) {
    MessageType.IMAGE.code -> Icons.Filled.Image
    MessageType.VIDEO.code -> Icons.Filled.Videocam
    MessageType.VOICE.code -> Icons.Filled.Mic
    MessageType.FILE.code, MessageType.OFFICE_REF.code, MessageType.TASK_REF.code -> Icons.Filled.Description
    else -> Icons.AutoMirrored.Filled.Chat
}

private fun formatHistoryTime(timestamp: Long): String =
    localUiDateTime(timestamp).let { "${it.yearMonthDay('/')} ${it.hourMinute()}" }

/**
 * 会话搜索中心页：顶部搜索框（搜索全部内容，回车进入「聊天记录」浏览页），
 * 下方四个分类入口。放在「会话设置」窗口的导航栈内。
 */
@Composable
fun ChatHistorySearchHubScreen(
    chatName: String,
    onSearchAll: (keyword: String) -> Unit,
    onOpenCategory: (ChatHistoryCategory) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    var query by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "搜索聊天记录", onBack = onBack)

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜索全部聊天内容") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "清空", modifier = Modifier.size(18.dp))
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearchAll(query) }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Tk.spacing.lg, vertical = Tk.spacing.sm)
                .testTag("chatHistory.hub.input"),
        )
        Text(
            text = "范围：$chatName",
            style = MaterialTheme.typography.labelSmall,
            color = Tk.colors.metaText,
            modifier = Modifier.padding(horizontal = Tk.spacing.lg),
        )
        Spacer(Modifier.height(Tk.spacing.xs))

        ChatHistoryCategory.entries.forEach { category ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenCategory(category) }
                    .padding(horizontal = Tk.spacing.lg, vertical = Tk.spacing.md)
                    .testTag("chatHistory.category.${category.name}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    chatHistoryCategoryIcon(category),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(Tk.spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(category.title, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        category.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Tk.colors.metaText,
                    )
                }
                Text("›", style = MaterialTheme.typography.titleMedium, color = Tk.colors.metaText)
            }
            HorizontalDivider(color = Tk.colors.divider)
        }
    }
}

/**
 * 分类浏览页：顶部搜索框 + 发送人/时间筛选（按用户要求的下拉交互），
 * 结果按时间从新到旧排列；点击结果跳转回聊天定位该消息。
 * 扫描与筛选状态都在本页持有；筛选或关键词变化防抖后重扫。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHistoryBrowserScreen(
    category: ChatHistoryCategory,
    chatName: String,
    initialKeyword: String,
    senderOptions: List<ChatHistorySenderOption>,
    /** 本机清空水位：已清空消息不再参与搜索展示与扫描。 */
    clearedBeforeSeq: Long = 0L,
    scanPage: suspend (fromSeq: Long, limit: Int) -> List<Message>,
    onMessageClick: (Message) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val controller = remember(category) { ChatHistoryScanController(scope, scanPage) }
    androidx.compose.runtime.DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }

    var keyword by remember(category) { mutableStateOf(initialKeyword) }
    var selectedSenders by remember(category) { mutableStateOf(emptySet<String>()) }
    var dateRange by remember(category) { mutableStateOf<ChatHistoryDateRange?>(null) }
    val scanState by controller.state.collectAsState()

    // 关键词与筛选变化防抖重扫；首次组合也触发初始扫描。
    LaunchedEffect(category, keyword, selectedSenders, dateRange, clearedBeforeSeq) {
        delay(250)
        val range = dateRange
        controller.search(
            predicate = chatHistoryPredicate(category, keyword, selectedSenders, range, clearedBeforeSeq),
            stopWhen = { message: Message ->
                message.serverSeq <= clearedBeforeSeq ||
                    (range?.let { message.timestamp < it.startMillis } ?: false)
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = category.title, onBack = onBack)

        OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            placeholder = { Text("搜索${category.title}") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (keyword.isNotEmpty()) {
                    IconButton(onClick = { keyword = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "清空", modifier = Modifier.size(18.dp))
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                val range = dateRange
                controller.search(
                    chatHistoryPredicate(category, keyword, selectedSenders, range, clearedBeforeSeq),
                    { message: Message ->
                        message.serverSeq <= clearedBeforeSeq ||
                            (range?.let { message.timestamp < it.startMillis } ?: false)
                    },
                )
            }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Tk.spacing.lg, vertical = Tk.spacing.sm)
                .testTag("chatHistory.browser.input"),
        )
        Text(
            text = chatName,
            style = MaterialTheme.typography.labelSmall,
            color = Tk.colors.metaText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Tk.spacing.lg),
        )

        ChatHistoryFilterRow(
            senderOptions = senderOptions,
            selectedSenders = selectedSenders,
            onSendersConfirmed = { selectedSenders = it },
            dateRange = dateRange,
            onDateRangeConfirmed = { dateRange = it },
            filtersApplied = selectedSenders.isNotEmpty() || dateRange != null,
            onResetFilters = {
                selectedSenders = emptySet()
                dateRange = null
            },
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                scanState.failed -> ChatHistoryErrorState(onRetry = controller::retry)
                scanState.results.isEmpty() && scanState.scanning -> CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(28.dp)
                        .testTag("chatHistory.scanning"),
                )

                scanState.results.isEmpty() && !scanState.scanning -> Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(Tk.spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = if (scanState.exhausted) {
                            "没有找到相关${category.title}"
                        } else {
                            "在最近 ${scanState.scannedCount} 条记录中未找到"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tk.colors.metaText,
                        modifier = Modifier.testTag("chatHistory.empty"),
                    )
                    if (!scanState.exhausted) {
                        TextButton(
                            onClick = controller::loadMore,
                            modifier = Modifier.testTag("chatHistory.loadMore"),
                        ) { Text("加载更多") }
                    }
                }

                else -> ChatHistoryResultList(
                    results = scanState.results,
                    senderNames = senderOptions.associate { it.uid to it.name },
                    category = category,
                    scanning = scanState.scanning,
                    exhausted = scanState.exhausted,
                    onLoadMore = controller::loadMore,
                    onMessageClick = onMessageClick,
                )
            }
        }
    }
}

/** 筛选行：发送人下拉（勾选后确定生效）、时间下拉（日历范围 + 重置/确定）、总重置。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatHistoryFilterRow(
    senderOptions: List<ChatHistorySenderOption>,
    selectedSenders: Set<String>,
    onSendersConfirmed: (Set<String>) -> Unit,
    dateRange: ChatHistoryDateRange?,
    onDateRangeConfirmed: (ChatHistoryDateRange?) -> Unit,
    filtersApplied: Boolean,
    onResetFilters: () -> Unit,
) {
    var senderMenuOpen by remember { mutableStateOf(false) }
    var pendingSenders by remember { mutableStateOf(emptySet<String>()) }
    var dateDialogOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tk.spacing.lg, vertical = Tk.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tk.spacing.sm),
    ) {
        Box {
            FilterChipButton(
                label = if (selectedSenders.isEmpty()) "发送人" else "发送人 · ${selectedSenders.size}",
                highlighted = selectedSenders.isNotEmpty(),
                onClick = {
                    pendingSenders = selectedSenders
                    senderMenuOpen = true
                },
                modifier = Modifier.testTag("chatHistory.filter.sender"),
            )
            androidx.compose.material3.DropdownMenu(
                expanded = senderMenuOpen,
                onDismissRequest = { senderMenuOpen = false },
            ) {
                Column(modifier = Modifier.heightIn(max = 320.dp)) {
                    if (senderOptions.isEmpty()) {
                        Text(
                            "暂无可选发送人",
                            style = MaterialTheme.typography.bodySmall,
                            color = Tk.colors.metaText,
                            modifier = Modifier.padding(Tk.spacing.md),
                        )
                    }
                    senderOptions.forEach { option ->
                        DropdownMenuItemRow(
                            text = option.name,
                            checked = option.uid in pendingSenders,
                            onClick = {
                                pendingSenders = if (option.uid in pendingSenders) {
                                    pendingSenders - option.uid
                                } else {
                                    pendingSenders + option.uid
                                }
                            },
                            modifier = Modifier.testTag("chatHistory.senderOption.${option.uid.take(8)}"),
                        )
                    }
                }
                HorizontalDivider()
                TextButton(
                    onClick = {
                        onSendersConfirmed(pendingSenders)
                        senderMenuOpen = false
                    },
                    enabled = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("chatHistory.filter.senderConfirm"),
                ) { Text("确定") }
            }
        }

        Box {
            FilterChipButton(
                label = when (val range = dateRange) {
                    null -> "时间"
                    else -> "${chatHistoryDayText(range.startMillis)} ~ ${chatHistoryDayText(range.endExclusiveMillis - 1)}"
                },
                highlighted = dateRange != null,
                onClick = { dateDialogOpen = true },
                modifier = Modifier.testTag("chatHistory.filter.date"),
            )
        }

        if (filtersApplied) {
            TextButton(
                onClick = onResetFilters,
                modifier = Modifier.testTag("chatHistory.filter.reset"),
            ) { Text("重置") }
        }
    }

    if (dateDialogOpen) {
        val pickerState = rememberDateRangePickerState(
            initialSelectedStartDateMillis = dateRange?.startMillis,
            initialSelectedEndDateMillis = dateRange?.endExclusiveMillis?.minus(1),
        )
        Dialog(
            onDismissRequest = { dateDialogOpen = false },
            // 460 宽任务窗口内 DateRangePicker 七列会被平台默认宽度裁剪，占满窗口宽度。
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Tk.spacing.md)
                    .testTag("chatHistory.dateDialog"),
            ) {
                Column(modifier = Modifier.padding(Tk.spacing.md)) {
                    DateRangePicker(
                        state = pickerState,
                        showModeToggle = false,
                        title = {
                            Text(
                                "选择时间范围",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = Tk.spacing.md),
                            )
                        },
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .heightIn(max = 420.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                pickerState.setSelection(null, null)
                            },
                            modifier = Modifier.testTag("chatHistory.filter.dateReset"),
                        ) { Text("重置") }
                        TextButton(
                            onClick = {
                                val start = pickerState.selectedStartDateMillis
                                val end = pickerState.selectedEndDateMillis
                                if (start != null && end != null) {
                                    onDateRangeConfirmed(chatHistoryDateRangeFromPicker(start, maxOf(start, end)))
                                } else {
                                    onDateRangeConfirmed(null)
                                }
                                dateDialogOpen = false
                            },
                            enabled = pickerState.selectedStartDateMillis != null &&
                                pickerState.selectedEndDateMillis != null,
                            modifier = Modifier.testTag("chatHistory.filter.dateConfirm"),
                        ) { Text("确定") }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChipButton(
    label: String,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (highlighted) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Tk.spacing.md, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = Tk.colors.metaText,
            )
        }
    }
}

@Composable
private fun DropdownMenuItemRow(
    text: String,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Tk.spacing.md, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (checked) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "已选择",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ChatHistoryResultList(
    results: List<Message>,
    senderNames: Map<String, String>,
    category: ChatHistoryCategory,
    scanning: Boolean,
    exhausted: Boolean,
    onLoadMore: () -> Unit,
    onMessageClick: (Message) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(results, key = { it.clientMsgId }) { message ->
            val senderName = senderNames[message.senderUid] ?: message.senderUid.take(8)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onMessageClick(message) }
                    .padding(horizontal = Tk.spacing.lg, vertical = Tk.spacing.sm)
                    .testTag("chatHistory.result.${message.clientMsgId.take(12)}"),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        messageIcon(message),
                        contentDescription = null,
                        tint = Tk.colors.secondaryText,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(Tk.spacing.sm))
                    Text(
                        text = chatHistoryHeadline(message, category),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "$senderName · ${formatHistoryTime(message.timestamp)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Tk.colors.metaText,
                )
            }
            HorizontalDivider(
                color = Tk.colors.divider.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = Tk.spacing.lg),
            )
        }
        item(key = "chatHistory.footer") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Tk.spacing.md),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    scanning -> CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    !exhausted -> TextButton(
                        onClick = onLoadMore,
                        modifier = Modifier.testTag("chatHistory.loadMore"),
                    ) { Text("加载更多") }

                    else -> Text(
                        "已经到底了",
                        style = MaterialTheme.typography.labelSmall,
                        color = Tk.colors.metaText,
                    )
                }
            }
        }
    }
}

private fun chatHistoryHeadline(message: Message, category: ChatHistoryCategory): String = when (category) {
    ChatHistoryCategory.LINKS -> messageHistoryLink(message) ?: MessagePreview.preview(message, flagsAware = false)
    ChatHistoryCategory.MEDIA -> MessagePreview.preview(message, flagsAware = false)
    else -> MessagePreview.preview(message, flagsAware = false)
}

private fun chatHistoryDayText(millis: Long): String = localUiDateTime(millis).yearMonthDay('/')

@Composable
private fun ChatHistoryErrorState(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("chatHistory.error"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("查找失败，请检查网络后重试", style = MaterialTheme.typography.bodyMedium, color = Tk.colors.metaText)
        Spacer(Modifier.height(Tk.spacing.sm))
        TextButton(onClick = onRetry, modifier = Modifier.testTag("chatHistory.retry")) { Text("重试") }
    }
}
