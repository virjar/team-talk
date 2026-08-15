# Wire Format — TCP 二进制协议规格

> 逐字节精确规格。据此 + PacketBuffer 原语表可从零重写编解码器并保持 wire 兼容。
> 源码：`shared/src/commonMain/kotlin/com/virjar/tk/protocol/{Frame,PacketBuffer,PacketCodec,PacketType}.kt`

---

## 1. 帧布局（Frame）

```
┌─────────┬─────────┬─────────┬─────────┬──────────────┬────────────┐
│ MAGIC_H │ MAGIC_L │ VERSION │  TYPE   │    LENGTH    │   PAYLOAD  │
│  0x54   │  0x4B   │  0x01   │ 1 byte  │ 4B big-endian│  N bytes   │
│  'T'    │  'K'    │         │ 无符号   │  有符号 int   │  N=LENGTH  │
└─────────┴─────────┴─────────┴─────────┴──────────────┴────────────┘
 0         1         2         3         4              8
```

| 项 | 值 | 说明 |
|----|----|------|
| 帧头大小 | 8 字节 | `Frame.HEADER_SIZE` |
| MAGIC | `54 4B`（"TK"） | 不匹配 → CorruptedFrameException 断连 |
| VERSION | `0x02` | `PROTOCOL_VERSION`（当前 2）；不兼容变更必须递增 |
| LENGTH 上限 | 16,777,216（16MB） | `Frame.MAX_PAYLOAD_SIZE`；超限断连 |
| 字节序 | **大端**（固定宽度整数、LENGTH） | Netty ByteBuf 默认 |

**握手**：TCP 建立后**服务端先发** 3 字节 `MAGIC_H MAGIC_L VERSION`；客户端校验后回同样的 3 字节，随后双方升级 pipeline 进入数据阶段。

**解码器行为**（PacketCodec）：
- 半帧等待（header/payload 不齐 → reset 重读）
- 未知 TYPE → 跳过 payload 静默丢帧（前向兼容）
- 零长 payload 仅对 PING/PONG/DISCONNECT 合法（解码为空信号对象）

**编码器行为**：LENGTH 先写 0 占位，写完 payload 后 `setInt` 回填。

## 2. 心跳常量

| 常量 | 值 | 含义 |
|------|----|------|
| PING_INTERVAL_SECONDS | **15** | 客户端写空闲 15s → 自动发 PING |
| READ_IDLE_TIMEOUT_SECONDS | **45**（=3×ping） | 读空闲 45s → 关连接 → 重连。防 NAT 半开 |

## 3. PacketBuffer 原语表（编解码原子操作）

> 所有 payload/模型字段都由这些原语组成。**字段顺序 = wire 顺序，不容错位。**

| 原语 | wire 格式 | 备注 |
|------|----------|------|
| `writeByte/readByte` | 1 字节 | readByte 返回**无符号**（0..255） |
| `writeShort/readShort` | 2 字节大端 | readShort 有符号 |
| `writeInt/readInt` | 4 字节大端有符号 | |
| `writeLong/readLong` | 8 字节大端有符号 | |
| `writeVarInt/readVarInt` | LEB128 无符号 varint：每字节低 7 位有效，最高位=1 表示后续还有；低组在前。32 位最多 5 字节 | **无 zigzag；负数非法**（写负数会截断成 1 字节——契约上只允许非负） |
| `writeVarLong/readVarLong` | 同上，64 位最多 10 字节 | 同上 |
| `writeString/readString` | `[present:1B][len:varInt][utf8]`；present=0 → null | 可空字符串 |
| `writeBytes/readBytes` | `[present:1B][len:varInt][raw]`；present=0 → null | 可空字节 |
| `writeExtension/readExtension` | `[has:1B][count:varInt]` + count×(string key + string value) | 扩展 map（当前未使用） |
| 嵌套对象约定 | `[present:1B]` + 若 1 则紧跟对象 writeTo | 如 `Contact.user`、`Message.body` |
| 可空数字约定 | `[present:1B]` + 若 1 则 varInt/varLong | 仅 `Conversation.lastMessageType/lastMsgTimestamp` 使用 |
| 列表（ProtoCodec.encodeList） | `[count:varInt]` + count 个对象连续 writeTo | **无逐项长度前缀**（依赖解码器精确消费） |

## 4. PacketType（帧 TYPE 字节）

