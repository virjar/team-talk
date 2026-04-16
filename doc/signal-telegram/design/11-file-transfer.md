# 文件传输设计

> TeamTalk 的文件上传/下载策略：进度反馈、大小限制、自动下载

---

## 1. 设计目标

- **上传进度反馈**：用户上传文件时实时显示进度百分比
- **下载管理**：避免重复下载，支持取消下载
- **大小限制**：服务端配置文件大小上限，防止滥用
- **自动下载策略**：小文件自动下载，大文件按需下载
- **安全性**：MIME 类型校验，防止恶意文件上传

---

## 2. 架构概览

文件传输走 HTTP，不走 TCP 包协议。理由：
- TCP 包协议有 16MB 上限，文件可能更大
- HTTP 天然支持流式传输和进度跟踪
- 文件上传/下载不需要 TCP 的低延迟特性

架构遵循"隐藏 MinIO"原则：客户端不直接访问 MinIO，所有文件操作通过服务端流式代理。

```
客户端                     服务端                      MinIO
  │                          │                          │
  │  POST /api/v1/files      │                          │
  │  (multipart/form-data)   │                          │
  │ ──────────────────────►  │                          │
  │                          │  putObject(file)         │
  │                          │ ──────────────────────►  │
  │                          │  200 { fileId, url }     │
  │  ◄────────────────────── │  ◄────────────────────── │
  │                          │                          │
  │  GET /api/v1/files/{id}  │                          │
  │ ──────────────────────►  │                          │
  │                          │  getObject(fileId)       │
  │                          │ ──────────────────────►  │
  │  ◄────────────────────── │  ◄────────────────────── │
  │  (streaming response)    │  (streaming proxy)       │
```

### 2.1 文件存储架构：隐藏 MinIO

当前下载流程存在问题：客户端拿到的是 MinIO 预签名地址（如 `http://minio:9000/teamtalk/uid/uuid.jpg?X-Amz-...`），导致：

1. **耦合**：MinIO 地址暴露给客户端，地址变化后所有缓存链接失效
2. **不对称**：上传走 Server 代理，下载却直连 MinIO

**架构决策：Server 流式代理**

```
上传：Client → POST /api/v1/files/upload → TeamTalk Server → MinIO  （不变）
下载：Client → GET /api/v1/files/{path}  → TeamTalk Server → MinIO → 流式返回给 Client
                                            ↑
                                      客户端只看到 TeamTalk URL
                                      MinIO 完全隐藏
```

客户端的文件 URL 从 `http://minio:9000/teamtalk/xxx` 变成 `http://teamtalk:8080/api/v1/files/xxx`，MinIO 地址变化对客户端透明。

**为什么不用担心性能**：Ktor 的请求处理在协程中运行，网络 I/O 通过 NIO（Netty）实现不阻塞线程。文件流式转发使用固定大小 buffer（如 8KB），不把整个文件加载到内存。MinIO Java SDK 使用阻塞 I/O，但对 TeamTalk 的目标规模（<1 万用户）完全不是问题。

**实施要点**：

- `FileService` 新增流式下载方法：从 MinIO 获取 `InputStream`，分块写入 Ktor `OutgoingContent` 响应流
- `FileRoutes` 将下载路由从 302 重定向改为流式代理，设置正确的 `Content-Type`、`Content-Length`、`Cache-Control` 头
- 客户端**零改动**：当前已通过 `GET /api/v1/files/{path}` 请求文件，改为流式代理后自动适配
- 旧的 302 重定向行为可一次性切换，MinIO 作为内部组件客户端完全无感知，未来迁移只需改服务端配置

---

## 3. 上传设计

### 3.1 上传流程

```kotlin
suspend fun uploadFile(
    file: File,
    channelId: String,
    onProgress: (Float) -> Unit  // 0.0 ~ 1.0
): FileUploadResult {
    return httpClient.submitFormWithBinaryData(
        url = "/api/v1/files",
        formData = formData {
            append("file", file.readBytes(), Headers.build {
                append(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
            })
            append("channelId", channelId)
        }
    ) {
        onUpload { bytesSentTotal, contentLength ->
            onProgress(bytesSentTotal.toFloat() / contentLength)
        }
    }
}
```

### 3.2 服务端校验

```kotlin
// FileRoutes.kt
post("/api/v1/files") {
    val multipart = call.receiveMultipart()
    val part = multipart.readPart() as PartData.FileItem

    // 大小校验
    val maxSize = config.property("file.max-size-mb").toLong() * 1024 * 1024
    if (part.provider().readRemaining().size > maxSize) {
        call.respond(HttpStatusCode.RequestEntityTooLarge, "文件超过大小限制")
        return@post
    }

    // MIME 校验（白名单）
    val allowedTypes = setOf("image/*", "video/*", "audio/*", "application/pdf", "application/zip")
    val contentType = part.contentType?.toString() ?: "application/octet-stream"
    if (!allowedTypes.any { contentType.matchesGlob(it) }) {
        call.respond(HttpStatusCode.UnsupportedMediaType, "不支持的文件类型")
        return@post
    }

    // 存储
    val fileId = UUID.randomUUID().toString()
    minioClient.putObject(bucket, fileId, part.provider())
    call.respond(FileUploadResponse(fileId, "/api/v1/files/$fileId"))
}
```

