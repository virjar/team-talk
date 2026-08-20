# 事件参考

服务器通过 NOTIFY 帧把领域变化推送给用户设备。外层 `NotifyPayload` 格式为：

```text
eventId(varLong) + notifyType(1B) + payload(bytes?)
```

`NotifyContracts` 是 NotifyType 与 payload 类型的唯一映射。服务器发送前校验实际类型，客户端从同一契约表解码。

## 事件表

| code | NotifyType | payload | 典型接收者 | 客户端主要副作用 |
|---:|---|---|---|---|
| 1 | `CONTACT_APPLY` | `ContactApply` | 被申请人 | 写入待处理联系人并提示 |
| 2 | `CONTACT_ACCEPTED` | `Contact` | 双方 | 更新各自视角的好友关系 |
| 3 | `CONTACT_DELETED` | `Contact` | 双方 | 更新或移除联系人投影 |
| 10 | `CHAT_CREATED` | `Chat` | 新会话成员 | 写入 Chat，刷新会话列表 |
| 11 | `CHAT_UPDATED` | `Chat` | 全体成员 | 更新群资料和权限相关状态 |
| 12 | `CHAT_DELETED` | `Chat` | 解散时的原成员；成员移除时仅目标用户 | 删除该 chat 的全部本地投影 |
| 13 | `MEMBER_ADDED` | `Chat` | 群成员 | 刷新群与成员信息 |
| 14 | `MEMBER_REMOVED` | `Chat` | 移除完成后的剩余群成员（不含目标） | 保留群并刷新成员信息 |
| 15 | `MEMBER_MUTED` | `Chat` | 群成员 | 刷新禁言状态 |
| 16 | `MEMBER_UNMUTED` | `Chat` | 群成员 | 刷新禁言状态 |
| 17 | `MEMBER_ROLE_CHANGED` | `Chat` | 群成员 | 刷新角色与管理权限 |
| 20 | `MESSAGE_RECV` | `Message` | 会话成员 | 写本地消息并发布消息流 |
| 30 | `CONVERSATION_UPDATED` | `Conversation` | 该用户设备 | upsert 会话投影 |
| 31 | `CONVERSATION_DELETED` | `Conversation` | 该用户设备 | 按 chatId 删除会话投影 |
| 40 | `PRESENCE` | `PresencePayload` | 好友在线设备 | 发布在线状态流 |
| 41 | `TYPING` | `Message` | 其他会话成员 | 发布 `(chatId, senderUid)` 临时状态 |
| 50 | `READ_SYNC` | `ReadSyncPayload` | 其他会话成员 | 更新 `peerReadSeq` |
| 60 | `USER_UPDATED` | `User` | 当前用户设备 | 更新用户缓存 |
| 99 | `GENERIC` | `GenericPayload` | 扩展定义决定 | 严格解码信封；未注册扩展安全忽略并推进游标 |

踢人、自行退群、受管群收敛移除和服务成员撤权都以同一数据库事务提交成员停用、目标会话行删除
和上述持久事件。目标用户
先收到隐私边界 `CHAT_DELETED`，再收到兼容的 `CONVERSATION_DELETED`，不会再收到容易被
误解为“刷新群成员”的 `MEMBER_REMOVED`；剩余成员只收到 `MEMBER_REMOVED`。客户端处理 `CHAT_DELETED` 时会在一个
SQLite 事务中清除 chat、conversation、草稿 outbox、member、message 和机器人 inbox，已被
界面持有的消息 Flow 保持原对象但立即变空，后续合法重放仍写回同一 Flow。

## 持久事件与临时事件

通过 `SyncEventService` 发出的事件带 uid 内从 1 连续递增的 `eventId`，不同账号可使用相同数字；
服务端保存用户事件。认证成功只建立身份，
不直接推送历史事件；LocalCache 与 EventProcessor 就绪后，客户端用 `SYNC_REQUEST(lastEventId)`
逐批拉取。每批事件全部完成本地投影并把游标单调写入 `sync_cursor` 后，客户端才请求下一批。
服务端最终在同一用户事件门闩内二次确认无遗漏，发送 `SYNC_READY` 后才把连接加入实时推送表。
非零游标若越过当前账号的 `lastSeq`，服务端先发送 `SYNC_RESET` 且保持同步态；客户端原子清空
本地服务器投影、cursor、草稿 outbox 和 bot inbox 后，在同一连接以 0 重新开始。重置失败或重复
RESET 必须断开，不能跳过历史进入实时态。

`eventId = 0` 表示不参与持久游标，当前用于 PRESENCE、TYPING 等瞬时直发。历史消息通过
`MessageRpc.getHistory` 按 chat `serverSeq` 分页读取，不进入持久事件批次，也不推进或覆盖已经保存的
正数事件游标。

TYPING 在产品语义上是临时状态；当前服务端仍通过同步事件设施广播。后续若改为完全非持久直发，必须保持 NotifyType 和 payload 兼容，并补充断线场景测试。

## 收敛规则

1. 领域写入成功后再发事件，事件不能代替权威持久化。
2. UI 不直接消费原始 wire；`EventProcessor` 先更新 LocalCache 或领域事件流。
3. 多次收到同一结果必须安全，缓存写入使用 upsert 或最大水位。
4. payload 解码、投影或游标落盘失败时不请求下一批，并关闭异常连接；自动重连后从已持久游标重试。
5. MESSAGE 既有 chat seq，也有用户事件 eventId：seq 用于聊天历史，eventId 用于跨领域离线补偿，两者不能混用。
6. 新增 NotifyType 必须追加稳定 code、登记 `NotifyContracts`、实现双端处理并通过完备性测试。
7. `SYNC_RESET` 只清服务器事件投影；独立文档草稿 store 不属于该边界，必须保留。

## 消息与会话的联动

发送消息成功通常产生两类事件：

```text
MESSAGE_RECV                 → 每个成员的本地消息表
CONVERSATION_UPDATED        → 每个成员自己的会话摘要、未读和排序
```

编辑或撤回最后一条消息时，也必须更新会话摘要。只刷新消息列表会导致中栏仍显示旧内容。

`markRead` 先更新调用者的 `readSeq` 和会话，再向其他成员发送 `READ_SYNC`，用于对端已读水位。群聊的逐成员已读详情不是当前契约的一部分。
