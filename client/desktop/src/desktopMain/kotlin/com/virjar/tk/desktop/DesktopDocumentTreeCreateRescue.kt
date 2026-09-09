package com.virjar.tk.desktop

import com.virjar.tk.app.navigation.feature.document.DocumentTreeCreateRescue
import com.virjar.tk.app.navigation.feature.document.DocumentTreeCreateRescueMetadata
import com.virjar.tk.app.navigation.feature.document.DocumentTreeCreateRescuePrepared
import com.virjar.tk.shared.client.LocalCacheDiagnosticOwner
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
internal data class DesktopDocumentTreeCreateRescueSpaces(
    val owner: LocalCacheDiagnosticOwner,
    val manifestSha256: String,
    val spaceIds: List<String>,
    val note: String = "Spaces with admitted document creation requests. Preview validates the selected workset and the empty target",
)

@Serializable
internal data class DesktopDocumentTreeCreateRescuePreview(
    val root: String,
    val database: String,
    val owner: LocalCacheDiagnosticOwner,
    val selection: DocumentTreeCreateRescueMetadata,
    val manifestSha256: String,
    val targetStateSha256: String,
    val consequence: String = "Restore all live drafts and original admitted document creation requests in the selected existing space. " +
        "Opening the client retries those requests in ancestor order when the space is available. No space creation request is restored. " +
        "Unsubmitted drafts and later edits remain unsaved. Other spaces remain in the archive. " +
        "Missing access or assets may leave requests pending. No other operation or unfinished upload is restored. " +
        "The archive and original files remain unchanged",
)

/** A selected existing space uses the same source copy, target transaction and atomic writer as other rescue commands. */
internal object DesktopDocumentTreeCreateRescue {
    fun listSpaces(archive: File): DesktopDocumentTreeCreateRescueSpaces =
        DesktopDocumentDraftRescue.readDocumentSource(archive) { source, owner, sha ->
            DesktopDocumentTreeCreateRescueSpaces(owner, sha, DocumentTreeCreateRescue.spaceIds(source))
        }

    fun preview(root: File, database: String, archive: File, spaceId: String): DesktopDocumentTreeCreateRescuePreview {
        val source = readSource(archive, spaceId)
        return DesktopDocumentDraftRescue.previewTarget(root, database, source.owner) { directory, state ->
            source.preview(directory.root.toString(), database, state)
        }
    }

    fun importRecords(
        root: File, database: String, archive: File, spaceId: String,
        expectedManifestSha256: String, expectedTargetStateSha256: String,
    ): DesktopDocumentTreeCreateRescuePreview {
        val source = readSource(archive, spaceId, expectedManifestSha256)
        return DesktopDocumentDraftRescue.importTarget(root, database, archive, source.owner, expectedManifestSha256,
            expectedTargetStateSha256, source.prepared.payload) { directory, state ->
            source.preview(directory.root.toString(), database, state)
        }
    }

    private fun readSource(archive: File, spaceId: String, expected: String? = null): Source =
        DesktopDocumentDraftRescue.readDocumentSource(archive, expected) { source, owner, sha ->
            Source(owner, sha, DocumentTreeCreateRescue.prepare(source, spaceId))
        }

    private data class Source(
        val owner: LocalCacheDiagnosticOwner,
        val manifestSha256: String,
        val prepared: DocumentTreeCreateRescuePrepared,
    ) {
        fun preview(root: String, database: String, state: String) = DesktopDocumentTreeCreateRescuePreview(
            root, database, owner, prepared.metadata, manifestSha256, state,
        )
    }
}
