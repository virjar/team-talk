# RPC 方法矩阵 — ServiceId / MethodId / payload 布局

> 全部 RPC 方法的路由枚举与请求/响应 payload 布局。
> 源码：`shared/.../protocol/RpcMethod.kt`（枚举唯一事实源）+ `server/.../protocol/dispatcher/*RouteHandler.kt`（服务端解码顺序）+ `shared/.../repository/*.kt`（客户端编码）。
> 编码原语见 [wire-format](wire-format.md#3-packetbuffer-原语表)。

---

## 1. 路由机制

```
客户端 InvokePayload(requestId, serviceId, methodId, payload)
 → 服务端 RpcDispatcher.dispatch
    → route(serviceId) → <X>RouteHandler.route(uid, methodId, payload)
    → ResponsePayload(requestId, status, result)
```

- 枚举 `when` 分派，无巨型 when 块；加方法 = 枚举 + handler 分支
- **认证前置**：dispatch 仅在 ImAgent AUTHENTICATED 后可达；uid 由连接层注入，handler 不再验 token
- 客户端前置校验（AuthRules）：username 3-50 字符、password ≥6——与服务端规则镜像，避免无效请求出门

## 2. ServiceId

| id | 服务 | handler |
|----|------|---------|
| 1 | AUTH | AuthRouteHandler |
| 2 | USER | UserRouteHandler |
| 3 | CONTACT | ContactRouteHandler |
| 4 | CHAT | ChatRouteHandler |
| 5 | MESSAGE | MessageRouteHandler |
| 6 | CONVERSATION | ConversationRouteHandler |
| 7 | DEVICE | DeviceRouteHandler |
| 99 | GENERIC | GenericRouteHandler（methodId=ExtensionType.code；当前无注册扩展） |

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
