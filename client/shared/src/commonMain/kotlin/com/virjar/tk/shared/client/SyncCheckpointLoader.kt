package com.virjar.tk.shared.client

import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.Contact
import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.ConversationCapacityPolicy
import com.virjar.tk.protocol.model.SyncCheckpointPageRequest
import com.virjar.tk.protocol.rpc.def.SyncRpc

/** 收集一个完整权威服务器检查点的可测试边界。 */
fun interface ServerCheckpointLoader {
    suspend fun load(
        expectedDatasetId: String,
        expectedOwnerUid: String,
        reportProgress: () -> Unit,
    ): ServerProjectionCheckpoint
}

/** 收集一个连接绑定的检查点，而不在本地发布任何部分页。 */
internal class SyncCheckpointLoader(
    private val rpc: SyncRpc,
) : ServerCheckpointLoader {
    override suspend fun load(
        expectedDatasetId: String,
        expectedOwnerUid: String,
        reportProgress: () -> Unit,
    ): ServerProjectionCheckpoint {
        val header = rpc.beginCheckpoint(expectedDatasetId)
        check(header.datasetId == expectedDatasetId) {
            "Checkpoint header belongs to another dataset"
        }
        check(header.currentUser.uid == expectedOwnerUid) {
            "Checkpoint header belongs to another account"
        }
        reportProgress()

        val contacts = collectContacts(header.checkpointId, expectedOwnerUid, reportProgress)
        val chats = collectChats(header.checkpointId, reportProgress)
        val conversations = collectConversations(header.checkpointId, reportProgress)
        val chatIds = chats.mapTo(hashSetOf(), Chat::chatId)
        check(conversations.all { it.chatId in chatIds }) {
            "Checkpoint conversation has no accessible Chat projection"
        }
        return ServerProjectionCheckpoint(
            datasetId = header.datasetId,
            baseEventId = header.baseEventId,
            currentUser = header.currentUser,
            contacts = contacts,
            chats = chats,
            conversations = conversations,
        )
    }

    private suspend fun collectContacts(
        checkpointId: String,
        expectedOwnerUid: String,
        reportProgress: () -> Unit,
    ): List<Contact> {
        val items = ArrayList<Contact>()
        collectPages(
            maximumItems = MAX_CHECKPOINT_CONTACTS,
            keyOf = Contact::friendUid,
            fetch = { cursor ->
                rpc.listCheckpointContacts(SyncCheckpointPageRequest(checkpointId, cursor))
                    .let { it.items to it.nextCursor }
            },
            accept = { page ->
                check(page.all { it.uid == expectedOwnerUid }) {
                    "Checkpoint contact belongs to another account"
                }
                check(page.all { it.user?.uid == it.friendUid }) {
                    "Checkpoint contact is missing its authoritative user"
                }
                items += page
            },
            reportProgress = reportProgress,
        )
        return items
    }

    private suspend fun collectChats(
        checkpointId: String,
        reportProgress: () -> Unit,
    ): List<Chat> {
        val items = ArrayList<Chat>()
        collectPages(
            maximumItems = ConversationCapacityPolicy.MAX_CONVERSATIONS_PER_USER,
            keyOf = Chat::chatId,
            fetch = { cursor ->
                rpc.listCheckpointChats(SyncCheckpointPageRequest(checkpointId, cursor))
                    .let { it.items to it.nextCursor }
            },
            accept = { items += it },
            reportProgress = reportProgress,
        )
        return items
    }

    private suspend fun collectConversations(
        checkpointId: String,
        reportProgress: () -> Unit,
    ): List<Conversation> {
        val items = ArrayList<Conversation>()
        collectPages(
            maximumItems = ConversationCapacityPolicy.MAX_CONVERSATIONS_PER_USER,
            keyOf = Conversation::chatId,
            fetch = { cursor ->
                rpc.listCheckpointConversations(SyncCheckpointPageRequest(checkpointId, cursor))
                    .let { it.items to it.nextCursor }
            },
            accept = { items += it },
            reportProgress = reportProgress,
        )
        return items
    }

    private suspend fun <T> collectPages(
        maximumItems: Int,
        keyOf: (T) -> String,
        fetch: suspend (String?) -> Pair<List<T>, String?>,
        accept: (List<T>) -> Unit,
        reportProgress: () -> Unit,
    ) {
        val identities = HashSet<String>()
        val cursors = HashSet<String>()
        var cursor: String? = null
        var count = 0
        while (true) {
            val (page, nextCursor) = fetch(cursor)
            page.forEach { item ->
                check(identities.add(keyOf(item))) { "Checkpoint pages contain a duplicate identity" }
            }
            count += page.size
            check(count <= maximumItems) {
                "Checkpoint contains more than $maximumItems items"
            }
            accept(page)
            reportProgress()
            if (nextCursor == null) return
            check(nextCursor != cursor && cursors.add(nextCursor)) {
                "Checkpoint cursor did not advance"
            }
            cursor = nextCursor
        }
    }

    private companion object {
        /** 镜像服务器硬性 Contact 聚合预算。 */
        const val MAX_CHECKPOINT_CONTACTS = 4_000
    }
}
