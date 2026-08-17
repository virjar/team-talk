# CLI / AI 员工基础设施（P1 战略方向设计稿）

> 2026-08 立项设计。定位：把"无头 IM"从百行验证脚本升级为**产品级 AI 员工运行时**。
> 本文是实施事实源，分四期落地。

## 架构总览

```
┌─────────────────────────  宿主机 ─────────────────────────┐
│                                                            │
│  ┌──────────────┐  本地 HTTP (127.0.0.1:8600)  ┌────────┐ │
│  │  tt-cli      │◄────────────────────────────►│ tt-agent│ │
│  │ (每次调用起)  │   JSON-RPC 风格 REST          │ 守护进程 │ │
│  └──────────────┘                               └───┬────┘ │
│  ┌──────────────┐  stdio (MCP 协议)                 │      │
│  │ MCP server   │◄── 复用同一 REST ─────────────────┤      │
│  └──────────────┘                                   │      │
│                                            TCP 5100 ▼      │
│                                              ┌──────────┐  │
│                                              │IM server │  │
│                                              └──────────┘  │
└────────────────────────────────────────────────────────────┘
```

- **tt-agent（守护进程）**：持有 ImBot 会话常驻——长连接保活/自动重连、消息缓冲、
  大文件上传下载（耗时任务不阻塞命令通道）、状态缓存（会话/好友/群）。部署为
  systemd（Linux）/ Windows Service（后续）。**它本身就是现有 HeadlessMain 的
  常驻化升级**，不是新进程。
- **tt-cli（命令行）**：无状态薄客户端，每次调用通过本地 HTTP 与 agent 交互，
  输出人类可读或 `--json` 机器可读。CI/e2e 用它替代"每次创建连接"的测试脚本。
- **MCP server**：stdio 协议适配层，把 CLI 的 REST 端点映射为 MCP tools，
  供大模型（Claude/其他）直接操作 IM。复用 agent 的 REST，零额外状态。

## 一期：tt-agent 守护进程 + 本地 REST API

**进程模型**：`bin/tt-agent --host im.virjar.com --port 5100 --user xx --pass yy
--api 127.0.0.1:8600 [--data-dir ~/.tt-agent]`

- 启动：登录（或 token 静默重连，复用 refresh-token 体系）→ 连接保活 →
  REST listen（仅 127.0.0.1，Token 简单鉴权：`--api-token` 随机生成打印，
  CLI 端从 `~/.tt-agent/token` 读）
- 凭据持久化：`~/.tt-agent/`（credentials.properties + 会话缓存），
  重启后静默重连（同桌面端 TokenStore 语义）
- 消息缓冲：断线期间 REST 拒绝发送（明确 busy 状态）；在线期间收到的消息
  环形缓冲（最近 1000 条）供 CLI 拉取

**REST API v1**（全部 JSON；`Authorization: Bearer <api-token>`）：

| 端点 | 方法 | 说明 |
|------|------|------|
| `/v1/status` | GET | 连接状态/uid/用户名/队列深度 |
| `/v1/messages?chatId=&limit=&afterSeq=` | GET | 环形缓冲消息（agent 收到的） |
| `/v1/recv-wait?chatId=&timeout=` | GET | 长轮询等新消息（e2e 同步用） |
| `/v1/send-text` | POST | `{chatId, text}` |
| `/v1/send-rich` | POST | `{chatId, markdown}`（富文本） |
| `/v1/send-file` | POST | `{chatId, path}`（agent 侧本地文件，大文件后台线程） |
| `/v1/upload` | POST | `{path}` → url（不发送） |
| `/v1/history` | POST | `{chatId, fromSeq, limit}` |
| `/v1/revoke` | POST | `{chatId, serverSeq}` |
| `/v1/forward` | POST | `{srcChatId, srcSeq, targetChatId}` |
| `/v1/mark-read` | POST | `{chatId, readSeq}` |
| `/v1/conversations` | GET | 会话列表 |
| `/v1/friends` | GET | 好友列表 |
| `/v1/friend-apply` | POST | `{targetUid, remark?}` |
| `/v1/friend-accept` | POST | `{token}` |
| `/v1/friend-pending` | GET | 待处理申请 |
| `/v1/users-search` | POST | `{keyword}` |
| `/v1/group-create` | POST | `{name, memberUids}` |
| `/v1/group-members` | POST | `{chatId}` |
| `/v1/group-invite` | POST | `{chatId, uids}` |
| `/v1/chat-personal` | POST | `{targetUid}` → chatId |
| `/v1/selftest` | POST | 双 bot 自检（注册临时号互发） |

## 二期：tt-cli 命令行（覆盖收发全功能）

```
tt <command> [args] [--json] [--config ~/.tt-cli]

连接/状态:   status | whoami
消息:        send <chatId> <text...>       send-file <chatId> <path>
             send-rich <chatId> <md...>    history <chatId> [--after seq] [--limit n]
             recv [--chatId] [--wait s]    revoke <chatId> <seq>
             forward <srcChatId> <seq> <targetChatId>  mark-read <chatId> [seq]
会话/联系人:  conversations                friends
             user-search <keyword>         friend-add <uid> [remark]
             friend-accept [token]         friend-pending
群组:        group-create <name> <uid...>  group-members <chatId>
             group-invite <chatId> <uid...>
聊天:        chat-with <uid> → chatId（个人会话解析）
文件:        upload <path> → url
测试:        selftest
```

- 输出：默认人类可读表格；`--json` 机器可读（e2e 断言用）
- e2e 模式：`--wait` 长轮询（映射 recv-wait），替代当前 TestPeer 每用例重连

## 三期：systemd / 服务化

- Linux：`tt-agent.service`（Type=simple, Restart=on-failure, WatchdogSec）+
  `tt-agent install` 子命令自动写 unit 文件
- Windows Services：后续（winsw 包装或 jpackage + service wrapper）
- macOS：launchd plist（低优先）

## 四期：MCP server

`tt-mcp`（stdio JSON-RPC）：把 agent REST 映射为 MCP tools（send_text / recv /
history / conversations / search_users...），大模型直接作为 IM 用户参与协作。
工具描述面向 LLM（含 chatId 获取指引：先 user-search → chat-with）。

## e2e 测试迁移

现 TestPeer（server test 内嵌 ImClient 每用例重连）→ 改为调 tt-cli：
- CI 起 `tt-agent`（peer 账号）→ 测试代码 `tt send/recv --json` 断言
- 好处：测试路径=产品路径（CLI 的 bug 测试期暴露）；无每用例 TCP 连接开销
- 迁移渐进：TestPeer 保留为 fallback（agent 未起时直连），新用例全走 CLI

## 实施顺序

一期（agent+REST）→ 二期（cli）→ e2e 迁移验证 → 三期（systemd）→ 四期（MCP）。
每期独立可用、独立提交。
