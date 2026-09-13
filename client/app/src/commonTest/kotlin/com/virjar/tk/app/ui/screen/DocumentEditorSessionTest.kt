package com.virjar.tk.app.ui.screen

import androidx.compose.ui.text.TextRange
import com.mohamedrejeb.richeditor.model.RichTextState
import com.virjar.tk.app.navigation.feature.document.DocumentDraftLifecycleBridge
import com.virjar.tk.app.navigation.feature.document.DocumentTabState
import com.virjar.tk.app.navigation.feature.document.DocumentWorkspaceTabs
import com.virjar.tk.app.navigation.feature.document.updateDocumentDraftTabs
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportBindingRouter
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportEvent
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportEventSink
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportPlacement
import com.virjar.tk.app.ui.component.rich.DocumentBlockEditorController
import com.virjar.tk.app.ui.component.rich.DocumentBlockEditorFrame
import com.virjar.tk.app.ui.component.rich.DocumentEmbeddedFileBlock
import com.virjar.tk.app.ui.component.rich.DocumentMarkdownBlockCodec
import com.virjar.tk.app.ui.component.rich.DocumentRichEditorSession
import com.virjar.tk.app.ui.component.rich.DocumentRichRun
import com.virjar.tk.app.ui.component.rich.PendingAssetJob
import com.virjar.tk.app.ui.component.rich.PendingAssetJobState
import com.virjar.tk.app.ui.component.rich.embeddedAssetMarkdown
import com.virjar.tk.app.ui.component.rich.embeddedAssetMarkdownReferences
import com.virjar.tk.app.ui.component.rich.insertDocumentEmbeddedAssetAtRichSelection
import com.virjar.tk.protocol.body.EmbeddedAssetPresentation
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.EmbeddedAsset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 会话规则通过实际导入路由、块 controller 和草稿投影协作；不启动平台选择器或 Compose 窗口。 */
class DocumentEditorSessionTest {
    @Test
    fun `ready splits the visual asset without retaining its old inline rich snapshot on save`() {
        val initial = tab("document-a", 1).copy(
            savedMarkdown = "正文最后输入。", draftMarkdown = "正文最后输入。",
        )
        val tabs = DocumentWorkspaceTabs().apply { publish(listOf(initial), initial.tabId) }
        val controller = DocumentBlockEditorController()
        val session = DocumentEditorSession(initial, controller) { update ->
            tabs.replace(updateDocumentDraftTabs(tabs.items, update))
        }
        session.finishInitialization()

        fun mountRichEditor(frame: DocumentBlockEditorFrame): DocumentRichRun {
            val block = frame.blocks.filterIsInstance<DocumentRichRun>().single()
            val state = RichTextState().setMarkdown(block.markdown)
            val rich = DocumentRichEditorSession(state, block).apply {
                normalizedBaseline = state.toMarkdown()
                lastReportedMarkdown = normalizedBaseline
                ready = true
            }
            frame.richSessions[block.key] = rich
            frame.richSnapshots[block.key] = rich::snapshotRichRun
            state.selection = TextRange(state.annotatedString.length)
            if (!controller.consumePendingRichActivation(block.key, state, requestFocus = {})) {
                controller.activate(block.key, state)
            }
            controller.bindActions(
                insert = {}, append = {},
                insertEmbeddedAsset = { _, syntax, _ ->
                    frame.materializeRichSnapshots()
                    val active = frame.blocks.single { it.key == controller.activeBlockKey }
                    insertDocumentEmbeddedAssetAtRichSelection(
                        active, controller.activeRichState, frame.richSessions[active.key]?.state, syntax,
                    )
                },
                snapshot = frame::snapshotMarkdown,
            )
            return block
        }

        val beforeReady = DocumentBlockEditorFrame(initial.draftMarkdown, emptyList())
        val originalRich = mountRichEditor(beforeReady)
        val router = EmbeddedAssetImportBindingRouter()
        router.bind(session.embeddedAssetOwnerKey, EmbeddedAssetImportEventSink { event ->
            session.acceptEmbeddedAssetImport(event, previewMode = false)
        })
        val binding = assertNotNull(router.captureForImport())
        val job = job(1)
        val placement = EmbeddedAssetImportPlacement("refactor-attachment.txt", EmbeddedAssetPresentation.FILE)
        val asset = EmbeddedAsset(job.assetId, Attachment("files/fixture.txt", placement.label, "text/plain", 64))
        val expected = initial.draftMarkdown + embeddedAssetMarkdown(asset, placement.presentation, placement.label)
        router.publish(binding, EmbeddedAssetImportEvent.StateChanged(job, placement))
        assertEquals(expected, controller.snapshotMarkdown(""))
        router.publish(binding, EmbeddedAssetImportEvent.Ready(ready(job), asset, placement))
        assertEquals(expected, session.blockMarkdown)

        // READY 在同一 documentKey 下把引用拆为卡片；前缀恰好复用 LOCAL 插入之前的块 key。
        val afterReady = DocumentBlockEditorFrame(session.blockMarkdown, session.embeddedAssetSnapshot.assets)
        afterReady.reconcileActiveEditor(controller)
        assertNull(controller.activeRichState)
        assertEquals(originalRich.key, controller.pendingActivationKey)
        val newRich = mountRichEditor(afterReady)
        assertNull(controller.pendingActivationKey)
        assertEquals(originalRich.key, newRich.key)
        assertEquals(1, afterReady.blocks.filterIsInstance<DocumentEmbeddedFileBlock>().size)
        assertEquals(expected, afterReady.snapshotMarkdown(""))
        val lifecycle = DocumentDraftLifecycleBridge()
        val registration = lifecycle.register(session.owner) { session.captureLatestDraft() }
        assertTrue(session.prepareSave())
        assertTrue(lifecycle.captureAndUnregister(registration))
        assertEquals(expected, tabs.activeTab?.draftMarkdown)
        assertEquals(listOf(job.assetId), embeddedAssetMarkdownReferences(session.blockMarkdown).map { it.assetId })

        // 修复只淘汰旧画布状态，同一资产被用户显式引用两次仍须原样保存。
        val repeated = expected + embeddedAssetMarkdown(asset, placement.presentation, placement.label)
        val repeatedFrame = DocumentBlockEditorFrame(repeated, listOf(asset))
        mountRichEditor(repeatedFrame)
        assertEquals(repeated, repeatedFrame.snapshotMarkdown(""))
        assertEquals(2, repeatedFrame.blocks.filterIsInstance<DocumentEmbeddedFileBlock>().size)
        router.close()
    }

