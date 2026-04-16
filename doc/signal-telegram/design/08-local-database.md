# 本地数据库设计

> 方案设计文档 — 引入 SQLDelight 实现客户端本地缓存与增量同步

---

## 1. 选型决策

### ADR：选择 SQLDelight

| 方案 | 类型 | KMP 支持 | 结论 |
|------|------|----------|------|
| **SQLDelight** | 编译期 SQL → Kotlin | Android + JVM + iOS + Native | **采用** |
| Room | SQLite ORM | Android only | 不适合 KMP，Desktop 无法使用 |
| SQLCipher | 加密 SQLite | 需 native 绑定 | 加密不需要（依赖 OS 底层安全），过度设计 |
| Realm | 文档数据库 | 已停止维护 | 不可选 |

**选择 SQLDelight 的理由**：

1. **KMP 原生支持**：Android（SQLite）和 Desktop（SQLite via JDBC）共享同一套 `.sq` 文件，编译期生成平台特定的 Kotlin 代码
2. **编译期 SQL 验证**：SQL 语法错误在编译阶段暴露，而非运行时崩溃
3. **类型安全**：生成的 Kotlin 代码对查询结果提供完整类型信息，无需手写映射代码
4. **轻量**：不引入 ORM 抽象层，开发者直接写 SQL，对数据库行为完全可控

**不加密的理由**（参见 [04-security.md](./04-security.md)）：

- Desktop 依赖 macOS FileVault / Windows BitLocker 全盘加密
- Android 依赖系统全盘加密（Android 6.0+ 默认开启）
- 办公 IM 场景下数据归组织所有，不需要应用层独立加密

---

## 2. 数据库 Schema

### 核心表（5 张）

```sql
-- 消息表
CREATE TABLE messages (
    channel_id TEXT NOT NULL,
    seq INTEGER NOT NULL,
    message_id TEXT NOT NULL UNIQUE,
    sender_uid TEXT NOT NULL,
    packet_type INTEGER NOT NULL,
    payload BLOB NOT NULL,        -- 二进制序列化的完整 payload
    flags INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (channel_id, seq)
);

-- 会话表
CREATE TABLE conversations (
    channel_id TEXT NOT NULL PRIMARY KEY,
    channel_type INTEGER NOT NULL,
    channel_name TEXT NOT NULL,
    avatar_url TEXT,
    last_message TEXT,
    last_message_time INTEGER,
    last_seq INTEGER NOT NULL DEFAULT 0,
    unread_count INTEGER NOT NULL DEFAULT 0,
    is_pinned INTEGER NOT NULL DEFAULT 0,
    is_muted INTEGER NOT NULL DEFAULT 0,
    draft_text TEXT,
    draft_updated_at INTEGER,
    version INTEGER NOT NULL DEFAULT 0
);

-- 联系人表
CREATE TABLE contacts (
    uid TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    avatar_url TEXT,
    remark TEXT,
    is_friend INTEGER NOT NULL DEFAULT 1,
    is_blacklisted INTEGER NOT NULL DEFAULT 0,
    updated_at INTEGER NOT NULL
);

-- 频道表
CREATE TABLE channels (
    channel_id TEXT NOT NULL PRIMARY KEY,
    channel_type INTEGER NOT NULL,
    name TEXT NOT NULL,
    avatar_url TEXT,
    owner_uid TEXT NOT NULL,
    member_count INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

-- 已读位置表（每个频道的已读 seq）
CREATE TABLE read_positions (
    channel_id TEXT NOT NULL PRIMARY KEY,
    read_seq INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (channel_id) REFERENCES conversations(channel_id)
);
```

### 索引设计

```sql
-- 消息表：按频道查询消息列表（最频繁的查询）
CREATE INDEX idx_messages_channel_created
    ON messages(channel_id, created_at);

-- 消息表：按 message_id 快速定位（撤回、回复引用）
CREATE INDEX idx_messages_message_id
    ON messages(message_id);

-- 会话表：按置顶 + 时间排序
CREATE INDEX idx_conversations_pinned_time
    ON conversations(is_pinned DESC, last_message_time DESC);

-- 联系人表：按名称搜索
CREATE INDEX idx_contacts_name
    ON contacts(name);

-- 联系人表：过滤黑名单
CREATE INDEX idx_contacts_blacklisted
    ON contacts(is_blacklisted);
```

### 设计说明

| 决策 | 理由 |
|------|------|
| `payload` 用 BLOB | 保持与服务端一致的二进制编码，避免额外序列化开销 |
| `PRIMARY KEY (channel_id, seq)` | 消息天然按频道有序，复合主键直接支持范围查询 |
| 会话表包含 `draft_text` | 草稿仅存本地，不需要同步到服务端 |
| 独立 `read_positions` 表 | 已读位置是纯本地状态，与会话元数据解耦便于独立更新 |
| 仅 5 张表 | TeamTalk <1 万用户规模，不需要 Signal 的 30+ 表或 Telegram 的分表策略 |

---

## 3. Repository 双源模式

### 当前架构（纯网络）

```
UI → ViewModel → Repository → ApiClient（HTTP / TCP）
```

### 目标架构（本地优先 + 网络同步）

```
UI → ViewModel → Repository
                     ├── LocalDataSource (SQLDelight)  ← 优先读取
                     └── RemoteDataSource (ApiClient)  ← 后台同步
```

Repository 内部封装数据源切换逻辑，ViewModel 和 UI 层无感知。

### 三种同步策略

