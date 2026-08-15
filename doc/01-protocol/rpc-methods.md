# RPC 方法矩阵 — IDL 生成体系（协议 v2）

> **Kotlin interface = IDL**（精简版 gRPC）：`@RpcService(name)` interface 定义服务契约，
> rpc-processor（KSP2）编译期生成 Contract/Stub/Proxy——参数编码解码路由全部生成物锁定，
> 手写对齐已从代码库根除。
>
> IDL：`shared/.../rpc/def/*.kt` · 生成器：`rpc-processor/` · 生成物：`shared/build/generated/ksp/.../rpc/gen/`

---

## 0. IDL 规范

```kotlin
@RpcService("message")                    // serviceId = 字符串（wire 直传）
interface MessageRpc {
    suspend fun getHistory(chatId: String, fromSeq: Long, limit: Int): List<Message>
    @RpcMethod(5)                         // 可省略：按声明顺序 1,2,3... 分配
    suspend fun forward(...): Message
}
```

**规则（违反 → 编译失败）**：方法必须 suspend；参数白名单 String/Int/Long/Boolean/String?/List\<String\>/IProto 子类，禁止默认值；返回同上 + Unit/List。**methodId 稳定性：新方法只追加末尾；中间插入必须 @RpcMethod 锁定**（生成 Contract 常量即 golden 文件，git diff 可见漂移）。

**生成物（每 service 一个文件）**：
- `XxxRpcContract`：SERVICE/M_* 常量 + 参数 encode + verifyRoundTrip 自检
- `XxxRpcStub(uid)`：服务端 abstract（uid 成员，dispatch 解码→调用→编码；实现类见 server `protocol/rpc/RpcImpls.kt` 薄壳或 domain 直接实现）
- `XxxRpcProxy(rpc)`：客户端实现（encode→invoke→ensureSuccess→decode；Repository 内部使用）
- `RpcServiceRegistry`：全量注册表 + verifyAll

**新增 RPC 方法 = 三步**：IDL 加方法（末尾）→ 服务端 Impl override → 客户端 Repository 调 Proxy。编译器保证双端对齐。

## 1. 路由机制（生成物驱动）

```
客户端 XxxRpcProxy → RpcInvoker.invoke("服务名", M_常量, 编码参数)
 → 服务端 ImAgent → RpcDispatcher → RpcStubRegistry（字符串 serviceId → Stub 工厂(uid)）
 → XxxRpcStub.dispatch（解码→调用 Impl→编码返回）
 → ResponsePayload(requestId, status, result)
```

- **认证前置**：dispatch 仅在 AUTHENTICATED 后可达；uid 注入 Stub 成员
- 客户端前置校验（AuthRules）：username 3-50 字符、password ≥6——服务端规则镜像

## 2. ServiceId（字符串，IDL name）

| serviceId | IDL | 服务端实现 |
|----|------|---------|
| "auth" | AuthRpc | AuthRpcImpl |
| "user" | UserRpc | UserRpcImpl |
| "contact" | ContactRpc | ContactRpcImpl |
| "chat" | ChatRpc | ChatRpcImpl |
| "message" | MessageRpc | MessageRpcImpl |
| "conversation" | ConversationRpc | ConversationRpcImpl |
| "device" | DeviceRpc | DeviceRpcImpl |
| ~~99 GENERIC~~ | 已删除（零使用，防过早实现） |

## 3. AUTH（认证走 TCP 握手包，此处仅会话管理）

| id | 方法 | 请求布局 | 响应 | 规则 |
|----|------|---------|------|------|
| 3 | LOGOUT | `string refreshToken?` | 空 | 吊销 refresh（只删不发）+ 可选吊销整设备 token |
| 5 | UPDATE_PASSWORD | `string old, string new` | 空 | 旧密码错 → 400 |
| 1/2/4 | REGISTER/LOGIN/REFRESH_TOKEN | — | **400 "Use TCP handshake for auth"** | 故意拒绝（防误调静默成功） |

## 4. USER