    @Test
    fun `reparsed frame drops missing manually inserted active and pending block keys`() {
        val controller = DocumentBlockEditorController()
        val frame = DocumentBlockEditorFrame("原文", emptyList())
        val inserted = DocumentRichRun(
            key = "document-insert-fixture-rich-9", markdown = "新插入正文", leadingMarkdown = "\n\n",
        )
        frame.blocks += inserted
        val reparsed = DocumentBlockEditorFrame(DocumentMarkdownBlockCodec.encode(frame.blocks), emptyList())
        assertTrue(reparsed.blocks.none { it.key == inserted.key })

        controller.activate(inserted.key, RichTextState().setMarkdown(inserted.markdown))
        reparsed.reconcileActiveEditor(controller)
        assertNull(controller.activeBlockKey)
        assertNull(controller.activeRichState)
        assertNull(controller.pendingActivationKey)

        controller.requestRichActivation(inserted.key)
        reparsed.reconcileActiveEditor(controller)
        assertNull(controller.activeBlockKey)
        assertNull(controller.pendingActivationKey)
        assertNull(controller.pendingFocusKey)
        assertEquals("原文\n\n新插入正文", reparsed.snapshotMarkdown(""))
    }

    @Test
    fun `ready materializes the pending visual frame before publishing its asset manifest`() {
        val initial = tab("document-a", 1)
        val tabs = DocumentWorkspaceTabs().apply { publish(listOf(initial), initial.tabId) }
        val controller = DocumentBlockEditorController()
        val session = DocumentEditorSession(initial, controller) { update ->
            tabs.replace(updateDocumentDraftTabs(tabs.items, update))
        }
        session.beginInitialization()
        session.publishCurrentDraftIfReady()
        assertEquals(initial, tabs.activeTab)
        session.finishInitialization()

        var visualMarkdown = initial.draftMarkdown
        controller.bindActions(
            insert = {}, append = {},
            insertEmbeddedAsset = { _, syntax, _ ->
                visualMarkdown += "\n\n$syntax"
                true
            },
            snapshot = { visualMarkdown },
        )
        val router = EmbeddedAssetImportBindingRouter()
        router.bind(session.embeddedAssetOwnerKey, EmbeddedAssetImportEventSink { event ->
            session.acceptEmbeddedAssetImport(event, previewMode = false)
        })
        val binding = assertNotNull(router.captureForImport())
        val job = job(1)
        router.publish(binding, EmbeddedAssetImportEvent.StateChanged(job, placement))
        assertFalse(session.prepareSave())
        assertEquals(DocumentEmbeddedAssetCommitError.UPLOAD_PENDING, session.embeddedAssetError)

        // 模拟实际 controller 能读到、但尚未经过块编辑器 250ms projection 合并的最后输入。
        visualMarkdown += "\n\n尚未同步的视觉输入"
        assertFalse(session.blockMarkdown.contains("尚未同步"))
        val asset = asset(job)
        router.publish(binding, EmbeddedAssetImportEvent.Ready(ready(job), asset, placement))
        assertEquals(visualMarkdown, session.blockMarkdown)
        assertEquals(visualMarkdown, tabs.activeTab?.draftMarkdown)
        assertEquals(listOf(asset), tabs.activeTab?.draftAssets)
        assertNull(session.embeddedAssetError)
        assertTrue(session.prepareSave())

        session.toggleSourceMode()
        controller.clear(visualMarkdown)
        session.sourceMarkdown += "\n\n源码最后一行"
        session.prepareForPreview()
        session.toggleSourceMode()
        assertFalse(session.sourceMode)
        // 新视觉画布挂载前就捕获，源码修改也不能被旧画布的退役帧覆盖。
        val lifecycle = DocumentDraftLifecycleBridge()
        val registration = lifecycle.register(session.owner) { session.captureLatestDraft() }
        assertTrue(lifecycle.captureAndUnregister(registration))
        assertEquals(session.sourceMarkdown, tabs.activeTab?.draftMarkdown)
        assertEquals(listOf(asset), tabs.activeTab?.draftAssets)
        assertTrue(tabs.activeTab?.dirty == true)
        router.close()
    }

