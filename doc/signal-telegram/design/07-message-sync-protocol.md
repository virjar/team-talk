# 消息同步协议设计

> 基于 serverSeq 的增量同步方案——初始同步、实时推送、离线追赶、多设备已读同步

---

## 1. 设计目标

| 目标 | 说明 |
|------|------|
| 初始同步 | 新设备首次登录，拉取所有活跃会话的近期消息 |
| 增量同步 | 基于 serverSeq 的增量拉取，避免全量传输 |
| 离线追赶 | 断线重连后自动补拉离线期间的消息 |
| 多设备已读同步 | 在不同设备上标记已读后，其他设备同步更新 |

核心约束：TeamTalk 面向中小型组织（<1 万用户），每个频道拥有**单调递增的 serverSeq**。同步协议围绕这个单一序号构建，无需引入全局 pts。

---

## 2. 初始同步流程

```
Client                              Server
  │──── TCP CONNECT ──────────────────→│  握手 + 认证
  │←─── TCP CONNACK ──────────────────│
  │──── GET /api/v1/conversations ────→│  拉取会话列表
  │←─── [conversation list] ──────────│
  │  对每个活跃会话:                      │
  │    ├─ 本地无记录 → 拉取最近 50 条      │
  │    └─ 本地有 lastSeq → 增量补拉       │
  │──── SUBSCRIBE(ch1..chN) ─────────→│  订阅所有活跃频道
  │  开始接收实时推送                     │
```

1. **TCP 连接 + 握手成功**
2. **HTTP 拉取会话列表**，获取所有活跃会话及各会话的 `lastSeq`
3. **对每个会话**：本地有 `lastSeq` 则 SUBSCRIBE 增量补拉，无则 SUBSCRIBE(channelId, 0) 拉取最近 N 条
4. **SUBSCRIBE 所有活跃频道**，开始接收实时推送

---

## 3. 增量同步协议

复用 SUBSCRIBE 包，扩展 payload：

```
SUBSCRIBE(10) payload:
  channelId: String       // 目标频道 ID
  lastSeq: VarInt         // 客户端最后已知 seq（0 = 拉取最近 N 条）
```

### 服务端响应策略

| lastSeq 后消息数 | 策略 | 说明 |
|-----------------|------|------|
| <= 100 | TCP 批量推送 | write-without-flush + batch flush，合并为一个 TCP 段 |
| > 100 | 截断标记 + HTTP 降级 | CMD(`sync_truncate`) 通知客户端改用 HTTP 分页拉取 |

超限时的截断标记：

```
CMD(100) payload:
  cmdType: "sync_truncate"
  payload: { "channelId": "xxx", "total": 350 }
```

---

## 4. 间隙检测（Gap Detection）

客户端维护每个频道的 `expectedSeq`，检测到 seq 不连续时自动补拉：

```
Client                              Server
  │  收到 seq=100, expectedSeq=101      │
  │  收到 seq=103 → 缺失 101-102        │
  │──── SUBSCRIBE(ch, lastSeq=100) ──→│  补拉
  │←─── seq=101, seq=102 ────────────│
```

### 客户端状态机

```
每个频道维护:
  lastContiguousSeq: Long              // 最后连续的 seq
  pendingBuffer: Map<Long, Message>    // 缓存超前的消息

收到消息(msgSeq):
  msgSeq == lastContiguousSeq + 1 → 写入 DB, 顺带检查 pendingBuffer
  msgSeq >  lastContiguousSeq + 1 → 放入 pendingBuffer, 触发补拉
  msgSeq <= lastContiguousSeq     → 重复, 忽略（幂等）
```

补拉超时（5s）：超时未修复则标记间隙消息不可用，UI 显示"部分消息加载失败"。

---

## 5. 多设备已读同步

通过 CMD(100) 推送已读状态变更：

```
设备 A ── RECVACK(seq=42) ──→ Server ── CMD(read_sync) ──→ 设备 B
                                       更新 readSeq = 42     更新本地已读 + 未读计数
```

```
CMD(100) payload:
  cmdType: "read_sync"
  payload: { "channelId": "ch_xxx", "readSeq": 42, "readAt": 1712304000000 }
```

会话列表增量同步同样通过 CMD，客户端基于 `version` 字段判断是否更新：

```
CMD(100) payload:
  cmdType: "conversation_sync"
  payload: { "channelId": "ch_xxx", "unreadCount": 3, "version": 15 }
```

---

## 6. 数据一致性保证

### 幂等写入

`messages` 表使用 `PRIMARY KEY (channel_id, seq)` + `UNIQUE (message_id)`。重复推送（补拉与实时推送交叉）不产生重复数据，客户端使用 `INSERT OR IGNORE`。

### 乱序处理

TCP 推送可能乱序到达。客户端按 seq 排序后写入本地数据库，UI 从本地读取，不依赖 TCP 到达顺序。

### 本地优先

```
┌────────────────────────────────┐
│            UI 层               │
│      只从本地数据库读取          │
├────────────────────────────────┤
│        Repository 层           │
│  ┌──────────┐ ┌──────────┐    │
│  │ Local DB │ │ Network  │    │
│  │ (主数据源)│ │(同步到本地)│    │
│  └──────────┘ └──────────┘    │
└────────────────────────────────┘
```

消息发送采用乐观更新：先写入本地，服务端确认后更新状态。网络不可用时本地数据仍可展示。

---

## 7. 与 Telegram getDifference 的对比

Telegram 使用全局 `pts` 追踪所有变更：`getDifference(pts=100)` 一次性返回新消息、编辑、删除、已读等所有差异。

TeamTalk 简化版：

| 维度 | Telegram | TeamTalk |
|------|----------|----------|
| 同步维度 | 全局 pts | 频道维度 serverSeq |
| 变更类型 | 全部统一 | 消息用 seq，已读用 CMD |
| 协议复杂度 | 高（全局状态机） | 低（频道独立） |
| 适用规模 | 数亿用户 | <1 万用户 |

选择频道维度 serverSeq：更直观（seq 独立递增）、更容错（频道互不影响）、更易实现（RocksDB 天然按频道分区）、规模匹配。

---

## 总结

| 机制 | 协议载体 | 说明 |
|------|---------|------|
| 初始同步 | SUBSCRIBE(10) + HTTP API | 新设备拉取活跃会话近期消息 |
| 增量同步 | SUBSCRIBE(10, lastSeq) | <=100 条 TCP 批量推送，>100 条降级 HTTP |
| 实时推送 | PacketType(20-36) | 订阅后实时接收新消息 |
| 间隙检测 | 客户端 expectedSeq | 自动补拉，5s 超时标记不可用 |
| 已读同步 | CMD(100, read_sync) | 多设备已读状态实时同步 |
| 幂等保证 | messageId UNIQUE | 重复推送不产生重复数据 |
| 乱序处理 | 客户端按 seq 排序写入 | 不依赖 TCP 到达顺序 |
