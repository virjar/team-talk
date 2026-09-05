package com.virjar.tk.server.infra.storage

internal const val DEFAULT_FILE_STORE_MAX_TOTAL_BYTES = 10L * 1024 * 1024 * 1024
internal const val DEFAULT_FILE_STORE_MAX_TOTAL_FILES = 100_000
internal const val DEFAULT_FILE_STORE_MAX_OWNER_BYTES = 2L * 1024 * 1024 * 1024
internal const val DEFAULT_FILE_STORE_MAX_OWNER_FILES = 20_000

internal enum class FileStoreCapacityScope(val responseMessage: String) {
    OWNER("File storage owner capacity is exhausted"),
    GLOBAL("File storage global capacity is exhausted"),
}

internal class FileStoreCapacityExceededException(
    val scope: FileStoreCapacityScope,
) : IllegalStateException(scope.responseMessage)

/** 存储测试与启动恢复使用的不可变、不标识身份的核算投影。 */
internal data class FileStoreUsage(
    val storedBytes: Long,
    val storedFiles: Int,
) {
    init {
        require(storedBytes >= 0L) { "FileStore usage bytes must not be negative" }
        require(storedFiles >= 0) { "FileStore usage files must not be negative" }
    }

    companion object {
        val EMPTY = FileStoreUsage(storedBytes = 0L, storedFiles = 0)
    }
}

/** 内存准入划分：持久对象计入 stored，已开始的上传计入 pending。 */
internal data class FileStoreCapacityUsage(
    val stored: FileStoreUsage,
    val pending: FileStoreUsage,
) {
    val admitted: FileStoreUsage
        get() = stored.plusObjectGroup(pending)
}

internal data class FileStoreQuotaLimits(
    val maxTotalBytes: Long,
    val maxTotalFiles: Int,
    val maxOwnerBytes: Long,
    val maxOwnerFiles: Int,
) {
    init {
        require(maxTotalBytes > 0L) { "maxTotalBytes must be positive" }
        require(maxTotalFiles > 0) { "maxTotalFiles must be positive" }
        require(maxOwnerBytes > 0L) { "maxOwnerBytes must be positive" }
        require(maxOwnerFiles > 0) { "maxOwnerFiles must be positive" }
    }

    fun exceededScope(
        total: FileStoreUsage,
        owner: FileStoreUsage,
        additionalBytes: Long,
    ): FileStoreCapacityScope? {
        check(additionalBytes >= 0L) { "FileStore capacity admission size must not be negative" }
        if (
            total.storedFiles >= maxTotalFiles ||
            total.storedBytes > maxTotalBytes ||
            additionalBytes > maxTotalBytes - total.storedBytes
        ) {
            return FileStoreCapacityScope.GLOBAL
        }
        if (
            owner.storedFiles >= maxOwnerFiles ||
            owner.storedBytes > maxOwnerBytes ||
            additionalBytes > maxOwnerBytes - owner.storedBytes
        ) {
            return FileStoreCapacityScope.OWNER
        }
        return null
    }
}

/**
 * O(1) 内存准入台账。FileStore 围绕此台账序列化每次变更，而
 * FileMetadata 仍是启动对账重建的持久权威源。
 */
