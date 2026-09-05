package com.virjar.tk.server.infra.search

import com.virjar.tk.server.domain.telemetry.TelemetryHighlightSpan
import com.virjar.tk.server.domain.telemetry.TelemetryTextHighlight

internal const val HIGHLIGHT_START_SENTINEL = '\u0001'
internal const val HIGHLIGHT_END_SENTINEL = '\u0002'

/** 在信任边界处把 Lucene 的私有控制标记转换为经过校验的纯文本偏移。 */
internal fun parseTelemetryHighlightFragment(
    fragment: String,
    maxCharacters: Int,
): TelemetryTextHighlight? {
    require(maxCharacters > 0) { "telemetry highlight limit must be positive" }
    val plain = StringBuilder(fragment.length)
    val spans = mutableListOf<TelemetryHighlightSpan>()
    var openSpan = -1
    fragment.forEach { character ->
        when (character) {
            HIGHLIGHT_START_SENTINEL -> {
                if (openSpan >= 0) return null
                openSpan = plain.length
            }
            HIGHLIGHT_END_SENTINEL -> {
                if (openSpan < 0) return null
                if (plain.length > openSpan) spans += TelemetryHighlightSpan(openSpan, plain.length)
                openSpan = -1
            }
            else -> plain.append(character)
        }
    }
    if (openSpan >= 0) return null

    val limit = plain.safeUtf16Limit(maxCharacters)
    val clippedSpans = spans.mapNotNull { span ->
        val end = minOf(span.end, limit)
        span.takeIf { it.start < end }?.let { TelemetryHighlightSpan(it.start, end) }
    }
    return TelemetryTextHighlight(plain.substring(0, limit), clippedSpans)
}

private fun CharSequence.safeUtf16Limit(maxCharacters: Int): Int {
    var limit = minOf(length, maxCharacters)
    if (limit in 1 until length &&
        Character.isHighSurrogate(this[limit - 1]) &&
        Character.isLowSurrogate(this[limit])
    ) {
        limit--
    }
    return limit
}

internal fun safeTelemetryHighlightExcerpt(text: String, maxCharacters: Int): TelemetryTextHighlight {
    val plain = text
        .replace(HIGHLIGHT_START_SENTINEL, '\uFFFD')
        .replace(HIGHLIGHT_END_SENTINEL, '\uFFFD')
    return TelemetryTextHighlight(plain.substring(0, plain.safeUtf16Limit(maxCharacters)), emptyList())
}
