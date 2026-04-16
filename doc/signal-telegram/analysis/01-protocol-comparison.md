# 协议层对比分析

> Signal / Telegram / TeamTalk — 包格式、序列化、负载设计、消息操作建模

---

## 1. 协议总览

| 维度 | Signal | MTProto (Telegram) | TeamTalk |
|------|--------|---------------------|----------|
| **传输层** | WebSocket + HTTP | TCP / HTTP long-poll | TCP (Netty) + HTTP (Ktor) |
| **包格式** | 无显式帧（WebSocket 帧即边界） | auth_key_id + msg_key + encrypted_body | Version(1B) + Type(1B) + Len(VLQ) + Payload |
| **包类型数** | 1（Envelope，语义由 type 字段区分） | 10+（Container/Message/Msgs_ack/RPC 等） | 12（CONNECT/SEND/RECV/PING/CMD 等） |
| **序列化** | Protobuf（二进制，~3-5 bytes/字段头） | TL（构造器 ID 4B + 位置参数） | JSON（kotlinx.serialization） |
| **消息负载** | DataMessage 大结构 + optional 字段 | 40+ 专用构造器（messageService/messageMedia 等） | MessageType 枚举 + JSON content |
| **可选字段** | Protobuf field presence + flags 机制 | TL flags 位图（每 bit 代表一个可选字段） | JSON null 省略 |
| **扩展方式** | 新 Protobuf field number + 旧端忽略 | 新 TL constructor ID | 新 MessageType 枚举值 |
| **消息确认** | 服务端 200 OK（无应用层 ACK） | msg_id + msgs_ack + RPC 结果 | SENDACK + RECVACK |
| **多设备** | 主设备同步加密消息 | 所有设备平等，独立同步 | 多设备在线（独立 TCP 连接） |

---

## 2. 传输层设计

> 三种系统的传输包格式：Signal 的 WebSocket+Protobuf 封装、MTProto 的 TL 容器、TeamTalk 的 VLQ 二进制包

### 2.1 Signal — WebSocket + Protobuf Envelope

Signal 客户端通过 WebSocket 长连接与服务端通信。没有自定义的帧层——WebSocket 帧本身就是消息边界，每条 WebSocket 消息就是一个 Protobuf 编码的 `Envelope`。

```
WebSocket Frame (binary)
  └── Envelope (Protobuf)
        ├── type: CIPHERTEXT / PREKEY_BUNDLE / RECEIPT / KEY_EXCHANGE ...
        ├── source: String          // 发送者 UID
        ├── sourceDevice: Int       // 设备编号
        ├── timestamp: Long         // 服务端时间戳
        ├── content: ByteArray      // 加密后的 Protobuf bytes
        └── serverGuid: String      // 服务端消息 ID
```

**传输层特点**：
- **无显式包头**：依赖 WebSocket 协议的帧边界，无需自管理消息分帧
- **单一入口**：所有消息（聊天、系统通知、已读回执、密钥交换）都通过同一个 Envelope type 区分
- **二进制负载**：content 字段是 Protobuf bytes，无需 Base64 编码
- **无应用层 ACK**：依赖 WebSocket 的有序可靠传输，服务端 200 即为成功

### 2.2 MTProto — TL 容器 + 加密外层

MTProto 在 TCP 流上自行分帧，帧内嵌套 TL（Type Language）序列化结构。外层是加密容器，内层是 TL 消息体。

```
TCP Stream
  └── Unencrypted Message 或 Encrypted Message
        ├── auth_key_id: Int64      // 0 = 明文，非 0 = 关联的 auth_key
        ├── msg_key: Int128         // 消息摘要（用于校验 + AES 参数派生）
        └── encrypted_data: Bytes
              ├── salt: Int64
              ├── session_id: Int64
              ├── message_id: Int64
              ├── seq_no: Int32
              ├── length: Int32
              └── body: Bytes       // TL 序列化的消息体

Container（消息容器，一个 TCP 帧可包含多条消息）:
  msg_container#73f1f8dc
    messages: Vector<%Message>     // 批量打包，减少 TCP 包数量
```

**传输层特点**：
- **自管理分帧**：TCP 流上自行划分消息边界（length 字段），不依赖传输层
- **消息容器**：一个 TCP 包可打包多条消息（msg_container），减少 RTT 开销
- **三级消息模型**：明文消息（握手阶段）→ 加密消息（日常通信）→ Container（批量打包）
- **session_id 隔离**：同一 TCP 连接可复用多个逻辑会话

### 2.3 TeamTalk — VLQ 二进制包

TeamTalk 在 Netty TCP 连接上使用自定义二进制包，VLQ 编码剩余长度（与 MQTT 协议相同的编码方式）。

