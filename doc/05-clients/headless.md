# 无头客户端与自动化接入

无头客户端是 TeamTalk 的一种正式客户端形态：它使用与 Desktop、Android 相同的协议、认证、Repository、事件和附件安全链路，但不创建 UI。适合机器人、业务桥接、测试对端和 AI 工具。

## 1. 组件关系

```text
外部程序 / 人 / AI
       │
       ├── tt-cli ───────┐
       └── tt-mcp ───────┤  loopback HTTP + Bearer token
                          ▼
                      tt-agent
                          │ 持有常驻 ImBot
                          ▼
        ImBot → ClientSession → ImClient → TeamTalk server
```

| 组件 | 职责 | 是否持有 IM 连接 |
|---|---|---|
| `ImBot` | Kotlin 无头 SDK 入口，提供强类型 API 与事件流 | 是 |
| `tt-agent` | 常驻进程、凭据、消息缓冲和本地 REST | 是 |
| `tt-cli` | 面向人和脚本的无状态命令行 | 否 |
| `tt-mcp` | 把核心 REST 操作映射为 MCP tools | 否 |

任何层都不能绕过 SDK 直接拼 wire 或写数据库。这样机器人发送文件时仍会经过路径规范化、服务器存在性校验和 ACK 成功语义。

## 2. ImBot

`ImBot` 位于 `shared`，直接复用 ClientSession。登录/注册支持显式注入 `ImBotCacheOwner`；
`tt-agent` 固定使用 `PersistentImBotCacheOwner(dataDir)`，认证成功取得 uid 后才打开
`dataDir/users/<uid>/`，因此进程重启会从该账号已提交的 cursor 继续。登录/注册不再提供隐式
owner；短生命周期测试也必须显式注入
`ImBotCacheOwner { FakeLocalCache() }`，避免测试便利路径被常驻机器人误用。

主要能力：

- 注册、登录、等待连接状态和级联关闭；
- 订阅消息、联系人、群变更、presence 和 typing 流；
- 发送 Markdown、文件、图片、语音、视频和交互卡片；
- 上传文件、等待 ACK、拉历史、撤回、转发和已读；
- 用户搜索、好友申请/接受/删除；
- 创建私聊、创建群、邀请成员和读取成员。

```kotlin
val inbox = ImBotMessageInbox()
val bot = ImBot.login(
    host, port, username, password,
    cacheOwner = PersistentImBotCacheOwner(dataDir),
    messageInbox = inbox,
)
bot.awaitState()

while (true) {
    val delivery = bot.nextMessageDelivery { it.senderUid != bot.uid }
    bot.sendText(delivery.message.chatId, "收到：${delivery.message.serverSeq}")
    bot.ackMessage(delivery)
}
```

消息先进入普通持久投影，再以 eventId 写入账号 SQLite 的 `bot_message_inbox`，最后才推进 cursor。
inbox 对 `(chatId, serverSeq)` 另有唯一约束，服务端即使以不同 eventId 重试同一 projection 也只
产生一条业务 delivery。进程内只有 CONFLATED wake-up，因此大 backlog 不依赖消费者启动时序，
也不会形成内存队列。崩溃发生在 inbox INSERT 与 cursor 提交之间时，事件会重放但被幂等键吸收；
cursor 已提交而业务尚未消费时，pending 磁盘行会在重启后继续交付。

`nextMessageDelivery` 是显式 peek/ack：业务成功后才调用 `ackMessage`，未 ack 的 delivery 重启会
再次出现。`nextMessage` 是兼顾简单脚本的 at-most-once 便利 API，会在返回前自动 ack，不适合直接
驱动不可重入的外部副作用。tt-agent ack inbox 后，REST `/messages` 与 `/recv-wait` 仍从普通消息
SQLite 投影读取，而不是以内存 ring 为事实源，所以进程崩溃不会抹掉 REST 可见 recent 历史。
`bot.messages` 只是不对 cursor 施加背压的实时广播，不应用作可靠 backlog 队列。

