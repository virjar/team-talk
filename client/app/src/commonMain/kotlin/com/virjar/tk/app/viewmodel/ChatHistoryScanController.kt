package com.virjar.tk.app.viewmodel

import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.body.FileBody
import com.virjar.tk.protocol.body.OfficeRefBody
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.body.TaskRefBody
import com.virjar.tk.protocol.model.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * 单会话聊天记录分类：浏览页的数据口径。
 * 链接没有独立消息类型，是富文本/引用正文中的 http(s) URL，由客户端识别。
 */
enum class ChatHistoryCategory(val title: String, val subtitle: String) {
    CHAT_RECORDS("聊天记录", "文字、语音"),
    MEDIA("图片及视频", "图片、视频"),
    FILES("文件", "文件、任务、文档"),
    LINKS("链接", "网址链接"),
}

/** 发送人筛选项；name 已解析为展示名。 */
data class ChatHistorySenderOption(val uid: String, val name: String)

/** 本地日历日粒度的时间范围；[endExclusiveMillis] 为结束日次日零点。 */
data class ChatHistoryDateRange(val startMillis: Long, val endExclusiveMillis: Long) {
    fun contains(timestamp: Long): Boolean = timestamp in startMillis until endExclusiveMillis
}

/**
 * 从 Material DatePicker 的 UTC 零点毫秒解析出所选的起止“日”，再换算为本地时区的
 * 日界。日期选择最小单位是天，边界归属跟随设备时区的自然日。
 */
fun chatHistoryDateRangeFromPicker(
    startUtcMillis: Long,
    endUtcMillis: Long,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): ChatHistoryDateRange {
    val utc = TimeZone.UTC
    val startDay = Instant.fromEpochMilliseconds(startUtcMillis).toLocalDateTime(utc).date
    val endDay = Instant.fromEpochMilliseconds(endUtcMillis).toLocalDateTime(utc).date
    return ChatHistoryDateRange(
        startMillis = startDay.atStartOfDayIn(zone).toEpochMilliseconds(),
        endExclusiveMillis = endDay.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds(),
    )
}

/** 提取消息中第一个 http(s) 链接；没有则 null。链接只可能出现在富文本/引用正文里。 */
fun messageHistoryLink(message: Message): String? {
    if (message.messageType != MessageType.RICH_TEXT.code &&
        message.messageType != MessageType.REPLY.code
    ) {
        return null
    }
    val body = message.body as? RichTextBody ?: return null
    return historyLinkRegex.find(body.plainText)?.value
        ?: historyLinkRegex.find(body.markdown)?.value
}

private val historyLinkRegex = Regex("https?://[^\\s)><\\]]+", RegexOption.IGNORE_CASE)

/** 参与关键词匹配的正文文本：正文纯文本/Markdown、文件名、文档/任务标题。 */
fun messageHistorySearchText(message: Message): String = when (val body = message.body) {
    is RichTextBody -> body.plainText + "\n" + body.markdown
    is FileBody -> body.attachment.name
    is OfficeRefBody -> "${body.title}\n${body.subtitle}"
    is TaskRefBody -> "${body.title}\n${body.subtitle}"
    else -> ""
}

/** 分类口径：见 [ChatHistoryCategory]；已撤回消息不进入任何分类。 */
fun messageInHistoryCategory(message: Message, category: ChatHistoryCategory): Boolean {
    if (message.flags and Message.FLAG_REVOKED != 0) return false
    return when (category) {
        ChatHistoryCategory.CHAT_RECORDS -> message.messageType in intArrayOf(
            MessageType.RICH_TEXT.code,
            MessageType.REPLY.code,
            MessageType.VOICE.code,
            MessageType.STICKER.code,
        )

        ChatHistoryCategory.MEDIA -> message.messageType in intArrayOf(
            MessageType.IMAGE.code,
            MessageType.VIDEO.code,
        )

        ChatHistoryCategory.FILES -> message.messageType in intArrayOf(
            MessageType.FILE.code,
            MessageType.OFFICE_REF.code,
            MessageType.TASK_REF.code,
        )

        ChatHistoryCategory.LINKS -> messageHistoryLink(message) != null
    }
}

/**
 * 组合过滤谓词：分类口径 + 关键词（不区分大小写的包含匹配，空白关键词不限制）
 * + 发送人集合（空集合不限制）+ 时间范围（null 不限制）
 * + 本机清空水位（服务端读取不经过本地落库路径，必须在此排除已清空消息）。
 */
fun chatHistoryPredicate(
    category: ChatHistoryCategory,
    keyword: String,
    senderUids: Set<String>,
    dateRange: ChatHistoryDateRange?,
    clearedBeforeSeq: Long = 0L,
): (Message) -> Boolean {
    val normalizedKeyword = keyword.trim()
    return predicate@{ message ->
        if (message.serverSeq <= clearedBeforeSeq) return@predicate false
        if (!messageInHistoryCategory(message, category)) return@predicate false
        if (senderUids.isNotEmpty() && message.senderUid !in senderUids) return@predicate false
        if (dateRange != null && !dateRange.contains(message.timestamp)) return@predicate false
        if (normalizedKeyword.isNotEmpty() &&
            !messageHistorySearchText(message).contains(normalizedKeyword, ignoreCase = true)
        ) {
            return@predicate false
        }
        true
    }
}