```
Packet = Version(1B) + PacketType(1B) + RemainingLength(VLQ) + Payload(bytes)

Version:    固定 1 字节，当前为 0x01
PacketType:  固定 1 字节，枚举值 1-12
RemainingLength:  VLQ 编码（1-4 字节）
  编码规则: 每字节低 7 位存数据，最高位为继续标志
  示例: 127 → 0x7F (1B)
        128 → 0x80 0x01 (2B)
        16383 → 0xFF 0x7F (2B)
Payload:    剩余长度指定的字节数，当前为 JSON (UTF-8)
```

```
PacketType 枚举（12 种）:
  CONNECT(1)     CONNACK(2)     DISCONNECT(3)
  SEND(4)        SENDACK(5)     RECV(6)        RECVACK(7)
  PING(8)        PONG(9)
  SUBSCRIBE(10)  UNSUBSCRIBE(11)
  CMD(12)
```

**传输层特点**：
- **轻量包头**：包头最小仅 3 字节（Version + Type + 1B VLQ）
- **MQTT 风格**：VLQ 长度编码成熟可靠，解码器实现简单
- **包类型即语义**：12 种包类型覆盖连接管理、消息收发、心跳、订阅、命令五大类
- **Netty 流水线**：PacketDecoder → TcpHandler 链式处理，天然支持 ByteBuf 零拷贝

### 2.4 传输层对比总结

| 维度 | Signal | MTProto | TeamTalk |
|------|--------|---------|----------|
| 分帧方式 | WebSocket 帧（传输层管理） | 自管理（length 字段） | 自管理（VLQ 长度） |
| 最小包头 | 0（WebSocket 帧自带） | ~32B（加密外层） | 3B（Ver+Type+VLQ） |
| 批量打包 | 不支持（逐条发送） | Container（多条合并一帧） | 不支持（逐帧发送） |
| 包类型 | 1 种 Envelope（type 字段区分） | 10+ 种容器/消息类型 | 12 种显式包类型 |
| 适用场景 | 浏览器兼容（WebSocket） | 极致效率（TCP 原生） | 中小规模（Netty 原生） |

**TeamTalk 可借鉴**：
- MTProto 的 **Container 批量打包**：已决策不采用——TeamTalk 使用 TLS 传输加密（无每包加密开销），Netty 的 write-without-flush + batch flush 已实现小包合并。详见 [03-binary-encoding.md](../design/03-binary-encoding.md) §7
- 包格式将从 VLQ 演进为固定包头（详见 §7.4）

---

## 3. 序列化格式

> Protobuf vs TL vs JSON 的编码效率、编解码性能、Kotlin 生态兼容性

### 3.1 Protobuf（Signal）

Signal 的协议层全面使用 Protocol Buffers。每个消息类型对应一个 `.proto` 定义，编译为各平台原生代码。

```
// Envelope 的 Protobuf 编码
message Envelope {
  optional Type   type         = 1;  // field 1, varint 编码 = 1 byte header
  optional string source       = 2;  // field 2, length-delimited
  optional uint32 sourceDevice = 7;  // field 7, varint
  optional uint64 timestamp    = 5;  // field 5, varint
  optional bytes  content      = 8;  // field 8, length-delimited
  optional string serverGuid   = 9;  // field 9, length-delimited
  // ... ~20 个 optional 字段
}

// DataMessage
message DataMessage {
  optional string             body        = 1;
  repeated AttachmentPointer  attachments = 2;
  optional GroupContext       group       = 3;
  optional uint64             timestamp   = 7;
  optional Quote              quote       = 8;
  repeated Contact            contact     = 9;
  optional Preview            preview     = 10;
  optional Sticker            sticker     = 11;
  optional uint32             flags       = 14;
  optional Delete             delete      = 16;
  optional Reaction           reaction    = 18;
  // ... ~25 个字段
}
```

**编码特性**：
- **Field tag**：每个字段 = `(field_number << 3 | wire_type)` ，通常 1 byte
- **Varint**：整数按 7 位分组，小数字仅需 1 byte
- **Optional**：未设置的字段不编码（零开销）
- **Repeated**：列表字段逐条编码，无需 length 前缀
- **兼容性**：新增字段 = 新 field number，旧端自动忽略

### 3.2 TL 序列化（Telegram）

Telegram 使用自研的 TL（Type Language）序列化方案。每种数据结构用 **构造器 ID（4 bytes）** 区分，字段按位置排列。

```
// message 格式（简化）
message#58F4E58C
  flags:#                          // 位图，每 bit 代表一个可选字段
  out:flags.1?true                 // bit 1 → 是否是发出的消息
  mentioned:flags.4?true           // bit 4 → 是否被 @
  media:flags.9?MessageMedia       // bit 9 → 可选的媒体对象
  reply_markup:flags.6?ReplyMarkup // bit 6 → 可选的回复键盘
  entities:flags.7?Vector<MessageEntity> // bit 7 → 可选的消息格式
  // ... 20+ 字段
= Message;

// 二进制编码
[58F4E58C]           // 构造器 ID (4B, 小端)
[flags: Int32]       // 位图
[...]                // 位置参数（按定义顺序排列）
```