    @Test
    fun `retiring unbound editor accumulates queued assets without changing the next editor`() {
        val first = tab("document-a", 1)
        val second = tab("document-b", 2)
        val tabs = DocumentWorkspaceTabs().apply { publish(listOf(first, second), first.tabId) }
        val firstController = DocumentBlockEditorController()
        val firstSession = DocumentEditorSession(first, firstController) { update ->
            tabs.replace(updateDocumentDraftTabs(tabs.items, update))
        }
        firstSession.finishInitialization()
        val lifecycle = DocumentDraftLifecycleBridge()
        val firstCapture = lifecycle.register(firstSession.owner) { firstSession.captureLatestDraft() }
        val router = EmbeddedAssetImportBindingRouter()
        val firstImports = router.bind(firstSession.embeddedAssetOwnerKey, EmbeddedAssetImportEventSink { event ->
            firstSession.acceptEmbeddedAssetImport(event, previewMode = false)
        })
        val firstBinding = assertNotNull(router.captureForImport())
        val jobs = listOf(job(1), job(2))
        jobs.forEach { router.publish(firstBinding, EmbeddedAssetImportEvent.StateChanged(it, placement)) }
        jobs.forEach { router.publish(firstBinding, EmbeddedAssetImportEvent.Ready(ready(it), asset(it), placement)) }
        assertEquals(4, firstSession.deferredEmbeddedAssetEvents.size)

        // 原生子编辑器可能先退出，其最后输入甚至还没进入父级的正文 projection。
        val finalVisualMarkdown = first.draftMarkdown + "\n\n退出前的最后输入"
        firstController.clear(finalVisualMarkdown)
        firstImports.close()
        assertTrue(lifecycle.captureLatest())
        val retired = tabs.items.first { it.tabId == first.tabId }
        assertTrue(retired.draftMarkdown.startsWith(finalVisualMarkdown))
        jobs.forEach { assertTrue(retired.draftMarkdown.contains(EmbeddedAsset.uri(it.assetId))) }
        assertEquals(jobs.map(::asset), retired.draftAssets)
        assertTrue(firstSession.deferredEmbeddedAssetEvents.isEmpty())
        // 重复生命周期屏障和保存检查都不能重新读取旧的 clear 快照，抹掉边界追加。
        assertTrue(lifecycle.captureLatest())
        assertTrue(firstSession.prepareSave())
        assertEquals(retired, tabs.items.first { it.tabId == first.tabId })

        val secondSession = DocumentEditorSession(second, DocumentBlockEditorController()) { update ->
            tabs.replace(updateDocumentDraftTabs(tabs.items, update))
        }
        secondSession.finishInitialization()
        secondSession.title = "B 独立标题"
        tabs.activate(second.tabId)
        val secondCapture = lifecycle.register(secondSession.owner) { secondSession.captureLatestDraft() }
        router.bind(secondSession.embeddedAssetOwnerKey, EmbeddedAssetImportEventSink { event ->
            secondSession.acceptEmbeddedAssetImport(event, previewMode = false)
        })

        // 更晚到达的 A disposal 不得捕获或摘掉 B 的注册。
        assertTrue(lifecycle.captureAndUnregister(firstCapture))
        assertEquals(second, tabs.activeTab)

        assertTrue(lifecycle.captureLatest())
        assertEquals("B 独立标题", tabs.activeTab?.draftTitle)
        assertTrue(tabs.activeTab?.draftAssets?.isEmpty() == true)
        assertTrue(lifecycle.captureAndUnregister(secondCapture))
        router.close()
    }

