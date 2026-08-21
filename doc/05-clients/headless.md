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
eventId 是重放幂等键；同一 `(chatId, serverSeq)` 的创建、编辑和撤回拥有不同 eventId，因此都会
形成独立业务 delivery。进程内只有 CONFLATED wake-up，因此大 backlog 不依赖消费者启动时序，
也不会形成内存队列。崩溃发生在 inbox INSERT 与 cursor 提交之间时，同一 eventId 的重放会被
主键吸收；cursor 已提交而业务尚未消费时，pending 磁盘行会在重启后继续交付。

`nextMessageDelivery` 是显式 peek/ack：业务成功后才调用 `ackMessage`，未 ack 的 delivery 重启会
再次出现。`nextMessage` 是兼顾简单脚本的 at-most-once 便利 API，会在返回前自动 ack，不适合直接
驱动不可重入的外部副作用。ack 在 SQLite 中只更新 `acked`，不会删除 delivery；因此 tt-agent 的
REST `/messages` 与 `/recv-wait` 从同一 delivery log 按全局 eventId 查询，进程崩溃或业务 ack 都
不会抹掉 REST 可见历史。
`bot.messages` 只是不对 cursor 施加背压的实时广播，不应用作可靠 backlog 队列。

等待 `AUTHENTICATED` 使用 15 秒“无同步进展”窗口，而不是 15 秒总时长；
`SYNCHRONIZING` cursor 每次持久推进都会续期，因此大历史回放只要持续前进就不会误超时。

磁盘 inbox 提供 at-least-once delivery，不承诺外部副作用 exactly-once；调用方仍应以
delivery `eventId` 或包含操作类型的业务幂等键去重。只有明确希望把创建、编辑、撤回合并成
“每条消息最多一次副作用”时，才应主动以 `(chatId, serverSeq)` 合并。

MESSAGE_RECV 会发给包含发送者在内的成员，因此 echo bot 必须过滤自己的消息。`shutdown()` 是 ImBot 的生命周期终点，并级联关闭 session 和连接资源。
ImBot 是可多实例 SDK；创建时把 `fileServerUrl` 固定到自身认证会话，文件上传逐次读取该
`UserSession` 的原子凭据快照。同 uid 重连后的 token 轮换自动生效，uid 变化则失败关闭；
`shutdown()` 同时关闭文件 Repository 和活跃 HTTP 连接，不存在进程全局登录 token。

## 3. 构建与启动 agent

```bash
./gradlew :shared:headlessDist

read -r TK_USER
read -rs TK_PASS
export TK_USER TK_PASS
shared/build/headless/bin/tt-agent \
  --host im.example.com \
  --port 5100 \
  --server-url https://im.example.com
unset TK_USER TK_PASS
```

上例用于前台 bootstrap，秘密不会进入命令参数或 shell history。常驻部署应在首次认证后使用
`dataDir/credentials.properties`，不要把登录参数长期留在进程命令行。

默认值：

| 参数/环境变量 | 默认 | 作用 |
|---|---|---|
| `--host` / `TK_HOST` | `im.virjar.com` | TCP 主机 |
| `--port` / `TK_PORT` | `5100` | TCP 端口 |
| `--server-url` / `TK_SERVER_URL` | `https://<host>` | 文件 HTTP 根地址 |
| `--api` | `127.0.0.1:8600` | 本地 REST 监听；只接受 `127.0.0.0/8`、`localhost` 或 `[::1]` |
| `--data-dir` / `TK_AGENT_DIR` | `~/.tt-agent` | 凭据与 API token 目录 |
| `--user` / `TK_USER` | 无 | 登录用户名 |
| `TK_PASS` | 无 | 登录密码；仅从受控前台环境输入提供，命令行 `--pass` 被拒绝 |
| `--register --prefix <name>` | 关闭 | 注册随机后缀账户 |
| `--reauth` | 关闭 | ACTIVE refresh 撤销/过期后，仅用 stored username + `TK_PASS` 一次性恢复 |