**编码特性**：
- **构造器 ID**：每种类型/变体都有唯一 4-byte ID（如 `58F4E58C`），直接反序列化为对应类
- **flags 位图**：用 4-byte 位图表达所有 optional 字段，置 1 的字段按顺序编码，置 0 的跳过
- **位置编码**：字段按定义顺序排列，无需 field number
- **Vector**：`[vector_id:4B] [count:4B] [items...]`
- **字符串**：`[length:1-4B] [data] [padding]`（按 4 字节对齐）

### 3.3 JSON（TeamTalk 当前）

TeamTalk 使用 kotlinx.serialization 将负载序列化为 JSON，嵌在二进制包内传输。

```json
// SEND 包的 payload（JSON UTF-8）
{
  "channelId": "ch_abc123",
  "channelType": 1,
  "messageType": 1,
  "clientMsgNo": "msg-42-1712304000000",
  "clientSeq": 0,
  "payload": "{\"content\":\"hello\",\"mentionUids\":[]}",
  "flags": 0
}

// RECV 包的 payload
{
  "messageId": "msg_xyz789",
  "channelId": "ch_abc123",
  "channelType": 1,
  "senderUid": "u_001",
  "messageType": 1,
  "serverSeq": 10042,
  "timestamp": 1712304000000,
  "payload": "{\"content\":\"hello\",\"mentionUids\":[]}",
  "flags": 0
}
```

**编码特性**：
- **可读性好**：开发调试时可直接阅读，无需专用解码工具
- **冗余较大**：字段名占大量空间（如 `"channelId":` = 13 bytes），Protobuf 仅需 1 byte tag
- **无 schema 验证**：编译期不检查字段名拼写，运行时依赖 `ignoreUnknownKeys = true` 容错
- **嵌套 JSON**：消息内容 payload 本身也是 JSON 字符串，存在双重序列化开销
- **Kotlin 生态原生**：kotlinx.serialization 与 KMP 完美集成，无需引入额外工具链

### 3.4 序列化对比总结

| 维度 | Protobuf (Signal) | TL (Telegram) | JSON (TeamTalk) |
|------|-------------------|---------------|-----------------|
| 编码方式 | tag+value | constructorID+positional | key:value |
| 字段头开销 | 1-2 B/字段 | 0 B（位置编码） | 5-20 B/字段（字段名） |
| 可选字段 | field presence | flags 位图 | null 省略 |
| 扩展能力 | 新 field number | 新 constructor ID | 新 JSON key |
| 工具链 | protoc 编译器 | TL code generator | 无需编译 |
| KMP 支持 | protobuf-kotlin（可用） | 无 Kotlin 工具链 | kotlinx.serialization（原生） |
| 典型消息体积 | ~80 B（纯文本 Envelope） | ~60 B（纯文本 message） | ~250 B（纯文本 SEND payload） |

**TeamTalk 序列化决策**：
- **放弃 JSON**：字段名冗余（每字段 5-20 B 头开销）、双重序列化（嵌套 JSON 字符串）、无法直接在 ByteBuf 上操作，不适合作为传输编码
- **放弃 Protobuf**：Protobuf 的核心价值是跨语言互操作（`.proto` → 多语言代码生成）。TeamTalk 前后端已收敛到 Kotlin 单一语言（KMP），引入 IDL 文件 + protoc 编译器 + gradle 插件是多余的构建复杂度
- **采用字段顺序二进制编码**：Kotlin data class 本身就是 schema，字段顺序就是编解码规则。`IProto` 接口的 `writeTo(ByteBuf)` + `create(ByteBuf)` 直接操作 Netty ByteBuf，零额外依赖，天然支持部分解码和零拷贝。详见 [03-binary-encoding.md](../design/03-binary-encoding.md)
- **TL 序列化与 Kotlin 生态不兼容**，不建议采用

---

## 4. 消息负载建模

> 三种系统如何用不同的负载结构表达：文本、图片、回复、转发、撤回、系统通知等

### 4.1 Signal — DataMessage 大结构 + optional 字段

Signal 使用单一 `DataMessage` Protobuf 结构承载所有消息类型。不同消息类型通过 optional 字段组合表达。

```
DataMessage
  ├── body: String                    // 文本内容
  ├── attachments: [AttachmentPointer] // 图片/文件/语音/视频
  │     ├── contentType: String        // MIME type
  │     ├── key: ByteArray             // 加密密钥
  │     ├── digest: ByteArray          // 完整性校验
  │     ├── size: Int
  │     ├── width/height: Int          // 图片/视频
  │     ├── caption: String            // 文件描述
  │     └── blurHash: String           // 缩略图 blurhash
  ├── quote: Quote                     // 回复
  │     ├── id: Long                   // 原消息 timestamp
  │     ├── author: String             // 原消息发送者
  │     └── text: String               // 原消息文本预览
  ├── preview: [Preview]               // 链接预览
  ├── sticker: Sticker                 // 贴纸
  ├── reaction: Reaction               // 表情回应
  │     ├── emoji: String
  │     └── remove: Boolean            // 取消回应
  ├── delete: Delete                   // 删除（服务端删除）
  ├── edit: Edit                       // 编辑
  │     ├── targetSentTimestamp: Long   // 原消息时间戳
  │     └── dataMessage: DataMessage    // 新内容（递归嵌套）
  └── groupChangeChange: GroupChange   // 群组变更
```

