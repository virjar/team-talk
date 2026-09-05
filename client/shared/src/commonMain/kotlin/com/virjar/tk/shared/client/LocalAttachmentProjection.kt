package com.virjar.tk.shared.client

import com.virjar.tk.protocol.model.Attachment

/** 重建一个可空描述符，同时拒绝撕裂/损坏的本地投影行。 */
internal fun storedAttachment(
    path: String?,
    name: String?,
    contentType: String?,
    size: Long?,
    label: String,
): Attachment? {
    if (path == null && name == null && contentType == null && size == null) return null
    return Attachment(
        path = requireNotNull(path) { "$label has no path" },
        name = requireNotNull(name) { "$label has no name" },
        contentType = requireNotNull(contentType) { "$label has no content type" },
        size = requireNotNull(size) { "$label has no size" },
    )
}
