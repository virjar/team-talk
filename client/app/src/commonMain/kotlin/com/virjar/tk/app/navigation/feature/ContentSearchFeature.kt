package com.virjar.tk.app.navigation.feature

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.virjar.tk.protocol.model.ContentSearchHit
import com.virjar.tk.protocol.model.ContentSearchPage
import com.virjar.tk.protocol.model.ContentSearchRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal data class ContentSearchSection(
    val request: ContentSearchRequest? = null,
    val sourceGeneration: Long = 0,
    val items: List<ContentSearchHit> = emptyList(),
    val nextCursor: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val atCapacity: Boolean get() = items.size >= ContentSearchFeature.MAX_RESIDENT_RESULTS
}

/** 搜索页拥有三个独立分页窗口，查询或来源世代变化即退役对应窗口。 */
internal class ContentSearchFeature(
    private val scope: CoroutineScope,
    private val search: suspend (ContentSearchRequest) -> ContentSearchPage,
    private val open: suspend (ContentSearchHit) -> Unit,
) {
    private val sections = mutableStateMapOf<Int, ContentSearchSection>()
    private val jobs = mutableMapOf<Int, Job>()
    private val owners = mutableMapOf<Int, Any>()
    private var openJob: Job? = null
    var openingKey by mutableStateOf<String?>(null)
        private set
    var openError by mutableStateOf<String?>(null)
        private set

    fun section(kind: Int): ContentSearchSection = sections[kind] ?: ContentSearchSection()

    fun activate(request: ContentSearchRequest?, kind: Int, sourceGeneration: Long) {
        val previous = sections[kind]
        if (request != null && previous?.request == request && previous.sourceGeneration == sourceGeneration) return
        jobs.remove(kind)?.cancel()
        owners.remove(kind)
        if (request == null) {
            sections.remove(kind)
            return
        }
        require(request.kind == kind && request.cursor == null)
        sections[kind] = ContentSearchSection(request, sourceGeneration)
        fetch(kind, append = false, debounce = true)
    }

    fun retry(kind: Int) = fetch(kind, append = section(kind).items.isNotEmpty())
    fun refresh(kind: Int) {
        val previous = sections.remove(kind) ?: return
        openError = null
        activate(previous.request, kind, previous.sourceGeneration)
    }

    fun loadMore(kind: Int) {
        val state = section(kind)
        if (!state.loading && !state.atCapacity && state.nextCursor != null) fetch(kind, append = true)
    }

    private fun fetch(kind: Int, append: Boolean, debounce: Boolean = false) {
        val previous = sections[kind] ?: return
        val first = previous.request ?: return
        if (previous.loading || (append && (previous.nextCursor == null || previous.atCapacity))) return
        val request = first.copy(cursor = if (append) previous.nextCursor else null)
        val owner = Any()
        owners[kind] = owner
        sections[kind] = previous.copy(loading = true, error = null)
        jobs[kind] = scope.launch {
            try {
                if (debounce) delay(280)
                val page = search(request)
                currentCoroutineContext().ensureActive()
                if (owners[kind] !== owner) return@launch
                check(page.nextCursor == null || page.nextCursor != request.cursor) { "搜索游标未推进" }
                val items = ((if (append) previous.items else emptyList()) + page.items)
                    .distinctBy(::contentSearchHitKey).take(MAX_RESIDENT_RESULTS)
                sections[kind] = previous.copy(items = items, nextCursor = page.nextCursor, loading = false, error = null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (owners[kind] === owner) sections[kind] = previous.copy(
                    loading = false, error = "搜索未完成，请检查连接后重试",
                )
            } finally {
                if (owners[kind] === owner && section(kind).loading) {
                    sections[kind] = section(kind).copy(loading = false)
                }
            }
        }
    }

    fun open(hit: ContentSearchHit) {
        if (openingKey != null) return
        val key = contentSearchHitKey(hit)
        openingKey = key
        openError = null
        openJob = scope.launch {
            try {
                open.invoke(hit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                openError = "内容不可访问、已变化或连接不可用，请刷新后重试"
            } finally {
                openingKey = null
            }
        }
    }

    fun close() {
        owners.clear()
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        openJob?.cancel()
        sections.clear()
    }

    companion object { const val MAX_RESIDENT_RESULTS = 200 }
}

internal fun contentSearchHitKey(hit: ContentSearchHit): String =
    "${hit.kind}.${hit.scopeId}.${hit.serverSeq}.${hit.targetId}"
