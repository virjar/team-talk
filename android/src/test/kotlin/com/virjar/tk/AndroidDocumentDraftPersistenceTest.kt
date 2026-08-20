package com.virjar.tk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AndroidDocumentDraftPersistenceTest {

    @Test
    fun `uid is converted to a deterministic path safe hash`() {
        val suspiciousUid = "../../other-user/账号"
        val first = AndroidDocumentDraftPersistence.draftFileName(suspiciousUid)
        val second = AndroidDocumentDraftPersistence.draftFileName(suspiciousUid)

        assertEquals(first, second)
        assertTrue(first.matches(Regex("[0-9a-f]{64}\\.json")))
        assertTrue(".." !in first)
        assertTrue("/" !in first)
        assertNotEquals(first, AndroidDocumentDraftPersistence.draftFileName("another-user"))
    }

    @Test
    fun `payload budget covers server maximum baseline plus draft`() {
        // Server documents allow 1M chars. Worst-case UTF-8 is four bytes per char and the
        // recovery payload contains both the saved baseline and unsaved draft.
        val worstCaseBaselineAndDraft = 2 * 1_000_000 * 4
        assertTrue(AndroidDocumentDraftPersistence.MAX_PAYLOAD_BYTES > worstCaseBaselineAndDraft)
    }
}