**设计特点**：
- **"上帝对象"模式**：一个 DataMessage 承载所有类型，通过哪个字段非空来判断消息类型
- **Edit = 递归嵌套**：编辑消息 = `edit { targetSentTimestamp, dataMessage { 新内容 } }`，新内容本身又是一个 DataMessage
- **删除 = 特殊 DataMessage**：delete 字段非空时表示删除操作
- **附件通用化**：图片、文件、语音、视频都用 AttachmentPointer，由 contentType 区分
- **时间戳即 ID**：用发送时间戳标识消息（无独立 messageId）

### 4.2 Telegram — 40+ 专用构造器

Telegram 为不同消息内容定义了独立的 TL 构造器，形成扁平的类型体系。

```
// 消息本身
message#58F4E58C flags:# out:flags.1?true ...
  from_id:Peer peer_id:Peer
  message:string              // 文本内容（始终存在）
  media:flags.9?MessageMedia  // 媒体附件（可选）
  reply_to:flags.3?MessageReplyHeader
  fwd_from:flags.2?MessageFwdHeader
  views:flags.10?int edit_date:flags.15?int
  entities:flags.7?Vector<MessageEntity>
  = Message;

// 媒体类型（独立构造器）
messageMediaEmpty#3DED6320
messageMediaPhoto#695150D7 photo:Photo caption:string
messageMediaGeo#56E0D474 geo:GeoPoint
messageMediaContact#5BCC75A5 phone:string first_name:string last_name:string
messageMediaDocument#9CB070D7 document:Document caption:string
messageMediaWebPage#A32DD600 webpage:WebPage
messageMediaVenue#2C6AFAA1 venue:string geo:GeoPoint
messageMediaGame#FD43051C game:Game
messageMediaInvoice#845413A4 title:string description:string ...
messageMediaPoll#4BD25A5E poll:Poll results:PollResults
// ... 更多

// 回复头（独立结构）
messageReplyHeader#BC5F4D14 reply_to_msg_id:int = MessageReplyHeader;

// 转发头（独立结构）
messageFwdHeader#7891576F from_id:Peer date:int ...
  = MessageFwdHeader;
```

**设计特点**：
- **类型爆炸**：每种媒体类型都有独立的构造器，总共有 40+ 种 Message 子类型
- **message 字段始终存在**：即使是纯图片消息，`message:string` 也存在（可为空串）
- **媒体正交**：`MessageMedia` 是独立类型族，可独立演进而不影响 Message 结构
- **操作分离**：编辑、删除、转发通过独立的 Updates 事件表达，不修改原始消息结构
- **字段多但不冗余**：flags 位图确保可选字段不占用空间

### 4.3 TeamTalk — MessageType 枚举 + JSON content

TeamTalk 使用 MessageType 枚举区分消息类别，每种类别对应不同的 JSON content 结构。

```
MessageType 枚举:
  TEXT(1)    IMAGE(2)    VOICE(3)    VIDEO(4)
  FILE(5)    LOCATION(6) CARD(7)
  REPLY(10)  FORWARD(11) MERGE_FORWARD(12) REVOKE(13) TYPING(14)
  CHANNEL_CREATED(20)  CHANNEL_UPDATED(21) ...
  CMD(30)    ACK(31)     CUSTOM_START(64)

// SEND 包中 payload 是嵌套 JSON
// 文本消息:  payload = TextContent.toJson()
// 图片消息:  payload = ImageContent.toJson()
// 回复消息:  payload = ReplyContent.toJson()

// TextContent
{"content": "hello", "mentionUids": []}

// ImageContent
{"url": "https://...", "width": 1920, "height": 1080, "size": 524288}

// ReplyContent（包含原始消息信息 + 新回复内容）
{"messageId": "msg_123", "senderUid": "u_001", "payload": "{...}",
 "messageType": 1, "content": "reply text"}
```

**设计特点**：
- **枚举驱动**：messageType 决定 payload 的解析方式，显式且清晰
- **JSON 灵活**：每种消息类型的 content 结构可独立演化，无需 schema 编译
- **嵌套 JSON**：ReplyContent 内嵌原始消息的 payload 字符串，存在双重序列化
- **操作 = 消息类型**：REVOKE、FORWARD、MERGE_FORWARD 是独立的消息类型，而非消息上的操作标记
- **无独立编辑类型**：消息编辑尚未实现

### 4.4 负载建模对比总结

