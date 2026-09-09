package com.virjar.tk.android

import com.virjar.tk.shared.client.AndroidLocalCacheStorageCloseException
import com.virjar.tk.shared.client.DeploymentIdentity
import com.virjar.tk.shared.client.LocalCacheStorageCompactionException
import com.virjar.tk.shared.client.LocalCacheStorageCompactionFailure
import com.virjar.tk.shared.client.LocalCacheStorageCompactionReport
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AndroidStorageMaintenanceOwnerTest {
    private val identity = DeploymentIdentity.from("127.0.0.1", 5100, "http://127.0.0.1:18088")
    private val datasetId = "11111111-1111-4111-8111-111111111111"
    private val report = LocalCacheStorageCompactionReport(8192, 4096, 2, 1, 1, 0)

    @Test
    fun `observer disposal never joins maintenance and only explicit finish admits a new workspace`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ownerKey = Any()
        val calls = AtomicInteger()
        val maintenance = AndroidStorageMaintenanceOwner(identity) { selectedIdentity, selectedDataset, uid ->
            assertEquals(identity, selectedIdentity)
            assertEquals(datasetId, selectedDataset)
            assertEquals("user-a", uid)
            calls.incrementAndGet()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            report
        }
        assertTrue(maintenance.beginRequest(ownerKey, identity, datasetId, "user-a", true))
        assertFalse(maintenance.compact())
        maintenance.completeRetirement(Any(), true)
        assertEquals(AndroidStorageMaintenancePhase.PREPARING, maintenance.state.value.phase)
        maintenance.completeRetirement(ownerKey, true)
        val observer = launch(start = CoroutineStart.UNDISPATCHED) { maintenance.state.collect { } }
        try {
            assertTrue(maintenance.compact())
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // Activity disposal only stops its observer. It neither cancels nor joins the IO owner.
            withTimeout(1000) { observer.cancelAndJoin() }
            assertEquals(AndroidStorageMaintenancePhase.RUNNING, maintenance.state.value.phase)
            assertFalse(maintenance.compact())
            assertFalse(maintenance.finish())
            assertFalse(maintenance.beginRequest(Any(), identity, datasetId, "user-a", true))
        } finally {
            release.countDown()
            observer.cancelAndJoin()
        }
        val completed = withTimeout(5000) {
            maintenance.state.first { it.phase == AndroidStorageMaintenancePhase.FINISHED }
        }
        assertSame(report, completed.result)
        assertEquals(1, calls.get())
        assertTrue(maintenance.finish())
        assertEquals(AndroidStorageMaintenancePhase.IDLE, maintenance.state.value.phase)
        assertTrue(maintenance.beginRequest(Any(), identity, datasetId, "user-a", true))
    }

    @Test
    fun `unconfirmed or failed retirement never opens a maintenance database or resumes authentication`() {
        var calls = 0
        val maintenance = AndroidStorageMaintenanceOwner(identity) { _, _, _ -> calls++; report }
        val ownerKey = Any()
        assertFalse(maintenance.beginRequest(ownerKey, identity, datasetId, "user-a", false))
        val other = DeploymentIdentity.from("127.0.0.1", 5101, "http://127.0.0.1:18089")
        assertFalse(maintenance.beginRequest(ownerKey, other, datasetId, "user-a", true))
        assertTrue(maintenance.beginRequest(ownerKey, identity, datasetId, "user-a", true))
        assertFalse(maintenance.finish())
        maintenance.completeRetirement(ownerKey, false)
        maintenance.completeRetirement(ownerKey, true)
        assertEquals(AndroidStorageMaintenancePhase.RETIREMENT_FAILED, maintenance.state.value.phase)
        assertFalse(maintenance.compact())
        assertFalse(maintenance.finish())
        assertFalse(maintenance.beginRequest(Any(), identity, datasetId, "user-a", true))
        assertEquals(0, calls)
    }

    @Test
    fun `driver close failure keeps authentication paused even when maintenance has ended`() = runBlocking {
        val maintenance = AndroidStorageMaintenanceOwner(identity) { _, _, _ ->
            throw AndroidLocalCacheStorageCloseException(IOException("close refused"))
        }
        ready(maintenance)
        assertTrue(maintenance.compact())
        val failed = withTimeout(5000) {
            maintenance.state.first { it.phase == AndroidStorageMaintenancePhase.RETIREMENT_FAILED }
        }
        assertNull(failed.result)
        assertNotNull(failed.errorMessage)
        assertFalse(maintenance.finish())
        assertFalse(maintenance.compact())
    }

    @Test
    fun `ordinary maintenance failure after successful close is visible and can be retried`() = runBlocking {
        val calls = AtomicInteger()
        val maintenance = AndroidStorageMaintenanceOwner(identity) { _, _, _ ->
            if (calls.incrementAndGet() == 1) {
                throw LocalCacheStorageCompactionException(LocalCacheStorageCompactionFailure.INSUFFICIENT_FREE_SPACE)
            }
            report
        }
        ready(maintenance)
        assertTrue(maintenance.compact())
        val failed = withTimeout(5000) {
            maintenance.state.first { it.phase == AndroidStorageMaintenancePhase.FINISHED }
        }
        assertNull(failed.result)
        assertNotNull(failed.errorMessage)
        assertTrue(maintenance.compact())
        val completed = withTimeout(5000) {
            maintenance.state.first { it.phase == AndroidStorageMaintenancePhase.FINISHED }
        }
        assertSame(report, completed.result)
        assertNull(completed.errorMessage)
        assertEquals(2, calls.get())
        assertTrue(maintenance.finish())
    }

    private fun ready(maintenance: AndroidStorageMaintenanceOwner) {
        val key = Any()
        assertTrue(maintenance.beginRequest(key, identity, datasetId, "user-a", true))
        maintenance.completeRetirement(key, true)
        assertEquals(AndroidStorageMaintenancePhase.READY, maintenance.state.value.phase)
    }
}
