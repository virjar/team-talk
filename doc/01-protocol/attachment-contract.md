# 附件契约（协议 v4）

## 决策

附件不是 URL，而是 TeamTalk `FileStore` 中一个不可变对象的公开描述符：

```kotlin
data class Attachment(
    val path: String,        // FileStore 相对路径，唯一身份
    val name: String,        // 服务端清洗后的原始文件名
    val contentType: String, // 服务端记录的 MIME
    val size: Long,          // 服务端记录的字节数
)
```

文件、图片、语音、视频和贴纸消息统一实现 `AttachmentBody`。主文件使用
`attachment`，图片/视频缩略图使用可空的 `thumbnail: Attachment?`。协议中不再
出现 `url`、`thumbnailUrl`、`fileName`、`size` 等互相独立且可能矛盾的平行字段。

这是一项不兼容变更，`PacketCodec.PROTOCOL_VERSION` 已从 3 升为 4。项目尚未发布，
旧 MessageStore、客户端 SQLite 和文件测试数据应直接清空，不提供 v3 数据迁移器。

## 数据流与所有权

```text
HTTP upload
  → FileStore 写数据与 FileMetadata
  → 返回 UploadResult(file: Attachment, thumbnail: Attachment?)
  → SDK/客户端直接用返回值构造 AttachmentBody
  → SDK AttachmentPolicy 校验结构并归一化 path
  → TCP Message
  → 服务端 AttachmentService 对照 FileStore 校验完整描述符
  → 只持久化 FileStore 返回的权威 Attachment
  → 客户端访问时用当前 ServerConfig 把 path 解析成 HTTP endpoint
```

权责边界：

- `FileStore` 是 path、name、contentType、size 的唯一事实源。
- `AttachmentPolicy` 是 SDK 与服务端共享的结构契约，不访问存储。
- `AttachmentService` 是服务端附件领域入口，消息发送、编辑、转发统一经过它。
- `MessageService` 不理解存储 tier、storageKey 或 URL。
- UI 下载状态以 `attachment.path` 为键，展示只读取权威 name/size/contentType。
- HTTP URL 仅是访问端点，不进入消息模型；SDK 兼容输入中的完整文件端点 URL 会被
  提取成相对 path，并在下载时重新绑定当前会话服务器。

## 安全不变量

一条附件消息 ACK 成功，必须同时满足：

1. `messageType` 与具体 `AttachmentBody` 类型一致；附件类型不能缺少 body。
2. path 非空、无反斜杠、`.`/`..`、空路径段或非 TeamTalk 绝对 URL。
3. name、contentType 非空，size 非负。
4. 主文件和缩略图均能在当前服务端 FileStore 中解析。
5. 客户端声明的整个描述符与 FileStore 权威描述符一致。

客户端不能通过伪造 size 把大文件变成静默下载，也不能通过消息中的 host 让接收端
访问第三方服务。上传文件名在 FileStore 入口被收敛为安全叶子名。

## HTTP 上传响应

```json
{
  "file": {
    "path": "uid/0123abcd.pdf",
    "name": "report.pdf",
    "contentType": "application/pdf",
    "size": 524288
  },
  "thumbnail": null,
  "width": 0,
  "height": 0,
  "durationSec": null
}
```

服务端与 SDK 共用同一个 `UploadResult` JSON 模型，禁止再次手工解析字段。
