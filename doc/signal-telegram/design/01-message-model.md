# 消息模型定义

> 参考 Signal / Telegram 定义 TeamTalk 完备的消息模型：基础结构、内容类型、操作消息、系统消息、控制消息、生命周期。
>
> **与 [03-binary-encoding.md](./03-binary-encoding.md) 的关系**：本文档定义每种 PacketType 的**逻辑 payload 字段**（消息携带什么信息），03 定义**二进制编码规则**（如何用 IProto 读写这些字段）。PacketType 编码方案：消息 20-36（双向统一），ACK 80-81，系统 90-98，控制 100-102。各类型完整的 payload 字段定义见 [03a-payload-definitions.md](./03a-payload-definitions.md)。

---

## 1. 基础消息结构

### 1.1 三方基础字段对比

| 字段 | Signal | Telegram | TeamTalk 现状 | TeamTalk 建议 |
|------|--------|----------|--------------|--------------|
| 消息 ID | timestamp（时间戳即 ID） | message_id（自增整数） | messageId（UUID） | 保持 messageId（UUID） |
| 发送者 | source + sourceDevice | from_id（Peer 对象） | senderUid | 保持 senderUid |
| 目标频道 | 无（点对点加密，服务端按收件人路由） | peer_id（Peer: User/Chat/Channel） | channelId + channelType | 保持 |
| 消息类型 | 由 DataMessage 字段推断 | flags + media 类型 | PacketType（语义拍平） | 消灭 MessageType，PacketType 即语义（见 [03](./03-binary-encoding.md) §2.2） |
| 负载 | DataMessage (Protobuf) | message + media + entities | payload (JSON 字符串) | field-order binary (IProto)，每种 PacketType 有独立 payload 结构 |
| 时间戳 | timestamp (uint64, ms) | date (int32, 秒) | timestamp (Long, ms) | 保持 |
| 序列号 | 无 | id (int32, 频道内自增) | serverSeq (Long, 频道内自增) | 保持 |
| 客户端序列号 | 无 | random_id (int64, 幂等去重) | clientSeq + clientMsgNo | 保持 |
| 标志位 | 无（字段级控制） | flags (int32, 位图) | flags (Int, 位图) | 扩展（见 §7） |
| 已删除 | 无（服务端删除后不通知） | 无（删除通过 Update 推送） | isDeleted (Boolean) | 保持 |
| 置顶 | 无 | 无（单独 API） | isPinned (Boolean) | 保持 |
| 编辑时间 | edit 中的 dataMessage | edit_date (int32) | 无 | 新增 editedAt: Long? |

### 1.2 TeamTalk 建议：MessageRecord 完整定义

> MessageRecord 是**存储层**模型。在线路上，每种 PacketType 有独立的 IProto payload 结构（见 [03](./03-binary-encoding.md) §3.3）。MessageRecord 是持久化后的统一视图。

```kotlin
data class MessageRecord(
    // === 身份 ===
    val messageId: String,           // 服务端分配的 UUID
    val clientMsgNo: String,         // 客户端生成的幂等 ID
    val channelId: String,           // 所属频道
    val channelType: Int,            // 频道类型
    val senderUid: String,           // 发送者 UID

    // === 内容 ===
    val packetType: Int,              // PacketType 编码值（消息 20-36 / 系统消息 90-98）
    val payload: String,             // JSON 序列化的 Content（存储层仍用 JSON，传输层用 IProto）

    // === 排序与同步 ===
    val serverSeq: Long,             // 频道内单调递增序列号
    val clientSeq: Long = 0,         // 客户端序列号
    val timestamp: Long,             // 服务端接收时间 (epoch ms)

    // === 状态 ===
    val flags: Int = 0,              // 位图标志（见 §7）
    val isDeleted: Boolean = false,  // 软删除标记
    val isPinned: Boolean = false,   // 置顶标记
    val expireAt: Long? = null,      // 过期时间（预留，暂不使用）

    // === 扩展（新增建议） ===
    val editedAt: Long? = null,      // 最后编辑时间，null=未编辑
    val replyCount: Int = 0,         // 回复数（冗余计数，避免频繁查询）
    val reactionSummary: String? = null, // JSON: [{"emoji":"👍","count":3}]
)
```

