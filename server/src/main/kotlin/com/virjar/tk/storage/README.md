# 内嵌文件存储系统

## 架构

按文件大小自动路由到不同存储后端，对上层透明：

| 大小 | 存储后端 | 说明 |
|------|---------|------|
| ≤ 32MB | RocksDB BlobDB | BlobDB 按 minBlobSize 自动分层，上层无感知，LZ4 压缩 |
| > 32MB | 本地文件系统 | 两级哈希散列目录（256×256），避免单目录文件数爆炸 |

元数据（路径、MIME、大小、层级等）统一存 RocksDB `meta` Column Family。

## 三层存储模型

虽然代码层面只有 `ROCKSDB` 和 `FILESYSTEM` 两个 Tier，但 RocksDB 内部实际形成了三层结构：

```
┌─────────────────────────────────────────────────┐
│  Layer 1: LSM-Tree 内联（< 4KB）                 │
│  极小文件直接嵌入 SSTable，无额外 IO              │
├─────────────────────────────────────────────────┤
│  Layer 2: BlobDB 文件（4KB - 32MB）              │
│  Value 存入独立 Blob 文件，LSM-Tree 只存指针      │
│  读取时一次 seek 拿到指针 → 一次 IO 读 Blob       │
├─────────────────────────────────────────────────┤
│  Layer 3: OS 文件系统（> 32MB）                   │
│  两级哈希散列目录，RocksDB 只存元数据              │
│  RandomAccessFile 支持高效 Range 读取             │
└─────────────────────────────────────────────────┘
```

**为什么 BlobDB 文件不会有性能问题**：

1. BlobDB 是 RocksDB 内置的键值分离机制，非外部组件。LSM-Tree 只存轻量指针（索引），Compaction 只搬运指针不搬运 Value，写放大极低。
2. 小文件（< 4KB）自动内联到 LSM-Tree，不会产生额外的 Blob IO。
3. Blob 文件使用 LZ4 压缩，4MB 一个文件顺序存放，读取时局部性好。
4. `prepopulateBlobCache = FLUSH_ONLY` 保证新生成的 Blob 数据在 Flush 时预热到缓存，热数据无冷启动问题。

## GC 与生命周期策略

### BlobDB GC — 模拟 MinIO 对象整理

MinIO 通过后台 erasure coding 整理消除碎片文件。BlobDB 用类似的思路：

- **低频 GC**：`blobGarbageCollectionAgeCutoff = 0.25`，Blob 文件中垃圾占比超过 25% 才触发重写，容忍一定空间放大换取 IO 平稳。
- **渐进回收**：GC 只搬运有效 Blob，无效数据随重写自然消失，不产生全库扫描。
- **与业务生命周期配合**：文件先 `markArchived()`，归档后由定时任务 `findDeletable()` 批量删除。删除产生 LSM 墓碑标记，随下次 Compaction 清理，Blob 垃圾占比上升后触发 BlobDB GC 回收空间。

### 文件系统删除

大文件（> 32MB）删除是低频操作——仅在归档过期或用户主动删除时触发。哈希散列目录设计保证：

- 删除只是 `File.delete()`，不影响目录下其他文件。
- 目录层级（256×256 = 65536 叶目录）保证单目录文件数始终可控，不会因删除产生空目录碎片问题。

## 文件布局

```
data/file-store/
├── rocksdb/          # RocksDB 数据（meta CF + data CF）
├── files/            # 大文件文件系统存储
│   └── {hex[0..1]}/{hex[2..3]}/{storageKey}.dat
└── tmp/              # 上传临时文件
```

## 核心组件

### FileStore（单例对象）

统一读写入口，自动路由到 RocksDB 或文件系统。

- **写入**：`store()` 接收临时文件，小文件读入 RocksDB 后删除临时文件，大文件 rename 到文件系统目录。
- **读取**：`streamTo(meta, channel, range?)` 直接写入 `ByteWriteChannel`，无中间缓冲。
  - `StorageTier.ROCKSDB`：一次性读取 ByteArray（≤32MB，内存可控）。
  - `StorageTier.FILESYSTEM`：64KB 逐块流式写入，支持 Range。
- **生命周期**：`markArchived()` → `findArchivable()` / `findDeletable()`，支持归档和过期清理。

### FileSystemTier

大文件文件系统存储辅助，管理哈希散列目录下的 `.dat` 文件。

- `moveFrom()`：原子 rename，跨文件系统时降级为 copy+delete。
- `streamTo()`：`RandomAccessFile.seek()` + 64KB buffer 逐块写入 `ByteWriteChannel`。
- `delete()`：删除物理文件。

## BlobDB 配置

| 参数 | 值 | 说明 |
|------|---|------|
| `minBlobSize` | 4KB | 小于此值内联到 LSM-Tree |
| `blobFileSize` | 4MB | Blob 文件大小上限 |
| `blobCompressionType` | LZ4 | Blob 文件压缩 |
| `compressionType` | LZ4 | 普通层压缩 |
| `bottommostCompressionType` | ZSTD | 最底层压缩 |
| `blobGarbageCollectionAgeCutoff` | 0.25 | GC 触发阈值 |
| `prepopulateBlobCache` | FLUSH_ONLY | Flush 时预热 Blob 缓存 |

## 设计决策

- **Why not MinIO**：需外部进程，违背单进程极简部署目标。
- **Why not 纯文件系统**：大量碎片小文件导致 OS 磁盘碎片和目录检索性能劣化。
- **Why not RocksDB 存全量**：超 32MB 文件在 LSM 架构中引发 Compaction/IO 风暴和内存压力。
- **WORM 优化**：典型写入一次、读取多次场景，BlobDB GC age cutoff 设低（0.25）减少空间放大。
