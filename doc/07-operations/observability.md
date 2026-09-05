# 可观测性

## 1. 观察层次

| 层次 | 信号 | 回答的问题 |
|---|---|---|
| 构建 | release/build identity、build time、协议版本 | 实际运行的是什么 |
| 组件 | `/health` | 存储、索引、文件和 TCP 是否可用 |
| 服务端业务 | 主日志、策略命中的连接 trace | 请求在哪一步失败 |
| 客户端 | trace、fault、crash dump | 哪个设备/状态/事件出错 |
| 验收 | acceptance report | 用户旅程是否真正闭环 |

单个绿色信号不能替代其他层次。例如 `/health` 成功不能证明好友事件 payload 正确。

## 2. 健康检查

`GET /health` 汇总 PostgreSQL、MessageStore/RocksDB、Lucene、FileStore、TCP 监听以及
`sync-event-dispatcher`、`message-projection`、`managed-chat-projection` 三个 durable runtime /
projection readiness。所有关键组件 UP 才返回 200。响应同时包含从 Server artifact 内嵌 manifest
读取的完整 `buildIdentity`；部署探针必须检查 HTTP status、结构化 component 和与 staged artifact
完全相同的 identity。
`client-telemetry` 作为低价值、可丢失诊断子系统仍独立报告 UP/DOWN、retention backlog 与 overdue，
但不参与核心 IM overall readiness；它故障时聊天、组织、文件等业务继续承接流量。
TLS 实例的 `tcp` component 不做普通 socket 冒烟：探针以当前 keystore 叶证书作为唯一信任锚，对由
`TCP_HOST` 派生的本机连接地址和 `TCP_PORT` 完成一次真实 TLS handshake。它证明本实例正在呈现预期
叶证书，但客户端侧仍须以系统 WebPKI 验证完整链、hostname 与 SNI。显式 loopback 明文测试实例才
只检查 socket 连通性。

`sync-event-dispatcher=DOWN` 表示首次 durable scan 尚未成功，或运行期 worker 已不可恢复地终止。
启动门禁会阻止前一种状态的实例开放服务；后一种状态用于触发摘流和重启。component detail 只返回
固定生命周期描述，不返回内部异常类型、异常正文、SQL 或连接信息；具体根因从服务端日志查询。

`message-projection` 与 `managed-chat-projection` 的公开 detail 只描述固定生命周期阶段，不回显
projection key、unit/revision/attempt、异常正文、SQL、路径或连接信息；具体失败上下文只进入服务端日志。
`message-projection=DOWN` 后由 Application maintenance 每 5 秒独立重试持久 outbox，不依赖新的
消息请求进入实例；只有全局 outbox 连续两次为空且 generation 未变才恢复 UP。
`managed-chat-projection=DOWN` 表示至少一个组织受管群的 desired revision 尚未应用。此时相关聊天权限主动拒绝，不能把旧成员投影视为
可降级数据。运行期会按持久退避每 5 秒扫描已到期任务；只有 pending 清零才恢复 readiness。若启动时
完整 drain 仍未收敛，服务会在开放 TCP 前失败，避免带着旧权限投影对外提供服务。

Lucene 在每次生产启动时先与 MessageStore 做完整审计。主日志中的 `VERIFIED` 表示活动索引的 live
条目及全部当前派生字段与权威消息一致，`REBUILT` 表示检测到缺失/多余/revision 或字段差异后已从权威
消息重建并完成 side-directory 原子发布；两者只记录权威消息数与扫描编码字节数，不记录正文。审计、
重建、completion marker、原子 rename 或崩溃残留收敛任一步失败都会阻止 SearchIndex 进入 running，
因而实例不会开放 TCP/HTTP，也不会用“空搜索结果”掩盖索引丢失。

精确路径 `/health` 不占用共享 HTTP 阻塞执行器，避免执行器已经饱和时探针也被一起拒绝。该例外的
路由解析、进程内原子状态读取和有界响应构造留在非阻塞调用线程；PostgreSQL、managed-chat projection
和 TCP transport 探针都在显式可中断的 IO 边界内并行执行，各自保留独立超时，因此一次新鲜检查的总等待
取最慢探针而不是三者相加。并发请求只共享当前正在执行的一次检查，完成后立即清除单飞状态、不缓存
readiness 结果，因此状态变化后的下一次请求不会读到过期 UP/DOWN。等待者取消不会取消共享检查，
刷新发起者的 caller cancellation 仍原样传播；实现不创建后台任务。

健康检查不执行注册或发送消息，不能作为发布验收。