dataDir 是带 `.tt-agent-data` 标记的专用目录。仅当它不存在、直接父目录已存在且完整父链没有
symlink 时，agent 才以 POSIX `0700` 创建这一个叶目录；既存目录必须已有正确标记、owner 和
`0700`，否则 fail-fast，绝不把任意目录自动 chmod 后接管。`/etc`、`/var`、`/var/lib`、`/opt`、
`/usr`、`/root`、`/home`、`/Users`、文件系统根和整个 user home 都不能直接作为 dataDir。
任何 OS temporary root（包括当前 `java.io.tmpdir`）的子树也不允许；完整父链必须是普通目录、
没有 symlink，且不能对 group/others 可写，避免攻击者在校验后 rename/swap 数据目录。
预发布旧 dataDir 若没有标记，应显式删除后重新 bootstrap，不能由新进程隐式迁移。

`credentials.properties` 固定为 `0600`，同时保存本地 API token、稳定 `deviceId` 和认证状态。
只有 `REGISTER_PENDING` 会临时保存 exact username/password；首次 AUTH 成功时会在进入历史同步和
ready 前原子替换为 `ACTIVE` 的 uid/username/refresh token，并删除 password。后续进程启动只走
refresh 认证，每次服务端轮换 refresh token 也先完成同一持久化门，再允许连接进入同步。因此同一
dataDir 不会不断新增设备，也不会长期保存登录密码。凭据更新使用同目录临时文件、文件 fsync、
原子替换和目录 fsync，并拒绝 symlink、hard link、错误 owner 或过宽权限。非 POSIX 文件系统会
直接失败，不以宽松权限继续运行。旧 plaintext ACTIVE 格式不再隐式兼容，预发布环境应重新
bootstrap 专用 dataDir。

### 3.1 systemd 安装

systemd 服务使用明确的非 root 账号。安装是三步闭环：先由 root 只准备专用
dataDir，再由服务账号以受控前台输入完成 ACTIVE bootstrap，最后才生成 unit。
unit 从不引用 EnvironmentFile，也不包含用户名、密码、refresh token 或注册标志。

先创建不可登录账号；它的 home 不应指向整个数据目录，然后显式准备数据目录：

```bash
sudo groupadd --system tt-agent
sudo useradd --system --gid tt-agent --home-dir /nonexistent --no-create-home \
  --shell /usr/sbin/nologin tt-agent
sudo cp -a shared/build/headless /opt/tt-agent
sudo /opt/tt-agent/bin/tt-agent prepare-service-data \
  --service-user tt-agent \
  --data-dir /var/lib/tt-agent
```

`prepare-service-data` 只解析真实 UID/GID，并创建带标记、owner 和 `0700` 的单一叶目录；
它不写 unit，也不接受任何认证参数。对已有账号，从终端读入一次性密码后以
服务账号前台启动；值只经环境传递，不进入 argv 或文件：

```bash
read -r TK_USER
read -rs TK_PASS
export TK_USER TK_PASS
sudo --preserve-env=TK_USER,TK_PASS -u tt-agent \
  /opt/tt-agent/bin/tt-agent \
  --host im.example.com \
  --server-url https://im.example.com \
  --data-dir /var/lib/tt-agent
unset TK_USER TK_PASS
```

看到 `ready` 后停止该前台进程。此时文件已是不含 password 的 `ACTIVE`。需要新注册账号时，
在同一个已准备 dataDir 上执行下列前台命令；username、随机密码、稳定 deviceId 和
`REGISTER_PENDING` 会在联网前原子落盘。进程若在服务端提交后崩溃，重启会先以同一身份
login，失败才用同一 exact username 注册，不会制造第二个账号：

```bash
sudo -u tt-agent /opt/tt-agent/bin/tt-agent \
  --register --prefix agent \
  --host im.example.com \
  --server-url https://im.example.com \
  --data-dir /var/lib/tt-agent
```

只有 ACTIVE dataDir 才能进入最后安装；未执行准备步骤时，`install` 不会顺带创建目录：

```bash
sudo /opt/tt-agent/bin/tt-agent install \
  --host im.example.com \
  --port 5100 \
  --server-url https://im.example.com \
  --service-user tt-agent \
  --data-dir /var/lib/tt-agent
sudo systemctl daemon-reload
sudo systemctl enable --now tt-agent
```

