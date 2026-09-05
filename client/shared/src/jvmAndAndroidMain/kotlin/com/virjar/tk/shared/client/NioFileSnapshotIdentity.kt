package com.virjar.tk.shared.client

import java.nio.file.attribute.BasicFileAttributes

/**
 * 跨 provider 比较一个有界 NIO 快照。Unix file key 可用时优先；没有它们的 provider 保留之前的
 * creation/mtime/size 兼容边界。
 */
internal fun sameNioFileSnapshotIdentity(
    before: BasicFileAttributes,
    after: BasicFileAttributes,
): Boolean = if (before.fileKey() != null || after.fileKey() != null) {
    before.fileKey() != null && before.fileKey() == after.fileKey()
} else {
    before.creationTime() == after.creationTime() &&
        before.lastModifiedTime() == after.lastModifiedTime() &&
        before.size() == after.size()
}
