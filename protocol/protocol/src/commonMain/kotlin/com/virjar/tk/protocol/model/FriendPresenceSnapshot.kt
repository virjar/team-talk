package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.PresenceContractPolicy
import com.virjar.tk.protocol.ProtocolCorruptionException

/**
 * 已认证用户当前好友集合的完整权威 presence 视图。
 *
 * 集合被 canonical 化以保证线格式输出确定。解码还要求对端发送同样的 canonical 顺序，
 * 因此重复或乱序的线格式值无法为同一快照构造第二种表示。
 */
class FriendPresenceSnapshot(
    val serverEpoch: String,
    val revision: Long,
    friendUids: Collection<String>,
    onlineFriendUids: Collection<String>,
) : IProto {
    val friendUids: List<String>
    val onlineFriendUids: List<String>

    init {
        PresenceContractPolicy.requireServerEpoch(serverEpoch)
        require(revision >= 0L) { "presence snapshot revision must be non-negative" }
        requireCollectionBound(friendUids, "friendUids")
        requireCollectionBound(onlineFriendUids, "onlineFriendUids")
        friendUids.forEach { PresenceContractPolicy.requireUid(it, "presence.friendUids[]") }
        onlineFriendUids.forEach { PresenceContractPolicy.requireUid(it, "presence.onlineFriendUids[]") }

        this.friendUids = friendUids.toSortedSet().toList()
        this.onlineFriendUids = onlineFriendUids.toSortedSet().toList()
        val friendSet = this.friendUids.toHashSet()
        require(this.onlineFriendUids.all(friendSet::contains)) {
            "onlineFriendUids must be a subset of friendUids"
        }
    }

    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(serverEpoch)
        buf.writeVarLong(revision)
        writeUidCollection(buf, friendUids)
        writeUidCollection(buf, onlineFriendUids)
    }

    override fun equals(other: Any?): Boolean =
        other is FriendPresenceSnapshot &&
            serverEpoch == other.serverEpoch &&
            revision == other.revision &&
            friendUids == other.friendUids &&
            onlineFriendUids == other.onlineFriendUids

    override fun hashCode(): Int {
        var result = serverEpoch.hashCode()
        result = 31 * result + revision.hashCode()
        result = 31 * result + friendUids.hashCode()
        result = 31 * result + onlineFriendUids.hashCode()
        return result
    }

    override fun toString(): String =
        "FriendPresenceSnapshot(serverEpoch=$serverEpoch, revision=$revision, " +
            "friendUids=$friendUids, onlineFriendUids=$onlineFriendUids)"

    companion object : IProtoReader<FriendPresenceSnapshot> {
        const val MAX_FRIENDS = PresenceContractPolicy.MAX_FRIENDS_PER_SNAPSHOT

        override fun readFrom(buf: PacketBuffer): FriendPresenceSnapshot {
            val serverEpoch = PresenceContractPolicy.readServerEpoch(buf, "presenceSnapshot.serverEpoch")
            val revision = buf.readVarLong()
            val friendUids = readUidCollection(buf, "presenceSnapshot.friendUids")
            val onlineFriendUids = readUidCollection(buf, "presenceSnapshot.onlineFriendUids")
            requireCanonicalWireOrder(friendUids, "presenceSnapshot.friendUids")
            requireCanonicalWireOrder(onlineFriendUids, "presenceSnapshot.onlineFriendUids")
            return decodeSnapshotValue {
                FriendPresenceSnapshot(serverEpoch, revision, friendUids, onlineFriendUids)
            }
        }

        private fun requireCollectionBound(values: Collection<String>, fieldName: String) {
            require(values.size <= MAX_FRIENDS) { "$fieldName exceeds $MAX_FRIENDS entries" }
        }

        private fun writeUidCollection(buf: PacketBuffer, values: List<String>) {
            buf.writeVarInt(values.size)
            values.forEach(buf::writeString)
        }

        private fun readUidCollection(buf: PacketBuffer, fieldName: String): List<String> {
            val count = buf.readCollectionSize(
                maximum = MAX_FRIENDS,
                minimumBytesPerEntry = 2,
                fieldName = fieldName,
            )
            return List(count) { PresenceContractPolicy.readUid(buf, "$fieldName[]") }
        }

        private fun requireCanonicalWireOrder(values: List<String>, fieldName: String) {
            if (!values.zipWithNext().all { (left, right) -> left < right }) {
                throw ProtocolCorruptionException("$fieldName must be sorted and duplicate-free")
            }
        }

        private inline fun <T> decodeSnapshotValue(build: () -> T): T = try {
            build()
        } catch (corrupt: ProtocolCorruptionException) {
            throw corrupt
        } catch (invalid: IllegalArgumentException) {
            throw ProtocolCorruptionException(invalid.message ?: "Invalid friend presence snapshot")
        }
    }
}
