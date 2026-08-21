# 客户端与 SDK 架构

## 1. 分层

```text
Platform shell（android / desktop）
    └── app：Compose screens、ViewModel、AppDataState
          └── shared：ClientSession、Repository、LocalCache、ImBot
                 └── protocol：wire、模型、消息体、RPC 契约
                └── TCP/HTTP
```

`shared` 是完整的无 UI 客户端 SDK，并通过 `protocol` 使用跨端契约。`app` 只消费 SDK 公开能力；
平台壳决定窗口、导航、权限和系统集成。
ImBot/Agent 可以直接依赖 `shared`，不加载 Compose。

## 2. 会话组装

认证成功后，`createSession` 为当前用户建立一个拥有明确销毁边界的 `ClientSession`：

```text
UserSession（uid/token）
 └── ClientSession
      ├── ImClient / RpcClient
      ├── LocalCache（按 uid 隔离）
      ├── EventProcessor
      ├── Repositories
      ├── GroupBotManagementRepository（session-owned HTTP）
      ├── CrashDumper / HttpLogUploader
      └── ViewModels（由 app 层持有）
```

平台的 HTTP 与媒体资源同样必须归属这次认证会话，不能通过进程全局 token 补参数。Desktop 在
`ClientSession` 外侧建立同寿命的 `DesktopSessionResources`：固定 owner uid，逐次从当前
`ClientSession` 的生命周期门禁凭据入口读取可轮换的 access token，并在每次请求前同时校验 uid 与
identity epoch 仍属于原认证会话。这样同 uid 的正常 TCP 重连/令牌轮换可以继续工作，而 logout 后
同 uid 重登也不能复用旧 HTTP owner；退出后复用同一个 `UserSession` 容器登录另一账号时，旧
下载或上传任务无法取得新账号凭据。认证会话销毁会统一取消平台协程、录音与传输任务；`close()`
同样必须幂等。平台异步任务的诊断 logger 也必须从 `ClientSession` 取得固定 owner 快照，再由平台会话
资源增加独立 close gate；不得在任务内读取进程全局 `AppLog`。Desktop 诊断只接受预定义的脱敏事件，
不记录附件引用、本地路径、文件名或底层异常文本，避免退出重登后串入下一账号日志或上传敏感元数据。

HTTP 附件上传由会话拥有的 `FileRepository` 统一协调。Repository 固定 `server + owner uid`，每个请求
从同一 `UserSession` 读取一份原子凭据快照；上传内容实现 common `UploadSource`，声明已知长度并把
分块写给平台 sink。JVM 与 Android 只负责固定长度 multipart 的平台传输，不接收整块大文件
`ByteArray`。只有不超过 1 MiB 的明确小 payload 可以使用内存便利入口；Repository 关闭会断开正在
执行的 HTTP 连接，关闭或取消之后不再发布进度和结果。

群机器人管理的 HTTP Repository 同样是 `ClientSession` 的唯一资源，不由页面临时构造。它固定
`server + owner uid + identity epoch`，每个请求读取同一 `UserSession` 的最新原子凭据，任一 owner
字段变化立即失败；quiesce
会先关闭发布 gate，再断开所有活跃连接，因此迟到响应不能回写退出后的页面状态。

会话终止采用两个不可逆阶段。`quiesce(reason)` 先拒绝全部新业务，停止发送队列、事件消费、HTTP
Repository、日志上传和本地缓存；reason 明确区分用户退出、认证撤销、进程 owner 替换、协议升级与
应用关闭。用户主动退出时，原始 `RpcClient` 不通过 Repository 或 session getter 暴露，只在
`USER_LOGOUT` quiesced 状态下签发一次性 retirement capability；该 capability 至多发送一次 logout
RPC，并在 `finally` 中至多执行一次 full close。页面先退出认证 UI，再尽力完成它；随后按 transport
owner generation 决定是否断开 TCP。其他终态直接从 quiesce 进入 close。每个资源按 best-effort
释放，单项 close 或 TokenStore 清理异常不得阻断后续资源、UI 终态、RPC stop 或 transport disconnect。
`createSession` 的组装本身也是事务：cache、worker、HTTP transport、AppLog、RpcClient 与事件 binding
每取得一个 owner 就登记逆序 rollback，只有完整构造后才移交给 `ClientSession`；构造失败由组合根
继续清空内存身份并断开不属于 construction stack 的 `ImClient`。

业务 RPC、消息/typing、ACK 注册和事件同步控制各持有不可复活的 session admission。quiesce 在发布
`QUIESCED` 前同步退休业务 wire 与 sync wire；EventLoop 在 admission 锁内完成“校验 owner generation
→ 注册 waiter → 实际 write”，退休会等待已经准入的实际 write 退出，返回后不可能再向替代账号写包。
`SYNC_REQUEST` 的初次、分页与 reset 三个出口以及 `SYNC_READY` 都经过同一 sync admission；异步移除
binding 仅用于回收，不承担安全边界。

