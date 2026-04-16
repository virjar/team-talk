# 全文搜索技术选型：PostgreSQL tsvector vs Apache Lucene

> 对比分析补充文档 — 架构决策记录

---

## 背景

消息搜索是 IM 应用的基础功能。当前 TeamTalk 没有任何搜索能力——只能按频道顺序拉取消息，无法跨频道搜索文本内容。P2 路线图中将消息搜索列为优先项。

根据单体架构原则，排除 Elasticsearch（独立进程、分布式架构）。候选方案：

1. **PostgreSQL tsvector** — 利用已有的 PG 数据库，零额外依赖
2. **Apache Lucene** — ES 的底层引擎，嵌入式 Java 库，进程内集成

---

## 现状分析

### 消息存储架构

```
PostgreSQL                     RocksDB
┌──────────────────┐          ┌─────────────────────────┐
│ users            │          │ Key: channelIdLen(2B)   │
│ channels         │          │     + channelId          │
│ channel_members  │          │     + seq(8B)            │
│ conversations    │          │ Value: JSON(MessageRecord)│
│ friends          │          │   - messageId            │
│ friend_applies   │          │   - channelId            │
│ devices          │          │   - senderUid            │
│ tokens           │          │   - messageType          │
└──────────────────┘          │   - payload (JSON)       │
                              │   - timestamp            │
  元数据：8 张表               │   - isDeleted            │
                              └─────────────────────────┘
                               消息数据： RocksDB 存储
```

**关键事实：消息内容存储在 RocksDB 中，PostgreSQL 里没有消息表。** 消息的文本内容藏在 `MessageRecord.payload`（JSON 字符串）里，不同消息类型的 payload 结构不同（TextContent、ReplyContent、MergeForwardContent 等）。

### 搜索需求

| 维度 | 需求 |
|------|------|
| 搜索范围 | 全局搜索（跨频道）+ 频道内搜索 |
| 搜索内容 | 文本消息、文件名、回复引用的原文 |
| 搜索语言 | 中文为主，需支持中文分词 |
| 权限控制 | 只能搜到自己是成员的频道中的消息 |
| 结果展示 | 高亮关键词、定位到原始消息上下文 |

---

## 方案对比

### 方案 A：PostgreSQL tsvector

#### 实现路径

由于消息不在 PG 中，需要额外工作：

```
路径1：将消息从 RocksDB 迁移到 PG
  → 重写 MessageStore，影响范围大
  → PG 对高吞吐消息写入的性能不如 RocksDB
  → 违背当初选 RocksDB 存消息的设计意图

路径2：保留 RocksDB，在 PG 中建搜索索引表
  → 新建 message_search 表（messageId, channelId, text, tsvector）
  → 每条消息写入 RocksDB 后同步写入 PG
  → 两个数据源的一致性问题
```

#### 中文分词

PG 的全文搜索默认不支持中文。需要安装扩展：

| 扩展 | 说明 |
|------|------|
| `zhparser` | 基于 SCWS 分词，需要编译安装 C 扩展 |
| `pg_jieba` | 基于 jieba 分词，需要编译安装 C 扩展 |

这意味着 `docker-compose.yml` 中的 PostgreSQL 镜像不能直接用官方镜像，需要自定义 Dockerfile 编译安装扩展，增加维护成本。

#### 评估

| 维度 | 评分 | 说明 |
|------|------|------|
| 额外依赖 | ★★★★★ | 零，PG 已在用 |
| 实现复杂度 | ★★☆☆☆ | 需要数据同步或迁移 + 自定义 PG 镜像 |
| 中文分词 | ★★☆☆☆ | 需要编译安装 PG 扩展 |
| 搜索能力 | ★★★☆☆ | 基础全文搜索，高亮需要额外处理 |
| 一致性风险 | ★★☆☆☆ | 双写 RocksDB + PG，需保证一致 |

---

### 方案 B：Apache Lucene（嵌入式）

#### 架构

```
Ktor Server 进程（单个 JVM）
├── Ktor HTTP (8080)
├── Netty TCP (5100)
├── PostgreSQL（元数据）
├── RocksDB（消息存储）
└── Lucene Index（搜索索引）     ← 嵌入式，同一进程
    ├── 索引目录：~/.tk/lucene-index/
    ├── IK Analyzer（中文分词）
    └── 写入：消息存储时同步写索引
```

Lucene 是 ES 的底层引擎。ES 做的是分布式封装（多节点、分片、副本、REST API），Lucene 本身是一个纯 Java 库，直接在 JVM 进程内调用，无需独立进程。

#### 中文分词

Lucene 生态有成熟的中文分词器，无需编译 C 扩展：

| 分词器 | 说明 |
|--------|------|
| **IK Analyzer** | 最流行的中文分词器，支持细粒度/智能分词，热加载词典 |
| SmartChineseAnalyzer | Lucene 内置，基于 HMM，分词质量一般 |
| Jieba Analyzer | jieba 的 Java 移植版 |

IK Analyzer 是 Java/Kotlin 生态中中文搜索的事实标准，Maven 依赖直接引入即可。

#### 写入路径

```
消息写入流程（MessageService.sendMessage）:
1. MessageStore.storeMessage(record)  → RocksDB（主存储）
2. SearchIndex.indexMessage(record)   → Lucene（搜索索引）
   - 提取 payload 中的文本内容
   - IK Analyzer 分词
   - 写入 Lucene Document（messageId, channelId, channelType,
     senderUid, text, timestamp, messageType）
```

#### 查询路径

