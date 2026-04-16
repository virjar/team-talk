# 错误码体系设计

> TeamTalk 统一错误码体系：覆盖 TCP 协议层（SENDACK/CONNACK）、HTTP API 层、CMD 推送层。

---

## 1. 设计原则

| 原则 | 说明 |
|------|------|
| 分层不嵌套 | TCP 协议层、HTTP API 层各有独立编码，不混用 |
| 0=成功 | 所有层统一使用 0 表示成功 |
| 可扩展 | 预留编码空间，新增错误码不破坏旧客户端 |
| 人类可读 | 每个错误码都有 reason 字符串，方便调试 |
| 最小化 | 仅定义已知需要的错误码，不过度设计 |

---

## 2. TCP 协议层错误码

### 2.1 握手响应码（CONNACK）

握手响应包中的 Code 字段（1 字节）：

| Code | 常量 | 说明 | 客户端行为 |
|------|------|------|-----------|
| 0 | AUTH_OK | 认证成功 | 进入包交换阶段 |
| 1 | AUTH_FAILED | token 无效或过期 | 清除本地 token，跳转登录页 |
| 2 | VERSION_UNSUPPORTED | 协议版本不兼容 | 提示升级客户端 |
| 3 | SERVER_MAINTENANCE | 服务器维护中 | 显示维护提示，延迟重连 |
| 4 | DEVICE_BANNED | 设备被封禁 | 提示联系管理员 |
| 5 | TOO_MANY_CONNECTIONS | 同 UID 连接数超限 | 断开最旧的连接或提示 |

### 2.2 SENDACK 响应码

SENDACK 包中的 code 字段（VarInt）：

| Code | 常量 | 说明 |
|------|------|------|
| 0 | SEND_OK | 发送成功 |
| 1 | SEND_RATE_LIMITED | 发送频率超限 |
| 2 | SEND_CHANNEL_NOT_FOUND | 频道不存在 |
| 3 | SEND_NO_PERMISSION | 无权限（非成员发送、被禁言等） |
| 4 | SEND_PAYLOAD_TOO_LARGE | 负载超过 16 MB |
| 5 | SEND_DUPLICATE | 重复消息（clientMsgNo 已存在） |
| 6 | SEND_CHANNEL_FULL | 频道成员已满 |
| 7 | SEND_CONTENT_REJECTED | 内容审核不通过 |

> SEND_DUPLICATE(5) 是幂等成功的特殊情况——客户端收到此码可视为发送成功（服务端已有该消息）。

### 2.3 ACK 通用响应码

ACK 包（PacketType.ACK=101）的通用 code 字段：

| Code | 常量 | 说明 |
|------|------|------|
| 0 | ACK_OK | 确认成功 |
| 1 | ACK_INVALID_PACKET | 无效的包格式 |
| 2 | ACK_UNKNOWN_TYPE | 未知的 PacketType |
| 3 | ACK_SERVER_ERROR | 服务端内部错误 |

---

## 3. HTTP API 层错误码

HTTP API 使用标准 HTTP 状态码 + JSON body 中的业务错误码：

```json
{
  "code": 40001,
  "message": "好友申请已存在，请勿重复申请"
}
```

### 3.1 HTTP 状态码约定

| HTTP Status | 含义 | 是否有业务错误码 |
|-------------|------|----------------|
| 200 | 成功 | 无（直接返回数据） |
| 400 | 请求参数错误 | 有 |
| 401 | 未认证（token 缺失/无效） | 无 |
| 403 | 无权限 | 有 |
| 404 | 资源不存在 | 有 |
| 409 | 冲突（重复操作） | 有 |
| 429 | 频率限制 | 无 |
| 500 | 服务端内部错误 | 无 |

### 3.2 业务错误码编码规则

```
5 位数字: A BB CC

A:    模块大类
BB:   子模块
CC:   具体错误

示例: 4 01 03 = 频道/群组模块(4) + 频道(01) + 频道已满(03)
```

### 3.3 模块编码

| 模块 | 前缀 | 说明 |
|------|------|------|
| 1xxxx | 认证 | 注册、登录、token 刷新 |
| 2xxxx | 用户 | 资料修改、搜索、头像 |
| 3xxxx | 联系人 | 好友申请、确认、删除、黑名单 |
| 4xxxx | 频道/群组 | 创建、成员管理、设置 |
| 5xxxx | 消息 | 发送、搜索、撤回、编辑 |
| 6xxxx | 文件 | 上传、下载、配额 |
| 7xxxx | 会话 | 同步、置顶、静音 |
| 8xxxx | 管理后台 | 群管理、用户管理（预留） |

### 3.4 常用错误码详表

#### 认证模块 (1xxxx)