LocalCache 与所有已捕获的 `MessagePager` 共享同一个 `CacheUseGate`。固定锁序从 cache lease 开始；
close 取得独占 lease，等待已准入的同步 DB 操作退出，推进历史代次并关闭 driver，之后 ACK、cursor、
draft、history page 或 pager 的任何迟到写入都会在 SQL 前失败。SendQueue 回调和 EventProcessor 的
cache/SharedFlow/cursor 发布另带不可复用的停止代次，协程 cancel 只负责回收，不作为安全边界。

发送队列不是会话内存列表：`enqueue` 返回前必须把规范化后的最终 wire Message、单调
`localOrdinal`、状态、尝试次数与退避时间提交到账号 SQLite。worker 只 claim 最老的非终态 ordinal；
最老项仍在退避时不得越过它发送后续项。成功 ACK 的 message 序号/状态与 outbox `SUCCESS`
回执同事务提交；回执按完成时间有界保留，worker 不再 claim。`SUCCESS` 只是幂等查询凭据，绝不用其
初始 payload 合成或覆盖已确认消息；server echo/history replay 是 `SENT` 正文、时间戳、flags 和附件
元数据的唯一权威来源。如 echo 先于 ACK 到达，匹配 sender 的 outbox 会在同一投影事务中自愈为
`SUCCESS`，且不改写服务端字段。
超时、断线、负码与服务端 5xx 保留同一 `clientMsgId` 退避重试；4xx、ACK 身份错配和无效成功 ACK
才进入保留诊断的终态失败。
`USER_LOGOUT`/`AUTH_REVOKED` 以 CANCEL 关闭队列，其他 owner 替换或进程关闭以 PRESERVE 留给同账号
下次恢复；固定 uid、cache namespace 和 transport owner lease 共同保证旧账号不会借新 token 发送。
启动恢复还会把没有 matching outbox 的 `serverSeq=0` SENDING/QUEUED/UPLOADING 投影标为 FAILED，
保留气泡用于诊断或手动重发，避免上传中或兼容直发路径崩溃后永久转圈。

`app` 在 `ClientSession` 之上建立一个会话级组合根，而不是再造一个包含全部业务的“超级
ViewModel”：

```text
AppDataState（组合、生命周期、页面数据分发）
 ├── ConversationViewModel / ContactViewModel / ChatViewModel
 ├── AccountFeature（资料、好友申请、黑名单、设备）
 ├── GroupFeature（群设置、成员、邀请链接）
 └── DiscoveryFeature（搜索、发起单聊、转发）
```

Feature controller 组织跨 Repository 的页面用例与短期 UI 状态，但不拥有连接、数据库和平台导航。
Android/Desktop 壳消费同一业务动作，各自决定全屏、弹窗、抽屉和返回逻辑。新增功能先选择已有
feature；只有形成独立业务能力和状态生命周期时才新增 feature，不能把动作重新堆回
`AppDataState`。

## 3. ImClient 状态机

`ImClient` 只拥有连接级状态：channel、pending ACK 和重连任务。它不拥有 uid 或 refresh token。

```text
DISCONNECTED → CONNECTING → CONNECTED → AUTHENTICATED
      ▲             │            │             │
      └──── retry ──┴────────────┴── network ──┘
                                      │
                                      └── AUTH_FAILED → 停止重试
```

- `connectAndAuth` 把认证参数设置和连接调度放入同一个 EventLoop 任务，避免协程线程插入造成竞态。
- refresh attempt 固定 caller 期望 uid；AUTH 回包在 durable credential hook、事件同步和 cache 构造前
  同时校验 credential owner 与已安装 projection owner。不同 uid 直接进入终态，不发送 SYNC_REQUEST。
- token 轮换属于 AUTH admission：`UserSession` 在同一身份锁内执行 uid 校验、同步持久化 hook、再发布
  新身份；持久化失败时旧身份保持完整且认证不能进入同步。logout/timeout 会先退休 callback gate，
  等待已准入 commit 后再清凭据和内存身份，迟到 AUTH 不能重新保存 token 或复活会话。
- 连接状态在单线程 Netty EventLoop 中修改。
- 重连指数退避并复用用户层认证材料。
- 写操作在未认证时被门禁拒绝，不能排队成未知时序。

## 4. Repository 与本地优先

Repository 封装远端调用和本地更新策略。读操作可以从服务端拉取并写入缓存；多数写操作不直接
伪造最终本地状态，而等待服务端 NOTIFY 走统一写入路径。

