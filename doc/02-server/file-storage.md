# 文件存储与传输

> TeamTalk 的文件存储经历了从 MinIO 到嵌入式双层存储的演进。
> 本文记录完整的存储决策链：为什么不用 MinIO、三层逻辑架构、RocksDB 调优策略。

## 设计目标

- **单进程部署**：不依赖外部存储服务（MinIO/S3/Redis），一个 jar 包包含所有功能
- **小文件高效**：IM 场景大量小文件（头像、语音、表情图），需要快速读写
- **大文件可控**：视频、文档等大文件不能压垮内存和 LSM-Tree
- **零运维**：不需要单独管理存储集群、扩容、备份策略

---

## 为什么不用 MinIO

TeamTalk 最初使用 MinIO + 自建 Netty S3 客户端（含 AWS V4 签名）。2026-05-01 的提交 `b18fea6` 删除了 1643 行 MinIO/S3 相关代码，替换为嵌入式 RocksDB + 文件系统双层存储。

### 决策理由

| 方案 | 否决理由 |
|------|---------|
| **MinIO** | 需要外部进程（独立容器/端口/凭证/生命周期），违背单进程极简部署目标 |
| **纯文件系统** | 大量碎片小文件导致 OS 磁盘碎片和目录检索性能劣化 |
| **RocksDB 存全量** | 超 32MB 文件在 LSM 架构中引发 Compaction/IO 风暴和内存压力 |

### 被删除的代码

- `S3Client.kt`（475行）— 手写 Netty S3 客户端
- `AwsV4Signer.kt`（145行）— AWS V4 签名实现
- `FileService.kt`（287行）— 文件服务层
- `docker-compose.yml` 的 `minio:` 服务块
- `application.conf` 的 `minio {}` 配置块

---

## 三层逻辑存储架构

应用层路由到两个后端，但 RocksDB BlobDB 自动产生第三层：

```
                        文件上传
                           │
                           ▼
                   ┌───────────────┐
                   │  size > 32MB? │
                   └───────┬───────┘
                    Yes    │    No
               ┌───────────┘    └───────────┐
               ▼                            ▼
     ┌─────────────────┐          ┌──────────────────┐
     │ Layer 3:        │          │ RocksDB data CF  │
     │ OS 文件系统      │          │ (BlobDB 模式)     │
     │                 │          │                   │
     │ Hash散列目录     │          │ ┌───────────────┐ │
     │ ab/cd/uuid.dat  │          │ │ size < 4KB?  │ │
     │                 │          │ └──────┬───────┘ │
     │ meta CF 记录路径 │          │  Yes   │   No    │
     └─────────────────┘          │ ┌──────┘   └───┐  │
                                  │ ▼              ▼  │
                                  │ Layer 1:    Layer 2:│
                                  │ LSM内联     Blob文件 │
                                  │ (<4KB)     (4KB-32MB)│
                                  └──────────────────┘
```

### 三层详解

| 层级 | 文件大小范围 | 存储位置 | 读取特征 |
|------|------------|---------|---------|
| **Layer 1: LSM 内联** | < 4 KB | 直接嵌入 SSTable，无额外 IO | 一次 LSM 查找即得 |
| **Layer 2: BlobDB 文件** | 4 KB – 32 MB | Value 存入独立 blob 文件，LSM 只存指针 | LSM 查找 → 读指针 → 读 blob 文件 |
| **Layer 3: OS 文件系统** | > 32 MB | Hash 散列目录（256×256=65536 叶目录） | meta CF 记录 storageKey → 文件系统读取 |

### 为什么是三层不是两层

应用代码只路由到两个后端（RocksDB / 文件系统），但 RocksDB 的 BlobDB 机制自动将 < 4KB 的 value 内联到 LSM-Tree，4KB 以上的 value 分离到 blob 文件。这个 `minBlobSize = 4KB` 的阈值是 RocksDB 层面的优化，不需要应用代码干预。

---

## RocksDB 调优详解

### Column Family 设计

两个 CF 在同一个 RocksDB 实例中：