**与现状的差异**：
- `packetType` 取代 `messageType`：语义拍平后 PacketType 即消息类型，无二次分派
- `payload`：存储层保持 JSON（便于查询和调试），传输层由 IProto 二进制编码处理
- `editedAt`：编辑消息的关键字段，区分"已编辑"和"原始"消息
- `replyCount`：冗余计数，避免加载回复列表时全量查询
- `reactionSummary`：表情回应的聚合摘要，存在消息记录上避免独立查询

---

## 2. Channel 模型

### 2.1 三方 Channel 模型对比

| 维度 | Signal | Telegram | TeamTalk |
|------|--------|----------|----------|
| 1:1 标识 | recipientId (电话号码 hash) | InputPeerUser(user_id) | channelId=对方UID, channelType=1 |
| 群组标识 | GroupId (random) | InputPeerChat(chat_id) | channelId=groupNo, channelType=2 |
| 超级群/频道 | 无 | InputPeerChannel(channel_id) | 暂不支持 |
| 系统频道 | 无（系统通知是独立 API） | 无（Updates 事件流） | channelId="system", channelType=3 |
| 频道属性 | GroupContextV2 (master key) | Chat/Channel 对象 | Channel 对象 |

### 2.2 TeamTalk Channel 定义

```
channelType 枚举:
  1 = 个人频道 (1:1 聊天)
      channelId = 对方用户的 UID
      每对用户有且仅有一个频道（双向共享）
  2 = 群组频道
      channelId = 服务端分配的 groupNo
      群成员通过 member 表关联
  3 = 系统频道（预留）
      channelId = 固定值（如 "system_notify"）
      用于系统公告、全员通知等场景
```

**当前 Channel 数据结构**：

```kotlin
data class Channel(
    val channelId: String,
    val channelType: Int,
    val name: String = "",
    val avatar: String = "",
    val creator: String? = null,
    val status: Int = 1,
    val maxSeq: Long = 0,           // 频道内最大 seq（用于增量同步）
)
```

**建议扩展**（中期）：

```kotlin
data class Channel(
    // ... 现有字段 ...
    val memberCount: Int = 0,       // 成员数（冗余计数）
    val lastMessage: String? = null, // 最后一条消息摘要
    val muted: Boolean = false,     // 全频道静音
    val announcement: String? = null, // 群公告
    val maxMemberCount: Int = 500,  // 最大成员数
)
```

---

## 3. 内容消息

> 承载用户可见内容的消息类型。完整 payload 定义详见 [01a-content-messages.md](./01a-content-messages.md)。

| PacketType | 类型 | 详见 |
|-----------|------|------|
| 20-26 | TEXT / IMAGE / VOICE / VIDEO / FILE / LOCATION / CARD | [01a-content-messages.md](./01a-content-messages.md) |
| 33 | STICKER | [01a-content-messages.md](./01a-content-messages.md) §3.9 |
| 35-36 | INTERACTIVE / RICH | [01a-content-messages.md](./01a-content-messages.md) §3.10-3.11 |

---

## 4. 操作消息

> 回复、转发、撤回、编辑、表情回应。详见 [01b-action-messages.md](./01b-action-messages.md)。

---

## 5. 系统消息

> 频道生命周期、成员变更、群设置变更。详见 [01c-system-messages.md](./01c-system-messages.md)。

| PacketType | 类型 | 详见 |
|-----------|------|------|
| 90-92 | CHANNEL_CREATED / UPDATED / DELETED | [01c-system-messages.md](./01c-system-messages.md) §2 |
| 93-94 | MEMBER_ADDED / REMOVED | [01c-system-messages.md](./01c-system-messages.md) §3 |
| 95-98 | MEMBER_MUTED / UNMUTED / ROLE_CHANGED / ANNOUNCEMENT | [01c-system-messages.md](./01c-system-messages.md) §4 |

---

## 6. 控制消息

