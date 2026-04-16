# Payload 字段定义

> TeamTalk 协议的**单一真相源**——所有 PacketType 的 payload 字段定义。
>
> - 编码规则（IProto 接口、ByteBuf 读写、零拷贝）见 [03-binary-encoding.md](./03-binary-encoding.md)
> - 各消息类型的设计决策和 Signal/Telegram 对比分析见 [02 系列文档](./01-message-model.md)
> - PacketType 编码方案：消息 20-36（双向统一），ACK 80-81，系统 90-98，控制 100-102

---

## 1. 约定

### 1.1 命名规则

| 类别 | 命名模式 | 方向 |
|------|---------|------|
| 消息 payload | `XXXPayload` | 双向统一（Client ⇄ Server） |
| 系统消息 payload | `XXXPayload` | Server → Client |
| 控制消息 payload | `XXXPayload` | Server → Client（CMD/ACK/PRESENCE） |
| 确认消息 payload | `XXXAckPayload` | 双向（SENDACK/RECVACK 各自独立） |

### 1.2 公共前缀

所有消息 payload 共享前 8 个字段（路由、幂等与元数据）。下文用 `[MessagePrefix]` 缩写。上行时服务端字段为 null/0，服务端填充后直接转发同一结构。

```
[MessagePrefix]:
  --- 客户端填写 ---
  channelId: String       // 目标频道
  clientMsgNo: String     // 客户端幂等 ID
  clientSeq: VarInt       // 客户端序列号
  --- 服务端填充（上行 null/0）---
  messageId: String?      // 服务端 UUID
  senderUid: String?      // 发送者 UID
  channelType: Byte       // 频道类型 1=个人 2=群组 3=系统（服务端根据 channelId 查库填充，不信任客户端）
  serverSeq: VarInt       // 频道内单调递增
  timestamp: VarInt       // 服务端接收时间
```

### 1.3 可空字段

| 类型 | null / 默认值 | 编码 |
|------|-------------|------|
| String | null | len = -1 (short) |
| ByteArray | null | len = -1 (int) |
| VarInt | 0 = 默认/不存在 | 值本身 |
| List | 空列表 | count = 0 (short) |
| Byte | 不支持 null | 使用哨兵值 |

### 1.4 复合对象列表

复合对象列表编码为 `count:Short + [对象字段 × N]`。每个对象按其字段定义顺序读写。

示例：`List<MergeForwardUser>` → `short(count) + [string(uid) + string(name) + string?(avatar)] × N`

### 1.5 Content Bytes（二进制内联）

REPLY / FORWARD / MERGE_FORWARD 引用原消息时，内联原消息的 **content bytes**——即原类型 Payload 中去掉 `[MessagePrefix]` 和 `flags` 后的字段。下表列出各类型的 content 字段：

| PacketType | Content 字段（去除 MessagePrefix 和 flags） |
|-----------|--------------------------------------|
| TEXT(20) | text, mentionUids |
| IMAGE(21) | url, width, height, size, thumbnailUrl?, caption? |
| VOICE(22) | url, duration, size, waveform? |
| VIDEO(23) | url, width, height, size, duration, coverUrl? |
| FILE(24) | url, fileName, fileSize, mimeType?, thumbnailUrl? |
| LOCATION(25) | latitude, longitude, title?, address? |
| CARD(26) | uid, name, avatar?, phone? |
| STICKER(33) | stickerId, packId?, url, emoji?, width, height |
| INTERACTIVE(35) | botId, templateType, title, content?, imageUrl?, buttons |
| RICH(36) | segments, mentionUids |

> **设计理由**：去除路由字段（channelId 等）和 flags 是因为它们在回复/转发消息自身中已存在，无需重复。content bytes 仅包含业务展示所需的字段。

---

## 2. 内容消息 Payload（20-36，双向统一）

### 2.1 TEXT(20)

```
TextPayload(20):  [MessagePrefix] + text:String + mentionUids:List<String> + flags:Byte
```

完整 IProto 编码示例见 [03-binary-encoding.md](./03-binary-encoding.md) §3.2。

### 2.2 IMAGE(21)

```
ImagePayload(21):  [MessagePrefix] + url:String + width:VarInt + height:VarInt + size:VarInt + thumbnailUrl:String? + caption:String? + flags:Byte
```