| 维度 | Signal | Telegram | TeamTalk |
|------|--------|----------|----------|
| 建模方式 | 大结构 + optional 字段组合 | 多构造器 + flags 位图 | 枚举 + JSON content |
| 消息类型数量 | 1 个 DataMessage（字段组合） | 40+ TL 构造器 | 15+ MessageType 枚举值 |
| 新增消息类型 | 新增 optional 字段 | 新增 constructor ID | 新增枚举值 + content 类 |
| 回复表达 | quote 字段嵌套 | MessageReplyHeader | ReplyContent（含原消息） |
| 编辑表达 | edit 字段（递归 DataMessage） | 独立 Update 事件 | 未实现 |
| 撤回表达 | delete 字段 | 独立 Update 事件 | REVOKE 消息类型 |
| 附件建模 | 统一 AttachmentPointer | 独立 MessageMedia 子类 | 各 content 类型自行定义 |

**TeamTalk 可借鉴**：
- Signal 的 **Edit 递归嵌套** 是一种简洁的建模方式——编辑消息携带原始消息时间戳 + 新内容，实现时可参考
- Telegram 的 **操作与消息分离**（编辑/删除作为独立 Update）对服务端实现更友好，但增加了协议复杂度
- TeamTalk 当前的"操作 = 消息类型"模式（REVOKE 是独立 MessageType）简洁实用，建议保持

---

## 5. 消息操作建模

> 发送/接收确认、编辑、撤回、转发——各系统如何将「操作」建模为协议消息

### 5.1 Signal — 单一 Envelope + 操作语义由负载字段表达

Signal 的消息流非常简洁：客户端发送 PUT /v1/messages，服务端返回 200 OK。没有应用层 ACK 机制——依赖 HTTP 的请求/响应语义。

```
发送流程:
  Client → Server:  PUT /v1/messages/{account-id}
    body: [Envelope(CIPHERTEXT, content=encrypted(DataMessage)), ...]
  Server → Client:  200 OK

接收流程:
  Server → Client:  WebSocket push (Envelope)
    Envelope.type = CIPHERTEXT, content = encrypted(DataMessage)

已读回执:
  Client → Server:  PUT /v1/receipt/{account-id}
    body: { type: "READ", timestamp: 1712304000000 }

编辑:
  发送新的 DataMessage，其中 edit 字段非空:
    edit { targetSentTimestamp: 原消息时间戳, dataMessage: { 新内容 } }
  服务端找到原消息，标记为已编辑

撤回:
  发送 DataMessage，其中 delete 字段非空:
    delete { targetSentTimestamp: 原消息时间戳 }
  服务端删除原消息
```

**操作建模特点**：
- **操作 = DataMessage 内的特殊字段**：edit、delete、reaction 都是 DataMessage 的 optional 字段
- **无消息 ID**：用时间戳标识消息（signalTimestampAsId），简化了 ID 管理
- **无重传机制**：HTTP 请求失败由客户端重试，服务端不维护消息状态
- **已读回执走 HTTP**：不走 WebSocket，独立的 REST 接口

### 5.2 Telegram — 专用 TL 构造器 + Updates 事件流

Telegram 通过 Updates 事件流推送所有状态变更（新消息、编辑、删除、已读、输入状态等），每种操作有独立的 TL 构造器。

```
发送流程:
  Client → Server:  messages.sendMessage(peer, message, random_id, ...)
  Server → Client:  Updates(updateNewMessage(message))
  // RPC 调用返回后，客户端通过 Updates 流接收自己发的消息

接收流程:
  Server → Client:  Updates(updateNewMessage(message))
  Client → Server:  msgs_ack(msg_id)  // 确认收到 Updates

编辑:
  Client → Server:  messages.editMessage(peer, id, message)
  Server → Client:  Updates(updateEditMessage(message))
  // 推送给所有已接收该消息的设备

删除:
  Client → Server:  messages.deleteMessages(id: [42, 43])
  Server → Client:  Updates(updateDeleteMessages(messages: [42, 43]))

已读:
  Client → Server:  messages.readHistory(peer, max_id)
  Server → Client:  Updates(updateReadHistoryOutbox(peer, max_id))

输入状态:
  Client → Server:  messages.setTyping(peer, action)
  Server → Client:  Updates(updateUserTyping(user_id, action))
```

**操作建模特点**：
- **Updates 是统一事件流**：所有变更（新消息、编辑、删除、已读、在线状态）都通过 Updates 推送
- **RPC + 推送双通道**：客户端通过 RPC 发起操作，服务端通过 Updates 广播结果
- **操作是独立事件**：editMessage、deleteMessages 是独立的服务方法，不是消息的特殊字段
- **msg_id 去重**：客户端生成 random_id，服务端据此去重，保证幂等

### 5.3 TeamTalk — SEND/RECV 包类型 + JSON payload

TeamTalk 使用显式包类型表达消息流向，操作语义通过 MessageType + JSON payload 表达。

