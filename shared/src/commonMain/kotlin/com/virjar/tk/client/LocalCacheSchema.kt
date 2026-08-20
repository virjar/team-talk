package com.virjar.tk.client

/**
 * Local-cache schema generation.
 *
 * TeamTalk is still in its pre-release development cycle, so local SQLite data is rebuildable
 * from the server and does not carry an in-place migration contract yet. An incompatible schema
 * change increments this epoch and therefore selects a new database file. This keeps the runtime
 * bootstrap deterministic and prevents historical migration branches from accumulating.
 */
internal const val LOCAL_CACHE_SCHEMA_EPOCH = 2

/** Server identities become local path components on JVM and database names on Android. */
internal fun validatedLocalCacheOwnerId(ownerUid: String): String {
    require(ownerUid.length in 1..64 && ownerUid.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
        "Local-cache owner uid is not a safe identifier"
    }
    return ownerUid
}

internal fun localCacheDatabaseFileName(ownerUid: String? = null): String = buildString {
    append("cache_e")
    append(LOCAL_CACHE_SCHEMA_EPOCH)
    ownerUid?.let {
        append('_')
        append(validatedLocalCacheOwnerId(it))
    }
    append(".db")
}
