package com.virjar.tk.shared.client

import com.virjar.tk.protocol.body.ReplyBody
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.shared.database.AppDatabaseQueries

/** 与消息成功/丢弃事务共享锁和连接；只有最后一个持有方退出时才释放受管源。 */
internal fun releaseOutgoingChatAssets(queries: AppDatabaseQueries, chatId: String, clientMsgId: String) {
    val links = queries.selectOutgoingChatAssetLinks(chatId, clientMsgId).executeAsList()
    queries.deleteOutgoingChatAssets(chatId, clientMsgId)
    links.forEach { queries.deleteUnownedChatAsset(it.asset_id) }
}

internal fun releaseConfirmedOutgoingChatAssets(queries: AppDatabaseQueries) {
    queries.selectConfirmedOutgoingChatAssetLinks().executeAsList()
        .map { it.chat_id to it.client_msg_id }.distinct()
        .forEach { (chat, id) -> releaseOutgoingChatAssets(queries, chat, id) }
}

internal fun transferOutgoingChatAssets(queries: AppDatabaseQueries, oldClientMsgId: String, replacement: Message) {
    val retainedIds = when (val body = replacement.body) {
        is RichTextBody -> body.assets.map { it.assetId }
        is ReplyBody -> body.assets.map { it.assetId }
        else -> emptyList()
    }.toSet()
    queries.selectOutgoingChatAssetLinks(replacement.chatId, oldClientMsgId).executeAsList()
        .filter { it.asset_id in retainedIds }.forEach {
            queries.retainOutgoingChatAsset(replacement.chatId, replacement.clientMsgId, replacement.senderUid, it.asset_id)
        }
    releaseOutgoingChatAssets(queries, replacement.chatId, oldClientMsgId)
}