| Column Family | 用途 | Key 格式 | Value 格式 |
|---------------|------|---------|-----------|
| `meta` | 文件元数据 | `path`（UTF-8） | JSON 序列化的 FileMetadata |
| `data` | 文件 blob 数据 | `path`（UTF-8） | 原始文件字节 |

### data CF — BlobDB 参数（逐项解释）

```kotlin
ColumnFamilyOptions()
    .setWriteBufferSize(64 * 1024 * 1024)      // 64MB memtable
    .setCompressionType(LZ4_COMPRESSION)        // 上层快速压缩
    .setBottommostCompressionType(ZSTD_COMPRESSION) // 底层高压缩比
    .setEnableBlobFiles(true)                   // 启用 BlobDB
    .setMinBlobSize(4 * 1024)                   // <4KB 内联，≥4KB 分离
    .setBlobFileSize(4 * 1024 * 1024)           // blob 文件最大 4MB
    .setBlobCompressionType(LZ4_COMPRESSION)    // blob 文件 LZ4 压缩
    .setEnableBlobGc(true)                      // 启用 blob GC
    .setBlobGcAgeCutoff(0.25)                   // 垃圾占比 >25% 触发重写
    .setBlobGcForceThreshold(0.5)              // 垃圾占比 >50% 强制重写
    .setBlobCompactionReadaheadSize(1024*1024)  // compaction 预读 1MB
    .setPrepopulateBlobCache(FLUSH_ONLY)        // flush 时预热 blob cache
```

### 调优决策理由

**双层压缩（LZ4 + ZSTD）**
- 上层（热数据）：LZ4 压缩快、解压快，适合频繁访问
- 底层（冷数据）：ZSTD 压缩比高，适合很少访问的历史数据
- 不用 Snappy（压缩比不如 ZSTD），不用全 ZSTD（上层写入延迟高）

**BlobDB GC age cutoff = 0.25（低值）**
- 文件存储是 WORM（Write Once Read Many）场景，删除少
- 低 cutoff 意味着 blob 文件中 25% 以上是垃圾时才触发重写
- 容忍一定空间放大换取 IO 平稳（GC 重写会产生写放大）
- 类似 MinIO 的 erasure coding 整理策略，但在进程内完成

**64MB memtable（比消息存储的 16MB 大 4 倍）**
- 文件上传是突发性大批量写入
- 大 memtable 吸收上传突发，减少 flush 频率

### DB 级别选项

```kotlin
DBOptions()
    .setIncreaseParallelism(availableProcessors())  // 按 CPU 核数并行
    .setMaxOpenFiles(1000)                          // 适中上限
```

### 视频 LRU 缓存

针对视频播放场景的优化（视频播放器会发多次 probe 请求）：

```kotlin
// RocksDbTier.kt
private val readCache = ReadCache(
    maxSizeBytes = 128L * 1024 * 1024,    // 128MB
    cacheThreshold = 2L * 1024 * 1024,    // 只缓存 ≥2MB 的视频
)
// 仅缓存 contentType.startsWith("video/") && size >= 2MB 的文件
```

避免视频播放器重复 probe 导致反复读取 RocksDB。

---

## 文件系统层（Layer 3）

### Hash 散列目录

```
$dataRoot/file-store/files/
├── ab/                    ← storageKey[0..1]
│   ├── cd/                ← storageKey[2..3]
│   │   ├── abcdef123456.dat
│   │   └── 789abc...dat
│   └── ef/
│       └── ...
└── ...
```

2 个 hex 字符 × 2 层 = 256 × 256 = 65536 个叶目录，保证单目录文件数始终可控。

### 流式读取

**大文件（文件系统层）**：`RandomAccessFile` + `seek` + 64KB buffer，支持 HTTP Range 请求。Ktor 的 `respondFile` 让 Netty 使用 `sendfile` 零拷贝。

**小文件（RocksDB 层）**：一次性 `db.get` 读取整个 blob（≤32MB，内存可控），然后按 range 切片写入响应 channel。不做 RocksDB 部分值读取（API 不支持）。

---

## 消息存储 vs 文件存储

两个完全独立的 RocksDB 实例，不同的路径、key 格式、CF 结构、调优参数：