- `size`：文件大小 (bytes)
- `thumbnailUrl`：缩略图地址（可选，避免加载原图）
- `caption`：图片描述（参考 Signal/Telegram 设计）

### 2.3 VOICE(22)

```
VoicePayload(22):  [MessagePrefix] + url:String + duration:VarInt + size:VarInt + waveform:ByteArray? + flags:Byte
```

- `url`：Opus 音频下载地址
- `duration`：秒
- `waveform`：归一化波形 [0-255]，客户端录制时生成，服务端只存不渲染

### 2.4 VIDEO(23)

```
VideoPayload(23):  [MessagePrefix] + url:String + width:VarInt + height:VarInt + size:VarInt + duration:VarInt + coverUrl:String? + flags:Byte
```

- `duration`：秒
- `coverUrl`：视频封面图地址

### 2.5 FILE(24)

```
FilePayload(24):  [MessagePrefix] + url:String + fileName:String + fileSize:VarInt + mimeType:String? + thumbnailUrl:String? + flags:Byte
```

- `mimeType`：MIME 类型（用于选择预览方式和文件图标）
- `thumbnailUrl`：缩略图（图片/PDF 预览）

### 2.6 LOCATION(25)

```
LocationPayload(25):  [MessagePrefix] + latitude:Int + longitude:Int + title:String? + address:String? + flags:Byte
```

- `latitude` / `longitude`：原始值 × 1_000_000 取整（精度 ~0.11m）。如 39.9042° → 39904200
- 使用固定 4 字节 Int（非 VarInt），因为经纬度可为负数

### 2.7 CARD(26)

```
CardPayload(26):  [MessagePrefix] + uid:String + name:String + avatar:String? + phone:String? + flags:Byte
```

- `uid`：被分享用户的 UID，接收方可直接点击添加好友或发起聊天

### 2.8 STICKER(33)

```
StickerPayload(33):  [MessagePrefix] + stickerId:String + packId:String? + url:String + emoji:String? + width:VarInt + height:VarInt + flags:Byte
```

- `url`：贴纸图片地址（webp 格式）
- `emoji`：对应的 emoji（用于输入提示）

### 2.9 INTERACTIVE(35)

> **设计决策**：Bot 作为虚拟用户（用户表 `userType` 字段区分），交互式卡片使用独立 PacketType 承载。办公 IM 中机器人用于 AI 助手、内部系统查询、审批流程、监控报警等场景，是核心竞争力之一。
>
> 对比分析见 [01a-content-messages.md](./01a-content-messages.md) §3.10。

```
InteractivePayload(35):  [MessagePrefix]
  + botId:String              // 机器人 UID
  + templateType:Byte         // 模板类型: 0=自定义卡片
  + title:String              // 卡片标题
  + content:String?           // 卡片正文（简单 Markdown）
  + imageUrl:String?          // 卡片封面图
  + buttons:List<InteractiveButton>
  + flags:Byte

InteractiveButton:
  label:String                // 按钮文字
  action:Byte                 // 0=callback(回调服务端), 1=url(打开链接)
  value:String                // 动作值
  style:Byte                  // 0=默认, 1=主要(蓝色), 2=危险(红色)
```

- `templateType`：预留模板类型，0=自定义卡片，未来可扩展审批模板、通知模板等
- `buttons`：按钮列表，最多 4 个（办公场景不宜过多）
- 按钮点击回调：用户点击按钮 → 客户端发送 CMD(`bot_callback`) → 服务端转发给 Bot HTTP 回调地址
- Bot 发送消息通过 HTTP API，服务端收到后以 INTERACTIVE(35) 推送给客户端
- 机器人也可发送普通消息类型（TEXT/IMAGE/FILE 等），INTERACTIVE 仅用于需要交互按钮的场景

### 2.10 RICH(36)

> **设计决策**：富文本消息采用分段模型，消息由 segment 有序列表组成。短期作为混合内容的补充类型（简单消息仍用 TEXT/IMAGE 等），长期统一所有用户消息（TEXT/IMAGE 降级为 RICH 的语法糖）。
>
> 对比分析见 [01a-content-messages.md](./01a-content-messages.md) §3.11。

