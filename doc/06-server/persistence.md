# 持久化

## 1. 存储分工

| 数据 | 存储 | 原因 |
|---|---|---|
| 用户、设备、凭据哈希、组织、机器人授权、好友、群、成员、会话、申请、邀请、同步事件、群文件、文档与修订 | PostgreSQL | 关系、约束、事务和查询 |
| 消息正文、幂等索引与投影 outbox | RocksDB | 按 chat/seq 顺序读写、单批原子 KV |
| 文件小对象与元数据 | RocksDB | 本地嵌入、低运维成本 |
| 大文件 | 文件系统 | 避免 KV 大 blob 放大 |
| 消息全文索引 | Lucene | 分词、相关性和高亮 |
| 客户端本地数据 | SQLite/SQLDelight | 离线和 StateFlow 观察 |

## 2. PostgreSQL 关系

### users / devices / credentials

users 保存身份、资料、状态与用户级 `credential_epoch`；devices 保存设备状态、最后活动与设备级
`credential_epoch`。credentials 保存 access/refresh 类型、uid、deviceId、签发时捕获的两层 epoch、
有效期和 token 的 SHA-256；明文 token 不落库，也不能由管理查询恢复。

签发、refresh 轮换、封禁、密码重置和设备撤销使用固定锁序 `users → devices → credentials`。封禁或
密码重置在同一事务推进用户级 epoch，设备撤销推进设备级 epoch；旧 credential 即使尚未物理清理，
校验时也会因 epoch 不匹配而失效。解除封禁只改变账号状态，不回退 epoch，因此不会恢复任何旧 token。
同一用户同一设备再次登录时，事务删除此前 credential，只保留最新 access/refresh pair，避免多个
可长期轮换的设备凭据分支。

### chats / group_chats / group_members

chats 保存共同身份与类型；group_chats 保存群扩展；group_members 保存成员角色和加入状态。禁言
可以使用成员字段或独立表，但权限查询必须得到单一结果。

### friends / friend_applies

friends 以有向双行表达双方视角，备注属于各自记录。friend_applies 保存申请方向、token、状态和创建/
更新时间；收到与发出记录由当前 uid 对应 `to_uid` 或 `from_uid` 得出。创建申请时按固定顺序锁定双方
users 行，使同方向 pending 的查询与插入原子复用；token 仍保存在关系事实中，但读取投影只向待处理
申请的收件人返回。

Contact 是首个完整迁入 `PgUnitOfWork` 的关系聚合。apply、accept、reject、delete、blacklist、解除拉黑、
直接 add/remove 和备注更新都必须携带 outer UoW 提供的不透明事务句柄；Exposed 适配器不能自行开启或
提交写事务。所有双人 mutation 依 uid 排序锁定双方 users 行，accept 同一事务内生成双方 Contact
视角，随后 durable event intents 才按 uid 排序取得 stream 锁并提交。这样故障只会得到“关系事实和
全部事件都提交”或“全部回滚”，不会留下已接受但无通知、单边删除事件等永久裂缝。

`ContactStore` 是无状态门面，不缓存好友 UID。好友关系同时参与聊天授权，旧的无界进程缓存既会随
活跃账号增长，也存在 load 与 mutation 并发时回填提交前旧集合的窗口；当前直接读取 PostgreSQL
权威事实，因而没有需要在事务前更新的本地投影或 after-commit write-through。

### conversations

主键概念是 `(uid, chatId)`。保存 lastSeq、readSeq、peerReadSeq、draft、pin、mute 和版本。单调字段
更新在锁定 Chat 和会话行后使用 max/条件写，避免乱序事件倒退。draft/pin/mute/delete/markRead
必须携带 outer UoW 事务句柄；只有实际改变的行才推进 version。draft/pin/mute 即使同值也会返回并发布
当前权威快照，以收敛“服务端已提交但 RPC 响应丢失”后的客户端重试和本地草稿 outbox。markRead 事务同时推进
读者 readSeq 与已存在对端投影的 peerReadSeq；已删除/缺失的对端 Conversation 没有可持久水位，不伪造 READ_SYNC。

