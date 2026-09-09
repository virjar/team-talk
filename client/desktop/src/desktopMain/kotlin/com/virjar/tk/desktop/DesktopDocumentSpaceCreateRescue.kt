package com.virjar.tk.desktop

import com.virjar.tk.app.navigation.feature.document.DocumentSpaceCreateRescue
import com.virjar.tk.app.navigation.feature.document.DocumentSpaceCreateRescueMetadata
import com.virjar.tk.app.navigation.feature.document.DocumentSpaceCreateRescuePrepared
import com.virjar.tk.shared.client.LocalCacheDiagnosticOwner
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
internal data class DesktopDocumentSpaceCreateRescuePreview(
    val root: String,
    val database: String,
    val owner: LocalCacheDiagnosticOwner,
    val selection: DocumentSpaceCreateRescueMetadata,
    val manifestSha256: String,
    val targetStateSha256: String,
    val consequence: String = "Restore the original space creation request with its document drafts and admitted creation requests. " +
        "Opening the client reconciles or retries the same space identity, then retries admitted document requests when the space is available. " +
        "Unsubmitted drafts and later edits remain unsaved. An accepted space is not reverted or revived; " +
        "missing access or assets may leave document requests pending. No other operation or unfinished upload is restored. " +
        "The archive and original files remain unchanged",
)

/** Space and child records are published together by the same offline record writer. */
internal object DesktopDocumentSpaceCreateRescue {
    fun listRecords(archive: File): DesktopDocumentDraftRescueRecords =
        DesktopDocumentDraftRescue.readDocumentSource(archive) { source, owner, sha ->
            DesktopDocumentDraftRescueRecords(owner, sha, DocumentSpaceCreateRescue.recordKeys(source),
                "One unretired space creation and its supported document dependencies. Preview also validates the empty target")
        }

    fun preview(root: File, database: String, archive: File, recordKey: String): DesktopDocumentSpaceCreateRescuePreview {
        val source = readSource(archive, recordKey)
        return DesktopDocumentDraftRescue.previewTarget(root, database, source.owner) { directory, state ->
            source.preview(directory.root.toString(), database, state)
        }
    }

    fun importRecords(
        root: File, database: String, archive: File, recordKey: String,
        expectedManifestSha256: String, expectedTargetStateSha256: String,
    ): DesktopDocumentSpaceCreateRescuePreview {
        val source = readSource(archive, recordKey, expectedManifestSha256)
        return DesktopDocumentDraftRescue.importTarget(root, database, archive, source.owner, expectedManifestSha256,
            expectedTargetStateSha256, source.prepared.payload) { directory, state ->
            source.preview(directory.root.toString(), database, state)
        }
    }

    private fun readSource(archive: File, recordKey: String, expected: String? = null): Source =
        DesktopDocumentDraftRescue.readDocumentSource(archive, expected) { source, owner, sha ->
            Source(owner, sha, DocumentSpaceCreateRescue.prepare(source, recordKey))
        }

    private data class Source(
        val owner: LocalCacheDiagnosticOwner,
        val manifestSha256: String,
        val prepared: DocumentSpaceCreateRescuePrepared,
    ) {
        fun preview(root: String, database: String, state: String) = DesktopDocumentSpaceCreateRescuePreview(
            root, database, owner, prepared.metadata, manifestSha256, state,
        )
    }
}