```
RichPayload(36):  [MessagePrefix]
  + segments:List<Segment>
  + mentionUids:List<String>
  + flags:Byte
```

**Segment 编码**：每个 Segment 首字节 `segmentType:Byte` 标识类型，后续字段按该类型定义顺序读写。编码器/解码器通过 `when(segmentType)` 分派。

```
Segment 类型:

  TEXT(0):   text:String
  IMAGE(1):  url:String + width:VarInt + height:VarInt + size:VarInt + thumbnailUrl:String?
  AT(2):     uid:String + name:String
  VIDEO(3):  url:String + width:VarInt + height:VarInt + size:VarInt + duration:VarInt + coverUrl:String?
  FILE(4):   url:String + fileName:String + fileSize:VarInt + mimeType:String?
  LINK(5):   url:String + title:String? + description:String? + imageUrl:String?
```

**渲染规则**：
- **内联段**（TEXT/AT）：不换行，连续内联段拼接为一行文字
- **块级段**（IMAGE/VIDEO/FILE/LINK）：独占一行，上下有间距

**mentionUids**：所有 AT 段中出现的 uid 去重汇总，服务端据此发送通知。

**初始实现范围**：TEXT(0) + IMAGE(1) + AT(2)。其余为远期扩展。

---

## 3. 操作消息 Payload

### 3.1 REPLY(27) — 二进制内联

> **设计决策**：采用**二进制内联**引用原消息。`replyToPayloadBytes` 包含原消息的 content bytes（见 §1.5），按 `replyToPacketType` 对应的 IProto 规则解码。彻底消灭 JSON 嵌套。
>
> 对比分析见 [01b-action-messages.md](./01b-action-messages.md) §4.1。

```
ReplyPayload(27):  [MessagePrefix]
  + text:String                          // 回复文本
  + mentionUids:List<String>
  + replyToMessageId:String              // 原消息 ID
  + replyToSenderUid:String              // 原消息发送者
  + replyToSenderName:String?            // 原消息发送者名称
  + replyToPacketType:Byte                // 原消息的 PacketType
  + replyToPayloadBytes:ByteArray?       // 原消息 content bytes（null=已删除）
  + flags:Byte
```

**解码说明**：客户端根据 `replyToPacketType` 选择对应的 `XXXPayload` 解码器，从 `replyToPayloadBytes` 中读取原消息的 content 字段（不含 MessagePrefix 和 flags）。

### 3.2 FORWARD(28) — 二进制内联

> **设计决策**：转发消息内联原消息的完整 content bytes + 溯源信息，采用二进制编码。Signal 无显式转发类型（转发=复制原消息），Telegram 用 `MessageFwdHeader` 附加溯源元数据。
>
> 对比分析见 [01b-action-messages.md](./01b-action-messages.md) §4.2。

```
ForwardPayload(28):  [MessagePrefix]
  + fromChannelId:String?                // 原始频道 ID
  + fromMessageId:String                 // 原始消息 ID
  + fromSenderUid:String                 // 原始发送者 UID
  + fromSenderName:String?               // 原始发送者名称
  + fromTimestamp:VarInt                 // 原始发送时间 (epoch ms)
  + forwardedPacketType:Byte              // 原消息的 PacketType
  + forwardedPayloadBytes:ByteArray      // 原消息 content bytes
  + flags:Byte
```

### 3.3 MERGE_FORWARD(29) — 二进制内联

> **设计决策**：合并转发是 TeamTalk 的原创设计（Signal/Telegram 均无独立类型）。多消息打包转发，每条消息的 payload 以二进制 content bytes 内联。
>
> 对比分析见 [01b-action-messages.md](./01b-action-messages.md) §4.3。

```
MergeForwardPayload(29):  [MessagePrefix]
  + messages:List<MergeForwardMessage>
  + users:List<MergeForwardUser>
  + flags:Byte
```

子结构：

```
MergeForwardMessage:
  messageId:String?          // 原消息 ID（可 null）
  fromUid:String             // 原消息发送者 UID
  timestamp:VarInt           // 原消息发送时间 (epoch ms)
  packetType:Byte             // 原消息 PacketType
  contentBytes:ByteArray     // 原消息 content bytes

MergeForwardUser:
  uid:String
  name:String
  avatar:String?
```

