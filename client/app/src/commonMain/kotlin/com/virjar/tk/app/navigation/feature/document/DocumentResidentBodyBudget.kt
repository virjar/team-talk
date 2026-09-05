package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.protocol.model.DocumentPolicy

/**
 * 常规驻留正文预算。它刻意小于持久恢复包络：
 * 普通导航必须在这里收敛，而一份先前持久化的未保存草稿绝不能仅仅因为
 * 当前客户端收紧了其内存策略就被删除。
 */
internal const val MAX_RESIDENT_DOCUMENT_BODY_CHARS =
    8L * DocumentPolicy.MAX_MARKDOWN_LENGTH

/** 已挂载的编辑器仍然可以全尺寸替换它的服务器基线和本地草稿。 */
internal const val MAX_ACTIVE_DOCUMENT_BODY_CHARS =
    2L * DocumentPolicy.MAX_MARKDOWN_LENGTH

/**
 * 绝对恢复上限，是推导出来的而不是猜测的。
 *
 * 每一个合法的恢复字符串都来自聚合 UTF-8 JSON 大小受
 * [MAX_TOTAL_DOCUMENT_DRAFT_RECORD_BYTES] 约束的记录。JSON 每个 UTF-16 码元至少使用一个字节。
 * 因此加上一份完整的活动编辑器预留，就能覆盖最大的旧快照，
 * 而不让恢复变成常规预算的一个无界例外。
 */
internal const val MAX_RECOVERED_DOCUMENT_BODY_CHARS =
    MAX_TOTAL_DOCUMENT_DRAFT_RECORD_BYTES + MAX_ACTIVE_DOCUMENT_BODY_CHARS

internal const val MAX_RECOVERED_DOCUMENT_TABS = MAX_DOCUMENT_DRAFT_RECORDS

internal sealed interface DocumentResidentBodyPlan {
    data class Admitted(
        val tabs: List<DocumentTabState>,
        val evictedInstanceIds: List<Long>,
        /** 实际驻留字符串，加上为活动编辑器预留的未使用增长空间。 */
        val reservedBodyChars: Long,
        val bodyLimitChars: Long,
        val recoveryDebt: Boolean,
    ) : DocumentResidentBodyPlan

    data class Rejected(
        val requiredProtectedBodyChars: Long,
        val bodyLimitChars: Long,
        val protectedTabCount: Int,
        val tabLimit: Int,
    ) : DocumentResidentBodyPlan
}

/**
 * 规划一次原子的驻留 tab 发布。
 *
 * dirty 和 creating 的 tab 拥有用户数据，绝不可能成为驱逐候选。被请求的活动 tab 也被钉住，
 * 并获得足够的余量来容纳一份最大尺寸的已保存基线和一份最大尺寸的草稿。干净的非活动 tab
 * 是缓存条目：最旧的条目先退役，直到正文和 tab 上限都成立。这种预留让编辑器回调
 * 能够发布它们完整的帧，而不会遭遇迟到的、状态分歧的容量拒绝。
 *
 * [allowRecoveryDebt] 预留给调用方已经恢复或发布的身份集合；新 tab 路径必须保持它为 false。
 * 它可以超过常规运行目标，但绝不能超过独立有界的、由持久化推导的恢复上限。
 * 恢复债务只保留受保护的 tab，因此它会随着草稿被保存或显式关闭而单调收缩。
 */
