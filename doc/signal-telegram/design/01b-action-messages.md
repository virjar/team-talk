# 操作消息定义

> 承载用户交互操作的消息类型（回复、转发、撤回、编辑、表情回应）。本文档从 [01-message-model.md](./01-message-model.md) 拆分而来。
>
> PacketType 编码方案：消息 20-36（双向统一）。详见 [03-binary-encoding.md](./03-binary-encoding.md) §2.2。
>
> **Payload 字段定义**：每种 PacketType 的 payload 字段见 [03a-payload-definitions.md](./03a-payload-definitions.md)（单一真相源）。本文档仅提供 Signal/Telegram 对比分析和设计决策。

---

## 4.1 回复消息 — PacketType.REPLY(27)

**Signal**：`DataMessage.quote`：

```
Quote
  ├── id: long                  // 原消息 timestamp（即原消息 ID）
  ├── author: string            // 原消息发送者 UID
  ├── text: string              // 原消息文本预览
  └── attachments: [QuotedAttachment]  // 原消息附件预览
```

**Telegram**：`MessageReplyHeader`：

```
MessageReplyHeader
  ├── replyToMsgId: int         // 原消息 ID
  ├── replyToPeerId: Peer       // 原消息所在频道（支持跨频道回复）
  ├── replyToTopId: int         // 主题帖消息 ID（论坛模式）
  └── forumTopic: bool          // 是否是论坛主题
```

**TeamTalk 设计**：

采用**二进制内联**方案引用原消息。`ReplyPayload` 包含：

- `replyToPacketType: Byte` — 原消息的 PacketType，决定如何解码下方的 content bytes
- `replyToPayloadBytes: ByteArray?` — 原消息的 content bytes（按 `replyToPacketType` 对应的 IProto 规则解码），null 表示原消息已删除

**二进制内联 vs JSON 嵌套**：旧方案将原消息序列化为 JSON 字符串塞入 `replyToPayload` 字段，需要二次 JSON 解析。新方案直接嵌入二进制 content bytes，客户端根据 `replyToPacketType` 选择对应的解码器读取原消息字段（url、width、height 等），一次二进制读取完成，无需中间 String 转换。

Content bytes 是原消息 Payload 去掉 `[MessagePrefix]`（channelId 等路由字段）和 `flags` 后的字段——这些在回复消息自身中已存在，无需重复。各类型 content 字段列表见 [03a](./03a-payload-definitions.md) §1.5。

完整字段定义见 [03a-payload-definitions.md](./03a-payload-definitions.md) §3.1。

**优先级**：已有实现，二进制内联改造为协议升级的一部分。

---

## 4.2 转发消息 — PacketType.FORWARD(28)

**Signal**：无显式转发消息类型。转发 = 复制原消息内容发送到新频道。`DataMessage` 无转发元数据。

**Telegram**：`MessageFwdHeader`，附加在转发后的消息上：

```
MessageFwdHeader
  ├── fromId: Peer               // 原始发送者
  ├── date: int                  // 原始发送时间
  ├── channelPost: int           // 如果来自频道，原消息 ID
  ├── postAuthor: string         // 频道作者署名
  ├── savedFromPeer: Peer        // 转发来源频道
  ├── savedFromMsgId: int        // 转发来源消息 ID
  └── psaType: string            // 公共服务公告类型
```

**TeamTalk 设计**：

采用**二进制内联**方案。`ForwardPayload` 包含：

- `forwardedPacketType: Byte` — 原消息的 PacketType
- `forwardedPayloadBytes: ByteArray` — 原消息的 content bytes

同时携带溯源信息：`fromChannelId`（来源频道）、`fromMessageId`（原消息 ID）、`fromSenderUid`/`fromSenderName`（原始发送者）、`fromTimestamp`（原始发送时间）。Telegram 的 `MessageFwdHeader` 提供了更完整的溯源信息（频道帖子、作者署名），TeamTalk 当前设计已覆盖办公 IM 的核心需求。

与 REPLY 的差异：FORWARD 的 `forwardedPayloadBytes` 不可为 null（转发时原消息必然存在），且 FORWARD 不包含自身的新文本内容——它纯粹是原消息的搬运。

完整字段定义见 [03a-payload-definitions.md](./03a-payload-definitions.md) §3.2。

**优先级**：中。转发功能已部分实现，二进制内联改造需补全。

---

## 4.3 合并转发 — PacketType.MERGE_FORWARD(29)

**Signal**：无合并转发功能。

**Telegram**：无独立合并转发类型，使用 `messages.forwardMessages` 批量转发，客户端在 UI 上以"气泡组"展示。

**TeamTalk 设计**：

合并转发是 TeamTalk 的原创设计（Signal/Telegram 均无独立类型），用于将多条消息打包为一个整体转发。采用**二进制内联**方案，每条内联消息的 payload 以 content bytes 嵌入。

核心子结构：

- **MergeForwardMessage** — 每条内联消息，包含 `packetType: Byte`（原消息 PacketType）和 `contentBytes: ByteArray`（原消息 content bytes），加上 `messageId`、`fromUid`、`timestamp` 等元数据
- **MergeForwardUser** — 用户信息表（`uid`、`name`、`avatar?`），多条消息共享同一用户，通过 `fromUid` 关联，避免重复

