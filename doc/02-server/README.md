# 服务端架构

> 单体服务（Ktor HTTP + Netty TCP），DDD 分层：RpcStub（IDL 生成，uid 绑定）→ Service（业务）→ Store（内存缓存）→ Repository（DB）。
> 深入：[database.md](database.md) · [threading.md](threading.md) · [file-storage.md](file-storage.md) · [fulltext-search.md](fulltext-search.md)

---

## 1. 启动序列（Application.kt）

```
main()
 1. Environment 解析 + 设 LOG_DIR（必须在 logback 初始化前）
 2. 注入 Slf4jTkLogger（shared 模块日志 → SLF4J）
 3. Ktor Netty 启动（HTTP :8080 / 可选 HTTPS :443 + PKCS12）
 4. Application.module()：
    ① Koin 安装（serverModule 全部单例）
    ② DatabaseFactory（PostgreSQL/HikariCP/Exposed + createMissingTablesAndColumns 自动建表加列）
    ③ MessageStore.init()（RocksDB）/ FileStore.init() / ClientLogStore
    ④ SearchIndex.start()（Lucene + IK）
    ⑤ TcpServer.start（:5100）→ 每连接 new ImAgent（非单例，10 依赖手工注入）
    ⑥ HealthChecker / HTTP routes
 5. ApplicationStopping → clientRegistry.stop → searchIndex.stop → tcpServer.stop → messageStore.close
```

## 2. TCP 连接处理管线

```
TcpServer（boss + NioEventLoopGroup workers）
 └─ pipeline: IdleStateHandler(45s读超时) → HandshakeHandler → PacketCodec → ImAgent
```

**ImAgent**（每连接一个，连接生命周期）：
- 状态：CONNECTED → AUTHENTICATED → DISCONNECTED（AtomicReference）
- 路由：PING/DISCONNECT/UNSUBSCRIBE 在 **EventLoop 内联**处理；AUTH/INVOKE/MESSAGE/SUBSCRIBE 切 **IOExecutor 线程池**（`max(4, cores)` 守护线程）
- 认证成功：注册 ClientRegistry → 上线广播 → **若 lastEventId>0 补发离线事件** → 回 AuthResponse
- 断开：注销 → 异步下线广播
- ImAgentFacade（WeakReference 包装）防 Netty channel/direct buffer 被 GC 根泄漏

**错误三层**（ImAgent + RpcDispatcher 共用）：
| 异常 | 处理 | 理由 |
|------|------|------|
| IllegalArgumentException | ACK/RESPONSE code=400 + message | 业务拒绝（用户可见） |
| IndexOutOfBoundsException | **断连**（FatalCodec） | 编解码越位 = 协议漂移，连接已不可信 |
| 其他 Exception | code=500 + ERROR 日志 | 内部错误，连接保持 |

## 3. 领域服务速览（规则 + 事件）

### AuthService（认证）
- TCP 握手包处理：版本检查（≠1 → version_unsupported）→ authType 0/1/2
- token 对一次性轮换：refreshAccessToken = 删旧 + 发新对；**logout 只删不发**（曾经误用 refreshAccessToken 导致登出反而签发新凭证——已修）
- Token TTL：access 30 天 / refresh 90 天

### UserService
- 注册：username/phone 唯一性预检 → uid = 8 位 base62 短码（SecureRandom，20 次重试防碰撞，兜底 UUID）→ BCrypt
- 登录：未知用户与错误密码**同文案**（防枚举）
- updateProfile → USER_UPDATED → 自己

### ContactService
- apply：非自己、非好友；CONTACT_APPLY(ContactApply) → 对方
- accept：CONTACT_ACCEPTED(**各自视角 Contact**) → 双方
- deleteFriend：CONTACT_DELETED（各自视角）→ 双方

