# 持久化

## 1. 存储分工

| 数据 | 存储 | 原因 |
|---|---|---|
| 用户、设备、凭据哈希、组织、机器人授权、好友、群、成员、会话、申请、邀请、同步事件、群文件、文档与修订 | PostgreSQL | 关系、约束、事务和查询 |
| 消息序号高水位、正文、幂等索引与投影 outbox | RocksDB | 按 chat/seq 顺序读写、单批原子 KV |
| 文件小对象、元数据与上传事务收据 | RocksDB | 本地嵌入、低运维成本、对象与精确上传结果共同恢复 |
| 大文件 | 文件系统 | 避免 KV 大 blob 放大 |
| 客户端设备画像、采集策略与遥测管理审计 | PostgreSQL | 低频关系控制面、真实管理员 actor 和行政查询审计 |
| 消息全文索引 | Lucene `lucene-index` | 可从权威消息重建的派生投影 |
| 7日客户端遥测事件与上传收据 | Lucene `client-telemetry-index` | 可丢失诊断数据；本机全文/过滤查询，避免关系库双写 |
| 7日服务端连接轨迹 | Lucene `connection-trace-index` | 与消息/客户端遥测隔离；有界、可清空、按精确连接代际联查 |
| 客户端本地数据 | SQLite/SQLDelight | 离线和 StateFlow 观察 |

FileStore 的 RocksDB metadata 同时是文件系统对象的容量权威、上传者归属事实和恢复日志。默认容量硬
边界是全局 10 GiB/100,000 对象，以及每个 metadata `uid` 2 GiB/20,000 对象；0B 对象仍占两层对象槽。
大文件创建按 `PENDING_CREATE(metadata + global/owner 计量) → 实体 move/force → ACTIVE`，删除按
`ACTIVE → PENDING_DELETE → 实体确认消失 → metadata 删除 + global/owner 扣减`。每次状态切换都使用
同步 WAL；实体确认消失前禁止先删 metadata 或释放任一层容量。小对象的 metadata/payload 创建和删除
仍由单一 RocksDB `WriteBatch` 原子完成；batch 写入或补偿删除失败时两层内存占用保持不可借出，实例
失败关闭，重启再从 metadata 决定最终账本。

FileStore 另有独立 `uploads` column family，以认证 uid + canonical uploadId 保存严格 JSON 上传事务。
路由在复制正文前同步写 `STARTED`；其预留尺寸进入内存 pending 账本，物化主文件/缩略图时按原尺寸从
pending 转入 stored。每个事务对象的 metadata 同时保存 transaction key、attempt token 与对象序号，
因此启动可以把崩溃遗留的 `STARTED` 对象逐项回滚，而不按文件名或目录猜测归属。完成时同步写
`COMPLETED`，其中包含规范请求指纹、descriptor、租约和即将交付的原始 JSON 收据；HTTP 200 丢失或
重启后只有完整重放同一正文才能取得这一字节完全相同的结果。

启动 reconcile 会先联合读取 uploads 与 metadata：所有 `STARTED` 的 RocksDB/文件系统实体确认删除后
才删除事务；所有未过期 `COMPLETED` 必须拥有完全匹配的 ACTIVE metadata 和 backing。过期完成记录会在
同一 RocksDB batch 删除并清空对应 metadata 的 upload ownership，之后对象仍由业务引用和未引用租约
管理。首次与重放 HTTP delivery 只持有短暂进程内 pin，防止维护在响应窗口拆除收据；pin 不进入持久
schema，也不承担客户端 outbox 职责。

启动必须联合扫描 RocksDB `metadata` / `data` / `uploads` 三个业务 column family 与文件目录，流式校验
uid、size、路径归属与加法溢出，
完成 pending、删除 dangling/orphan，并从剩余权威记录同时重建 global 与 per-uid usage。owner map 的
基数由全局对象上限约束；任一 owner 或全局历史用量超限、记录损坏或状态无法确定收敛，都会阻止
FileStore 发布运行标志和健康，不能静默截断或回退为空账本。该按上传者硬围栏仍只是部署级硬容量，
不等同于组织计费；可审计组织配额、保留策略和孤儿上传 GC 仍是独立生命周期问题。

### 客户端遥测数据面与控制面

客户端遥测不使用 `uid/device/date` 文件树，也不把高频事件复制进 PostgreSQL。请求正文不能声明
uid/deviceId，HTTP Bearer 对应的 principal 是唯一身份来源。非空 batch 进入单一有界 Lucene writer；
writer 在很短的时间/条数窗口内合并请求，并用一次 commit 原子发布该批全部事件和一条 receipt 文档。
receipt 保存 owner、batchId、payload hash、末尾 sequence 与 receivedAt：响应丢失后的精确重试可在
内容策略已经回落时继续 ACK，hash 冲突则固定拒绝。ACK 只在 durable commit 成功后发出；提交前崩溃
整批不可见，提交后崩溃整批可见。writer 队列和每组总事件/字节均有硬上限，不能把 HTTP 并发转成
无界内存或每请求一次 fsync。

`client-telemetry-index` 同时承担 7 日诊断存储和全文/过滤查询，按服务端 `receivedAt` 删除事件及
receipt。它不是业务事实，也没有 PostgreSQL 重建源；进程内单 writer 在准入时校验 batch/event key、
eventId 唯一性、单一 runId、严格递增 sequence 和容量计费。正常启动只校验 schema/READY commit marker、
live document 计数、无 tombstone，以及每个 segment 的固定 FieldInfo 结构，不扫描 stored event、term 或
重跑全文分析；结构异常、构建中断或 schema 不兼容时安全清空为空。该可丢失边界避免为短期日志付出
关系库网络、WAL、表膨胀和 Lucene 双写成本，也把启动工作约束在 segment 数与固定字段数上。
初次 open/reset 失败不会阻断核心 IM 启动，maintenance 每 5 秒重试；writer 已进入终态时关闭写入和
查询 channel，避免同一对象原地复活，必须通过进程重启取得新的生命周期。

PostgreSQL 只保存 `client_telemetry_devices` 的最新运行画像，以及 `client_telemetry_policies` 和 audit
控制事实。空 heartbeat 不创建 receipt/event，只按条件更新设备画像并返回当前策略；手机号仅在管理
application 边界解析为 uid，不进入画像、策略目标或 Lucene 文档。设备画像和 exact-device 诊断策略
隶属于当前认证安装 generation：设备撤销或槽位回收会删除该设备画像与 exact 策略，旧 credential epoch
的迟到 refresh 固定 no-op；uid-wide 策略属于账号级控制事实，不随单个设备撤销删除。PG 策略到期清理
与 Lucene 168 小时物理清理是两个隔离的 maintenance step，前者失败不能阻断后者删除诊断正文。

画像与遥测事件中的 `protocolVersion` 保存独立协议数字 ID：`(major << 16) | minor`，范围为
`0..Int.MAX_VALUE`。PostgreSQL 的 `INTEGER` 与 Lucene 的整数 StoredField 原本就能表示这个范围，
本次只放宽旧的 `<=255` 约束和入库校验，保留已有字段及记录；已有关系库通过下述 0 号迁移升级，
不改变 dataset 或存储 epoch，也不为此重建 Lucene。