/** 扫描器对外状态；[results] 严格时间从新到旧。 */
data class ChatHistoryScanState(
    val results: List<Message> = emptyList(),
    val scanning: Boolean = false,
    /** 历史已扫完（分页耗尽、到达首条消息或按时间范围提前判定到界）。 */
    val exhausted: Boolean = false,
    /** 本轮扫描预算用尽但历史尚未到底；[loadMore] 可继续。 */
    val budgetReached: Boolean = false,
    val failed: Boolean = false,
    val scannedCount: Long = 0,
)

/**
 * 聊天记录扫描器：基于只读 `queryHistory` 的时间倒序分页，在客户端应用过滤。
 *
 * - 每次搜索从最新一页重扫；页之间用“上一页最小 seq - 1”衔接（0 表示最新页）。
 * - [search] 的 [stopWhen] 允许按时间范围提前停止：倒序扫描中遇到范围外的更早
 *   消息即可判定后续都在范围外，不必扫完全部历史。
 * - 预算按“读取的消息条数”计，不按命中数计，避免空结果会话无限拉取；加载更多
 *   按需追加预算并继续（结果仍然从头到尾保持倒序）。
 * - 只读，不写本地缓存、不触碰常驻窗口；失败置 [ChatHistoryScanState.failed] 供重试。
 */
class ChatHistoryScanController(
    private val scope: CoroutineScope,
    private val scanPage: suspend (fromSeq: Long, limit: Int) -> List<Message>,
) {
    companion object {
        const val PAGE_SIZE = Message.MAX_QUERY_PAGE_SIZE
        const val INITIAL_SCAN_BUDGET = 300
        const val LOAD_MORE_SCAN_STEP = 300
    }

    private val _state = MutableStateFlow(ChatHistoryScanState())
    val state: StateFlow<ChatHistoryScanState> = _state.asStateFlow()

    private var predicate: (Message) -> Boolean = { true }
    private var stopWhen: (Message) -> Boolean = { false }
    private var scanBudget = INITIAL_SCAN_BUDGET
    private var nextFromSeq = 0L
    private var requestNonce = 0L
    private var scanJob: Job? = null

    /**
     * 重新搜索：丢弃既有结果，从最新页开始扫描。
     * [stopWhen] 在倒序扫描中命中即提前结束（不再继续拉更早的历史）。
     */
    fun search(
        predicate: (Message) -> Boolean,
        stopWhen: (Message) -> Boolean = { false },
    ) {
        this.predicate = predicate
        this.stopWhen = stopWhen
        scanBudget = INITIAL_SCAN_BUDGET
        requestNonce += 1
        scanJob?.cancel()
        _state.value = ChatHistoryScanState(scanning = true)
        val nonce = requestNonce
        scanJob = scope.launch { scanLoop(nonce, reset = true) }
    }

/** 在既有结果上继续向后扫描；扫描中、已到底或失败时是 no-op（失败走 [retry]）。 */
    fun loadMore() {
        val current = _state.value
        if (current.scanning || current.exhausted || current.failed || !current.budgetReached) return
        scanBudget += LOAD_MORE_SCAN_STEP
        requestNonce += 1
        val nonce = requestNonce
        scanJob?.cancel()
        scanJob = scope.launch { scanLoop(nonce, reset = false) }
    }

    fun retry() = search(predicate, stopWhen)

    /** 组合载体（页面/窗口）销毁时调用，停止在途扫描。 */
    fun dispose() {
        requestNonce += 1
        scanJob?.cancel()
        scanJob = null
    }

    private suspend fun scanLoop(nonce: Long, reset: Boolean) {
        var results = if (reset) emptyList() else _state.value.results
        var scanned = if (reset) 0L else _state.value.scannedCount
        var fromSeq = if (reset) 0L else nextFromSeq
        _state.value = ChatHistoryScanState(
            results = results,
            scanning = true,
            scannedCount = scanned,
        )
        try {
            var exhausted = false
            while (results.size < scanBudget && scanned < scanBudget && !exhausted) {
                if (nonce != requestNonce) return
                val page = scanPage(fromSeq, PAGE_SIZE)
                if (nonce != requestNonce) return
                if (page.isEmpty()) {
                    exhausted = true
                    break
                }
                scanned += page.size
                results = results + page.filter(predicate)
                val oldest = page.minOf { it.serverSeq }
                nextFromSeq = oldest - 1
                if (oldest <= 1L || page.any(stopWhen)) {
                    // 倒序扫描遇到首条消息/范围外的更早消息：后续必然更早，判定到界。
                    exhausted = true
                    break
                }
                fromSeq = nextFromSeq
                _state.value = ChatHistoryScanState(
                    results = results,
                    scanning = true,
                    scannedCount = scanned,
                )
            }
            if (nonce != requestNonce) return
            _state.value = ChatHistoryScanState(
                results = results,
                scanning = false,
                exhausted = exhausted,
                budgetReached = !exhausted,
                scannedCount = scanned,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            if (nonce != requestNonce) return
            _state.value = ChatHistoryScanState(
                results = results,
                scanning = false,
                failed = true,
                scannedCount = scanned,
            )
        }
    }
}
