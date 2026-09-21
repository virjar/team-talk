package com.virjar.tk.app.navigation.feature.document

import kotlin.test.*

class DocumentDraftPendingWritesTest {
    private fun owner(uid: String) = DocumentDraftOwnerKey("a".repeat(64), "12345678-1234-4234-8234-123456789abc", uid)
    private fun payload() = DocumentDraftPayload("snapshot", emptyList(), emptySet())

    @Test
    fun anEncodingWriteKeepsItsOwnerBoundAndCannotOutliveDeletionOrReplaceNewerWork() {
        val buffer = DocumentDraftPendingWrites(1)
        val alice = owner("alice")
        val bob = owner("bob")
        buffer.put(alice, 1, ::payload)
        val encoding = assertNotNull(buffer.take())
        assertFalse(buffer.canAccept(bob), "Encoding work still owns the bounded slot")
        buffer.put(alice, 2, ::payload)
        buffer.complete(encoding)
        assertFalse(buffer.isCurrent(encoding))
        val replacement = assertNotNull(buffer.take())
        assertTrue(buffer.isCurrent(replacement))
        buffer.invalidate(alice, 3)
        assertFalse(buffer.isCurrent(replacement), "Late publication must not resurrect a deleted owner")
        buffer.complete(replacement)
        buffer.forgetIdleOwnersExcept()
        assertTrue(buffer.canAccept(bob))
    }
}
