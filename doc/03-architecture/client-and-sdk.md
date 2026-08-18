# 客户端与 SDK 架构

## 1. 分层

```text
Platform shell（android / desktop）
    └── app：Compose screens、ViewModel、AppDataState
          └── shared：ClientSession、Repository、LocalCache、protocol
                └── TCP/HTTP
```

`shared` 是完整的无 UI SDK。`app` 只消费其公开能力；平台壳决定窗口、导航、权限和系统集成。
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

## 5. EventProcessor

EventProcessor 消费 `NOTIFY`，在 IO 调度器解码和写数据库：

1. 根据 NotifyContracts 选择唯一 payload reader。
2. 校验并解码完整快照。
3. upsert 或删除本地对象。
4. 发出消息、输入状态或联系人变化流。
5. 全部成功后推进 `lastEventId`。

如果步骤 2–4 失败，游标不能提前推进；否则服务端会认为事件已消费，客户端永久丢失变化。

## 6. LocalCache

本地缓存由 SQLDelight 持久层与内存 StateFlow 组成。平台实现负责数据库驱动和存储路径，公共接口
负责：

- 用户、联系人、会话和消息的观察流。
- 消息插入、更新与按会话窗口读取。
- 会话合并，确保 serverSeq、readSeq 等单调字段不倒退。
- `markConversationRead` 的即时本地反馈与远端同步。
- 测试使用的 `FakeLocalCache`。

UI 不应绕过 ViewModel 直接把网络响应当作长期状态。任何新增展示数据都需要先回答：它如何进入
LocalCache、如何从事件恢复、如何在重启后存在。

## 7. 平台边界

共享层适合放：领域屏幕内容、消息渲染、ViewModel、Repository 和主题令牌。平台层必须拥有：

- Android NavHost、Activity、权限、通知和 Media3。
- Desktop Window、弹窗/抽屉/任务窗口、系统托盘、文件选择和桌面媒体。
- token store、SQLite driver、文件下载目录等平台实现。

“代码能共享”不是共享的充分理由。交互模型不一致时，应共享业务动作和视觉令牌，分别实现容器。