| code | 名称 | payload 类 |
|------|------|-----------|
| 1 | AUTH | AuthRequestPayload |
| 2 | AUTH_RESP | AuthResponsePayload |
| 3 | DISCONNECT | DisconnectSignal（零长） |
| 4 | PING | PingSignal（零长） |
| 5 | PONG | PongSignal（零长） |
| 10 | INVOKE | InvokePayload |
| 11 | RESPONSE | ResponsePayload |
| 12 | STREAM_ITEM | StreamItemPayload（预留） |
| 13 | STREAM_END | StreamEndPayload（预留） |
| 20 | MESSAGE | Message |
| 21 | MESSAGE_ACK | MessageAckPayload |
| 30 | NOTIFY | NotifyPayload |
| 40/41 | SUBSCRIBE/UNSUBSCRIBE | SubscribePayload / UnsubscribePayload |

## 5. 协议控制 payload 布局

### AuthRequestPayload（AUTH，C→S）
```
varInt  authType        // 0=login, 1=register, 2=refresh-token
string  username?
string  password?
string  name?           // register 用
string  refreshToken?   // authType=2 用
string  deviceId        // 必填
string  deviceName?
string  deviceModel?
varInt  deviceFlag
varInt  protocolVersion // 必须等于 PROTOCOL_VERSION(1)
varLong lastEventId     // >0 时服务端补发离线事件
```

### AuthResponsePayload（AUTH_RESP，S→C）
```
varInt  code            // 0=OK 1=auth_failed 2=version_unsupported 3=maintenance 4=device_banned 5=too_many_connections
string  reason?
string  uid?
string  username?
string  name?
string  accessToken?    // 当前未使用（认证即会话）
string  refreshToken?   // 一次性轮换
varLong expiresIn       // 秒（30 天）
```

### InvokePayload（INVOKE，C→S）/ ResponsePayload（RESPONSE，S→C）
```
varInt  requestId       // 客户端自增，从 1 开始
string serviceId       // 字符串服务名（@RpcService name，协议 v2 起）
varInt  methodId        // 方法枚举 id
bytes   payload?        // 方法特定参数
---
varInt  requestId
varInt  status          // 0=OK；401=未认证；400=业务错；504=超时；500=内部错
bytes   payload?        // 方法特定返回
```

### MessageAckPayload（MESSAGE_ACK，S→C）
```
string  clientMsgId
varLong serverSeq       // 0=失败
varInt  code            // 0=OK；401=未认证；400=业务拒；500=内部
string  reason?
```

### NotifyPayload（NOTIFY，S→C）
```
varLong eventId         // sync_events 自增 id；0=非持久事件（历史回放）
byte    notifyType      // NotifyType code（原始 1 字节，非 varint）
bytes   payload?        // 按 NotifyContracts 契约解码
```

### SubscribePayload / ReadSyncPayload
```
// SUBSCRIBE
string  chatId
varLong lastSeq         // >0: 从 lastSeq+1 正向回放；0: 最近 100 条倒序
// READ_SYNC（NOTIFY 内嵌）
string  peerUid         // 谁读的
string  chatId
varLong peerReadSeq     // 读到哪
```

## 6. 模型 wire 布局（全部字段按序）

### User
```
string uid, string username, string name, string? avatar, string? phone,
varInt sex, varInt role, varInt status(默认1)
```

### Chat
```
string chatId, varInt chatType(1=私聊 2=群), string? name, string? avatar,
string? creator, varInt memberCount, varLong maxSeq, string? notice,
byte mutedAll(0/1)
```

### Member
```
string uid, string chatId, varInt role(0=成员 1=管理员 2=群主),
string? nickname, varLong joinedAt, [byte hasUser] + User?
```

### Contact
```
string uid, string friendUid, string? remark, varInt status(1=正常 2=拉黑),
[byte hasUser] + User?
```

### ContactApply
```
varLong id, string fromUid, string toUid, string? token, string? remark,
varInt status(0=pending 1=accepted 2=rejected), varLong createdAt,
[byte hasFromUser] + User?
```

### Conversation
```
string chatId, varInt chatType, string? chatName, string? chatAvatar,
string? lastMessage,
[byte present]+varInt lastMessageType?,          // 可空 Int 约定
[byte present]+varLong lastMsgTimestamp?,        // 可空 Long 约定
varLong lastSeq, varLong readSeq, varInt unreadCount,
byte isPinned(0/1), byte isMuted(0/1),
varLong peerReadSeq, string? draft
```

### Message（MESSAGE 帧及 MESSAGE_RECV 契约）
```
string chatId, string clientMsgId, varLong serverSeq, string senderUid,
byte   messageType,        // 原始 1 字节！MessageType code
varLong timestamp, varInt flags,
[byte hasBody] + MessageBody?   // 按 MessageBodyRegistry(messageType) 解码
```

**flags 位标记**：`bit0=FLAG_REVOKED(1)` `bit1=FLAG_EDITED(2)` `bit2=FLAG_FORWARDED(4)`
**sendStatus（客户端本地，绝不序列化）**：0=SENT 1=SENDING 2=FAILED

