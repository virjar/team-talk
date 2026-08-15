# 架构总览

> 系统全貌：模块分层、三级状态、数据流。详细规格见各分册。
> 设计理念见 [design-philosophy.md](design-philosophy.md)。

---

## 1. 系统组成

```
┌─────────────────────────────────────────────────────────────────┐
│                        客户端（两端一壳）                         │
│                                                                 │
│  ┌───────────────────────────────┐  ┌────────────────────────┐ │
│  │  android/ (Android shell)     │  │ desktop/ (Desktop shell)│ │
│  │  NavHost 导航/媒体/平台能力     │  │  多窗口/托盘/keepawake   │ │
│  └──────────────┬────────────────┘  └───────────┬────────────┘ │
│                 │                                │              │
│  ┌──────────────┴────────────────────────────────┴────────────┐ │
│  │  app/ — 纯 UI 层（Compose）                                  │ │
│  │  ui/screens + components / viewmodel / navigation          │ │
│  │  AuthController（唯一 Compose 认证包装）                     │ │
│  └──────────────┬──────────────────────────────────────────────┘ │
│                 │ 只消费 SDK 公开 API                             │
│  ┌──────────────┴──────────────────────────────────────────────┐ │
│  │  shared/ — IM SDK（完整闭环，无 UI 依赖）                     │ │
│  │                                                            │ │
│  │  protocol/    帧编解码 + PacketBuffer + 契约表 + RPC 枚举     │ │
│  │  client/      ImClient(TCP) RpcClient(RPC) EventProcessor   │ │
│  │               LocalCache(SQLite) ClientSession UserSession  │ │
│  │  repository/  7 个 Repository（RPC 封装 + 本地缓存写入）      │ │
│  │  bot/         ImBot 无头客户端（AI/CLI 入口）                 │ │
│  │  model/body/  传输模型 + 15 种消息体                          │ │
│  │  testing/     FakeLocalCache + FakeRpcInvoker               │ │
│  └────────────────────────────────────────────────────────────┘ │
└──────────────────────────┬──────────────────────────────────────┘
                           │ TCP 5100（IM 全量）/ HTTPS 443（文件）
┌──────────────────────────┴──────────────────────────────────────┐
│  server/ — 服务端单体（Ktor HTTP + Netty TCP）                    │
│                                                                 │
│  protocol/   TcpServer → Handshake → PacketCodec → ImAgent      │
│              → RpcDispatcher → RpcStubRegistry（IDL 生成 Stub）                │
│  domain/     7 个领域 Service（auth/user/contact/chat/message/  │
│              conversation/presence）+ Store 缓存 + Repository   │
│  infra/      PostgreSQL(Exposed) / RocksDB(消息+token+文件)      │
│              Lucene(全文搜索) / SyncEventService(事件同步)       │
│  api/        HTTP（文件上传下载/客户端日志/健康检查）              │
└─────────────────────────────────────────────────────────────────┘
```

## 2. 三条数据通道