## 3. 服务端日志

主 logback 日志写控制台与滚动文件：

- `teamtalk.log`：启动、HTTP、领域错误和系统状态。

连接轨迹不再写独立日志文件（`traces/trace.log` 通道已删除）：服务端 Recorder 轨迹的唯一
落盘通道是遥测的 Lucene `connection-trace-index`，与客户端事件流经同一套定向诊断策略
同开同关；分析问题时用管理端的五字段上下文联查，把客户端与服务端轨迹放在一起看
（见「客户端遥测」一节的联查入口）。

Recorder 在 AUTH 前最多缓存 30 条未求值的有界条目；只有服务端查得当前 uid/deviceId 的
有效 DIAGNOSTIC 策略后才申请诊断 writer 并释放该缓存。BASELINE、过期、全局 writer 容量拒绝或存储
故障都不得扩大采集；trace 经专属有界队列非阻塞投递，满载时丢弃最新条目，不向协议主路径施加背压。
运行时累计诊断 writer 准入拒绝、两级队列满载、释放后迟到、过期、事件/字节预算、不安全 detail 和投递失败计数。
固定 phase 为 `CONNECTION / AUTHENTICATION / POLICY / RPC / SYNC / EVENT / MESSAGE / HEARTBEAT / SHUTDOWN`，
outcome 为 `STARTED / SUCCEEDED / REJECTED / FAILED / DROPPED / CLOSED`。BASELINE 或未命中连接的昂贵 detail 不会求值。

## 4. 客户端遥测

客户端不再上传按天拼接的自由文本文件，而是发送有版本的结构化事件：`FAULT`、`USER_NOTICE`、
`MEDIA`、`SYSTEM`、`PAGE_DWELL`、`ACTION`、`OUTGOING_QUEUE` 和兼容期 `LOG`。用户看到的 toast/snackbar 来自有限的
`feedbackCode → publicMessage` 词表；同一次展示把相同 code、文案、页面、动作和展示方式写入
`USER_NOTICE`，不能只把底层异常留在 UI。附件事件只记录类型、动作、结果、字节数/耗时和稳定原因码，
不记录文件名、路径、URL、消息正文或底层异常文案。

关键办公动作由业务状态机向 typed sink 提交 `STARTED`；当前协议的 `ACTION` 结果集合为
`QUEUED / SUCCEEDED / FAILED / CANCELLED`。同一交互尝试恰好提交其中一个结果，不能把按钮点击当成
成功。`QUEUED` 表示可靠命令已写入本地持久 outbox、当前交互可以结束，但服务端业务结果尚未确认；它
不是 `SUCCEEDED`，后台重放的最终确认或拒绝也不能回写、篡改已经发送的遥测事件。每个 `ACTION` 由当时策略独立准入，
所以服务端不保证同时看到起止两端；策略切换、到期、页面或 owner 退役都可能只留下其中一端，不得为此
伪造业务取消或成功。Android 已知的同步清理失败须在提交最终事件前合并；旧上传器退役后才完成的异步
草稿 flush 仍绑定旧 sink，允许不落盘但绝不能改记到新账号。消息发送以持久 outgoing 收据为终态，
已读以 SQLite 会话投影达到目标水位为终态；建群、邀请链接和文档保存以对应的权威业务结果为终态；
群文件发布或追加版本只有直接 ACK 才记 `SUCCEEDED`，已持久排队则记 `QUEUED`。事件只带稳定页面、
动作和结果枚举，不携带消息正文、文件名、文档标题或业务对象 ID。

每个事件带有 run 内单调 sequence 和稳定 eventId；每批还带平台、系统/架构/设备型号、客户端版本、
build number、git commit、build identity/time、协议版本和发行渠道。uid 与 deviceId 不由客户端正文提供，
只取 Bearer 对应的服务端权威身份。客户端记录路径只做内存准入和封批，磁盘写入、GZIP 与 HTTP 都由
会话独占的有界 IO worker 串行完成，不阻塞 Compose Main。正常会话收尾会尽力封批并落盘；离线或上传
失败后，只有已成功持久化的 immutable batch 才保证留在按
`deployment fingerprint + datasetId + uid` 隔离的 spool，后来的失败不会覆盖更早的未确认批次。系统
强杀或收尾 flush 失败不保证内存事件落盘；未捕获异常仅在 crash boundary 成功执行时另留固定 marker。
服务端只对精确 batchId/末尾 sequence 的 ACK 删除对应 segment。spool 使用七日保留窗口，并在下一次
启动、上传或本地维护时裁剪超期 segment，不承诺闲置安装在第七日准时物理删除；文件数和总字节另有
硬上限，高优先级故障可在空间耗尽时淘汰较旧的低优先级批次。遥测本地存储初始化属于 best-effort
边界：普通文件系统故障只禁用当前会话遥测，取消和 VM-fatal 仍按原对象传播，本地账号、缓存和离线
启动不得依赖遥测可用性。

