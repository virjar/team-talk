package com.virjar.tk.desktop

import com.virjar.tk.app.navigation.feature.document.DocumentDraftOwnerKey
import com.virjar.tk.desktop.media.desktopMediaNamespace
import com.virjar.tk.shared.client.AccountDataCleanup
import com.virjar.tk.shared.client.AccountDataCleanupTarget
import com.virjar.tk.shared.client.accountDiagnosticCleanupTargets
import java.io.File

/** Desktop 安装根已经认领且持有进程锁；此处只列出精确账号拥有的目录。 */
internal fun desktopAccountDataCleanup(dataDir: File): AccountDataCleanup = AccountDataCleanup(dataDir) { owner ->
    val users = listOf("deployments", owner.deploymentFingerprint, "datasets", owner.datasetId, "users")
    val draftOwner = DocumentDraftOwnerKey(owner.deploymentFingerprint, owner.datasetId, owner.uid)
    buildList {
        add(AccountDataCleanupTarget.tree(
            dataDir, *com.virjar.tk.shared.repository.chatAssetSpoolDirectories(owner).toTypedArray(),
        ))
        add(AccountDataCleanupTarget.tree(dataDir, *(users + owner.uid).toTypedArray()))
        add(AccountDataCleanupTarget.matchingChildren(
            dataDir, users, Regex("${Regex.escape(owner.uid)}\\.corrupt-[A-Za-z0-9-]+"),
        ))
        add(AccountDataCleanupTarget.tree(
            dataDir, "media_e2", desktopMediaNamespace(owner.deploymentFingerprint, owner.datasetId, owner.uid),
        ))
        add(AccountDataCleanupTarget.tree(
            dataDir,
            DesktopDocumentDraftPersistence.DRAFTS_DIRECTORY,
            DesktopDocumentDraftPersistence.STORAGE_VERSION_DIRECTORY,
            DesktopDocumentDraftPersistence.DEPLOYMENTS_DIRECTORY,
            owner.deploymentFingerprint,
            DesktopDocumentDraftPersistence.OWNERS_DIRECTORY,
            desktopDocumentDraftOwnerNamespace(draftOwner),
        ))
        addAll(accountDiagnosticCleanupTargets(dataDir, owner))
    }
}
