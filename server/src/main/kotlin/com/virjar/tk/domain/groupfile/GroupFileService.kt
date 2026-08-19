package com.virjar.tk.domain.groupfile

import com.virjar.tk.body.AttachmentPolicy
import com.virjar.tk.domain.attachment.AttachmentCatalog
import com.virjar.tk.domain.chat.ActiveChatMembership
import com.virjar.tk.domain.chat.ChatRepository
import com.virjar.tk.model.Attachment
import com.virjar.tk.model.ChatType
import com.virjar.tk.model.GroupFileEntry
import com.virjar.tk.model.GroupFileVersion
import java.util.Locale
import java.util.UUID

/**
 * 群共享文件领域服务。
 *
 * 群成员资格在每次读写时实时判断；上传者只能把自己刚上传且元数据完全匹配的附件
 * 发布到文件空间。历史版本不可变且计入配额，避免“替换文件”成为绕过容量限制的入口。
 */
class GroupFileService(
    private val repository: GroupFileRepository,
    private val chats: ChatRepository,
    private val memberships: ActiveChatMembership,
    private val attachments: AttachmentCatalog,
    private val quotaBytes: Long = DEFAULT_QUOTA_BYTES,
) {
    fun list(actorUid: String, chatId: String, parentId: String?): List<GroupFileEntry> {
        requireMember(actorUid, chatId)
        requireParent(chatId, parentId)
        return repository.list(chatId, parentId)
    }

    fun createFolder(actorUid: String, chatId: String, parentId: String?, name: String): GroupFileEntry {
        requireMember(actorUid, chatId)
        requireParent(chatId, parentId)
        val now = System.currentTimeMillis()
        val entry = GroupFileEntry(
            entryId = UUID.randomUUID().toString(),
            chatId = chatId,
            parentId = parentId,
            kind = GroupFileEntry.KIND_FOLDER,
            name = validateName(name),
            createdBy = actorUid,
            createdAt = now,
            updatedBy = actorUid,
            updatedAt = now,
        )
        return repository.create(entry, null, quotaBytes)
    }

    fun createFile(
        actorUid: String,
        chatId: String,
        parentId: String?,
        name: String,
        declared: Attachment,
    ): GroupFileEntry {
        requireMember(actorUid, chatId)
        requireParent(chatId, parentId)
        val attachment = resolveOwnedAttachment(actorUid, declared)
        requireQuota(chatId, attachment.size)
        val now = System.currentTimeMillis()
        val entryId = UUID.randomUUID().toString()
        val entry = GroupFileEntry(
            entryId = entryId,
            chatId = chatId,
            parentId = parentId,
            kind = GroupFileEntry.KIND_FILE,
            name = validateName(name),
            attachment = attachment,
            contentVersion = 1,
            createdBy = actorUid,
            createdAt = now,
            updatedBy = actorUid,
            updatedAt = now,
        )
        return repository.create(
            entry,
            GroupFileVersion(entryId, 1, attachment, actorUid, now),
            quotaBytes,
        )
    }

    fun addVersion(
        actorUid: String,
        chatId: String,
        entryId: String,
        declared: Attachment,
        expectedRevision: Long,
    ): GroupFileEntry {
        requireMember(actorUid, chatId)
        val entry = requireEntry(chatId, entryId)
        require(entry.kind == GroupFileEntry.KIND_FILE) { "目录不能添加文件版本" }
        val attachment = resolveOwnedAttachment(actorUid, declared)
        requireQuota(chatId, attachment.size)
        val now = System.currentTimeMillis()
        return repository.appendVersion(
            entryId,
            expectedRevision,
            GroupFileVersion(entryId, entry.contentVersion + 1, attachment, actorUid, now),
            actorUid,
            quotaBytes,
        )
    }

    fun listVersions(actorUid: String, chatId: String, entryId: String): List<GroupFileVersion> {
        requireMember(actorUid, chatId)
        val entry = requireEntry(chatId, entryId)
        require(entry.kind == GroupFileEntry.KIND_FILE) { "目录没有文件版本" }
        return repository.listVersions(entryId)
    }

    fun rename(actorUid: String, chatId: String, entryId: String, name: String, expectedRevision: Long): GroupFileEntry {
        requireMember(actorUid, chatId)
        requireEntry(chatId, entryId)
        return repository.rename(entryId, expectedRevision, validateName(name), actorUid)
    }

    fun delete(actorUid: String, chatId: String, entryId: String, expectedRevision: Long) {
        requireMember(actorUid, chatId)
        val entry = requireEntry(chatId, entryId)
        require(entry.kind != GroupFileEntry.KIND_FOLDER || !repository.hasActiveChildren(entryId)) {
            "目录非空，请先删除其中的内容"
        }
        repository.delete(entryId, expectedRevision, actorUid)
    }

    private fun requireMember(uid: String, chatId: String) {
        val chat = chats.getChat(chatId)
        require(chat != null && chat.chatType == ChatType.GROUP.code) { "群聊不存在" }
        require(chatId in memberships.listUserChatIds(uid)) { "你不是当前群成员" }
    }

    private fun requireParent(chatId: String, parentId: String?) {
        if (parentId == null) return
        val parent = requireEntry(chatId, parentId)
        require(parent.kind == GroupFileEntry.KIND_FOLDER) { "父级不是目录" }
    }

    private fun requireEntry(chatId: String, entryId: String): GroupFileEntry {
        val entry = repository.find(entryId) ?: throw IllegalArgumentException("文件条目不存在")
        require(entry.chatId == chatId) { "文件条目不属于当前群" }
        return entry
    }

    private fun resolveOwnedAttachment(actorUid: String, declared: Attachment): Attachment {
        val canonical = declared.copy(path = AttachmentPolicy.canonicalPath(declared.path))
        require(attachments.getOwnerUid(canonical.path) == actorUid) { "只能发布自己上传的文件" }
        val actual = attachments.getAttachment(canonical.path)
            ?: throw IllegalArgumentException("上传文件不存在或已失效")
        require(actual == canonical) { "上传文件元数据不匹配" }
        return actual
    }

    private fun requireQuota(chatId: String, incomingBytes: Long) {
        require(incomingBytes >= 0) { "文件大小非法" }
        val used = repository.totalVersionBytes(chatId)
        require(used <= quotaBytes && incomingBytes <= quotaBytes - used) {
            "群文件空间已超出配额（${quotaBytes / 1024 / 1024} MiB）"
        }
    }

    private fun validateName(value: String): String {
        val name = value.trim()
        require(name.isNotEmpty()) { "名称不能为空" }
        require(name.length <= 180) { "名称不能超过 180 个字符" }
        require(name != "." && name != ".." && name.none { it == '/' || it == '\\' || it.code < 32 }) {
            "名称包含非法字符"
        }
        return name
    }

    companion object {
        const val DEFAULT_QUOTA_BYTES: Long = 1024L * 1024 * 1024
        fun nameKey(name: String): String = name.trim().lowercase(Locale.ROOT)
    }
}
