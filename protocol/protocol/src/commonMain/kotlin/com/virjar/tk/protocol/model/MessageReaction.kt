package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.body.MessageBodyPolicy
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.ProtocolCorruptionException

/**
 * 服务端权威的 单消息 × 单表情 回应聚合。
 *
 * reactorUids 由服务端快照去重并按字典序输出；解码侧要求同一 canonical 顺序，
 * 重复或乱序 wire 不能为同一消息构造第二种聚合表示。聚合计数永远来自服务端，
 * 客户端只投影、不自行合并增删语义。
 */
class MessageReactionGroup(
    val emoji: String,
    reactorUids: List<String>,
) : IProto {
    val reactorUids: List<String>

    init {
        MessageBodyPolicy.requireReactionEmoji(emoji, "reaction.emoji")
        require(reactorUids.size <= MAX_REACTORS_PER_GROUP) {
            "reaction.reactorUids exceeds $MAX_REACTORS_PER_GROUP entries"
        }
        reactorUids.forEach { requireUid(it) }
        val canonical = reactorUids.toSortedSet().toList()
        require(canonical.size == reactorUids.size) { "reaction.reactorUids must be duplicate-free" }
        this.reactorUids = canonical
    }

    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(emoji)
        buf.writeVarInt(reactorUids.size)
        reactorUids.forEach(buf::writeString)
    }

    override fun equals(other: Any?): Boolean =
        other is MessageReactionGroup &&
            emoji == other.emoji &&
            reactorUids == other.reactorUids

    override fun hashCode(): Int = 31 * emoji.hashCode() + reactorUids.hashCode()

    override fun toString(): String =
        "MessageReactionGroup(emoji=$emoji, reactorUids=${reactorUids.size})"

    companion object : IProtoReader<MessageReactionGroup> {
        const val MAX_REACTORS_PER_GROUP = GroupPolicy.MAX_MEMBERS
        private const val MAX_UID_LENGTH = 36

        override fun readFrom(buf: PacketBuffer): MessageReactionGroup {
            val emoji = buf.readRequiredString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_EMOJI_LENGTH),
            )
            val count = buf.readCollectionSize(
                maximum = MAX_REACTORS_PER_GROUP,
                minimumBytesPerEntry = 2,
                fieldName = "reaction.reactorUids",
            )
            val uids = List(count) {
                buf.readRequiredString(
                    MessageBodyPolicy.utf8WireLimit(MAX_UID_LENGTH),
                )
            }
            if (!uids.zipWithNext().all { (left, right) -> left < right }) {
                throw ProtocolCorruptionException("reaction.reactorUids must be sorted and duplicate-free")
            }
            return decodeValue { MessageReactionGroup(emoji, uids) }
        }

        private fun requireUid(uid: String) {
            require(uid.length in 1..MAX_UID_LENGTH) { "reaction reactor uid is invalid" }
        }

        private inline fun <T> decodeValue(build: () -> T): T = try {
            build()
        } catch (corrupt: ProtocolCorruptionException) {
            throw corrupt
        } catch (invalid: IllegalArgumentException) {
            throw ProtocolCorruptionException(invalid.message ?: "Invalid message reaction group")
        }
    }
}

/**
 * 一个 serverSeq 的全部回应聚合快照。groups 按 emoji 字典序 canonical 输出。
 */
class MessageReactionSummary(
    val serverSeq: Long,
    groups: List<MessageReactionGroup>,
) : IProto {
    val groups: List<MessageReactionGroup>

    init {
        require(serverSeq > 0L) { "reaction summary serverSeq must be positive" }
        require(groups.size <= MAX_GROUPS_PER_SUMMARY) {
            "reaction summary exceeds $MAX_GROUPS_PER_SUMMARY groups"
        }
        val canonical = groups.sortedBy(MessageReactionGroup::emoji)
        canonical.zipWithNext().forEach { (left, right) ->
            require(left.emoji < right.emoji) { "reaction summary groups must be unique per emoji" }
        }
        this.groups = canonical
    }

    override fun writeTo(buf: PacketBuffer) {
        buf.writeVarLong(serverSeq)
        buf.writeVarInt(groups.size)
        groups.forEach { it.writeTo(buf) }
    }

    override fun equals(other: Any?): Boolean =
        other is MessageReactionSummary &&
            serverSeq == other.serverSeq &&
            groups == other.groups

    override fun hashCode(): Int = 31 * serverSeq.hashCode() + groups.hashCode()

    override fun toString(): String =
        "MessageReactionSummary(serverSeq=$serverSeq, groups=${groups.size})"

    companion object : IProtoReader<MessageReactionSummary> {
        const val MAX_GROUPS_PER_SUMMARY = 16_384

        override fun readFrom(buf: PacketBuffer): MessageReactionSummary {
            val serverSeq = buf.readVarLong()
            val count = buf.readCollectionSize(
                maximum = MAX_GROUPS_PER_SUMMARY,
                fieldName = "reaction.summary.groups",
            )
            val groups = List(count) { MessageReactionGroup.readFrom(buf) }
            return decodeValue { MessageReactionSummary(serverSeq, groups) }
        }

        private inline fun <T> decodeValue(build: () -> T): T = try {
            build()
        } catch (corrupt: ProtocolCorruptionException) {
            throw corrupt
        } catch (invalid: IllegalArgumentException) {
            throw ProtocolCorruptionException(invalid.message ?: "Invalid message reaction summary")
        }
    }
}