每条客户端遥测事件可额外保存一个完整或完全缺省的连接上下文：correlationId、traceId、sessionId、
connectionGeneration 与 policyRevision。管理端先按内部 event record id 精确读取事件，再以该事件从
Bearer 身份固化的 uid/deviceId、全部五个上下文字段和 7 日时间窗查询 `connection-trace-index`；
不存在的事件返回 404，旧事件没有上下文时安全返回空集合，不存在按 uid 或相似时间做猜测联查。
客户端回传的五字段上下文只是非权威 correlation hint：它不能改写 uid/deviceId，但同一账号和设备仍可
篡改或重放自己的遥测正文，所以联查结果不是事件真实性或因果关系的安全证据。连接轨迹自身采用固定 enum
phase/outcome、短白名单 detail 和单一 writer，具有 queue-event/queued-byte/single-event/document/
accounted-byte/physical-byte 六层硬预算；Lucene 异常只关闭或清空该可丢诊断边界，不传播到 IM。

`client_telemetry_admin_audits` 追加记录事件搜索、精确联查和策略启停的真实 admin actor、固定动作、
稳定目标、结果与时间。成功查询若审计无法持久化会失败关闭；业务操作已经失败时会尽力记录 REJECTED
或 FAILED 且不以审计异常覆盖原失败。查询条件、手机号、策略 reason、trace token 和正文不进入审计。

## 2. PostgreSQL 关系

### users / devices / credentials

users 保存身份、资料、状态、用户级 `credential_epoch` 与单调 `device_credential_sequence`；devices
保存设备状态、最后活动与从该账号序列分配的设备级 `credential_epoch`。credentials 保存
access/refresh 类型、uid、deviceId、签发时捕获的两层 epoch、
有效期和 token 的 SHA-256；明文 token 不落库，也不能由管理查询恢复。

当前用户头像只存于 users 的 `avatar_path/avatar_name/avatar_content_type/avatar_size` 四列；数据库约束要求
四列全空或全非空，并把 size 限在 0..8 MiB、content type 限在 JPEG/PNG/WebP；领域层用同一策略校验 canonical 描述符。
`avatar_path` 索引用于批量判断当前头像引用，
是 FileStore 鉴权下载和未引用 GC 的关系事实入口。联系人卡片、消息卡片和 Conversation 中的头像只是
显示快照，不延长对象寿命，也不能单独授予下载权限。

人类注册先在事务和 BCrypt 外围用共享 `AuthRules` 复验 username/password/displayName 及全部设备字段，
这会在任何 user insert 前拒绝可确定的空白、字符越界、不安全 deviceId、未知 deviceFlag，以及
PostgreSQL `varchar` 无法表示的设备名/型号 U+0000。随后在事务外
完成密码 derivation 并预生成最多 20 个 uid 候选，再由唯一的 `RegistrationService` 聚合入口逐个尝试；
`users_uid_unique`、
`users_username_unique` 和 `users_phone_unique` 是并发唯一性的事实源。用户名/手机号冲突只映射稳定业务
错误，不保留可能包含冲突值的 PostgreSQL detail；只有 uid 约束冲突会消费下一个预生成候选，最多 20
次。每个候选的 User、首个 Device、设备 epoch 以及 access/refresh SHA-256 均在同一个 PostgreSQL
UoW 提交；token 生成、哈希、设备写入或 commit 任一步失败都会连同 User 一起回滚。人类身份没有可独立
提交的第二条注册写轨。BOT/SYSTEM 的 `password_hash` 列保存随机 `!service-account:v1:` marker，而不是可验证密码或 BCrypt
hash；认证策略不会把该 marker 交给密码适配器。

签发、refresh、封禁、密码重置和设备撤销使用固定锁序 `users → devices → credentials`。封禁或
密码重置在同一事务推进用户级 epoch，设备撤销推进设备级 epoch；旧 credential 即使尚未物理清理，
校验时也会因 epoch 不匹配而失效。解除封禁只改变账号状态，不回退 epoch，因此不会恢复任何旧 token。
同一用户同一设备再次使用密码登录时，事务替换完整 pair。refresh 则保留唯一 refresh hash 及其初次
签发的 createdAt/expiresAt，只删除旧 access、推进设备 epoch、更新 refresh 行 epoch 并插入新 access；
相同 bearer 的丢响应重试或并发请求会串行成功，后一次 access 成为唯一有效值，绝对期限不会滑动。
这既避免响应丢失导致不可恢复，也保持每设备严格两条 credential。每个账号最多保留 16 条 Devices 行；
新 deviceId 在已锁定 users 行的同一
事务内检查容量，未满时插入，已满时只回收按 `last_login / created_at / id` 排序最旧的 revoked 行，
16 条全部 active 则明确失败关闭。所有设备登录、refresh 与撤销都从 users 上的全局序列分配新 epoch，
所以 revoked 槽被回收、旧 deviceId 日后再次出现也不会低于进程内已有 fence。账号行锁同时覆盖空集
检查、回收与插入，并发新设备登录不能各自看到“还有最后一个槽”。

### chats / group_chats / group_members

chats 保存共同身份与类型；group_chats 保存群扩展；group_members 保存成员角色和加入状态。禁言
可以使用成员字段或独立表，但权限查询必须得到单一结果。
`group_creation_commands` 以 `(creator_uid, operation_id)` 为主键保存不可变请求指纹与结果 chatId，
结果 chatId 也全局唯一。适配器先识别精确重放，再进入用户锁和 Conversation 容量准入；首次创建时
收据、Chat/Member/Conversation、容量台账与收件人事件在同一 UoW 提交。收据存在但指纹不同固定冲突，
收据指向缺失或失活群则按数据完整性错误失败关闭，任何分支都不得另建第二个群。

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

领域服务直接通过 `ContactRepository` 读取 PostgreSQL，不再经过无状态转发层，也不缓存好友 UID。
好友关系同时参与聊天授权；直接读取权威事实避免 load 与 mutation 并发时回填旧集合，
因此没有需要在事务前更新的本地投影或 after-commit write-through。好友、黑名单、待处理
申请和申请历史都以单次联表/有界分页加载资料，不允许逐 uid 再开事务。好友上限 4,000、黑名单上限
1,000；双方 User 行既是关系写锁，也是并发容量围栏。列表读取多取一行并在旧数据越界时失败关闭，
避免生成超过 16 MiB 的 RPC 帧。

### conversations

主键概念是 `(uid, chatId)`。保存 lastSeq、readSeq、peerReadSeq、draft、pin、mute 和版本。单调字段
更新在锁定 Chat 和会话行后使用 max/条件写，避免乱序事件倒退。draft/pin/mute/delete/markRead
必须携带 outer UoW 事务句柄；只有实际改变的行才推进 version。draft/pin/mute 即使同值也会返回并发布
当前权威快照，以收敛“服务端已提交但 RPC 响应丢失”后的客户端重试和本地草稿 outbox。markRead 事务同时推进
读者 readSeq 与已存在对端投影的 peerReadSeq；已删除/缺失的对端 Conversation 没有可持久水位，不伪造 READ_SYNC。

`last_msg_timestamp` 保存最后一条新消息的服务端时间，空会话为 null；仅 CREATE 随 lastSeq 一起推进，
列表快照与 `CONVERSATION_UPDATED` 使用同一列。置顶、免打扰、草稿、已读、编辑和撤回不改排序时间，
不能用通用 `updated_at` 替代。保存的消息副本使用首次保存时间，幂等重放保留原时间，不重复提升列表位置。