### Device / InviteLink
```
// Device
string deviceId, string? deviceName, string? deviceModel,
varInt deviceFlag, varLong lastLogin, byte isOnline(0/1)
// InviteLink
string token, string chatId, string name(读时 null→""),
varInt maxUses(0=无限), varInt useCount, varLong expiresAt, varLong revokedAt
```

## 7. MessageBody 布局（按 MessageType）

| type | Body | 布局（顺序） |
|------|------|-------------|
| 1 TEXT | TextBody | string text |
| 2 IMAGE | ImageBody | string url, varInt w, varInt h, varLong size |
| 3 VOICE | VoiceBody | string url, varInt duration, varLong size |
| 4 VIDEO | VideoBody | string url, varInt duration, varInt w, varInt h, varLong size, string? thumbnailUrl |
| 5 FILE | FileBody | string url, string fileName, varLong size |
| 6 LOCATION | LocationBody | string lat(十进制串), string lng, string? title, string? address |
| 7 CARD | CardBody | string targetUid, string targetName, string? targetAvatar |
| 8 REPLY | ReplyBody | string replyToMsgId, string replyToSenderUid, string? replyToSenderName, string? replySnippet, string content |
| 9 FORWARD | ForwardBody | string? fromChatId, string? fromMsgId, string? fromSenderUid, string? note |
| 10 MERGE_FORWARD | MergeForwardBody | string? title, varInt messageCount |
| 11 REVOKE | RevokeBody | string revokedMsgId |
| 12 EDIT | EditBody | string editedMsgId, string newContent |
| 13 STICKER | StickerBody | string url, varInt w, varInt h |
| 14 REACTION | ReactionBody | string targetMsgId, string emoji, varInt action(1=add 0=remove) |
| 15 TYPING | —（未注册 body，解出 null） | |
| 99 GENERIC | GenericPayload（varInt extensionType, bytes? data） | |

兼容注记：ReplyBody.content 为后加字段，解码时 `readableBytes()>0` 才读（旧数据缺省 ""）；LocationBody 的 Double 走十进制字符串避免浮点精度漂移。

## 8. 错误分层（双端约定）

| 层 | code | 语义 | 客户端映射（AppError） |
|----|------|------|----------------------|
| ACK/RESPONSE status | 0 | 成功 | — |
| | 400 | 业务拒绝（IllegalArgumentException.message） | Business(400, msg) |
| | 401 | 未认证/token 失效 | **AuthExpired（停，登出）** |
| | 504 | RPC 10s 超时（客户端合成） | Timeout |
| | 500 | 服务端内部错 | Business(500) |
| 解码异常 | — | IndexOutOfBoundsException | **FatalCodec + 断连**（协议漂移=开发者 bug，双端代码错误，醒目上报） |

## 8.5 RPC IDL 代码生成（协议 v2 起）

serviceId 为字符串（`@RpcService(name)`），methodId 由 IDL interface 声明顺序分配（`@RpcMethod` 覆盖）。
双端代码由 rpc-processor（KSP2）从 Kotlin interface IDL 生成——参数编码/解码/路由表收敛于生成物，
**手写 encodePayload/withPayload 已全部移除**。IDL 规范见 [rpc-methods.md](rpc-methods.md)。

## 9. 设计决策：为什么 NOTIFY/RPC payload 是 [len][bytes] 而非字段内联

曾讨论过消除 payload 的 ByteArray 中转（Message 子类直写帧）。结论：**保留不透明块设计**，三个硬约束：

1. **持久化必需字节形态**——每个 NOTIFY 写 sync_events BLOB（离线补发源），emit 侧 encode→字节无法消除
2. **批量编码共享**——emitEvents 编码 1 次 N 个接收者共享；内联（持 IProto 逐帧 writeTo）回退为 N 次编码
3. **补发路径统一**——离线补发从 DB 读 BLOB 直包 NotifyPayload；内联会造成 payload 的字节/IProto 双轨表示

对齐 protobuf 的 length-delimited 嵌套消息语义：前向兼容（跳过未知 payload）+ 批量/持久化/生命周期隔离四个能力，代价是 envelope/payload 分层的固有一次小拷贝（<1KB 纳秒级）。RPC 同理保留方法参数直传风格（encodePayload/withPayload + 字段序注释），不做 Request wrapper 类型化（与方法签名风格冲突）。

## 10. 大小/时序常量速查

| 常量 | 值 |
|------|----|
| 帧头 / payload 上限 | 8B / 16MB |
| 心跳 PING / 读超时 | 15s / 45s |
| RPC 超时 / 消息 ACK 超时 | 10s / 10s（超时合成 code=-1 ack） |
| incomingPackets 缓冲 | 64（满则丢帧+trace，绝不阻塞 EventLoop） |
