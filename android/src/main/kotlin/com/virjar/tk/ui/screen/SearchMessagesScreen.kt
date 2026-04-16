package com.virjar.tk.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.virjar.tk.dto.MessageSearchResult
import com.virjar.tk.viewmodel.SearchViewModel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchMessagesScreen(
    searchVm: SearchViewModel,
    channelId: String? = null,
    channelName: String = "",
    onBack: () -> Unit,
    onResultClick: (channelId: String, channelType: Int, channelName: String, seq: Long) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var hasSearched by remember { mutableStateOf(false) }
    var filtersExpanded by remember { mutableStateOf(false) }
    var senderUidInput by remember { mutableStateOf("") }
    var startDate by remember { mutableStateOf<LocalDate?>(null) }
    var endDate by remember { mutableStateOf<LocalDate?>(null) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    val searchState by searchVm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun doSearch() {
        if (query.isNotBlank()) {
            hasSearched = true
            val startTs = startDate?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
            val endTs = endDate?.plusDays(1)?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
            scope.launch {
                searchVm.search(
                    query, channelId,
                    senderUid = senderUidInput.ifBlank { null },
                    startTimestamp = startTs,
                    endTimestamp = endTs,
                )
            }
        }
    }

    // DatePicker dialogs
    if (showStartPicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = startDate?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showStartPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { ms ->
                        startDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
                    }
                    showStartPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = {
                    startDate = null
                    showStartPicker = false
                }) { Text("Clear") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showEndPicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = endDate?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showEndPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { ms ->
                        endDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
                    }
                    showEndPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = {
                    endDate = null
                    showEndPicker = false
                }) { Text("Clear") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (channelId != null) "Search in $channelName" else "Search Messages")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { paddingVal ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingVal).padding(horizontal = 16.dp)) {
            // Search input + button row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        hasSearched = false
                    },
                    label = { Text("Search messages") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { doSearch() },
                    enabled = query.isNotBlank() && !searchState.isSearching,
                ) {
                    if (searchState.isSearching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                }
            }

            // Filter toggle
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.Start,
            ) {
                TextButton(onClick = { filtersExpanded = !filtersExpanded }) {
                    Text(if (filtersExpanded) "Hide Filters" else "Show Filters")
                }
            }

            // Filter panel
            if (filtersExpanded) {
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    // Sender UID
                    OutlinedTextField(
                        value = senderUidInput,
                        onValueChange = { senderUidInput = it },
                        label = { Text("Sender UID (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    // Date range
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = startDate?.format(DateTimeFormatter.ISO_LOCAL_DATE) ?: "",
                            onValueChange = {},
                            label = { Text("From date") },
                            modifier = Modifier.weight(1f).clickable { showStartPicker = true },
                            readOnly = true,
                            enabled = false,
                        )
                        OutlinedTextField(
                            value = endDate?.format(DateTimeFormatter.ISO_LOCAL_DATE) ?: "",
                            onValueChange = {},
                            label = { Text("To date") },
                            modifier = Modifier.weight(1f).clickable { showEndPicker = true },
                            readOnly = true,
                            enabled = false,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Results
            when {
                searchState.isSearching && searchState.results.isEmpty() -> {
                    Box(
                        Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                searchState.error.isNotEmpty() && hasSearched -> {
                    Text(
                        searchState.error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                hasSearched && searchState.results.isEmpty() -> {
                    Box(
                        Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("No messages found", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                else -> {
                    LazyColumn(state = listState) {
                        items(searchState.results, key = { it.messageId }) { result ->
                            MessageSearchResultItem(
                                result = result,
                                isGlobalSearch = channelId == null,
                                onClick = {
                                    onResultClick(
                                        result.channelId,
                                        result.channelType,
                                        result.channelName.ifEmpty { result.channelId },
                                        result.seq,
                                    )
                                },
                            )
                        }
                        // Load more trigger
                        if (searchState.results.size < searchState.total) {
                            item {
                                LaunchedEffect(Unit) {
                                    scope.launch { searchVm.loadMore(channelId) }
                                }
                                Box(
                                    Modifier.fillMaxWidth().padding(16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageSearchResultItem(
    result: MessageSearchResult,
    isGlobalSearch: Boolean,
    onClick: () -> Unit,
) {
    val highlightColor = MaterialTheme.colorScheme.primary
    val formattedTime = remember(result.timestamp) {
        val date = java.util.Date(result.timestamp)
        val sdf = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault())
        sdf.format(date)
    }

    ListItem(
        headlineContent = {
            if (isGlobalSearch) {
                Text(
                    result.channelName.ifEmpty { result.channelId },
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        },
        supportingContent = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        result.senderName.ifEmpty { result.senderUid.take(8) },
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formattedTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(2.dp))
                HighlightedText(
                    html = result.highlight,
                    highlightColor = highlightColor,
                )
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
    HorizontalDivider()
}

/**
 * Parse server-returned `<em>...</em>` HTML highlight tags into Compose AnnotatedString.
 */
@Composable
private fun HighlightedText(html: String, highlightColor: Color) {
    val annotatedString = remember(html) { parseHighlightHtml(html, highlightColor) }
    Text(
        text = annotatedString,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
    )
}

private fun parseHighlightHtml(html: String, highlightColor: Color): AnnotatedString {
    val builder = AnnotatedString.Builder()
    var i = 0
    while (i < html.length) {
        val emStart = html.indexOf("<em>", i)
        if (emStart == -1) {
            builder.append(html.substring(i))
            break
        }
        // Append text before <em>
        if (emStart > i) {
            builder.append(html.substring(i, emStart))
        }
        val emEnd = html.indexOf("</em>", emStart)
        if (emEnd == -1) {
            builder.append(html.substring(emStart))
            break
        }
        val highlighted = html.substring(emStart + 4, emEnd)
        builder.withStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.Bold)) {
            append(highlighted)
        }
        i = emEnd + 5
    }
    return builder.toAnnotatedString()
}