> 输入状态、命令、ACK、在线状态。详见 [01d-control-messages.md](./01d-control-messages.md)。

| PacketType | 类型 | 详见 |
|-----------|------|------|
| 80-81 | SENDACK / RECVACK | [01d-control-messages.md](./01d-control-messages.md) §2 |
| 100-102 | CMD / ACK / PRESENCE | [01d-control-messages.md](./01d-control-messages.md) §3-5 |

---

## 7. 消息标志位

### 7.1 当前标志位

```kotlin
object MessageFlags {
    const val NO_PERSIST = 1    // bit 0: 不存储到数据库
    const val RED_DOT = 2       // bit 1: 红点提醒
    const val SYNC_ONCE = 4     // bit 2: 只同步一次（不重复推送）
}
```

### 7.2 各消息类型建议的标志位

| 消息类型 | NO_PERSIST | RED_DOT | SYNC_ONCE | 说明 |
|---------|------------|---------|-----------|------|
| TEXT | ✗ | ✗ | ✗ | 普通消息，正常持久化 |
| IMAGE/VIDEO/VOICE/FILE | ✗ | ✗ | ✗ | 媒体消息，正常持久化 |
| REPLY/FORWARD/MERGE_FORWARD | ✗ | ✗ | ✗ | 操作消息，正常持久化 |
| REVOKE | ✗ | ✗ | ✗ | 撤回需要持久化（用于通知） |
| EDIT | ✗ | ✗ | ✗ | 编辑需要持久化 |
| REACTION | ✓ | ✗ | ✓ | 回应不持久化，同步一次即可 |
| TYPING | ✓ | ✗ | ✓ | 输入状态不持久化，同步一次 |
| PRESENCE | ✓ | ✗ | ✓ | 在线状态不持久化，同步一次 |
| CHANNEL_CREATED/UPDATED/DELETED | ✗ | ✗ | ✗ | 系统消息需持久化 |
| MEMBER_ADDED/REMOVED | ✗ | ✓ | ✗ | 成员变更需持久化 + 红点 |
| CMD | ✓ | ✗ | 视情况 | 命令消息一般不持久化 |
| ACK | ✓ | ✗ | ✗ | 确认消息不持久化 |

### 7.3 扩展标志位建议

```kotlin
object MessageFlags {
    const val NO_PERSIST = 1       // bit 0: 不存储
    const val RED_DOT = 2          // bit 1: 红点提醒
    const val SYNC_ONCE = 4        // bit 2: 只同步一次

    // 扩展建议
    const val SILENT = 8           // bit 3: 静默消息（不触发通知）
    const val GROUPED = 16         // bit 4: 分组消息（同一组媒体合并展示）
    const val ENCRYPTED = 32       // bit 5: 负载已加密（未来 TLS 时的兼容位）
}
```

**`SILENT`**：用于"仅同步"场景——如消息编辑通知，不需要触发桌面通知。
**`GROUPED`**：多图消息（相册模式），标记同一时间窗口发送的图片为一组。

---

## 8. 消息生命周期

### 8.1 消息状态流转

```
                          ┌──────────────┐
                          │   COMPOSING  │ (客户端，本地状态)
                          └──────┬───────┘
                                 │ PacketType.TEXT(20)
                                 ▼
                          ┌──────────────┐
                          │   PENDING    │ (服务端收到，分配 messageId + serverSeq)
                          └──────┬───────┘
                          SENDACK(80) 返回给发送者
                                 │
                                 ▼
                          ┌──────────────┐
                    ┌────►│  DELIVERED   │ (投递给接收者，PacketType.TEXT(20))
                    │     └──────┬───────┘
                    │            │ 接收者 RECVACK(81)
                    │            ▼
                    │     ┌──────────────┐
                    │     │    READ      │ (接收者已读)
                    │     └──────┬───────┘
                    │            │
              ┌─────┤     ┌──────▼──────┐
              │     │     │   EDITED    │ (被编辑，editedAt 非空)
              │     │     └─────────────┘
              │     │
              │     │     ┌──────────────┐
              │     └─────│   REVOKED    │ (被撤回，isDeleted=true)
              │           └──────────────┘
              │
              │           ┌──────────────┐
              └───────────│   PINNED     │ (被置顶，isPinned=true)
                          └──────────────┘
```