`install` 明确拒绝 `--pass`、`--user`、`--register`、`--reauth` 和本地 API 凭据。
若管理员在服务端 revoke 了 device 或 refresh 过期，先停服务，再以一次性 `--reauth`
恢复同一账号。它只使用 ACTIVE 中的 username 和稳定 deviceId，仅从 `TK_PASS` 取密码；
成功后替换 refresh 并立即退出；AUTH 拒绝或本地原子提交失败都不覆盖旧 ACTIVE：

```bash
sudo systemctl stop tt-agent
read -rs TK_PASS
export TK_PASS
sudo --preserve-env=TK_PASS -u tt-agent \
  /opt/tt-agent/bin/tt-agent --reauth --data-dir /var/lib/tt-agent
unset TK_PASS
sudo systemctl start tt-agent
```

生成的 unit 使用 `User=tt-agent`、`UMask=0077`、默认 dataDir 对应的 `StateDirectoryMode=0700`、
`NoNewPrivileges=true`、`ProtectSystem=strict`、`ProtectHome=true`、`PrivateTmp=true` 和只指向 dataDir 的
`ReadWritePaths`。若通过 `--service-user` 改名，必须先创建同名 primary group 的非 root 系统账号；
准备命令会先解析实际 UID/GID（UID/GID 0 的别名同样拒绝），再创建或校验自定义 dataDir 的标记、
owner 与 `0700`；最终安装再次校验 owner/mode 和 ACTIVE refresh 后才写 unit。自定义 dataDir 不会额外声明或改动默认
`/var/lib/tt-agent`。

unit 同时设置 `RestartPreventExitStatus=78`：网络暂态仍按 `Restart=on-failure` 恢复，但 ACTIVE
refresh 被服务端明确拒绝时 agent 以 78 退出并停止重启，避免每 5 秒形成认证风暴；管理员按上面的
one-shot `--reauth` 步骤恢复后再启动服务。maintenance/connection-limit 等可恢复拒绝仍允许重启，
但 unit 的 5 次/300 秒启动限流会阻止持续高频重试。

agent 的 SQLite delivery log 保存 REST cursor 事实，单次最多返回 1000 条；内存只为长轮询提供
唤醒，不保存业务消息。完整、可分页的服务端聊天历史仍通过 message RPC 查询。

## 4. 本地 REST

所有端点（包括 `/v1/status`）都要求：

```http
Authorization: Bearer <agent-api-token>
Content-Type: application/json
```

响应统一为 `{ "ok": true, "data": {...} }` 或 `{ "ok": false, "error": "..." }`。
request body 最大 64 KiB；服务先检查 `Content-Length`，并在流式读取时再次计数。超限返回
413，错误响应不会回显本地文件路径。

