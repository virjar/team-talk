# 群邀请链接设计

> 方案设计文档 — 通过链接/二维码邀请加入群组，支持过期、次数限制和可选审批流程

---

## 1. 需求分析

### 1.1 场景

当前添加群成员只能由群内成员主动邀请（逐个选择好友）。但在以下场景中，邀请链接更方便：

- 团队招募新成员，在论坛/邮件中分享链接
- 项目组临时拉人，不需要逐个加好友
- 跨组织协作，对方不在好友列表中

### 1.2 三方对比

| 维度 | Telegram | Discord | TeamTalk |
|------|----------|---------|----------|
| 邀请链接 | ✅ t.me/+xxx | ✅ discord.gg/xxx | ❌ 待实现 |
| 过期时间 | 1h / 1d / 自定义 / 永久 | 30m / 1h / 6h / 12h / 1d / 永久 | 待定 |
| 使用次数 | 无限 / 有限 | 无限 / 有限 | 待定 |
| 需审批 | 可选 | 可选 | 待定 |
| 二维码 | ✅ | ✅ | 待定 |

### 1.3 设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| Token 格式 | 8 字符 Base62 | 62^8 ≈ 2.2×10^14 种组合，短小易分享 |
| URL 格式 | `https://host/join/{token}` | 统一入口，客户端拦截 scheme |
| 过期策略 | 创建时指定，默认 7 天 | 中小型组织通常不需要永久链接 |
| 次数限制 | 可选，默认无限 | 灵活性优先 |
| 审批流程 | 可选开关 | 默认直接加入，群主可开启审批 |
| 每群链接数 | 最多 5 条 | 防止滥用，满足分渠道管理需求 |
| 权限 | OWNER + ADMIN | 与群管理权限一致（见 [19](./14-group-permission-matrix.md) §2） |

---

## 2. 数据模型

### 2.1 数据库表

```sql
CREATE TABLE group_invite_links (
    id              SERIAL PRIMARY KEY,
    token           VARCHAR(8) NOT NULL UNIQUE,      -- Base62 随机 token
    channel_id      VARCHAR(64) NOT NULL,             -- 关联群组
    creator_uid     VARCHAR(64) NOT NULL,             -- 创建者
    name            VARCHAR(64),                      -- 链接备注名（如"校园招聘群"）
    max_uses        INTEGER,                          -- 最大使用次数，NULL=无限
    use_count       INTEGER NOT NULL DEFAULT 0,       -- 已使用次数
    require_approval BOOLEAN NOT NULL DEFAULT false,  -- 是否需要审批
    expires_at      BIGINT,                           -- 过期时间 epoch ms，NULL=永不过期
    created_at      BIGINT NOT NULL,                  -- 创建时间 epoch ms
    revoked_at      BIGINT,                           -- 撤销时间 epoch ms，NULL=有效

    FOREIGN KEY (channel_id) REFERENCES channels(channel_id)
);

CREATE INDEX idx_invite_token ON group_invite_links(token);
CREATE INDEX idx_invite_channel ON group_invite_links(channel_id, revoked_at);
```

### 2.2 Kotlin 模型

```kotlin
data class GroupInviteLink(
    val id: Long,
    val token: String,
    val channelId: String,
    val creatorUid: String,
    val name: String? = null,
    val maxUses: Int? = null,         // null = 无限
    val useCount: Int = 0,
    val requireApproval: Boolean = false,
    val expiresAt: Long? = null,      // null = 永不过期
    val createdAt: Long,
    val revokedAt: Long? = null,      // null = 有效
)
```

---

## 3. API 设计

### 3.1 端点总览

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/api/v1/channels/{channelId}/invite-links` | 创建邀请链接 | OWNER + ADMIN |
| GET | `/api/v1/channels/{channelId}/invite-links` | 列出该群所有有效链接 | OWNER + ADMIN |
| DELETE | `/api/v1/channels/{channelId}/invite-links/{token}` | 撤销链接 | OWNER + ADMIN（或创建者） |
| POST | `/api/v1/invite/{token}` | 通过链接加入群组 | 已登录用户 |
| GET | `/api/v1/invite/{token}/info` | 查看链接信息（不加入） | 已登录用户 |
| GET | `/api/v1/channels/{channelId}/invite-links/{token}/join-requests` | 查看待审批列表 | OWNER + ADMIN |

### 3.2 创建邀请链接

```
POST /api/v1/channels/{channelId}/invite-links

