@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.virjar.tk.shared.client

import com.virjar.tk.shared.platform.*
import com.virjar.tk.shared.repository.chatAssetSpoolDirectories
import com.virjar.tk.protocol.ProtocolVersions
import platform.Foundation.*
import platform.posix.chmod

internal val iosStorageLock = PlatformLock()

actual fun platformDataDir(): PlatformFile {
    val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
    return PlatformFile(base, "TeamTalk").also { directory ->
        if (!directory.exists()) check(directory.mkdirs()) { "Cannot create application storage" }
        check(directory.isDirectory && !directory.isSymbolicLink()) { "Unsafe application storage" }
        check(chmod(directory.path, 448u) == 0) { "Cannot protect application storage" }
        check(NSFileManager.defaultManager.setAttributes(mapOf(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication), directory.path, null)) {
            "Cannot configure application data protection"
        }
    }
}

internal fun iosPrivateDirectory(root: PlatformFile, components: List<String>, create: Boolean = true): PlatformFile {
    require(root.isDirectory && !root.isSymbolicLink()) { "Private root is not a directory" }
    var directory = root
    components.forEach { name ->
        requireIosPathComponent(name)
        directory = PlatformFile(directory, name)
        if (!directory.exists()) {
            if (create) {
            check(directory.mkdir()) { "Cannot create private directory" }
            check(chmod(directory.path, 448u) == 0) { "Cannot protect private directory" }
            }
        }
        require(!directory.exists() || directory.isDirectory && !directory.isSymbolicLink()) { "Private ancestor is not a directory" }
    }
    return directory
}
internal fun requireIosPathComponent(value: String) {
    require(value.isNotBlank() && value !in setOf(".", "..") && value.none { it == '/' || it == '\\' || it.isISOControl() }) { "Unsafe private path component" }
}
internal fun requireIosRegularFile(file: PlatformFile) {
    require(!file.exists() || file.isFile && !file.isSymbolicLink()) { "Private entry is not a regular file" }
}

actual fun privateAtomicTextFileStore(
    dataDir: PlatformFile,
    privateDirectories: List<String>,
    fileName: String,
    replacementTemporaryFileName: String?,
): PrivateAtomicTextFileStore {
    privateDirectories.forEach(::requireIosPathComponent)
    requireIosPathComponent(fileName)
    val temporaryName = replacementTemporaryFileName ?: ".$fileName.pending"
    requireIosPathComponent(temporaryName)
    require(temporaryName != fileName)
    return object : PrivateAtomicTextFileStore {
        fun target(create: Boolean = false) = PlatformFile(iosPrivateDirectory(dataDir, privateDirectories, create), fileName)
        fun pending(create: Boolean = false) = PlatformFile(iosPrivateDirectory(dataDir, privateDirectories, create), temporaryName)
        override fun existsNonEmpty(): Boolean = synchronized(iosStorageLock) {
            target().let { requireIosRegularFile(it); it.exists() && it.length() > 0 }
        }
        override fun readText(maxBytes: Long): String? = synchronized(iosStorageLock) {
            require(maxBytes >= 0)
            val file = target(); requireIosRegularFile(file)
            if (!file.exists()) return@synchronized null
            require(file.length() <= maxBytes) { "Private file exceeds size limit" }
            file.readBytes().also { require(it.size.toLong() <= maxBytes) }.decodeToString(throwOnInvalidSequence = true)
        }
        override fun replaceText(content: String, maxBytes: Long): Unit = synchronized(iosStorageLock) {
            val bytes = content.encodeToByteArray()
            require(bytes.size.toLong() <= maxBytes) { "Private file exceeds size limit" }
            val file = target(true); val temp = pending(true)
            requireIosRegularFile(file); requireIosRegularFile(temp)
            temp.writeBytes(bytes)
            check(chmod(temp.path, 384u) == 0) { "Cannot protect private file" }
            temp.syncToDisk()
            file.atomicReplaceWith(temp)
            file.parentFile?.syncToDisk()
        }
        override fun cleanupPendingReplacement(): Boolean = synchronized(iosStorageLock) {
            val temp = pending(); requireIosRegularFile(temp)
            if (!temp.exists()) false else { check(temp.delete()); true }
        }
        override fun delete(): Boolean = synchronized(iosStorageLock) {
            val file = target(); requireIosRegularFile(file)
            if (!file.exists()) false else { check(file.delete()); file.parentFile?.syncToDisk(); true }
        }
    }
}

fun prepareIosClientDataVersion(dataDir: PlatformFile = platformDataDir(), currentMajor: Int = ProtocolVersions.MAJOR): Boolean {
    val marker = privateAtomicTextFileStore(dataDir, emptyList(), ".client-data-version")
    return prepareClientDataVersion(currentMajor, { marker.readText(128) }, marker::replaceText) {
        checkNotNull(dataDir.listFiles()).filter { it.name != ".client-data-version" }.forEach {
            check(it.deleteRecursively()) { "Cannot reset client data; reset marker retained" }
        }
        clearIosCredentialStorage()
    }
}

fun iosAccountDataCleanup(dataDir: PlatformFile = platformDataDir()): AccountDataCleanup = AccountDataCleanup(dataDir) { owner ->
    listOf(
        AccountDataCleanupTarget.tree(dataDir, "deployments", owner.deploymentFingerprint, "datasets", owner.datasetId, "users", owner.uid),
        AccountDataCleanupTarget.tree(dataDir, *chatAssetSpoolDirectories(owner).toTypedArray()),
        AccountDataCleanupTarget.tree(dataDir, "document-drafts", owner.deploymentFingerprint, owner.datasetId, owner.uid),
        AccountDataCleanupTarget.tree(dataDir, "media", owner.deploymentFingerprint, owner.datasetId, owner.uid),
    ) + accountDiagnosticCleanupTargets(dataDir, owner)
}