等待 `AUTHENTICATED` 使用 15 秒“无同步进展”窗口，而不是 15 秒总时长；
`SYNCHRONIZING` cursor 每次持久推进都会续期，因此大历史回放只要持续前进就不会误超时。

磁盘 inbox 提供 at-least-once delivery，不承诺外部副作用 exactly-once；调用方仍应以
`(chatId, serverSeq)` 或业务幂等键去重。

MESSAGE_RECV 会发给包含发送者在内的成员，因此 echo bot 必须过滤自己的消息。`shutdown()` 是 ImBot 的生命周期终点，并级联关闭 session 和连接资源。
ImBot 是可多实例 SDK；创建时把 `fileServerUrl` 固定到自身认证会话，文件上传逐次读取该
`UserSession` 的原子凭据快照。同 uid 重连后的 token 轮换自动生效，uid 变化则失败关闭；
`shutdown()` 同时关闭文件 Repository 和活跃 HTTP 连接，不存在进程全局登录 token。

## 3. 构建与启动 agent

```bash
./gradlew :shared:headlessDist

shared/build/headless/bin/tt-agent \
  --host im.example.com \
  --port 5100 \
  --server-url https://im.example.com \
  --user bot-user \
  --pass '<password>'
```

默认值：

| 参数/环境变量 | 默认 | 作用 |
|---|---|---|
| `--host` / `TK_HOST` | `im.virjar.com` | TCP 主机 |
| `--port` / `TK_PORT` | `5100` | TCP 端口 |
| `--server-url` / `TK_SERVER_URL` | `https://<host>` | 文件 HTTP 根地址 |
| `--api` | `127.0.0.1:8600` | 本地 REST 监听 |
| `--data-dir` / `TK_AGENT_DIR` | `~/.tt-agent` | 凭据与 API token 目录 |
| `--user` / `TK_USER` | 无 | 登录用户名 |
| `--pass` / `TK_PASS` | 无 | 登录密码 |
| `--register --prefix <name>` | 关闭 | 注册随机后缀账户 |

agent 的普通 SQLite 消息投影保存 REST recent 事实，单次最多返回 1000 条；内存只为长轮询提供
唤醒，不保存业务消息。完整、可分页的服务端历史仍通过 message RPC 查询。

## 4. 本地 REST

所有端点（包括 `/v1/status`）都要求：

```http
Authorization: Bearer <agent-api-token>
Content-Type: application/json
```

响应统一为 `{ "ok": true, "data": {...} }` 或 `{ "ok": false, "error": "..." }`。