```text
UI action
  → ViewModel
  → Repository / MessageSender
  → Server
  → NOTIFY / ACK
  → EventProcessor
  → LocalCache
  → StateFlow
  → UI recomposition
```

消息发送允许局部乐观状态：先插入 `SENDING`，收到 ACK 后变为 `SENT`；服务端回环事件再提供权威
快照。发送失败必须保留可重试状态，不能把 RPC 调用成功与业务消息成功混为一谈。

职责边界如下：ViewModel 负责连续、可观察的数据流；feature controller 负责编排有明确起止的用户
用例；Repository 负责远端调用与本地收敛策略。Composable 只接收状态和回调，不直接编排多个
Repository。

## 5. EventProcessor

EventProcessor 消费 `NOTIFY`，在 IO 调度器解码和写数据库：

1. 根据 NotifyContracts 选择唯一 reader，校验并解码完整快照。
2. upsert 或删除本地对象。
3. 如果会话安装了可靠 sink，在停止代次 gate 内同步投递持久入站消息；sink 返回前 stop 不得返回。
4. 以非阻塞 `tryEmit` 发出消息、输入状态或联系人变化提示；它不参与成功语义。
5. 权威本地投影和可靠 sink 都成功后推进 `lastEventId`。

身份认证成功只进入 `SYNCHRONIZING`。EventProcessor 先订阅入站事件并打开按 uid 的 LocalCache，
再从持久游标发起 `SYNC_REQUEST`；每一批严格按 eventId 投影并逐条单调落盘，整批完成后才请求
下一页。任一持久事件失败都立即断开，后续事件不得越过失败项；收到 `SYNC_READY` 后连接才进入
`AUTHENTICATED` 并承接实时事件。

如果服务端以 `SYNC_RESET` 拒绝本地游标，EventProcessor 在 IO dispatcher 上执行一次事务性
投影重置：清 user/contact/chat/member/message/conversation、draft outbox、sync cursor 和 bot inbox，
但不清本地 outgoing。重置事务会从 outgoing 的 immutable payload 重建乐观消息投影（active 为
QUEUED、terminal 为 FAILED），避免之后 ACK 只命中 outbox 却永久丢失消息气泡；独立文档草稿也
不受影响。事务成功且返回 cursor 0 后，
ImClient 才在同一认证连接发送 `SYNC_REQUEST(0)`。一次连接只准接受一次 RESET；与页面投影重叠、
重复 RESET 或本地清理失败都会关闭连接。

跨层通知只通过 `SharedFlow` 对多个会话内消费者广播，消费者在自己的 scope 中订阅。
这些 flow 是可合并/可丢弃的刷新提示，LocalCache 和持久 cursor 才是权威；慢 UI 消费者不得对
持久事件 replay 施加背压。无头 ImBot 不把 `SharedFlow` 当业务队列：MESSAGE_RECV 先写普通消息
投影，再 `INSERT OR IGNORE` 到账号 SQLite inbox，最后推进 cursor。eventId 保序，
eventId 主键吸收同一持久事件的重放；同一 `(chatId, serverSeq)` 的创建、编辑、撤回事件不会相互
覆盖。两个磁盘语句之间崩溃会触发安全重放而不是丢消息。进程内仅保留 CONFLATED wake-up。ack 只把精确 eventId 更新为
acked，不删除历史行；peek 只返回 unacked，而 cursor 分页包含 acked 与 unacked。
直连 ImBot 使用 `nextMessageDelivery` + 显式 ack 获得 at-least-once；tt-agent 的 REST 按全局
eventId 查询同一持久 delivery log，内存对象只负责唤醒长轮询，不承担事实源。
例外是权威 `CHAT_DELETED` 墓碑：它是授权撤销/隐私边界，会原子清理该 chat 的 pending/acked
delivery、active/terminal/SUCCESS outgoing 回执与消息投影，而不保留为普通历史诊断。
`CHAT_CREATED` 在投影时只合并 conversation-dirty，先提交 Chat
与 cursor，再在 `AUTHENTICATED` 后异步重拉会话；重拉失败不回滚 cursor，也不高频自旋。
每次进入 `AUTHENTICATED` 还会无条件对账一次，以修复“cursor 已提交但 dirty 尚未落地就进程死亡”
的窗口。
`EventProcessor` 不保留可被后来者覆盖的单槽页面回调。页面、ViewModel 或会话销毁产生的
`CancellationException` 必须继续向上传播，Repository 不得将它包装成网络失败，Feature/ViewModel
也不得在取消后提交迟到状态或错误提示。

如果步骤 1–3 失败，游标不能提前推进；提示流被合并不影响权威投影的成功语义。

## 6. LocalCache

本地缓存由 SQLDelight 持久层与内存 StateFlow 组成。平台实现负责数据库驱动和存储路径，公共接口
负责：