    @Test
    fun `preview drains received placements and source rebind does not resurrect a removed reference`() {
        val initial = tab("document-a", 1)
        val tabs = DocumentWorkspaceTabs().apply { publish(listOf(initial), initial.tabId) }
        val controller = DocumentBlockEditorController()
        val session = DocumentEditorSession(initial, controller) { update ->
            tabs.replace(updateDocumentDraftTabs(tabs.items, update))
        }
        session.finishInitialization()
        val router = EmbeddedAssetImportBindingRouter()
        val editing = router.bind(session.embeddedAssetOwnerKey, EmbeddedAssetImportEventSink { event ->
            session.acceptEmbeddedAssetImport(event, previewMode = false)
        })
        val upload = assertNotNull(router.captureForImport())
        val job = job(1)
        router.publish(upload, EmbeddedAssetImportEvent.StateChanged(job, placement))
        assertEquals(1, session.deferredEmbeddedAssetEvents.size)

        editing.close()
        controller.clear(initial.draftMarkdown)
        val preview = router.bind(session.embeddedAssetOwnerKey, EmbeddedAssetImportEventSink { event ->
            session.acceptEmbeddedAssetImport(event, previewMode = true)
        }, acceptNewImports = false)
        assertNull(router.captureForImport())
        session.drainEmbeddedAssetImports(previewMode = true)
        assertTrue(session.currentMarkdown.contains(EmbeddedAsset.uri(job.assetId)))
        assertFalse(session.prepareSave())

        preview.close()
        router.bind("document:other", EmbeddedAssetImportEventSink { error("wrong import owner") })
        router.publish(upload, EmbeddedAssetImportEvent.Ready(ready(job), asset(job), placement))
        session.toggleSourceMode()
        session.sourceMarkdown = "源码中已移除附件，保留这行新输入"
        router.bind(session.embeddedAssetOwnerKey, EmbeddedAssetImportEventSink { event ->
            session.acceptEmbeddedAssetImport(event, previewMode = false)
        })
        session.drainEmbeddedAssetImports(previewMode = false)

        val lifecycle = DocumentDraftLifecycleBridge()
        val capture = lifecycle.register(session.owner) { session.captureLatestDraft() }
        assertTrue(lifecycle.captureAndUnregister(capture))
        assertEquals(session.sourceMarkdown, tabs.activeTab?.draftMarkdown)
        assertTrue(tabs.activeTab?.draftAssets?.isEmpty() == true)
        assertTrue(session.deferredEmbeddedAssetEvents.isEmpty())
        assertNull(session.embeddedAssetError)
        assertTrue(session.prepareSave())
        router.close()
    }