### 8.2 多设备同步模型

**Signal**：主设备 → 从设备加密同步。主设备签名后发送，从设备被动接收。

**Telegram**：所有设备平等。每台设备独立维护 `pts`（位置状态号），通过 `getDifference` 增量同步缺失的 Updates。

**TeamTalk 建议**（参考 Telegram 模式）：

```
每台设备独立维护:
  readSeq: Long          — 该设备已读的最大 seq
  收到的最大 serverSeq   — 用于断线重连后增量拉取

同步流程:
  1. 设备上线 → TCP CONNECT
  2. 服务端返回 CONNACK（含该设备的最后 readSeq）
  3. 客户端调用 GET /api/v1/messages?channelId=X&sinceSeq=lastSeq 增量拉取
  4. 此后新消息通过对应 PacketType 包实时推送
  5. 已读状态通过 CMD("read_sync") 广播给其他设备
```

### 8.3 关键设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 编辑策略 | 原地更新（不保留历史） | 办公 IM 不需要编辑审计，简化实现 |
| 撤回时间窗口 | 服务端校验（建议 2 分钟） | 防止长时间后撤回导致沟通混乱 |
| 消息删除 | 软删除（isDeleted=true） | 保留撤回记录用于通知 |
| 增量同步 | 基于 serverSeq | 单调递增，无冲突 |
| 多设备已读同步 | CMD("read_sync") | 推送已读位置到其他设备 |
| 消息置顶 | 服务端存储 + CMD 通知 | 群组内高优先级消息需要全员可见 |

### 8.4 消息置顶

**用途**：将重要消息固定在频道顶部，便于所有成员快速访问。

#### API 设计

```
POST   /api/v1/channels/{channelId}/messages/{messageId}/pin    — 置顶消息
DELETE /api/v1/channels/{channelId}/messages/{messageId}/pin    — 取消置顶
GET    /api/v1/channels/{channelId}/pinned                      — 获取置顶消息列表
```

#### 限制

| 频道类型 | 最大置顶数 |
|---------|-----------|
| 个人频道 (channelType=1) | 10 |
| 群组频道 (channelType=2) | 50 |

#### 权限

| 操作 | 个人频道 | 群组频道 |
|------|---------|---------|
| 置顶 | 任意一方 | OWNER + ADMIN |
| 取消置顶 | 任意一方 | OWNER + ADMIN（自己置顶的可自行取消） |
| 查看置顶列表 | 双方 | 所有成员 |

#### CMD 通知

置顶/取消置顶后，服务端通过 CMD 推送通知频道内所有成员：

```json
{
  "cmd": "message_pinned",
  "channelId": "group_001",
  "messageId": "msg_uuid",
  "pinned": true,
  "operatorUid": "uid_001",
  "timestamp": 1712000000000
}
```

```json
{
  "cmd": "message_unpinned",
  "channelId": "group_001",
  "messageId": "msg_uuid",
  "operatorUid": "uid_001",
  "timestamp": 1712000000000
}
```

客户端收到 CMD 后更新 `MessageRecord.isPinned` 字段，并刷新 UI 中的置顶标记。

---

## 9. 三方消息模型设计哲学

| | Signal | Telegram | TeamTalk |
|---|--------|----------|----------|
| 哲学 | 安全优先，最少类型 | 功能全面，类型丰富 | 实用主义，按需扩展 |
| 消息类型数量 | ~1 个 DataMessage（字段组合） | 40+ 构造器 | 17 种消息 PacketType（双向统一） |
| 操作建模 | 字段级（edit/delete/reaction 是字段） | 事件级（独立 Update 构造器） | 类型级（独立 PacketType） |
| 扩展方式 | 新 Protobuf 字段 | 新 TL constructor | 新 PacketType + IProto payload |
| 适合场景 | 隐私通信 | 全功能社交 | 中小型办公 |