```
搜索 API：GET /api/v1/messages/search?q=关键词&channelId=&limit=20
1. 权限过滤：查询 PG channel_members 表获取用户的频道列表
2. Lucene 搜索：BooleanQuery(text MATCH 关键词 AND channelId IN [...])
3. 结果返回：messageId + channelId + seq + 高亮片段
4. （可选）从 RocksDB 加载完整消息内容
```

#### 索引管理

| 关注点 | 处理方式 |
|--------|----------|
| 索引持久化 | Lucene 索引写入文件系统（`~/.tk/lucene-index/`），重启不丢失 |
| 实时性 | 使用 `NRTManager`（Near Real-Time），写入后几毫秒可搜索 |
| 索引损坏 | Lucene 索引可从 RocksDB 全量重建（`rebuildIndex()`） |
| 磁盘占用 | 约为原始文本的 30-50%，1 万用户日均千条消息量级可忽略 |
| 性能 | 单机百万文档级搜索毫秒级响应，远超 TeamTalk 需求 |

#### 评估

| 维度 | 评分 | 说明 |
|------|------|------|
| 额外依赖 | ★★★★☆ | lucene-core + ik-analyzer（~8MB JAR） |
| 实现复杂度 | ★★★★☆ | 嵌入式集成，无需外部进程或自定义镜像 |
| 中文分词 | ★★★★★ | IK Analyzer，Maven 依赖直接引入 |
| 搜索能力 | ★★★★★ | 模糊搜索、短语搜索、高亮、排序、过滤 |
| 一致性风险 | ★★★★☆ | RocksDB 和 Lucene 同进程写入，无网络分区风险 |

---

## 决策：Apache Lucene

### 选择理由

**核心原因：消息在 RocksDB 而非 PG 中，这使得 PG tsvector 方案天然不匹配。**

1. **无数据同步负担**：Lucene 与 RocksDB 同进程，写入路径自然延伸（存消息 → 写索引），不需要跨存储引擎同步数据。PG tsvector 方案则需要在 RocksDB 和 PG 之间建立双写或异步同步机制。

2. **中文分词零运维**：IK Analyzer 是纯 Java 库，`build.gradle.kts` 加一行依赖即可。PG 的 zhparser/pg_jieba 需要编译 C 扩展、自定义 Docker 镜像，增加部署复杂度。

3. **搜索能力更强**：Lucene 的搜索功能（模糊匹配、短语查询、结果高亮、自定义评分）远超 PG tsvector。ES 之所以选择 Lucene 作为底层引擎，正是因为这个能力。

4. **符合单体架构**：嵌入式库，不引入外部进程。与 RocksDB 一样是进程内组件，不会增加部署拓扑的复杂度。

5. **索引可重建**：Lucene 索引损坏时，可从 RocksDB 全量重建。PG tsvector 的搜索表如果与 RocksDB 失同步，没有可靠的对账机制。

### 代价

- 新增 ~8MB JAR 依赖（lucene-core + analysis-ik）
- 需要管理 Lucene IndexWriter 的生命周期（启动时打开、关闭时 flush）
- 索引文件占用磁盘空间（对 <1 万用户场景可忽略）

---

## 实施要点

### 依赖

```kotlin
// server/build.gradle.kts
implementation("org.apache.lucene:lucene-core:9.12.0")
implementation("org.apache.lucene:lucene-queryparser:9.12.0")
implementation("org.apache.lucene:lucene-highlighter:9.12.0")
// IK Analyzer（中文分词，从 Maven Central 或本地 JIR 引入）
implementation("com.github.magese:ik-analyzer:9.0.0")
```

> 注意：IK Analyzer 的最新版本发布情况需确认。如果 Maven Central 没有 9.x 兼容版本，备选方案包括基于 jieba 的 Java 实现（`com.huaban:jieba-analysis`）或 Lucene 内置的 `SmartChineseAnalyzer`。

### 服务端

- 新建 `SearchIndex` 类：封装 Lucene IndexWriter/IndexSearcher 的生命周期管理
- 写入钩子：在 `MessageService.sendMessage()` 中调用 `SearchIndex.indexMessage()`
- 删除钩子：在 `MessageService.revokeMessage()` 中同步删除索引
- 搜索 API：`GET /api/v1/messages/search?q=&channelId=&senderUid=&limit=&offset=`
- 索引重建命令：启动参数或管理 API 触发从 RocksDB 全量重建

### 索引字段

| 字段 | 类型 | 说明 |
|------|------|------|
| messageId | StringField | 唯一标识 |
| channelId | StringField | 频道 ID（精确过滤） |
| channelType | IntPoint | 频道类型 |
| senderUid | StringField | 发送者 UID（精确过滤） |
| text | TextField (IK Analyzer) | 消息文本内容（全文索引） |
| timestamp | LongPoint | 时间戳（范围过滤、排序） |
| messageType | IntPoint | 消息类型（过滤） |

### 客户端

- 新增搜索页面（复用现有 NavDestination 导航机制）
- 搜索结果列表，点击跳转到对应聊天上下文

---

## 与 Signal / Telegram 的对比

| | Signal | Telegram | TeamTalk（决策后） |
|---|---|---|---|
| 搜索引擎 | 不详（服务端未完全开源） | 自建（MTProto 协议集成） | Apache Lucene（嵌入式） |
| 客户端搜索 | 本地 SQLite FTS | 本地 SQLite FTS5 + 服务端 | 服务端搜索（未来可加客户端本地索引） |
| 中文分词 | 不适用（英文为主） | 内置分词 | IK Analyzer |
| 架构 | 微服务 | 分布式 | 单体嵌入式 |

Signal 和 Telegram 都在客户端做了大量的本地搜索优化（SQLite FTS）。TeamTalk 当前阶段先实现服务端搜索，P1 本地数据库完成后可在客户端侧增加本地搜索能力。
