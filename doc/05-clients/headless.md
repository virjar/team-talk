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
`dataDir/deployments/<fingerprint>/datasets/<datasetId>/users/<uid>/`，因此进程重启会从同一
TCP+HTTP 部署、同一服务端 dataset、同一账号已提交的 cursor 继续。登录/注册不再提供隐式
owner；短生命周期测试也必须显式注入
`ImBotCacheOwner { _, _, _ -> FakeLocalCache() }`，避免测试便利路径被常驻机器人误用。
显式密码登录/注册只执行一次 TCP 传输尝试；连接失败或 AUTH_RESP 前断线会立即返回类型化 transport
失败并释放口令，调用方决定何时重新提交。ImBot 在创建 transport 之前使用共享 `AuthRules`
复验账号与安装级 deviceId，ImClient 在保留 exact attempt 前再次复验。只有已经持久化的 refresh owner
才在后台退避重连。

连接复用同一前置版本协商：`NEGOTIATE/NEGOTIATE_RESP` 确认双方协议 major/minor 窗口后才发送
AUTH。客户端低于最低 minor、major 不兼容或窗口无交集时返回类型化的
`PROTOCOL_VERSION_UNSUPPORTED` 并停止该连接的重试，不先拿密码尝试登录。兼容的较旧 minor
仍可认证；`ImBot.protocolCompatibility` 透传与 `ImClient`/`ClientSession` 相同的只读结果。
`AuthenticationFailure.requiresClientUpgrade` 区分服务器淘汰旧客户端与服务器自身过旧；后者在管理员
升级服务器后可用同一客户端显式发起新连接，不能被调用方持久记成必须更换客户端。
无头入口没有图形横幅或升级弹窗，CLI/MCP 也不拥有第二套兼容判断。

SDK 协商与 MCP `initialize.serverInfo.version` 使用生成的 `TeamTalkBuild.RELEASE_VERSION`，
与服务端和图形客户端保持同一发行字符串。
发行字符串只用于显示和诊断，版本判断始终使用独立数字协议 ID；不能用字符串排序或发行 patch
替代协商。当前项目只提供同 major 的短期有条件兼容，尚未承诺正式发布后的长期兼容。

主要能力：

- 注册、登录、等待连接状态和级联关闭；
- 订阅消息、联系人、群变更、presence 和 typing 流；
- 发送 Markdown、文件、图片、语音、视频和交互卡片；
- 上传文件、等待 ACK、拉历史、撤回、转发和已读；
- 用户搜索、好友申请/接受/删除；
- 创建私聊、创建群、邀请成员和读取成员。

`ClientSession` 同时持有群文件 Repository，其创建目录、发布文件、追加版本、重命名和删除的可恢复入口
都先把完整命令写入 deployment + dataset + uid 隔离的 SQLite outbox。创建共享
`chat + parent + 规范名称` 意图槽，追加版本/重命名/删除共享 `chat + entry` mutation 槽；队列上限为
256 条、单条 24 KiB、合计 3 MiB，超限不驱逐旧事实。前台与恢复 worker 由 Repository single-flight，
transport/超时/408/429/5xx 保留原命令并返回 `PENDING`；确定业务 4xx 清除本 generation，401/403/codec 保留但报错。
会话向上发布后台终结的 `ACKNOWLEDGED` / `REJECTED` completion，图形客户端用它驱动匹配页面和已打开路径收敛。
当前公开的 ImBot、tt-agent
REST 和 MCP 还没有群文件操作入口，因此上述 SDK 事实不能被描述为已交付的 headless 产品功能；后续暴露时必须
调用可恢复 Repository 入口，不得绕过它直接发 RPC。

需要当前好友在线状态时使用 `friendPresenceByUid`，它在认证后由完整好友快照与带 epoch/revision 的
PRESENCE 增量收敛，断线立即变为空 map；原始 `presenceEvents` 只是瞬时提示，不能自行持久化后当作
当前事实。发送输入状态优先使用 `trySendTyping(chatId)` 的 Boolean 准入结果；false 表示精确会话
transport 未接纳该可丢信号，不应进入重试队列。`sendTyping` 只为需要严格失败反馈的调用方保留。

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
这里的 backlog 可靠性以“事件仍在服务端保留窗内，或已经进入本地 inbox”为边界，
不表示服务端保留无限事件队列。

