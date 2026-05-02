package com.virjar.tk.database

import com.virjar.tk.protocol.ChannelType
import com.virjar.tk.dto.*
import com.virjar.tk.protocol.PacketType
import com.virjar.tk.protocol.payload.*
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import kotlinx.serialization.json.*

class LocalCache(private val queries: DatabaseQueries) {

    // ── Messages ──

    fun getMessages(channelId: String, limit: Int = 50): List<Message> {
        return queries.selectAllMessages(channelId, limit.toLong()).executeAsList()
            .mapNotNull { it.toMessage() }
    }

    fun getMessagesAfterSeq(channelId: String, afterSeq: Long, limit: Int = 50): List<Message> {
        return queries.selectMessagesAfterSeq(channelId, afterSeq, limit.toLong()).executeAsList()
            .mapNotNull { it.toMessage() }
    }

    fun getMessagesBeforeSeq(channelId: String, beforeSeq: Long, limit: Int = 50): List<Message> {
        return queries.selectMessagesBeforeSeq(channelId, beforeSeq, limit.toLong()).executeAsList()
            .mapNotNull { it.toMessage() }
    }

    fun getMaxSeq(channelId: String): Long {
        return queries.selectMaxSeq(channelId).executeAsOneOrNull()?.MAX ?: 0L
    }

    fun insertMessage(msg: Message) {
        val bodyBytes = bodyToBytes(msg.body)
        queries.insertMessage(
            channel_id = msg.channelId,
            seq = msg.serverSeq,
            message_id = msg.messageId ?: "",
            sender_uid = msg.senderUid ?: "",
            sender_name = "",
            packet_type = msg.packetType.code.toLong(),
            body = bodyBytes,
            flags = msg.flags.toLong(),
            created_at = msg.timestamp,
        )
    }

    fun updateMessageId(oldMessageId: String, newMessageId: String, newSeq: Long) {
        queries.updateMessageId(newMessageId, newSeq, oldMessageId)
    }

    fun deleteMessage(messageId: String) {
        queries.deleteMessageByMessageId(messageId)
    }

    fun markLocalDeleted(messageId: String) {
        queries.markLocalDeleted(messageId)
    }

    fun updateMessagePayload(messageId: String, newText: String) {
        val newBody = TextBody(newText, emptyList())
        queries.updateMessagePayload(bodyToBytes(newBody), messageId)
    }

    // ── Conversations ──

    fun getAllConversations(): List<ConversationDto> {
        return queries.selectAllConversations().executeAsList().map { it.toDto() }
    }

    fun getConversation(channelId: String): ConversationDto? {
        return queries.selectConversation(channelId).executeAsOneOrNull()?.toDto()
    }

    fun insertConversation(dto: ConversationDto) {
        queries.insertConversation(
            channel_id = dto.channelId,
            channel_type = dto.channelType.toLong(),
            channel_name = dto.channelName,
            avatar_url = "",
            last_message = dto.lastMessage,
            last_message_time = dto.lastMsgTimestamp,
            last_seq = dto.lastMsgSeq,
            last_message_type = dto.lastMessageType.toLong(),
            unread_count = dto.unreadCount.toLong(),
            is_pinned = if (dto.isPinned) 1L else 0L,
            is_muted = if (dto.isMuted) 1L else 0L,
            draft_text = dto.draft,
            draft_updated_at = 0L,
            version = dto.version,
        )
    }

    fun insertConversations(dtos: List<ConversationDto>) {
        queries.transaction {
            dtos.forEach { insertConversation(it) }
        }
    }

    fun updateLastMessage(channelId: String, lastMessage: String, timestamp: Long, lastSeq: Long) {
        queries.updateLastMessage(lastMessage, timestamp, lastSeq, channelId)
    }

    fun updateUnreadCount(channelId: String, count: Long) {
        queries.updateUnreadCount(count, channelId)
    }

    fun incrementUnread(channelId: String) {
        queries.incrementUnread(channelId)
    }

    fun updatePin(channelId: String, pinned: Boolean) {
        queries.updatePin(if (pinned) 1L else 0L, channelId)
    }

    fun updateMute(channelId: String, muted: Boolean) {
        queries.updateMute(if (muted) 1L else 0L, channelId)
    }

    fun updateDraft(channelId: String, draft: String) {
        queries.updateDraft(draft, System.currentTimeMillis(), channelId)
    }

    fun clearExpiredDrafts() {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000 // 30 天
        queries.clearExpiredDrafts(cutoff)
    }