| 维度 | MessageStore | FileStore |
|------|-------------|-----------|
| 路径 | `$dataRoot/rocksdb/` | `$dataRoot/file-store/rocksdb/` |
| Column Family | default only | `meta` + `data` |
| memtable | 16MB | 64MB |
| BlobDB | 否 | 是 |
| 压缩 | 默认 | LZ4 上层 / ZSTD 底层 |
| Key 格式 | `[chatId][8B seq BE]` | `path` UTF-8 |
| Value 格式 | 二进制 PacketBuffer | JSON / 原始字节 |
| 访问模式 | 范围扫描（seek/seekForPrev） | 点查询（db.get） |
| 读缓存 | 无 | 128MB LRU（视频 ≥2MB） |

### 消息 Key 设计

```
chatSeqIndex:     [chatId bytes][8B seq BigEndian]  → message bytes
clientMsgIdIndex: [0x01][clientMsgId bytes]         → chatId + seq
```

- seq 用 **BigEndian** 编码，使字节序的字典序等于数字序，支持范围扫描
- `0x01` 前缀手动分隔 keyspace，避免 clientMsgId 索引与 chatSeq 索引冲突
- seq 由 ChatStore 统一分配（不自增），MessageStore 只负责存储

---

## 元数据模型

```kotlin
@Serializable
data class FileMetadata(
    val path: String,           // 公开路径 "{uid}/{32hex-uuid}.{ext}"，也是 RocksDB key
    val originalName: String,   // 上传者提供的原始文件名
    val contentType: String,    // MIME 类型
    val size: Long,             // 字节数
    val tier: StorageTier,      // ROCKSDB / FILESYSTEM
    val storageKey: String,     // 32hex UUID，文件系统层的磁盘文件名
    val uploadedAt: Long,       // 上传时间戳
    val uid: String,            // 上传者 ID
)
```

JSON 序列化时 `ignoreUnknownKeys = true`，前向兼容（新增字段不破坏旧数据）。

### 已移除的字段

V1 曾有 `thumbnailPath`、`expireAt`、`archived` 字段和对应的 `markArchived()`/`findDeletable()`/`delete()` 方法。因为没有任何业务调用方，在提交 `49c3088` 中作为"过早实现"删除（遵循 CLAUDE.md「不要过早实现」原则）。

---

## HTTP 接口

### 上传

```
POST /api/v1/files/upload
Content-Type: multipart/form-data

→ 流式写入临时文件（8KB buffer）
→ FileStore.store() 路由到 RocksDB 或文件系统
→ 返回 { "path": "{uid}/{uuid}.{ext}" }
→ 完整 URL: $serverUrl/api/v1/files/$path
```

上传限制：150MB（`application.conf` 中 `file.max-size-bytes = 157286400`，可配置）

### 下载

```
GET /api/v1/files/{path}

→ 查 meta CF 获取 FileMetadata
→ 文件系统层：respondFile（Netty sendfile 零拷贝）
→ RocksDB 层：streamTo channel（无中间缓冲，直写响应通道）
→ 支持 Range 请求
```

### 鉴权

当前无鉴权（TODO），从 `X-Uid` header 读取 uid，fallback `anonymous`。

---

## 客户端文件处理

### 本地缓存优先

客户端所有媒体资源先下载到本地缓存再渲染：

```kotlin
fun downloadToCache(url: String): File {
    val cached = File(cacheDir, fileName)
    if (cached.exists()) return cached  // 已缓存直接返回
    // 下载...
    return cached
}
```

缓存目录：`~/.teamtalk/media/`（Desktop）/ 应用内部缓存（Android）

### Desktop 拖拽文件

`Modifier.dragAndDropTarget` 接收拖入的文件，根据扩展名自动识别图片/视频/文件，走对应的 MessageBody 发送流程。

**相关代码**：`server/.../infra/storage/FileStore.kt`、`server/.../infra/storage/RocksDbTier.kt`、`server/.../infra/storage/FileSystemTier.kt`、`server/.../api/FileRoutes.kt`、`desktop/.../DesktopMediaHelper.kt`