| id | 方法 | 请求布局 | 响应 |
|----|------|---------|------|
| 1 | GET_PROFILE | `string targetUid`（空=自己） | User |
| 2 | UPDATE_PROFILE | `User`（uid 置空） | 空 |
| 3 | SEARCH | `string keyword` | List\<User\>（上限 20） |

## 5. CONTACT

| id | 方法 | 请求布局 | 响应 | 服务端事件 |
|----|------|---------|------|-----------|
| 1 | LIST | 空 | List\<Contact\> | — |
| 2 | APPLY | `string targetUid, string? remark` | ContactApply | CONTACT_APPLY → 对方 |
| 3 | ACCEPT | `string token` | ContactApply | CONTACT_ACCEPTED → 双方（各自视角 Contact） |
| 4 | REJECT | `string token` | ContactApply | — |
| 5 | DELETE | `string friendUid` | 空 | CONTACT_DELETED → 双方（各自视角） |
| 6 | SET_REMARK | `string friendUid, string? remark` | 空 | — |
| 7 | BLACKLIST | `string targetUid` | 空 | — |
| 8 | BLACKLIST_REMOVE | `string targetUid` | 空 | — |
| 9 | LIST_APPLIES | 空 | List\<ContactApply\>（status=0） | — |
| 10 | LIST_BLACKLIST | 空 | List\<Contact\>（status=2） | — |

## 6. CHAT（群组生命周期）

| id | 方法 | 请求布局 | 响应 | 权限 | 事件 |
|----|------|---------|------|------|------|
| 1 | CREATE_PERSONAL | `string targetUid` | Chat | ≠自己 | CHAT_CREATED→双方 + **预创建会话行** |
| 2 | CREATE_GROUP | `string name, string? avatar, varInt count, [string uid × count]` | Chat | 群名非空/成员非空 | CHAT_CREATED→全员 + 预创建会话行 |
| 3 | GET | `string chatId` | Chat | — | — |
| 4 | UPDATE | `string chatId, string? name, string? avatar, string? notice` | 空 | 管理员 | CHAT_UPDATED→全员 |
| 5 | DELETE | `string chatId` | 空 | 群=群主；私聊=任一方 | CHAT_DELETED→全员（删除前快照） |
| 6 | ADD_MEMBERS | `string chatId, varInt count, [string uid]` | 空 | 管理员 | CHAT_CREATED→新成员；MEMBER_ADDED→全员 |
| 7 | REMOVE_MEMBERS | `string chatId, string targetUid` | 空 | 见下方踢人规则 | MEMBER_REMOVED→全员+被踢者 |
| 8 | GET_MEMBERS | `string chatId` | List\<Member\>（含嵌套 User） | — | — |
| 9 | TRANSFER_OWNER | `string chatId, string newOwnerUid` | 空 | 群主 | MEMBER_ROLE_CHANGED→全员 |
| 10 | SET_ROLE | `string chatId, string targetUid, varInt role(0/1)` | 空 | 群主 | MEMBER_ROLE_CHANGED→全员 |
| 11 | MUTE_MEMBER | `string chatId, string targetUid, varInt durationSeconds` | 空 | 管理员 | MEMBER_MUTED→全员 |
| 12 | UNMUTE_MEMBER | `string chatId, string targetUid` | 空 | 管理员 | MEMBER_UNMUTED→全员 |
| 13/14 | MUTE_ALL / UNMUTE_ALL | `string chatId` | 空 | **群主** | CHAT_UPDATED→全员 |
| 15 | CREATE_INVITE_LINK | `string chatId, string name, varInt maxUses, varLong expiresAt` | string token | 管理员 | — |
| 16 | LIST_INVITE_LINKS | `string chatId` | List\<InviteLink\> | 管理员 | — |
| 17 | REVOKE_INVITE_LINK | `string token` | 空 | 管理员 | — |
| 18 | JOIN_BY_INVITE | `string token` | Chat | 链接有效+未用尽；已成员幂等返回 | CHAT_CREATED→全员 + 预创建会话行 |
| 19 | GET_INVITE_INFO | `string token` | InviteLink | — | — |

