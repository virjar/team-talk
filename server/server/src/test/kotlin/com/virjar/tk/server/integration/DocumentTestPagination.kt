package com.virjar.tk.server.integration

import com.virjar.tk.server.domain.document.DocumentService
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpacePage
import com.virjar.tk.protocol.model.DocumentSpacePageRequest

/** 仅测试使用的有界收集器，用于夹具有意跨多页的断言。 */
suspend fun DocumentService.listSpaces(actorUid: String): List<DocumentSpace> {
    val spaces = mutableListOf<DocumentSpace>()
    val seenIds = hashSetOf<String>()
    val seenCursors = hashSetOf<String>()
    var cursor: String? = null
    repeat(MAX_TEST_DOCUMENT_SPACE_PAGES) {
        val page = listSpaces(
            actorUid,
            DocumentSpacePageRequest(cursor, DocumentSpacePage.MAX_PAGE_SIZE),
        )
        page.items.forEach { space ->
            check(seenIds.add(space.spaceId)) { "Document test pagination repeated a space" }
            spaces += space
        }
        val next = page.nextCursor ?: return spaces
        check(next != cursor && seenCursors.add(next)) {
            "Document test pagination cursor did not advance"
        }
        cursor = next
    }
    error("Document test pagination exceeded its explicit fixture bound")
}

private const val MAX_TEST_DOCUMENT_SPACE_PAGES = 64