### 状态与接收

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/v1/status` | 连接状态、uid、username、缓冲数量 |
| GET | `/v1/messages?chatId=&limit=&afterSeq=` | 读取 SQLite recent 消息投影 |
| GET | `/v1/recv-wait?chatId=&timeout=` | 等待一条消息 |
| GET/POST | `/v1/conversations` | 会话列表 |
| GET/POST | `/v1/friends` | 好友列表 |
| GET/POST | `/v1/friend-pending` | 待处理好友申请 |

### 消息与文件

| 方法 | 路径 | body |
|---|---|---|
| POST | `/v1/send-text` | `{chatId, text}` |
| POST | `/v1/send-rich` | `{chatId, markdown}` |
| POST | `/v1/send-file` | `{chatId, path}`，path 是 agent 所在机器的本地文件 |
| POST | `/v1/upload` | `{path}` |
| POST | `/v1/history` | `{chatId, fromSeq, limit}` |
| POST | `/v1/revoke` | `{chatId, serverSeq}` |
| POST | `/v1/forward` | `{srcChatId, srcSeq, targetChatId}` |
| POST | `/v1/mark-read` | `{chatId, readSeq}` |

`send-file` 以流式 `File` source 通过 TeamTalk HTTP 端点上传，再用服务端返回的 Attachment 发送
消息，不把本地附件整体读入内存。REST 返回 200 只表示 agent 调用成功；消息结果仍应检查 data
中的 ACK code。

### 联系人与群组

| 方法 | 路径 | body |
|---|---|---|
| POST | `/v1/users-search` | `{keyword}` |
| POST | `/v1/chat-personal` | `{targetUid}` |
| POST | `/v1/friend-apply` | `{targetUid, remark?}` |
| POST | `/v1/friend-accept` | `{token}` |
| POST | `/v1/group-create` | `{name, memberUids}`，成员用逗号分隔 |
| POST | `/v1/group-members` | `{chatId}` |
| POST | `/v1/group-invite` | `{chatId, uids}`，成员用逗号分隔 |

## 5. CLI

把 agent 启动时生成的 API token 写入 `~/.tt-cli`，或通过 `TT_TOKEN` / `--token` 提供：

```bash
shared/build/headless/bin/tt status
shared/build/headless/bin/tt conversations
shared/build/headless/bin/tt user-search alice
shared/build/headless/bin/tt chat-with <uid>
shared/build/headless/bin/tt send <chatId> 'hello'
shared/build/headless/bin/tt send-rich <chatId> '**hello**'
shared/build/headless/bin/tt recv --chatId <chatId> --wait 10
shared/build/headless/bin/tt send-file <chatId> /absolute/path/report.pdf
```

其他命令包括 `messages`、`history`、`upload`、`revoke`、`forward`、`mark-read`、`friends`、`friend-pending`、`friend-add`、`friend-accept`、`group-create`、`group-members` 和 `group-invite`。`--json` 输出机器可读 data。

## 6. MCP

`tt-mcp` 使用 stdio JSON-RPC，内部仍调用 agent REST，不持有第二条 IM 连接。当前工具集：

```text
status, conversations, friends,
send_text, send_markdown,
recv, messages, history,
search_users, chat_with,
mark_read, revoke
```

MCP 是适配层，不是新的权限边界。模型能做什么取决于 agent 账户在 TeamTalk 中的权限；生产化前还需要管理员授权、审计、速率限制和会话白名单。

## 7. 安全与运行边界

- REST 默认只绑定 loopback。不要把 `--api` 改为公网地址；当前 HTTP 服务没有 TLS、来源限制或细粒度授权。
- 所有 REST 端点都校验同一个 agent API token；仍应保持 loopback 监听，避免把拥有完整客户端权限的接口暴露到局域网或公网。
- `credentials.properties` 当前保存可恢复的用户名和密码以及 API token。运行用户目录权限必须收紧；正式产品应接入系统密钥库或只持久化可轮换 token。
- CLI token 不应出现在命令历史、日志或代码仓库。
- `send-file` 读取 agent 主机本地路径，只能在受信任调用者可以访问本地 REST 时使用。
- agent 的长轮询不是 Webhook；内存唤醒在进程重启时会消失，但消息仍在 SQLite recent 投影中，
  重连后可通过 `/v1/messages` 读取。需要消费确认、多个订阅者或永久保留时仍应单独设计外部订阅。

无头能力的产品化计划见[路线图](../10-reference/roadmap.md)，当前成熟度见[功能状态](../10-reference/feature-status.md)。

## 8. 受控通知机器人

只需要由 CI、监控或审批系统主动向群发通知时，不应启动持有完整客户端身份的 ImBot。当前群成员可以从
群设置创建通知机器人，保存 TeamTalk 一次性签发的 `ttb_...` token，再把页面给出的入站通知 URL
交给外部系统调用。URL 已绑定目标群，调用正文只需要 Markdown，不需要让外部系统另外维护 chatId。

完整的群内工作流、HTTP 契约、curl/Python/GitHub Actions 示例、错误码和凭据安全边界见
[通知机器人接入](notification-bots.md)。通知机器人是单向、最小权限身份；ImBot/tt-agent 是可以
接收事件和执行双向业务的完整客户端，二者不能共享 token 或权限模型。