internal fun planDocumentResidentBodies(
    tabs: List<DocumentTabState>,
    activeInstanceId: Long?,
    allowRecoveryDebt: Boolean,
    /** 必须在这次发布中存续下来、且不会变得可编辑的过渡性 UI owner。 */
    pinnedInstanceIds: Set<Long> = emptySet(),
    bodyBudgetChars: Long = MAX_RESIDENT_DOCUMENT_BODY_CHARS,
    activeBodyReservationChars: Long = MAX_ACTIVE_DOCUMENT_BODY_CHARS,
    maxOpenTabs: Int = MAX_OPEN_DOCUMENT_TABS,
    recoveryBodyLimitChars: Long = MAX_RECOVERED_DOCUMENT_BODY_CHARS,
    maxRecoveredTabs: Int = MAX_RECOVERED_DOCUMENT_TABS,
): DocumentResidentBodyPlan {
    require(bodyBudgetChars > 0L) { "Document resident body budget must be positive" }
    require(activeBodyReservationChars > 0L) {
        "Document active body reservation must be positive"
    }
    require(maxOpenTabs > 0) { "Document resident tab limit must be positive" }
    require(recoveryBodyLimitChars >= bodyBudgetChars) {
        "Document recovery body limit must cover the operating budget"
    }
    require(maxRecoveredTabs >= maxOpenTabs) {
        "Document recovery tab limit must cover the operating limit"
    }

    val protected = tabs.filter { tab ->
        tab.dirty || tab.creating || tab.instanceId == activeInstanceId ||
            tab.instanceId in pinnedInstanceIds
    }
    val protectedBodyChars = reservedDocumentBodyChars(
        tabs = protected,
        activeInstanceId = activeInstanceId,
        activeBodyReservationChars = activeBodyReservationChars,
    )
    val needsRecovery = protectedBodyChars > bodyBudgetChars || protected.size > maxOpenTabs
    val recoveryAdmitted = allowRecoveryDebt &&
        protectedBodyChars <= recoveryBodyLimitChars && protected.size <= maxRecoveredTabs

    if (needsRecovery && !recoveryAdmitted) {
        return DocumentResidentBodyPlan.Rejected(
            requiredProtectedBodyChars = protectedBodyChars,
            bodyLimitChars = if (allowRecoveryDebt) recoveryBodyLimitChars else bodyBudgetChars,
            protectedTabCount = protected.size,
            tabLimit = if (allowRecoveryDebt) maxRecoveredTabs else maxOpenTabs,
        )
    }

    if (needsRecovery) {
        val protectedIds = protected.mapTo(mutableSetOf(), DocumentTabState::instanceId)
        return DocumentResidentBodyPlan.Admitted(
            tabs = tabs.filter { it.instanceId in protectedIds },
            evictedInstanceIds = tabs.filterNot { it.instanceId in protectedIds }
                .map(DocumentTabState::instanceId),
            reservedBodyChars = protectedBodyChars,
            bodyLimitChars = recoveryBodyLimitChars,
            recoveryDebt = true,
        )
    }

    val retained = tabs.toMutableList()
    val evictedInstanceIds = mutableListOf<Long>()
    while (
        retained.size > maxOpenTabs ||
        reservedDocumentBodyChars(
            tabs = retained,
            activeInstanceId = activeInstanceId,
            activeBodyReservationChars = activeBodyReservationChars,
        ) > bodyBudgetChars
    ) {
        val evictionIndex = retained.indexOfFirst { tab ->
            !tab.dirty && !tab.creating && tab.instanceId != activeInstanceId &&
                tab.instanceId !in pinnedInstanceIds
        }
        if (evictionIndex < 0) {
            return DocumentResidentBodyPlan.Rejected(
                requiredProtectedBodyChars = protectedBodyChars,
                bodyLimitChars = bodyBudgetChars,
                protectedTabCount = protected.size,
                tabLimit = maxOpenTabs,
            )
        }
        evictedInstanceIds += retained.removeAt(evictionIndex).instanceId
    }
    return DocumentResidentBodyPlan.Admitted(
        tabs = retained,
        evictedInstanceIds = evictedInstanceIds,
        reservedBodyChars = reservedDocumentBodyChars(
            tabs = retained,
            activeInstanceId = activeInstanceId,
            activeBodyReservationChars = activeBodyReservationChars,
        ),
        bodyLimitChars = bodyBudgetChars,
        recoveryDebt = false,
    )
}

