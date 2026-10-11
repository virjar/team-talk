package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.infra.db.ContentSearchPending
import com.virjar.tk.server.infra.db.requireExposedTransaction
import org.jetbrains.exposed.sql.upsert

/** 仅在聚合 CAS 成功且仍持有该资源写锁时调用。 */
internal fun markContentSearchDirty(transaction: PgWriteTransactionContext, kind: Int, id: String, revision: Long) {
    transaction.requireExposedTransaction()
    ContentSearchPending.upsert(ContentSearchPending.kind, ContentSearchPending.resourceId) {
        it[ContentSearchPending.kind] = kind
        it[resourceId] = id
        it[ContentSearchPending.revision] = revision
    }
}
