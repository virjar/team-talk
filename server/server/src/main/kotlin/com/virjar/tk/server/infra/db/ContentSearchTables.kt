package com.virjar.tk.server.infra.db

import org.jetbrains.exposed.sql.Table

/** One pending revision per authoritative resource; retries never append an unbounded event history. */
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
