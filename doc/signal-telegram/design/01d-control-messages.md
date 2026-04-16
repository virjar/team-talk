# 控制消息定义

> 输入状态、命令消息、确认消息、在线状态等控制类消息。本文档从 [01-message-model.md](./01-message-model.md) 拆分而来。
>
> PacketType 编码方案：ACK 80-89，控制 100-109。详见 [03-binary-encoding.md](./03-binary-encoding.md) §2.2。
>
> **Payload 字段定义**：每种 PacketType 的 payload 字段见 [03a-payload-definitions.md](./03a-payload-definitions.md)（单一真相源）。本文档仅提供 Signal/Telegram 对比分析和设计决策。

---

## 6.1 输入状态 — PacketType.TYPING(32)

**Signal**：无公共输入状态（Sealed Sender 限制）。仅在加密会话中有 `typingMessage`（Protobuf）：

```
TypingMessage
  ├── timestamp: long
  └── action: STARTED | STOPPED
```

**Telegram**：`messages.setTyping(peer, action)`，action 有多种：

```
SendMessageTypingAction          — 正在输入文本
SendMessageChooseContactAction   — 正在选择联系人
SendMessageUploadPhotoAction     — 正在上传图片 (progress: int)
SendMessageRecordVideoAction     — 正在录制视频
SendMessageUploadVideoAction     — 正在上传视频 (progress: int)
SendMessageRecordAudioAction     — 正在录音
SendMessageUploadAudioAction     — 正在上传语音 (progress: int)
SendMessageUploadDocumentAction  — 正在上传文件 (progress: int)
SendMessageGeoLocationAction     — 正在选择位置
SendMessageChooseStickerAction   — 正在选择贴纸
SendMessageEmojiInteraction      — emoji 交互动画
```

**TeamTalk 设计**：

```
TypingPayload(32):  [MessagePrefix] + action:Byte
```

> 完整字段定义见 [03a-payload-definitions.md](./03a-payload-definitions.md)，action 枚举在 [03-binary-encoding.md](./03-binary-encoding.md) §3.4 的 TypingPayload 中。

**设计说明**：
- `action`：0=text, 1=voice, 2=image, 3=file（使用 Byte 而非 String，与 03a 定义一致）
- TYPING 消息设置 flags = NO_PERSIST，不存储到数据库
- 服务端收到后广播给频道其他成员（flags 含 SYNC_ONCE）
- 客户端 5 秒未收到新的 TYPING 自动停止显示

**优先级**：中（P1 功能）。

---

## 6.2 命令消息 — PacketType.CMD(100)

**Signal**：无等价物。Signal 服务端不做命令推送。

**Telegram**：Updates 事件流是核心推送通道，所有服务端发起的事件都通过 Updates 推送：

```
updateNewMessage          — 新消息
updateEditMessage         — 消息编辑
updateDeleteMessages      — 消息删除
updateUserTyping          — 输入状态
updateReadHistoryOutbox   — 已读回执
updateUserStatus          — 在线状态
updateGroupCall           — 群通话
updateBotWebhookJSON      — Bot webhook
... 50+ 种 Update 类型
```

**TeamTalk 设计**：

```
CmdPayload(100):
  cmdType:String + payload:String
```

> 完整字段定义见 [03a-payload-definitions.md](./03a-payload-definitions.md) §5.1。

**设计说明**：
- `payload` 为 JSON 字符串——CMD 是通用服务端推送通道，`cmdType` 决定 payload 的 schema
- 这是二进制协议中**有意保留的 JSON 例外**：CMD 消息低频、类型多变，为每种 cmdType 定义 IProto 结构的 ROI 太低
- 建议的 cmdType 枚举：`read_sync` / `presence` / `conversation_sync` / `channel_sync` / `link_preview`

**优先级**：高。CMD 包是多设备同步和服务端推送的基础设施。

---

## 6.3 确认消息 — PacketType.SENDACK(80) / RECVACK(81)

**Signal**：无显式 ACK（依赖 HTTP/WebSocket 可靠传输）。

**Telegram**：`msgs_ack`，确认收到 Update：

```
msgs_ack
  ├── msgIds: Vector<long>     // 被确认的消息 ID 列表
```

**TeamTalk 设计**：

```
SendAckPayload(80):
  messageId:String + clientMsgNo:String + clientSeq:VarInt + serverSeq:VarInt + code:Byte

RecvAckPayload(81):
  messageId:String + channelId:String + channelType:Byte + serverSeq:VarInt
```

> 完整字段定义见 [03a-payload-definitions.md](./03a-payload-definitions.md) §6。

**设计说明**：
- `SendAckPayload.code`：0=成功, 非0=错误码
- 两级 ACK（SENDACK + RECVACK）设计合理，保持不变

**优先级**：已实现。

---

## 6.4 在线状态 — PacketType.PRESENCE(102)

**Signal**：无在线状态显示。用户隐私优先设计。

**Telegram**：`updateUserStatus` 推送，支持多种状态：

```
UserStatus
  ├── UserStatusOnline(expires: int)     // 在线，含过期时间
  ├── UserStatusOffline(wasOnline: int)  // 离线，含最后在线时间
  ├── UserStatusRecently                 // 最近一周内在线
  ├── UserStatusLastWeek                 — 最近一个月内在线
  └── UserStatusLastMonth                — 更久
```

Telegram 的隐私设计：用户可选择谁看到自己的在线状态（所有人/仅联系人/无人），且提供模糊的"最近在线"状态。

**TeamTalk 设计**：

```
PresencePayload(102):
  uid:String + status:Byte + lastSeenAt:VarInt
```

> 完整字段定义见 [03a-payload-definitions.md](./03a-payload-definitions.md) §5.3。

**设计说明**：
- `status`：0=offline, 1=online（使用 Byte 而非 String，与 03a 定义一致）
- `lastSeenAt`：offline 时为最后在线时间 (epoch ms)，online 时为 0
- PRESENCE 是独立的 PacketType(102)，不通过 CMD 包承载
- PRESENCE 消息设置 flags = NO_PERSIST | SYNC_ONCE
- 用户上线时服务端广播 PRESENCE(online) 给其好友
- 用户下线时服务端广播 PRESENCE(offline, lastSeenAt)

**优先级**：中（P2 功能）。办公 IM 中"看到同事是否在线"是实用功能。
