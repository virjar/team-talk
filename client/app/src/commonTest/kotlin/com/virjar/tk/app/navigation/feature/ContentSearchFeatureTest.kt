package com.virjar.tk.app.navigation.feature

import com.virjar.tk.protocol.model.ContentSearchHit
import com.virjar.tk.protocol.model.ContentSearchPage
import com.virjar.tk.protocol.model.ContentSearchRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ContentSearchFeatureTest {
    @Test
    fun retiredQueryCannotOverwriteNewResultsEvenIfTransportIgnoresCancellation() = runTest {
        val late = CompletableDeferred<ContentSearchPage>()
        val feature = ContentSearchFeature(this, search = { request ->
            if (request.keyword == "old") withContext(NonCancellable) { late.await() }
            else ContentSearchPage(listOf(document("new")), null)
        }, open = {})
        feature.activate(request("old"), DOCUMENT, 0)
        advanceUntilIdle()
        feature.activate(request("new"), DOCUMENT, 0)
        advanceUntilIdle()
        late.complete(ContentSearchPage(listOf(document("old")), "obsolete"))
        advanceUntilIdle()
        assertEquals(listOf("new"), feature.section(DOCUMENT).items.map { it.title })
        assertNull(feature.section(DOCUMENT).nextCursor)
        feature.close()
    }

    @Test
    fun sourceChangeImmediatelyRetiresRowsAndContinuationWithoutDisturbingOtherKinds() = runTest {
        val refreshed = CompletableDeferred<ContentSearchPage>()
        val calls = mutableListOf<ContentSearchRequest>()
        val feature = ContentSearchFeature(this, search = { request ->
            calls += request
            if (request.kind == FILE) ContentSearchPage(listOf(file()), "file-more")
            else if (calls.count { it.kind == DOCUMENT } == 1) ContentSearchPage(listOf(document("before")), "doc-more")
            else refreshed.await()
        }, open = {})
        feature.activate(request(""), DOCUMENT, 0)
        feature.activate(ContentSearchRequest(FILE, ""), FILE, 0)
        advanceUntilIdle()
        feature.activate(request(""), DOCUMENT, 1)
        assertTrue(feature.section(DOCUMENT).items.isEmpty())
        assertNull(feature.section(DOCUMENT).nextCursor)
        assertTrue(feature.section(DOCUMENT).loading)
        assertEquals("file-more", feature.section(FILE).nextCursor)
        advanceUntilIdle()
        refreshed.complete(ContentSearchPage(listOf(document("after")), null))
        advanceUntilIdle()
        assertEquals(listOf(null, null), calls.filter { it.kind == DOCUMENT }.map { it.cursor })
        assertEquals(listOf("after"), feature.section(DOCUMENT).items.map { it.title })
        feature.close()
    }

    @Test
    fun failedContinuationKeepsRowsAndRetriesSameCursor() = runTest {
        var continuationAttempts = 0
        val cursors = mutableListOf<String?>()
        val feature = ContentSearchFeature(this, search = { request ->
            cursors += request.cursor
            if (request.cursor == null) ContentSearchPage(listOf(document("one")), "next")
            else if (continuationAttempts++ == 0) error("connection lost")
            else ContentSearchPage(listOf(document("one"), document("two")), null)
        }, open = {})
        feature.activate(request(""), DOCUMENT, 0)
        advanceUntilIdle()
        feature.loadMore(DOCUMENT)
        advanceUntilIdle()
        assertEquals(listOf("one"), feature.section(DOCUMENT).items.map { it.title })
        assertNotNull(feature.section(DOCUMENT).error)
        assertFalse(feature.section(DOCUMENT).loading)
        feature.retry(DOCUMENT)
        advanceUntilIdle()
        assertEquals(listOf(null, "next", "next"), cursors)
        assertEquals(listOf("one", "two"), feature.section(DOCUMENT).items.map { it.title })
        assertNull(feature.section(DOCUMENT).error)
        feature.close()
    }

    @Test
    fun sameAttachmentInDifferentMessagesRemainsTwoResults() = runTest {
        val first = ContentSearchHit(ATTACHMENT, "chat", "a".repeat(64), "image.png", "", "chat", 1, 1,
            "image/png", 10, 1)
        val second = first.copy(serverSeq = 2)
        val feature = ContentSearchFeature(this, search = { request ->
            if (request.cursor == null) ContentSearchPage(listOf(first), "next")
            else ContentSearchPage(listOf(first, second), null)
        }, open = {})
        feature.activate(ContentSearchRequest(ATTACHMENT, ""), ATTACHMENT, 0)
        advanceUntilIdle()
        feature.loadMore(ATTACHMENT)
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), feature.section(ATTACHMENT).items.map { it.serverSeq })
        feature.close()
    }

    @Test
    fun closeRejectsPendingResultsAndOpenFailureIsVisible() = runTest {
        val response = CompletableDeferred<ContentSearchPage>()
        val feature = ContentSearchFeature(this, search = {
            withContext(NonCancellable) { response.await() }
        }, open = { error("no longer authorized") })
        feature.open(document("deleted"))
        advanceUntilIdle()
        assertNotNull(feature.openError)
        assertNull(feature.openingKey)
        feature.activate(request(""), DOCUMENT, 0)
        advanceUntilIdle()
        feature.close()
        response.complete(ContentSearchPage(listOf(document("late")), null))
        advanceUntilIdle()
        assertTrue(feature.section(DOCUMENT).items.isEmpty())
    }

    private fun request(keyword: String) = ContentSearchRequest(DOCUMENT, keyword)
    private fun document(title: String) = ContentSearchHit(DOCUMENT, "space", title, title, "", "Space", 1, 1)
    private fun file() = ContentSearchHit(FILE, "chat", "entry", "file.txt", "", "Group", 1, 1, "text/plain", 10)

    private companion object {
        const val DOCUMENT = ContentSearchRequest.KIND_DOCUMENT
        const val FILE = ContentSearchRequest.KIND_GROUP_FILE
        const val ATTACHMENT = ContentSearchRequest.KIND_CHAT_ATTACHMENT
    }
}
