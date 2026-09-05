# 事件参考

服务器通过 NOTIFY 帧把领域变化推送给用户设备。外层 `NotifyPayload` 格式为：

```text
eventId(varLong) + notifyType(1B) + payload(bytes?)
```

`NotifyContracts` 是 NotifyType 与 payload 类型的唯一映射。服务器发送前校验实际类型，客户端从同一契约表解码。

## 事件表

| code | NotifyType | payload | 典型接收者 | 客户端主要副作用 |
|---:|---|---|---|---|
| 1 | `CONTACT_APPLY` | `ContactApply` | 申请双方（新申请仅被申请人） | 新申请或处理状态变化提示；客户端权威刷新历史、资料状态与红点，不直接写好友关系 |
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
| 21 | `MESSAGE_REACTION` | `MessageReactionEventPayload(chatId, serverSeq, emoji, actorUid, action)` | 会话成员 | 行级 upsert/delete 本地回应投影；重放收敛到同一状态，聚合快照以 `listReactions` 为权威 |
| 30 | `CONVERSATION_UPDATED` | `Conversation` | 该用户设备 | upsert 会话投影 |
| 31 | `CONVERSATION_DELETED` | `Conversation` | 该用户设备 | 仅删除用户主动隐藏的会话视图 |
| 40 | `PRESENCE` | `PresencePayload(serverEpoch, revision, uid, status, lastSeenAt)` | 好友在线设备 | 按 epoch/revision 收敛会话内好友在线投影 |
| 41 | `TYPING` | `Message` | 其他会话成员 | 为 `(chatId, senderUid)` 续期 3 秒临时状态 |
| 50 | `READ_SYNC` | `ReadSyncPayload` | 其他会话成员 | 更新 `peerReadSeq` |
| 60 | `USER_UPDATED` | `User` | durable：本人和提交时的活动好友；transient：其余 SYNC_READY 会话 | durable 与资料事实同事务；其余在线会话收到 `eventId=0` best-effort 完整 User，按正数单调 revision 丢弃跨通道旧值，并排除 durable 收件人避免重复 |
| 61 | `ORGANIZATION_CHANGED` | `OrganizationChangedPayload(revision)` | 已完成 `SYNC_READY` 的在线终端 | 持久提升组织 `requiredRevision`，将旧快照标为非权威并合并触发二进制 RPC 对账 |
| 62 | `EVENT_CURSOR_ADVANCED` | 无 payload | 连接输出投影 | 仅推进对应持久 eventId；不创建业务事实，不进入领域持久事件发布入口 |

踢人、自行退群、受管群收敛移除和服务成员撤权都以同一数据库事务提交成员停用、目标会话行删除
和上述持久事件。目标用户只收到唯一隐私边界 `CHAT_DELETED`，不会再收到容易被
误解为“刷新群成员”的 `MEMBER_REMOVED`；剩余成员只收到 `MEMBER_REMOVED`。
`CONVERSATION_DELETED` 仅用于账号主动删除某个会话视图，不与授权撤销叠加。客户端处理 `CHAT_DELETED` 时会在一个
SQLite 事务中清除 chat、conversation、草稿 outbox、member、message、该 chat 的 outgoing 和机器人 inbox，已被
界面持有的消息 Flow 保持原对象但立即变空，后续合法重放仍写回同一 Flow。

## 持久事件与临时事件

通过 `SyncEventService` 发出的事件带 uid 内从 1 连续递增的 `eventId`，不同账号可使用相同数字；
服务端在有界保留窗内保存用户事件。认证成功只建立身份，
不直接推送历史事件；与 AUTH `datasetId` 一致的 LocalCache 与 EventProcessor 就绪后，客户端用
`SYNC_REQUEST(lastEventId, datasetId)` 逐批拉取。每批事件全部完成本地投影并把 dataset + 游标原子
写入 `sync_state` 后，客户端才请求下一批。
服务端最终在同一用户事件门闩内二次确认无遗漏，发送 `SYNC_READY` 后才把连接加入实时推送表。
datasetId 不匹配、游标低于当前 `compactedThrough` 或越过当前账号的 `lastSeq` 时，
服务端先发送携带权威 datasetId 的 `SYNC_RESET` 且保持同步态。客户端在同一连接使用
二进制 `SyncRpc` 收齐 current User、Contact、Chat 和 Conversation 的独立 keyset 页；
这些页不共用跨 RPC 的 MVCC snapshot。全部 section 通过验证后，本地只在 expected
dataset + cursor 仍精确匹配时，以一个 SQLite 事务替换紧凑服务器投影并把游标设为
header 的 `baseEventId`，然后以 `SYNC_REQUEST(baseEventId, datasetId)` 拉取 tail。
本地 outgoing、bot inbox/retained floor、conversation draft/read 和可靠命令 outbox 均保留，
权威投影安装后再叠加。收集或原子安装失败、重复 RESET 必须断开，不能跳过 tail 进入实时态。

`sync_events` 默认保留 30 天。物理清理只跨越已完成进程内推送尝试且已过期的连续前缀，在一个
事务内同时删行并推进 `compactedThrough`；replay/checkpoint lease 与 per-user delivery gate
防止清理跨过已准入连接仍需要的游标。因此 at-least-once 只对仍在服务端保留窗内、或已进入
本地可靠 inbox 的事件成立；超长离线跨过窗口后，checkpoint 只恢复当前权威投影，历史消息由
Message history RPC 读取，已压缩的过往 delivery/编辑/撤回回调不会补发。