internal class FileStoreCapacityLedger(
    private val limits: FileStoreQuotaLimits,
) {
    private var storedTotal = FileStoreUsage.EMPTY
    private var pendingTotal = FileStoreUsage.EMPTY
    private val storedOwners = mutableMapOf<String, FileStoreUsage>()
    private val pendingOwners = mutableMapOf<String, FileStoreUsage>()

    val totalUsage: FileStoreUsage
        get() = storedTotal

    val totalCapacityUsage: FileStoreCapacityUsage
        get() = FileStoreCapacityUsage(stored = storedTotal, pending = pendingTotal)

    fun ownerUsage(uid: String): FileStoreUsage = storedOwners[uid] ?: FileStoreUsage.EMPTY

    fun ownerCapacityUsage(uid: String): FileStoreCapacityUsage = FileStoreCapacityUsage(
        stored = ownerUsage(uid),
        pending = pendingOwners[uid] ?: FileStoreUsage.EMPTY,
    )

    fun requireAvailable(uid: String, size: Long) {
        requireValidFileStoreOwnerUid(uid)
        limits.exceededScope(
            total = totalCapacityUsage.admitted,
            owner = ownerCapacityUsage(uid).admitted,
            additionalBytes = size,
        )?.let { scope ->
            throw FileStoreCapacityExceededException(scope)
        }
    }

    /** 直接、已物化的写入保留其历史的一步核算路径。 */
    fun reserve(uid: String, size: Long) {
        requireAvailable(uid, size)
        val previousOwner = ownerUsage(uid)
        val nextOwner = previousOwner.plusObject(size)
        val nextTotal = storedTotal.plusObject(size)

        // 先发布拥有者条目。其余赋值不可能失败，观察者只在
        // FileStore 的变更锁下运行，因此没有调用方能看到半更新的对。
        storedOwners[uid] = nextOwner
        storedTotal = nextTotal
    }

    /** 在上传请求开始写其请求体之前，先占用字节与一个对象名额。 */
    fun reservePending(uid: String, size: Long) {
        requireAvailable(uid, size)
        val previousOwner = pendingOwners[uid] ?: FileStoreUsage.EMPTY
        pendingOwners[uid] = previousOwner.plusObject(size)
        pendingTotal = pendingTotal.plusObject(size)
    }

    /** 把一个精确预留转入持久用量，而不做第二次容量决策。 */
    fun commitPending(uid: String, size: Long) {
        releasePending(uid, size)
        val previousOwner = ownerUsage(uid)
        storedOwners[uid] = previousOwner.plusObject(size)
        storedTotal = storedTotal.plusObject(size)
    }

    fun releasePending(uid: String, size: Long) {
        val previousOwner = checkNotNull(pendingOwners[uid]) {
            "FileStore owner pending usage counter underflow"
        }
        val nextOwner = previousOwner.minusObject(
            size,
            "FileStore owner pending usage counter underflow",
        )
        val nextTotal = pendingTotal.minusObject(
            size,
            "FileStore global pending usage counter underflow",
        )
        check(nextOwner.storedFiles != 0 || nextOwner.storedBytes == 0L) {
            "FileStore owner pending usage bytes remain without an object slot"
        }
        if (nextOwner.storedFiles == 0) pendingOwners.remove(uid) else pendingOwners[uid] = nextOwner
        pendingTotal = nextTotal
    }

    fun retire(uid: String, size: Long) {
        val previousOwner = checkNotNull(storedOwners[uid]) {
            "FileStore owner usage counter underflow"
        }
        val nextOwner = previousOwner.minusObject(size, "FileStore owner usage counter underflow")
        val nextTotal = storedTotal.minusObject(size, "FileStore global usage counter underflow")
        check(nextOwner.storedFiles != 0 || nextOwner.storedBytes == 0L) {
            "FileStore owner usage bytes remain without an object slot"
        }

        if (nextOwner.storedFiles == 0) storedOwners.remove(uid) else storedOwners[uid] = nextOwner
        storedTotal = nextTotal
    }

    fun restore(usage: FileStorePersistentUsage) {
        var summed = FileStoreUsage.EMPTY
        val restoredOwners = HashMap<String, FileStoreUsage>(usage.ownerUsages.size)
        usage.ownerUsages.forEach { (uid, ownerUsage) ->
            check(isValidFileStoreOwnerUid(uid)) { "FileStore persistent owner metadata is invalid" }
            check(ownerUsage.storedFiles > 0) { "FileStore persistent owner usage is invalid" }
            check(
                ownerUsage.storedBytes <= limits.maxOwnerBytes &&
                    ownerUsage.storedFiles <= limits.maxOwnerFiles
            ) { "FileStore owner persistent capacity is exceeded" }
            check(restoredOwners.put(uid, ownerUsage) == null) {
                "FileStore persistent owner usage is duplicated"
            }
            summed = summed.plusObjectGroup(ownerUsage)
        }
        check(summed == usage.totalUsage) { "FileStore persistent usage totals do not match owner usage" }
        check(
            usage.totalUsage.storedBytes <= limits.maxTotalBytes &&
                usage.totalUsage.storedFiles <= limits.maxTotalFiles
        ) { "FileStore global persistent capacity is exceeded" }
        check(restoredOwners.size <= usage.totalUsage.storedFiles) {
            "FileStore persistent owner usage cardinality is invalid"
        }

        storedOwners.clear()
        storedOwners.putAll(restoredOwners)
        storedTotal = usage.totalUsage
        pendingOwners.clear()
        pendingTotal = FileStoreUsage.EMPTY
    }

    fun clear() {
        storedOwners.clear()
        pendingOwners.clear()
        storedTotal = FileStoreUsage.EMPTY
        pendingTotal = FileStoreUsage.EMPTY
    }
}

internal fun isValidFileStoreOwnerUid(uid: String): Boolean =
    uid.length in 1..MAX_FILE_STORE_OWNER_UID_LENGTH &&
        uid != "." &&
        uid != ".." &&
        uid.none { character ->
            character == '/' ||
                character == '\\' ||
                character.isWhitespace() ||
                character.isISOControl()
        }

internal fun requireValidFileStoreOwnerUid(uid: String) {
    require(isValidFileStoreOwnerUid(uid)) { "FileStore owner metadata is invalid" }
}

private fun FileStoreUsage.plusObject(size: Long): FileStoreUsage {
    check(size >= 0L) { "FileStore object size must not be negative" }
    return FileStoreUsage(
        storedBytes = Math.addExact(storedBytes, size),
        storedFiles = Math.addExact(storedFiles, 1),
    )
}

internal fun FileStoreUsage.plusObjectGroup(other: FileStoreUsage): FileStoreUsage = FileStoreUsage(
    storedBytes = Math.addExact(storedBytes, other.storedBytes),
    storedFiles = Math.addExact(storedFiles, other.storedFiles),
)

private fun FileStoreUsage.minusObject(size: Long, underflowMessage: String): FileStoreUsage {
    check(size in 0L..storedBytes && storedFiles > 0) { underflowMessage }
    return FileStoreUsage(
        storedBytes = storedBytes - size,
        storedFiles = storedFiles - 1,
    )
}

private const val MAX_FILE_STORE_OWNER_UID_LENGTH = 36