**踢人规则**（REMOVE_MEMBERS）：自退（op==target）：群主不可退；踢人：需管理员、不可踢群主、仅群主可踢管理员。

## 7. MESSAGE

| id | 方法 | 请求布局 | 响应 | 规则 |
|----|------|---------|------|------|
| 1 | GET_HISTORY | `string chatId, varLong fromSeq(0=最新), varInt limit` | List\<Message\> | 成员校验；倒序窗口 |
| 2 | SEARCH | `string chatId, string keyword, varInt limit` | List\<Message\>（含高亮片段） | 成员校验；Lucene |
| 3 | REVOKE | `string chatId, varLong serverSeq` | 空 | 发送者或管理员；flags\|=1 |
| 4 | EDIT | `Message`（完整编码，serverSeq 定位） | 空 | 仅发送者；flags\|=2 |
| 5 | FORWARD | `string srcChatId, varLong srcSeq, string targetChatId` | Message（新消息） | 双方会话成员；新 clientMsgId/seq/timestamp；flags\|=4 |
| 6 | MARK_READ | `string chatId, varLong readSeq` | 空 | 转发 ConversationService（见下） |

**消息发送不走 RPC**：MESSAGE 帧 + MESSAGE_ACK 独立协议（消息有独立 ACK 语义与 10s 超时合成错误码，见 [wire-format §5](wire-format.md)）。服务端 `sendMessage`：clientMsgId 幂等（重复投递返回原 seq）→ 原子分配 seq → RocksDB → Lucene → MESSAGE_RECV 广播**全体成员（含发送者）** → CONVERSATION_UPDATED 逐成员。

## 8. CONVERSATION

| id | 方法 | 请求布局 | 响应 | 事件 |
|----|------|---------|------|------|
| 1 | LIST | 空 | List\<Conversation\> | — |
| 2 | SYNC | `varLong afterVersion` | List\<Conversation\>（增量） | — |
| 3 | SET_DRAFT | `string chatId, string? draft` | 空 | CONVERSATION_UPDATED→自己 |
| 4 | SET_PIN | `string chatId, varInt pinned(0/1)` | 空 | 同上 |
| 5 | SET_MUTE | `string chatId, varInt muted(0/1)` | 空 | 同上 |
| 6 | DELETE | `string chatId` | 空 | CONVERSATION_DELETED→自己（哨兵 chatType=0，客户端只用 chatId） |

**MARK_READ 的级联**（服务端 ConversationService）：
1. 持久化自己 readSeq（行不存在则 INSERT——历史上 no-op 导致换设备全未读）
2. CONVERSATION_UPDATED → 自己
3. 对每个其他成员：持久化其 peerReadSeq（取 max）+ READ_SYNC(peerUid, chatId, readSeq) 推送

## 9. DEVICE

| id | 方法 | 请求布局 | 响应 |
|----|------|---------|------|
| 1 | LIST | 空 | List\<Device\> |
| 2 | KICK | `string deviceId` | 空（同时吊销该设备全部 token） |

## 10. 客户端 Repository 映射（谁封装了哪个方法）

| Repository | 封装方法 | 本地缓存副作用 |
|-----------|---------|---------------|
| MessageRepository | GET_HISTORY（逐条 insertMessage）；其余透传 | getHistory 写缓存 |
| ConversationRepository | LIST（逐条 upsert）；SET_* 透传 | listConversations 写缓存 |
| ContactRepository | LIST（逐条 upsert）；DELETE 后 deleteContact；其余透传 | apply/accept 靠通知更新缓存 |
| ChatRepository | DELETE 后 deleteChat；REMOVE 后 removeMember；其余透传 | 创建/成员变更靠通知 |
| UserRepository | GET_PROFILE 后 upsertUser | |
| DeviceRepository | 全透传，无缓存 | |
| FileRepository | 不走 RPC（HTTP） | |

**本地优先模式的意图**：读方法把服务端数据写进 LocalCache 让 UI 观察；写方法大多不碰缓存——缓存收敛交给 NOTIFY 事件（单一写入路径，避免双写竞态）。例外（DELETE 立即删本地）是为了 UI 即时反馈。
