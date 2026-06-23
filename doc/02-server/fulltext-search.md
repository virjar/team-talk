# 全文搜索

> 基于 Lucene + IK 中文分词器的消息全文搜索，按聊天范围搜索。

## 架构

```
消息入库
  │
  ▼
MessageService.sendMessage()
  │
  ├── 存储到 RocksDB（消息持久化）
  └── 异步索引到 Lucene（不阻塞消息发送）
        │
        ▼
     Lucene Index Writer
       ├── 分词：IK 中文分词器
       ├── 索引字段：chatId, messageSeq, senderUid, content, timestamp
       └── 写入索引目录：$dataRoot/lucene-index/
```

## 索引设计

### 索引字段

| 字段 | 类型 | 说明 |
|------|------|------|
| chatId | StringField | 聊天 ID（用于按范围搜索） |
| messageSeq | LongPoint | 消息序列号（排序） |
| senderUid | StringField | 发送者 UID |
| content | TextField | 消息文本内容（分词索引） |
| timestamp | LongPoint | 时间戳（排序） |

### 分词

使用 IK 中文分词器，支持：
- 中文智能分词
- 英文按空格/标点分词
- 中英混合文本

## 搜索 API

客户端通过 RPC 调用搜索：

```kotlin
// 客户端
val results = messageRepo.searchMessages(chatId, keyword)

// 服务端 MessageRouteHandler.SEARCH
LuceneIndex.search(chatId, keyword, limit = 50)
  → 返回匹配的消息列表（按 timestamp 排序）
```

### 搜索范围

- **全局搜索**：chatId 传空字符串，搜索所有会话的消息
- **会话内搜索**：指定 chatId，只搜索该会话的消息

### 搜索结果

返回匹配的消息完整数据（含 senderUid、timestamp、body），客户端渲染为搜索结果列表。

## 索引恢复

服务端重启后索引自动恢复：
- Lucene 索引持久化到磁盘
- 重启后直接打开索引目录，无需重建
- 索引目录：`$dataRoot/lucene-index/`

## 用户搜索

用户搜索不走 Lucene，走 PostgreSQL SQL LIKE：

```sql
SELECT * FROM users 
WHERE username LIKE '%keyword%' 
   OR name LIKE '%keyword%' 
   OR shortNo = 'keyword'
```

**相关代码**：`server/.../infra/search/SearchIndex.kt`、`server/.../domain/message/MessageService.kt`
