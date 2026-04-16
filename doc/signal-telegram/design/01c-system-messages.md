# 系统消息定义

> 频道生命周期、成员变更、群设置变更等系统消息。本文档从 [01-message-model.md](./01-message-model.md) 拆分而来。
>
> PacketType 编码方案：系统消息 90-99。详见 [03-binary-encoding.md](./03-binary-encoding.md) §2.2。
>
> **Payload 字段定义**：每种 PacketType 的 payload 字段见 [03a-payload-definitions.md](./03a-payload-definitions.md)（单一真相源）。本文档仅提供 Signal/Telegram 对比分析和设计决策。

---

## 5.1 频道生命周期 — PacketType 90-92

**Signal**：`DataMessage.groupChange`，嵌套 `GroupChange` Protobuf：

```
GroupChange
  ├── source: UUID                  // 操作者
  ├── changes: [GroupChange.Change]
  │     ├── modifyTitle: string?    // 改名
  │     ├── modifyAvatar: string?   // 改头像
  │     ├── modifyDescription: string?
  │     ├── addMembers: [Member]
  │     ├── deleteMembers: [Member]
  │     ├── promoteMembers: [Member]  // 升级管理员
  │     └── dismissAdmin: [Member]    // 降级管理员
  └── epoch: uint32                 // 群版本号
```

**Telegram**：`messageAction*` 系列 TL 构造器：

```
messageActionChatCreate(title, users)
messageActionChatEditTitle(title)
messageActionChatEditPhoto(photo)
messageActionChatDeletePhoto
messageActionChatAddUser(users)
messageActionChatDeleteUser(user_id)
messageActionChatJoinedByLink(inviter_id)
messageActionChatMigrateTo(channel_id)      // 升级为超级群
messageActionChannelCreate(title)
messageActionGroupCall(duration)            // 群通话
```

**TeamTalk 设计**：

| PacketType | 名称 | 说明 | 状态 |
|---|---|---|---|
| CHANNEL_CREATED(90) | 频道创建 | channelId + channelType + channelName + creatorUid | 已实现 |
| CHANNEL_UPDATED(91) | 频道更新 | channelId + channelType + field + oldValue? + newValue? + operatorUid | 已实现 |
| CHANNEL_DELETED(92) | 频道删除 | channelId + channelType + operatorUid | 已实现 |

> 完整字段定义见 [03a-payload-definitions.md](./03a-payload-definitions.md) §4.1。

**设计说明**：
- `CHANNEL_UPDATED` 的 `field` 标识变更的字段名（"name" | "avatar" | "announcement"）
- `oldValue` / `newValue` 可选，用于 UI 展示变更详情

**优先级**：中。系统消息的 payload 需要结构化定义，当前是隐式的 JSON。

---

## 5.2 成员变更 — PacketType 93-94

**Signal**：嵌套在 `GroupChange.changes` 中（见 5.1）。

**Telegram**：独立 messageAction（见 5.1），每条动作产生一条系统消息。

**TeamTalk 设计**：

| PacketType | 名称 | 说明 | 状态 |
|---|---|---|---|
| MEMBER_ADDED(93) | 成员加入 | channelId + memberUid + memberName? + inviterUid | 已实现 |
| MEMBER_REMOVED(94) | 成员移除 | channelId + memberUid + memberName? + operatorUid + reason:Byte | 已实现 |

> 完整字段定义见 [03a-payload-definitions.md](./03a-payload-definitions.md) §4.2。

**设计说明**：
- `MEMBER_REMOVED` 的 `reason`：0=主动退出, 1=被踢出（使用 Byte 而非 String，与 03a 定义一致）
- `memberName` 可选，用于 UI 展示

**优先级**：中。

---

## 5.3 群设置变更 — PacketType 95-98

**Signal**：通过 `GroupChange` 的 `modifyDisappearingMessagesTimer`、`modifyPermissions` 等变更表达。

**Telegram**：

```
messageActionChatJoinedByRequest       — 加入审批通过
messageActionChatJoinedByLink          — 通过链接加入
messageActionGroupCall                  — 群通话
messageActionSetMessagesTTL            — 设置消息存活时间
messageActionSetChatTheme              — 设置群主题
messageActionHistoryClear              — 清空历史
```

**TeamTalk 设计**：

| PacketType | 名称 | 说明 | 状态 |
|---|---|---|---|
| MEMBER_MUTED(95) | 成员禁言 | channelId + memberUid + operatorUid + duration:VarInt | 待实现 |
| MEMBER_UNMUTED(96) | 解除禁言 | channelId + memberUid + operatorUid | 待实现 |
| MEMBER_ROLE_CHANGED(97) | 角色变更 | channelId + memberUid + operatorUid + oldRole:Byte + newRole:Byte | 待实现 |
| CHANNEL_ANNOUNCEMENT(98) | 群公告 | channelId + content + operatorUid | 待实现 |

> 完整字段定义见 [03a-payload-definitions.md](./03a-payload-definitions.md) §4.3。

**设计说明**：
- `duration`：禁言时长（秒），0=永久
- `role`：0=普通成员, 1=管理员, 2=群主

**优先级**：中（P2 功能）。群管理工具是办公 IM 的基础能力。