每个客户端安装只在专属遥测根维护 V2 私有 registry：最多登记 16,384 个固定三层哈希身份，序列化内容
最多 3 MiB，正文和动态业务 ID 不进入 registry。构造 store 和每次 segment 发布都会在进程锁与文件锁内
登记当前身份并刷新 marker；旧空间维护在同一锁边界内完成 registry 一页扫描、精确快照删除与状态发布。
registry 达到硬上限时不驱逐任意身份，也不借新账号继续写入；新身份的当前会话只会禁用遥测。随后只有
再次打开某个已登记身份时，该 store 才能按持久 cursor 继续有界维护；若安装只再使用全新身份，则该安装
的结构化遥测保持禁用。该上限是明确的 fail-closed 容量边界，而不是静默淘汰或保证自愈的策略。
单次维护最多访问 4096 个节点，清理数量另有 32 个硬上限；按每身份最坏 261 个节点预留，当前默认一页
最多检查并选择 15 个 identity 级清理。在跨 namespace 维护前置条件满足时，持久 cursor、整个周期最早
deadline 和立即重试标志可避免大量短会话反复只扫描首页。已过期 segment 即使 namespace 仍活跃也可
删除；只有整个 namespace 的最新保留依据过期时才删除目录。写入前会重新登记并刷新 marker；仅在
registry 仍接受该身份时，存活会话才能复活已清理 leaf，达到硬上限仍按 fail-closed 处理。

Desktop 持久 data root 与 Android app-private data root 的架构前置条件是经验证的本地持久文件系统；
网络盘、FUSE、external/SAF 或语义未知 provider profile 整体不受支持。scanner 只对安全目录句柄、稳定
file key 和 force 调用结果做 fail-closed 检查；文件系统及其断电语义由 profile 配置和验收保证，运行时
不声称能识别任意 provider。跨 namespace 删除要求 `SecureDirectoryStream`、目录和文件均有稳定
file key，且目录 force 实际成功。每次实际删除子节点后都 force 仍存在的父目录，空父目录的压缩再逐层
向上重复。只有 leaf 已删除并完成持久缺失证明后才移除 registry 身份；进程在删除与状态发布之间退出时，
已登记身份作为 tombstone，在下一次满足条件的已登记 store 维护时重新 force 并证明缺失。
受支持的 Windows 本地 profile 缺少跨 namespace 所需能力时保守跳过回收，但当前身份自身的 segment
七日窗口、文件数和字节上限仍生效。未登记节点、链接、陌生文件、非空且缺 marker 的异常 leaf、变化中
的快照一律保留。除按已登记且验证稳定的快照删除过期 marker/segment 外，崩溃残留回收仅允许删除
固定名称、经 owner/mode/link/fileKey 验证的私有普通 pending 文件，以及稳定且完全空的已登记
markerless leaf；旧 namespace 正文永远不由新会话读取或重新归属。

默认 `BASELINE` 只上传结构化 fault、审核词表中的用户提示、服务端白名单内的关键连接状态和失败媒体操作。
这不是客户端可自行声明的信任级别：服务端会再次核对事件类别、稳定 code/state/outcome，并用审核词表文案
替换客户端自报提示；fault 的自由摘要只在 `DIAGNOSTIC` 保留。所有 runtime、稳定名称和诊断文本在 PG/Lucene
边界前分别按字段语法和隐私规则归一化。管理员可以按 uid、deviceId 或手机号定位目标，并对一个用户或
单个设备开启最长 24 小时的 `DIAGNOSTIC`；手机号只在管理服务边界解析为 uid，不复制到设备资料、事件
或全文索引。客户端通过普通上传响应和空 heartbeat 获取最新策略，策略过期后本地和服务端都自动回到
BASELINE。DIAGNOSTIC 才接收 trace、页面停留、点击、普通系统事件和成功媒体操作；两种模式都受每分钟
事件数、每日编码字节和单批事件数预算约束。

