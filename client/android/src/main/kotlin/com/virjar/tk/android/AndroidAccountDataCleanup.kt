package com.virjar.tk.android

import android.content.Context
import com.virjar.tk.app.navigation.feature.document.DocumentDraftOwnerKey
import com.virjar.tk.shared.client.AccountDataCleanup
import com.virjar.tk.shared.client.AccountDataCleanupTarget
import com.virjar.tk.shared.client.AccountDataOwner
import com.virjar.tk.shared.client.accountAndroidDatabaseCleanupTarget
import com.virjar.tk.shared.client.accountDiagnosticCleanupTargets
import java.util.concurrent.TimeUnit

/**
 * 共享认证控制器先退役会话，再在 IO 线程调用此钩子，最后才删除下方列出的磁盘资料。
 * 进程级草稿写入器跨 Activity 存活，必须确认该 owner 的删除请求已落盘，不能只删除文件。
 */
internal fun AndroidDocumentDraftPersistence.discardBannedAccountDrafts(owner: AccountDataOwner) {
    val draftOwner = DocumentDraftOwnerKey(owner.deploymentFingerprint, owner.datasetId, owner.uid)
    check(delete(draftOwner)) { "Account draft deletion was not accepted" }
    check(requestFlush().get(30, TimeUnit.SECONDS)) { "Account draft writer did not drain" }
}

/** Application 启动前或账号资源及草稿写入器排空后使用；不清整个 sandbox 或共享缓存。 */
internal fun androidAccountDataCleanup(context: Context): AccountDataCleanup {
    val app = context.applicationContext
    return AccountDataCleanup(app.noBackupFilesDir) { owner ->
        val draftOwner = DocumentDraftOwnerKey(owner.deploymentFingerprint, owner.datasetId, owner.uid)
        val draftHash = AndroidDocumentDraftPersistence.draftFileName(draftOwner).removeSuffix(".json")
        val mediaScope = sha256Hex(mediaCacheNamespace(owner.deploymentFingerprint, owner.datasetId, owner.uid)).take(32)
        buildList {
            add(accountAndroidDatabaseCleanupTarget(app.getDatabasePath("unused").parentFile!!, owner))
            add(accountAndroidDatabaseCleanupTarget(app.cacheDir, owner))
            add(AccountDataCleanupTarget.matchingChildren(
                app.noBackupFilesDir,
                listOf(ANDROID_DOCUMENT_DRAFT_DIRECTORY),
                Regex("${Regex.escape(draftHash)}\\..+"),
            ))
            add(AccountDataCleanupTarget.tree(app.cacheDir, "teamtalk-media", mediaScope))
            add(AccountDataCleanupTarget.tree(app.cacheDir, "teamtalk-media", "attachments", mediaScope))
            addAll(accountDiagnosticCleanupTargets(app.filesDir, owner))
            // 未捕获异常处理器使用 getDir("teamtalk")，正常遥测则使用 filesDir。
            addAll(accountDiagnosticCleanupTargets(app.getDir("teamtalk", Context.MODE_PRIVATE), owner))
        }
    }
}