| 错误码 | HTTP | 常量 | 说明 |
|--------|------|------|------|
| 10001 | 400 | PHONE_INVALID | 手机号格式无效 |
| 10002 | 400 | CODE_INVALID | 验证码错误 |
| 10003 | 400 | CODE_EXPIRED | 验证码过期 |
| 10004 | 401 | TOKEN_EXPIRED | token 已过期 |
| 10005 | 401 | TOKEN_INVALID | token 无效 |
| 10006 | 409 | USER_ALREADY_EXISTS | 用户已注册 |

#### 联系人模块 (3xxxx)

| 错误码 | HTTP | 常量 | 说明 |
|--------|------|------|------|
| 30001 | 400 | APPLY_SELF | 不能向自己发起好友申请 |
| 30002 | 409 | APPLY_EXISTS | 好友申请已存在 |
| 30003 | 403 | APPLY_BLOCKED | 对方已将你加入黑名单 |
| 30004 | 404 | APPLY_NOT_FOUND | 好友申请不存在 |
| 30005 | 400 | ALREADY_FRIENDS | 已经是好友 |
| 30006 | 403 | NOT_FRIENDS | 非好友关系 |

#### 频道/群组模块 (4xxxx)

| 错误码 | HTTP | 常量 | 说明 |
|--------|------|------|------|
| 40001 | 404 | CHANNEL_NOT_FOUND | 频道不存在 |
| 40002 | 403 | NOT_MEMBER | 非频道成员 |
| 40003 | 403 | ALREADY_MEMBER | 已是成员 |
| 40004 | 403 | MEMBER_LIMIT_EXCEEDED | 成员数达到上限 |
| 40005 | 403 | NOT_OWNER | 非群主 |
| 40006 | 403 | NOT_ADMIN | 非管理员（含以上） |
| 40007 | 403 | OWNER_CANNOT_LEAVE | 群主不能退出（只能解散） |
| 40008 | 400 | GROUP_NAME_EMPTY | 群名不能为空 |

#### 消息模块 (5xxxx)

| 错误码 | HTTP | 常量 | 说明 |
|--------|------|------|------|
| 50001 | 404 | MESSAGE_NOT_FOUND | 消息不存在 |
| 50002 | 403 | REVOKE_TIMEOUT | 超出撤回时间窗口（2 分钟） |
| 50003 | 403 | REVOKE_NOT_SENDER | 只能撤回自己发送的消息 |
| 50004 | 400 | EDIT_CONTENT_EMPTY | 编辑内容不能为空 |
| 50005 | 400 | FORWARD_INVALID | 转发目标无效 |

#### 文件模块 (6xxxx)

| 错误码 | HTTP | 常量 | 说明 |
|--------|------|------|------|
| 60001 | 400 | FILE_EMPTY | 上传文件为空 |
| 60002 | 400 | FILE_TOO_LARGE | 文件超过大小限制 |
| 60003 | 400 | FILE_TYPE_NOT_ALLOWED | 文件类型不允许 |
| 60004 | 404 | FILE_NOT_FOUND | 文件不存在 |

---

## 4. CMD 推送层错误码

CMD 包中的 JSON payload 可携带错误信息：

```json
{
  "cmd": "error",
  "params": {
    "code": 50001,
    "message": "消息不存在",
    "relatedPacketType": 30,
    "relatedMessageId": "msg_xxx"
  }
}
```

`cmd: "error"` 用于服务端主动通知客户端某个操作失败，通常在异步操作场景下使用（如消息撤回在服务端验证失败）。

---

## 5. 客户端处理策略

### 5.1 错误码优先级

| 层级 | 来源 | 处理策略 |
|------|------|---------|
| L1 | HTTP 401 / CONNACK code=1 | 清除 token，跳转登录 |
| L2 | HTTP 429 / SENDACK code=1 | 显示"操作太频繁"，延迟重试 |
| L3 | HTTP 4xx + 业务错误码 | 显示具体错误提示 |
| L4 | HTTP 5xx / SENDACK code=7 | 显示"服务器错误"，稍后重试 |

### 5.2 错误提示映射

客户端维护一个错误码到用户友好消息的映射表。对于未知错误码（新增的或旧客户端不识别的），统一显示"操作失败，请稍后重试"。

---

## 6. 与现有代码的映射

当前代码中的零散定义：

| 位置 | 当前 | 迁移到 |
|------|------|--------|
| `Payloads.kt` ConnAckPayload.code | 硬编码 0/1/2 | §2.1 握手响应码常量 |
| `Payloads.kt` SendAckPayload.code | 仅 0=成功 | §2.2 SENDACK 响应码常量 |
| `AuthRoutes.kt` HttpStatusCode | 直接使用 HTTP 状态码 | §3.1 + 业务错误码 |
| `MessageRoutes.kt` HttpStatusCode | 直接使用 HTTP 状态码 | §3.1 + 业务错误码 |

迁移时服务端统一使用错误码常量类返回，客户端统一解析。