服务端不把个人会话的 peer 资料复制成另一份持久用户事实。构造 PERSONAL `Conversation` 时从活动成员
确定 `peerUid`，并从当前 users 行投影对端 name/avatar/revision；GROUP 和删除哨兵固定返回空
`peerUid/peerRevision/chatAvatar`。`chatAvatar` 只是响应快照，当前头像授权与 GC 仍只认 users 四列。

### chat 聚合约束

`chats.personal_key` 保存排序后的私聊用户对并全局唯一；群聊该列为 null。`group_members` 以部分唯一索引
保证每个 Chat 最多一个活跃 owner，`group_member_mutes` 以 `(chat_id, uid)` 唯一并使用 upsert 刷新禁言。
邀请加入的链接额度、成员行和 Conversation 是同一聚合事务，不再由领域服务分步投影。

群容量不是单行数据库约束：所有现有群成员写适配器必须先锁同一 `chats` 行，再在事务快照中用
`status = 1` 的数量加上去重后待新增/复活目标执行 `GroupPolicy.MAX_MEMBERS` 判定。新建群在首个写入前
计算包含 creator 的最终集合；组织受管群以组织用户与活动 Bot grant 服务身份的最终并集判定。
`checkArchitecture` 将生产 `GroupMembers` 写入限制在经过审计的 Chat、Member 和组织投影三个适配器，
并要求这些文件保留对应 `GroupPolicy` 调用；新增写路径必须先显式扩展该边界和并发验收。

### sync_events

`sync_streams(uid, last_seq, compacted_through)` 为每个账号分配连续序号并保存已物理删除的前缀
水位；`sync_events` 以 `(uid, stream_seq)` 为复合主键保存 NotifyType、payload bytes 与 live dispatcher
重试状态。事件表不再保存 `dedupe_key`，也不是第二个可靠命令收据库：消息投影依赖同一事务的
projection receipt，受管组织群投影依赖同一事务的 applied revision；重试只有在这些权威幂等事实首次
应用时才 append event。`stream_seq` 继续使用现有 wire `eventId`，不是跨账号全局 ID。
认证后由已就绪客户端显式请求 `stream_seq > lastEventId` 的升序有界批次；游标仅在
`compacted_through..last_seq` 内有效。最终查空与实时连接激活共用 per-user delivery gate。

`PgUnitOfWork` 先运行全部领域 SQL 和事件 intent 构造，block 返回后才按 uid 排序锁定 stream 行、
分配序号并一起提交。stream 锁是命令最后获取的数据库锁；同 uid 后来的事务不能先提交，多 uid
命令也不会留下部分事件。stream 初始化、锁定和末尾水位更新都按 uid 词法序以最多 512 个 uid 的
参数化批次执行；事件 insert 每批最多 2,048 行且只绑定一次同一不可变值编码出的共享 payload，
同时以 32 MiB 唯一 payload bytes 为硬预算，单个超限 payload 是保证前进的唯一例外。因此满员群消息不会
退化为“每个收件人一次 SELECT/INSERT/UPDATE”，也不会在 JVM 或 JDBC 层预先复制 1,000 份相同 Message
字节。领域 write block 是非挂起契约，只能组合当前事务的同步 Repository 操作；
网络请求、delay 或其他协程等待必须在准入前完成，禁止在持锁期间等待外部子系统。进入 UoW 前先拒绝已经取消的调用；一旦准入，数据库事务、提交后的本地
缓存失效和 dispatcher wake 是一个不可取消的终态段。commit 后先完成全部本地可见性 callback，再
唤醒 live dispatcher，避免客户端收到事件后回查到旧热缓存。进程若在 commit 后直接退出，dispatcher
启动扫描仍能恢复持久事件，进程缓存也随进程消失；某序号 live push 失败时，同 uid 后续序号不得越过。
普通 callback 或 wake 失败只记录告警，由持久扫描兜底；取消与非 `Exception` 的 fatal failure 不能被
当作普通错误吞掉，而是在其余 callback 和必要 wake 全部获得执行机会后以原异常对象传播。异常在
不可取消协程边界内作为终态结果收集，越过该边界后才抛出，避免协程栈恢复复制异常而破坏身份语义。

`PgUnitOfWork` 不继承数据库或 role 的默认隔离级别：只读事务固定为 JDBC `REPEATABLE_READ`，写事务固定为
JDBC `READ_COMMITTED`，二者都禁止任意 block 自动重试。写侧的选择保证命令等待 State/行锁围栏后，后续 receipt、owner
与权限事实重读能看到围栏持有者刚提交的结果；若写事务使用 `REPEATABLE_READ`，等待前快照会破坏精确重放和归属/归档线性化。
`dispatched_at` 只表示完成过一次实时推送尝试，不参与离线 replay 过滤。
未派发事件有两条不同访问路径：全局到期扫描使用以 `next_attempt_at` 开头的 partial index；单用户按
序读取 head 使用 `(uid, stream_seq) WHERE dispatched_at IS NULL` 的专用 partial index，不能让前者
因列顺序不匹配退化成历史表扫描。

所有持久领域事件都只能由权威 mutation 所在的 outer `PgWriteScope` 直接
`appendEvent`；代码中不存在可单独开启 event-only UoW 的发布口。`TransientEventPublisher`
仅承载 PRESENCE/TYPING 这类明确不进入离线同步的瞬时信号，不接受持久事件。

当前开发基线默认保留 30 天事件，`TEAMTALK_SYNC_EVENT_RETENTION_DAYS` 可配置为 1..3650
的整数。定时 cleanup 只选取精确位于 `compacted_through + 1` 的 head，且只当它及后续连续行均已
完成派发尝试、已过期时才前进；遇到未派发或未过期行即停止，不跳过阻塞行。序号空洞视为
持久化不变量损坏，整轮失败并记录、重试，绝不猜测推进 floor。每个 uid 的删除与
`compacted_through` 推进共用一个 PostgreSQL 事务。每页 replay、checkpoint anchor 和 cleanup
共用 per-user delivery gate；连接级 lease 保护最低仍需要的 replay/checkpoint cursor，断连时释放，
因此 compactor 不能跨过已准入的读取。

过旧 cursor 收到 `SYNC_RESET` 后，客户端用 `SyncRpc` 收齐当前 User、Contact、Chat 和
Conversation checkpoint，以 header 的 `baseEventId` 为 tail 起点。各 section 页是独立 keyset 读取，
不共享跨 RPC 的 MVCC snapshot；权威 tail 使页间并发变化最终收敛。

### organization_units / organization_memberships

organization_units 保存单根层级、负责人和可选稳定部门群 ID；organization_memberships 保存直接部门
归属、职位与主部门标记。同一用户的唯一主部门由用户行锁串行化写入，并由 `is_primary = true` 的部分唯一索引兜底。群成员表只是组织事实的
投影，不能反向编辑组织关系。写事务在同一个 `organization_state` 串行边界内拒绝第 10,001 个活动
节点、第 100,001 条成员关系、单节点第 10,001 条直属关系及单用户第 33 条关系；查询分页上限不承担
容量执法，也不得静默截断已提交事实。根深度固定为 1，活动树最多 64 层；永久单元记录（活动+归档）与
永久受管群投影各最多 20,000 条。所有组织写都用 `limit + 1` 锁定最多 20,001 条投影和 10,001 个活动节点，
越界数据失败关闭，不再把永久历史无界物化到 JVM。锁序保持
`organization_state -> ordered projection rows -> User -> ordered active Unit -> Membership`；局部锁或 O(1) 容量台账需另行并发证明。