```
发送流程:
  Client → Server:  SEND 包
    { channelId, channelType, messageType=TEXT, clientMsgNo, payload }
  Server → Client:  SENDACK 包
    { clientMsgNo, messageId, serverSeq, timestamp }

接收流程:
  Server → Client:  RECV 包
    { messageId, channelId, senderUid, messageType, serverSeq, payload }
  Client → Server:  RECVACK 包
    { channelId, serverSeq }

撤回:
  Client → Server:  SEND 包 (messageType=REVOKE)
    payload = { messageId: "要撤回的消息ID" }
  Server 广播 RECV(messageType=REVOKE) 给所有成员

回复:
  Client → Server:  SEND 包 (messageType=REPLY)
    payload = ReplyContent (含原消息ID + 回复内容)

转发:
  Client → Server:  SEND 包 (messageType=FORWARD)
    payload = ForwardContent (含原消息ID + payload + messageType)

合并转发:
  Client → Server:  SEND 包 (messageType=MERGE_FORWARD)
    payload = MergeForwardContent (含多条消息的摘要)

输入状态:
  Client → Server:  SEND 包 (messageType=TYPING)
    payload = { channelId, channelType }
```

**操作建模特点**：
- **包类型表达流向，MessageType 表达语义**：SEND/RECV 是传输语义，TEXT/REVOKE/REPLY 是业务语义
- **SENDACK/RECVACK 确认链**：发送确认 + 接收确认，两级 ACK 保证消息投递
- **操作 = MessageType**：REVOKE、REPLY、FORWARD 都是独立的 MessageType，语义清晰
- **clientMsgNo 幂等**：客户端生成唯一消息号，服务端据此去重
- **无 Updates 事件流**：编辑通知、已读推送等尚未实现，需要新增机制

### 5.4 操作建模对比总结

| 操作 | Signal | Telegram | TeamTalk |
|------|--------|----------|----------|
| 发送确认 | HTTP 200 OK | RPC 返回 + Updates 推送 | SENDACK 包 |
| 接收确认 | 无（WebSocket 可靠传输） | msgs_ack | RECVACK 包 |
| 编辑 | DataMessage.edit 字段 | messages.editMessage RPC | 未实现 |
| 撤回 | DataMessage.delete 字段 | messages.deleteMessages RPC | SEND(messageType=REVOKE) |
| 已读回执 | PUT /v1/receipt | messages.readHistory RPC | HTTP API（非 TCP 协议） |
| 输入状态 | 无（Sealed Sender 限制） | messages.setTyping RPC | SEND(messageType=TYPING) |
| 多设备同步 | 主设备加密同步 | Updates 事件流各自消费 | 独立 TCP 连接各自拉取 |

**TeamTalk 可借鉴**：
- **两级 ACK 已经优于 Signal**：SENDACK（服务端确认持久化）+ RECVACK（接收者确认），消息投递可靠性有保障
- **编辑实现建议**：语义拍平后 `PacketType.EDIT(31)` 是独立包类型，payload 携带 `EditPayload(targetMessageId, newContent)`，比 Signal 的递归嵌套更直观
- **Updates 机制是中期需求**：当引入在线状态、消息编辑推送时，需要一个类似 Telegram Updates 的服务端推送通道。当前 CMD 包类型可以作为基础设施复用

---

## 6. 扩展性与兼容性

> 新增消息类型、新增字段、版本协商——三种协议的演进能力

### 6.1 Signal（Protobuf 天然兼容）

Protobuf 的前向/后向兼容性是业界最佳实践：

- **新增字段**：分配新 field number，旧客户端自动忽略未知字段
- **删除字段**：标记为 `reserved`，新客户端收到旧消息时该字段为默认值
- **枚举扩展**：在 Type 枚举中新增值，旧客户端收到未知 type 时可跳过
- **版本协商**：通过 `SignalServiceConfiguration` 在连接时协商特性，无协议版本号

Signal 的 DataMessage 从 v1 扩展到 v5 仍保持单一结构，靠的就是 Protobuf optional 字段的天然兼容性。

### 6.2 Telegram（Constructor ID 隔离）

TL 序列化的扩展通过新增构造器实现：

- **新增消息类型**：注册新的 constructor ID，不影响已有类型
- **新增可选字段**：在 flags 位图中分配新 bit，旧客户端忽略该 bit
- **Layer 协商**：客户端在 initConnection 中声明 `layer` 版本号，服务端据此决定返回哪些构造器
  - 当前 layer 已到 190+，每个 layer 可能新增/修改构造器
- **Schema 持续演进**：Telegram 定期发布新 layer schema，客户端必须跟进

这是最灵活但也最重的方式——客户端需要持续维护与 layer 版本对应的 TL 定义。

### 6.3 TeamTalk（JSON 灵活 + 枚举管控）

TeamTalk 当前的扩展机制：

- **新增消息类型**：在 MessageType 枚举中新增值（如 EDIT=15），旧客户端收到未知 messageType 时可降级渲染或忽略
- **新增 JSON 字段**：`ProtocolJson { ignoreUnknownKeys = true }` 保证旧客户端忽略新字段
- **协议版本**：Packet.version 字段预留（当前为 1），理论上可用于版本协商
- **自定义消息**：CUSTOM_START(64) 起始码为第三方扩展保留空间