### 3.3 大小限制配置

```yaml
# application.conf
file:
  max-size-mb: 50           # 单文件最大 50MB
  image-max-size-mb: 20     # 图片最大 20MB
  video-max-size-mb: 100    # 视频最大 100MB
  allowed-mime-types:       # 允许的 MIME 类型
    - image/*
    - video/*
    - audio/*
    - application/pdf
    - application/zip
```

---

## 4. 下载设计

### 4.1 流式下载

服务端使用 Ktor 的 `respondOutputStream` 实现流式代理：

```kotlin
get("/api/v1/files/{id}") {
    val fileId = call.parameters["id"]!!
    call.respondOutputStream {
        minioClient.getObject(bucket, fileId).copyTo(this)
    }
}
```

客户端使用 Ktor 的流式下载跟踪进度：

```kotlin
suspend fun downloadFile(
    fileId: String,
    destFile: File,
    onProgress: (Float) -> Unit
) {
    val response = httpClient.get("/api/v1/files/$fileId") {
        onDownload { bytesReceivedTotal, contentLength ->
            onProgress(bytesReceivedTotal.toFloat() / contentLength)
        }
    }
    destFile.writeBytes(response.body<ByteArray>())
}
```

### 4.2 下载去重

```kotlin
class FileDownloadManager {
    private val activeDownloads = mutableMapOf<String, Job>()  // fileId → download Job

    suspend fun download(fileId: String, destFile: File, onProgress: (Float) -> Unit) {
        // 去重：如果已有相同文件正在下载，复用
        if (activeDownloads.containsKey(fileId)) {
            activeDownloads[fileId]?.join()
            return
        }

        // 文件已存在：跳过下载
        if (destFile.exists()) return

        activeDownloads[fileId] = scope.launch {
            fileRepository.downloadFile(fileId, destFile, onProgress)
            activeDownloads.remove(fileId)
        }
    }

    fun cancel(fileId: String) {
        activeDownloads[fileId]?.cancel()
        activeDownloads.remove(fileId)
    }
}
```

### 4.3 自动下载策略

```
自动下载决策树：
  消息类型 == TEXT → 不需要下载
  消息类型 == IMAGE → 自动下载缩略图（小），原图按需加载
  消息类型 == VIDEO → 下载缩略图，视频按需播放
  消息类型 == FILE → 不自动下载，显示文件信息和下载按钮
  消息类型 == VOICE → 自动下载（语音消息通常 < 1MB）
```

---

## 5. 缩略图

### 5.1 缩略图生成

图片和视频上传时，服务端自动生成缩略图：

```kotlin
// 图片缩略图（200x200 居中裁剪）
fun generateThumbnail(imageBytes: ByteArray, size: Int = 200): ByteArray {
    // 使用 Kotlin 图形库或 ImageIO 处理
    // 上传时同步生成，与原始文件一起存储到 MinIO
}
```

### 5.2 缩略图 URL

```
原始文件：GET /api/v1/files/{id}           → 原图/原文件
缩略图：  GET /api/v1/files/{id}/thumbnail → 200x200 缩略图
```

消息 payload 中携带缩略图 URL：
```kotlin
data class ImagePayload(
    // ... MessagePrefix 字段 ...
    val url: String,            // 原图 URL
    val thumbnailUrl: String,   // 缩略图 URL
    val width: Int,
    val height: Int,
    val size: Long,
)
```

---

## 6. 与 Telegram FileLoader 的对比

| 维度 | Telegram FileLoader | TeamTalk |
|------|---------------------|----------|
| 下载优先级 | HIGH/NORMAL/LOW 三级 | 无优先级（初期） |
| 分片下载 | 支持（大文件分片并行） | 不支持（中小规模不需要） |
| 断点续传 | 支持 | Phase 3 |
| 并发控制 | 4-8 个线程 | 无限制（Ktor 协程） |
| 缓存策略 | 三级缓存 + 预加载 | 磁盘缓存 + 去重 |
| 自动下载 | 细粒度策略（按网络/联系人/大小） | 简化策略（按类型+大小） |

TeamTalk 不需要 Telegram 那么复杂的下载系统。中小规模下，简单的流式下载 + 去重 + 缩略图优先即可满足需求。

---

## 7. 实现优先级

| Phase | 功能 |
|-------|------|
| Phase 1 | 上传进度反馈 + 服务端大小/MIME 校验 + 缩略图生成 |
| Phase 2 | 下载进度反馈 + 下载去重 + 自动下载策略 |
| Phase 3 | 断点续传 + 文件管理器 + 细粒度自动下载配置 |
