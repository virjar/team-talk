# 草稿与未读管理

> 会话草稿持久化、未读计数管理、多设备已读同步。

## 草稿管理

### 设计

草稿是纯客户端状态（不同设备的草稿独立），存储在本地 SQLite 的会话表中。

### 保存策略

- **离开会话时保存**：`ChatPanel` 的 `DisposableEffect(chatId)` 在 `onDispose` 时调用 `onDraftChange(inputText)`
- **发送后清除**：消息发送成功后，草稿自动清空
- **空草稿传 null**：空字符串转 null，避免 `[草稿]` 标签残留

```kotlin
// Desktop
onDraftChange = { draft ->
    conversationRepo.setDraft(chatId, draft.ifBlank { null })
}
```

### 草稿预览

会话列表显示草稿预览：

```
┌──────────────────────────────────┐
│ 🔵 张三               10:30     │
│ [草稿] 你好，这个问题...        │
└──────────────────────────────────┘
```

### 踩坑：空草稿残留

**问题**：进入聊天页立即显示 `[草稿]` 标签（空内容）。

**根因**：`onDraftChange("")` 写了空字符串草稿，`"" != null` 所以显示为草稿。

**修复**：空字符串转 null。`LocalCache.mergeConversation` 中草稿合并规则：本地非空草稿优先于服务端。

---

## 未读计数管理

### 数据流

```
收到新消息 NOTIFY
  │
  ▼
EventProcessor → upsertConversation(unreadCount + 1)
  │
  ▼
用户打开会话
  │
  ▼
ChatViewModel.markRead()
  ├── 本地：unreadCount = 0, readSeq = lastSeq
  └── 服务端：INVOKE(MARK_READ)
        └── 服务端更新 readSeq → NOTIFY 推给其他设备
```

### 状态合并

多状态源（本地 + 服务端 NOTIFY）合并时，`mergeConversation` 保护本地已读状态：

```kotlin
private fun mergeConversation(local: Conversation, remote: Conversation): Conversation {
    val mergedReadSeq = maxOf(local.readSeq, remote.readSeq)
    // 本地已标记已读，服务端通知滞后 → 不被覆盖
    val mergedUnread = if (local.readSeq >= remote.readSeq && local.unreadCount == 0) 0 else remote.unreadCount
    return remote.copy(readSeq = mergedReadSeq, unreadCount = mergedUnread)
}
```

### 踩坑：红点计数紊乱

**问题**：标记已读后未读数又回来。

**根因**：`upsertConversation` 直接用服务端通知的旧 unreadCount 覆盖了本地已清零的 0。

**修复**：字段级合并（上述 `mergeConversation`），不整体替换。

---

## 多设备已读同步

用户在设备 A 标记已读 → 服务端更新 readSeq → NOTIFY 推给设备 B → 设备 B 也标记已读。

```
设备A: markRead(chatId)
  → INVOKE(MARK_READ) → 服务端 readSeq = lastSeq
  → NOTIFY(CONVERSATION_UPDATED, {readSeq: lastSeq, unreadCount: 0})
  → 设备B: EventProcessor → unreadCount = 0
```

这样多设备的未读状态保持最终一致。

---

## Badge 显示

### 会话级

会话列表每项显示未读数 Badge（红色圆点）：

```
┌──────────────────────────────────┐
│ 💬 工作群              3        │  ← Badge: 3
│ 项目进度同步...                │
└──────────────────────────────────┘
```

- 未读数 > 99 显示 `99+`
- 未读数 = 0 不显示 Badge

### 应用图标级

- Android：应用图标 Badge（通过 ShortcutBadger 或系统 API）
- Desktop：系统托盘图标叠加未读数

**相关代码**：`app/.../ui/screen/ConversationListScreen.kt`（Badge 渲染）、`app/.../client/LocalCacheImpl.kt`（mergeConversation）
