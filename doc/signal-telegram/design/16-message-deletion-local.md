# 本地消息删除设计

> 方案设计文档 — "仅删除我本地的消息"，纯客户端操作

---

## 1. 需求分析

### 1.1 场景

用户希望清理聊天历史中的某些消息（如误发的文件、过期的图片），但**不想通知对方**。这不同于消息撤回（REVOKE），撤回是全局操作——所有人看到消息消失；本地删除仅影响自己的视图。

### 1.2 三方对比

| 维度 | Signal | Telegram | TeamTalk |
|------|--------|----------|----------|
| 全局删除 | ❌ 不支持 | ❌ 不支持 | ❌ 不支持（仅 REVOKE 撤回） |
| 本地删除 | ✅ 长按 → 删除（仅本地） | ✅ 删除（仅自己可见） | ❌ 待实现 |
| "所有人"删除 | ❌ | ✅ 48h 内双方可删 | 仅 REVOKE（2 分钟内） |

### 1.3 设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 实现范围 | 仅本地删除 | 全局删除涉及太多边界（对方离线、已截图、多设备同步），暂不引入 |
| 存储层 | 本地数据库标记 | 在 messages 表添加 `is_local_deleted` 列 |
| 服务端感知 | 无 | 纯客户端行为，不发送 TCP 包，不调用 HTTP API |
| 多设备同步 | 不同步 | 每台设备独立管理自己的本地删除列表 |
| 误操作恢复 | 不提供回收站 | 简化实现，用户可通过服务端历史重新同步 |

---

## 2. 客户端实现

### 2.1 本地数据库变更

在 `messages` 表新增列：

```sql
ALTER TABLE messages ADD COLUMN is_local_deleted INTEGER NOT NULL DEFAULT 0;

-- 查询时过滤已本地删除的消息
-- CREATE INDEX 仅在需要时添加（消息量小时全表扫描足够）
```

SQLDelight 查询：

```sql
-- getMessages 已经通过 seq 范围查询，需要追加过滤条件
selectMessagesByRange :
SELECT * FROM messages
WHERE channel_id = ? AND seq >= ? AND seq <= ? AND is_local_deleted = 0
ORDER BY seq ASC;

-- 标记单条消息为本地删除
markLocalDeleted :
UPDATE messages
SET is_local_deleted = 1
WHERE message_id = ?;

-- 批量标记（多选删除场景）
markLocalDeletedBatch :
UPDATE messages
SET is_local_deleted = 1
WHERE message_id IN ?;
```

### 2.2 Repository 层

```kotlin
// ChatRepository 新增方法
class ChatRepository(private val database: TeamTalkDatabase) {

    /** 删除单条消息（仅本地） */
    suspend fun deleteMessageLocal(messageId: String) {
        database.teamTalkQueries.markLocalDeleted(messageId)
    }

    /** 批量删除消息（仅本地） */
    suspend fun deleteMessagesLocal(messageIds: List<String>) {
        database.teamTalkQueries.transaction {
            messageIds.forEach { id ->
                database.teamTalkQueries.markLocalDeleted(id)
            }
        }
    }
}
```

### 2.3 ViewModel 层

```kotlin
// ChatViewModel 新增
fun deleteLocal(messageId: String) {
    viewModelScope.launch {
        chatRepository.deleteMessageLocal(messageId)
        // 从内存列表中移除
        _messages.value = _messages.value.filterNot { it.messageId == messageId }
    }
}

fun deleteLocalBatch(messageIds: List<String>) {
    viewModelScope.launch {
        chatRepository.deleteMessagesLocal(messageIds)
        val idSet = messageIds.toSet()
        _messages.value = _messages.value.filterNot { it.messageId in idSet }
    }
}
```

### 2.4 UI 入口

| 操作 | 入口 | 行为 |
|------|------|------|
| 单条删除 | 消息气泡长按菜单 → "删除（仅自己）" | 弹确认对话框 → 调用 `deleteLocal` |
| 批量删除 | 多选模式 → 选中消息 → "删除" | 弹确认对话框 → 调用 `deleteLocalBatch` |

**确认对话框文案**：

> 删除后仅在你本地不可见，对方不受影响。此操作不可撤销。

---

## 3. 边界场景

### 3.1 增量同步时不覆盖本地删除

问题：服务端增量同步推送新消息时，如何避免已本地删除的消息"复活"？

方案：增量同步基于 `sinceSeq` 拉取新 seq 的消息，而已删除消息的 seq 不变。同步逻辑只 **INSERT OR REPLACE** 新消息，不会重新拉取已删除的旧 seq。因此本地删除天然不会被覆盖。

```
时间线:
  t1: 消息 seq=100 到达 → 存入本地，正常显示
  t2: 用户本地删除 seq=100 → is_local_deleted=1
  t3: 断线重连，sinceSeq=99 → 拉取 seq>99 的消息
       → seq=100 已存在本地（is_local_deleted=1），INSERT OR REPLACE 跳过
       → 不会"复活"
```

### 3.2 换设备登录

本地删除状态不同步到服务端。用户在新设备上登录后会看到完整的消息历史（包含之前设备已本地删除的消息）。这是**设计预期**——本地删除仅影响当前设备。

### 3.3 置顶消息被本地删除

置顶消息被本地删除后，置顶列表中该消息仍然存在（因为置顶列表从服务端获取）。用户点进置顶消息时，如果该消息已被本地删除，则显示占位文本 "该消息已删除"。

### 3.4 引用的消息被本地删除

如果消息 A 引用了消息 B，而 B 被本地删除，A 的引用预览应显示 "原消息已删除" 而非空白。这与 REVOKE 场景的处理方式一致。

---

## 4. 不做的事

| 不做 | 理由 |
|------|------|
| "删除所有人消息" API | 需要服务端参与、多设备同步、已读回执修正，复杂度高且容易误操作 |
| 回收站 / 撤销删除 | 增加存储和 UI 复杂度，收益低 |
| 服务端记录本地删除状态 | 违反"纯客户端"原则，且浪费服务端存储 |
| 本地删除后自动调整未读数 | 已删除的消息不应影响未读计数——如果删除的是最新消息，未读数可能需要减 1。但考虑到本地删除场景通常是清理旧消息，且未读数由服务端 readSeq 驱动，暂不处理 |