`users` 列表避免每条消息重复用户信息，客户端通过 `fromUid` 关联。

### 3.4 REVOKE(30)

```
RevokePayload(30):  [MessagePrefix]
  + targetMessageId:String              // 要撤回的消息 ID
  + flags:Byte
```

撤回操作会产生独立的 REVOKE 消息记录，接收方据此将原消息标记为 `isDeleted=true`。

### 3.5 EDIT(31)

```
EditPayload(31):  [MessagePrefix]
  + targetMessageId:String              // 要编辑的原消息 ID
  + newContent:String                   // 新文本内容
  + editedAt:VarInt                     // 编辑时间 (epoch ms)（上行 0，服务端填充）
  + flags:Byte
```

服务端收到 EDIT(31) 后：找到 targetMessageId → 更新 payload → 设置 editedAt → 广播 EDIT(31) 给其他成员。原消息内容不保留历史。

### 3.6 REACTION(34)

```
ReactionPayload(34):  [MessagePrefix]
  + targetMessageId:String              // 目标消息 ID
  + emoji:String                        // emoji 字符（如 "👍"）
  + remove:Boolean                      // true=取消回应
  + flags:Byte
```

Reaction 不产生持久化消息记录（flags 含 NO_PERSIST），服务端更新目标消息的 `reactionSummary` 聚合数据后广播。

---

## 4. 系统消息 Payload（90-98，仅服务端→客户端）

系统消息仅服务端发送。

### 4.1 频道生命周期

```
ChannelCreatedPayload(90):
  channelId:String + channelType:Byte + channelName:String + creatorUid:String

ChannelUpdatedPayload(91):
  channelId:String + channelType:Byte + field:String + oldValue:String? + newValue:String? + operatorUid:String

ChannelDeletedPayload(92):
  channelId:String + channelType:Byte + operatorUid:String
```

- `field`：变更字段名（"name" | "avatar" | "announcement"）

### 4.2 成员变更

```
MemberAddedPayload(93):
  channelId:String + channelType:Byte + memberUid:String + memberName:String? + inviterUid:String

MemberRemovedPayload(94):
  channelId:String + channelType:Byte + memberUid:String + memberName:String? + operatorUid:String + reason:Byte
```

- `reason`：0=主动退出, 1=被踢出

### 4.3 群设置

```
MemberMutedPayload(95):
  channelId:String + memberUid:String + operatorUid:String + duration:VarInt

MemberUnmutedPayload(96):
  channelId:String + memberUid:String + operatorUid:String

MemberRoleChangedPayload(97):
  channelId:String + memberUid:String + operatorUid:String + oldRole:Byte + newRole:Byte

ChannelAnnouncementPayload(98):
  channelId:String + content:String + operatorUid:String
```

- `duration`：禁言时长（秒），0=永久
- `role`：0=普通成员, 1=管理员, 2=群主

---

## 5. 控制消息 Payload（100-102）

### 5.1 CMD(100)

```
CmdPayload(100):
  cmdType:String + payload:String
```

- `payload` 为 JSON 字符串——CMD 是通用服务端推送通道，cmdType 决定 payload 的 schema
- 这是二进制协议中**有意保留的 JSON 例外**：CMD 消息低频、类型多变，为每种 cmdType 定义 IProto 结构的 ROI 太低
- 建议 cmdType 枚举：`read_sync` / `presence` / `conversation_sync` / `channel_sync`

### 5.2 ACK(101)

```
AckPayload(101):
  code:Byte + messageId:String?
```

- `code`：0=成功, 非0=错误码

### 5.3 PRESENCE(102)

```
PresencePayload(102):
  uid:String + status:Byte + lastSeenAt:VarInt
```

- `status`：0=offline, 1=online
- `lastSeenAt`：offline 时为最后在线时间 (epoch ms)，online 时为 0

---

## 6. 确认消息 Payload（80-81）

```
SendAckPayload(80):
  messageId:String + clientMsgNo:String + clientSeq:VarInt + serverSeq:VarInt + code:Byte

RecvAckPayload(81):
  messageId:String + channelId:String + channelType:Byte + serverSeq:VarInt
```

- `SendAckPayload.code`：0=成功, 非0=错误码
