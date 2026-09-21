@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.virjar.tk.shared.client

import com.virjar.tk.shared.platform.*
import com.virjar.tk.protocol.payload.SyncDatasetIdPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.Foundation.*
import platform.Security.*
import platform.posix.memcpy

/** Keychain replaces the entire credential record atomically under one process owner lock. */
class IosTokenStore(override val deploymentIdentity: DeploymentIdentity) : TokenStore {
    override val ownerClaimNamespace: String = "ios-keychain:$KEYCHAIN_SERVICE/auth"
    private val rejections = synchronized(credentialLock) {
        rejectionStates.getOrPut(deploymentIdentity.fingerprint) { MutableStateFlow(emptySet()) }
    }
    override val rejectedProtocolVersions: StateFlow<Set<Int>> = rejections.asStateFlow()
    val deviceId: String get() = iosDeviceId()

    override fun claimOwner(): TokenStoreOwner = synchronized(credentialLock) {
        val previous = read()
        check(previous.generation < Long.MAX_VALUE) { "Credential owner generation exhausted" }
        val sameDeployment = previous.deployment == deploymentIdentity.fingerprint
        val next = previous.copy(
            generation = previous.generation + 1,
            deployment = deploymentIdentity.fingerprint,
            uid = if (sameDeployment) previous.uid else null,
            token = if (sameDeployment) previous.token else null,
            dataset = if (sameDeployment) previous.dataset else null,
            rejected = (if (sameDeployment) previous.rejected else emptySet()) + rejections.value,
        )
        rejections.value = next.rejected
        write(next)
        TokenStoreOwner(next.generation, next.login(), next.rejected)
    }
    override fun save(ownerGeneration: Long, uid: String, refreshToken: String, datasetId: String): StoredLogin? = synchronized(credentialLock) {
        require(uid.isNotBlank() && refreshToken.isNotBlank())
        SyncDatasetIdPolicy.requireValid(datasetId)
        val current = read()
        if (!owns(current, ownerGeneration)) return@synchronized null
        val next = current.copy(uid = uid, token = refreshToken, dataset = datasetId)
        if (next != current) write(next)
        next.login()
    }
    override fun compareAndClear(expected: StoredLogin): Boolean = synchronized(credentialLock) {
        val current = read()
        if (current.login() != expected || expected.deploymentFingerprint != deploymentIdentity.fingerprint) return@synchronized false
        write(current.copy(uid = null, token = null, dataset = null)); true
    }
    override fun clearBannedAccount(owner: AccountDataOwner): Unit = synchronized(credentialLock) {
        val current = read()
        if (current.deployment == owner.deploymentFingerprint && current.dataset == owner.datasetId && current.uid == owner.uid) {
            write(current.copy(uid = null, token = null, dataset = null))
        }
    }
    override fun isCurrentOwner(ownerGeneration: Long): Boolean = synchronized(credentialLock) { owns(read(), ownerGeneration) }
    override fun markProtocolVersionRejected(protocolVersion: Int): Boolean = synchronized(credentialLock) {
        require(protocolVersion >= 0)
        rejections.value = rejections.value + protocolVersion
        val current = read()
        if (current.deployment != deploymentIdentity.fingerprint) return@synchronized false
        write(current.copy(rejected = current.rejected + protocolVersion)); true
    }
    private fun owns(value: IosCredentials, generation: Long) = value.generation == generation && value.deployment == deploymentIdentity.fingerprint
    private fun read(): IosCredentials = keychainRead("auth")?.let { Json.decodeFromString<IosCredentials>(it) } ?: IosCredentials()
    private fun write(value: IosCredentials) = keychainWrite("auth", Json.encodeToString(value))
}

@Serializable
private data class IosCredentials(
    val generation: Long = 0,
    val deployment: String = "",
    val uid: String? = null,
    val token: String? = null,
    val dataset: String? = null,
    val rejected: Set<Int> = emptySet(),
) {
    fun login(): StoredLogin? {
        if (uid.isNullOrBlank() || token.isNullOrBlank() || dataset.isNullOrBlank()) return null
        SyncDatasetIdPolicy.requireValid(dataset)
        return StoredLogin(uid, token, generation, deployment, dataset)
    }
}
private const val KEYCHAIN_SERVICE = "com.virjar.teamtalk.credentials"
private val credentialLock = PlatformLock()
private val rejectionStates = mutableMapOf<String, MutableStateFlow<Set<Int>>>()
fun iosDeviceId(): String = synchronized(credentialLock) {
    keychainRead("device-id")?.also { platformCanonicalUuid(it) } ?: platformRandomUuid().also { keychainWrite("device-id", it) }
}
internal fun clearIosCredentialStorage(): Unit = synchronized(credentialLock) {
    keychainWrite("auth", Json.encodeToString(IosCredentials()))
    rejectionStates.values.forEach { it.value = emptySet() }
}
private fun keychainQuery(account: String): Map<CFStringRef?, Any?> = mapOf(
    kSecClass to kSecClassGenericPassword,
    kSecAttrService to KEYCHAIN_SERVICE,
    kSecAttrAccount to account,
    kSecAttrSynchronizable to false,
)
private inline fun <T> withDictionary(value: Map<CFStringRef?, Any?>, block: (CFDictionaryRef) -> T): T {
    val dictionary = checkNotNull(CFDictionaryCreateMutable(kCFAllocatorDefault, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr))
    try {
        value.forEach { (key, item) ->
            val pointer = item as? CPointer<*>
            val reference = pointer ?: CFBridgingRetain(item)
            try { CFDictionarySetValue(dictionary, checkNotNull(key), reference) }
            finally { if (pointer == null) reference?.let(::CFRelease) }
        }
        return block(dictionary)
    } finally { CFRelease(dictionary) }
}
private fun keychainRead(account: String): String? = memScoped {
    val result = alloc<CFTypeRefVar>()
    val status = withDictionary(keychainQuery(account) + mapOf(kSecReturnData to true, kSecMatchLimit to kSecMatchLimitOne)) {
        SecItemCopyMatching(it, result.ptr)
    }
    if (status == errSecItemNotFound) return@memScoped null
    check(status == errSecSuccess) { "Keychain read failed ($status); credentials retained" }
    val data = CFBridgingRelease(result.value) as NSData
    check(data.length <= 64u * 1024u) { "Credential record exceeds size limit" }
    val bytes = ByteArray(data.length.toInt())
    if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    bytes.decodeToString(throwOnInvalidSequence = true)
}
private fun keychainWrite(account: String, value: String) {
    val bytes = value.encodeToByteArray()
    require(bytes.size <= 64 * 1024)
    val data = if (bytes.isEmpty()) NSData() else bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    val attributes = mapOf<CFStringRef?, Any?>(kSecValueData to data, kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
    val status = withDictionary(keychainQuery(account)) { query ->
        withDictionary(attributes) { SecItemUpdate(query, it) }
    }
    if (status == errSecItemNotFound) {
        val added = withDictionary(keychainQuery(account) + attributes) { SecItemAdd(it, null) }
        check(added == errSecSuccess) { "Keychain create failed ($added)" }
    } else check(status == errSecSuccess) { "Keychain update failed ($status); credentials retained" }
}
