package com.virjar.tk.app.ui.bridge

import com.virjar.tk.app.ui.component.rich.PendingAssetJobState
import com.virjar.tk.protocol.body.EmbeddedAssetPresentation
import com.virjar.tk.protocol.http.AttachmentUploadIdentity
import com.virjar.tk.protocol.http.UploadResult
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.shared.repository.asSmallUploadSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class EmbeddedAssetImportCoordinatorTest {
    @Test
    fun retryRetainsPreparedSourceAndIdentityAndReleasesOnlyAfterReady() = runTest {
        var prepared = 0
        var released = 0
        var releasedSelection = 0
        val identities = mutableListOf<AttachmentUploadIdentity>()
        val events = mutableListOf<EmbeddedAssetImportEvent>()
        val imports = EmbeddedAssetImportCoordinator(
            launch = { action -> launch { action() } }, publishOnUi = { it() }, ensureOpen = {},
            prepare = { _: EmbeddedAssetLocalSelection -> ++prepared },
            source = { _: Int -> byteArrayOf(1).asSmallUploadSource() },
            upload = { value, _, identity, progress ->
                assertEquals(1, value)
                identities += identity
                progress(0.7f); progress(0.2f)
                if (identities.size == 1) error("response lost")
                UploadResult(attachment)
            },
            releaseSource = { released++ }, releaseSelection = { releasedSelection++ },
        )
        imports.bind("document:a", { events += it }, true)
        imports.import(selection)
        runCurrent()
        val failed = events.last().job
        assertEquals(PendingAssetJobState.FAILED, failed.state)
        assertEquals(0, released)
        assertEquals(0, releasedSelection)
        assertTrue(imports.retry(failed.jobId))
        runCurrent()
        assertEquals(identities[0], identities[1])
        assertEquals(1, prepared)
        assertEquals(1, released)
        assertEquals(1, releasedSelection)
        assertIs<EmbeddedAssetImportEvent.Ready>(events.last())
        imports.close()
        assertEquals(1, released)
    }

    @Test
    fun retirementBeforeFirstDispatchReleasesSelectionWithoutReadingSource() = runTest {
        var released = 0
        val imports = EmbeddedAssetImportCoordinator<Unit>(
            launch = { action -> launch { action() } }, publishOnUi = { it() }, ensureOpen = {},
            prepare = { error("retired import must not read its source") },
            source = { byteArrayOf(1).asSmallUploadSource() },
            upload = { _, _, _, _ -> error("retired import must not upload") },
            releaseSelection = { released++ },
        )
        imports.bind("document:a", {}, true)
        imports.import(selection)
        imports.close()
        runCurrent()
        assertEquals(1, released)
    }

    private val attachment = Attachment("owner/file", "材料.txt", "text/plain", 1L)
    private val selection = EmbeddedAssetLocalSelection("local-file", "材料.txt", "text/plain", 1L,
        EmbeddedAssetPresentation.FILE, EmbeddedAssetImportSource.DESKTOP_PICKER, deleteAfterImport = true)
}
