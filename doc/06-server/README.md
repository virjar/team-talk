# 服务端

TeamTalk 服务端是 Ktor HTTP(S) 与 Netty TCP 组成的模块化单体。领域服务共享同一进程和存储连接，
但通过明确的 Repository、Store 和事件边界保持可理解性。

## 1. 请求入口

```text
TCP                              HTTP(S)
├── AUTH                         ├── /health
├── INVOKE → generated RPC Stub  ├── /api/v1/files/*
├── MESSAGE                      ├── /api/client-telemetry
├── SYNC_REQUEST                 ├── /api/admin/*
└── PING/PONG                    └── /downloads/* and static
```

TCP 负责实时和确定性业务；HTTP 负责文件、大 payload、管理和运维。领域规则不能因为入口不同而
出现两套实现。

## 2. 内部结构

```text
protocol/api adapters
        ↓
application coordinators → domain services → domain ports
                                             ↑
                              infrastructure adapters
                                             ↓
                         PostgreSQL / RocksDB / Lucene
```

- adapter 负责认证上下文、编解码和错误映射。
- application 组合连接生命周期、管理视图等跨域流程。
- domain service 负责权限、状态转换与事件目标，只面向领域端口。
- infra adapter 实现 Repository、消息存储、搜索、事件和在线会话端口。

## 3. 先认识数据，再读消息链路

下面四个概念不是同一对象的四种包装，而是不同的业务事实：

| 概念 | 表达什么 | 身份与存储 |
|---|---|---|
| [Chat / Member](../../protocol/protocol/src/commonMain/kotlin/com/virjar/tk/protocol/model/Chat.kt) | 一个聊天及其参与者；群名、成员角色属于这一侧 | `chatId`；PostgreSQL 的 `chats`、成员及群资料表 |
| [Conversation](../../protocol/protocol/src/commonMain/kotlin/com/virjar/tk/protocol/model/Conversation.kt) | **某个用户**列表里的聊天条目；置顶、静音、草稿、已读位置各人不同 | `uid + chatId`；PostgreSQL 的 `conversations` |
| [Message](../../protocol/protocol/src/commonMain/kotlin/com/virjar/tk/protocol/model/Message.kt) | 聊天中的一条内容；正文由 `MessageBody` 表达 | `chatId + serverSeq`；RocksDB。发送重试使用 `chatId + clientMsgId` |
| 用户同步事件 | 告诉用户“有什么改变了”；消息、会话、联系人等变化都可以产生事件 | `uid + streamSeq`；PostgreSQL 的 `sync_events`，线上封装为 `NotifyPayload` |

例如 A、B 的私聊只有一个 Chat，一条消息也只存一份，但有两条 Conversation；这次发送还会分别
为 A、B 产生消息与会话更新事件。事件序号是**每个用户的同步位置**，不是消息序号。
`Chat.maxSeq` 是 PostgreSQL 已完成消息投影的位置；新消息序号由 RocksDB 的 `MessageStore` 分配，
不能反过来用 `Chat.maxSeq + 1` 充当权威序号。编辑/撤回保留原 `serverSeq`，推进的是投影操作的 revision。

### 一条普通消息经过哪里

```text
MESSAGE 帧
  → ImAgent.handleMessage：切到 IOExecutor
  → MessageService.sendMessage：校验、重试去重、补全内容
  → MessageStore.appendMessage：同批写入消息、幂等记录、待投影操作
  → MessageProjector：更新 Lucene，再提交 PostgreSQL 会话与同步事件
  → 返回 serverSeq，ImAgent 发送成功 ACK

PostgreSQL 事件提交 → SyncEventDispatcher → ClientRegistry → 各设备的 NOTIFY
```

ACK 表示服务端已接受消息并完成上述投影，**不表示所有设备已经收到**。实时投递独立进行，
离线设备通过 `SyncEventService` 重放用户事件流。若消息已落库、投影尚未完成，RocksDB 中的待投影
操作会保留；命令重试和后台恢复都使用同一个 `MessageProjector`，不是另建一套补偿业务。

建议按这个顺序打开源码，第一遍只跟发送路径，不必同时展开认证、搜索、管理和遥测：

1. [ImAgent.handleMessage](../../server/server/src/main/kotlin/com/virjar/tk/server/protocol/connection/ImAgent.kt)：
   确认包如何进入业务线程、成功/失败如何转成 ACK。历史查询、编辑等 RPC 从同文件的 `handleInvoke`
   经 `RpcDispatcher → RpcImpls` 进入领域服务；普通发送不经过 RPC Stub。