### chat 聚合约束

`chats.personal_key` 保存排序后的私聊用户对并全局唯一；群聊该列为 null。`group_members` 以部分唯一索引
保证每个 Chat 最多一个活跃 owner，`group_member_mutes` 以 `(chat_id, uid)` 唯一并使用 upsert 刷新禁言。
邀请加入的链接额度、成员行和 Conversation 是同一聚合事务，不再由领域服务分步投影。

### sync_events

`sync_streams(uid, last_seq)` 为每个账号分配连续序号；`sync_events` 以 `(uid, stream_seq)` 为复合
主键保存 NotifyType、payload bytes、可选 dedupe key 与 live dispatcher 重试状态。`stream_seq`
继续使用现有 wire `eventId`，不是跨账号全局 ID。认证后由已就绪客户端显式请求
`stream_seq > lastEventId` 的升序有界批次；最终查空与实时连接激活共用 per-user delivery gate。

`PgUnitOfWork` 先运行全部领域 SQL 和事件 intent 构造，block 返回后才按 uid 排序锁定 stream 行、
分配序号并一起提交。stream 锁是命令最后获取的数据库锁；同 uid 后来的事务不能先提交，多 uid
命令也不会留下部分事件。commit 后的 wake 与缓存 callback 都只是进程内提示，崩溃后由 dispatcher
启动扫描恢复；某序号 live push 失败时，同 uid 后续序号不得越过。`dispatched_at` 只表示完成过一次
实时推送尝试，不参与离线 replay 过滤。

除 Contact 与 Conversation 外，尚未逐域迁移的服务暂由 standalone `EventPublisher` 创建 event-only UoW，以保持现有调用兼容；
它仍不能把之前已经提交的领域 mutation 变成同一事务。后续领域迁移必须在 outer `PgWriteScope`
中直接 append，禁止在 UoW 内再次调用 standalone publisher。

当前开发基线用 `SYNC_RESET` 让错误/串账号 cursor 从 0 原子重建投影，但重建仍依赖完整事件历史，
所以不设 TTL，定时 cleanup 是明确 no-op；这保住长离线正确性，代价是表无界增长。正式上线前
必须先增加权威快照/checkpoint bootstrap，之后才能开启保留期和物理删除。

### organization_units / organization_memberships

organization_units 保存单根层级、负责人和可选稳定部门群 ID；organization_memberships 保存直接部门
归属、职位与主部门标记。同一用户的唯一主部门由用户行锁串行化写入，并由 `is_primary = true` 的部分唯一索引兜底。群成员表只是组织事实的
投影，不能反向编辑组织关系。

### automation_bots / automation_bot_grants

automation_bots 关联服务 User，只保存 webhook token 哈希、状态和最后调用时间；明文 token 不落库。
automation_bot_grants 以 `(botId, chatId)` 唯一，作为可发送群的权限事实。group_members 中的机器人行
是可修复投影，服务启动按 grant 重放。

### group_file_entries / group_file_versions / group_file_audits

group_file_entries 保存群文件目录树、逻辑名称、当前 Attachment、revision 和当前内容版本。根目录用
稳定 parentKey 参与同级名称唯一约束；软删除时释放名称键，允许重新创建同名条目。

group_file_versions 只追加不可变 Attachment 快照，`(entryId, version)` 唯一。group_file_audits 与每次
创建、追加版本、重命名、删除在同一 PostgreSQL 事务提交，只记录动作与有限摘要，不保存文件正文。
物理二进制仍在 FileStore；数据库版本表是下载引用和群空间配额的事实源。
所有写事务先锁定对应的活动群行并复验操作者的活动成员行；服务层事务外的 ACL 预检不能替代这一
安全边界。

### document_spaces / document_space_grants

document_spaces 保存空间元数据、创建者所有权与归档状态。document_space_grants 以
`(spaceId, principalType, principalId)` 唯一，保存用户或组织部门的角色以及是否包含下级部门；它不
复制部门成员，实时有效角色由领域服务计算。

### document_nodes / document_content_revisions