`eventId = 0` 表示不参与持久游标，当前用于 PRESENCE、TYPING、非好友在线会话的 `USER_UPDATED` 和
`ORGANIZATION_CHANGED` 等瞬时直发。USER_UPDATED 的本人/当前好友副本仍使用正 eventId 持久流；广播只补
其余 SYNC_READY 会话，断线不补发，权威刷新负责恢复。`User.revision` 统一排序 durable、transient、RPC 与
Conversation peer 快照，防止动态好友 audience 或并发 publication 把新资料回退成旧值。
`ORGANIZATION_CHANGED` 只发给已经完成 `SYNC_READY` 的在线连接，payload 只含已提交组织 revision；
服务端不为每个账号追加一条持久组织事件，也不承诺断线补发。客户端先把 revision 单调写入
LocalCache 的 `requiredRevision`，使更旧的单位/成员快照变为“可离线展示但不完整”，再合并触发
`OrganizationRpc` 刷新。每次重新进入 `AUTHENTICATED` 都无条件执行一次完整 revision-fenced 对账，
因此断线漏掉提示不会永久停留在旧 revision。

PRESENCE 的 `serverEpoch` 是服务进程启动时生成的规范 UUID；每个首设备上线/末设备下线 transition 在
Registry 串行 owner 内取得正数 revision。online 固定 `lastSeenAt = 0`，offline 携带末台设备离线的
发生时间。登录基线由 `contact.getPresenceSnapshot()` 返回：`revision` 可为 0，`friendUids` 是当前
认证用户的完整好友集合，`onlineFriendUids` 是同一个 Registry revision 下的在线子集。

客户端 Presence 投影只存在于当前 session。每次进入 `AUTHENTICATED`、联系人提示或 serverEpoch 变化
都会合并触发新快照；迟到快照由 refresh generation 拒绝。同 epoch 下，revision 不大的增量或快照
不能覆盖新状态；快照前到达的事件保持不可见，只有快照确认该 uid 仍是好友时才发布，快照遗漏则移除
旧好友。epoch 变化、断线、quiesce 或 close 都先撤下旧投影并回到 UNKNOWN，不能把旧 ONLINE 持久化
或显示成仍然权威。

PRESENCE 增量与 TYPING 都通过瞬时发布端口直接发送给当前在线连接，不写 `sync_events`，也不在重连后
补发。历史消息通过 `MessageRpc.getHistory` 按 chat `serverSeq` 分页读取，不进入持久事件批次，也不
推进或覆盖已经保存的正数事件游标。同 epoch 的单条 PRESENCE 增量丢失不会触发即时补拉；客户端只在
上述认证/重连、联系人变化或换代等既定刷新点以完整快照重建基线。

TYPING 的发送方通过 MESSAGE 通道提交 `MessageType.TYPING` 空正文信封；服务端以认证 uid 覆盖
`senderUid`，在同一个权威会话成员读取中确定其他接收者，然后用 `NotifyType.TYPING` 直发且不返回
MESSAGE_ACK。过载或 transport 未就绪时信号直接丢弃，不进入发送 outbox。图形客户端只在前台聊天的
真实正文变化上尝试发送，成功准入后才开启 2 秒 leading throttle；接收端每次信号续期 3 秒，并在
断线、该发送者的新消息进入当前投影、切换/销毁聊天时立即清除。

## 收敛规则

1. 领域写入成功后再发事件，事件不能代替权威持久化。
2. UI 不直接消费原始 wire；`EventProcessor` 先更新 LocalCache 或领域事件流。
3. 多次收到同一结果必须安全，缓存写入使用 upsert 或最大水位。
4. payload 解码、投影或游标落盘失败时不请求下一批，并关闭异常连接；自动重连后从已持久游标重试。
5. MESSAGE 既有 chat seq，也有用户事件 eventId：seq 用于聊天历史，eventId 用于跨领域离线补偿，两者不能混用。
6. 新增 NotifyType 必须追加稳定 code、登记 `NotifyContracts`、实现双端处理并通过完备性测试。
7. 同一 dataset 内的 `SYNC_RESET` 只用 checkpoint 替换紧凑服务器投影；outgoing、Bot inbox、
   会话草稿/已读 outbox 和独立
   文档草稿 store 不属于该边界，必须保留。若认证得到不同 `datasetId`，独立文档 store 必须切换
   deployment + dataset + uid 命名空间，旧 dataset 的草稿与可靠文档 operation 不得重放。

## 消息与会话的联动

发送消息成功通常产生两类事件：

```text
MESSAGE_RECV                 → 每个成员的本地消息表
CONVERSATION_UPDATED        → 每个成员自己的会话摘要、未读和排序
```

编辑或撤回最后一条消息时，也必须更新会话摘要。只刷新消息列表会导致中栏仍显示旧内容。

`markRead` 先更新调用者的 `readSeq` 和会话，再向其他成员发送 `READ_SYNC`，用于对端已读水位。群聊的逐成员已读详情不是当前契约的一部分。
