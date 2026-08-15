# NOTIFY 契约表 — 唯一事实源机制

> NotifyType → payload 类型的绑定规则、三层防线、以及为什么需要它。
> 源码：`shared/.../protocol/NotifyContracts.kt` + `server/.../infra/sync/SyncEventService.kt` + `shared/src/commonTest/.../NotifyContractTest.kt`

---

## 1. 问题：契约漂移

NOTIFY 的 payload 是不透明字节，**类型信息不在帧里**。两端各自维护"这个 NotifyType 对应什么类"的映射：

- 服务端 emit 侧：`syncEventService.emitEvent(uid, NotifyType.X, somePayload)`
- 客户端 decode 侧：`when (notifyType) { X -> ProtoCodec.decode(SomeClass, payload) }`

两边手写、编译器看不见彼此 → 漂移只在运行时爆炸，且**症状在 UI 层**（数据错乱/解析异常），排查要穿透 4 层。历史上两次翻车：

1. **CONTACT_APPLY 错配**（commit 3b74b64 修复）：服务端发 `ContactApply`，客户端按 `Contact` 解
2. **CONTACT_ACCEPTED/DELETED 错配**（契约表引入时修复）：服务端发 `Contact`，客户端按 `ContactApply` 解——wire 布局完全不同（3 string vs varlong+4 string），字符串错位产生垃圾数据

## 2. 机制：三层防线

```
                 ┌────────────────────────────────┐
                 │  NotifyContracts（shared）       │
                 │  Map<NotifyType, IProtoReader>  │  ← 唯一事实源
                 └──────┬──────────────┬───────────┘
                        │              │
          服务端 emit 前 │              │ 客户端 decode 时
                        ▼              ▼
        SyncEventService.assertContract   EventProcessor.decodePayload<T>
        （payload::class vs 契约类名，     （reader 从表取，
          错配当场抛 IllegalStateException   不允许手写旁路）
          → 测试期失败）                        │
                                             ▼
                                  NotifyContractTest
                                  （完备性 + round-trip + 类名解析，
                                    改任一侧不同步表 → CI 红）
```

**新增 NOTIFY 类型的完整流程**（缺一步 CI 就红）：
1. `NotifyType.kt` 加枚举
2. `NotifyContracts.payloads` 登记契约（或 `exempt` 注明豁免原因）
3. 服务端 emit 点 + 客户端 EventProcessor 分支
4. `NotifyContractTest.sampleOf` 补最小样例（round-trip 需要）

## 3. 契约表（18 登记 + 1 豁免）

| NotifyType (code) | 契约 payload | 服务端发射方 → 接收者 |
|-------------------|-------------|---------------------|
| CONTACT_APPLY (1) | **ContactApply** | ContactService.apply → 申请接收者 |
| CONTACT_ACCEPTED (2) | **Contact**（各自视角） | ContactService.accept → 双方 |
| CONTACT_DELETED (3) | **Contact**（各自视角） | ContactService.deleteFriend → 双方 |
| CHAT_CREATED (10) | Chat | ChatService（创建/加成员/邀请入群）→ 相关成员 |
| CHAT_UPDATED (11) | Chat | updateGroup / muteAll/unmuteAll → 全员 |
| CHAT_DELETED (12) | Chat | deleteChat → 全员（删除前快照） |
| MEMBER_ADDED (13) | Chat | addMembers → 全员 |
| MEMBER_REMOVED (14) | Chat | removeMember → 全员 + 被移除者 |
| MEMBER_MUTED (15) / UNMUTED (16) | Chat | mute/unmuteMember → 全员 |
| MEMBER_ROLE_CHANGED (17) | Chat | transferOwner / setRole → 全员 |
| MESSAGE_RECV (20) | **Message** | send/revoke/edit/forward → **全体成员含发送者** |
| CONVERSATION_UPDATED (30) | **Conversation** | onMessageReceived 逐成员 / setDraft/Pin/Mute/markRead → 自己 |
| CONVERSATION_DELETED (31) | Conversation（哨兵 chatType=0） | deleteConversation → 自己 |
| TYPING (41) | Message（仅用 chatId/senderUid） | ImAgent.handleTyping → 成员-发送者 |
| READ_SYNC (50) | **ReadSyncPayload** | markRead → 其他成员 |
| USER_UPDATED (60) | User | updateProfile → 自己 |
| GENERIC (99) | GenericPayload | 预留扩展入口（客户端静默忽略未注册扩展） |
| PRESENCE (40) | —（**豁免**） | PresenceService 直写不持久化；服务端当前未启用，客户端仅记日志 |

## 4. 客户端 decode 规范

```kotlin
// EventProcessor 内唯一合法的 decode 路径
private fun <T : IProto> decodePayload(type: NotifyType, payload: ByteArray): T {
    val reader = NotifyContracts.payloads[type]
        ?: throw IllegalStateException("No payload contract for $type")
    return ProtoCodec.decode(reader as IProtoReader<T>, payload)
}
```

- 禁止在 EventProcessor 之外对 NOTIFY payload 手写 `ProtoCodec.decode`
- 豁免类型（PRESENCE）分支只记日志，**仍算处理成功**（推进游标）

## 5. 服务端 emit 规范

- 所有业务事件必须走 `SyncEventService.emitEvent/emitEvents`（持久化 + 在线推送 + 契约校验）
- 例外：Presence 直写 agent（不持久化）；SUBSCRIBE 历史回放 eventId=0
- 校验实现：reader 是 data class 的 companion，其 JVM 类名去 `$Companion` 后缀与 payload::class.java.name 比对

## 6. 视角规则（Contact 类事件特有）

CONTACT_ACCEPTED / CONTACT_DELETED 的 payload 是 **Contact**，且必须按**接收者视角**构造：
- `uid` = 接收者自己，`friendUid` = 对方，`user` = 对方的 User

历史 bug：accept 曾把申请方视角的同一个 Contact 发给双方——接收方入库后 friendUid 是反的。服务端现为每个接收者分别构造。

## 7. 测试（NotifyContractTest，shared commonTest）

| 测试 | 断言 |
|------|------|
| 完备性 | `payloads.keys + exempt == NotifyType.entries`（新类型不登记→红） |
| 无重叠 | exempt 与 payloads 不相交 |
| round-trip | 每契约样例 encode→decode 结构相等（GenericPayload 已重写 ByteArray 内容 equals） |
| 类名解析 | reader 去后缀类名 == 样例类名（保证服务端 assertContract 可靠） |
