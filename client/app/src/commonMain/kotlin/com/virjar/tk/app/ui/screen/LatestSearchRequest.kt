package com.virjar.tk.app.ui.screen

import kotlinx.coroutines.CancellationException

/**
 * 搜索 UI 状态的同步 owner 闸门。
 *
 * token 在首次挂起之前捕获已提交的查询及其代际。查询编辑与销毁会使 owner 失效，
 * 因此即使底层 RPC 直到返回后才理会取消，迟到的响应也无法发布进更新的屏幕状态。
 */
internal class LatestSearchRequestGate {
    internal data class Token(val generation: Long, val query: String)

    private var generation = 0L
    private var current: Token? = null

    fun begin(query: String): Token {
        // 在递增之前先退役前一个能力，使耗尽也以失败关闭（fail closed）。
        current = null
        return Token(nextGeneration(), query).also { current = it }
    }

    fun isCurrent(token: Token): Boolean = current == token

    fun invalidate() {
        current = null
        nextGeneration()
    }

    private fun nextGeneration(): Long {
        check(generation < Long.MAX_VALUE) { "Search request generation exhausted" }
        generation += 1L
        return generation
    }
}

/** 只执行远程工作；每个 UI 回调仍归最新的请求 token 所有。 */
internal suspend fun <T> executeLatestSearch(
    gate: LatestSearchRequestGate,
    token: LatestSearchRequestGate.Token,
    search: suspend (String) -> List<T>,
    onSuccess: (List<T>) -> Unit,
    onFailure: (Exception) -> Unit,
    onFinished: () -> Unit,
) {
    val result = try {
        search(token.query)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        if (gate.isCurrent(token)) {
            onFailure(failure)
            onFinished()
        }
        return
    }
    if (gate.isCurrent(token)) {
        onSuccess(result)
        onFinished()
    }
}