`nextMessageDelivery` 是显式 peek/ack：业务成功后才调用 `ackMessage`，未 ack 的 delivery 重启会
再次出现。`nextMessage` 是兼顾简单脚本的 at-most-once 便利 API，会在返回前自动 ack，不适合直接
驱动不可重入的外部副作用。ack 先在 SQLite 中更新 `acked`；未 ack 行无论数量和大小都不会被历史
回收删除。正常单消费者按 eventId 顺序确认时，已 ack 的诊断历史收敛为最新、同时满足 1024 条和
32 MiB 编码 payload 的前缀；每次 ack 与回收在同一事务中完成。回收高水位作为持久 replay tombstone，
因此被回收的旧 eventId 重放不会再次变成业务 delivery。

回收高水位绝不跨过更早的 pending 行。这样一个 Long cursor 始终只表示连续的保留窗口，不会把
回收洞误报成空页。旧版本遗留或调用方伪造的乱序 ack 会由最早 pending 暂时钉住回收，ACK 历史此时
可以临时超过目标预算；该 pending 按正常顺序确认后，同一事务会继续收敛。当前模型有意不为强行满足
诊断历史配额而删除可靠 pending。retained floor 单独保存在 `bot_inbox_metadata`，与服务端投影的
`sync_state(datasetId, cursor)` 没有复用关系；重启会同时恢复两类事实，而同一 dataset 的
checkpoint 替换保留 inbox 和 floor，只重建 current User/Contact/Chat/Conversation 等紧凑服务器投影。

tt-agent 的 REST `/messages` 与 `/recv-wait` 从同一 delivery log 按全局 eventId 查询。`afterEventId=0`
明确表示从**当前保留窗口**开始；落后于高水位的正数 cursor 会抛出包含 retained floor 的类型化过期
错误；REST 将该边界映射为 HTTP 410 和固定错误
`delivery history cursor expired; restart with afterEventId=0`，调用方必须用 0 重新建立当前窗口基线，
不能把它误解为一页没有数据。无 cursor 的等待以“当前行高水位与回收高水位的较大值”为 baseline，
只观察后续 delivery。完整、长期的消息历史仍由服务端 message history RPC 提供。
`bot.messages` 只是不对 cursor 施加背压的实时广播，不应用作可靠 backlog 队列。

等待 `AUTHENTICATED` 使用 15 秒“无同步进展”窗口，而不是 15 秒总时长；
`SYNCHRONIZING` 中持久 cursor 的新高水位或已完整校验的 checkpoint 页会续期。连接状态来回变化、
cursor 回落或失败页不算进展，不能无限重置 watchdog。

磁盘 inbox 提供 at-least-once delivery，不承诺外部副作用 exactly-once；调用方仍应以
delivery `eventId` 或包含操作类型的业务幂等键去重。只有明确希望把创建、编辑、撤回合并成
“每条消息最多一次副作用”时，才应主动以 `(chatId, serverSeq)` 合并。
长离线越过服务端事件保留期后，checkpoint 只恢复当前权威投影，历史消息可由 history RPC 读取；
已压缩的历史 delivery、编辑和撤回回调不会补发到 inbox。

MESSAGE_RECV 会发给包含发送者在内的成员，因此 echo bot 必须过滤自己的消息。`shutdown()` 是 ImBot 的生命周期终点，并级联关闭 session 和连接资源。
ImBot 是可多实例 SDK；创建时把 `fileServerUrl` 固定到自身认证会话，文件上传逐次读取该
`UserSession` 的原子凭据快照。同 uid 重连后的 access 轮换自动生效，uid 变化则失败关闭；
`shutdown()` 同时关闭文件 Repository 和活跃 HTTP 连接，不存在进程全局登录 token。当前 access
bearer 被 HTTP 401 明确拒绝时，ImBot 会在与 AUTH 结果安装相同的门禁中再次比较该 exact bearer：
轮换前请求的迟到 401 不影响新 access；真正命中当前 bearer 或后续 refresh 收到终态拒绝时，会先以
`AUTH_REVOKED` 完整关闭会话资源，再发布不含 bearer 的类型化认证终态。tt-agent 等待这个终态，停止
本地 REST 后以 78 退出并保留 ACTIVE 记录，供管理员执行 `--reauth`。