`organization_state` 保存单例全局 revision；每条组织写命令在同一 PostgreSQL UoW 内锁内复验树、
负责人和成员事实，提交事实、revision 递增及受影响部门群的 desired revision。
目录读取以 `(revision, exclusive key)` 组成服务端 opaque cursor；每一页在 PostgreSQL
repeatable-read 事务中读取，不把 revision 与行集分属两个 MVCC 快照。活动节点页只读取本页节点并
对这些节点聚合直属人数。每页先用无 path 的 root-down CTE 核对单根、覆盖数、去重数和最大深度；游标可被客户端构造，
因此不能把其 revision 当成“前页已验证”的信任证明。递归成员页的 CTE 只携带 `(unit_id, depth)`，合法树中每个子树节点只访问一次；
`COUNT(*) != COUNT(DISTINCT unit_id)` 或第 65 层 frontier 会在任何 relation row 产生前失败关闭。这避免了深链中逐行复制
`varchar[] path` 造成的 O(U²) path 单元；对合法子树单页为 O(S)，`S <= 10,000`。
`organization_managed_chat_projections` 永久保存每个稳定 chatId 的 desired/applied revision、正负状态和
失败信息。禁用或归档不会删除该行；负投影负责撤销 Chat、成员、Conversation、邀请、禁言和 Bot grant，
因此旧的正任务不能在稍后重放时复活权限。投影按 revision CAS 做完整成员替换，事件使用
`unit/revision/uid/kind` 去重；pending 期间 Chat、消息、群文件和附件访问统一 fail closed。服务启动会
包含延迟重试项执行完整 drain，未收敛就不会开放 TCP；运行期每 5 秒只处理已到重试时间的 pending 行。
正投影的 1,000 人容量包含负责人、子树去重用户和活动授权机器人；永久超限记录固定 failure 并保持
pending，事实侧减少任一成员并发布新 revision 后再收敛。
归档节点与负投影暂无安全自动回收；TTL 会破坏迟到任务围栏。在引入 generation-aware compactor 前，
数据达到 20,000 永久槽位时显式拒绝新身份；继续扩容需要保留围栏语义的归档或迁移方案，不能以
“预发布”为由默认清库，也不做宽泛自动清理。
`archiveUnit` 在同一组织写事务中先锁定 `organization_state` 单例围栏和活动 Unit 集，再以无行锁谓词查询是否存在
`document_spaces(owner_principal_type=ORGANIZATION_UNIT, owner_principal_id=unitId, status=ACTIVE)`。文档归属交接也在任何 User、Space 或 Unit 行锁之前取得同一 State 围栏，
因此归档检查与并发交接线性化且不引入反向 Unit → Space 锁。任一活动行存在都拒绝归档，资产必须先由文档域交接；
不级联删除、不自动改写为父节点，也不把受管群的负投影当作资产交接。

### automation_bots / automation_bot_grants

automation_bots 关联服务 User，只保存 webhook token 哈希、状态和最后调用时间；明文 token 不落库。
automation_bot_grants 以 `(botId, chatId)` 唯一，作为可发送群的权限事实。group_members 中的机器人行
是可修复投影，服务启动按 grant 重放。

bot_credential_commands 以 `(actorUid, operationId)` 保存群机器人创建/轮换的原子收据，只包含规范化
请求指纹、token SHA-256、机器人/服务身份引用和结果元数据，不包含明文 token。客户端在请求前生成并
持久化 256 位 token；机器人创建或 tokenHash 更新与收据必须处于同一 PostgreSQL UoW。同 operationId
的精确 payload/token 重试返回同一 secret-free 结果；任意字段或 token 不同都返回 409，且重试不再次
消费创建速率或活动配额。409 只表达 operation identity/payload 冲突，不能证明旧请求的 token 已失效；
轮换收据只有在其 tokenHash 仍是机器人当前哈希时才能重放，已被后续轮换或停用的精确结果固定返回
410，物理不存在的目标返回 404。客户端只把后两类作为可自动终止恢复的类型化事实，不解析错误文案。

收据不能随软停用、撤权或普通“移除”删除：迟到的创建请求否则会重新制造服务身份，旧轮换请求也会
覆盖新 token。每个机器人最多 256 条收据（创建 1 条、轮换最多 255 条），达到上限后拒绝新轮换，从而
为账本建立硬边界；达到边界的新轮换返回 410，因为候选 token 确定没有生效且以后也不能执行。物理删除
bot 或关联账号时由外键级联清理。只要 bot/账号仍存在，rotation ledger 就必须完整保留，不能用时间
TTL 猜测客户端重试窗口。

这个 256 上限只约束单个 bot 的 rotation ledger，并不约束普通移除后保留的 bot/服务账号总数。当前
尚未实现跨 bot 的归档或硬删除维护任务；长期部署前必须补充显式、可审计的物理清理策略，并同时定义
客户端可靠命令的最终失效边界。不能只给 receipt 加 TTL，否则迟到 create 会重新创建身份、迟到 rotate
会覆盖更新凭据。

### group_file_entries / group_file_versions / group_file_chat_usages / group_file_commands / group_file_audits

group_file_entries 保存群文件目录树、逻辑名称、当前 Attachment、revision 和当前内容版本。根目录用
稳定 parentKey 参与同级名称唯一约束；软删除时释放名称键，允许重新创建同名条目。条目还保存创建
commandId、不可变 payload 指纹及该活动条目全部版本的字节合计。

group_file_versions 只追加不可变 Attachment 快照，`(entryId, version)` 和版本 commandId 分别唯一。
group_file_commands 以全局唯一 commandId 保存 createFolder、createFile、addVersion、rename、delete 五类
命令的 chat、entry、认证操作者、规范化 payload 指纹及可空结果版本；精确重试必须同时匹配资源、操作者、
命令种类和指纹。createFile/addVersion 保存实际 `resultVersion`，rename/delete 的 `Unit` 结果明确保存
`resultVersion = null`，精确重放只确认收据而不再次修改条目。group_file_chat_usages 为每个使用过群文件写入的群
保存唯一容量台账，记录活动条目数与活动版本总字节。group_file_audits 与每次创建、追加版本、重命名、删除在同一 PostgreSQL
事务提交，只记录动作与有限摘要，不保存文件正文。
物理二进制仍在 FileStore；数据库版本表是下载引用和群空间配额的事实源。
所有未命中精确收据的新写入先锁定对应的活动群行并复验操作者的活动成员行；服务层事务外的 ACL 预检
不能替代这一安全边界。锁内对活动行执行四项容量检查：每群最多 10,000 个活动条目、同一 `parentKey` 最多 512 个
活动直接子条目、每个活动文件最多 128 个版本，以及当前活动条目版本总字节不超过部署配额。创建和
追加版本的精确持久化重试在计数前返回原事实，不能重复占槽。五类命令的收据与对应条目、版本、usage
和审计变更原子提交；任一步失败都整体回滚。rename/delete 的精确 `Unit` 重放在首次收据检查、群行写
准入等待后检查，以及写准入明确失败后的最后一次检查中识别并直接 ACK。这是为了覆盖并发首发已经提交
但调用方丢响应的窄例外，不允许不同 actor、kind、资源或 payload 冒领，也不允许新命令绕过当前成员
资格。零字节版本同样参与条目与版本计数。
usage 行的首次创建只允许发生在已锁定的 Chat 下且该群尚无任何群文件行时；已有条目却缺少台账会
失败关闭，不执行启动回填。创建和追加只读取 usage、当前条目 contentVersion/字节数及有界同级计数，
删除直接扣除条目保存的字节合计，写路径不再对全群历史版本做 join + SUM。