### 状态与接收

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/v1/status` | 连接状态、uid、username、缓冲数量 |
| GET | `/v1/messages?afterEventId=&chatId=&limit=` | 按全局 eventId 正序读取持久 delivery；每项含 eventId，响应含 nextEventId |
| GET | `/v1/recv-wait?afterEventId=&chatId=&timeout=` | 按全局 eventId 等一条 delivery；chatId 只过滤 |
| GET/POST | `/v1/conversations` | 会话列表 |
| GET/POST | `/v1/friends` | 好友列表 |
| GET/POST | `/v1/friend-pending` | 待处理好友申请 |

### 消息与文件

| 方法 | 路径 | body |
|---|---|---|
| POST | `/v1/send-text` | `{chatId, clientMsgId, text}` |
| POST | `/v1/send-rich` | `{chatId, clientMsgId, markdown}` |
| POST | `/v1/send-file` | `{chatId, clientMsgId, path}`，path 必须位于 `dataDir/outgoing` |
| GET | `/v1/outgoing?chatId=&clientMsgId=` | 查询持久发送回执；未知 key 返回 404 |
| POST | `/v1/upload` | `{path}`，与 send-file 使用同一文件访问策略 |
| POST | `/v1/history` | `{chatId, fromSeq, limit}` |
| POST | `/v1/revoke` | `{chatId, serverSeq}` |
| POST | `/v1/forward` | `{srcChatId, srcSeq, targetChatId}` |
| POST | `/v1/mark-read` | `{chatId, readSeq}` |

三个发送端点都要求调用方生成稳定 `clientMsgId`，并在超时或 HTTP 响应丢失后原样复用。请求先把
最终规范 wire payload 提交到账号 SQLite，随即返回 `queued/sending/failed/sent` 回执，HTTP 生命周期不等待 ACK。
同 `(chatId, clientMsgId)` 且相同请求返回已有回执；不同不可变 payload 返回 409，绝不产生第二条消息。
`sent` 回执有界保留；超出回执窗口后若同 key 的权威消息仍在本地，重用会 fail closed 而不覆盖正文。

`send-file` 以流式 source 通过 TeamTalk HTTP 端点上传，再用服务端返回的 Attachment 持久入队，
不把本地附件整体读入内存。同 key 在固定条带锁内先查回执、校验文件快照指纹；已有回执不二次上传。
如进程恰在远端上传成功、SQLite 入队之前崩溃，允许留下一个可清理的孤儿文件；消息尚未发送，不会重复消息。

文件端点只接受 `dataDir/outgoing` 下可 canonicalize 的普通文件；相对路径以该目录为根。包含
`..` 的路径、任何符号链接路径、目录、hard link、错误 owner、group/world-writable 文件、dataDir 私密文件
以及 root/home 等宽泛路径都会被拒绝。策略以 `NOFOLLOW` 打开一次原文件，复制到 dataDir 内
`0600` 的唯一 staging 文件并原子安装；上传重试只重开私有 snapshot，原路径随后被替换也不会改变
上传内容。两个端点都会 best-effort 删除 staging；清理失败会记录不含路径的诊断并保留文件供受控
清理，但绝不把已经确定的远端成功改成 500、诱发重复发送。调用方应先以最小权限把待上传文件复制
到 outgoing，上传完成后自行清理原文件。
Agent 每次启动会在单 owner 边界内清理 `.staging` 下只匹配自身 `.upload-*.partial[.ready]` 命名的
崩溃遗留；不递归、不跟随符号链接，不删除其他名称。非法或缺失 `send-file` path 固定返回 400，
错误体不回显调用方本地路径。

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

本地 API token 生成后只写入 `credentials.properties`，ready 行和 journal 都不会打印它。可由有权
读取 agent dataDir 的管理员直接写入调用用户的 `~/.tt-cli`，再将文件收紧为 `0600`；也可通过
`TT_TOKEN` / `--token` 提供（后者可能进入 shell history，不建议用于常驻环境）：

```bash
umask 077
token_file="$(mktemp "$HOME/.tt-cli.XXXXXX")"
trap 'rm -f "$token_file"' EXIT
sudo -u tt-agent sed -n 's/^apiToken=//p' /var/lib/tt-agent/credentials.properties > "$token_file"
test -s "$token_file"
chmod 0600 "$token_file"
mv -f "$token_file" "$HOME/.tt-cli"
trap - EXIT
```

```bash
shared/build/headless/bin/tt status
shared/build/headless/bin/tt conversations
shared/build/headless/bin/tt user-search alice
shared/build/headless/bin/tt chat-with <uid>
shared/build/headless/bin/tt send <chatId> 'hello'
shared/build/headless/bin/tt send-rich <chatId> '**hello**'
shared/build/headless/bin/tt recv --chatId <chatId> --wait 10
shared/build/headless/bin/tt send-file <chatId> /var/lib/tt-agent/outgoing/report.pdf
shared/build/headless/bin/tt outgoing-status <chatId> <clientMsgId>
```

其他命令包括 `messages`、`history`、`upload`、`revoke`、`forward`、`mark-read`、`friends`、`friend-pending`、`friend-add`、`friend-accept`、`group-create`、`group-members` 和 `group-invite`。`--json` 输出机器可读 data。
发送命令可用 `--clientMsgId <id>` 显式复用自动化业务键；省略时 CLI 只生成一次，并在打开 HTTP 连接前把最终 ID
写到 stderr。即使 I/O 或响应 JSON 解析失败，调用方仍可用该 ID 查询/重试。

## 6. MCP

`tt-mcp` 使用 stdio JSON-RPC，内部仍调用 agent REST，不持有第二条 IM 连接。当前工具集：

```text
status, conversations, friends,
send_text, send_markdown, send_file, outgoing_status,
recv, messages, history,
search_users, chat_with,
mark_read, revoke
```

MCP 是适配层，不是新的权限边界。模型能做什么取决于 agent 账户在 TeamTalk 中的权限；生产化前还需要管理员授权、审计、速率限制和会话白名单。
MCP 的三个发送工具都把 `clientMsgId` 声明为必填，调用方负责在工具重试时复用。

## 7. 安全与运行边界

- REST 强制绑定 loopback；通配地址、局域网/公网地址和需要 DNS 解析的主机名都会在启动前被拒绝。当前 HTTP 服务没有 TLS、来源限制或细粒度授权。
- 所有 REST 端点都校验同一个 agent API token，但 token 永远不进入 ready/journal。读取 token 是一次显式的本机特权操作。
- `credentials.properties` 的 ACTIVE 状态只保存 uid、username、refresh token、API token 与稳定 deviceId；plaintext password 仅允许短暂存在于 REGISTER_PENDING，AUTH 成功即原子删除。目录 `0700`、文件 `0600`、NOFOLLOW 与原子落盘是强制条件。已有账号的 plaintext password 只经受控前台环境传递，systemd unit 不引用任何持久 bootstrap 环境文件。正式产品仍可进一步接入系统密钥库。
- CLI token 不应出现在命令历史、日志或代码仓库。
- `send-file` 和 `upload` 只能读取 agent dataDir 的 `outgoing` 子树，并共用同一 canonical/symlink/secret 校验链。
- `/messages` 的 `afterEventId` 与返回项 `eventId` 是跨 chat 的唯一分页游标；不同 chat 的相同
  `serverSeq` 不冲突。重复同一 cursor 会得到同一页，不能再使用已移除的全局 `afterSeq`。
- `recv-wait` 传 cursor 时先查持久日志、注册 waiter、再二次查询以封闭竞态；不传 cursor 时先快照
  当前 maxEventId，只等待下一条而不会无限重复“最新一条”。超时也返回本次有效 baseline 作为
  `nextEventId`，因此持久化与内存通知的边界竞态不会跳过事件。非法/负 cursor、越界 limit/timeout
  返回 400。
- agent 的长轮询不是 Webhook；内存唤醒在进程重启时会消失，但消息仍在 SQLite delivery log 中，
  重连后可通过 `/v1/messages` 读取。需要外部副作用 exactly-once 时仍须使用业务幂等键。
- 权威 `CHAT_DELETED` 是授权撤销/隐私删除：即使 eventId 历史和 SUCCESS 回执通常持久，墓碑仍会清理该 chat
  的 pending/acked delivery、所有 outgoing 回执和本地消息；此后不能再通过 Agent cursor/status 查回。

本地缓存当前从 epoch 2 切到 epoch 3：`cache_e2*.db` 保留但不读取，epoch 3 登录后重同步服务器投影；
旧草稿和未上服本地状态不迁移。新的 outgoing outbox 与 acked delivery history 从 epoch 3 开始生效。
每次打开会幂等补齐当前 schema 在首次建库崩溃时缺失的 DDL。但早期未发布 epoch 3 中间构建不属于兼容范围；
曾运行过这类构建的开发实例必须删除对应 `cache_e3*.db` 再重同步。

无头能力的产品化计划见[路线图](../10-reference/roadmap.md)，当前成熟度见[功能状态](../10-reference/feature-status.md)。

## 8. 受控通知机器人

只需要由 CI、监控或审批系统主动向群发通知时，不应启动持有完整客户端身份的 ImBot。当前群成员可以从
群设置创建通知机器人，保存 TeamTalk 一次性签发的 `ttb_...` token，再把页面给出的入站通知 URL
交给外部系统调用。URL 已绑定目标群，调用正文只需要 Markdown，不需要让外部系统另外维护 chatId。

完整的群内工作流、HTTP 契约、curl/Python/GitHub Actions 示例、错误码和凭据安全边界见
[通知机器人接入](notification-bots.md)。通知机器人是单向、最小权限身份；ImBot/tt-agent 是可以
接收事件和执行双向业务的完整客户端，二者不能共享 token 或权限模型。