document_nodes 同时保存目录树、文档当前 Markdown 快照、有界 excerpt 投影、revision 和创建/修改身份；
文件夹正文为 null。目录和首页查询必须只投影 excerpt，不能用 `selectAll` 把正文载入内存。parentId 必须
指向同空间的活动文件夹。活动文件夹图最深包含 128 个文件夹，文档因而最多返回 128 个祖先 ID。创建、
移动和删除在同一
PostgreSQL 事务中锁定 document_spaces 行，然后复验父节点、环、整个活动子树深度和空目录约束；
领域层的提前检查只用于快速报错，仓储事务才是并发下的最终不变量边界。删除只改变 status 并推进
revision。

当前祖先链解析采用最多 128 次的有界逐层 SQL，代码简单且便于每层执行防御性校验。当单空间规模或深层
打开频率成为瓶颈时，应改为受深度限制的 recursive CTE 或批量路径投影，但不能牺牲同空间、活动文件夹和防环校验。

document_content_revisions 只追加每次成功保存的标题与完整 Markdown 快照，
`(documentId, revision)` 唯一。更新在锁定 document_nodes 当前行的同一事务内完成 revision 条件写和
修订插入，避免两个并发保存都成功。完整快照简化恢复与验收，但会增加存储；增量压缩、保留期和管理
员审计属于生产化后续设计。

document_user_recents 以 `(uid, documentId)` 为主键保存最后访问时间。创建文档时，创建者的访问记录与
文档及首个修订在同一事务提交；读取正文后的访问更新是辅助索引，失败不得把已授权正文伪装成读取
失败。最近列表查询仍需实时过滤空间 ACL、空间归档和节点删除，历史访问记录本身不是权限凭据。

## 3. MessageStore

消息主键按 chatId 前缀和 big-endian serverSeq 编码，使 RocksDB 范围扫描天然按序：

```text
[chatId bytes][serverSeq 8B BE] → Message bytes
```

另有 clientMsgId 幂等索引指向 chatId/serverSeq。分配 seq、写消息和写幂等索引需要保持可恢复顺序；
重复请求必须返回已存在消息。

MessageStore 在同一 RocksDB `WriteBatch` 中写入消息、clientMsgId 索引、消息 revision 和不可变操作记录：

```text
[0x05][chatId length+bytes][serverSeq 8B][revision 8B][operation 1B]
    → { CREATE | EDIT | REVOKE, revision, Message, chatType, sorted recipient snapshot }
[0x06][chatId length+bytes][serverSeq 8B] → latest revision 8B
```

`projectionKey = message/v1/{length}:{chatId}/{serverSeq}` 在消息整个生命周期内稳定，revision 从 CREATE=1
开始递增。ACK 只删除完全匹配的 operation key，因此旧 projector 不可能误删稍后写入的 EDIT/REVOKE。
相同 canonical edit 和已经撤回的 revoke 重试是 no-op，不会人为制造新 revision。

Lucene、Conversation 和 `sync_events` 都完成后才精确删除该 operation。幂等重试和服务启动会扫描并
补偿未完成项；启动恢复循环分页直到全局 outbox 连续两次为空，不受单页 1000 条限制。永久失败会
保留 operation、使 `message-projection` readiness 为 DOWN，并阻止启动进入 TCP 服务阶段。

## 4. 派生数据

Lucene 索引、会话预览、缩略图和部分计数都是派生数据：

- 派生写失败需要 fault 日志和重建/补偿方式。
- 搜索结果不能反向成为消息权威。
- 重建工具读取 MessageStore，不从客户端缓存回灌。
- 健康检查应区分“索引不可用”和“消息已丢失”。

Lucene 文档保存同一个稳定 projectionKey 和最新 revision。`applyProjection` 只接受更大的 revision，
并在返回前 commit；EDIT 覆盖原文，空正文和 REVOKE 写入带 revision 的不可搜索 tombstone。进程重启
从 live document 恢复 revision fence，所以较旧操作不能让已撤回正文复活。

## 5. 一致性与事务

