package com.virjar.tk.shared.client

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.DirectoryIteratorException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertSame

class NioFileSnapshotIdentityTest {
    @Test
    fun `providers without file keys use stable bounded metadata`() {
        val before = attributes(fileKey = null, size = 7L, modified = 11L)

        assertTrue(sameNioFileSnapshotIdentity(before, attributes(null, 7L, 11L)))
        assertFalse(sameNioFileSnapshotIdentity(before, attributes(null, 8L, 11L)))
        assertFalse(sameNioFileSnapshotIdentity(before, attributes(null, 7L, 12L)))
        assertFalse(sameNioFileSnapshotIdentity(before, attributes(null, 7L, 11L, created = 4L)))
    }

    @Test
    fun `a present file key is authoritative and cannot match a missing key`() {
        assertTrue(
            sameNioFileSnapshotIdentity(
                attributes(fileKey = "same", size = 1L, modified = 2L),
                attributes(fileKey = "same", size = 9L, modified = 8L),
            ),
        )
        assertFalse(
            sameNioFileSnapshotIdentity(
                attributes(fileKey = "same", size = 1L, modified = 2L),
                attributes(fileKey = null, size = 1L, modified = 2L),
            ),
        )
        assertFalse(
            sameNioFileSnapshotIdentity(
                attributes(fileKey = "before", size = 1L, modified = 2L),
                attributes(fileKey = "after", size = 1L, modified = 2L),
            ),
        )
    }

    @Test
    fun `iterator IO failure is a best effort filesystem boundary`() {
        assertTrue(DirectoryIteratorException(IOException("iterator"))
            .isTelemetryFilesystemBoundaryFailure())
    }

    @Test
    fun `close aggregation promotes fatal failure and still closes every resource`() {
        val ordinary = IOException("ordinary-close")
        val fatal = AssertionError("fatal-close")
        val closed = mutableListOf<Int>()

        val failure = closeAllResourcesPreservingFatalFailure(
            { closed += 1; throw ordinary },
            { closed += 2; throw fatal },
            { closed += 3 },
        )

        assertSame(fatal, failure)
        assertTrue(ordinary in fatal.suppressed)
        assertEquals(listOf(1, 2, 3), closed)
    }

    @Test
    fun `resource use promotes a fatal close over an ordinary action failure`() {
        val ordinary = IOException("ordinary-action")
        val fatal = AssertionError("fatal-close")
        val resource = AutoCloseable { throw fatal }

        val caught = assertFailsWith<AssertionError> {
            useResourcePreservingFatalFailure(resource) { throw ordinary }
        }

        assertSame(fatal, caught)
        assertTrue(ordinary in fatal.suppressed)
    }

    @Test
    fun `resource use preserves cancellation over an ordinary close failure`() {
        val cancelled = CancellationException("cancelled-action")
        val ordinary = IOException("ordinary-close")
        val resource = AutoCloseable { throw ordinary }

        val caught = assertFailsWith<CancellationException> {
            useResourcePreservingFatalFailure(resource) { throw cancelled }
        }

        assertSame(cancelled, caught)
        assertTrue(ordinary in cancelled.suppressed)
    }

    @Test
    fun `resource use propagates close failure after a successful action`() {
        val closeFailure = IOException("ordinary-close")
        val resource = AutoCloseable { throw closeFailure }

        val caught = assertFailsWith<IOException> {
            useResourcePreservingFatalFailure(resource) { "completed" }
        }

        assertSame(closeFailure, caught)
    }

    @Test
    fun `resource use keeps an ordinary action failure primary over ordinary close failure`() {
        val actionFailure = IOException("ordinary-action")
        val closeFailure = IllegalStateException("ordinary-close")
        val resource = AutoCloseable { throw closeFailure }

        val caught = assertFailsWith<IOException> {
            useResourcePreservingFatalFailure(resource) { throw actionFailure }
        }

        assertSame(actionFailure, caught)
        assertTrue(closeFailure in actionFailure.suppressed)
    }

    private fun attributes(
        fileKey: Any?,
        size: Long,
        modified: Long,
        created: Long = 3L,
    ): BasicFileAttributes = object : BasicFileAttributes {
        override fun lastModifiedTime(): FileTime = FileTime.fromMillis(modified)
        override fun lastAccessTime(): FileTime = FileTime.fromMillis(modified)
        override fun creationTime(): FileTime = FileTime.fromMillis(created)
        override fun isRegularFile(): Boolean = true
        override fun isDirectory(): Boolean = false
        override fun isSymbolicLink(): Boolean = false
        override fun isOther(): Boolean = false
        override fun size(): Long = size
        override fun fileKey(): Any? = fileKey
    }
}
