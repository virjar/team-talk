package com.virjar.tk.desktop

import com.virjar.tk.app.navigation.feature.document.DocumentDraftRescueException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

private enum class DocumentRescueCommandFamily(val suffix: String, val selectionKey: String = "record-key") {
    DRAFT("document-draft-rescue"),
    CREATE("document-create-rescue"),
    SPACE_CREATE("document-space-create-rescue"),
    TREE_CREATE("document-tree-create-rescue", "space-id"),
}

/** Maintenance is selected before default data-root, logging, AWT, credentials or version initialization. */
internal fun runDesktopDocumentDraftRescueCommand(args: Array<String>, output: (String) -> Unit): Int? {
    val command = args.firstOrNull() ?: return null
    val action = command.substringBefore('-')
    if (action !in setOf("list", "preview", "import")) return null
    val family = DocumentRescueCommandFamily.entries.firstOrNull { command == "$action-${it.suffix}" } ?: return null
    return try {
        val keys = if (action == "list") setOf("archive") else
            setOf("cache-root", "database", "archive", family.selectionKey) +
            if (action == "import") setOf("confirm-manifest-sha256", "expected-target-state-sha256") else emptySet()
        val options = linkedMapOf<String, String>()
        require((args.size - 1) % 2 == 0)
        var index = 1
        while (index < args.size) {
            val key = args[index].removePrefix("--")
            require(args[index].startsWith("--") && key in keys && key !in options)
            require(args[index + 1].isNotBlank() && !args[index + 1].startsWith("--"))
            options[key] = args[index + 1]
            index += 2
        }
        require(options.keys == keys)
        val json = Json { encodeDefaults = true; prettyPrint = true }
        if (action == "list") {
            val archive = File(options.getValue("archive"))
            output(when (family) {
                DocumentRescueCommandFamily.TREE_CREATE -> json.encodeToString(DesktopDocumentTreeCreateRescue.listSpaces(archive))
                DocumentRescueCommandFamily.SPACE_CREATE -> json.encodeToString(DesktopDocumentSpaceCreateRescue.listRecords(archive))
                DocumentRescueCommandFamily.DRAFT -> json.encodeToString(DesktopDocumentDraftRescue.listRecords(archive))
                DocumentRescueCommandFamily.CREATE -> json.encodeToString(DesktopDocumentDraftRescue.listRecords(archive, DesktopDocumentRescueKind.CREATE))
            })
            return 0
        }
        val root = File(options.getValue("cache-root"))
        val archive = File(options.getValue("archive"))
        val database = options.getValue("database")
        val selection = options.getValue(family.selectionKey)
        val confirmation = if (action == "import") {
            val digest = options.getValue("confirm-manifest-sha256")
            val state = options.getValue("expected-target-state-sha256")
            require(digest.matches(Regex("[0-9a-f]{64}")) && state.matches(Regex("[0-9a-f]{64}")))
            digest to state
        } else null
        when (family) {
            DocumentRescueCommandFamily.TREE_CREATE -> {
                val report = if (confirmation == null) DesktopDocumentTreeCreateRescue.preview(root, database, archive, selection)
                    else DesktopDocumentTreeCreateRescue.importRecords(root, database, archive, selection, confirmation.first, confirmation.second)
                output(json.encodeToString(report))
            }
            DocumentRescueCommandFamily.SPACE_CREATE -> {
                val report = if (confirmation == null) DesktopDocumentSpaceCreateRescue.preview(root, database, archive, selection)
                    else DesktopDocumentSpaceCreateRescue.importRecords(root, database, archive, selection, confirmation.first, confirmation.second)
                output(json.encodeToString(report))
            }
            DocumentRescueCommandFamily.DRAFT, DocumentRescueCommandFamily.CREATE -> {
                val kind = if (family == DocumentRescueCommandFamily.DRAFT) DesktopDocumentRescueKind.DRAFT else DesktopDocumentRescueKind.CREATE
                val report = if (confirmation == null) DesktopDocumentDraftRescue.preview(root, database, archive, selection, kind)
                    else DesktopDocumentDraftRescue.importDraft(root, database, archive, selection, confirmation.first, confirmation.second, kind)
                output(json.encodeToString(report))
            }
        }
        0
    } catch (failure: Exception) {
        // Never print parser/IO exception details: they can contain archived document bodies or paths.
        val code = when (failure) {
            is DesktopDocumentDraftRescueFailure -> failure.code
            is DocumentDraftRescueException -> "SOURCE_${failure.reason}"
            else -> failure.message?.takeIf { it.matches(Regex("Local cache document draft rescue failed: [A-Z_]+")) }
                ?: "INVALID_ARGUMENTS_OR_DOCUMENT_DRAFT_UNAVAILABLE"
        }
        output("Document draft rescue failed: $code")
        2
    }
}
