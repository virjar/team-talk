package com.virjar.tk.server.infra.search

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

class SearchIndexLifecycleTest {

    @Test
    fun `successful stop is idempotent and permits a clean restart`() {
        val root = Files.createTempDirectory("tk-search-restart-lifecycle-").toFile()
        val index = SearchIndex(root)
        try {
            index.start()
            assertTrue(index.isRunning)
            index.stop()
            index.stop()
            assertFalse(index.isRunning)

            index.start()
            assertTrue(index.isRunning)
            index.stop()
        } finally {
            if (index.isRunning) index.stop()
            check(root.deleteRecursively() || !root.exists()) {
                "Failed to delete SearchIndex restart lifecycle root: $root"
            }
        }
    }

    @Test
    fun `ordinary startup failure with successful rollback remains retryable`() {
        val root = Files.createTempDirectory("tk-search-retryable-startup-").toFile()
        val startupFailure = SearchStartupFailure("transient startup checkpoint failed")
        var shouldFail = true
        val index = SearchIndex(
            root,
            SearchIndexLifecycleActions(
                afterAcquireAction = { step ->
                    if (step == SearchIndexLifecycleStep.WRITER_ACQUIRED && shouldFail) {
                        shouldFail = false
                        throw startupFailure
                    }
                },
            ),
        )

        try {
            val first = try {
                index.start()
                fail("the first injected startup should fail")
            } catch (failure: Throwable) {
                failure
            }
            assertSame(startupFailure, first)
            assertFalse(index.isRunning)

            index.start()
            assertTrue(index.isRunning, "a fully rolled-back startup failure must remain retryable")
            index.stop()
        } finally {
            if (index.isRunning) index.stop()
            check(root.deleteRecursively() || !root.exists()) {
                "Failed to delete retryable SearchIndex startup root: $root"
            }
        }
    }

    @Test
    fun `startup rollback drains acquired resources and preserves fatal cleanup failure`() {
        val root = Files.createTempDirectory("tk-search-startup-lifecycle-").toFile()
        val startupFailure = SearchStartupFailure("startup checkpoint failed")
        val cleanupFatalFailure = SearchLifecycleFatalFailure("writer close failed")
        val acquired = mutableListOf<SearchIndexLifecycleStep>()
        val cleaned = mutableListOf<SearchIndexLifecycleStep>()
        val index = SearchIndex(
            root,
            SearchIndexLifecycleActions(
                afterAcquireAction = { step ->
                    acquired += step
                    if (step == SearchIndexLifecycleStep.WRITER_ACQUIRED) throw startupFailure
                },
                cleanupAction = { step, action ->
                    action()
                    cleaned += step
                    if (step == SearchIndexLifecycleStep.CLOSE_WRITER) throw cleanupFatalFailure
                },
            ),
        )

        try {
            val observed = try {
                index.start()
                fail("injected startup failure should escape")
            } catch (failure: Throwable) {
                failure
            }

            assertSame(cleanupFatalFailure, observed)
            assertTrue(cleanupFatalFailure.suppressed.any { it === startupFailure })
            assertFalse(index.isRunning)
            assertEquals(
                listOf(
                    SearchIndexLifecycleStep.ANALYZER_ACQUIRED,
                    SearchIndexLifecycleStep.DIRECTORY_ACQUIRED,
                    SearchIndexLifecycleStep.WRITER_ACQUIRED,
                ),
                acquired,
            )
            assertEquals(
                listOf(
                    SearchIndexLifecycleStep.CLOSE_WRITER,
                    SearchIndexLifecycleStep.CLOSE_DIRECTORY,
                    SearchIndexLifecycleStep.CLOSE_ANALYZER,
                ),
                cleaned,
                "a fatal writer-close failure must not skip later cleanup",
            )

            val acquiredCount = acquired.size
            val cleanupCount = cleaned.size
            val repeatedStart = try {
                index.start()
                fail("cleanup-failed startup must remain terminal")
            } catch (failure: Throwable) {
                failure
            }
            assertSame(cleanupFatalFailure, repeatedStart)
            val repeatedStop = try {
                index.stop()
                fail("stop must replay a startup cleanup terminal failure")
            } catch (failure: Throwable) {
                failure
            }
            assertSame(cleanupFatalFailure, repeatedStop)
            assertEquals(acquiredCount, acquired.size, "terminal replay must not acquire resources again")
            assertEquals(cleanupCount, cleaned.size, "terminal replay must not close resources twice")

            // 失败的 writer 在注入失败之前确实已关闭，因此目录锁可复用。
            SearchIndex(root).also { recovered ->
                recovered.start()
                recovered.stop()
            }
        } finally {
            check(root.deleteRecursively() || !root.exists()) {
                "Failed to delete SearchIndex startup lifecycle root: $root"
            }
        }
    }

    @Test
    fun `failed stop drains all resources and replays the same terminal failure`() {
        val root = Files.createTempDirectory("tk-search-stop-lifecycle-").toFile()
        val commitFailure = SearchCommitFailure("commit diagnostics failed")
        val closeFatalFailure = SearchLifecycleFatalFailure("searcher close failed")
        val cleanupSteps = mutableListOf<SearchIndexLifecycleStep>()
        val index = SearchIndex(
            root,
            SearchIndexLifecycleActions(
                cleanupAction = { step, action ->
                    action()
                    cleanupSteps += step
                    when (step) {
                        SearchIndexLifecycleStep.WRITER_COMMIT -> throw commitFailure
                        SearchIndexLifecycleStep.CLOSE_SEARCHERS -> throw closeFatalFailure
                        else -> Unit
                    }
                },
            ),
        )

        try {
            index.start()
            val first = try {
                index.stop()
                fail("injected stop failures should escape")
            } catch (failure: Throwable) {
                failure
            }

            assertSame(closeFatalFailure, first)
            assertTrue(closeFatalFailure.suppressed.any { it === commitFailure })
            assertFalse(index.isRunning)
            assertEquals(
                listOf(
                    SearchIndexLifecycleStep.WRITER_COMMIT,
                    SearchIndexLifecycleStep.CLOSE_SEARCHERS,
                    SearchIndexLifecycleStep.CLOSE_WRITER,
                    SearchIndexLifecycleStep.CLOSE_DIRECTORY,
                    SearchIndexLifecycleStep.CLOSE_ANALYZER,
                ),
                cleanupSteps,
                "all Lucene resources must drain after earlier failures",
            )

            val cleanupCount = cleanupSteps.size
            val repeatedStop = try {
                index.stop()
                fail("failed stop must replay its terminal failure")
            } catch (failure: Throwable) {
                failure
            }
            assertSame(closeFatalFailure, repeatedStop)
            assertEquals(cleanupCount, cleanupSteps.size, "terminal replay must not close twice")

            val restart = try {
                index.start()
                fail("an instance with a failed stop must not restart")
            } catch (failure: Throwable) {
                failure
            }
            assertSame(closeFatalFailure, restart)
            assertEquals(cleanupCount, cleanupSteps.size)
        } finally {
            check(root.deleteRecursively() || !root.exists()) {
                "Failed to delete SearchIndex stop lifecycle root: $root"
            }
        }
    }
}

private class SearchStartupFailure(message: String) : RuntimeException(message)

private class SearchCommitFailure(message: String) : RuntimeException(message)

private class SearchLifecycleFatalFailure(message: String) : Error(message)