条目软删除后不再计入上述四项活动容量，且其版本不再提供下载引用；版本行和审计行暂不物理删除。
同级列表与版本列表分别使用 513 和 129 的 `limit + 1` 越界探针，检测到不可能由当前写入口产生的
历史超限数据时失败关闭，不静默截断。物理版本、审计和命令收据目前都没有 TTL 或物理回收任务。
`group_file_commands` 不能直接按时间删除：客户端可能仍持有同一稳定 commandId，删掉收据会让迟到重放
重新执行或被误判为新命令。生产化回收必须先定义客户端可观察的可靠终止契约，或保留足以永久拒绝旧
identity 的有界 tombstone，再分别为不可变版本与审计设计有界保留、归档和管理员查询。

### document_spaces / document_space_grants / policy 与 custody receipts

document_spaces 保存空间元数据和归档状态；`created_by` 是不可变创建来源，
`owner_principal_type + owner_principal_id` 是可转移的 USER/ORGANIZATION_UNIT 资产归属，`steward_uid` 是唯一隐式 Owner，
`custody_revision` 是正整数交接 CAS，`policy_revision` 是正整数显式 ACL CAS；后者只在 grant 事实真实变化时推进。
数据库约束个人 owner 与 steward 必须为同一 ID，并对归属主体和 steward 建立活动空间索引。
归档还原子保存 `archive_actor_uid`，不再用创建者猜测后来的责任人。

document_space_grants 以 `(spaceId, principalType, principalId)` 唯一，保存用户或组织部门的 viewer/editor/admin
角色以及是否包含下级部门；数据库 check 拒绝未知主体类型和 Owner/越界角色。它不复制部门成员，实时有效能力由领域服务计算。每位用户跨空间
最多有 1,000 条直接 USER grant；新授权先锁该 User 行再计数，已有授权更新不重复占槽。

document_space_policy_commands 以 `(actor_uid, operation_id)` 为主键，保存 mutation 类型、规范请求
fingerprint、`from_policy_revision`、原 `resulting_policy_revision`、客户端 `issued_at`、`expires_at` 与服务端时间。actor User 行串行化同一 actor 的
receipt 身份；写事务随后按 uid 排序锁 actor/USER target，再锁任意状态 Space，避免 upsert/remove、账号停用
和 custody 管理形成反向锁序。exact receipt 在新命令的活动身份、ACL 和 CAS 前识别；同 actor/opId 异指纹
返回 409。receipt 永不被后续变更覆盖，精确重放也不按原 resulting revision 重做副作用，而是在当前 Space/
grant 锁内计算当前 effective role 与 policyRevision 返回。no-op receipt 的 from/result revision 相同；真实
变化先对 Space 做 revision CAS、写 grant，再插入 receipt，三者处于同一 PostgreSQL 事务。每 actor 最多
保留 1,024 条尚可重试的 receipt；新命令在 actor 围栏内删除 `expires_at < now` 的行后执行硬容量检查，
精确回放在该检查之前，no-op 不绕过容量。issuedAt 已过 7 天的命令无论 receipt 是否尚在都返回 410，
所以过期行回收不会让旧未知结果重新执行。后台可靠命令维护任务也按固定批次和固定轮数回收静默 actor
留下的过期 ACL receipt，并与好友处理、邀请链接回执共用有界追赶调度。

document_space_custody_transfers 以全局唯一 operationId 保存 actor、space、规范请求指纹、交接前后的 owner/steward、
起始 revision、结果 revision 和时间。收据与空间条件更新在同一 PostgreSQL 事务提交，既是响应丢失重放身份，也是不可变归属审计轨迹。
精确重放从收据重建原 `DocumentCustodyTransferResult`，因此后续交接或归档不会让历史命令返回新状态；复用 ID 但不匹配 actor/space/指纹则失败关闭。
任何进入写事务的归属命令都先锁 `OrganizationState` 全局围栏，再检查该不可变收据；未命中才继续按
`State → User → Space → Unit` 锁序解析实时权限和目标主体。收据检查不得后移到当前 steward 鉴权之后。未命中的新命令在锁内确认 owner/steward 完全不变时返回 400，既不更新空间也不插入收据；修正后的真实交接可复用该 operationId。

该表在当前预发布阶段是只追加的无界审计与重放身份表：不能简单用 TTL 或硬删除回收，否则历史 `operationId`
可被重新使用；空交接等未产生业务事实的廉价请求不得写入该表。生产化前必须增加每 actor/空间限速与审计分区/归档，或先把可回收的有限重放收据与长期审计记录拆分后再定义过期语义。
任何容量拒绝都必须在精确重放检查之后执行，避免已提交命令在容量满后无法收敛。

`document_custody_batch_transfers` 保存管理批次的 operationId、经验证的 admin principal、source、请求/计划指纹、显式目标、条目数、撤销 grant
数与时间；`document_custody_batch_transfer_items` 以 `(operationId, spaceId)` 保存每空间旧/新 owner、steward 和 revision。精确重放先于当前账号、
组织与容量校验，返回不可变原收据；同 ID 不同请求失败关闭。新执行先锁 OrganizationState，再按 uid 锁 source/target User，按 spaceId 锁 custody
及待撤 grant 所属 Space，最后锁目标 Unit 和 grant。计划指纹包含每个空间 ID/custodyRevision/policyRevision/
owner/steward、显式目标，以及全部 source USER grant 空间的 ID/policyRevision；空计划仍插入批次收据。grant 清理是最后锁类上的
单条集合 DELETE，并把实际删除数写入收据；每个实际删除 grant 的空间在同一事务把 policyRevision 推进一次，
与 custody 是否也在该空间发生变化相互独立。

文档读权限快照不加载完整 `organization_units`。同一个 `REPEATABLE READ` 事务使用参数绑定的
recursive CTE，只以当前 actor 的活动直属 membership 为锚点，沿主键向上读取活动祖先；宽树中的无关
部门不会进入 JDBC 结果，树深也不会增加 SQL 语句数。多个直属归属和共享祖先按 ID 确定性去重；归档
节点会截断继承链，历史循环路径只保留直属部门事实，不授予任何继承权限。`includeDescendants=false`
只匹配直属集合，`true` 才匹配直属与完整活动祖先集合；USER grant、ORGANIZATION_UNIT grant 和 steward 隐式 Owner 语义独立计算，owner principal 本身不进入 ACL。
写权限另有空间行、grant、组织节点和 membership 的锁序及竞态复验，不能为了复用无锁读 CTE 而削弱。

### document_nodes / document_content_revisions

