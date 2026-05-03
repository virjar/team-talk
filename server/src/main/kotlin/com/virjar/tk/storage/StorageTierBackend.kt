package com.virjar.tk.storage

import io.ktor.utils.io.ByteWriteChannel
import org.rocksdb.ColumnFamilyHandle
import org.rocksdb.RocksDB

abstract class StorageTierBackend(
    protected val db: RocksDB,
    protected val dataCf: ColumnFamilyHandle,
) {
    abstract suspend fun streamTo(meta: FileMetadata, channel: ByteWriteChannel, range: ReadRange? = null)
    abstract fun deleteData(meta: FileMetadata)
}
