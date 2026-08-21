package com.virjar.tk.client

/**
 * Local-cache schema generation.
 *
 * TeamTalk is still in its pre-release development cycle, so an explicitly approved incompatible
 * change selects a new database file instead of running an in-place migration. Server projections
 * then rebuild from the server; local-only facts from an older epoch are deliberately not imported.
 * Epoch 3 adds durable outgoing/delivery facts, so every later epoch switch must make that data-loss
 * decision explicitly rather than treating the whole database as a disposable projection.
 */
internal const val LOCAL_CACHE_SCHEMA_EPOCH = 3

/** Server identities become local path components on JVM and database names on Android. */
internal fun validatedLocalCacheOwnerId(ownerUid: String): String {
    require(ownerUid.length in 1..64 && ownerUid.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
        "Local-cache owner uid is not a safe identifier"
    }
    return ownerUid
}

internal fun validatedDeploymentFingerprint(fingerprint: String): String {
    require(fingerprint.length == 64 && fingerprint.all { it in '0'..'9' || it in 'a'..'f' }) {
        "Local-cache deployment fingerprint is invalid"
    }
    return fingerprint
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

internal fun localCacheDatabaseFileName(
    deploymentFingerprint: String,
    ownerUid: String,
): String = buildString {
    append("cache_e")
    append(LOCAL_CACHE_SCHEMA_EPOCH)
    append('_')
    append(validatedDeploymentFingerprint(deploymentFingerprint))
    append('_')
    append(validatedLocalCacheOwnerId(ownerUid))
    append(".db")
}
