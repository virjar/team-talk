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
INTERACTIVE_CARD。是否有完整产品入口以[功能状态](../10-reference/feature-status.md)为准，
枚举存在不等于所有客户端已经完成体验。

MessageBodyRegistry 是 `MessageType → reader` 的唯一解码入口。发送前与服务端落库前都调用
MessageBodyPolicy，确保 messageType 与 body 实际类型一致。

`CardBody.targetAvatar` 使用与 `User.avatar` 相同的完整 FileStore `Attachment?`，不是 URL/path 字符串。
它只是发送时重建的显示快照，不形成附件保留引用；头像随后替换或清除时，旧卡片可降级为占位。

`TYPING(15)` 是唯一复用 Message 信封但没有 MessageBody 的瞬时例外：客户端通过 MESSAGE 发送，
服务端校验当前成员后改写权威 sender/timestamp 并以 `NotifyType.TYPING(41), eventId = 0` 直发其他成员。
它不分配 serverSeq、不写历史/会话/outbox、不返回 MESSAGE_ACK；transport 未就绪或过载时允许丢弃。

所有 canonical 消息信封和 MessageBody 的文本字段统一禁止 NUL (`U+0000`)。这条跨端规则同时覆盖 Markdown
派生纯文本、交互卡片标题/文本块、附件名、位置标题/地址、联系人名、回复正文/摘要、转发备注、
合并转发标题和编辑正文；SDK 在发送前拒绝，服务端必须在写入权威 MessageStore/outbox 前再次拒绝，
避免 PostgreSQL `text`/`varchar` 会话预览无法表示该字符而留下永久失败的投影恢复项。