`OUTGOING_QUEUE` 是严格的 `DIAGNOSTIC`-only 数字快照，固定事件名为
`outgoing.queue.snapshot`。它只包含 pending/in-flight、retry-wait、terminal-failed 三个互斥状态
计数，以及最老 active 项年龄和所有非成功项的最大 attempt 数；不接受消息正文、路径、业务对象 ID、
metadata 或任何客户端自由文本。pending 与 retry-wait 各自受界并共享 active 总量上限；只有
`pending + retry-wait == 0` 时最老 active 年龄必须为零，只有三类计数全为零时最大 attempt 才必须
为零，所以 retry-only 与 terminal-only 都是合法快照。服务端以固定展示文案和五个 typed Lucene
数字字段保存，不能从全文字段反向解析。管理查询可分别使用 `pendingCountMin/Max`、
`retryWaitCountMin/Max`、`terminalFailedCountMin/Max`、`oldestActiveAgeMillisMin/Max` 和
`maxAttemptCountMin/Max` 做有界闭区间筛选。

客户端收到新的 DIAGNOSTIC generation 时会唤醒原 BASELINE 长周期计时器，先记录当前持久队列快照并
立即触发上传，不等待旧周期结束；切回 BASELINE 只重载周期，不额外制造诊断上传。Recorder 在封存前
按 BASELINE 可接纳性切分批次，诊断专属事件不会和故障、用户提示等基础事件混装。持久 spool 的最老
诊断批次若因策略已过期收到 HTTP 403，只有本地 `batchId + encodedJson` 与本次拒绝内容精确一致时才
删除并在同一轮继续后续批次；401、409、429、5xx 或内容不一致均保留并失败关闭，避免误删、连带丢失
基础事件，或让一条已经永久不可接纳的过期诊断批次长期阻塞后续基础遥测。

进程全局 AppLog 仍只有一个固定 owner 快照；buffer、遥测 recorder、fault 触发器与 crash owner 原子
轮换并以 identity CAS 释放。旧会话的迟到任务不能借用新账号的 token 或 recorder。认证前连接树和
禁用上传的 headless 会话只写平台诊断。未捕获异常的同步崩溃边界仅原子持久化一个不含异常正文/堆栈的
固定 marker；同一 owner 下次启动时再转换为结构化 fatal。远程上传基址必须是 HTTPS，且不跟随重定向；
明文 HTTP 仅允许严格字面量 loopback 测试地址。

服务端 `POST /api/client-telemetry` 只接受有界 GZIP JSON：压缩体最多 1 MiB、解压后最多 8 MiB，严格
UTF-8/结构校验、时间窗校验、身份与入口速率门禁后进入单一有界 Lucene writer。writer 在短窗口内合并
多个上传，以一次 commit 原子发布每批 receipt 和全部 event；只有 durable commit 成功才 ACK，重复的
batchId + payload hash 可直接幂等确认，冲突内容固定拒绝。事件索引是 7 日、可丢失的诊断存储，不再
复制进 PostgreSQL；单 writer 在写入前封闭 batch/event 身份、顺序与容量不变量，启动只按 commit、
计数、tombstone 和逐 segment 固定字段结构做有界校验，不扫描正文或倒排词典；结构异常或 schema marker
不兼容时清空为空。PostgreSQL 只保存低频设备画像、诊断策略和策略审计。设备画像与 exact-device 策略
绑定当前认证安装 generation，撤销或回收安装时一并删除，旧 epoch 的迟到 refresh 不再创建画像；
uid-wide 策略保留。管理后台支持按关键词、uid、deviceId、手机号、平台、版本、commit、类别、事件名和时间
查询，并可查看设备资料及启停诊断策略。服务端按 `receivedAt` 精确保留 Lucene 中最近 168 小时，因此
用户规模增长不会再产生 `uid/device/date` 文件树，也不会为日志制造关系库事件流量。

当前实现明确以单实例为边界：写队列最多 128 个命令并受 64 MiB 待写字节预算约束；索引最多 200 万
文档、8 GiB 逻辑计费字节和 16 GiB 实际磁盘字节。删除段尚未被 Lucene 合并回收且触发物理压力时，服务端
可以清空整座可丢遥测索引后恢复写入，不能让日志挤占聊天事实存储或耗尽进程。多实例共享检索、跨节点
容灾、长期留存或大规模聚合分析不是这个本机索引的目标；出现这些要求时应在现有 event-store 端口后接
专用日志/列式系统，而不是把高频事件改写进业务 PostgreSQL。
初次 Lucene open/reset 失败由 maintenance 每 5 秒重试；writer 运行期终态会立即关闭准入并要求进程
重启，不在已关闭 channel 上原地恢复。PG 策略到期清理与 Lucene 物理 TTL 独立执行，控制面故障不能
延误 168 小时前 stored text 的删除。

