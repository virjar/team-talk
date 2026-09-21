package com.virjar.tk.shared.repository

import com.virjar.tk.shared.client.AccountDataOwner
import com.virjar.tk.shared.platform.PlatformFile

actual fun createChatAssetSpool(
    dataDir: PlatformFile,
    owner: AccountDataOwner,
    quotaBytes: Long,
    maxEntries: Int,
): ChatAssetSpool = DurableChatAssetSpool(
    chatAssetSpoolStorage(dataDir, chatAssetSpoolDirectories(owner)), quotaBytes, maxEntries,
)
