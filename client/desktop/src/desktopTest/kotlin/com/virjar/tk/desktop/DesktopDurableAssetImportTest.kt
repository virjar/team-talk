package com.virjar.tk.desktop

import com.virjar.tk.app.ui.bridge.ChatAssetImportDelegate
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportEvent
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportEventSink
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportRegistration
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportSource
import com.virjar.tk.app.ui.bridge.EmbeddedAssetLocalSelection
import com.virjar.tk.desktop.media.DesktopSessionResources
import com.virjar.tk.protocol.body.EmbeddedAssetPresentation
import com.virjar.tk.shared.client.AccountDataOwner
import com.virjar.tk.shared.client.DeploymentIdentity
import com.virjar.tk.shared.client.SessionHttpCredentials
import com.virjar.tk.shared.log.NoopLogger
import com.virjar.tk.shared.repository.ChatAssetSpool
import com.virjar.tk.shared.repository.StagedChatAsset
import com.virjar.tk.shared.repository.UploadSink
import com.virjar.tk.shared.repository.UploadSource
import com.virjar.tk.shared.repository.createChatAssetSpool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDurableAssetImportTest {
    @Test
    fun `durable gateway freezes user file before placement and survives gateway retirement`() = runBlocking {
        val root = Files.createTempDirectory("desktop-durable-import-").toFile()
        val resources = resources(root)
        val spool = createChatAssetSpool(root, owner)
        val delegate = Delegate(spool)
        val gateway = DesktopEmbeddedAssetImportGateway(resources, resources.fileTransfer, { it() }, delegate)
        try {
            val original = File(root, "selection.png").apply { writeText("first snapshot") }
            val placement = CompletableDeferred<EmbeddedAssetImportEvent.StateChanged>()
            val registration = gateway.bind("chat:one:draft", EmbeddedAssetImportEventSink { event ->
                if (event is EmbeddedAssetImportEvent.StateChanged && event.placement != null) placement.complete(event)
            })
            gateway.import(desktopEmbeddedAssetSelection(
                original, EmbeddedAssetPresentation.FILE, EmbeddedAssetImportSource.DESKTOP_PICKER,
            ))
            withTimeout(5_000) { delegate.entered.await() }
            assertFalse(placement.isCompleted, "No accepted node may precede durable source admission")
            delegate.allowPrepare.complete(Unit)
            val event = withTimeout(5_000) { placement.await() }
            assertEquals(event.job.assetId, event.job.jobId)
            assertEquals(EmbeddedAssetPresentation.FILE, event.placement!!.presentation)
            val staged = withTimeout(5_000) { delegate.prepared.await() }
            original.writeText("changed after selection")
            assertTrue(gateway.retry(event.job.assetId))
            assertEquals(1, delegate.retries.get())
            registration.close()
            assertEquals(1, delegate.unbound.get())
            gateway.close()
            val reopened = createChatAssetSpool(root, owner)
            val output = ByteArrayOutputStream()
            reopened.open(staged.sourceId).writeTo(UploadSink { bytes, offset, length -> output.write(bytes, offset, length) })
            assertEquals("first snapshot", output.toString(Charsets.UTF_8))
            assertEquals("changed after selection", original.readText())
        } finally {
            delegate.allowPrepare.complete(Unit)
            gateway.close()
            resources.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `gateway disposes owned clipboard copy while retaining durable source`() = runBlocking {
        val root = Files.createTempDirectory("desktop-durable-clipboard-").toFile()
        val resources = resources(root)
        val spool = createChatAssetSpool(root, owner)
        val delegate = Delegate(spool).apply { allowPrepare.complete(Unit) }
        val gateway = DesktopEmbeddedAssetImportGateway(resources, resources.fileTransfer, { it() }, delegate)
        try {
            val temporary = File(root, "clipboard.png").apply { writeText("clipboard snapshot") }
            val placement = CompletableDeferred<Unit>()
            gateway.bind("chat:one:draft", EmbeddedAssetImportEventSink { event ->
                if (event is EmbeddedAssetImportEvent.StateChanged && event.placement != null) placement.complete(Unit)
            })
            gateway.import(desktopEmbeddedAssetSelection(
                temporary, EmbeddedAssetPresentation.IMAGE, EmbeddedAssetImportSource.DESKTOP_CLIPBOARD,
                deleteAfterImport = true, displayName = "剪贴板图片.png",
            ))
            withTimeout(5_000) { placement.await() }
            gateway.close()
            resources.close()
            withTimeout(5_000) { while (temporary.exists()) delay(5) }
            assertFalse(temporary.exists())
            assertEquals(1, spool.list().size)
        } finally {
            gateway.close()
            resources.close()
            root.deleteRecursively()
        }
    }

    private class Delegate(private val spool: ChatAssetSpool) : ChatAssetImportDelegate {
        val entered = CompletableDeferred<Unit>()
        val allowPrepare = CompletableDeferred<Unit>()
        val prepared = CompletableDeferred<StagedChatAsset>()
        val unbound = AtomicInteger()
        val retries = AtomicInteger()
        override fun handles(ownerKey: String) = ownerKey.startsWith("chat:") && ownerKey.endsWith(":draft")
        override fun bind(ownerKey: String, sink: EmbeddedAssetImportEventSink) =
            EmbeddedAssetImportRegistration { unbound.incrementAndGet() }
        override suspend fun prepare(ownerKey: String, assetId: String, source: UploadSource, selection: EmbeddedAssetLocalSelection) {
            entered.complete(Unit)
            allowPrepare.await()
            prepared.complete(spool.stage(source))
        }
        override fun cancel(assetId: String) = true
        override fun retry(assetId: String): Boolean { retries.incrementAndGet(); return true }
        override fun preparationFailed(ownerKey: String) { prepared.completeExceptionally(IllegalStateException("Preparation failed")) }
    }

    private fun resources(root: File) = DesktopSessionResources(
        ownerUid = owner.uid,
        datasetId = owner.datasetId,
        deploymentIdentity = DeploymentIdentity.from("localhost", 5100, "http://localhost:18088"),
        credentialProvider = { SessionHttpCredentials(owner.uid, "isolated-test-token") },
        dataDir = root,
        diagnosticLogger = NoopLogger,
    )

    private companion object {
        val owner = AccountDataOwner("a".repeat(64), "00000000-0000-4000-8000-000000000001", "owner")
    }
}