    fun deleteConversation(channelId: String) {
        queries.deleteConversation(channelId)
    }

    // ── Contacts ──

    fun getAllContacts(): List<FriendDto> {
        return queries.selectAllContacts().executeAsList().map { it.toDto() }
    }

    fun insertContacts(dtos: List<FriendDto>) {
        queries.transaction {
            dtos.forEach { insertContact(it) }
        }
    }

    fun deleteContact(uid: String) {
        queries.deleteContact(uid)
    }

    // ── Channels ──

    fun getAllChannels(): List<ChannelDto> {
        return queries.selectAllChannels().executeAsList().map { it.toDto() }
    }

    fun getChannel(channelId: String): ChannelDto? {
        return queries.selectChannel(channelId).executeAsOneOrNull()?.toDto()
    }

    fun insertChannel(dto: ChannelDto) {
        queries.insertChannel(
            channel_id = dto.channelId,
            channel_type = dto.channelType.toLong(),
            name = dto.name,
            avatar_url = dto.avatar,
            owner_uid = dto.creator ?: "",
            member_count = dto.memberCount.toLong(),
            created_at = 0L,
            updated_at = System.currentTimeMillis(),
        )
    }

    fun insertChannels(dtos: List<ChannelDto>) {
        queries.transaction {
            dtos.forEach { insertChannel(it) }
        }
    }

    // ── Read Positions ──

    fun getReadSeq(channelId: String): Long {
        return queries.selectReadPosition(channelId).executeAsOneOrNull()?.read_seq
            ?: 0L
    }

    fun updateReadSeq(channelId: String, readSeq: Long) {
        queries.insertReadPosition(channelId, readSeq)
    }

    // ── Cleanup ──

    fun deleteExpiredMessages(cutoffTimestamp: Long) {
        queries.deleteExpiredMessages(cutoffTimestamp)
    }

    // ── 内部映射方法 ──

    private fun Messages.toMessage(): Message? {
        val packetType = PacketType.fromCode(packet_type.toInt().toByte()) ?: return null
        val bodyBytes = body
        val header = MessageHeader(
            channelId = channel_id,
            clientMsgNo = "",
            clientSeq = 0,
            messageId = message_id,
            senderUid = sender_uid,
            channelType = ChannelType.PERSONAL,
            serverSeq = seq,
            timestamp = created_at,
            flags = flags.toInt(),
        )
        val body = bytesToBody(bodyBytes, packetType) ?: return null
        return Message(header, body)
    }

    private fun Conversations.toDto() = ConversationDto(
        channelId = channel_id,
        channelType = channel_type.toInt(),
        lastMsgSeq = last_seq,
        unreadCount = unread_count.toInt(),
        readSeq = 0,
        isMuted = is_muted != 0L,
        isPinned = is_pinned != 0L,
        draft = draft_text,
        version = version,
        channelName = channel_name,
        lastMessage = last_message,
        lastMsgTimestamp = last_message_time,
        lastMessageType = last_message_type.toInt(),
    )

    private fun Contacts.toDto() = FriendDto(
        uid = "",
        friendUid = uid,
        friendName = name,
        remark = remark,
    )

    private fun Channels.toDto() = ChannelDto(
        channelId = channel_id,
        channelType = channel_type.toInt(),
        name = name,
        avatar = avatar_url,
        creator = owner_uid,
        memberCount = member_count.toInt(),
    )

    private fun insertContact(dto: FriendDto) {
        queries.insertContact(
            uid = dto.friendUid,
            name = dto.friendName,
            avatar_url = "",
            remark = dto.remark,
            is_friend = 1L,
            is_blacklisted = 0L,
            updated_at = System.currentTimeMillis(),
        )
    }

    companion object {
        fun bodyToBytes(body: MessageBody): ByteArray {
            val buf: ByteBuf = Unpooled.buffer()
            try {
                body.writeTo(buf)
                val bytes = ByteArray(buf.readableBytes())
                buf.readBytes(bytes)
                return bytes
            } finally {
                buf.release()
            }
        }

        fun bytesToBody(bytes: ByteArray, packetType: PacketType): MessageBody? {
            val creator = PacketType.bodyCreatorFor<MessageBody>(packetType) ?: return null
            val buf: ByteBuf = Unpooled.wrappedBuffer(bytes)
            try {
                return creator.create(buf)
            } finally {
                buf.release()
            }
        }
    }
}