## 3. 构建与启动 agent

```bash
./gradlew :client:shared:headlessDist

read -r TK_USER
read -rs TK_PASS
export TK_USER TK_PASS
client/shared/build/headless/bin/tt-agent \
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

`credentials.properties` 固定为 `0600`，同时保存 canonical TCP+HTTP deployment 指纹、本地 API
token、稳定 `deviceId` 和认证状态。
只有 `REGISTER_PENDING` 会临时保存 exact username/password；首次 AUTH 成功时会在进入历史同步和
ready 前原子替换为 `ACTIVE` 的 uid/username/refresh token，并删除 password。后续进程启动只走
refresh 认证。服务端会回传同一设备级稳定 refresh 并轮换 access；认证结果仍先通过 owner/CAS 持久化
门，再允许连接进入同步，同值 refresh 可以不重复写盘。因此同一 dataDir 不会不断新增设备，也不会
长期保存登录密码。实际发生凭据更新时使用同目录临时文件、文件 fsync、
原子替换和目录 fsync，并拒绝 symlink、hard link、错误 owner 或过宽权限。非 POSIX 文件系统会
直接失败，不以宽松权限继续运行。旧 plaintext ACTIVE 格式不再隐式兼容，预发布环境应重新
bootstrap 专用 dataDir。

deployment 指纹缺失或与本次 `--host`/`--port`/`--server-url` 元组不一致时，旧 ACTIVE refresh 或
REGISTER_PENDING 密码会在任何 IM 网络连接之前原子失效；同 uid 的新部署也会使用独立缓存目录。
HTTP 与 TCP 可以部署在不同域名，但两者必须作为同一个不可拆分元组保存和校验。

### 3.1 systemd 安装

systemd 服务使用明确的非 root 账号。安装是三步闭环：先由 root 只准备专用
dataDir，再由服务账号以受控前台输入完成 ACTIVE bootstrap，最后才生成 unit。
unit 从不引用 EnvironmentFile，也不包含用户名、密码、refresh token 或注册标志。

先创建不可登录账号；它的 home 不应指向整个数据目录，然后显式准备数据目录：

```bash
sudo groupadd --system tt-agent
sudo useradd --system --gid tt-agent --home-dir /nonexistent --no-create-home \
  --shell /usr/sbin/nologin tt-agent
sudo cp -a client/shared/build/headless /opt/tt-agent
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

卸载同样只支持 Linux root，并按 `stop → disable → 删除 unit → daemon-reload` 的顺序失败即停；
unit 是符号链接或任一 systemd 命令失败时不会报告成功。unit 已不存在时不再执行 stop/disable，
但仍会执行一次 daemon-reload，因此也能安全重试“unit 已删除、manager 刷新失败”的部分完成状态：

```bash
sudo /opt/tt-agent/bin/tt-agent uninstall
```

身份查询和 systemd 操作都直接调用固定绝对路径，不经过 shell；身份查询限时 3 秒且只保留有界的
标准输出，systemd 操作限时 30 秒并在 OS pipe 边界丢弃输出。超时会先终止、再强制终止并有界等待
子进程回收。卸载只移除服务 unit，不删除 dataDir、ACTIVE refresh 或 delivery log。

agent 的 SQLite delivery log 保存 REST cursor 事实，单次最多返回 1000 条；这个上限在进入 SQLite
查询前执行。内存只为长轮询提供唤醒，不保存业务消息。未 ack backlog 以可靠交付优先，不会为了
磁盘配额静默丢弃；按序 ack 历史遵循前述 1024 条/32 MiB 收敛边界。

