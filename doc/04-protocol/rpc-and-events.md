# RPC 与事件

## 1. RPC envelope

INVOKE payload：

```text
requestId VarInt
serviceId String
methodId VarInt
payload Bytes?
```

RESPONSE payload：

```text
requestId VarInt
status VarInt
payload Bytes?
```

`requestId` 只在当前连接内关联请求。客户端保存 `requestId → Deferred`；连接断开时必须完成或取消
全部 pending request，不能让调用永远等待。

STREAM_ITEM/STREAM_END 复用 requestId，为大结果的流式返回保留。普通列表 RPC 仍使用单一
RESPONSE。

## 2. IDL 代码生成

RPC 定义位于 `protocol/src/commonMain/.../rpc/def/`：

```kotlin
@RpcService("message")
interface MessageRpc {
    suspend fun getHistory(chatId: String, fromSeq: Long, limit: Int): List<Message>
    suspend fun markRead(chatId: String, readSeq: Long)
}
```

KSP 生成：

- `Contract`：service 名称、method ID 与参数/返回编解码。
- `Proxy`：客户端类型安全调用。
- `Stub`：服务端分发和参数解码。

方法 ID 默认由声明顺序决定。已经进入协议的接口新增方法只能追加；中间插入必须显式锁定 ID，并
更新 `RpcMethodIdGoldenTest`。客户端和服务端不得手写另一套 service/method 枚举。

## 3. payload 规则

- 优先让参数和返回值使用实现 `IProto` 的模型。
- 少量基本类型由生成 codec 按 IDL 参数顺序写入。
- 客户端编码和服务端解码必须字段数量、顺序、nullability 和类型完全一致。
- 每个新模型或复杂 payload 添加 round-trip 测试。
- 业务错误用 RESPONSE status 返回；协议损坏或连接错误关闭连接。

群文件使用独立 `groupFile` 服务，当前方法顺序为 list、createFolder、createFile、addVersion、
listVersions、rename、delete。GroupFileEntry 携带逻辑 revision 和当前 contentVersion；
GroupFileVersion 携带不可变 Attachment 快照。文件二进制不进入 TCP payload，仍先通过 HTTP 上传。

文档使用独立 `document` 服务，当前方法顺序为 list、get、create、update、listRevisions、
getRevision、delete。列表模型不携带正文，修订列表不携带完整 Markdown；正文只在打开当前文档或指定
修订时返回。update/delete 的 expectedRevision 是并发契约，不是可选提示。

完整方法查询见[RPC 参考](../10-reference/rpc-reference.md)。

## 4. NOTIFY envelope

NOTIFY 表示服务端主动状态变化。概念字段包括：

- `eventId`：持久化事件的用户级游标；直写瞬时事件可为 0。
- `notifyType`：事件类型。
- `payload`：NotifyContracts 指定的 IProto 字节。

服务端发送持久化事件前调用契约校验，客户端从同一契约表取 reader。这样 CONTACT_ACCEPTED 之类
的事件不会出现服务端发 Contact、客户端按 ContactApply 解码的漂移。

## 5. 事件交付

```text
domain change
  → contract assertion
  → sync_events insert
  → online push
  → client decode/cache commit
  → persist lastEventId
```

客户端认证时携带 lastEventId，服务端分页补发更大的事件。语义为 at-least-once，因此：

- payload 应尽量是完整快照。
- 本地处理必须幂等。
- 只有处理完成才能推进游标。
- 解码失败要记录具体 type/eventId，并让事件下次重试。

## 6. 事件类别

事件分为联系人、群/成员、消息、会话、在线/输入状态、已读同步和用户资料。当前类型与 payload
映射见[事件参考](../10-reference/event-reference.md)。

Presence 与 Typing 是短暂信号，不应被误认为持久业务实体。Presence 可以直推而不进离线队列；
Typing 的 Message 只是承载 chatId/senderUid 的信号快照，不落消息历史。

## 7. SUBSCRIBE

SUBSCRIBE 用于按会话与序列请求历史回放，服务端可通过 MESSAGE_RECV 形式把结果写回请求连接。
它和持久化 NOTIFY 的区别是：订阅响应面向当前连接，不代表新的领域事件，也不能推进全局事件
游标。