- 用户、联系人、会话和消息的观察流。
- 消息插入、更新与按会话窗口读取。
- 会话合并，确保 serverSeq、readSeq 等单调字段不倒退。
- `markConversationRead` 的即时本地反馈与远端同步。
- 测试使用的 `FakeLocalCache`。

历史消息 RPC 必须在发请求前从 LocalCache 取得 `MessageHistoryLease`，并且只能通过该
lease 提交整页结果。lease 绑定缓存实例、全局投影代次、chat 生命周期、请求代次和
当前历史链。newest-page 的 begin 只预留 pending 新链并使更早的 newest 请求失效；
完整页成功提交后它才取代 committed anchor。older-page 只绑定 begin 当时已提交的
anchor，绝不绑定仅 pending 的新链：older 先提交可以安全扩展旧链，newest 先提交则
使旧 older 失效，因此不会把两段不连续页拼成假链。newest RPC 失败或取消时，
MessageRepository 必须显式 abandon pending lease，恢复上一个 committed anchor。`deleteChat`、
`resetServerProjection` 与 `close`
都会推进对应代次，因此墓碑之前的迟到页只返回 stale，不得重建被删除的 chat。
LocalCache 在固定锁序下再次验证 lease，并用一个 SQLite 事务校验、写入完整页；
页内任意一行失败必须整页回滚。MessageRepository 把 stale 映射为 `CancellationException`，
避免页面把丢弃结果当成业务失败。EventProcessor 的实时单条 `insertMessage` 不参与该 fence，
也不会使正常在途历史页失效。
重启后初始驻留窗口先按明确容量读取该 chat 的 `serverSeq=0` QUEUED/SENDING/FAILED 乐观行，
但已存在权威历史时至少保留一个最新 serverSeq 分页锚，再用最近权威消息填满剩余容量。内存与 Fake
使用同一“乐观优先、其次 serverSeq 倒序”规则，驻留窗口仍有界且 history cursor 可继续向旧页推进，
避免 50+ 条历史在 SQL `LIMIT` 前把本地可诊断气泡挤出视图。
窗口最多驻留配置容量的两倍；向旧页移动时会为刚加载的权威页腾出空间，不能把新页立即裁掉并重复
同一 cursor。若已到服务端历史末尾后，实时消息又挤出了权威旧页，pager 会返回被裁边界的精确
serverSeq，ViewModel 必须从服务端重新拉取该边界之前的页面；不能用不属于当前响应链的 SQLite stale tail
补位。

联系人展示模型由 Contact 关系与 User 资料组合投影；`getContacts` 与 `observeContacts`
使用同一 projector，`USER_UPDATED` 必须能驱动已展示联系人的姓名更新。平台壳不得直接写
LocalCache 伪造服务端状态；例如会话置顶必须经过 ConversationRepository，再由
`CONVERSATION_UPDATED` 收敛到本地投影。

当前仍是正式发布前阶段，本地 SQLite 只是可重建投影，不承载兼容性契约。不兼容的
schema 变化递增 `LOCAL_CACHE_SCHEMA_EPOCH` 并切换数据库文件，登录后由服务端快照和事件重建；
客户端启动路径不再维护历史增量迁移。当前 epoch 3 使用 `cache_e3*.db`；旧 `cache_e2*.db` 保留但
永不读取，服务器投影重新同步，旧草稿和未上服本地状态不迁移。epoch 3 的 outgoing 与 Bot delivery
log 已属于可靠本地事实，不得再按普通可重建投影随意清除。正式发布前必须重新评审保留与迁移策略。
建库时每次打开都幂等执行 `Schema.create`，因此当前 schema 的多 DDL 首次创建如果中途崩溃，
下次打开会补齐缺失表/索引。这不是对未发布中间 schema 的迁移：曾运行过早期 epoch 3 开发构建的
实例必须删除对应 `cache_e3*.db` 后重同步。

UI 不应绕过 ViewModel 直接把网络响应当作长期状态。任何新增展示数据都需要先回答：它如何进入
LocalCache、如何从事件恢复、如何在重启后存在。

## 7. 平台边界

共享层适合放：领域屏幕内容、消息渲染、ViewModel、Repository 和主题令牌。平台层必须拥有：

- Android NavHost、Activity、权限、通知和 Media3。
- Desktop Window、弹窗/抽屉/任务窗口、系统托盘、文件选择和桌面媒体。
- token store、SQLite driver、文件下载目录等平台实现。

Desktop 媒体目录按部署服务器与 uid 隔离；图片、视频、语音、普通附件、文本预览和群文件必须走
同一会话缓存与传输入口，不能各自维护全局目录、匿名协程或重复 HTTP 实现。

“代码能共享”不是共享的充分理由。交互模型不一致时，应共享业务动作和视觉令牌，分别实现容器。
