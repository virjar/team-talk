# 真实部署验收

TeamTalk 的主业务验收连接 `gradle/deployment.json` 指向的测试部署。它验证的不是单个类，而是客户端 SDK、TCP 服务、HTTP 文件端点、数据库和搜索索引共同形成的系统行为。

## 前置条件

1. `gradle/deployment.json` 已填写目标服务器参数；当前远程 SDK 验收使用 HTTPS + TLS/TCP 组合，
   其与服务运行时、部署工具的支持范围见[传输配置边界](../07-operations/configuration.md#传输配置边界)。
2. HTTPS 健康检查可达，`tcpAddress` 端口与运行时 `TCP_PORT` 一致，公网 IM TCP 可完成 TLS handshake。
3. 如果服务端代码、数据库结构或静态资源有变化，先部署新版本。
4. 测试环境允许创建带独立前缀的临时账户和业务数据。
5. 组织资产归属验收还需要运行机上的 owner-only `gradle/deployment.secrets`，或同时设置
   `TK_E2E_ADMIN_USER` / `TK_E2E_ADMIN_PASSWORD`；`acceptanceTest` 是显式的完整验收入口，缺少管理 fixture
   会带配置提示明确失败，不允许跳过组织治理用例后产生假通过。

```bash
./gradlew deployServer
./gradlew :server:server:acceptanceTest
```

小范围开发者内测可先执行 `./gradlew :server:server:previewSmokeTest`。该任务从同一测试类选择
`preview-smoke` 场景，不依赖管理 fixture，也不停止或重启服务；具体范围见
[预览版指南](../01-getting-started/developer-preview.md#轻量业务验收)。

`acceptanceTest` 是完整业务验收入口，只运行 `RemoteAcceptanceTest`，并由构建配置注入远程端点。不要通过手工拼接测试系统属性建立另一套隐含入口。

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
文件描述符、CPU ticks、主机 load1、MemAvailable、build identity 和 9 项健康状态。运行期间服务不能重启、
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

2026-08-31 使用客户端 `2d59e4ff`、服务端
`1.0.7+1796f4ec3506cd609793f7eda5301aa50dcc8c8f` 在同一测试实例连续运行两轮默认配置，结果如下：

| 指标 | 第一轮 | 第二轮 |
|---|---:|---:|
| ramp / hold | 64/64；60 秒零掉线、零认证变化 | 64/64；60 秒零掉线、零认证变化 |
| ramp p95 / p99 | 660.501 / 671.619 ms | 566.546 / 686.320 ms |
| 目标重连 / 精确认证 `+1` | 16/16 / 16 | 16/16 / 16 |
| 对照连接稳定 | 48/48 | 48/48 |
| 重连 p95 / 最慢 | 2,978.330 / 2,978.330 ms | 2,980.341 / 2,980.341 ms |
| thread 基线 / 峰值 / 清理后 | 59 / 64 / 62 | 60 / 64 / 62 |
| FD 基线 / 峰值 / 清理后 | 158 / 222 / 158 | 158 / 222 / 158 |
| 最大 RSS（仅记录） | 356,552,704 B | 357,601,280 B |

两轮均保持同一 systemd InvocationID/MainPID、同一 build identity 与 9/9 健康项。JSON SHA-256 分别为
`826025d3a28dafee1ad58aa89a9a53f02829691d1a6e91ae6d29d23478646f0a`、
`d704d6132e87a4adf0fbbee04c450ab7bb5f50f88b86791dd9c18103bbb1e38e`；JUnit XML SHA-256 分别为
`c2d284b32534c6fdbfa90fd58b0b7961e41035ef1a0c14de1469426b691d5c16`、
`22b0bdba81bc65eff8c1c6dbd7bc5475ab1ef6910bf127c0fadf312a367a7099`。该记录只冻结当前开发基线，不能外推
为更高连接规模或正式发布 SLO。

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
build identity，要求健康始终 9/9、CPU ticks 单调。RSS、线程、FD、load 与 p50/p95/p99 只记录，不在首轮
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

2026-08-31 使用测试驱动 `c859fab0`、服务端
`1.0.7+1796f4ec3506cd609793f7eda5301aa50dcc8c8f` 在同一测试实例连续运行两轮默认配置，结果如下：

| 指标 | 第一轮 | 第二轮 |
|---|---:|---:|
| 夹具用户 / 群 / 已确认消息 | 4 / 16 / 256 | 4 / 16 / 256 |
| 稳态成功 / 吞吐 | 200/200 / 11.533 cycle/s | 200/200 / 11.531 cycle/s |
| 稳态消息 p50 / p95 / p99 / max | 60.205 / 107.740 / 167.816 / 168.130 ms | 56.220 / 106.217 / 188.951 / 189.050 ms |
| UI burst 成功 / 吞吐 | 100/100 / 156.864 cycle/s | 100/100 / 163.717 cycle/s |
| UI cycle p50 / p95 / p99 / max | 78.995 / 140.454 / 160.873 / 162.984 ms | 81.401 / 134.037 / 145.504 / 220.202 ms |
| 新消息 scoped/global 投影 | 1/1；206.999 ms | 1/1；200.525 ms |
| 会话稳定 / 掉线 / 认证变化 | 4/4 / 0 / 0 | 4/4 / 0 / 0 |
| thread 基线 / 峰值 / 清理后 | 62 / 63 / 63 | 62 / 63 / 63 |
| FD 基线 / 峰值 / 清理后 | 163 / 163 / 158 | 163 / 163 / 158 |
| 最大 RSS（仅记录） | 401,907,712 B | 407,310,336 B |

两轮都完成 64/16/4/81/5/4 的 scoped/global/user/ordering/isolation/miss 矩阵且零失败，并保持同一
systemd InvocationID/MainPID、同一 build identity 与 9/9 健康项。JSON SHA-256 分别为
`5046ae8548fae7f16e6638c693b13ceacf7c2baf11e2a92b73b845b8af32dc15`、
`dad6f77dd708f00bc75c41e98239472b8603a4d3d29f7c00d2a6c1661b13d230`；JUnit XML SHA-256 分别为
`137d88608e853c27f3a7b802fed056abb74733f5acefeec360aa45f05a44a8cc`、
`be663dc34af5564983c22f313050f40b3503f65ee5771de9abe0b2f9d4b4063b`。

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
build identity，保持 9/9 健康和 CPU ticks 单调。

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

2026-09-01（报告 UTC 时间为 2026-08-31）使用测试驱动 `7e444902`、服务端
`1.0.7+1796f4ec3506cd609793f7eda5301aa50dcc8c8f` 在同一测试实例连续运行两轮默认配置，结果如下：

| 指标 | 第一轮 `e0600060bb96` | 第二轮 `7afa3c98cba1` |
|---|---:|---:|
| warmup / 稳态 / burst 上传 | 4/4 / 16/16 / 16/16 | 4/4 / 16/16 / 16/16 |
| 稳态 / burst 上传 p95 | 865.700 / 1,255.103 ms | 753.412 / 1,678.293 ms |
| 另一成员鉴权下载 / p95 | 36/36 / 2,326.689 ms | 36/36 / 2,109.684 ms |
| 唯一路径 / descriptor / 长度 / SHA-256 / 引用 | 36/36 / 36/36 / 36/36 / 36/36 / 36/36 | 36/36 / 36/36 / 36/36 / 36/36 / 36/36 |
| GroupFile 引用清理 | 36/36 | 36/36 |
| 会话稳定 / 重新认证 | 2/2 / 0 | 2/2 / 0 |
| RSS 基线 / 峰值 / 清理后 | 442,003,456 / 471,232,512 / 471,232,512 B | 472,412,160 / 488,796,160 / 488,796,160 B |
| thread 基线 / 峰值 / 清理后 | 62 / 64 / 64 | 63 / 64 / 64 |
| FD 基线 / 峰值 / 清理后 | 162 / 164 / 158 | 162 / 164 / 158 |

两轮都保持 InvocationID `12acf726677146e9a561bfc56c55dc48`、同一 MainPID/build identity 与
9/9 健康项。JSON SHA-256 分别为
`550e92e37ba5393a7b96a2dc116deac13027195a8a2aaa3612ba97e9f1769c8e`、
`478e5cb713e58bdaaea8075c79057b14917e28a7e1c0bf750f614c9ee651d76d`；JUnit XML SHA-256 分别为
`5b2ebf8d23a1cee463ea392fdc5328353c47f1b1216411fa1deed773d2f877db`、
`f790681039d8436659ddb8ec138104384f410f53549777612f88ab43c13bd963`。

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

2026-09-01 在服务端 `1.0.7+432c8cbaf92e98125509599bc36c0d1f20c12827` 上运行默认配置通过：
33,619,968 字节对象两次下载均得到 SHA-256
`a5b18068b45bedbdd7978bd98c93a2ca5e2627b260d6745609809dff8959e6a5`；目标大小文件在上传后由 0 增至 1，
重启和重放后仍为 1，存储字节保持 33,619,968。InvocationID 从
`650f390bdb56418f9287f9b36c93b6b5` 变为 `18ef979f149f48e1a3eb587f2c1dd8d5`，MainPID 从 2632614
变为 2633572；两个会话认证次数均精确 `1 -> 2`。业务引用清理成功，服务随后保持同一 build identity 与
9/9 健康。报告和 JUnit XML 分别保存在
`build/e2e-artifacts/epoch32/filesystem-tier-capacity-432c8cba.json` 与
`build/e2e-artifacts/epoch32/filesystem-tier-capacity-432c8cba.xml`，SHA-256 分别为
`f2605b6d8ee22bef2664135a3991fb92ca90e0adbfcc33982daa4f42601824b1`、
`d141f0e6b569898407522bcd8bbcca87391b7fc2e017508e31c5ab82bfb00ba7`。
路径中的 `epoch32` 是该次历史制品的证据目录，不是当前服务端数据基线；当前
产物与部署的 epoch/dataset 身份必须与当前服务端一致（见部署文档的数据代际纪律）。

该门禁证明的是单个大对象在当前文件系统层的流式转移、服务重启恢复和精确重放，不是并发带宽或磁盘容量
SLO；Desktop/Android 本地缓存原子发布与离线播放由下文真实双端门禁独立证明，正式发布物晋级仍归
REL-05。

### Desktop/Android 本地优先媒体门禁

2026-09-01 使用客户端提交 `ab19631c`、Desktop 全屏修复 `d03cd3ae` 和服务端
`1.0.7+c08ac4eae78a5246e1ac2ee49cb86e245a7064d5` 完成一次真实双端视频门禁：

- Desktop 冷缓存打开 5,286,805 字节视频后，0.131 秒只出现
  `media.gallery.video.downloadProgress`，4.862 秒才出现 `media.gallery.video.surface` 与控制条；下载
  进度和播放器没有同时存在。最终缓存文件精确为 5,286,805 字节，随后通过 seek、播放/暂停和原生全屏。
  macOS 全屏另以最终代码覆盖两轮正常切换、稳定后立即退出、快速反转与重复操作、系统原生退出、进入中
  关闭重开及全屏中关闭重开；系统、屏幕模式与 Skia 截图一致覆盖 3840×2160 物理屏，退出后收敛到
  `1920×1050 @ y=30` 的 Maximized 状态；
- 2026-09-02 当前 Intel macOS（x86_64）本地媒体覆盖再用一段横屏和一段竖屏视频完成真实 Desktop 上传、播放/暂停、
  seek 与完整全屏尺寸链；两段视频交替切换 12 次时当前进程始终只持有一个目标媒体 FD，各自连续开关
  4 次后目标媒体 FD 都回到零。配套 `desktopTest` 通过 8 轮视频、32 次创建后立即销毁和 4 轮纯音频
  生命周期，均以当前 PID 的精确文件路径 `lsof` 归零为释放条件；
- 小米真机冷缓存打开 23,303,457 字节视频时，14% 和 27% 两个下载采样都只有
  `media.gallery.video.downloadProgress`，语义树中没有任何 Media3 `exo_*` 控件；完整文件原子发布后才出现
  本地播放器，并通过 seek、播放/暂停和全屏交互；
- 离线夹具只执行 `systemctl stop teamtalk`，宿主机和手机的网卡、Wi-Fi、系统网络、代理、DNS 与防火墙
  全程不变。服务停止后 Desktop 在“离线”状态仍从缓存打开并可 seek/暂停，Android 无下载进度帧即进入
  本地 Media3，播放按钮语义按真实点击从“暂停”变为“播放”，离线 seek 仍有效；
- 双端最终文件大小都与声明一致，缓存目录没有遗留 `.part` / `.partial`。验收后 TeamTalk unit 已恢复
  active，`/health` 的 9/9 项全部为 UP。证据保存在 `build/acceptance/file12-local-first/`。

这些结果证明当前应用代码不存在服务器 URL 在线拉流旁路，并覆盖了冷缓存发布顺序、离线缓存命中和 Intel
macOS 本地媒体 FD 释放；它们不把开发运行冒充正式发布物，也不扩张为新的 Android 或 Apple Silicon 结果。
Compose 的 `createDistributable` / `createReleaseDistributable` 已在 x86_64 生成未签名 `.app` app image，
但尚未用 Conveyor 生成可分发安装包或更新站点。REL-05 仍需用未经重建的同批 Desktop/Android release
artifacts 重复门禁，并完成首个正式发布物、签名、公证、项目许可证随包和第三方归属审计。

### Desktop/Android 本地 SQLite clean-close 门禁

2026-09-02 使用提交 `35a700ec` 与测试加固 `a15d3c5a` 完成 LocalCache clean-close 验收；同批完整
Gradle 门禁共 213 个 task 成功。真实客户端证据分别为：

- Desktop 当前账号数据库实际为 `journal_mode=delete`。macOS 原生红色关闭键只隐藏到托盘，不是进程
  clean exit；使用标准 `Cmd+Q` 退出后，账号库 `quick_check=ok`，目录没有 `-wal` / `-shm`，重新启动后
  会话输入框精确恢复退出前的草稿。该结果只证明标准退出、driver 关闭和可靠事实重开，不声称 Desktop UI
  命中过 WAL；
- Android 使用 USB 小米 `2312DRA50C` 验收，APK SHA-256 为
  `cff8f02668738b35b98d83c77ca62a322b6b2d7666fe87f406d65fe9c56cd343`。Activity 运行期间当前账号 namespace
  存在 `.open` marker；执行真实系统 Back 结束 Activity 后 marker 删除。复制出的数据库
  `quick_check=ok`，再次启动后聊天输入框恢复退出前草稿。

`PRAGMA wal_checkpoint(PASSIVE)` 的 WAL 行为由 xerial SQLite 真实文件测试覆盖：独立 reader transaction
钉住旧 snapshot 时 clean close 有界返回并留下未 checkpoint 的 frame；释放 reader 后新 driver 精确恢复
草稿与 outbox。这个确定性证据与上面的真实客户端退出/重开证据互补，不能互相冒充。

部署链本身还必须证明：半安装目标和并发部署被拒绝；升级前分发在 live 目录外完成校验；systemd 停止后
MainPID/cgroup 确实清空；新 build identity 与 9 项健康检查全部通过才提交。故障注入验收应至少覆盖一次
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
最终仍须断言 9 项健康状态和精确 build identity。fixture 数据丢失是此验收的预期结果，不应用真实备份
恢复测试替代。

## 传输安全验收门槛

当前 HTTPS + TLS/TCP 部署的发布验收须收集以下证据。结果只证明被测传输组合，不能只因代码或
本地测试存在就标记为真实部署已通过：

1. IM TCP 只协商 TLS 1.2/1.3；客户端使用系统 WebPKI，并严格校验 `tcpAddress` hostname 与 SNI。
2. TLS handshake 成功前客户端不发布 `CONNECTED`、不发送 AUTH；错误主机名、不受信证书和黑洞
   handshake 都只能进入断线/重连路径，报告中不得出现凭据。
3. 被测 TLS 监听器不接受明文协议帧，SDK 在 TLS 失败后不回退明文。另行配置的明文监听器是不同
   运行模式，不能从这项结果推断它拒绝公网绑定。
4. 被测 HTTPS 安装只启用 HTTPS connector；当前 SDK 的远程认证 HTTP 基址使用 HTTPS。
   文件、群机器人和日志通道不把 3xx 当成功，也不跟随重定向转交凭据。
5. 部署生成的 `TCP_HOST/TCP_PORT`、实际监听和 `tcpAddress` 一致；`/health` 的 `tcp` 项以当前 keystore
   叶证书作为唯一信任锚完成真实 TLS handshake，而不是只验证 socket 可连接。

HTTP 可选、自签 TCP 与客户端信任的完整组合仍归路线图 [REL-03](../10-reference/roadmap.md)；
实现后须为该组合补充对应验收，不能由现有 TLS 模式的结果替代。

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

默认 30 天保留不能靠每次验收真实等待 30 个墙钟日。预发布可丢弃实例允许使用时间加速夹具，但夹具
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
该 build/deployment 还必须保留至少一轮第二次服务启动后的同一 floor。已通过基线分两轮覆盖 Desktop
`12 → floor/base 15 → 第二次启动仍为 15 → tail 16–18 → 18`，以及 Android
`28 → floor/base 32 → tail 33–35 + 已读事件 36 → 36`。

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

阶段一不把以下能力伪造成已验收：跨进程/跨设备富资产聊天草稿、持久本地源文件并断网续传的附件 outbox、
Android 文档拖放。上传失败的就地重试/取消以及回复消息内嵌资产按各自后续门禁验收。

### 聊天可视光标内嵌资产双端门禁

2026-09-02 在提交 `c24fed23` 上，Desktop 与 USB 小米 Android 都以 `before  after` 为可视草稿，用真实
指针/键盘事件把选区放在两段文字中间，再经各自系统 picker 连续导入一张图片和一个 Markdown 文件。
两端导入后都仍是可视编辑器，切到源码后都得到连续且顺序正确的两个 `teamtalk-asset://asset/<uuid>`
引用。发送后气泡子节点依次为前置文字、图片、文件和后置文字；离开会话再进入后，两个资产节点及顺序仍在。

该记录只关闭“聊天可视光标插入”这一切片；不替代文档编辑器门禁，也不证明跨进程草稿、本地源文件
spool/outbox、断网续传或失败重试的跨进程恢复。

### 文档可视光标内嵌资产双端门禁

2026-09-02 在提交 `2b5e57d6` 上，Desktop 与 USB 小米 Android 都以 `before  after` 为可视正文，先用
真实指针/方向键及字符探针确认光标位于两段文字之间，再经 macOS 与 MIUI 的真实系统 picker 连续导入
一张横图和一个 Markdown 文件。两端导入后都仍是可视编辑器；源码依次为前置文字、图片 URI、文件 URI
和后置文字。Desktop 保存为版本 1 后关闭标签并从文档树重开；Android 保存为版本 1 后进入预览、返回
目录并重开。两端最终都保留“文字 → 图片 → 文件 → 文字”的顺序及 READY sidecar。

实现回归还覆盖：READY 紧邻最近 250 ms 输入时先同步捕获当前富文本；资产 sidecar 触发 block list 重建
后投影绑定新列表；controller 尚未挂载时 LOCAL/READY 保序重放；预览或切页销毁时按块边界同步写入草稿，
且后续 READY 不读取已卸载 controller 的旧快照。该记录关闭文档 WYSIWYG 光标插入切片，但不证明
跨进程草稿、本地源文件 spool/outbox、断网续传、失败重试的跨进程恢复或 Android 文档拖放。

### 回复消息内嵌资产双端门禁

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
创建顺序。离线夹具只停止目标 TeamTalk 服务，宿主机网络、手机网络、代理、DNS 与防火墙均未改变：

- Desktop 分别完成离线 rename 和跨 parent move，正常退出再启动后仍显示待确认结构命令、缓存树和对应
  草稿；Android 完成同样流程，并以 force-stop/relaunch 验证进程边界；
- 服务恢复后，两端 outbox 自动收敛，旧/新分支、标题、父级、revision 与 path spine 一致。重命名时一并
  持久化的正文草稿继续保持 dirty，并由用户在结构命令收敛后显式再次保存，没有伪装成结构 ACK 的附带写入；
- move 完成后再次只停止 TeamTalk 服务并重启客户端，两端都能从缓存进入空间、展开目标父级并读取移动后
  文档；Desktop 首次回归发现“标签恢复但树为空”，`7c017d15` 修复为先发布缓存 root/spine、再独立校验正文；
- PostgreSQL 验收观测中，Android 对同一节点刻意发出的 rename 与 move 使 receipt 行数从 2 增至 4；此前
  Desktop move 节点在后续重启与恢复中始终保持 1 行。最终干净制品冒烟中的 Desktop/Android rename 又各
  增加 1 行，和用户实际发出的结构命令数一致。这是数据库旁证，不把停服场景包装成 ACK-loss 故障注入。

真实 UI 门禁证明的是“首个 RPC 前服务不可用”时 durable outbox、pending 提示、跨进程恢复与服务恢复后的
最终收敛。服务端已经提交但客户端丢失 ACK 的 exact receipt replay、同 identity 不重复推进 revision，则由
协议、临时 SQLite 和 PostgreSQL 确定性自动化覆盖，两层证据不能互相替代。Desktop 截图保存在
`build/acceptance/content04-reliable-mutation/desktop/`，Android 截图保存在
`build/acceptance/content04-reliable-mutation/android/`；该 `build/` 目录为本机忽略的验收产物，不作为
长期版本控制记录。
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
覆盖。任何非当前版本客户端都必须在认证时被当前协议服务端明确拒绝，不能进入组织、文档或会话 RPC
后猜测语义。

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

测试部署只清理本任务明确创建的测试资料，普通升级保留内测用户数据。项目尚未发布仍不保证对外兼容，
但内部遵循[版本与迁移规则](../04-protocol/versioning.md)；破坏性重建必须另有明确实例与数据范围授权，
并记录影响和恢复方案，不能用清库掩盖兼容缺陷。
