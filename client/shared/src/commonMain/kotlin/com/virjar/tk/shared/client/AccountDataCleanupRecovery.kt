package com.virjar.tk.shared.client

/** 仅在进程启动、任何账号资源打开之前调用；失败保留标记并阻止工作区启动。 */
fun resumePendingAccountCleanup(cleanup: AccountDataCleanup, clearCredentials: (AccountDataOwner) -> Unit) {
    cleanup.pendingOwners().forEach { owner ->
        cleanup.deleteOwnedData(owner)
        clearCredentials(owner)
        cleanup.complete(owner)
    }
}
