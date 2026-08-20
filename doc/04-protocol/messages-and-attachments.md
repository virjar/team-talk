# 消息与附件

## 1. Message wire layout

```text
chatId String
clientMsgId String
serverSeq VarLong
senderUid String
messageType Byte
timestamp VarLong
flags VarInt
hasBody Byte
body fields（当 hasBody=1）
```

`sendStatus`、`uploadProgress` 等字段只属于客户端 UI，不参与 wire。服务端接收新消息时不信任调用方
给出的 serverSeq、发送者权限或派生文本。

## 2. 身份、顺序与 flags

- `clientMsgId`：客户端生成，服务端幂等键。
- `serverSeq`：服务端按 chat 分配的单调序列。
- `flags bit0`：已撤回。
- `flags bit1`：已编辑。
- `flags bit2`：转发生成。

编辑和撤回传播更新后的同一消息身份，不通过本地“覆盖显示”绕过服务端权限。

## 3. 消息类型

当前协议分配 RICH_TEXT、IMAGE、VOICE、VIDEO、FILE、LOCATION、CARD、REPLY、FORWARD、
MERGE_FORWARD、REVOKE、EDIT、STICKER、REACTION、TYPING、
INTERACTIVE_CARD，以及预留的 `GENERIC(99)`。是否有完整产品入口以[功能状态](../10-reference/feature-status.md)为准，
枚举存在不等于所有客户端已经完成体验。

MessageBodyRegistry 是 `MessageType → reader` 的唯一解码入口。发送前与服务端落库前都调用
MessageBodyPolicy，确保 messageType 与 body 实际类型一致。

`GENERIC` 的 body 固定为 `GenericPayload(extensionType, opaque data)`。接收端必须完整消费并原样
保存未知扩展字节，不能因为当前版本不理解 extensionType 而断开连接；UI 只显示“不支持的扩展消息”，
不得把 opaque data 当成文本或 Markdown。前向兼容接收不等于开放创建：客户端发送未登记的
`ExtensionType` 时，服务端在落库与 ACK 前拒绝。

## 4. 富文本

普通文字消息的权威源是 `RichTextBody.markdown`。`plainText` 与 mentions 从 Markdown 重建，服务端
不能信任调用方上传的派生值。这保证搜索、预览和 `@` 权限检查使用同一文本解释。

文字消息只有 `RICH_TEXT(code=1)` 一种 wire 形式，不存在并行的兼容解码路径。

交互卡片是独立消息类型，不把可执行动作塞进 Markdown。服务端仍需校验 action schema 与权限。

## 5. Attachment

统一附件描述符：

```text
path String        TeamTalk FileStore 相对路径，例如 uid/object.ext
name String        展示文件名
contentType String MIME type
size VarLong       服务端元数据快照
```

IMAGE、VOICE、VIDEO、FILE、STICKER 等 body 通过 AttachmentBody 引用主文件和可选缩略图。

## 6. 路径规范

SDK 接受三种输入并归一化：

- `uid/object.ext`
- `/api/v1/files/uid/object.ext`
- `https://当前或其他域名/api/v1/files/uid/object.ext`

wire 始终只保存 `uid/object.ext`。以下输入被拒绝：

- 任意其他绝对 URL。
- 以 `/` 开头但不属于文件端点的路径。
- 包含 `..`、`.`、空段、反斜杠的路径。
- 空名称、空 contentType 或负 size。

接受端点 URL 是为了外部 SDK 对接方便，不代表消息允许第三方存储。

## 7. 两层校验

### SDK

发送前校验 body/type 匹配、附件字段和 canonical path。明显错误不进入网络。

### 服务端

ACK 前按 path 查询 FileStore，校验文件存在、元数据与调用者使用权限。只有服务端能权威判断上传
是否真实完成。任何客户端——包括 ImBot——收到成功 ACK 后都可以把消息视为已接受。

## 8. 上传与下载

上传是 HTTP multipart，认证使用当前 access token。响应包含权威 Attachment 和媒体元数据；构造
消息时只保存 path。

下载 URL 由：

```text
serverUrl + "/api/v1/files/" + canonicalPath
```

每次网络下载都必须使用 `Authorization: Bearer <accessToken>`。服务端只允许上传者或当前仍属于某个
引用该附件的会话成员读取；知道随机 path 本身不构成权限。客户端缓存可以改变何时访问网络，但不
能改变 URL 所属端点。小文件可静默下载；大文件由用户触发，并在消息气泡中展示等待、进度、成功
或失败状态。

服务端存储分层见[文件存储](../06-server/file-storage.md)。
