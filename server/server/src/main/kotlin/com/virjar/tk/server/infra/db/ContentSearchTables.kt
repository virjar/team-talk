package com.virjar.tk.server.infra.db

import org.jetbrains.exposed.sql.Table

/** 每个权威资源只保留一条待处理 revision；重试不会追加无界的事件历史。 */
object ContentSearchPending : Table("content_search_pending") {
    val kind = integer("kind")
    val resourceId = varchar("resource_id", 36)
    val revision = long("revision")
    override val primaryKey = PrimaryKey(kind, resourceId)
    init {
        check("ck_content_search_kind") { kind.between(1, 2) }
        check("ck_content_search_revision") { revision greater 0L }
    }
}