document_nodes 同时保存文档树、每个节点的当前 Markdown 快照、有界 excerpt 投影、revision 和创建/修改身份。
每个活动节点都是完整文档，即使正文为空也有自己的初始修订；不再使用节点类型或 nullable Markdown 表示文件夹。文档树和首页查询必须
只投影 excerpt，不能用 `selectAll` 把正文载入内存。parentId 必须指向同空间的活动文档。从空间根到直接父文档最多包含
128 个祖先 ID。创建、移动和删除在同一
PostgreSQL 事务中锁定 document_spaces 行，然后复验父文档、环、整个活动子树深度和“含子文档的节点不可删除”约束；
领域层的提前检查只用于快速报错，仓储事务才是并发下的最终不变量边界。节点 revision 是标题、正文、
父级和删除状态共享的聚合 CAS；删除只改变 status 并推进 revision，纯父级移动也推进 revision，但不
追加内容修订。父级和名称都未变化的 move 在校验当前 expectedRevision 后原样返回。移动校验在空间锁内
读取目标祖先与待移动文档实际可达子树的有界摘要，不为一个局部移动加载同空间的无关文档。

同级顺序直接使用 `created_at ASC, node_id ASC`，并由
`(space_id, parent_id, status, created_at, node_id)` 索引（根级使用同一 NULL parent 语义）支持分支读取。
created_at 是创建时写入的不变量，移动或改名不修改；schema 不保存 position/rank，也没有启动时重排。

`document_node_move_commands` 以 `(actor_uid, operation_id)` 为业务主键，保存 space/node、完整规范请求
指纹、from/resulting revision、issued_at、expires_at 和 created_at。actor User 与任意状态 Space 行把
精确重放检查、新命令容量准入、节点 mutation 和 receipt 追加串行到同一个 PostgreSQL 事务；因此响应
丢失后的重放不会再次移动或追加标题修订。每 actor 最多保留 1,024 条尚未超过 7 天的 receipt；精确重放
先于新命令容量检查，过期 identity 返回 410，同 ID 异指纹返回 409。维护任务按 expires_at/retention_id
有界清理过期行，清除后仍因 issuedAt 超窗而不能把旧 identity 当成新命令执行。

文档容量规则包含：一个 owner principal 最多 128 个活动 `document_spaces`，一个 HUMAN 最多负责 128 个活动空间，一位用户最多拥有 1,000 条
直接 USER grant；一个 space 最多 10,000 个 `document_nodes`，同一 parent（`NULL` 根级也视为一个 parent）最多 512 个直接子节点。
创建空间先锁初始用户 owner；交接使用上述 State 围栏、收据复查和 `State → User → Space → Unit` 锁序，并在该事务内执行目标 owner 容量检查；创建文档和跨 parent 移动沿用 space 行 `FOR UPDATE` 聚合围栏，在同一
事务内计数并占槽。文档创建先按 `User(actor) → 任意状态 Space` 取得专用命令围栏，再从包含软删除行的
`document_nodes` 复验 actor、space 和不可变 fingerprint；精确已提交重放在容量与实时 ACL 前返回
`DocumentCreateResult(documentId, null)`，因此失权、归档或删除后仍可确认提交。未精确命中的请求继续进入
实时 EDIT 门禁及活动空间创建路径；同 ID 不同 payload 或有权限的跨 actor 碰撞返回 409，满额后精确重放也不
重复占槽。空间创建返回 `DocumentSpaceCreateResult`，仅当空间活动且创建者仍为当前 steward 时携带当前投影，交接
失权或归档后只确认 `spaceId` 并令 `space = null`。移动不改变空间总量，只有 parent 实际变化时检查目标
层级，同 parent 改名不误占槽。归档和软删除不再计入活动容量。
owner、stewardship、直接 USER grant 容量投影与直接子文档列表都只多取一行作为越界探针，旧数据越界时失败关闭而不是静默截断。
这些规则不限制一个 actor 通过组织 grant 汇总后的可访问空间总量。`listSpaces` 因此使用
默认 32、最大 64 条的 `space_id ASC` 独占键集分页：SQL 先把 steward、用户 grant、直属部门和继承部门
grant 合并成可访问候选，`DISTINCT` 后只读取 `limit + 1` 条小字段来判断下一页，再只为本页空间读取有界
grant 并计算有效角色。它不使用 offset，也不先把全部可访问 spaceId 物化到 JVM；游标锚点无需仍存在或
仍可访问，所以归档游标行或撤销其授权不会让后续页重复或卡住。翻页期间授权变化不承诺快照一致性，刷新
从第一页重新建立权威投影。

`listRecentDocuments` 与 `listRecentlyCreatedDocuments` 的 SQL 也只负责有界预筛，并为候选空间批量读取 owner/steward、相关 grant 与 actor 组织路径组成
`DocumentHomeAccessSnapshot`；领域层随后由 `DocumentAuthorizationPolicy` 以 typed `DocumentCapability.READ` 逐项复验，任何 SQL 候选与域权限不一致都失败关闭而不是返回。这不是搜索索引，当前文档搜索仍未实现。

祖先链由单条参数绑定、深度受限的 recursive CTE 读取，只返回 direct parent 到 root 的最多 129 个
小字段探针行，不读取 Markdown。每一步都要求同空间且活动，SQL 路径数组标记循环；适配器随后验证
depth 连续、父子 ID 完整衔接、无重复、最终以 `parentId = NULL` 正常终止且正式结果不超过 128 个祖先。
因此缺失、归档、跨空间、循环和未在深度边界内终止的历史路径都会失败，树深不会增加 SQL 语句数。
PostgreSQL 适配器以 `DocumentRepository` 门面保持领域端口稳定，包内分别维护读投影、聚合写入、层级遍历和
行映射；树摘要、首页与路径查询不能借由协作者拆分重新引入正文列。

document_content_revisions 在创建以及标题或 Markdown 实际变化时追加完整快照，
`(documentId, revision)` 唯一。纯父级移动只推进 document_nodes 聚合 revision，因此内容修订号允许
不连续，不能把缺失的聚合版本解释为数据丢失。更新在锁定 document_nodes 当前行的同一事务内先执行
expectedRevision 校验，再决定 no-op、节点条件写和修订插入，避免内容保存与结构移动基于同一版本时都
成功。`updateDocument` 不接收 title，内容修订使用锁内当前名称；名称变更必须通过上述可靠结构命令。
完整快照简化恢复与验收，但会增加存储；增量压缩、保留期和管理员审计属于生产化后续设计。

document_user_recents 以 `(uid, documentId)` 为主键保存最后访问时间，每个 uid 最多保留
1,000 行。更新先锁定 Users 行作为跨进程容量围栏，然后 upsert 当前文档，并按
`accessed_at DESC, document_id ASC` 确定性保留最新工作集。同一锁和事务使并发请求不能各自占用最后一个槽位；
旧数据已越界时，下次访问会在 PostgreSQL 内删除全部超额尾部，不把无界行集加载到 JVM。
访问时间同时是排序标记：时钟回拨时在当前头部后单调分配；头部到达 `Long.MAX_VALUE` 时，在已有界的行集内按
原顺序一次性重编号，再写入新的最新值，不执行溢出加法。创建文档时，创建者的访问记录与文档及首个修订在同一事务提交；
读取正文后的访问更新是辅助索引，失败不得把已授权正文伪装成读取失败。最近列表单次最多返回 50 行，仍需实时过滤空间 ACL、空间归档和节点删除；
历史访问记录本身不是权限凭据，这 1,000 行是最近工作集而非完整访问日志。

## 3. MessageStore

消息主键按类型前缀、chatId 长度和值及 big-endian serverSeq 编码，使 RocksDB 范围扫描天然按序：