PostgreSQL 事务只覆盖关系表；它不能原子覆盖 RocksDB/Lucene。跨存储流程必须用业务顺序保证：

1. 权威消息与 outbox 原子写成功。
2. 按 revision 幂等更新 Lucene 并 commit。
3. 在 PostgreSQL UoW 中插入 `external_projection_receipts(projection_key, revision)`、更新 Conversation，
   并为快照中当前仍活跃的成员追加 MESSAGE_RECV / CONVERSATION_UPDATED；receipt、关系投影和事件一次提交。
4. 清除 outbox 并对外返回成功。

receipt 同时保存 operation、消息身份和完整 payload/recipient/preview 摘要；同 key/revision 内容不同会
fail-fast。若 PostgreSQL 已提交而进程在 Rocks ACK 前退出，重放命中 receipt，不再分配新的 eventId，
包括机器人账号的 inbox 也只收到该 revision 一次。若第 2 或第 3 步失败，outbox 保留并由重试/重启
补偿。不能在权威写入前推送事件，也不能在补偿未完成时把重复 `clientMsgId` 直接解释为完整成功。

recipient snapshot 是消息命令被接受时的最大收件集合。正常路径由同一 chat lifecycle gate 覆盖快照、
Rocks 写入和投影，因此集合不变；异常停顿期间若成员事实先发生变化，恢复使用
`snapshot ∩ current active members`。后来加入者不补旧事件，已经移除的成员也不会在
MEMBER_REMOVED/CHAT_DELETED 之后收到一条更晚的旧 MESSAGE_RECV；剩余成员仍可按稳定消息身份收敛。
若 chat 已失效，该 operation 记录 receipt 后不再发布事件并清除 outbox。

## 6. Schema epoch 与生命周期

正式发布前的服务端持久化使用单一 epoch（以 `ServerDataEpoch.CURRENT_EPOCH` 为唯一事实源）。空 PostgreSQL 首次启动时一次性创建当前全部表、约束和
索引，并写入 `schema_metadata`；以后启动只校验 epoch，不再执行 `ALTER TABLE`、历史数据归并或
`createMissingTablesAndColumns`。这使数据库结构问题在启动阶段明确失败，而不是把一次性兼容 SQL
永久留在每次启动路径。

当前 `ServerDataEpoch.CURRENT_EPOCH` 为 6。关系库校验通过后，数据根目录还必须有同 epoch 的
`data-epoch` marker。消息 RocksDB、Lucene、
FileStore RocksDB 与大文件目录被视为一个整体：marker 缺失时只有这些目录全部为空才能初始化；marker
不匹配或缺失但已有数据时启动失败。尤其是 Message 的 wire 字段或 MessageType 重排后，服务端不能把
旧 RocksDB 字节交给新 decoder 碰运气解析；Lucene 虽是派生数据，也不能与另一代消息混用。

epoch 缺失或不匹配表示当前测试数据已经过期，服务端会抛出 `SchemaResetRequiredException` 或
`DataResetRequiredException` 并拒绝提供服务。开发和测试实例应停止写入后重建 PostgreSQL
schema/volume 与服务端 durable data，再重新启动；只清空表数据不能
把旧列结构升级为当前结构。关系数据允许丢弃，但 RocksDB 与 FileStore 数据也应按同一轮测试数据整体
重置，不能把不同 epoch 的存储拼接使用。epoch 6 不读取或迁移已经删除的 RocksDB TokenStore；旧
access/refresh token 永久失效，客户端必须重新登录。

当前好友申请的“同方向只能有一条 pending”直接由最终 schema 的部分唯一索引保证；草稿正文从建库
起就是文本列。启动流程不再识别、修补或标记旧重复申请。

正式发布前仍必须把这一策略替换为可审计的版本化迁移，并补齐：

- PostgreSQL 向前迁移及回滚/恢复演练；
- RocksDB key/version 迁移策略；
- sync_events 与孤儿文件清理策略；
- 备份、恢复和一致性校验工具。

这些未完成项集中维护在[功能状态](../10-reference/feature-status.md)和
[路线图](../10-reference/roadmap.md)，不混入当前 schema 描述。