### ChatService（群组生命周期）
- 全部入口在创建 chat 后 **ensureConversations 预创建会话行**（否则 markRead 无行可写 → readSeq 丢失 → 换设备全未读——已修的历史断裂）
- 权限矩阵见 [rpc-methods §6](../01-protocol/rpc-methods.md#6-chat群组生命周期)
- 踢人：群主不可退/被踢；仅群主可踢管理员

### MessageService
- sendMessage：clientMsgId 幂等 → `chatStore.incrementMaxSeq`（内存原子 + 异步落库）→ RocksDB → Lucene → MESSAGE_RECV **全体成员含发送者** → CONVERSATION_UPDATED 逐成员
- revoke：发送者或管理员（flags|=1）；edit：仅发送者（flags|=2）；forward：双会话成员（新消息 flags|=4）

### ConversationService
- unreadCount = lastSeq − readSeq **服务端权威计算**下发
- markRead 级联：自己 readSeq（只增不减）→ CONVERSATION_UPDATED 自己 → 其他成员 peerReadSeq（取 max）+ READ_SYNC
- ensureConversations：建群/加人/邀请时为成员 INSERT OR IGNORE 会话行

### PresenceService
- 上下线广播给好友；**直写 agent 不经 SyncEventService**（不持久化，PRESENCE 契约豁免）

## 4. 事件发射矩阵（谁发什么给谁）

| 发射方 | NotifyType | payload | 接收者 |
|--------|-----------|---------|--------|
| UserService.updateProfile | USER_UPDATED | User | 自己 |
| ContactService.apply | CONTACT_APPLY | ContactApply | 申请接收者 |
| ContactService.accept | CONTACT_ACCEPTED | Contact（各自视角） | 双方 |
| ContactService.deleteFriend | CONTACT_DELETED | Contact（各自视角） | 双方 |
| ChatService.createPersonalChat/createGroup | CHAT_CREATED | Chat | 相关成员 |
| ChatService.addMembers | CHAT_CREATED + MEMBER_ADDED | Chat | 新成员 / 全员 |
| ChatService.joinByInvite | CHAT_CREATED | Chat | 全员 |
| ChatService.updateGroup / muteAll / unmuteAll | CHAT_UPDATED | Chat | 全员 |
| ChatService.deleteChat | CHAT_DELETED | Chat | 全员（删除前快照） |
| ChatService.removeMember | MEMBER_REMOVED | Chat | 全员+被移除者 |
| ChatService.transferOwner/setRole | MEMBER_ROLE_CHANGED | Chat | 全员 |
| ChatService.muteMember/unmute | MEMBER_MUTED/UNMUTED | Chat | 全员 |
| MessageService.send/revoke/edit/forward | MESSAGE_RECV | Message（含 flags 变更） | 目标会话全体成员（**含发送者**） |
| ImAgent.handleTyping | TYPING | Message（chatId+senderUid） | 成员−发送者 |
| ConversationService.onMessageReceived | CONVERSATION_UPDATED | Conversation | 逐成员 |
| ConversationService.setDraft/Pin/Mute/markRead | CONVERSATION_UPDATED | Conversation | 自己 |
| ConversationService.deleteConversation | CONVERSATION_DELETED | Conversation（哨兵） | 自己 |
| ConversationService.markRead | READ_SYNC | ReadSyncPayload | 其他成员 |
| PresenceService（直写，仅最后一台设备下线时广播） | PRESENCE | PresencePayload | 好友（不持久化，契约已登记但 emit 不经 assertContract） |
| ImAgent.handleSubscribe（直写 eventId=0） | MESSAGE_RECV | Message | 仅请求连接（历史回放） |

## 5. 事件同步（SyncEventService + sync_events 表）

```
emitEvent(uid, type, payload):
  ① assertContract(type, payload)          // 契约校验，错配当场抛
  ② INSERT sync_events(uid, event_type, payload_bytes) → eventId
  ③ pushToUser: ClientRegistry.getAgents(uid) 逐个 agent.write(NotifyPayload)
离线补发（认证时 lastEventId>0）:
  SELECT ... WHERE uid=? AND id > lastEventId ORDER BY id LIMIT 100 → 逐条推送
```

- at-least-once：客户端**处理成功才推进游标**，失败事件下次补发重试
- 防死循环：消息类有 seq 兜底（按 seq 拉历史）；事件 7 天 TTL
- ClientRegistry：`uid → (deviceId → ImAgent)`，单线程 Looper 串行化；同设备重复登录旧连接延迟 30s 踢出

## 6. 存储分工

| 数据 | 存储 | key/布局 |
|------|------|---------|
| 用户/关系/群/会话/申请/邀请/事件 | PostgreSQL | [database.md](database.md) |
| 消息体 | RocksDB | `[chatId utf8][seq 8B大端]` → Message 编码；`[0x01][clientMsgId]` → chatId+seq（幂等索引） |
| token | RocksDB CF `tokens` | access=原串；refresh=`"refresh:"+token`；value=5 字段 `\0` 分隔 |
| 文件 | 分层存储 | >32MB → 文件系统（2 级分片）；≤32MB → RocksDB（LZ4+ZSTD） |
| 全文索引 | Lucene FSDirectory | IK 分词；clientMsgId 为更新 Term |

## 7. HTTP API

| 路由 | 方法 | 说明 |
|------|------|------|
| `/health` | GET | 组件健康（PG/RocksDB/Lucene/FileStore/TCP:5100），全 UP 才 200 |
| `/api/v1/files/upload` | POST multipart | `X-Uid` 头（暂匿名 TODO 鉴权）；返回 `{path, url}` |
| `/api/v1/files/{path...}` | GET | 元数据定位 → FS respondFile / RocksDB 流式 |
| `/api/client-logs` | POST | gzip 日志 ingestion，按 uid/deviceId/日期落盘，7 天保留 |
| `/` · `/downloads/{f}` | GET | 静态主页 / 客户端安装包 |

## 8. DI 图（Koin 单例，serverModule.kt）

```
TokenStore(path)   ClientRegistry   MessageStore(path)   FileStore(db,fs)
SearchIndex(dir)   ClientLogStore
UserRepository   ContactRepository(UserRepo)   ChatRepository
ChatMemberRepository   InviteLinkRepository
ConversationRepository(ChatRepo, MemberRepo, UserRepo)   DeviceRepository
UserStore(UserRepo)   ContactStore(ContactRepo)
ChatStore(ChatRepo, MemberRepo, InviteRepo)
UserService(UserStore, Sync)   AuthService(UserService, TokenStore)
ContactService(ContactStore, Sync)   SyncEventService(ClientRegistry)
ChatService(ChatStore, UserStore, Sync, ConversationService)
ConversationService(ConvRepo, ChatRepo, Sync)
MessageService(MessageStore, ChatStore, Sync, ConversationService, SearchIndex)
PresenceService(ContactStore, ClientRegistry)   HealthChecker
8×RouteHandler → RpcDispatcher    TcpServer
```
（ImAgent 非单例，每连接工厂构造）

## 9. 数据目录

```
$dataRoot/            # -Dteamtalk.data.root 或 <install>/data
├── rocksdb/          # 消息
├── tokenstore/       # token
├── lucene-index/
├── file-store/{rocksdb, files, tmp}
├── client-logs/{uid}/{deviceId}/{date}.log
└── logs/
```