| 通道 | 端口/协议 | 用途 | 规格 |
|------|----------|------|------|
| **TCP 二进制** | 5100 | IM 全量：认证、RPC（INVOKE/RESPONSE）、消息（MESSAGE/ACK）、事件（NOTIFY）、心跳 | [wire-format](../01-protocol/wire-format.md) |
| **HTTP(S)** | 443/8080 | 文件上传/下载、客户端日志上报、健康检查 | [服务端 README](../02-server/README.md#http-api) |
| **本地 SQLite** | 客户端 | 本地优先的渲染数据源（按 uid 分库） | [local-cache](../03-sdk/local-cache.md) |

## 3. 一次写操作的完整数据流（以发消息为例）

```
UI 输入
 → ChatViewModel.sendMessage
    ① 乐观更新：localCache.insertMessage(status=SENDING) → UI 立即显示
    ② messageRepo.send → messageSender.sendAndWaitAck（直连层，10s 超时）
 → [TCP] Message 帧
 → 服务端 ImAgent.handleMessage（IO 线程池）
 → MessageService.sendMessage
    ③ 幂等检查（clientMsgId）→ 原子分配 serverSeq → RocksDB 存储 → Lucene 索引
    ④ 事件扩散：MESSAGE_RECV → 全体成员（含发送者）
    ⑤ 会话扩散：CONVERSATION_UPDATED → 全体成员（lastMessage/unreadCount）
 → [TCP] MessageAck 返回发送端
    ⑥ 客户端 updateMessage(serverSeq, SENT) → markRead(自己的水位线)
 → 接收端 EventProcessor（IO 线程，契约解码）
    ⑦ MESSAGE_RECV → localCache.insertMessage → 会话列表红点+1
    ⑧ 无头端：messageEvents 流 → bot 回调
```

关键点：**发送者也会收到自己消息的 MESSAGE_RECV**（服务端广播全体成员），UI 客户端靠 LocalCache 按 clientMsgId 幂等覆盖消化；无头 bot 需显式过滤（`nextMessage { it.senderUid != uid }`）。

## 4. 三级状态与生命周期

```
进程 ──────────────────────────────────────────────────────►
  App全局: ServerConfig / TokenStore / 登录窗口

  ┌─ 登录会话 A ─────────────────┐   ┌─ 登录会话 B ─────┐
  │ UserSession: uid/token       │   │ （重登后新建）     │
  │ ClientSession:               │   │                  │
  │   repos + eventProcessor     │   │                  │
  │   ┌─ TCP连接1 ─┐ ┌─ TCP连接2 ─┐ │                  │
│   │ ImClient  │ │ (重连新建)  │ │                  │
│   │ pendingAck│ │            │ │                  │
│   └───────────┘ └────────────┘ │                  │
  └───────────────────────────────┘   └─────────────────┘
        session.close() 级联销毁          ▲ AUTH_FAILED
                                          └─ tokenStore.clear()
```

- TCP 断开：只销毁连接层（自动重连 + pendingAuth 自动重认证），用户层不动
- 登出/AUTH_FAILED：`session.close()` 级联（uploader→rpc→eventProcessor→disconnect→AppLog 全局引用置空）+ token 清除
- 详见 [SDK README](../03-sdk/README.md)

## 5. 事件同步（离线补发）

每个 NOTIFY 持久化到 `sync_events` 表（自增 id = eventId）并推送给在线设备。重连认证时客户端上报 `lastEventId`，服务端补发其后所有事件（7 天 TTL）。客户端游标**处理成功才推进**——失败事件下次补发重试（at-least-once）。

详见 [服务端事件矩阵](../02-server/README.md#事件发射矩阵) 与 [SDK README 的 EventProcessor 节](../03-sdk/README.md)。

> 现状说明：客户端已维护 lastEventId 游标（StateFlow），但尚未把它回填进 AuthRequestPayload.lastEventId（当前恒为 0），离线补发链路处于"服务端就绪、客户端未接线"状态——见 [ROADMAP](../09-roadmap.md)。

## 6. 模块依赖图（单向，无环）

```
shared ◄──── server（只用协议+模型定义）
  ▲
  │
app ◄──── android
  ▲
  │
desktop
```

- shared 是**唯一下沉层**：SDK 全部能力，测试可纯 JVM 跑（不编译 Compose）
- app 依赖 shared，禁止反向；shell 依赖 app + shared（显式声明）
- 修改 shared = 改 SDK 契约，必须过 [契约测试](../01-protocol/notify-contracts.md)

## 7. 技术栈版本

| 组件 | 技术 | 版本 |
|------|------|------|
| 语言/构建 | Kotlin / Gradle | 2.3.20 / 8.14 |
| UI | Compose Multiplatform + Material3 | 1.10.3 |
| 网络 | Netty（TCP 双端）/ Ktor（HTTP） | 4.1.119 / 3.4.3 |
| 序列化 | 手写二进制（PacketBuffer）/ kotlinx-json（边缘） | — |
| 服务端 DB | PostgreSQL (Exposed) + RocksDB | 42.7.5 / 9.10 |
| 客户端 DB | SQLDelight (SQLite) | 2.3.2 |
| 搜索 | Lucene + IK 中文分词 | 9.12.0 |
| DI（服务端） | Koin | 4.0.4 |
| 平台 | Android 26+ / macOS(Desktop JVM) | — |

## 8. 各分册导航

| 想了解 | 读 |
|--------|-----|
| 协议怎么编码 | [01-protocol/wire-format](../01-protocol/wire-format.md) |
| 有哪些 RPC/通知 | [01-protocol/rpc-methods](../01-protocol/rpc-methods.md) · [notify-contracts](../01-protocol/notify-contracts.md) |
| 服务端怎么实现 | [02-server/README](../02-server/README.md) · [database](../02-server/database.md) |
| SDK 怎么工作 | [03-sdk/README](../03-sdk/README.md) · [imclient](../03-sdk/imclient.md) · [local-cache](../03-sdk/local-cache.md) |
| 无头 bot | [03-sdk/imbot](../03-sdk/imbot.md) |
| 历史踩坑 | [05-lessons](../05-lessons/README.md)（**新成员必读**） |
| 怎么测试 | [06-testing](../07-testing/README.md) |
| 构建部署 | [build-system](build-system.md) · [deploy](getting-started/deploy.md) |
| 未来方向 | [ROADMAP](../09-roadmap.md) |