/**
 * 在回调进行中发布一个编辑器拥有的帧，而不改变驻留身份集合。
 *
 * 特别是，`selectTab(B)` 在转移活动所有权之前会同步捕获编辑器 A。
 * 如果那次捕获被允许驱逐干净的 B，随后的本地优先激活将解析不到任何东西，
 * 一次离线点击就会看起来什么也没做。钉住完整的回调前身份集合，
 * 使捕获和随后的激活成为一个两阶段原子变更：捕获可以临时使用恢复包络，
 * 然后激活以钉住的 B 作为新的活动 owner，执行常规的干净缓存驱逐。
 */
internal fun planDocumentEditorFramePublication(
    tabs: List<DocumentTabState>,
    activeInstanceId: Long?,
    bodyBudgetChars: Long = MAX_RESIDENT_DOCUMENT_BODY_CHARS,
    activeBodyReservationChars: Long = MAX_ACTIVE_DOCUMENT_BODY_CHARS,
    maxOpenTabs: Int = MAX_OPEN_DOCUMENT_TABS,
    recoveryBodyLimitChars: Long = MAX_RECOVERED_DOCUMENT_BODY_CHARS,
    maxRecoveredTabs: Int = MAX_RECOVERED_DOCUMENT_TABS,
): DocumentResidentBodyPlan {
    val callbackPins = tabs.mapTo(mutableSetOf(), DocumentTabState::instanceId)
    val operating = planDocumentResidentBodies(
        tabs = tabs,
        activeInstanceId = activeInstanceId,
        allowRecoveryDebt = false,
        pinnedInstanceIds = callbackPins,
        bodyBudgetChars = bodyBudgetChars,
        activeBodyReservationChars = activeBodyReservationChars,
        maxOpenTabs = maxOpenTabs,
        recoveryBodyLimitChars = recoveryBodyLimitChars,
        maxRecoveredTabs = maxRecoveredTabs,
    )
    return if (operating is DocumentResidentBodyPlan.Admitted) {
        operating
    } else {
        planDocumentResidentBodies(
            tabs = tabs,
            activeInstanceId = activeInstanceId,
            allowRecoveryDebt = true,
            pinnedInstanceIds = callbackPins,
            bodyBudgetChars = bodyBudgetChars,
            activeBodyReservationChars = activeBodyReservationChars,
            maxOpenTabs = maxOpenTabs,
            recoveryBodyLimitChars = recoveryBodyLimitChars,
            maxRecoveredTabs = maxRecoveredTabs,
        )
    }
}

/** 保守地统计支撑内容，同时识别规范的干净共享 String。 */
internal fun residentDocumentBodyChars(tab: DocumentTabState): Long =
    tab.savedMarkdown.length.toLong() +
        if (tab.savedMarkdown === tab.draftMarkdown) 0L else tab.draftMarkdown.length.toLong()

internal fun reservedDocumentBodyChars(
    tabs: List<DocumentTabState>,
    activeInstanceId: Long?,
    activeBodyReservationChars: Long = MAX_ACTIVE_DOCUMENT_BODY_CHARS,
): Long {
    var total = 0L
    tabs.forEach { tab ->
        val actual = residentDocumentBodyChars(tab)
        val reserved = if (tab.instanceId == activeInstanceId) {
            maxOf(actual, activeBodyReservationChars)
        } else {
            actual
        }
        total = saturatedAdd(total, reserved)
    }
    return total
}

internal fun DocumentResidentBodyPlan.Rejected.userMessage(): String =
    "本机已保留的文档正文达到安全容量，请先保存或关闭一篇未保存文档后再试"

internal fun DocumentResidentBodyPlan.Rejected.asFailure(): IllegalStateException =
    IllegalStateException(
        "Document resident body capacity reached: protectedChars=$requiredProtectedBodyChars, " +
            "bodyLimit=$bodyLimitChars, protectedTabs=$protectedTabCount, tabLimit=$tabLimit",
    )

private fun saturatedAdd(left: Long, right: Long): Long =
    if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