    @Test
    fun `synchronous preview rebind waits for the old visual controller to hand over its last frame`() {
        val initial = tab("document-a", 1)
        val tabs = DocumentWorkspaceTabs().apply { publish(listOf(initial), initial.tabId) }
        val controller = DocumentBlockEditorController()
        val session = DocumentEditorSession(initial, controller) { update ->
            tabs.replace(updateDocumentDraftTabs(tabs.items, update))
        }
        session.finishInitialization()
        var visualMarkdown = initial.draftMarkdown
        controller.bindActions(
            insert = {}, append = {},
            insertEmbeddedAsset = { _, _, _ -> error("preview must wait for the old visual editor") },
            snapshot = { visualMarkdown },
        )
        val router = EmbeddedAssetImportBindingRouter()
        val editing = router.bind(session.embeddedAssetOwnerKey, EmbeddedAssetImportEventSink { event ->
            session.acceptEmbeddedAssetImport(event, previewMode = false)
        })
        val upload = assertNotNull(router.captureForImport())
        session.prepareForPreview()
        editing.close()
        router.bind("document:other", EmbeddedAssetImportEventSink { error("wrong import owner") })
        val job = job(1)
        router.publish(upload, EmbeddedAssetImportEvent.StateChanged(job, placement))
        router.publish(upload, EmbeddedAssetImportEvent.Ready(ready(job), asset(job), placement))

        // bind 会同步重放，但此时旧画布可能仍未清理，最后的输入还留在它的快照中。
        visualMarkdown += "\n\n画布退出时才物化的最后输入"
        router.bind(session.embeddedAssetOwnerKey, EmbeddedAssetImportEventSink { event ->
            session.acceptEmbeddedAssetImport(event, previewMode = true)
        }, acceptNewImports = false)
        assertEquals(2, session.deferredEmbeddedAssetEvents.size)
        session.drainEmbeddedAssetImports(previewMode = true)
        assertEquals(initial.draftMarkdown, session.currentMarkdown)

        controller.clear(visualMarkdown)
        session.drainEmbeddedAssetImports(previewMode = true)
        val lifecycle = DocumentDraftLifecycleBridge()
        val capture = lifecycle.register(session.owner) { session.captureLatestDraft() }
        assertTrue(lifecycle.captureLatest())
        assertTrue(session.prepareSave())
        assertTrue(lifecycle.captureAndUnregister(capture))
        val saved = assertNotNull(tabs.activeTab)
        assertTrue(saved.draftMarkdown.startsWith(visualMarkdown))
        assertEquals(1, saved.draftMarkdown.split(EmbeddedAsset.uri(job.assetId)).size - 1)
        assertEquals(listOf(asset(job)), saved.draftAssets)
        assertTrue(session.deferredEmbeddedAssetEvents.isEmpty())
        router.close()
    }

    private fun tab(id: String, instanceId: Long) = DocumentTabState(
        tabId = id, instanceId = instanceId, recoveryId = "recovery-$id", documentId = id,
        spaceId = "space", parentId = null, ancestorIds = emptyList(),
        savedTitle = id, draftTitle = id, savedMarkdown = "原文 $id", draftMarkdown = "原文 $id",
        revision = 1,
    )

    private fun job(number: Int) = PendingAssetJob(
        jobId = "job-$number", assetId = "00000000-0000-4000-8000-${number.toString().padStart(12, '0')}",
    )

    private fun ready(job: PendingAssetJob) = job.copy(state = PendingAssetJobState.READY, progress = 1f)

    private fun asset(job: PendingAssetJob) = EmbeddedAsset(
        job.assetId, Attachment("files/${job.assetId}.png", "fixture.png", "image/png", 64),
    )

    private val placement = EmbeddedAssetImportPlacement("fixture", EmbeddedAssetPresentation.IMAGE)
}
