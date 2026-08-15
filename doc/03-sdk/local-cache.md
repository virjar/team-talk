# LocalCache — 本地缓存与内存治理

> SQLDelight(SQLite) 按 uid 分库 + StateFlow 内存镜像 + 消息窗口 LRU。
> 源码：`shared/.../client/LocalCache.kt`（接口/常量）· `LocalCacheImpl.kt` · `MessageWindow.kt` · `AppDatabase.sq`

---

## 1. 双层结构

```
                    UI / ViewModel（观察）
                          │ StateFlow
        ┌─────────────────┼──────────────────────┐
        ▼                 ▼                      ▼
  usersFlow(全量)  contacts/chats/conversations(全量)  membersFlow: Map<chatId, List>
  [stateLock 保护读改写]                        [消息不走 StateFlow]
                                                    │
                                          chatWindows: LRU(≤20) × MessageWindow(≤100条)
                                                    │ 惰性驻留
                                          SQLite（AppDatabase.sq，7 表）
                                          user/contact/chat/member/message/conversation/sync_cursor
```

**初始化**：构造时全量加载非消息表到 StateFlow（数据量天然有限）；消息按需建窗口。

## 2. stateLock — StateFlow 读改写纪律

`MutableStateFlow.value` 赋值线程安全，但 `value = value.filter{}` 是**非原子读改写**——EventProcessor(IO 线程) 与 UI(Main) 并发 upsert 时 last-write-wins 丢更新（历史 bug）。

**规则**：所有非消息 StateFlow 复合更新必须经
```kotlin
private fun <T> updateFlow(flow, update) = synchronized(stateLock) { flow.value = update(flow.value) }
```
（membersFlow 的 Map 复合更新、toggleConversationPin 的 find+upsert 同样持锁）

## 3. 消息窗口（内存治理，Phase C）

| 常量 | 值 | 含义 |
|------|----|------|
| DEFAULT_MESSAGE_WINDOW | 100 | 单聊常驻窗口 |
| MAX_ACTIVE_CHATS | 20 | 同时驻留窗口数（LRU 淘汰） |
| DEFAULT_PAGE_SIZE | 50 | 上翻分页 |

- `getOrCreateWindow(chatId)`：LRU touch → 未驻留则从 DB 载最新 100 条 → 超 20 个 evict 最旧（**仅内存**，DB 不动，重进重载）
- `insertMessage`：**总是写 DB**；仅当窗口已驻留才更新窗口（未驻留 chat 下次 observe 落库数据）
- `upsert` 按 **clientMsgId 幂等覆盖**——服务端 MESSAGE_RECV 会回环自己发的消息，覆盖而非重复
- `loadMore`：最旧 serverSeq 向上翻 50 条追加尾部；`hasMore=false` 收口
- 窗口膨胀 2×（200 条）→ 修剪回 100 并强制 `hasMore=true`（被剪的可从 DB 再拉）
- `onChatInactive(chatId)`：ChatViewModel.destroy 时释放窗口

## 4. Conversation 合并策略（多设备同步的客户端半边）

```kotlin
mergeConversation(local, remote) = remote.copy(
    readSeq     = max(local.readSeq,     remote.readSeq),      // 水位线只增不减
    peerReadSeq = max(local.peerReadSeq, remote.peerReadSeq),  // 同上
    draft       = local.draft ?: remote.draft,                 // 草稿纯客户端状态，本地优先
)
// unreadCount / lastSeq：服务端权威，直接信任
```

- 为什么 max：本地 markConversationRead 可能比 CONVERSATION_UPDATED 通知先到，盲信 remote 会让红点复活
- 为什么 unreadCount 不再本地防护（历史版本曾"本地清零则强制 0"）：那是掩盖服务端 readSeq 滞后的补丁；根因（markRead no-op + 会话行缺失）已在服务端修复，防护删除后由服务端权威
- 排序不变量：`置顶 desc → lastMsgTimestamp desc`（每次 upsert 重排）

## 5. markConversationRead 的即时语义

进入聊天页 → `messageRepo.markRead(RPC)` + **立即** `localCache.markConversationRead(清零未读/推进 readSeq)`——不等 CONVERSATION_UPDATED 回环。原因：**自己发消息/自己标记不会触发给自己的事件通知**，等回环红点永远不消失。

自己发消息成功后同样本地推进 readSeq 到 ack.serverSeq。

## 6. 接口全景（LocalCache.kt）

| 域 | 方法 |
|----|------|
| 用户 | getUser / upsertUser |
| 联系人 | getContacts(读时联查 user) / observeContacts / upsertContact / deleteContact |
| 聊天 | getChat / upsertChat / deleteChat(联动释放窗口) |
| 成员 | getMembers / observeMembers / upsertMember / removeMember |
| 消息 | insertMessage / updateMessage(seq+status) / updateMessageStatus / getMessages / observeMessages |
| 窗口治理 | pager(chatId, windowSize) / onChatInactive |
| 会话 | get/observe/upsert/delete Conversation / markConversationRead / updatePeerReadSeq / toggleConversationPin |

## 7. 平台落地

| 平台 | 驱动 | 库文件 |
|------|------|--------|
| Android | AndroidSqliteDriver | `cache_$uid.db`（app 私有目录） |
| Desktop(JVM) | JdbcSqliteDriver | `dataDir/users/$uid/cache.db`（dataDir 由 shell 传入，SDK 不感知目录策略） |
| 无头/测试 | FakeLocalCache（纯内存） | — |

schema 变更：`.sq` 改表 = 客户端清库重建（开发期可接受；正式版需迁移方案，见 ROADMAP）。

## 8. FakeLocalCache（testing/，公开测试工具）

- 纯内存实现全部接口；消息 per-chat StateFlow 镜像（同 clientMsgId 幂等）
- `SimpleMessagePager`：hasMore=false、loadMore no-op
- 记录 `inactiveChats` 调用供断言
- 用途：ViewModel 单测、ImBot 零持久化缓存
