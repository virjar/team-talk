package com.virjar.tk.server.infra.search

import com.virjar.tk.server.domain.telemetry.TelemetrySearchHit
import com.virjar.tk.server.domain.telemetry.TelemetrySearchPage
import com.virjar.tk.server.domain.telemetry.TelemetrySearchQuery
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Sort
import org.apache.lucene.search.SortField
import org.apache.lucene.search.highlight.Highlighter
import org.apache.lucene.search.highlight.QueryScorer
import org.apache.lucene.search.highlight.SimpleHTMLFormatter

internal const val MAX_TELEMETRY_COLLECTION_WINDOW = 10_000L
private const val MAX_HIGHLIGHT_CHARS = 200

/**
 * 把一个已经租借的 Lucene 世代映射到有界的管理员搜索投影。
 * 生命周期与终结失败所有权仍在 [ClientTelemetrySearchIndex]。
 */
internal fun searchTelemetryEvents(
    searcher: IndexSearcher,
    analyzer: Analyzer,
    query: TelemetrySearchQuery,
    offset: Int,
    limit: Int,
): TelemetrySearchPage {
    val luceneQuery = buildTelemetryQuery(query, analyzer)
    val total = searcher.count(luceneQuery).toLong()
    val topDocs = searcher.search(
        luceneQuery,
        Math.addExact(offset, limit),
        Sort(
            SortField(FIELD_RECEIVED_AT, SortField.Type.LONG, true),
            SortField(FIELD_RECORD_ID, SortField.Type.LONG, true),
        ),
    )
    val keyword = query.keyword?.trim().orEmpty()
    val highlighter = if (keyword.isEmpty()) null else Highlighter(
        SimpleHTMLFormatter(
            HIGHLIGHT_START_SENTINEL.toString(),
            HIGHLIGHT_END_SENTINEL.toString(),
        ),
        QueryScorer(
            QueryParser(TELEMETRY_FIELD_TEXT, analyzer).parse(QueryParser.escape(keyword)),
        ),
    )
    val storedFields = searcher.storedFields()
    val hits = topDocs.scoreDocs.drop(offset).map { scoreDoc ->
        val document = storedFields.document(scoreDoc.doc)
        val text = document.get(TELEMETRY_FIELD_TEXT).orEmpty()
        val highlight = highlighter?.let {
            val fallback = safeTelemetryHighlightExcerpt(text, MAX_HIGHLIGHT_CHARS)
            if (text.indexOf(HIGHLIGHT_START_SENTINEL) >= 0 ||
                text.indexOf(HIGHLIGHT_END_SENTINEL) >= 0
            ) {
                fallback
            } else {
                runCatching { it.getBestFragment(analyzer, TELEMETRY_FIELD_TEXT, text) }
                    .getOrNull()
                    ?.let { fragment ->
                        parseTelemetryHighlightFragment(fragment, MAX_HIGHLIGHT_CHARS)
                    }
                    ?: fallback
            }
        }
        TelemetrySearchHit(document.toStoredTelemetryEvent(), highlight)
    }
    return TelemetrySearchPage(total, hits)
}