**扩展性评估**：
- JSON + ignoreUnknownKeys 提供了与 Protobuf 相当的兼容性
- MessageType 枚举需要在 shared 模块中同步修改，但编译期就能发现不一致
- Packet.version 是未来的版本协商入口，但目前没有使用

---

## 7. TeamTalk 协议演进建议

### 7.1 序列化：字段顺序二进制（初始设计决策）

TeamTalk 的 payload 编码从一开始就采用字段顺序二进制，而非 JSON 或 Protobuf。理由：

- **JSON 不适合传输编码**：字段名冗余、双重序列化、无法直接操作 ByteBuf
- **Protobuf 是跨语言方案**：TeamTalk 已收敛到 Kotlin 单一语言（KMP），IDL + code generator 是多余的构建复杂度
- **字段顺序编码是 Kotlin 单语言的最优解**：data class 即 schema，字段顺序即编码规则，零外部依赖

详见 [03-binary-encoding.md](../design/03-binary-encoding.md)。

### 7.2 短期改进

| 改进 | 说明 | 复杂度 |
|------|------|--------|
| 传输层简化 | 去掉 Version 字段（版本已在握手中协商）、VLQ 改为固定 4B 长度（`readInt()`/`writeInt()`）、payload 上限 16 MB | 低 |
| 握手与包分离 | CONNECT/CONNACK 改为独立握手包（Magic + Version + token），从 PacketType 枚举中移除，节省枚举空间 | 中 |
| 消息编辑 | 新增 `PacketType.EDIT(31)`（语义拍平后 EDIT 是独立包类型），payload = `EditPayload(targetMessageId, newContent)` | 低 |
| 消息压缩 | 利用 ConnectPayload 中已有的 `supportCompress` 字段，对大 payload 做 gzip 压缩 | 低 |

### 7.3 不建议采用

| 方案 | 原因 |
|------|------|
| TL 序列化 | 无 Kotlin 工具链，自研 code generator 维护成本高 |
| Protobuf | 引入 IDL + code generator 流程，KMP 多平台构建复杂度增加；field-order 方案已满足需求 |
| 加密协议（X3DH/Double Ratchet） | 办公 IM 不做 E2EE，TLS 传输加密足够（详见 [04-security.md](../design/04-security.md)） |
| MTProto 风格的 auth_key | JWT + TLS 已满足认证和加密需求 |

### 7.4 协议会话流程与演进路径

TeamTalk 的 TCP 连接分为两个阶段：**握手**（连接建立时执行一次）和**包交换**（持续进行）。握手包有独立的二进制格式，不属于包的概念，不走包编解码。

```
TCP 连接生命周期:

┌─────────────────────────────────────────────────────────┐
│  阶段 1：握手（每条连接执行一次，不属于包交换）             │
│                                                         │
│  Client → Server: 握手包（自有格式，不走包编解码）         │
│                                                         │
│    Magic(7B)      协议魔数，不匹配立即 close              │
│    Version(1B)    协议版本，不支持立即 close               │
│    tokenLen(2B)   token 长度，超过上限立即 close           │
│    token(N B)     JWT token                             │
│    uidLen(2B)     用户 ID 长度                           │
│    uid(N B)       用户 ID                               │
│    deviceIdLen(2B)                                      │
│    deviceId(N B)  设备标识                               │
│    ...            其他设备信息                            │
│                                                         │
│  Server 处理流程（逐级校验，尽早拒绝）:                     │
│    1. 读 8B → Magic 不匹配 → close（非 TeamTalk 流量）     │
│    2. 读 8B → Version 不支持 → close（协议版本不兼容）     │
│    3. 读 tokenLen → 超长 → close（防止内存攻击）          │
│    4. 读 token → JWT 验证失败 → 返回 Ack(code≠0) + close │
│    5. 读 uid/deviceInfo → 绑定连接                       │
│                                                         │
│  Server → Client: 握手响应包（自有格式）                   │
│                                                         │
│    Magic(7B)      服务端魔数，客户端可验证连对了服务器      │
│    Version(1B)    服务端协议版本                          │
│    Code(1B)       状态码:                                │
│                      0 = 认证成功                        │
│                      1 = token 无效                     │
│                      2 = 版本不支持                      │
│                      3 = 服务器维护中                    │
│    reasonLen(2B)                                        │
│    reason(N B)    人类可读的状态描述                      │
│                                                         │
│  握手成功后进入包交换阶段                                 │
└──────────────────────────┬──────────────────────────────┘
                           │ Code = 0
                           ▼
┌─────────────────────────────────────────────────────────┐
│  阶段 2：包交换（持续进行，N 个包）                        │
│                                                         │
│  每个包的格式:                                           │
│    PacketType(1B) + Length(4B) +                         │
│      Payload(field-order binary)                        │
│                                                         │
│  包头固定 5 字节，Length ≤ 16 MB（超限断开连接）           │
│                                                         │
│  PacketType — 语义拍平 + 编码范围（1 字节，0-255）:        │
│    0         Reserved                                   │
│    1-9       连接控制: DISCONNECT(1) PING(2) PONG(3)     │
│    10-19     会话管理: SUBSCRIBE(10) UNSUBSCRIBE(11)     │
│    20-49     消息(双向): TEXT(20) IMAGE(21) FILE(24)     │
│              REPLY(27) FORWARD(28) REVOKE(30)           │
│              EDIT(31) TYPING(32) ...                    │
│    80-89     确认应答: SENDACK(80) RECVACK(81)          │
│    90-99     系统消息: CHANNEL_CREATED(90) ...           │
│    100-109   命令: CMD(100) ACK(101)                    │
│    110-255   保留 / 自定义                              │
│                                                         │
│  双向统一：上行和下行共用同一 PacketType                     │
│  详见 03-binary-encoding.md §2.2                         │
│                                                         │
│  CONNECT/CONNACK 不在 PacketType 中——它们是握手包，       │
│  每条连接必定执行一次，无需用字节标记包类型，               │
│  用枚举值标记反而是浪费。握手完成后才进入包交换阶段。       │
│                                                         │
│  PacketType 直接决定 payload 结构（语义拍平）:              │
│    TEXT(20)   → TextPayload     (channelId+text+...)    │
│    IMAGE(21)  → ImagePayload    (channelId+url+w/h+...) │
│    REVOKE(30) → RevokePayload   (channelId+targetMsgId) │
│    TYPING(32) → TypingPayload   (channelId+channelType) │
│    SENDACK(80)→ SendAckPayload  (clientMsgNo+msgId)     │
│    RECVACK(81)→ RecvAckPayload  (channelId+serverSeq)   │
│    PING/PONG  → 空 payload                              │
│                                                         │
│  每种 PacketType 有独立的 IProto data class，              │
│  字段自包含，无 MessageType 二次分派。                    │
│  无字段名/tag，data class 即 schema                      │
└─────────────────────────────────────────────────────────┘
```

