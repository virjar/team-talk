package com.virjar.tk.shared.client

import java.security.MessageDigest

/** Existing document-draft identities, shared by platform storage and offline preservation tools. */
object DocumentDraftStoragePaths {
    const val ANDROID_DIRECTORY = "document-drafts-v2"

    fun jvmOwnerNamespace(fingerprint: String, datasetId: String, uid: String): String =
        "u-" + ownerDigest("teamtalk-desktop-document-draft-owner-v2", fingerprint, datasetId, uid)

    fun jvmDirectories(fingerprint: String, datasetId: String, uid: String): List<String> = listOf(
        "document-drafts", "v3", "deployments", fingerprint, "owners",
        jvmOwnerNamespace(fingerprint, datasetId, uid),
    )

    fun androidOwnerPrefix(fingerprint: String, datasetId: String, uid: String): String =
        ownerDigest("teamtalk-android-document-draft-owner-v3", fingerprint, datasetId, uid)

    // Storage-format directory versions and hash-domain versions intentionally differ. Changing
    // either would hide existing drafts; validation remains with each caller's existing owner type.
    private fun ownerDigest(domain: String, fingerprint: String, datasetId: String, uid: String): String {
        val bytes = "$domain\u0000$fingerprint\u0000$datasetId\u0000$uid".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = "0123456789abcdef"
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0x0f])
            }
        }
    }
}
