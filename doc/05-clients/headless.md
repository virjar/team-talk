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

`ImBot` 位于 `shared`，直接复用 ClientSession。它使用内存 `FakeLocalCache`，进程退出后不保留会话投影；需要长期缓存的产品应提供明确的持久化实现，不能假设 FakeLocalCache 是生产数据库。

主要能力：

- 注册、登录、等待连接状态和级联关闭；
- 订阅消息、联系人、群变更、presence 和 typing 流；
- 发送 Markdown、文件、图片、语音、视频和交互卡片；
- 上传文件、等待 ACK、拉历史、撤回、转发和已读；
- 用户搜索、好友申请/接受/删除；
- 创建私聊、创建群、邀请成员和读取成员。

```kotlin
val bot = ImBot.login(host, port, username, password)
bot.awaitState()

bot.messages.collect { message ->
    if (message.senderUid != bot.uid) {
        bot.sendText(message.chatId, "收到：${message.serverSeq}")
    }
}
```

MESSAGE_RECV 会发给包含发送者在内的成员，因此 echo bot 必须过滤自己的消息。`shutdown()` 是 ImBot 的生命周期终点，并级联关闭 session 和连接资源。

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

agent 在内存中保留最近 1000 条收到的消息，并为长轮询请求提供唤醒。它不是完整历史库；历史必须通过 message RPC 查询。

## 4. 本地 REST

除 `/v1/status` 外，端点要求：

```http
Authorization: Bearer <agent-api-token>
Content-Type: application/json
```

响应统一为 `{ "ok": true, "data": {...} }` 或 `{ "ok": false, "error": "..." }`。

### 状态与接收

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/v1/status` | 连接状态、uid、username、缓冲数量 |
| GET | `/v1/messages?chatId=&limit=&afterSeq=` | 读取内存环形缓冲 |
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

`send-file` 先通过 TeamTalk HTTP 端点上传，再用服务端返回的 Attachment 发送消息。REST 返回 200 只表示 agent 调用成功；消息结果仍应检查 data 中的 ACK code。

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

代码目前注册了 `/v1/selftest` 路由，但 AgentApi 没有对应分支，调用会返回 unknown。它不是可依赖的公开端点，补齐实现和测试后才能加入正式表格。

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
- `/v1/status` 当前不校验 API token，会暴露 uid 和 username；在修复前只能运行在可信本机。
- `credentials.properties` 当前保存可恢复的用户名和密码以及 API token。运行用户目录权限必须收紧；正式产品应接入系统密钥库或只持久化可轮换 token。
- CLI token 不应出现在命令历史、日志或代码仓库。
- `send-file` 读取 agent 主机本地路径，只能在受信任调用者可以访问本地 REST 时使用。
- agent 的环形缓冲和长轮询不是 Webhook；进程重启会丢失缓冲，可靠外部订阅仍需单独设计。

无头能力的产品化计划见[路线图](../10-reference/roadmap.md)，当前成熟度见[功能状态](../10-reference/feature-status.md)。