```text
[0x07][chatId length + bytes][serverSeq 8B BE] → Message bytes
[0x08][chatId length + bytes] → latest allocated serverSeq 8B BE
```

历史扫描会复验 key 长度以及解码后的 chatId/serverSeq，损坏或非规范 key 不能跨 Chat 返回消息。
MessageStore 的 RocksDB 句柄受公平读写生命周期门禁保护：已准入 get/iterator/
batch 退出后 `close` 才释放原生资源，关闭开始后新操作不能取得已关闭句柄。原生释放使用会上抛
错误的 `RocksDB.closeE()`，不使用会静默忽略错误的 `close()`。每个已发布句柄只尝试释放一次；
如果释放抛出普通
异常、中断或 fatal error，实例进入不可逆失败终态，并发/重复 `close` 和后续 `init` 重抛同一个
Throwable 对象；成功关闭保持幂等，仍允许一次全新的显式 `init`。

另有 clientMsgId 幂等索引指向 chatId/serverSeq。产品写入由 MessageStore 在同一个同步 WAL 批中读取并
推进 chat 高水位、写消息和幂等索引；失败不会留下只有序号而没有消息的状态。重复请求必须返回已存在
消息且不推进高水位。测试和归档夹具保留的显式序号写入口不属于领域端口；幂等命中仍须复验索引所指
消息的 clientMsgId 与 sender，不能由损坏索引覆盖另一条消息。

MessageStore 在同一 RocksDB `WriteBatch` 中写入 chat 高水位、消息、clientMsgId 索引、消息 revision 和
不可变操作记录：

```text
[0x05][chatId length+bytes][serverSeq 8B][revision 8B][operation 1B]
    → { CREATE | EDIT | REVOKE, revision, Message, chatType, sorted recipient snapshot }
[0x06][chatId length+bytes][serverSeq 8B] → latest revision 8B
```

`projectionKey = message/v1/{length}:{chatId}/{serverSeq}` 在消息整个生命周期内稳定，revision 从 CREATE=1
开始递增。ACK 只删除完全匹配的 operation key，因此旧 projector 不可能误删稍后写入的 EDIT/REVOKE。
相同 canonical edit 和已经撤回的 revoke 重试是 no-op，不会人为制造新 revision。
outbox 编码端与解码端使用同一预算：单消息最多 16 MiB、单 operation 最多 32 MiB、收件快照最多
100,000 个 uid，并校验 CREATE 只能是 revision 1；因此当前进程不会写出重启后自身无法解码的记录。

Lucene、Conversation 和 `sync_events` 都完成后才精确删除该 operation。幂等重试和服务启动会扫描并
补偿未完成项；每个恢复页同时受条数和默认 32 MiB Rocks value 编码字节预算约束，在解码前决定页
边界。若有数据但 head 单条已超过页预算，仍返回这一条以保证确认后可以前进。启动恢复循环分页直到
全局 outbox 连续两次为空，不受单页 1000 条限制；并发 generation fence 语义不变。永久失败会
保留 operation、使 `message-projection` readiness 为 DOWN，并阻止启动进入 TCP 服务阶段。运行期故障
不依赖下一条业务消息触发恢复：Application 拥有的 maintenance worker 只在 readiness 已阻断时
每 5 秒尝试全局 drain，成功观察到双空页后恢复 UP。因此即使负载均衡已因 DOWN 摘流，
实例仍有独立的自恢复路径；连续失败只保留同一持久 operation 并在下一个有界周期重试。

PostgreSQL 消息 CREATE 投影先锁 chat，断言 `message.serverSeq == Chat.maxSeq + 1`，并在 receipt、
Conversation 和事件的同一事务推进派生 `Chat.maxSeq`；精确 receipt 重放不重复推进。随后按快照 uid
顺序分批锁当前仍活跃的 member 与每用户
`conversation_usages` 容量行；缺失 Conversation 的容量增量经过同一批量 CAS 更新后，CREATE 使用
`INSERT .. ON CONFLICT DO UPDATE` 集合推进 last/read/version/hidden，EDIT/REVOKE 只集合更新当前
last_msg_seq 命中的行。可见 Conversation 随后分批读取，群名只读一次且当前 v22 仍不投影群头像，私聊 peer 元数据也只做
一次成员集和用户集读取。关系投影、容量台账、receipt 和后续批量事件仍处于同一 UoW，任一批次失败
会整体回滚；1,000 人容量验收同时限制完整 CREATE + 两类事件的 SQL statement 数，防止逐行路径回归。

## 4. 派生数据

Lucene 索引、会话预览、缩略图和部分计数都是派生数据：

- 派生写失败需要 fault 日志和重建/补偿方式。
- 搜索结果不能反向成为消息权威。
- 重建工具读取 MessageStore，不从客户端缓存回灌。
- 健康检查应区分“索引不可用”和“消息已丢失”。

Lucene 文档保存同一个稳定 projectionKey 和最新 revision。`applyProjection` 只接受更大的 revision，
并在返回前 commit；EDIT 覆盖原文，空正文和 REVOKE 写入带 revision 的不可搜索 tombstone。进程重启
从 live document 恢复 revision fence，所以较旧操作不能让已撤回正文复活。检索正文在现有
MessageBody 业务上限内完整提取；Conversation 的 `last_message` 独立截为最多 400 字，不能反向截断
Lucene 输入。

MessageStore 另提供仅供无写入启动阶段使用的全局 archive cursor。它按消息 Rocks key 严格前进，读取
每个当前消息值及独立 revision 行；单页有 256 条/32 MiB 双预算，原始 key、message value、revision
key/value 在 decode 前计入预算，单个超限 head 是唯一例外以保证前进。Lucene 启动据此逐消息比较完整
派生字段并核对 live count。需要修复时只在固定同级 side 目录构建，最终 commit 后再做第二遍精确审计、
写完成标记并用 active/backup/side 原子 rename 发布；未完成 side 永不成为活动索引。该流程发生在消息
投影 outbox 恢复之前，所以重建到最新 revision 后，较旧 pending operation 只补 PostgreSQL/事件阶段，
不能倒退搜索正文。

## 5. 一致性与事务

PostgreSQL 事务只覆盖关系表；它不能原子覆盖 RocksDB/Lucene。跨存储流程必须用业务顺序保证：

1. 权威消息与 outbox 原子写成功。
2. 按 revision 幂等更新 Lucene 并 commit。
3. 在 PostgreSQL UoW 中插入 `external_projection_receipts(projection_key, revision)`、连续推进
   `Chat.maxSeq`、更新 Conversation，并为快照中当前仍活跃的成员追加 MESSAGE_RECV /
   CONVERSATION_UPDATED；receipt、关系投影和事件一次提交。
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

发行、连接协议和存储布局分别回答不同的问题，不能一起重新编号：

| 标记 | 当前基线 | 负责什么 |
|---|---|---|
| 发行字符串 | `0.0.0` | 用户看到的版本；客户端、SDK、服务端来自同一构建输入，不决定二进制兼容 |
| 协议 major/minor | `0.0`，数字 ID 为 `0` | 每条 TCP 连接协商可使用的契约窗口，不改变已保存的消息和同步游标 |
| 服务端存储 epoch | **`1`** | 已存在的 PostgreSQL 和本地持久化布局；以 `ServerDataEpoch.CURRENT_EPOCH` 为事实源 |
| PostgreSQL 迁移版本 | `0` | `schema_migrations` 的连续完成记录；在现有 epoch 内保留数据地推进 SQL 布局 |
| dataset ID | 每套数据原有的 canonical UUID | PostgreSQL 与本地存储共同拥有的身份，普通升级保留原值 |

