package com.virjar.tk.server.infra.db

import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.UserAvatarPolicy
import org.jetbrains.exposed.sql.ResultRow

/** 重建由 [Users] 声明的全有或全无头像描述符。 */
internal fun ResultRow.toUserAvatar(): Attachment? {
    val path = this[Users.avatarPath] ?: return null
    return UserAvatarPolicy.requireCanonical(
        Attachment(
            path = path,
            name = checkNotNull(this[Users.avatarName]) { "User avatar name is missing" },
            contentType = checkNotNull(this[Users.avatarContentType]) {
                "User avatar content type is missing"
            },
            size = checkNotNull(this[Users.avatarSize]) { "User avatar size is missing" },
        ),
    )
}
