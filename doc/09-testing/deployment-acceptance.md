# 真实部署验收

TeamTalk 的主业务验收连接当前选中部署配置函数的目标。它验证客户端 SDK、TCP 服务、HTTP 文件端点、
数据库和搜索索引共同形成的系统行为。私有验收在独立 clone 中使用 Git 忽略的
`buildSrc/deployment-local/Deployment.kt`，主仓库默认 `buildSrc/deployment/Deployment.kt` 保持公版坐标。

## 前置条件

1. 当前选中的部署配置函数已填写目标服务器参数；传输组合与证书要求见
   [传输配置边界](../07-operations/configuration.md#传输配置边界)。
2. 配置的 HTTP(S) 健康检查可达，`tcpAddress` 端口与运行时 `TCP_PORT` 一致，公网 IM TCP 可完成 TLS handshake。
3. 如果服务端代码、数据库结构或静态资源有变化，先部署新版本。
4. 测试环境允许创建带独立前缀的临时账户和业务数据。
5. 组织资产归属验收还需要运行机上的 owner-only `gradle/deployment.secrets`，或同时设置
   `TK_E2E_ADMIN_USER` / `TK_E2E_ADMIN_PASSWORD`；`acceptanceTest` 是显式的完整验收入口，缺少管理 fixture
   会带配置提示明确失败，不允许跳过组织治理用例后产生假通过。
   管理 API 与文件请求使用同一 `serverUrl`，支持配置的 HTTP 或 HTTPS；TCP TLS 验证独立，管理请求不跟随重定向。

```bash
./gradlew deployServer
./gradlew :server:server:acceptanceTest
```

小范围开发者内测可先执行 `./gradlew :server:server:previewSmokeTest`。该任务从同一测试类选择
`preview-smoke` 场景，不依赖管理 fixture，也不停止或重启服务；具体范围见
[预览版指南](../01-getting-started/developer-preview.md#轻量业务验收)。

`acceptanceTest` 是完整业务验收入口，只运行 `RemoteAcceptanceTest`，并由构建配置注入远程端点。不要通过手工拼接测试系统属性建立另一套隐含入口。
需要供脚本查看实际非敏感配置时，运行 `./gradlew writeDeploymentConfig`，读取
`build/deployment/deployment-config.json`；不要直接解析 Kotlin 或把旧快照当作当前目标。

## 测试数据归属与真人内测实例

`RemoteAcceptanceSupport.registerUser()` 使用 `e2e-<场景>-<8 位随机后缀>` 注册账号。
`Session.close()` 只释放客户端连接和线程，`previewSmokeTest` 与 `acceptanceTest` 均不会自动删除
这些账号及其业务数据。因此，真人持续使用的私有内测实例不把远程 E2E 当作每次部署的默认步骤；
日常核对 `/health`、构建身份、下载制品并由参与用户复验，自动业务场景使用独立可清理测试实例。

在真人实例处理既有测试账号时，前缀只用于生成候选清单。先核对精确 UID、组织成员资格以及与真人
混合的好友、申请、历史群成员和会话，保存处理前后的清单，保留非目标账号。现有管理入口
`POST /api/admin/users/{uid}/ban` 会事务撤销凭据并使在线会话失效；停用账号不再进入普通用户搜索，
但管理台与关联历史仍保留。不要将停用记成物理删除，也不能只删除 PostgreSQL 用户行而留下跨库引用。

账号物理删除目前没有完整维护入口，涉及 PostgreSQL 关系、RocksDB 消息与附件引用、Lucene 投影。
清理后只做只读检查，不立即重跑会创建账号的远程测试。误选账号需通过原管理入口解封并重新登录，
不要恢复旧凭据以绕过吊销。

## 客户端安装站点的 HTTP 验收

Windows 的引导器和自动更新服务依赖静态下载契约。对实际部署的 `.appinstaller` 和它引用的 MSIX
执行以下检查，不能只凭文件存在、浏览器下载或服务端 `/health` 为 UP 就宣布可安装：

| 请求 | 预期 |
|---|---|
| GET / HEAD | 200，`Content-Length` 等于真实文件大小，HEAD 没有正文；声明 `Accept-Ranges: bytes` |
| `.appinstaller` / `.msix` 类型 | 分别为 `application/appinstaller` / `application/msix` |
| `Range: bytes=0-15` | 206，`Content-Length: 16`，`Content-Range: bytes 0-15/<总大小>`；正文与本地原文件前 16 字节相同 |
| `Range: bytes=-16` | 206，正文与原文件最后 16 字节相同 |
| 起始位置超过文件长度 | 416，不能回退为整包 200 |
| 不存在的安装包或元数据 | 404，不能返回下载首页作为兜底 |

以上应通过用户实际访问的 HTTP(S) 地址验证；如果有代理，验代理后的响应。现有发行包不因修复下载
服务而换字节、换签名或增加版本。之后在目标 Windows 用原引导器重试，核对安装、启动与原资料保留。
若仍失败，记录 Windows build、首次/覆盖安装及 AppInstaller 错误；HTTP 修复通过不替代系统侧安装结果。

下载路由回归入口为 `ClientDownloadRoutesTest`，它与业务附件的鉴权和 Range 链路分别验证。

## 多设备与精确服务重启

多设备收敛使用同一 uid 的两个不同 deviceId 连接和一个对端连接。单设备断线场景只关闭该客户端当前
transport 并暂停它自己的自动重连；宿主机网卡、Wi-Fi、代理、DNS 和防火墙不得作为离线夹具。断线期间
其余连接继续通过真实业务入口产生编辑、撤回、已读和 Conversation 变更，随后由原客户端实例恢复连接
并追平持久 cursor。

服务重启场景只通过部署配置指向的 SSH 目标执行固定的 `systemctl restart teamtalk`，并要求 unit 在操作
前后都是 active、InvocationID 与 MainPID 都发生变化。三个原客户端实例必须各自只新增一次成功认证，
重启前的 Message、Conversation 和历史继续存在；重启后继续发送的新消息必须沿原 chat 序号连续推进。
最终分别以各账号权威投影核对两个同 uid 设备和对端的当前本地缓存，不能用新建客户端或重新拉一份
临时结果替代原实例的恢复。

## 消息持久边界进程死亡

消息进程死亡夹具与普通服务重启分开。它用 clientMsgId 前缀选择三个物理窗口：`core02-rocks-` 在 RocksDB
权威消息批提交并确认 outbox pending 后、任何派生投影前；`core02-postgres-` 在 PostgreSQL 投影事务返回后、
outbox 删除前；`core02-outbox-` 在 outbox 删除返回后、MessageService 与网络 ACK 返回前。夹具先在部署目录
`data/acceptance/core02` 为精确 identity 写入一次性 arm；发布制品命中同一 chatId 与对应阶段时，原子发布
带 PID、systemd InvocationID 和阶段的 hit marker 并停止该命令。SSH 夹具复验 marker 对应当前
`teamtalk` MainPID/InvocationID 后，只执行 `systemctl kill --kill-who=main --signal=KILL teamtalk`，不能用
graceful restart 或宿主机断网冒充进程死亡。三个窗口复用同一 arm、marker、严格恢复和清理状态机。

每个窗口的首发等待都必须因 transport 中断结束，不能收到成功 ACK。systemd 拉起新进程后，原两个客户端
各只重新认证一次；在客户端做任何重试之前，权威历史、Lucene 搜索、Conversation 未读、本地消息与
durable 消息事件必须已经出现一次且只有一次。随后用完全相同的 `chatId + clientMsgId + body` 重试两次
都必须返回原 serverSeq；新 identity 使用紧邻的下一序号，最终 markRead 后未读归零。arm、claim、hit 和
临时文件在用例结束时按精确 identity 清理，下一窗口才能开始。

## 消息容量基线入口

消息提交的远端容量基线使用独立的 `capacityTest` source set 与显式任务，不属于普通 `server:test`，
也不混入完整业务 `acceptanceTest`：

```bash
./gradlew :server:server:capacityTest
```

默认场景建立 4 个独立发送用户、真实连接和一对一会话，共用一个接收端观察连接；每 lane 先发送 1 条
warmup，再执行 30 条、间隔 100 ms 的稳态提交，最后执行总计 80 条、并发度 16 的 burst。每条消息都
通过真实 SDK 等待 `MESSAGE_ACK`；报告记录 ACK p50/p95/p99、成功/失败分类和场景吞吐，并从接收端通知
和历史 RPC 按 `chatId + clientMsgId` 复验无丢失、无重复以及 ACK/通知/历史 serverSeq 精确收敛。每个
会话是新建空会话，因此完整 serverSeq 还必须从 1 开始连续。默认机器可读报告写入
`server/server/build/reports/capacity/message-capacity.json`。

同一接收账号还建立一个不同 deviceId 的滞后会话；负载开始前只暂停该客户端的自动重连，不改变宿主机
网络。warmup、steady、burst 和恢复探针共同形成离线积压，随后恢复该会话。默认要求
`targetCursor - baseCursor >= 128`，严格大于单个 `SYNC_BATCH` 的 64 事件上限；报告中的 replay 页数是按
积压量与该协议上限计算出的最少页数。门禁要求恢复只新增一次成功认证、cursor 至少追平负载高水位、
原始 replay 投影尝试按 `chatId + clientMsgId` 无丢失或重复且 serverSeq 与 ACK/历史一致，同时最终本地
Message 投影和每 lane Conversation 都与主接收会话收敛。报告另记录追平耗时和事件速率。

burst 必须实际观察到至少一次有界 `503` 拒绝，才算证明过载边界；被拒绝的原 `Message` 以及负载解除后
每 lane 的新消息探针，都在同一恢复 deadline 内按固定间隔重试原 identity。只有全部原消息与新探针
收敛、通知/历史无丢失或重复且三方 serverSeq 精确一致时门禁才通过。ACK 超时和 transport failure 虽会
用原对象重试以检查未知提交结果，但仍使本次容量门禁失败。p50/p95/p99 与吞吐只记录实测值，尚未定义
SLO 阈值，不能把一次报告误读为容量承诺。

参数只能通过该显式任务调整，且测试代码保留硬上限，避免把一次误配置变成长时间无界压力：

```bash
./gradlew :server:server:capacityTest \
  -PcapacitySenderLanes=4 \
  -PcapacityWarmupMessagesPerLane=3 \
  -PcapacitySteadyMessagesPerLane=60 \
  -PcapacitySteadyIntervalMs=100 \
  -PcapacityBurstMessagesTotal=160 \
  -PcapacityBurstConcurrency=32 \
  -PcapacityAckTimeoutMs=10000 \
  -PcapacityDeliveryTimeoutMs=30000 \
  -PcapacityRecoveryTimeoutMs=30000 \
  -PcapacityRecoveryRetryIntervalMs=250 \
  -PcapacityEventCatchupTimeoutMs=60000 \
  -PcapacityEventCatchupMinimumEvents=128 \
  -PcapacityReport=/absolute/path/message-capacity.json
```

若 burst 未触发 `503`，报告仍会保存且 `overloadObserved=false`，但任务按未证明过载恢复处理为失败；应在
测试代码声明的硬上限内调整总消息数和并发度，而不是把普通成功 burst 解释为过载证据。

这只是 REL-07 的多用户消息提交、过载恢复和有界多页事件追平基线，不是产品容量承诺。原始报告不固定进
文档；参考硬件、长时间 soak、大对象文件系统层、后台维护、慢 PostgreSQL、磁盘压力和正式 SLO 仍需后续阶段
共同形成完整容量门禁。

## 连接与认证容量基线入口

连接容量与消息容量使用两个独立任务，避免把不同负载形态和失败边界混入同一份报告：

```bash
./gradlew :server:server:connectionCapacityTest
```

默认先串行通过真实注册入口准备 64 个独立账号并关闭准备会话，避免夹具准备占满小型部署的 BCrypt/IO
并发槽；短暂等待连接释放后取服务端资源基线。随后每组 2 个真实 SDK 登录全部结束后等待 1 秒，再启动
下一组，直到 64 条连接全部达到 `AUTHENTICATED`。默认并发与当前小型部署的实际认证 worker 边界一致；
可调参数仍受默认登录源窗口与最大并发约束，避免把明显的夹具误配置误报成容量不足。连接
ready latency 的通过门槛是 p95 不超过 5 秒、p99 不超过 10 秒，比较使用原始纳秒值，毫秒舍入只用于报告。

全部连接保持 60 秒，默认每 5 秒同时记录客户端认证状态和服务端资源快照；保持期内不允许连接掉线或
认证次数变化。随后只对其中 16 个客户端调用 transport-scoped 断线/暂停钩子，再同时恢复；宿主机网卡、
Wi-Fi、代理、DNS 和防火墙始终不变。16 个目标必须各自精确新增一次认证，48 个对照客户端在整个窗口
必须持续认证且认证次数不变。重连 p95 不超过 15 秒，最慢一条不超过 30 秒；默认 16 个样本的
nearest-rank p95 等于最慢值，因此当前默认门槛实际更严格。

每个服务端快照通过一次只读 SSH 固定同一个 `teamtalk` systemd InvocationID/MainPID，记录 RSS、线程、
文件描述符、CPU ticks、主机 load1、MemAvailable、build identity 和 所有关键组件健康状态。运行期间服务不能重启、
build identity 不能变化、所有健康项必须为 `UP`；线程高水位不超过基线 +32，文件描述符高水位不超过
基线 + `2 × 客户数 + 64`，关闭全部客户端并等待 30 秒后两者都应回到基线 +16 以内。CPU、RSS、load 和
可用内存只记录实测趋势，尚无足够样本时不设置虚假的 SLO。默认报告写入
`server/server/build/reports/capacity/connection-capacity.json`；任务开始会先删除同路径旧报告，执行基础设施
异常时不得把上一轮成功 JSON 当作本轮证据。

任务硬限制最多 128 个客户端，参数示例：

```bash
./gradlew :server:server:connectionCapacityTest \
  -PconnectionCapacityClients=64 \
  -PconnectionCapacityRampGroupSize=2 \
  -PconnectionCapacityRampIntervalMs=1000 \
  -PconnectionCapacityHoldDurationMs=60000 \
  -PconnectionCapacityReconnectClients=16 \
  -PconnectionCapacityReconnectTimeoutMs=30000 \
  -PconnectionCapacitySampleIntervalMs=5000 \
  -PconnectionCapacityCleanupObservationMs=30000 \
  -PconnectionCapacityReport=/absolute/path/connection-capacity.json
```

该任务给出当前单实例测试部署的连接/认证/重连开发基线，不代表万级在线承诺，也不替代附件、
后台维护、慢 PostgreSQL、磁盘压力和长时间 soak。

## 搜索容量基线入口

搜索容量使用真实客户端二进制 `message/search` 与 `user/search` RPC，不直接调用 Lucene、PostgreSQL 或
服务端内部方法：

```bash
./gradlew :server:server:searchCapacityTest
```

默认夹具建立 4 个查询用户、1 个隔离用户、16 个所有查询用户都加入的群和 256 条已确认消息；另在隔离
用户自己的群写入 1 条包含相同 marker 的消息。全局 marker 会随群数量自动扩展，保证每个精确期望集合
都能装入协议固定的 10 条首屏。正式负载前逐用户核对 64 次 scoped 精确结果、16 次 global 精确结果、
4 次用户结果、81 次顺序、5 次隔离和 4 次 miss，不允许只抽查一条路径后把整个矩阵标绿。

负载先执行每用户 2 次消息+用户并行查询作为 warmup；稳态阶段每用户执行 50 次消息搜索、间隔 280 ms，
每 5 次包含 1 次确定 miss；突发阶段执行 100 个完整 UI cycle、并发度 16，每个 cycle 同时发起消息与用户
搜索。负载后再发送一条新消息，必须已收到 ACK，并在 30 秒有界窗口内分别从 scoped/global 搜索精确出现
一次。全部查询会话必须持续认证且认证次数不变；资源采样固定同一个 systemd InvocationID/MainPID 与
build identity，要求所有关键组件始终健康、CPU ticks 单调。RSS、线程、FD、load 与 p50/p95/p99 只记录，不在首轮
样本上伪造正式 SLO。

默认报告位于 `server/server/build/reports/capacity/search-capacity.json`。Gradle 在任务图开始执行前清除旧报告；
测试进入配置解析前先写带 `runId` 的 `started` 状态，夹具、投影、资源采样或配置失败会原子替换成带阶段
和错误类型的 `failed` 状态，完整场景结束后才原子替换为最终报告。因此编译失败不会留下上一轮成功 JSON，
运行期失败也不会只有 JUnit 文本。报告同时记录固定 10 秒 RPC timeout、稳态 miss 配比和完整负载形状。

可调参数仍受测试代码的用户数、群数、消息数和总夹具量硬边界约束：

```bash
./gradlew :server:server:searchCapacityTest \
  -PsearchCapacityUsers=4 \
  -PsearchCapacityChats=16 \
  -PsearchCapacityMessagesPerChat=16 \
  -PsearchCapacityWarmupCycles=2 \
  -PsearchCapacitySteadyQueriesPerUser=50 \
  -PsearchCapacitySteadyIntervalMs=280 \
  -PsearchCapacityBurstCycles=100 \
  -PsearchCapacityBurstConcurrency=16 \
  -PsearchCapacityProjectionTimeoutMs=30000 \
  -PsearchCapacitySampleIntervalMs=2000 \
  -PsearchCapacityCleanupObservationMs=30000 \
  -PsearchCapacityReport=/absolute/path/search-capacity.json
```

该门禁只证明当前协议的首屏消息与用户搜索，不覆盖文件、文档或服务搜索，也不代表任意更大索引规模、
固定参考硬件或正式发布 SLO。它也不替代下文独立的附件容量门禁。

## 附件容量基线入口

附件容量使用独立显式任务，通过与客户端相同的 SDK 链路验证小对象上传、业务引用和鉴权下载：

```bash
./gradlew :server:server:attachmentCapacityTest
```

默认建立 2 个真实用户会话和一个双方都加入的群。每个对象都是 512 KiB 文件：先每用户上传 2 个
warmup 对象（合计 4 个），再每用户上传 8 个稳态对象（合计 16 个，间隔 280 ms），最后执行 16 个、
并发度 4 的 burst 上传。上传走真实 `FileRepository` HTTP 链路，返回的 descriptor 由同一上传者
通过 `GroupFileRpc` 发布为群文件；随后另一名群成员用自己的认证会话下载全部 36 个对象，默认下载并发度为 4。
测试不直接调用服务端内部方法，也不使用绕过 SDK 的原始 HTTP 快捷路径。

门禁逐对象核对唯一 objectId 与 FileStore 相对路径、descriptor 的非空路径/名称/MIME/大小、
GroupFile 当前附件元数据、下载长度和 SHA-256。最后删除每个 GroupFile 逻辑条目，再通过完整目录列表确认
36 条业务引用全部消失。这个 cleanup 不会伪装成物理文件已立即删除：无其他引用的载荷仍按默认 7 天保留期由 GC
回收。两个会话在全程中必须保持认证且不增加认证次数；服务资源采样必须固定同一 systemd InvocationID/MainPID、
build identity，保持所有关键组件健康和 CPU ticks 单调。

默认报告位于 `server/server/build/reports/capacity/attachment-capacity.json`。Gradle 在任务图开始时删除旧报告；
测试在配置解析前先原子发布带 `runId` 的 `started` 状态，失败时原子替换为带 phase、异常类型和有界消息的
`failed` 状态，只有全部转移、正确性、引用清理、会话和资源门禁结束后才发布完整报告。
该任务始终保持宿主机网卡、Wi-Fi、代理、DNS 和防火墙不变。

### 上传事务精确重放门禁

附件验收还必须用同一真实用户和稳定的 `uploadId` / `issuedAt` 执行独立的重放场景：首次
上传一份固定 payload，保存完整 `UploadResult` 与主文件/可选缩略图 descriptor；然后精确重启
TeamTalk systemd unit、确认 InvocationID/MainPID 已更换并等待原 SDK 会话重新认证，再以同一身份和
完全相同的 payload 重试。重放必须
返回与首传精确相同的 descriptor，不创建第二份对象，也不重复计入 uid/global 字节或对象槽。
保留同一 `uploadId` 和 `issuedAt` 但改变任意 payload 字节的对照请求必须稳定返回 HTTP `409`，
原 descriptor、backing object 和容量台账保持不变。此门禁验证服务端收据与重启恢复；GUI 跨进程保存
尚未发出的源文件和上传命令仍属于 CLIENT-04。

参数只能通过显式任务调整，且测试代码保留对用户数、小对象大小、总对象数、并发度和总转移量的硬上限：

```bash
./gradlew :server:server:attachmentCapacityTest \
  -PattachmentCapacityUsers=2 \
  -PattachmentCapacityPayloadBytes=524288 \
  -PattachmentCapacityWarmupUploadsPerUser=2 \
  -PattachmentCapacitySteadyUploadsPerUser=8 \
  -PattachmentCapacitySteadyIntervalMs=280 \
  -PattachmentCapacityBurstUploadsTotal=16 \
  -PattachmentCapacityBurstConcurrency=4 \
  -PattachmentCapacityDownloadsPerAttachment=1 \
  -PattachmentCapacityDownloadConcurrency=4 \
  -PattachmentCapacityRequestTimeoutMs=120000 \
  -PattachmentCapacitySampleIntervalMs=2000 \
  -PattachmentCapacityCleanupObservationMs=30000 \
  -PattachmentCapacityReport=/absolute/path/attachment-capacity.json
```

该门禁只覆盖 512 KiB 小对象在当前 RocksDB 存储层的容量链路，实测延迟、RSS 和资源高水位不是带宽或
SLO 承诺。它不替代下文大于 32 MiB 的 FileStore 文件系统层门禁，也不替代随后独立执行的
Desktop/Android 本地优先媒体门禁；慢 PostgreSQL/磁盘压力、后台维护、长时间 soak、固定参考硬件和正式
SLO 仍保留在 REL-07。响应丢失与重启后的幂等重放由上述独立门禁验证。

### FileStore 文件系统层大对象门禁

大于 32 MiB 的对象使用单独的显式任务，避免把一次服务重启和大对象转移混入普通单元测试或小对象容量曲线：

```bash
./gradlew :server:server:filesystemTierCapacityTest --no-parallel --max-workers=1
```

默认生成 32 MiB + 64 KiB 的确定性载荷，走产品 `FileRepository` SDK 上传，发布为 `GroupFile` 后由另一成员
按当前业务授权流式下载并核对长度与 SHA-256。测试随后只执行一次精确的 TeamTalk systemd unit 重启，等待
两个既有客户端各重新认证一次，再以原 `uploadId` / `issuedAt` 和相同载荷重放上传。重放必须返回完全相同的
descriptor，目标大小的文件数量和总字节不能增加；另一成员再次流式下载仍须得到相同哈希。最后删除业务引用并
确认目录中已不可见。测试保持宿主机网卡、Wi-Fi、代理、DNS 和防火墙不变，也不直接停止其他服务。

报告默认写入 `server/server/build/reports/capacity/filesystem-tier-capacity.json`。可显式调整的参数只有载荷大小、请求
超时和报告路径；载荷必须在 `(32 MiB, 64 MiB]` 内：

```bash
./gradlew :server:server:filesystemTierCapacityTest \
  -PfilesystemTierCapacityPayloadBytes=33619968 \
  -PfilesystemTierCapacityRequestTimeoutMs=240000 \
  -PfilesystemTierCapacityReport=/absolute/path/filesystem-tier-capacity.json
```

该门禁证明的是单个大对象在当前文件系统层的流式转移、服务重启恢复和精确重放，不是并发带宽或磁盘容量
SLO；Desktop/Android 本地缓存原子发布与离线播放由下文真实双端门禁独立证明，正式发布物晋级仍归
REL-05。

### Desktop/Android 本地优先媒体门禁

使用同一批待分发客户端，在 Android 真机和对应 Desktop 平台分别验证：

1. 清空本任务视频的精确缓存后打开画廊，下载阶段只能出现 `media.gallery.video.downloadProgress`，
   完整校验和原子发布后才出现 `media.gallery.video.surface` 及播放器控件；两者不能同时存在。
2. 核对缓存文件大小与附件声明一致，测试 seek、播放/暂停和全屏。macOS 同时核对系统屏幕与 Skia 绘制，
   覆盖正常切换、快速反转、系统退出、进入中关闭及全屏中关闭重开，不能只看窗口 placement。
3. 使用横屏和竖屏视频交替切换至少 12 次，按当前 Desktop PID 与精确媒体路径确认只有当前播放器持有 FD；
   每段连续开关 4 次后 FD 归零，并覆盖立即销毁及纯音频关闭。其他系统使用对应文件句柄观测方法。
4. 只停止目标 TeamTalk 测试服务或隔离被测客户端到该端点的连接，保持宿主机和设备网络服务不变。
   两端再次打开缓存视频应直接进入本地播放器，seek/暂停继续可用，无新的下载请求。
5. 核对缓存没有 `.part` / `.partial` 残留，恢复目标服务并确认全部健康项为 `UP`。

报告记录客户端制品哈希、服务器身份、设备/系统/架构、语义树、截图与文件句柄结果。Apple Silicon、
Android 和各 Desktop 系统分别核对；构建成功或其他架构实测不能替代目标设备运行。同批制品要求见
[REL-05](../10-reference/roadmap.md#rel-05--发布物晋级门禁)，系统信任签名、公证及许可随包核对另按发行流程执行。

### Desktop/Android 本地 SQLite clean-close 门禁

验收前写入可辨识草稿并记录实际 `journal_mode`，按平台标准退出流程关闭本任务客户端：

- Desktop 使用正常退出操作；macOS 红色关闭键只隐藏窗口，不能代替进程 clean exit。退出后只读核对
  数据库完整性与 WAL/SHM 状态，再启动并确认草稿恢复。若实际模式为 `delete`，结果只证明标准退出、
  driver 关闭和可靠事实重开，不称为 WAL 覆盖。
- Android 确认运行中的账号 namespace 存在 `.open` marker；正常退出后 marker 清理，数据库
  `quick_check=ok`，再次启动恢复草稿。force-stop 是独立的非正常关闭场景，不能冒充 clean close。

`PRAGMA wal_checkpoint(PASSIVE)` 的 WAL 行为由 xerial SQLite 真实文件测试覆盖：独立 reader transaction
钉住旧 snapshot 时 clean close 有界返回并留下未 checkpoint 的 frame；释放 reader 后新 driver 精确恢复
草稿与 outbox。这个确定性证据与真实客户端退出/重开证据互补，不能互相冒充。

部署链本身还必须证明：半安装目标和并发部署被拒绝；升级前分发在 live 目录外完成校验；systemd 停止后
MainPID/cgroup 确实清空；新 build identity 与 所有关键组件健康检查全部通过才提交。故障注入验收应至少覆盖一次
停服后启动/健康失败，并确认旧分发、env、TLS、unit、旧监听端口与旧 build identity 恢复健康。任何
密码不得出现在进程参数、测试报告或异常文本中。

部署锁故障注入还必须覆盖控制进程 stdin EOF（等价于本地 Gradle 被强杀后的 SSH 管道关闭）、SSH
就绪超时和并发 owner 冲突：控制会话结束后 owner flock 应自动释放，不得依赖删除锁目录；如果断开时
已有远端命令或上传仍在执行，下一控制器的 operation drain 预检必须快速失败，直到旧操作退出；断开后
才抵达远端的旧代次操作必须在变更文件前被 fencing 拒绝。

`deployServerResetData` 的破坏性链路另须以可丢弃 fixture 验证：确认 property 必须精确匹配配置的
`host:/canonical/deployPath`；空目标、半安装、symlink/physical path 漂移都在删除前拒绝；新分发必须在
停服前完成 staging identity 校验；systemd 停止和 compose down 后只能删除该 target 的 `data/`，并同时
得到空 PostgreSQL bind mount 与空本地 stores。故障注入分为删除前、删除中/后两组：前者验证旧实例可
恢复，后者只允许验证“旧二进制在再次清空的数据上健康”，测试名称、日志和异常不得声称恢复了旧数据。
最终仍须断言 所有关键组件健康状态和精确 build identity。fixture 数据丢失是此验收的预期结果，不应用真实备份
恢复测试替代。

## 传输安全验收门槛

HTTPS + TLS/TCP 和 HTTP + 自签 TLS/TCP 部署的发布验收须分别收集以下证据。结果只证明被测传输组合，不能只因代码或
本地测试存在就标记为真实部署已通过：

1. IM TCP 只协商 TLS 1.2/1.3；记录客户端使用系统 WebPKI 还是配置证书的专用 TrustStore，严格校验
   `tcpAddress` 的 hostname/IP 与证书 SAN。自签 IP 场景不要求安装系统 CA。
2. TLS handshake 成功前客户端不发布 `CONNECTED`、不发送 AUTH；错误主机名、不受信证书和黑洞
   handshake 都只能进入断线/重连路径，报告中不得出现凭据。
3. 被测 TLS 监听器不接受明文协议帧，SDK 在 TLS 失败后不回退明文。另行配置的明文监听器是不同
   运行模式，不能从这项结果推断它拒绝公网绑定。
4. 被测 HTTPS 安装只启用 HTTPS connector；该组合的认证 HTTP 基址显式配置为 HTTPS。
   被测 HTTP + 自签 TLS/TCP 安装则继续使用 HTTP connector，同时配置 TCP keystore；不得把 HTTP 改成
   HTTPS 或绕过平台信任来掩盖与部署配置不一致。
   文件、群机器人和日志通道不把 3xx 当成功，也不跟随重定向转交凭据。
5. 部署生成的 `TCP_HOST/TCP_PORT`、实际监听和 `tcpAddress` 一致；`/health` 的 `tcp` 项以当前 keystore
   叶证书作为唯一信任锚完成真实 TLS handshake，而不是只验证 socket 可连接。

Android 发布验收还必须覆盖 **release APK × 非 loopback HTTP 地址**，不能用 debug 清单代替：
确认安装包清单允许明文，配置 HTTP 服务入口与匹配所选信任方式的 TCP TLS 端点，完成登录、认证文件
上传/下载和群机器人列表。报告分别记录 HTTP 与 TCP 的目标和信任方式；历史 WebPKI 验收不能替代
自签 TCP 验收，两者都不能证明远程明文 TCP 可用。认证请求仍不跟随重定向。

自签 IP 部署另须验证：公共 PEM 随部署配置注入 Android、Desktop 和无头 SDK，私钥未进入产物；
错误证书与错误 SAN 均失败，默认公版仍使用平台 WebPKI；重复生成与普通服务器升级保留原证书私钥，
升级前后原客户端继续连接。按[连接矩阵](../07-operations/configuration.md#传输配置边界)记录各端实际结果，
不把证书工具测试写成真实双端验收已通过。

## 验收范围

远程验收至少覆盖以下闭环：

- 注册、登录、刷新凭证和错误认证；
- 用户搜索、好友申请、接受与联系人同步；
- 使用同一制品让 Desktop 与 Android 两个真实账号互为好友：上传 canonical 图片、通过
  `ProfilePatch` 替换/清除头像，验证本人和好友收到完整 `USER_UPDATED`，资料、联系人、组织成员、消息头像、
  搜索及 `peerUid` 个人会话同时收敛；好友可鉴权下载当前头像，替换/清除后旧 path 不再凭头像引用授权，
  当前头像阻止未引用 GC，客户端缓存命中可离线展示且下载/校验/解码失败回占位；
- 私聊和群聊创建、成员与角色操作；
- 文本、Markdown 富文本、带 scope-local sidecar 的上下文图片/文件、独立图片、语音、视频和普通文件；
- 上传、附件消息发送前校验、下载与元数据读取；
- 群文件目录、版本、成员 ACL、删除后下载失效；
- 企业文档空间创建、用户/部门 ACL（直属 grant、包含下级继承、不包含下级时拒绝，以及组织 owner 不自动授权成员）、创建来源/归属/责任人交接、组织资产归档保护、可同时承载正文和子文档的文档树、版本冲突、历史读取、撤权后拒绝和删除；
- 消息历史、搜索、编辑、撤回、转发和已读；
- 会话草稿、置顶、静音与多设备同步；
- 构造低于 `compactedThrough` 的旧游标，验证 RESET 后 checkpoint 收齐、本地单事务安装、
  `baseEventId` tail 与 Desktop/Android 当前投影一致；不宣称各 section 页共享一个 MVCC snapshot；
- 连接诊断从 BASELINE 在线开启后，客户端结构化事件可按精确 record id 联查同代服务端轨迹；重连结果隔离，禁用后停止采集；
- 组织目录先通过 `OrganizationRepository` 的二进制 revision-fenced RPC 收敛基线，管理 HTTP 写入后
  已就绪终端收到 `eventId = 0` 的 `ORGANIZATION_CHANGED`，再次 RPC 刷新达到至少该 revision；
- 使用同一制品让 Desktop 与 Android 的两个真实账号互为好友，验证 Presence 初始快照、上下线、断线后
  重连重建基线，以及前台真实输入的 TYPING 2 秒发送节流、3 秒过期和断线/新消息/离页清理；
- 非法 payload、越权操作和不存在资源的拒绝行为。

具体用户流程见[场景目录](scenario-catalog.md)。

## 同步事件保留的加速验收

默认 30 天保留不能靠每次验收真实等待 30 个墙钟日。明确用于故障测试的可丢弃实例允许使用时间加速夹具，但夹具
只能改变目标事件是否越过保留 cutoff，不能替 compactor、checkpoint 或客户端制造结果。可重复流程如下：

1. 通过真实产品路径为目标账号建立投影和连续事件，让待验收客户端持久化一个正数旧游标；退出该精确
   客户端连接以释放 replay lease，但保留本地数据库。由对端继续通过真实业务路径产生权威状态，直到
   选定预期 checkpoint base。记录目标 uid、dataset、旧 cursor、旧
   `floor (= sync_streams.compacted_through)`、`last_seq`、事件正文/类型、dispatch 字段，以及事先选定且
   未越过 cutoff 的范围外/其他 uid 对照行。Desktop 与 Android 可以用不同账号分两轮执行。
2. 确认目标 `old floor + 1 .. base` 是无缺口连续前缀、行数等于 `base - old floor` 且全部已经由真实
   dispatcher 完成派发；不得为了满足清理条件伪造 dispatch 状态。停止精确 TeamTalk 服务但保持数据库
   可用，确保 retention worker 和业务写入不与夹具竞态。随后在 owner-only 数据库夹具事务中，只把该
   精确 `uid + stream_seq` 前缀内的 `sync_events.created_at` 回拨到 31 天前；更新行数必须与事先记录
   完全一致。
3. 禁止修改 `sync_streams.compacted_through`、`last_seq`、任何客户端 cursor、dataset、`event_type`/`payload`、
   `dispatched_at`、`dispatch_attempts`、`next_attempt_at`、`last_dispatch_error` 或 checkpoint 内容；禁止直接
   删除 `sync_events`。
   事先选定且未越 cutoff 的范围外事件和其他 uid 行必须保留为未变化的对照组；不要求全局 compactor
   跳过实例中原本已经满足回收条件的无关前缀。
4. 用正常服务启动触发真实 retention worker，等待有界清理完成。数据库断言目标连续前缀已被物理删除、
   `compacted_through` 只推进到 base、`last_seq` 不变，且选定对照行没有变化。每个 build/deployment 至少
   一轮在客户端恢复前再正常重启服务并复查同一 floor，证明它来自持久状态而不是进程内推断。
5. 重新启动原客户端及其原数据目录。客户端必须从低于 floor 的本地 cursor 发出正常同步请求，收到
   `SYNC_RESET`，以同一 dataset 收齐 User/Contact/Chat/Conversation checkpoint，并在本地单事务安装
   `baseEventId`。确认 checkpoint 就绪后，再由对端通过真实业务路径产生从 `baseEventId + 1` 开始的 tail；
   客户端必须连续消费它。最终持久 cursor 必须单调达到验收结束时的服务端高水位，当前用户、联系人、
   Chat 和 Conversation 投影与服务端一致；验收期间新到达的已读等事件也必须继续推进而不能被
   checkpoint 覆盖。Bot 的超长离线语义另按场景目录 `SYNC-03` 验证，不由 GUI 双端夹具补造历史回调。

门禁证据必须同时保留旧 cursor、压缩前后 floor/物理行、checkpoint base、tail 范围和客户端最终 cursor；
该 build/deployment 还必须保留至少一轮第二次服务启动后的同一 floor。

这个夹具证明默认 cutoff 下的年龄判定、真实物理压缩、floor 持久化以及双端 checkpoint + tail 恢复，
不证明服务曾连续运行或真实等待 30 天，也不替代长时间 soak、调度漂移和运维告警验收。报告必须写明
“31 天 `created_at` 加速夹具”，不得把结果表述为“墙钟 30 天离线已通过”。

## 多账户驱动

双向消息、好友和群组场景需要第二个在线账户。项目提供两种驱动方式：

- `TestPeer`：测试代码中的协议级对端，适合验收用例和故障定位；
- `tt-agent` / `tt-cli`：产品化无头客户端，适合长时间保持在线、外部自动化和跨客户端联动。

两者都必须通过公共 SDK 与真实服务交互，不允许绕过服务直接写数据库制造“成功”状态。需要验证服务端拒绝时，应从客户端 API 观察明确错误，而不是只检查日志。

## 文件消息的强制断言

文件消息是跨 HTTP 与 TCP 的关键契约，验收必须同时证明：

1. 上传返回 TeamTalk 文件端点的相对路径；
2. SDK 拒绝第三方 URL、路径穿越和结构不完整的附件；
3. 服务器在分配序号和返回成功前确认文件真实存在；
4. 不存在或元数据被伪造的文件不能形成一条成功消息；
5. 接收端能通过自己的部署地址解析路径并读取文件；
6. 小文件自动下载，大文件等待用户点击，气泡能表达传输状态。

“发送接口返回成功”必须等价于消息已通过服务器安全校验；不能把附件错误推迟到接收端暴露。
下载端同时验证 Bearer 身份和附件反向引用：上传者只可在对象仍为未业务绑定 staging 时通过 owner
旁路读取，消息落库后仅活动会话成员可读，文档落库后仅具有当前空间 READ 的主体可读；丢失业务 ACL 的旧上传者同样
得到 403。匿名请求和无关用户必须分别得到 401 与 403。远端验收必须保留这些身份断言，防止随机路径或历史 uploader
身份重新退化为授权凭据。

Markdown 上下文资产验收还必须证明：

1. Markdown 中的 `teamtalk-asset://asset/<uuid>` 引用与 canonical sidecar 精确闭包，缺失、跨 scope、重复或额外 descriptor 均被拒绝；
2. 消息 ACK 后，另一账号以自己的认证会话渲染图片/文件，拉取历史及客户端重启后 sidecar 仍在；
3. 文档每个修订读回各自的 Markdown + sidecar，新资产只接受调用者本人未绑定 staging，只有同一文档历史已知资产可复用；把其他可读消息/群文件/文档资产重绑进本文档必须拒绝；
4. 上传中或失败 job 、正文/sidecar 不一致均阻止发送/保存；A 发起上传后切到 B，READY 只能在返回 A 时交付，用户已删除的引用不因 READY 复活。

当前不把以下未实现能力列为已验收：跨进程/跨设备富资产聊天草稿、持久本地源文件并断网续传的附件 outbox、
Android 文档拖放。上传失败的就地重试/取消以及回复消息内嵌资产按各自后续门禁验收。

### 聊天可视光标内嵌资产双端门禁

Desktop 与 Android 都以 `before  after` 为可视草稿，用真实指针/键盘事件把选区放在两段文字中间，
再经系统 picker 连续导入一张图片和一个 Markdown 文件。导入后仍为可视编辑器；切到源码后应得到
连续且顺序正确的两个 `teamtalk-asset://asset/<uuid>` 引用。发送后气泡子节点依次为前置文字、图片、
文件和后置文字；离开会话再进入后，两个资产节点及顺序保持。

该门禁覆盖聊天可视光标插入；文档编辑器、跨进程草稿、源文件 spool/outbox 与断网续传各有独立范围。

### 文档可视光标内嵌资产双端门禁

Desktop 与 Android 都以 `before  after` 为可视正文，先用真实指针/方向键及字符探针确认光标位于
两段文字之间，再经系统 picker 连续导入一张图片和一个 Markdown 文件。导入后保持可视编辑器，
源码依次为前置文字、图片 URI、文件 URI 和后置文字。保存后关闭标签或返回目录并重开，另切换预览，
两端均保留上述顺序与 READY sidecar。

确定性回归覆盖 READY 紧邻输入时同步捕获富文本、sidecar 触发 block list 重建后的重新绑定、controller
未挂载时 LOCAL/READY 保序重放，以及预览/切页销毁时草稿写入。迟到 READY 不能读取已卸载 controller
的旧快照。本门禁不证明跨进程源文件恢复、断网续传或 Android 文档拖放。

### 回复消息内嵌资产双端门禁

分别以 Android → Desktop 和 Desktop → Android 发送含图片与文件的回复正文，验证上传屏障、回复引用、
对端认证渲染和资产顺序。两端离开会话再进入，核对历史 sidecar、图片画廊与文件预览；缓存命中不重复下载。
失败或取消不能发送残缺引用，原消息和其他消息不受影响。离线缓存场景另按本地优先媒体门禁核对。

## 群文件与文档业务验收

群文件验收额外证明：聊天中没有发送过该附件时，发布群文件也能为当前成员建立下载权限；非成员不能
列目录；成员可以用自己上传的附件追加版本；删除逻辑条目后，若没有其他业务引用，成员下载立即得到
403。这样可以防止实现把群文件错误地依赖在消息附件反向索引上。

群文件可靠命令还必须从真实客户端证明 createFolder、createFile、addVersion、rename、delete 都在第一次
发送前落入本地账号库。离线或响应未知时，UI 显示已持久排队而不是成功或失败；强停原客户端并重开后，
仍以相同 entryId/commandId 重放。恢复后服务端每个命令只保留一条 receipt，条目 revision、版本、usage
和审计只推进一次，本地 outbox 清空。rename/delete 的精确 `Unit` 重放还要跨一次 TeamTalk 进程重启，
并与改写 payload 的 409 对照；条目后来变化或 actor 离群后的窄收据确认由 PostgreSQL 确定性集成测试先
证明，远端不得用放宽新命令成员校验来制造通过。

群文件可靠命令的断网恢复还必须覆盖：飞行模式或只停服造成的离线窗口内，rename/delete 跨过
force-stop/relaunch 后仍按原 identity 重放收敛、outbox 归零，且排队状态在 UI 显示持久恢复提示而非
误报失败。

文档验收额外证明：创建有正文的父文档后可直接在其下创建子文档，父文档的身份、正文和修订不变；首页与文档树列表
不读取全部正文；最近访问/最近创建同时覆盖叶节点和内节点，SQL 候选经批量 access snapshot 后必须由 typed READ 最终裁决，并在撤权、归档或删除后隐藏；另一会话可以修订，旧 expectedRevision 必须失败；
修订 1 的完整 Markdown 在修订 2 后仍可读取；含子文档的文档不得直接删除，叶节点用当前 revision 删除后从普通列表消失。归档和删除还要证明
“提交成功但响应丢失”后以同一 operationId 重试成功，而不同 operationId 或 actor 不能冒领完成态。
空间创建的丢响应验收还要在原创建者仍为 steward 时重放并取得包含当前 owner/custodyRevision 的
`DocumentSpaceCreateResult.space`；再完成交接或归档后重放同一创建 ID，必须只得到相同 `spaceId` 与
`space = null`。SDK 和工作台应把后者作为已完成命令，清理旧干净投影并结束创建 outbox，不能重新发布 Owner。
文档创建也要冻结稳定 documentId 与初始 payload：首次返回完整投影；创建者因 custody 失权以及空间归档后，
同一命令的远端精确重放必须保持成功并得到 `DocumentCreateResult(documentId, null)`。本地验收另外覆盖软删除后
重放、并发首次/重放、payload 改写和跨 actor 隔离；null 必须完成 create outbox，不能触发第二次创建或伪造投影。
归属交接还要证明：`createdBy` 始终不变；组织 owner 不让普通成员自动可见；目标 steward 获得 Owner，旧 steward 按剩余 grant 重算权限；陈旧 custodyRevision 与改写 payload 的 operationId 稳定返回 409；原命令在旧 steward 失权、后续再交接或空间归档后仍从收据返回同一 `DocumentCustodyTransferResult`，SDK 已清理干净空间投影并由后续列表重建。持有活动空间的组织节点归档必须被拒绝，交接后才能归档。
PostgreSQL 集成验收还必须证明：新 operationId 的 owner/steward 完全不变时返回 400 且不写收据；用同一个 operationId 修正为真实交接随后成功，并继续支持精确重放。

两端先创建名称顺序与创建顺序相反的同级文档，确认改名、刷新和重启后仍按 `(createdAt, nodeId)` 保持
创建顺序。离线夹具只停止目标 TeamTalk 测试服务，保持宿主机网络、手机网络、代理、DNS 与防火墙不变：

- 分别离线 rename 与跨 parent move，正常退出及 force-stop/relaunch 后仍显示待确认结构命令、缓存树和草稿；
- 服务恢复后 outbox 收敛，旧/新分支、标题、父级、revision 与 path spine 一致；结构命令同时记录的正文草稿
  保持 dirty，只有用户显式保存后才更新正文，不能成为结构 ACK 的附带写入；
- move 完成后再次离线并重启，两端应先显示缓存 root/spine，再独立校验正文，能展开目标父级并读取移动后文档；
- 对照实际发送的唯一 commandId 核对 receipt，重启与恢复不增加重复行；服务恢复后本地 outbox 归零。

首个 RPC 前服务不可用的 UI 场景验证排队提示、跨进程恢复与最终收敛。提交后 ACK 丢失的精确收据重放、
同 identity 不重复推进 revision 由协议、临时 SQLite 和 PostgreSQL 的确定性测试补齐，两层证据互补。
报告与截图保存在任务或 CI 产物中，不把本机 `build/` 路径、单次行数和修复提交写成长期系统约束。

事务验收还要直接断言 `PgUnitOfWork.write` 使用 JDBC `READ_COMMITTED`、只读事务使用 `REPEATABLE_READ`；并发精确交接重试必须等待同一 State 围栏，在首个事务提交后看到其不可变 receipt 并返回完全相同的结果，不能受数据库或 role 默认隔离级别影响。
客户端不维护服务端权限的 lease、generation、watermark 或撤权墓碑；运行本验收前，应先通过
[本地测试](local-tests.md) 中的单 mutex 缓存提交、普通 latest projection、明确 403/根 404/完整终页
omission 清理、网络失败保留、脏草稿孤儿化、孤儿远端入口关闭、强杀恢复、重新可见完整重建、policy ACK
刷新和 create outbox 用例。
真实部署仍要验证服务端逐请求授权、最终跨账号可见性、权限拒绝、交接回执和 create outbox 业务结果，
两层证据不能互相代替。
建群验收还要在服务端已经提交而客户端未取得响应的窗口用同一 operationId 重放，确认返回相同 chatId、
成员 Conversation 与 CHAT_CREATED 事件都只产生一次；复用该 ID 改写群名或成员必须稳定返回 409。
组织目录远程验收消费的 `ORGANIZATION_CHANGED` 只是在线 wire 证据，不把它误写成 durable fanout；
`EventProcessor` 先持久提升 requiredRevision、保留 stale nonempty 行，以及认证恢复兜底由本地确定性测试
覆盖。低于服务端最低支持 minor 或 major 不匹配的客户端必须在协商阶段明确拒绝；兼容窗口内旧客户端
只使用其声明版本支持的 RPC 与字段，不能把最低支持版本误写成必须精确匹配当前 minor。

真实客户端验收使用至少 100–200 篇、包含多层子文档的空间，同时核对“文档首页 → 空间工作区”两级导航、Desktop 约 30–32dp 的紧凑行与 Android 约 44dp 的触控行、
标题打开正文与展开按钮加载子文档的独立命中区、新建落点、标题截断与滚动、富文本输入、上下文资产 picker/预览和未保存确认。树中不应出现大文件夹图标或
文件夹卡片。Desktop 还要覆盖多标签与独立窗口，Android 要覆盖返回文档树后的展开/定位状态和未保存切换保护。这些交互必须通过稳定语义节点
操作并保留截图，不由协议测试替代。

权限收敛验收还必须使用两个真实账号：一端保留未保存正文，另一端撤销并重新授予空间访问；被撤端应立即
移除干净空间、树和兄弟页，只保留只读本地孤儿，且保存、新建、移动、历史和授权入口不可用。Desktop 与
Android 分别在离线状态强杀重启后核对缓存/草稿，重新授权后由服务端完整空间行和树重建，不能复活旧权限字段。

上下文资产的客户端验收必须使用真实产品入口：聊天 Desktop 分别覆盖 picker、文件 drop 和二进制 clipboard paste，
Android 真机覆盖系统 picker 和二进制 clipboard；文档 Desktop 在主窗口与独立窗口都覆盖图片/文件 picker、drop
和二进制 clipboard paste（普通文本 paste 仍走编辑器）。Android 文档除图片/文件 picker 外，还要覆盖显式
“粘贴剪贴板附件”、物理 Ctrl/Meta+V 的二进制剪贴板路径、普通文本 fallback，以及系统 content URI 上传完成后
保存、强杀重启和认证渲染；失败或未完成上传不能遗留可提交的 pending 引用。至少保留 pending、READY 后可发布、
已发布认证渲染与重启回放的语义树/截图证据；协议或集成测试不能代替真实系统选择器、拖放、剪贴板和认证媒体组件。

## 失败处理

按以下顺序收集证据：

1. 确认健康端点和服务版本；
2. 查看 `server/server/build/reports/tests/acceptanceTest/` 中的失败用例；
3. 使用 correlation id、uid、chatId 或 clientMsgId 查询服务日志；
4. 区分部署陈旧、配置错误、环境故障与产品回归；
5. 在修复产品问题后，把最小复现保留为稳定验收场景。

测试部署只清理本任务明确创建的测试资料，普通升级保留内测用户数据。项目处于开发者预览阶段，对外尚不承诺稳定版兼容，
内部遵循[版本与迁移规则](../04-protocol/versioning.md)；破坏性重建必须另有明确实例与数据范围授权，
并记录影响和恢复方案，不能用清库掩盖兼容缺陷。
