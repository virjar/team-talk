package com.virjar.tk.app.ui

import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.app.ui.component.AutomaticFileDownloadLedger
import com.virjar.tk.app.ui.component.FileDownloadController
import com.virjar.tk.app.ui.component.FileDownloadState
import com.virjar.tk.app.ui.component.VoicePlaybackController
import androidx.compose.runtime.snapshots.SnapshotStateMap

/** 会话能力视图，交给深层公共 UI 节点使用，否则这些节点会直接调用 owner。 */
internal class AdmittedVoicePlaybackController(
    private val delegate: VoicePlaybackController,
    private val admission: UiActionAdmission,
) : VoicePlaybackController {
    override val playingUrl: String? get() = delegate.playingUrl
    override val progress: Float get() = delegate.progress

    override fun toggle(attachment: Attachment, durationSec: Int) {
        admission.runIfOpen { delegate.toggle(attachment, durationSec) }
    }
}

internal class AdmittedFileDownloadController(
    private val delegate: FileDownloadController,
    private val admission: UiActionAdmission,
) : FileDownloadController {
    override val states: SnapshotStateMap<String, FileDownloadState> get() = delegate.states
    override val automaticDownloadLedger: AutomaticFileDownloadLedger
        get() = delegate.automaticDownloadLedger

    override fun ensure(attachment: Attachment) {
        admission.runIfOpen { delegate.ensure(attachment) }
    }

    override fun download(attachment: Attachment) {
        admission.runIfOpen { delegate.download(attachment) }
    }

    override fun openOrDownload(attachment: Attachment) {
        admission.runIfOpen { delegate.openOrDownload(attachment) }
    }

    /** 这是非持有视图；只有平台/会话 owner 才会关闭真正的 controller。 */
    override fun close() = Unit
}