消息类型按数字协议版本追加；零号基线已经移除未注册的通用扩展消息。
当前 body 没有独立长度，历史列表不能安全跳过未知类型。新增类型必须同时提供版本适配或提高
最低支持版本，不能把未知字节当 Markdown 或静默丢弃，详见[演进边界](versioning.md#兼容分支不是任意新业务的自动翻译器)。

## 4. 富文本

普通文字消息的权威源是 `RichTextBody.markdown`。`plainText`、mentions 与内嵌资产引用从 Markdown 重建，服务端
不能信任调用方上传的派生值。这保证搜索、预览和 `@` 权限检查使用同一文本解释。

文字消息只有 `RICH_TEXT(code=1)` 一种 wire 形式，不存在并行的兼容解码路径。

`RichTextBody.assets` 与 `ReplyBody.assets` 是各自消息内容边界的 canonical sidecar；每项由 scope-local
`assetId`、主 `Attachment`、可选缩略图及媒体尺寸组成。正文只能用标准 Markdown 语法定位：

```markdown
![架构图](teamtalk-asset://asset/01234567-89ab-cdef-0123-456789abcdef)
[需求附件](teamtalk-asset://asset/fedcba98-7654-3210-fedc-ba9876543210)
```

`assetId` 必须是小写 canonical UUID。同一 ID 可在正文多次出现，但 sidecar 只有一项；去重后
的正文引用集必须与 sidecar 精确相等，同时 sidecar 按首次引用排序。图片语法只能指向
`image/*`；畸形地址、跨 scope ID、缺少/多余 descriptor 都在发送与服务端落库前拒绝。
围栏/行内代码里的字面地址不形成资产引用。搜索纯文本只使用图片 alt、文件 label 或附件名，
不索引不透明 ID 或 FileStore 路径。普通已发消息编辑提交完整 `Message`，所以可以更新
`RichTextBody.markdown + assets`；`REPLY` 的作者正文使用相同 sidecar。兼容保留的旧式
`EditBody.newContent` 仍只有纯 Markdown 字符串，因此必须拒绝 `teamtalk-asset` 引用。

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
`EmbeddedAsset` 复用同一 Attachment 契约，但授权 scope 是包含它的消息或文档，不是 Markdown URL。

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

上传是 HTTP multipart，认证使用当前 access token。每次请求必须同时携带：

```text
Idempotency-Key: <lowercase canonical UUID>
X-TeamTalk-Command-Issued-At: <canonical non-negative epoch millisecond>
```

时间戳十进制除 `0` 外不能有前导零，并与 uploadId 一起保持到这次上传意图终结；它使用统一可靠命令的
7 天重试窗口和 15 分钟未来时钟偏差。服务端按认证 uid 隔离同一个 uploadId。请求必须有唯一的外层
`Content-Length`，不能使用 `Transfer-Encoding`；正文是严格的 `multipart/form-data` 单文件 part，
part 名固定为 `file`，且 part 自己声明的 `Content-Length` 必须与从外层 framing 精确推导出的 payload
长度相等。payload 按长度读取，所以正文里形似 MIME boundary 的字节仍是普通文件内容；额外 part、缺少
终止边界、提前 EOF 或尾随字节都会得到 400。

服务端在创建正文临时文件、复制第一个 payload 字节之前，先持久化 `STARTED` 上传事务并预留主文件的
字节与对象槽；随后只流式暂存和计算一次 payload SHA-256。规范请求指纹包含版本、认证 uid、uploadId、
issuedAt、文件名、MIME、精确长度和摘要。主文件与可选缩略图先归属于 `STARTED` 并持久化；两者确认
可用后，再把描述符以及即将返回的原始 JSON 字节提交为 `COMPLETED`。首次响应与重放响应在 HTTP
delivery 结束前都短暂 pin 住该收据及其对象。

如果 200 响应丢失或服务在提交后重启，调用方用同一 identity 和同一正文重试，会得到字节完全相同的
JSON 收据，不会再生成主文件或缩略图。在 identity 时间仍可接受时，同一 uploadId 改变 issuedAt、
文件名、MIME、长度或正文返回 409；同一事务仍在执行时也返回 409 并携带短 `Retry-After`。identity
或收据已过 7 天窗口返回 410，owner/global FileStore 容量不足返回 507。未完成的 `STARTED` 在请求退出
或启动 reconcile 时连同其对象回滚，因此不会被解释为成功。

响应包含权威 Attachment 和媒体元数据；构造消息时只保存 path。对象另从 `uploadedAt` 起持有默认
7 天未引用租约；消息、群文件或文档引用必须在租约到期前权威提交，零引用过期对象会被服务端回收。
这条对象租约与上面的 identity/收据重试窗口是两个生命周期，客户端不能把一次上传成功理解为永久网盘
保留承诺。当前普通 Desktop/Android GUI 调用每次显式操作生成新的 identity，
不会在应用层自动重试；只有持有同一 identity 且能够重新提供完全相同正文的调用方才能安全重放。
跨进程源文件 spool、持久上传 outbox、取消和就地重试属于 Roadmap `CLIENT-04`，不能用内存重试循环
冒充已经完成。

下载 URL 由：

```text
serverUrl + "/api/v1/files/" + canonicalPath
```

每次网络下载都必须使用 `Authorization: Bearer <accessToken>`。服务端只允许上传者或当前仍属于某个
引用该附件的会话成员读取；知道随机 path 本身不构成权限。客户端缓存可以改变何时访问网络，但不
能改变 URL 所属端点。小文件可静默下载；大文件由用户触发，并在消息气泡中展示等待、进度、成功
或失败状态。

上传事务只改变“文件如何可靠进入 FileStore”，不改变下载与播放模型。图片、音频和视频必须先完成
认证下载，校验精确大小并原子发布到账号隔离的本地缓存，渲染器或播放器随后只打开本地文件。HTTP
Range 保留给协议兼容和未来可恢复下载，不能把它接成默认在线播放链路。

撤回附件消息会移除其活动反向引用；删除群文件条目会移除该条目全部版本的活动引用。同一路径仍被
其他消息或群文件使用时继续保留，最后一个引用消失后才具备物理回收资格。

`Attachment.size` 同时是下载端的精确字节契约。客户端在发起网络请求前拒绝负数、超过统一
512 MiB 附件上限或超过本机会话缓存配额的描述符；响应 `Content-Length` 存在时必须与
`Attachment.size` 完全一致，不存在时仍可流式接收。无论响应是否声明长度，客户端都必须在写入
每个分块前按实际累计字节核对绝对上限和附件声明大小，并在 EOF 后核对精确相等；偏大、偏小、
伪造长度、取消或身份替换均只能清理临时文件，不能发布最终缓存。

服务端存储分层见[文件存储](../06-server/file-storage.md)。
