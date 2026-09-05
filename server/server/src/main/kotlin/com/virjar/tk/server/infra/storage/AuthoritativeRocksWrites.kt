package com.virjar.tk.server.infra.storage

import org.rocksdb.WriteOptions

/**
 * 为可能在 RocksDB 之外被确认的事实创建写策略。
 *
 * 默认的 RocksDB 写可能在返回时其 WAL 仍只存在于进程或内核缓冲区中。
 * 返回前同步已启用的 WAL，可防止宿主机崩溃时让 PostgreSQL、搜索
 * 投影或已发出的客户端响应领先于为其提供依据的 RocksDB 事实/可靠发件箱。
 * 重放安全的缓存更新与补偿删除可以刻意使用更弱的选项。
 */
internal fun authoritativeRocksWriteOptions(): WriteOptions = WriteOptions()
    .setDisableWAL(false)
    .setSync(true)