与单条 FORWARD 的关键区别：MERGE_FORWARD 的 content bytes 嵌入在 `MergeForwardMessage` 子结构中（每条消息独立），而非直接在 Payload 顶层。

完整字段定义见 [03a-payload-definitions.md](./03a-payload-definitions.md) §3.3。

**优先级**：中。功能已有基础实现，二进制内联改造需同步更新。

---

## 4.4 撤回消息 — PacketType.REVOKE(30)

**Signal**：`DataMessage.delete`（每条消息可删除）：

```
Delete
  ├── targetSentTimestamp: long    // 要删除的消息的时间戳
  └── fullDelete: bool             // 是否同时从自己设备删除
```

**Telegram**：`messages.deleteMessages(channel, id: [42, 43])`，RPC 调用后服务端通过 `updateDeleteMessages` 广播给所有参与者。没有"撤回"概念，只有"删除"。

```
updateDeleteMessages
  ├── messages: Vector<int>    // 被删除的消息 ID 列表
  ├── channel: InputChannel    // 所属频道
  └── pts: int                 // 位置状态号（用于增量同步）
```

**TeamTalk 设计**：

REVOKE 是独立的消息类型——撤回操作本身会产生一条 REVOKE 消息记录。接收方看到 REVOKE 消息后，根据 `targetMessageId` 找到原消息并标记为已撤回。这与 Signal（纯删除指令）和 Telegram（RPC 调用 + 广播更新）的设计路径不同，TeamTalk 选择"产生通知消息"的模式，便于消息流的完整追溯。

Payload 极简：上行仅含 `targetMessageId`，下行加上标准 `[MessagePrefix]`。

完整字段定义见 [03a-payload-definitions.md](./03a-payload-definitions.md) §3.4。

**补充设计要求**：
- 撤回时间窗口（如 2 分钟内可撤回）应由服务端强制校验
- 原消息设置 `isDeleted = true`（软删除），REVOKE 消息本身保留用于通知

**优先级**：已有实现。

---

## 4.5 编辑消息 — PacketType.EDIT(31)

**Signal**：`DataMessage.edit`，递归嵌套：

```
Edit
  ├── targetSentTimestamp: long   // 原消息时间戳（即 ID）
  └── dataMessage: DataMessage    // 新内容（完整的 DataMessage）
```

服务端收到后找到原消息，用新 DataMessage 替换内容，标记 `editedAt`。

**Telegram**：`messages.editMessage(peer, id, message)`，RPC 调用。服务端通过 `updateEditMessage` 广播：

```
updateEditMessage
  ├── message: Message          // 更新后的完整 Message 对象
  └── pts: int                  // 位置状态号
```

Telegram 保留编辑历史（`editDate` 字段），客户端可选择展示。

**TeamTalk 设计**：

EditPayload 包含 `targetMessageId`（定位原消息）和 `newContent`（新文本）。EditPayload 额外包含 `editedAt`（编辑时间戳）。

- 服务端收到 EDIT(31) 后：找到 targetMessageId → 更新 payload → 设置 editedAt → 广播 EDIT(31) 给其他成员
- 原消息内容不保留历史（办公 IM 不需要编辑历史审计）

Signal 的方案递归嵌套完整的 DataMessage，Telegram 通过 RPC 替换后广播完整 Message 对象。TeamTalk 采用更轻量的方式：只传新文本内容，服务端就地更新，无需传输完整的消息结构。

完整字段定义见 [03a-payload-definitions.md](./03a-payload-definitions.md) §3.5。

**优先级**：中（P1 功能）。

---

## 4.6 表情回应 — PacketType.REACTION(34)

**Signal**：`DataMessage.reaction`：

```
Reaction
  ├── emoji: string             // 回应的 emoji
  ├── remove: bool              // true = 取消回应
  └── targetSentTimestamp: long // 目标消息时间戳
```

Signal 的 Reaction 不创建新消息——它修改目标消息的元数据（通过服务端的 `SendGroupMessageResponse`）。

**Telegram**：`MessageReactions` 附加在消息上：

```
MessageReactions
  ├── results: [ReactionCount]
  │     ├── reaction: Reaction      // emoji 或 custom_emoji
  │     │     ├── emoticon: string
  │     │     └── customEmojiId: int64
  │     └── count: int
  ├── recentReactions: [MessagePeerReaction]
  │     ├── peer: Peer              // 回应者
  │     └── reaction: Reaction
  └── canSeeList: bool              // 是否支持查看完整回应列表
```

**TeamTalk 设计**：

ReactionPayload 包含 `targetMessageId`（目标消息）、`emoji`（emoji 字符）、`remove`（取消标记）。

- Reaction 是轻量操作，不产生持久化的消息记录（flags 含 NO_PERSIST）
- 服务端收到 REACTION(34) 后更新目标消息的 `reactionSummary` 聚合数据，然后广播 REACTION(34) 给其他成员

Signal 的 Reaction 通过修改目标消息元数据实现（不产生独立消息），Telegram 将 Reaction 聚合为带计数的列表附加在消息上。TeamTalk 选择独立的包类型传输 Reaction 事件，服务端负责聚合——这种设计在二进制协议中更自然，不需要修改已有消息的存储格式。

完整字段定义见 [03a-payload-definitions.md](./03a-payload-definitions.md) §3.6。

**优先级**：低（P2 功能）。办公 IM 中使用频率低于社交 IM。