2. [MessageService](../../server/server/src/main/kotlin/com/virjar/tk/server/domain/message/MessageService.kt)：
   读 `sendMessage → sendMessageLocked`，再按需要展开正文与附件校验。
3. [MessageRepository](../../server/server/src/main/kotlin/com/virjar/tk/server/domain/message/MessageRepository.kt)
   和 [MessageStore](../../server/server/src/main/kotlin/com/virjar/tk/server/infra/storage/MessageStore.kt)：
   前者规定归档能力，后者实现 RocksDB 原子写入；这里的 Store 是持久化存储，不是缓存。
4. [MessageProjector](../../server/server/src/main/kotlin/com/virjar/tk/server/domain/message/MessageProjector.kt)：
   读 `drainPendingForMessageLocked → projectOperationLocked`。同目录的 `MessageProjection.kt` 定义
   待处理操作和接收者，不另存一套消息正文模型。
5. [ExposedMessageProjectionRepository](../../server/server/src/main/kotlin/com/virjar/tk/server/infra/db/repository/ExposedMessageProjectionRepository.kt)
   与 [ExposedPgUnitOfWork](../../server/server/src/main/kotlin/com/virjar/tk/server/infra/db/ExposedPgUnitOfWork.kt)：
   前者更新聊天/会话，后者把事件和这些变化一起提交。会话列表、操作响应与消息通知共用
   `ExposedConversationRepository.kt` 中的 `ResultRow.toConversation`，不再各维护一份字段映射。
6. [SyncEventDispatcher](../../server/server/src/main/kotlin/com/virjar/tk/server/infra/sync/SyncEventDispatcher.kt)：
   读提交后的实际投递；想看掉线后的补发，再读同目录的 `SyncEventService`。

会话列表与草稿、置顶、免打扰、已读操作从
[ConversationService](../../server/server/src/main/kotlin/com/virjar/tk/server/domain/conversation/ConversationService.kt)
进入：每个操作直接写出聊天串行化、PG 事务、仓储变更和事件追加，不再经过一次性的内部转发方法。
分页的客户端游标只在服务入口编解码；仓储接收 `afterChatId`、返回 `nextChatId`，没有单字段锚点模型。
`ExposedConversationRepository.readableConversations` 统一列表和单条读取的查询条件，
调用方只追加分页或目标 chatId；消息正文历史则看 `MessageStore.getHistory` 中的一条双向扫描循环。

### 按职责定位目录

服务端源码根是 `server/server/src/main/kotlin/com/virjar/tk/server/`：

- `protocol/`、`api/`：TCP/RPC 与 HTTP 入口。不要在这里找消息保存规则。
- `domain/message/`、`domain/chat/`、`domain/conversation/`：按业务分组，服务与其所需端口放在一起。
  读消息规则先留在 `domain/message/`，需要知道如何落库时才跳到外层。
- `infra/storage/`、`infra/db/repository/`、`infra/search/`：分别实现本地文件/RocksDB、PostgreSQL、Lucene。
  数据库行只在持久化层内部流转，不作为新的领域模型传出。
- `infra/sync/`：用户事件读取与投递、在线设备连接管理。
- `application/`：管理、机器人等跨业务编排；`di/ServerModule.kt` 说明真实实例如何组装，
  `Application.kt` 负责启动和关闭。

## 4. 关键不变量

1. 未认证连接只能发送 AUTH/PING 等允许帧，且 payload 上限更小。
2. 所有会话业务先校验成员资格。
3. 消息 ACK 前完成消息体、附件、权限、幂等和权威落库校验。
4. 每个成员加入 Chat 时创建其 Conversation。
5. `readSeq`、消息序列和关键版本只增不减。
6. 消息与待投影 outbox 原子提交；搜索、会话和事件投影失败后可恢复。
7. 业务状态提交后才发持久化事件；Presence/Typing 只走瞬时事件。
8. Lucene 与缩略图等派生数据可重建，不取代权威存储。

## 5. 分册

- [领域服务](domain-services.md)：用户、联系人、群、消息、会话与设备规则。
- [持久化](persistence.md)：PostgreSQL、RocksDB、Lucene 和数据恢复边界。
- [文件存储](file-storage.md)：上传、分层存储、附件校验和下载。
- [搜索与管理](search-and-admin.md)：全文搜索、用户搜索和管理后台。

线程、连接和启动过程见[服务端运行时](../03-architecture/server-runtime.md)；部署和监控见
[运维](../07-operations/README.md)。
