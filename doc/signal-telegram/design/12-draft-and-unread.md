# 草稿与未读管理设计

> TeamTalk 的草稿持久化和未读计数管理方案

---

## 1. 设计目标

- **草稿自动保存**：用户在输入框输入但未发送的内容自动保存，切换会话后不丢失
- **未读计数准确**：每个会话的未读消息数实时、准确
- **多设备同步**：已读状态在多设备间同步
- **Badge 显示**：应用图标/托盘图标显示总未读数

---

## 2. 草稿管理

### 2.1 数据结构

草稿存储在本地数据库（SQLDelight），与会话表合并：

```sql
-- 会话表中的草稿字段
ALTER TABLE conversations ADD COLUMN draft_text TEXT;
ALTER TABLE conversations ADD COLUMN draft_updated_at INTEGER;
```

草稿数据模型：

```kotlin
data class Draft(
    val channelId: String,
    val text: String,
    val updatedAt: Long,     // 时间戳，用于排序和清理
)
```

### 2.2 保存策略

- **防抖保存**：输入内容变化后，延迟 500ms 写入数据库（避免每次按键都触发 I/O）
- **发送后清除**：消息发送成功后，自动清空该会话的草稿
- **仅本地**：草稿不需要跨设备同步（用户在不同设备上的草稿独立）

### 2.3 草稿与会话列表

会话列表显示草稿预览：

```
┌──────────────────────────────────┐
│ 🔵 张三               10:30     │
│    [草稿] 我觉得这个方案...      │  ← 灰色文字 + "[草稿]" 前缀
└──────────────────────────────────┘
```

- 有草稿的会话按 `draft_updated_at` 排序到列表顶部（仅次于置顶会话）
- 没有 lastMessage 时，草稿替代 lastMessage 显示

### 2.4 草稿清理

- 会话删除时清理对应草稿
- 超过 30 天未更新的草稿自动清理（用户大概率已不再需要）

### 2.5 与 Signal 的对比

| 维度 | Signal | TeamTalk |
|------|--------|----------|
| 存储位置 | 独立 DraftTable | conversations 表内嵌字段 |
| 草稿类型 | 文本/位置/媒体等多种 | 仅文本（初期） |
| 跨设备同步 | 是（通过存储同步） | 否（仅本地） |

TeamTalk 初期仅支持文本草稿，后续可扩展为支持媒体草稿（图片/文件选择后未发送的状态）。

---

## 3. 未读计数管理

### 3.1 计数规则

```
unread_count = MAX(0, channel_last_seq - read_seq)
```

- `channel_last_seq`：频道内最新消息的 serverSeq（服务端维护）
- `read_seq`：用户已读位置（客户端维护）
- 每收到一条新消息（RECV + RECVACK），`channel_last_seq` 递增
- 用户打开聊天页查看消息时，`read_seq` 更新为当前查看的最大 seq

### 3.2 已读位置更新

```kotlin
fun markAsRead(channelId: String, lastVisibleSeq: Long) {
    // 1. 更新本地数据库
    db.readPositionsQueries.updateReadSeq(channelId, lastVisibleSeq)

    // 2. 通知服务端（用于多设备同步）
    apiClient.post("/api/v1/channels/$channelId/read", body = mapOf("readSeq" to lastVisibleSeq))

    // 3. 更新会话表未读计数
    val lastSeq = db.conversationsQueries.getLastSeq(channelId).executeAsOne()
    val unreadCount = maxOf(0L, lastSeq - lastVisibleSeq).toInt()
    db.conversationsQueries.updateUnreadCount(channelId, unreadCount)
}
```

### 3.3 总未读数 Badge

```kotlin
// 总未读数 = 所有非静音会话的未读数之和
val totalUnread = db.conversationsQueries.totalUnreadCount(
    excludeMuted = true
).executeAsOne()
```

- Desktop：更新 ComposeNativeTray 图标的 tooltip（"TeamTalk (3 条未读)"）
- Android：更新应用图标 badge（ShortcutBadger 或系统 API）

### 3.4 未读计数同步

多设备场景下的已读同步：

```
设备 A 标记已读
  → 服务端更新 read_seq
  → CMD(READ_SYNC) 推送给设备 B
  → 设备 B 更新本地 read_positions
  → 会话列表 UI 自动刷新未读数
```

### 3.5 @提及计数

除了普通未读计数外，还需要追踪 @提及数：

```sql
-- 扩展 conversations 表
ALTER TABLE conversations ADD COLUMN mention_count INTEGER NOT NULL DEFAULT 0;
```

- 收到包含 `mentionUids` 包含当前用户的邮件时，`mention_count` 递增
- 打开会话后清零
- UI 可以区分显示：普通未读数 vs @提及数

---

## 4. 实现优先级

| Phase | 功能 |
|-------|------|
| Phase 1 | 本地草稿保存 + 未读计数计算 + Badge 显示 |
| Phase 2 | 多设备已读同步（CMD READ_SYNC）+ @提及计数 |
| Phase 3 | 媒体草稿 + 免打扰时段自动调整未读提示 |