管理员开启 DIAGNOSTIC 后，当前认证 TCP 连接会获得服务器签发的
`correlationId + traceId + sessionId + connectionGeneration + policyRevision`。客户端在事件创建时冻结该
上下文并随遥测事件上传；服务端 Recorder 只在同一策略仍有效时采集，策略关闭、到期、重连或代际变化
都会停止旧 writer。服务端轨迹只包含固定 phase/outcome 和经过字段白名单处理的短 detail，不保存任意
Throwable message、原始 payload/body、token、URL 或路径。

服务端全局最多同时持有 100 个活动诊断 writer。轨迹先进入容量 1024 的 trace runtime 非阻塞队列，
每个物理连接最多 4096 条/1 MiB；随后进入独立 `connection-trace-index` 的容量 4096 条/16 MiB
非阻塞队列，单事件最多计 16 KiB，detail 最多 512 字符，写盘由单线程完成；
索引最多 100 万文档、2 GiB 逻辑计费和 4 GiB 实际磁盘，保留
168 小时。队列、容量或磁盘失败只旁路诊断，不能阻塞消息链路；schema/commit/FieldInfo 损坏时可清空
该独立索引。管理端以精确客户端事件 record id 调用
`GET /api/admin/telemetry/events/{eventRecordId}/connection-traces`，再以事件权威 uid/deviceId、五个上下文字段和时间窗联合过滤，
每次最多返回 200 条并显式标记 truncated，绝不把旧连接代际混入结果。事件搜索、该联查以及策略启停都从已验证 admin principal 取 actor，并把
actor、动作、稳定目标、成功/空/拒绝/失败结果和时间写入 PostgreSQL；该管理访问审计不记录关键词、手机号、reason
或任何 trace token。

这五个上下文字段由服务端签发，但作为遥测正文由客户端回传，因此只是非权威的关联提示。服务端只信任 Bearer
固化的 uid/deviceId，所以篡改上下文不会跨账号或跨设备命中；同一账号和设备可以篡改或重放自己的遥测，故结果不能用作
事件真实性、完整性或因果关系的密码学证据。

## 5. 诊断键

日志需要包含足够关联键，但不能包含 secret：

- uid、deviceId（必要时脱敏）。
- chatId、clientMsgId、serverSeq。
- requestId、serviceId、methodId。
- eventId、NotifyType。
- attachment size、contentType 和稳定媒体原因码；不记录 path/filename/URL。
- commit、build time、protocol version。

禁止记录密码、access/refresh token、管理口令、完整私钥和敏感消息正文。

## 6. 日志保留与容量

默认主日志按日期和大小滚动。Desktop 本地主日志保留 7 日，单 segment 8 MiB、目录 64 MiB，并在
进程首次写日志时立即清理，不依赖跨日轮换。服务端 Lucene 遥测日志精确保留 168 小时；管理
后台事件、设备和策略查询都有有界分页。服务端文件日志 tail 只读取末尾最多 2 MiB/2000 行。RocksDB 与 FileStore
容量统计对每个根目录最多访问 100000 个 entry；`storageScanTruncated` 表示 byte 数只是已扫描下限。
正式部署应按磁盘、隐私和故障发现时间调整，监控：

- Lucene 遥测文档增长、168 小时清理 backlog、group-commit 队列深度/延迟和拒绝数。
- 遥测 413/429/409/403 响应、客户端 spool pending/dropped 数和策略过期回落。
- 临时上传目录增长。
- fault/notice 上传失败与 spool pending 数。
- Lucene 和 RocksDB 写错误。
- 消息 Lucene 启动审计的 VERIFIED/REBUILT 结果、权威条目/编码字节，以及任何阻止启动的 side/backup 收敛失败。
- 客户端遥测索引的结构重置、运行期 terminal DOWN、168 小时清理 backlog 与 overdue 状态。
- 连接轨迹的 active diagnostic writer、准入/两级队列拒绝、释放后迟到、过期/事件/字节预算、旁路/投递失败、独立索引容量与 168 小时物理清理。

## 7. 排查顺序

1. 记录用户、设备、时间、构建和目标实例。
2. 用 health 排除组件整体故障。
3. 用 clientMsgId/requestId/eventId 串联客户端与服务端日志。
4. 检查权威存储是否写入，再看事件/索引/客户端缓存。
5. 用真实验收或最小操作复现。

按症状的具体步骤见[故障排查](troubleshooting.md)。
