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
      ├── CrashDumper / HttpLogUploader
      └── ViewModels（由 app 层持有）
```

销毁顺序先停止日志上传、RPC 和事件消费，再断开 TCP，最后解除全局日志回调。`close()` 必须幂等，
防止登出、认证失败和窗口销毁同时触发时产生二次清理问题。

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
3. 如果会话安装了可靠 sink，先投递持久入站消息。
4. 以非阻塞 `tryEmit` 发出消息、输入状态或联系人变化提示；它不参与成功语义。
5. 权威本地投影和可靠 sink 都成功后推进 `lastEventId`。

身份认证成功只进入 `SYNCHRONIZING`。EventProcessor 先订阅入站事件并打开按 uid 的 LocalCache，
再从持久游标发起 `SYNC_REQUEST`；每一批严格按 eventId 投影并逐条单调落盘，整批完成后才请求
下一页。任一持久事件失败都立即断开，后续事件不得越过失败项；收到 `SYNC_READY` 后连接才进入
`AUTHENTICATED` 并承接实时事件。

如果服务端以 `SYNC_RESET` 拒绝本地游标，EventProcessor 在 IO dispatcher 上执行一次事务性
投影重置：清 user/contact/chat/member/message/conversation、draft outbox、sync cursor 和 bot inbox，
并同步清空既有 StateFlow 与消息窗口；独立文档草稿不受影响。事务成功且返回 cursor 0 后，
ImClient 才在同一认证连接发送 `SYNC_REQUEST(0)`。一次连接只准接受一次 RESET；与页面投影重叠、
重复 RESET 或本地清理失败都会关闭连接。

跨层通知只通过 `SharedFlow` 对多个会话内消费者广播，消费者在自己的 scope 中订阅。
这些 flow 是可合并/可丢弃的刷新提示，LocalCache 和持久 cursor 才是权威；慢 UI 消费者不得对
持久事件 replay 施加背压。无头 ImBot 不把 `SharedFlow` 当业务队列：MESSAGE_RECV 先写普通消息
投影，再 `INSERT OR IGNORE` 到账号 SQLite inbox，最后推进 cursor。eventId 保序，
`(chatId, serverSeq)` 唯一键吸收服务端不同 eventId 的重复 projection；两个磁盘语句之间崩溃会
触发安全重放而不是丢消息。进程内仅保留 CONFLATED wake-up。
直连 ImBot 使用 `nextMessageDelivery` + 显式 ack 获得 at-least-once；tt-agent 的 REST recent/history
直接查询普通消息 SQLite 投影，内存对象只负责唤醒长轮询，不承担事实源。
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

联系人展示模型由 Contact 关系与 User 资料组合投影；`getContacts` 与 `observeContacts`
使用同一 projector，`USER_UPDATED` 必须能驱动已展示联系人的姓名更新。平台壳不得直接写
LocalCache 伪造服务端状态；例如会话置顶必须经过 ConversationRepository，再由
`CONVERSATION_UPDATED` 收敛到本地投影。

当前仍是正式发布前阶段，本地 SQLite 只是可重建投影，不承载兼容性契约。不兼容的
schema 变化递增 `LOCAL_CACHE_SCHEMA_EPOCH` 并切换数据库文件，登录后由服务端快照和事件重建；
客户端启动路径不再维护历史增量迁移。正式发布前必须重新评审这一策略。

UI 不应绕过 ViewModel 直接把网络响应当作长期状态。任何新增展示数据都需要先回答：它如何进入
LocalCache、如何从事件恢复、如何在重启后存在。

## 7. 平台边界

共享层适合放：领域屏幕内容、消息渲染、ViewModel、Repository 和主题令牌。平台层必须拥有：

- Android NavHost、Activity、权限、通知和 Media3。
- Desktop Window、弹窗/抽屉/任务窗口、系统托盘、文件选择和桌面媒体。
- token store、SQLite driver、文件下载目录等平台实现。

“代码能共享”不是共享的充分理由。交互模型不一致时，应共享业务动作和视觉令牌，分别实现容器。