## 4. 本地 REST

所有端点（包括 `/v1/status`）都要求：

```http
Authorization: Bearer <agent-api-token>
Content-Type: application/json
```

响应统一为 `{ "ok": true, "data": {...} }` 或 `{ "ok": false, "error": "..." }`。
request body 最大 64 KiB；服务先检查 `Content-Length`，并在流式读取时再次计数。超限返回
413，错误响应不会回显本地文件路径。

REST handler 使用固定 16 个 worker 和 128 个等待槽。`recv-wait` 长轮询占满两者时由
HttpServer dispatcher 同步执行下一项，从接收端施加背压；不会像 cached thread pool 那样随并发请求
无限创建原生线程。关闭 runtime 会停止 listener、取消等待者并回收该执行器。

### 状态与接收

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/v1/status` | 连接状态、uid、username、缓冲数量 |
| GET | `/v1/messages?afterEventId=&chatId=&limit=` | 按全局 eventId 正序读取持久 delivery；每项含 eventId，响应含 nextEventId；游标过期返回 410 |
| GET | `/v1/recv-wait?afterEventId=&chatId=&timeout=` | 按全局 eventId 等一条 delivery；chatId 只过滤；游标过期返回 410 |
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

`history` 的 `fromSeq` 默认 `0`（最新一页），`limit` 默认 `10`，范围 `1..10`，与协议
`Message.MAX_QUERY_PAGE_SIZE` 一致；CLI、MCP 与 REST 共用这一预算，非法或越界 limit 返回 HTTP 400。
它与按全局事件游标读取本地投递记录的 `messages` 是两个入口，分页上限不能混用。

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
| POST | `/v1/group-create` | `{operationId, name, memberUids}`；operationId 为未知结果重试复用的规范 UUID，成员用逗号分隔 |
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
client/shared/build/headless/bin/tt status
client/shared/build/headless/bin/tt conversations
client/shared/build/headless/bin/tt user-search alice
client/shared/build/headless/bin/tt chat-with <uid>
client/shared/build/headless/bin/tt send <chatId> 'hello'
client/shared/build/headless/bin/tt send-rich <chatId> '**hello**'
client/shared/build/headless/bin/tt recv --chatId <chatId> --wait 10
client/shared/build/headless/bin/tt send-file <chatId> /var/lib/tt-agent/outgoing/report.pdf
client/shared/build/headless/bin/tt outgoing-status <chatId> <clientMsgId>
```

其他命令包括 `messages`、`history`、`upload`、`revoke`、`forward`、`mark-read`、`friends`、`friend-pending`、`friend-add`、`friend-accept`、`group-create`、`group-members` 和 `group-invite`。`--json` 输出机器可读 data。
发送命令可用 `--clientMsgId <id>` 显式复用自动化业务键；省略时 CLI 只生成一次，并在打开 HTTP 连接前把最终 ID
写到 stderr。即使 I/O 或响应 JSON 解析失败，调用方仍可用该 ID 查询/重试。
`group-create` 同样在连接前输出 `operationId`，并接受 `--operationId <UUID>`；建群结果未知时必须复用，
本地 REST 直接调用者也必须在请求体中先提供该 ID。

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
- `credentials.properties` 的 ACTIVE 状态只保存 deployment 指纹、uid、username、refresh token、API token 与稳定 deviceId；plaintext password 仅允许短暂存在于 REGISTER_PENDING，AUTH 成功即原子删除。目录 `0700`、文件 `0600`、NOFOLLOW 与原子落盘是强制条件。已有账号的 plaintext password 只经受控前台环境传递，systemd unit 不引用任何持久 bootstrap 环境文件。正式产品仍可进一步接入系统密钥库。
- CLI token 不应出现在命令历史、日志或代码仓库。
- CLI/MCP 只接受字面量本机回环 API 地址，不能把 Bearer token 发送到 DNS、局域网或公网端点；
  token 配置文件限制为 4 KiB，单次 agent HTTP 响应限制为 32 MiB，超限时要求调用方缩小分页。