Request:
{
  "name": "校园招聘群",            // 可选，链接备注名
  "maxUses": 50,                  // 可选，NULL=无限
  "requireApproval": false,       // 可选，默认 false
  "expiresIn": 604800000          // 可选，毫秒，默认 7 天（NULL=永不过期）
}

Response:
{
  "token": "aB3kL9xM",
  "url": "https://im.example.com/join/aB3kL9xM",
  "expiresAt": 1712604800000,
  ...
}
```

**校验规则**：
- 每群最多 5 条有效链接
- `expiresIn` 最大 90 天
- Token 生成：8 字节 SecureRandom → Base62 编码 → 检查唯一性

### 3.3 通过链接加入

```
POST /api/v1/invite/{token}

Response (直接加入):
{
  "joined": true,
  "channelId": "group_001",
  "channelName": "产品讨论组"
}

Response (需要审批):
{
  "joined": false,
  "pendingApproval": true,
  "channelId": "group_001",
  "channelName": "产品讨论组"
}
```

**加入前的校验**：
1. 链接有效（未撤销、未过期、使用次数未达上限）
2. 用户已登录
3. 用户不是该群成员
4. 用户未被该群封禁（如果有黑名单功能）
5. 群未满员（maxMemberCount）

### 3.4 查看链接信息（不加入）

```
GET /api/v1/invite/{token}/info

Response:
{
  "channelId": "group_001",
  "channelName": "产品讨论组",
  "memberCount": 42,
  "requireApproval": false,
  "expiresAt": 1712604800000,
  "creatorName": "张三"          // 创建者昵称（脱敏）
}
```

用途：客户端展示"确认加入"对话框前的预览信息。

---

## 4. 审批流程

当 `requireApproval=true` 时，通过链接加入变为"申请加入"：

```
流程:
  1. 用户点击链接 → 客户端调用 GET /info 展示群信息
  2. 用户确认 → 调用 POST /invite/{token}
  3. 服务端创建 join_request 记录
  4. 服务端通过 CMD("join_request") 通知 OWNER + ADMIN
  5. ADMIN 调用 POST /api/v1/channels/{id}/join-requests/{reqId}/approve 或 /reject
  6. 服务端将用户加入群组，通过 CMD("join_handled") 通知申请人
```

### 4.1 审批数据表

```sql
CREATE TABLE group_join_requests (
    id              SERIAL PRIMARY KEY,
    channel_id      VARCHAR(64) NOT NULL,
    uid             VARCHAR(64) NOT NULL,             -- 申请人
    invite_token    VARCHAR(8) NOT NULL,              -- 来源链接
    status          SMALLINT NOT NULL DEFAULT 0,      -- 0=待审批, 1=已通过, 2=已拒绝
    handler_uid     VARCHAR(64),                      -- 审批人
    handled_at      BIGINT,
    created_at      BIGINT NOT NULL,

    FOREIGN KEY (channel_id) REFERENCES channels(channel_id)
);
```

### 4.2 审批 API

```
POST   /api/v1/channels/{channelId}/join-requests/{requestId}/approve    — 通过
POST   /api/v1/channels/{channelId}/join-requests/{requestId}/reject     — 拒绝
```

---

## 5. 二维码

链接生成后，客户端本地生成二维码（使用 `io.github.alexzhirkevich:qr` 库，已在 app 模块中依赖）：

```
二维码内容 = https://{host}/join/{token}
```

客户端拦截规则：当检测到 URL 匹配 `{host}/join/{token}` 时，自动调用 `GET /info` 展示加入确认对话框，而非打开浏览器。

---

## 6. 系统消息通知

邀请链接加入群组后，服务端在群内广播系统消息：

```json
{
  "packetType": 93,           // MEMBER_ADDED
  "channelId": "group_001",
  "payload": {
    "memberUid": "uid_456",
    "inviterUid": null,       // null 表示通过链接加入
    "inviteToken": "aB3kL9xM" // 来源链接（可选展示）
  }
}
```

群成员列表中显示加入来源为"邀请链接"。

---

## 7. 安全考量

| 风险 | 对策 |
|------|------|
| 暴力破解 token | 8 字符 Base62 = 2.2×10^14 组合，暴力破解不现实；服务端限频（每 IP 每分钟 30 次） |
| 链接泄露 | 支持随时撤销；创建者可设置过期时间和次数限制 |
| 机器人大规模加群 | require_approval 开关 + 限频 |
| 已退群用户反复加入 | 群设置可控制是否允许退群用户重新加入（暂不实现，留作扩展） |