| 策略 | 适用场景 | 行为 |
|------|---------|------|
| **先本地后网络** | 消息列表、会话列表 | 立即返回本地缓存，同时发起网络请求拉取增量，结果合并后通知 UI |
| **先网络后缓存** | 搜索用户、好友申请 | 必须获取最新数据，结果写入本地缓存供下次使用 |
| **仅本地** | 草稿、已读位置 | 纯本地读写，不需要服务端参与 |

### 各 Repository 的同步策略

| Repository | 主要操作 | 同步策略 |
|------------|---------|----------|
| ChatRepository | 拉取消息、发送消息 | 先本地后网络 |
| ConversationRepository | 会话列表、已读、置顶、静音 | 先本地后网络 |
| ContactRepository | 联系人列表、好友申请 | 先网络后缓存 |
| ChannelRepository | 频道信息、群组成员 | 先本地后网络 |
| FileRepository | 文件上传下载 | 仅网络（文件不适合本地数据库） |

---

## 4. 数据同步流程

### 4.1 消息列表同步（最核心的流程）

```
用户打开聊天页
     │
     ▼
┌─ LocalDataSource.getMessages(channelId, limit=50) ─┐
│  返回本地缓存消息（可能为空）                           │
│  UI 立即渲染，用户零等待                               │
└──────────────────────────────────────────────────────┘
     │
     ▼
┌─ RemoteDataSource.getMessages(channelId, afterSeq=本地最大seq) ─┐
│  服务端返回增量消息                                               │
│  ↓                                                              │
│  LocalDataSource.insertMessages(增量消息)                        │
│  ↓                                                              │
│  UI 自动更新（StateFlow 观察 LocalDataSource 查询结果）           │
└─────────────────────────────────────────────────────────────────┘
```

**关键点**：`afterSeq` 参数使用本地最大 seq 值，服务端仅返回该 seq 之后的消息，实现增量同步。

### 4.2 TCP 推送消息处理

```
TCP 收到 RECV 帧
     │
     ▼
解析消息 → LocalDataSource.insertMessage()
     │              │
     │              ▼
     │         更新 conversations 表（last_message, last_seq, unread_count++）
     │
     ▼
StateFlow 通知 UI 更新
     ├── 聊天页：新消息出现在列表底部
     └── 会话列表：会话置顶、最新消息预览更新
```

### 4.3 发送消息（乐观更新）

```
用户点击发送
     │
     ▼
LocalDataSource.insertMessage(临时 message_id, status=SENDING)
     │
     ▼
UI 立即显示消息（带发送中状态）
     │
     ▼
ApiClient.sendMessage() → 服务端返回 SENDACK（含 seq 和正式 message_id）
     │
     ▼
LocalDataSource.updateMessage(临时 id → 正式 id, status=SENT, seq)
```

---

## 5. 数据清理策略

### 5.1 消息保留策略

| 参数 | 默认值 | 说明 |
|------|--------|------|
| 保留天数 | 90 天 | 超过 90 天的消息从本地删除（服务端仍保留） |
| 单频道上限 | 10,000 条 | 超过上限时清理最旧的消息 |
| 清理触发时机 | 启动时 | 应用启动时检查并执行一次清理 |

### 5.2 LRU 清理规则

磁盘空间不足时按以下优先级清理：

1. 非置顶频道中最旧的消息
2. 已过期（>90 天）的所有消息
3. 缩略图缓存

### 5.3 重要数据保护

以下数据不受自动清理影响：

- **置顶会话的消息**：永不自动清理
- **联系人数据**：仅在网络同步时更新，不按时间清理
- **草稿和已读位置**：纯本地状态，无过期概念

### 5.4 清理实现

```sql
-- 清理过期消息（启动时执行）
DELETE FROM messages
WHERE created_at < :cutoffTimestamp
  AND channel_id NOT IN (
    SELECT channel_id FROM conversations WHERE is_pinned = 1
  );

-- 单频道消息数超限清理
DELETE FROM messages
WHERE channel_id = :channelId
  AND seq < (
    SELECT MAX(seq) - 10000 FROM messages WHERE channel_id = :channelId
  );
```

---

## 6. 与 Signal / Telegram 的对比

| 维度 | Signal | Telegram | TeamTalk |
|------|--------|----------|----------|
| 数据库 | SQLCipher（加密） | 内嵌 SQLite C API | SQLDelight（明文 SQLite） |
| 表数量 | 30+ | 20+（按聊天分表） | 5 张核心表 |
| 加密 | 应用层加密（E2EE 需要） | 无本地加密 | 不加密（依赖 OS 底层安全） |
| 离线能力 | 完全离线可用 | 完全离线可用 | 本地缓存可查看历史消息 |
| 同步机制 | StorageSync（加密增量） | MTProto 差量同步 | afterSeq 增量拉取 |
| 消息存储 | 按时间分区 | 按聊天分表 | 单表 + 索引 |
| 文件缓存 | 独立缓存管理器 | FileLoader（三级缓存） | 不在数据库范围内 |

**为什么不需要那么复杂**：

- **Signal 的 30+ 表**：大量表用于 E2EE 密钥管理（prekeys、sessions、identities），TeamTalk 不做 E2EE，不需要
- **Telegram 的分表**：面向数亿用户的消息表需要按聊天物理隔离，TeamTalk <1 万用户，单表 + 索引完全够用
- **SQLCipher 加密**：办公 IM 数据归组织所有，依赖 OS 全盘加密即可，不需要应用层重复加密

TeamTalk 的本地数据库设计目标是最小可用——5 张表覆盖核心场景，后续根据需要渐进扩展。