**为什么握手和包要分离**：

握手包是每条 TCP 连接的固定流程——客户端必然先发握手包，服务端必然先回响应包。这个顺序是隐含的，不需要用 PacketType 字节来标记"这是一个 CONNECT 包"。把握手从包中独立出来有两个好处：

1. **握手包可以塞更多校验逻辑**：Magic 快速排雷、token 长度限制防攻击、版本协商——这些都在包解码器启动之前完成，不消耗包处理资源
2. **PacketType 编码空间不被浪费**：CONNECT/CONNACK 是必定发生的流程步骤，不是可选的包类型。1 字节（0-255）的编码空间全部留给包语义，通过编码范围区分大类，通过语义拍平消除 SEND/RECV 的"万能容器"反模式

**包生命周期示例**（发送一条文本消息）：

```
0. 握手（连接建立时，固定流程）
   Client → Server:  Magic(7B) + Version(1B) + token + uid + deviceId + ...
   Server → Client:  Magic(7B) + Version(1B) + Code(0) + reason("ok")

1. 发送消息（PacketType 直接表达语义，无 SEND 容器）
   Client → Server:  TEXT(20) 包 { channelId,
                                     clientMsgNo, clientSeq, text, ... }
   Server → Client:  SENDACK(80) 包 { clientMsgNo, messageId, serverSeq }

2. 投递消息（PacketType 统一）
   Server → Client:  TEXT(20) 包 { channelId, clientMsgNo, clientSeq,
                                     messageId, senderUid, channelType,
                                     serverSeq, timestamp, text, ... }
   Client → Server:  RECVACK(81) 包 { channelId, serverSeq }

3. 心跳保活
   Client → Server:  PING(2) 包（30 秒空闲时发送）
   Server → Client:  PONG(3) 包
```

**演进路径**：

| Phase | 内容 |
|-------|------|
| **Phase 1（当前）** | VLQ 包头（Version + Type + VLQ Length）+ JSON 负载（kotlinx.serialization）+ 12 种 PacketType（SEND/RECV 容器 + MessageType 二次分派） |
| **Phase 2（协议重构）** | 独立握手包（Magic + Version + token 校验）+ 固定包头（Type 1B + Length 4B，≤16 MB）+ field-order binary 负载 + 语义拍平 PacketType（编码范围区分大类，无 SEND/RECV 中间层）。详见 [03-binary-encoding.md](../design/03-binary-encoding.md) |
| **Phase 3** | Container 批量打包（已决策不采用，理由见 [03](../design/03-binary-encoding.md) §7）→ 零拷贝优化（readRetainedSlice + CompositeByteBuf）+ write-without-flush 批量发送 |
| **Phase 4** | CMD 包承载服务端推送事件（编辑通知、在线状态变更等） |

---

> **安全相关**：加密策略不在本文档讨论范围内，详见 [04-security.md](../design/04-security.md)。