- MCP stdio 的单条 JSON-RPC frame 限制为 64 KiB；超限行会被完整丢弃并返回固定协议错误，下一行
  仍可继续处理，不能用一个永不换行的输入让进程内存无界增长。
- Agent 对 query 长度、字段数、Authorization 长度和请求正文分别设独立上限；内部异常只返回固定
  `internal error`，不会把本地路径、payload 或底层异常消息回显给 bearer 调用方。
- `send-file` 和 `upload` 只能读取 agent dataDir 的 `outgoing` 子树，并共用同一 canonical/symlink/secret 校验链。
- `/messages` 的 `afterEventId` 与返回项 `eventId` 是跨 chat 的唯一分页游标；不同 chat 的相同
  `serverSeq` 不冲突。在 cursor 仍位于保留窗口时，重复同一 cursor 会得到同一页；落后于 ACK 历史
  回收高水位的正 cursor 返回 HTTP 410；调用方以 `afterEventId=0` 重建当前窗口基线。`0` 表示当前
  保留窗口起点，不能再使用已移除的全局 `afterSeq`。
- `recv-wait` 传 cursor 时先查持久日志、注册 waiter、再二次查询以封闭竞态；不传 cursor 时先快照
  当前 maxEventId，只等待下一条而不会无限重复“最新一条”。超时也返回本次有效 baseline 作为
  `nextEventId`，因此持久化与内存通知的边界竞态不会跳过事件。非法/负 cursor、越界 limit/timeout
  返回 400。
- agent 的长轮询不是 Webhook；内存唤醒在进程重启时会消失，但未 ack 消息仍在 SQLite delivery log
  中，重启后继续可靠交付。ACK 历史只用于有界诊断窗口；需要长期回读时使用服务端消息历史，需要
  外部副作用 exactly-once 时仍须使用业务幂等键。
- 权威 `CHAT_DELETED` 是授权撤销/隐私删除：即使 eventId 历史和终态 outgoing 回执通常持久，墓碑仍会清理该 chat
  的 pending/acked delivery、所有 outgoing 回执和本地消息；此后不能再通过 Agent cursor/status 查回。

本地数据生命周期由客户端自身的协议 major 与独立 schema 版本管理。`tt-agent` 在打开 dataDir 后
先取得安装目录租约，再执行 major 检查，随后才打开凭据与账号 SQLite。安装较高 major 会重置本安装
数据并要求重新认证；这包括本地 API token、refresh 凭据、待发消息与未消费投递等本地事实，不能
宣称跨 major 自动恢复。普通服务器版本拒绝不执行此重置。

同 major 的 minor 升级使用 schema 迁移，保留账号身份、草稿、outbox、未 ack inbox、回收水位与
同步游标。缓存仍按 deployment + dataset + uid 隔离，未知更新 schema 必须拒绝打开，不能删除后
假装恢复；正常连接与 checkpoint 重建也不得抹掉这些可靠事实。当前缓存文件命名、开发基线接管与
迁移入口见[LocalCache 生命周期](../03-architecture/client-and-sdk.md#6-localcache)，不要根据旧
`cache_e*` 示例手工删除当前账号数据库来完成 minor 升级。

无头能力的产品化计划见[路线图](../10-reference/roadmap.md)，当前成熟度见[功能状态](../10-reference/feature-status.md)。

## 8. 受控通知机器人

只需要由 CI、监控或审批系统主动向群发通知时，不应启动持有完整客户端身份的 ImBot。当前群成员可以从
群设置创建通知机器人，保存管理客户端本地安全生成的 `ttb_...` token，再把页面给出的入站通知 URL
交给外部系统调用。URL 已绑定目标群，调用正文只需要 Markdown，不需要让外部系统另外维护 chatId。

完整的群内工作流、HTTP 契约、curl/Python/GitHub Actions 示例、错误码和凭据安全边界见
[通知机器人接入](notification-bots.md)。通知机器人是单向、最小权限身份；ImBot/tt-agent 是可以
接收事件和执行双向业务的完整客户端，二者不能共享 token 或权限模型。