新发行和协议从 0 建立基线，已有存储标记 **不从 1 改为 0**。标记重编号本身不会迁移数据，反而会让
本可继续读取的部署被拒绝启动。对外仍不保证兼容，未来允许设计破坏性变更；内部同一协议 major 的
普通升级必须保留现有业务数据，需要变更存储时先提供明确的迁移与恢复方案。协议 major 改变也不等于
获得清空服务端数据的授权。

当前空库首次启动一次性创建全部表、约束和索引，并写入 `schema_metadata`；已有库启动校验 epoch、
dataset ID 和迁移完成记录，只执行尚未完成的已知迁移。不会调用 `createMissingTablesAndColumns`
猜测修复旧库，也不把“同 epoch 校验通过”当作完整结构审计。下一次需要改表、RocksDB 编码或持久
业务模型时，迁移仍是该变更的前置工作，不能只改 marker 来绕过旧布局。

### PostgreSQL 有序迁移

[SchemaMigrations](../../server/server/src/main/kotlin/com/virjar/tk/server/infra/db/SchemaMigrations.kt)
维护一份短的追加式 SQL 清单；`schema_migrations(version, name, applied_at)` 记录每条迁移的完成事实，
编号从 0 开始，已经发布的顺序、名称和 SQL 不得原地修改。当前只有：

| 版本 | 名称 | 变化及数据影响 |
|---|---|---|
| `0` | `expand_client_telemetry_protocol_id` | 把已有 `client_telemetry_devices.protocol_version` CHECK 从 `0..255` 放宽为非负 PostgreSQL INTEGER；保留每行内容、主键、时间和 dataset |

`DatabaseFactory` 在建立业务容器前完成这一步。已有库的启动事务先锁定 `schema_metadata`，再校验
布局和读取迁移记录；事务使用 `READ_COMMITTED`，等待另一启动事务结束后能看到它刚提交的记录。
空库的建表与首次迁移也在同一个事务内完成。DDL 和迁移收据共同提交或回滚，失败不会留下“约束已改
但迁移未记账”的半状态；重启只检查已完成前缀，不重复执行 SQL。高于当前清单、编号缺口或名称不符
的记录会阻止启动，不能忽略它们去运行旧服务端。

这是现有 epoch 内的具体迁移入口，没有自动降级 SQL。当前 0 号迁移只扩大允许值，升级前的旧行保持
有效；恢复旧发行前仍须检查它是否能读取升级后产生的数据。RocksDB key/正文和其他未列出的历史
布局没有因此获得自动转换能力，仍须为实际变更补充专门的迁移和恢复验收。

### 数据集绑定与恢复

数据根目录必须有同 epoch 的 `data-epoch` marker；PostgreSQL schema 校验通过后、任何本地 store
打开前，启动还会要求 `data/dataset-id` 与 `schema_metadata.dataset_id` 是同一个 lowercase
canonical UUID。消息 RocksDB、Lucene、FileStore RocksDB 与大文件目录被视为一个整体：marker
缺失时只有这些目录全部为空才能初始化；marker 不匹配、dataset 身份不匹配，或 dataset marker
缺失但已有数据时启动失败。FileStore metadata 显式编码生命周期并拒绝未知 JSON 字段；不同 dataset
的消息、文件与索引不能拼接使用。Message 正文和持久同步 payload 使用协议编码，修改既有 wire
字段或重用 MessageType 会同时影响历史解码；不能寄望 epoch 校验自动发现这种源码变更。

已有数据的 epoch 缺失或不匹配、dataset 无法核实，都会阻止启动。历史异常类名
`SchemaResetRequiredException` / `DataResetRequiredException` 仍保留，但只表示存储不受当前实现支持，
不是自动重置指令。处理顺序是停止接入、保留 PostgreSQL 与完整 data 目录、核实原布局与 dataset，
再选择兼容构建、显式迁移或经过验证的恢复；不能通过删表、修改 marker 或换一个 dataset UUID
“修复”升级。普通部署不清库，确实需要破坏性重建时必须另外明确范围、数据损失和授权。

实现入口是 [ServerDataEpoch](../../server/server/src/main/kotlin/com/virjar/tk/server/infra/ServerDataEpoch.kt)
与 [DatabaseFactory](../../server/server/src/main/kotlin/com/virjar/tk/server/infra/db/DatabaseFactory.kt)；
连接协商与业务版本上下文见[服务端运行时](../03-architecture/server-runtime.md#连接版本与业务实现)。

`document_content_revisions` 同时保存不可变 Markdown 与 `content_length` 元数据。修订列表只投影
documentId、revision、title、contentLength、editedBy、editedAt，并按 revision 独占游标有界分页；只有
`getRevision` 可以读取完整 Markdown。该字段属于当前 epoch schema，不在启动时回填旧正文。

当前好友申请的“同方向只能有一条 pending”直接由最终 schema 的部分唯一索引保证，发出/收到 pending
分别通过部分索引在双方 User 行锁内计数；终态行只保留有界交互历史且不再保存处理 token。草稿正文
从建库起就是文本列。启动流程不再识别、修补或标记旧重复、超额或旧 token 生命周期申请。

`document_spaces.creation_fingerprint` 与 `document_nodes.creation_fingerprint` 保存 64 位小写 SHA-256
十六进制值，是客户端资源 ID 的不可变创建收据。指纹只用于判定同一创建意图，不随空间改名、文档编辑
或移动改变；因此响应丢失后的重试即使发生在后续编辑之后，仍能返回同一当前资源而不会制造副本。

### 协商降级与历史数据

持久事件只保存一份权威字节，不按客户端版本复制数据库。发送时对该连接不支持的 NotifyType 或
`MESSAGE_RECV` 正文类型使用 `EVENT_CURSOR_ADVANCED` 占位，保留原 eventId、去掉 payload；瞬时事件
直接不发送。占位只存在于 wire 投影，禁止作为业务事件写入 `sync_events`。因此即使整页都是新类型，
旧客户端也能推进游标，不会卡在同一页。具体路径见[通知投影与补齐边界](../03-architecture/server-runtime.md#通知投影与补齐边界)。

游标已经推进的旧客户端，在小版本升级后不会自动重新收到被跳过的历史事件。新增模块必须提供
首次启用或小版本迁移时的权威快照补齐路径；保留账号和草稿不代表新模块投影已经完整。普通消息历史、
搜索结果和全量同步 checkpoint 的 Message 列表也不能靠过滤顶层 Notify 解决：当前正文没有可跳过
未知类型的独立长度边界，新类型必须配套按协商版本生成旧端可解码结果的业务 adapter，或提高最低
minor 后才启用。`since` 门禁不会替开发者生成历史数据或 RPC 返回值适配器。

持久化格式发生下一次变化前需要追加并验收对应迁移，长期生命周期还需补齐：

- 后续 PostgreSQL 向前迁移及回退/恢复演练；
- RocksDB key/version 迁移策略；
- 孤儿文件与其他长期保留策略；
- 备份、恢复和一致性校验工具。

这些未完成项集中维护在[功能状态](../10-reference/feature-status.md)和
[路线图](../10-reference/roadmap.md)，不混入当前 schema 描述。
