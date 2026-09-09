package com.virjar.tk.desktop

import com.virjar.tk.app.navigation.feature.document.DocumentDraftRescueException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Maintenance is selected before default data-root, logging, AWT, credentials or version initialization. */
internal fun runDesktopDocumentDraftRescueCommand(args: Array<String>, output: (String) -> Unit): Int? {
    val command = args.firstOrNull()
    val kind = when (command) {
        "list-document-draft-rescue", "preview-document-draft-rescue", "import-document-draft-rescue" -> DesktopDocumentRescueKind.DRAFT
        "list-document-create-rescue", "preview-document-create-rescue", "import-document-create-rescue" -> DesktopDocumentRescueKind.CREATE
        else -> return null
    }
    val action = command.substringBefore('-')
    return try {
        val keys = if (action == "list") setOf("archive") else
            setOf("cache-root", "database", "archive", "record-key") +
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
            output(json.encodeToString(DesktopDocumentDraftRescue.listRecords(File(options.getValue("archive")), kind)))
            return 0
        }
        val root = File(options.getValue("cache-root"))
        val archive = File(options.getValue("archive"))
        val database = options.getValue("database")
        val recordKey = options.getValue("record-key")
        val report = if (action == "preview") {
            DesktopDocumentDraftRescue.preview(root, database, archive, recordKey, kind)
        } else {
            val digest = options.getValue("confirm-manifest-sha256")
            val state = options.getValue("expected-target-state-sha256")
            require(digest.matches(Regex("[0-9a-f]{64}")) && state.matches(Regex("[0-9a-f]{64}")))
            DesktopDocumentDraftRescue.importDraft(root, database, archive, recordKey, digest, state, kind)
        }
        output(json.encodeToString(report))
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
