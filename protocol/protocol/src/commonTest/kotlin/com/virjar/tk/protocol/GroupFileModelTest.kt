package com.virjar.tk.protocol

import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.GroupFileEntry
import com.virjar.tk.protocol.model.GroupFileVersion
import com.virjar.tk.protocol.ProtoCodec
import kotlin.test.Test
import kotlin.test.assertEquals

class GroupFileModelTest {
    @Test
    fun `entry and immutable version round trip`() {
        val attachment = Attachment("u/file.md", "file.md", "text/markdown", 42)
        val entry = GroupFileEntry(
            entryId = "entry",
            chatId = "chat",
            parentId = "folder",
            kind = GroupFileEntry.KIND_FILE,
            name = "说明.md",
            attachment = attachment,
            revision = 3,
            contentVersion = 2,
            createdBy = "u1",
            createdAt = 10,
            updatedBy = "u2",
            updatedAt = 20,
        )
        val version = GroupFileVersion("entry", 2, attachment, "u2", 20)

        assertEquals(entry, ProtoCodec.decode(GroupFileEntry, ProtoCodec.encode(entry)))
        assertEquals(version, ProtoCodec.decode(GroupFileVersion, ProtoCodec.encode(version)))
    }
}
