# 领域服务

## 1. User / Auth

### 注册

服务端在认证分流与任何用户写入前再次执行 `AuthRules`：显示名必填且最多 100 字符，设备 ID、
可选设备名/型号与设备类型必须符合与 wire/数据库相同的边界，尤其在用户写入前拒绝设备元数据中的
U+0000；空白显示名不回退为 username。密码先在
Application 自有的有界 CPU 执行器上通过 `PasswordHasher` 生成 BCrypt verifier，随后仓储直接尝试插入，
不做会竞态的用户名/手机号预查。PostgreSQL 唯一约束是
用户名、手机号和 uid 的事实源；适配器只对精确 uid 约束冲突使用预生成的最多 20 个安全随机短码继续
重试，用户名/手机号冲突映射为不携带驱动 detail/cause 的稳定业务错误。每次候选尝试都通过唯一的
`RegistrationService` 聚合入口，在同一个 PostgreSQL UoW 内写入 User、首个 Device 和 access/refresh
凭据哈希；任一步失败会回滚整个候选，不存在可独立提交的人类 User 注册旁路。

### 登录与 refresh

未知用户和密码错误使用相同外部错误，并各执行一次同成本 BCrypt 验证。缺失、封禁或服务身份在读取
角色/状态后先被选为不可登录策略，不把其持久字段交给密码校验，但仍由 `PasswordHasher` 对固定 dummy
verifier 消耗等价 CPU 工作，避免用快路径枚举身份或状态。BCrypt 只存在于基础设施安全适配器；查询在
仓储 IO dispatcher、密码验证在独立的固定 worker/固定队列 CPU owner 上运行。认证在 PostgreSQL 事务内创建或更新 Device，签发随机
access/refresh token，但只保存 SHA-256；首次注册原子创建唯一 pair，后续密码登录则替换同一用户同一设备的
credential pair。签发记录同时捕获 Users 与 Devices 的 `credentialEpoch`，后续 TCP 和 HTTP 校验都
要求账号、设备、凭据三者状态与 epoch 一致。refresh token 是保持首次签发绝对期限的设备级稳定
bearer；成功使用后同一事务保留其 hash/createdAt/expiresAt，只替换 access 并严格推进设备 credential
epoch。相同 refresh 的并发或丢响应重试由 `users → devices → credentials` 行锁序列化，后一次 access
成为唯一有效值，表始终只有一条 access 和一条 refresh。每次 mutation 都把“数据库提交 +
ClientRegistry fence”作为不可被请求取消拆开的终态编排；不能出现已提交新凭据、旧 TCP 却继续有写
权限的窗口。

认证成功本身不注册实时连接。客户端事件投影就绪后按持久游标显式分页同步；服务端最终在用户
delivery gate 内二次查空、发送 `SYNC_READY` 并激活实时连接。ClientRegistry 激活时还要检查已提交的
credential fence，旧 epoch 会话不能重新进入在线集合；同设备的新登录会立即替换旧连接。

用户自助改密与管理员重置都复用 `AuthRules` 与同一个 `PasswordHasher`。旧密码查询、验证和新 verifier
生成先在各自 IO/CPU 边界完成，再在锁定 User 行的事务内比较旧 verifier、更新密码、推进用户 credential epoch 并删除
全部凭据。发起自助改密的精确连接在提交后立即从认证/实时集合移除并进入终态，只为写完成功 RPC
响应而短暂保留 channel，响应 flush 后关闭；其他旧连接由同一 fence 关闭。会话例外使用 Netty 完整
channel id，而不是 deviceId 或用于日志的短 id，避免误保留同设备并发连接。

### 资料更新

只能通过 presence-aware `ProfilePatch` 更新允许的个人字段；缺席字段保持原值，avatar/phone 的
显式 null 表示清空，uid、用户名等身份键不能由普通资料接口覆盖。服务端锁定 User 行后只写入
present 且实际变化的字段。非空头像必须是认证用户本人上传、尚未业务绑定的 canonical FileStore
JPEG/PNG/WebP 描述符，且不超过 8 MiB；服务端先核对权威 metadata，把四元组与事件提交到
PostgreSQL，再单调标记 business-bound。发布失败时当前头像引用仍是可认证读取和可重试的权威事实；
精确重试补齐发布但不会再次改写头像。后续替换或清除会先修复当前 staging 头像的发布，使旧请求不能
覆盖新头像。其他已绑定、外部 URL、非图片、超限或 metadata 漂移一律拒绝。

实际变化在锁定 User 行的事务内 checked 推进正数单调 `User.revision`，并向本人和当时仍为活动好友的有界集合追加完整
`USER_UPDATED(User)`。头像附件围栏退出后，再把同一完整 User 以 `eventId=0` best-effort
广播给其余已完成 SYNC_READY 的会话；本人和当前好友从该瞬时广播排除，避免同一 reducer 收到重复事实。
若 PostgreSQL 已提交而 FileStore business-bound 标记失败，仍先 best-effort 广播 committed User，再把原发布错误返回；
精确重试只修复标记且不重复 durable 事件。客户端以 revision 拒绝 durable/transient 跨通道乱序旧值。
瞬时广播失败不改写已提交 RPC 结果，离线终端仍由 checkpoint、Conversation/Contact、活动群和组织资料
刷新兜底。无变化不更新 `updated_at`、也不产生事件。替换或显式清空立即移除旧头像的
当前资料引用，旧对象不再享有头像下载授权，并按其余业务引用或未引用 TTL 决定保留。

## 2. Contact

- `apply`：不能申请自己、不存在的用户、服务身份、已有好友或被策略禁止的用户。同方向 pending 在
  锁定双方 User 行的事务中复用且不重复通知；反方向 pending 保留给接收者处理，不能再创建镜像申请。
- 每位用户最多同时发出 100 条、收到 100 条 pending。新申请在双方 User 行锁内统计并占用两个方向的
  配额，处理或拉黑转为终态即在同一事务释放；同一申请的幂等重试先于容量门禁复用原行。
- `accept`：验证申请接收者和 token，在事务中形成双方关系。
- 新申请、直接建立关系和接受申请都会在同一双方 User 行锁内检查 4,000 个好友容量；任一侧已满时
  整个双向关系和事件一起回滚。黑名单以同样方式限制为每人 1,000 条。
- `reject`：只改变申请状态，不创建关系。
- `listPendingApplies` 完整返回当前用户收到的全部 pending（由上述 100 条写入硬边界保证有界），持久化
  数量若越界会失败关闭而非返回截断视图；双向历史使用有界 keyset 分页 `listApplyRecords`，资料页的
  两人 pending 状态使用 `getPendingApply` 精确查询，不能从历史首屏推断。
- 已接受/拒绝记录是交互便利历史而非审计日志：每位参与者最多保留最近处理的 1,000 条；一行属于双方，
  任一侧超限都可清理该共享旧行。状态转为终态时同时清空处理 token，并在事务内按固定行锁顺序裁剪。
- 处理 token 只属于收到申请的一方；`apply` 响应、发出记录和已处理记录不得回显 token。
- `delete`：删除双方关系，并分别发送双方视角的 CONTACT_DELETED。
- 备注属于关系所有者，不更新对方 User。
- 黑名单在建立关系和发送交互时参与权限判断。
- 好友、黑名单与申请资料必须由仓储联表读取，禁止 N+1 用户查询；全量好友/黑名单 RPC 依赖上述固定
  容量保证可编码，申请历史继续使用游标分页。

Contact 事件的 payload 视角必须明确；服务端不能把 A→B 的 Contact 原样发给 B。

## 3. Organization

OrganizationService 维护单根无环目录、用户多部门归属和唯一主部门。负责人必须是有效用户，并在
启用部门群时自动成为直属成员。删除负责人归属前必须先变更负责人。
组织成员和群成员读取先取得成员事实，再按去重 uid 分批联结用户展示信息；数据库适配器不得按成员
逐个打开用户查询。该规则同时覆盖递归部门目录和千人群详情，避免目录宽度直接放大 SQL 往返次数。

普通终端只通过 `OrganizationRpc.listUnitPage/listMemberPage` 的强类型二进制分页读取组织目录；
`/api/admin/organization/**` HTTP 路由只承担管理写控制面。每个成功组织命令先在 PostgreSQL 原子提交
事实、revision 和待收敛受管群任务，再 best-effort 向 `ClientRegistry` 中已经完成 `SYNC_READY` 的
连接广播 `ORGANIZATION_CHANGED(revision, eventId = 0)`。单连接或整个提示发布失败不能把已提交管理
命令改报失败。该广播不写 `sync_events`、不做每用户 durable fanout；客户端断线遗漏由下次认证恢复的
全量 revision-fenced RPC 对账补偿。

部门群以 OrganizationUnit.groupChatId 是否存在为启用状态。收敛算法计算当前节点及全部后代成员，
再合并 RequiredChatParticipants（当前为获授权机器人），调用 ChatService 的受管群管理入口补齐成员、
移除多余成员并维护 Conversation。受管群 reconciliation 和机器人撤权不得使用独立的“成员删除→
Conversation 删除→事件发送”链；它们与普通踢人共用 transaction-bound member removal，一次提交
成员事实、Conversation 行删除、目标 `CHAT_DELETED` 与剩余成员刷新事件。
客户端在单个本地事务内用 `CHAT_DELETED` 清理 Chat、Conversation 及全部从属投影，
因此授权撤销不再叠加一条冗余 `CONVERSATION_DELETED`。
普通群 RPC 遇到受管群时拒绝成员或生命周期变更。

正向收敛先对“子树用户和负责人 ∪ 当前活动 Bot grant 的服务身份”去重，并在锁定组织 revision、Chat、
Bot/grant 和成员快照的同一事务内应用 `GroupPolicy.MAX_MEMBERS`。超限时业务投影事务不创建或改动
Chat、Member、Conversation、事件；独立 failure 事务只记录固定容量原因并保持 desired/applied revision
不一致。投影因此 fail closed，缩小期望集合并产生新 revision 后可以正常恢复。

跨表流程采用“先组织事实、后幂等投影”：节点移动和成员变更提交后立即收敛，应用启动再重放全部
启用节点。单个失败会记录 unitId，不阻止其他部门恢复。
归档组织节点除了要求无子节点、无直属成员，还会在同一组织写事务中检查该节点是否仍持有活动 DocumentSpace。存在任一资产时
失败关闭，必须先通过文档域的可靠交接命令转移归属，不得归档节点后再猜测性地把资产上收到父节点。

## 4. Chat / Member

### 私聊

创建私聊前验证两人关系，并以排序后的用户对作为数据库唯一键。并发创建会收敛到同一 Chat，
成员与双方 Conversation 在同一事务内建立。

### 群聊

创建者成为群主，初始成员去重并验证存在。创建 Chat、Member 与各成员 Conversation 后再发送
CHAT_CREATED。用户建群以客户端 operationId 和规范化请求指纹作为持久命令身份；
`(creatorUid, operationId)` 收据与群事实、会话容量台账及事件在同一 UoW 提交。响应丢失后的精确重放
只返回已创建群，不重复建立 Conversation 或发送事件；同 ID 改写 payload 稳定冲突。
`GroupPolicy.MAX_MEMBERS = 1_000` 是所有群写入口共用的活跃成员上限，创建者计入；
普通用户、BOT 和 SYSTEM 身份不设置相互独立的隐藏额度。

### 权限

| 动作 | 群主 | 管理员 | 成员 |
|---|---|---|---|
| 修改普通群资料 | 是 | 按当前规则 | 否 |
| 邀请成员 | 是 | 是 | 按群策略 |
| 移除普通成员 | 是 | 是 | 否 |
| 设置管理员 | 是 | 否 | 否 |
| 转让群主 | 是 | 否 | 否 |
| 解散群 | 是 | 否 | 否 |
| 退出群 | 先转让/解散 | 是 | 是 |

最终矩阵以 ChatService 当前实现和 RPC 验收为准。权限变化必须在一个操作内更新存储并发完整群快照。
退出只失效当前成员关系，解散才把 Chat 标记为非活跃；两者不能共享一个含糊的“删除群”用例。
每个活跃群最多一个 owner 由部分唯一索引兜底，转让在锁定 Chat 聚合行的事务内重新校验双方成员状态。

邀请加入先锁定邀请行与 Chat 聚合行，再在同一 PostgreSQL 事务内校验失效、过期、限额和群活跃状态，
建立成员与 Conversation、消费一次额度，并在同一 PgUnitOfWork 写入所有收件人的 CHAT_CREATED。
重复加入只补齐 Conversation，不重复计数或发事件；即使首次提交已耗尽额度，丢失响应后的同成员重试仍按
已提交事实成功返回。缓存仅在上述事务提交后失效。

普通成员添加、受管群补员与服务成员授权共用 transaction-bound addition：锁定 Chat/成员快照后
重新校验权限，在一个 PgUnitOfWork 内建立或重新激活 Member、创建 Conversation，并按同一提交写入
新成员 CHAT_CREATED 与全部现有成员 MEMBER_ADDED。重复命令没有事实变化时不重复产生事件。

创建群、普通 add、邀请加入、Bot create/grant/startup recovery 和受管群全量投影都必须在各自
持久化适配器重复执行容量门禁，服务层早拒绝不能替代它。已有群的写入先锁权威 Chat 行，再以
`ACTIVE 成员数 + 去重后当前非 ACTIVE 目标数` 原子判定；容量异常发生在 Member、Conversation、邀请
`useCount`、Bot grant 和 durable event 变化之前，外部只收到固定容量原因。已是 ACTIVE 的目标不占新
名额，邀请幂等重试在满员时仍不消费次数。进程内 ChatLifecycleGate 只减少竞争，跨进程正确性依赖
PostgreSQL Chat 行锁。

聊天是否存在、是否群聊、成员资格以及管理员/群主阈值统一由 `ChatAccess` 判断。Chat、Message、
GroupFile 和 Bot 不得各自复制角色数字和成员错误分支；禁言、黑名单、受管群等操作专属规则仍由
对应领域服务负责。

## 5. Message

发送顺序：

1. 校验 sender 是当前认证身份，并规范化 MessageBody 与 Attachment 声明。
2. 按 `chatId + clientMsgId` 查询幂等结果；已接受的人类消息先恢复原投影和 ACK，不被之后的离群或禁言
   反向改写，Bot 重试仍复验当前凭据和 grant。
3. 重建服务端权威引用，在 FileStore 验证全部附件。
4. 锁定 Chat 准入快照，校验成员、禁言、黑名单、mention 和业务限制。
5. MessageStore 在一个同步 WAL 批中推进 chat 高水位、分配 serverSeq，并原子写入消息、幂等索引、
   附件反向索引、revision 和 CREATE operation。
6. Lucene 按 revision 幂等提交；PostgreSQL receipt、连续 `Chat.maxSeq`、Conversation 和同步事件在同一
   UoW 提交。
7. 投影全部成功后清除 outbox，再返回 ACK。

若第 6 步中断，相同 `clientMsgId` 重试或服务端重启会继续补齐投影，不能只返回旧 seq。EDIT/REVOKE
也写入不可变的递增 revision operation；重复 canonical edit/revoke 不产生新 revision。

编辑只允许原发送者修改可编辑的文字消息；撤回允许发送者或有权限的管理员；转发产生新消息并
重新走目标会话权限和附件校验。

## 6. Conversation

ConversationService 维护用户视角状态：

- list 返回当前权威会话快照；增量恢复统一走持久 `sync_events` 流。
- setDraft、setPin、setMute 只影响当前用户。
- markRead 用 max 合并 readSeq，更新自己所有设备并向会话成员同步 peer waterline。
- deleteConversation 删除收件箱视图，不删除 Chat 或消息。

上述用户写操作都在锁定 Chat 聚合行后重验活动成员资格，会话行、已读水位、对端水位和
durable event 在同一 `PgUnitOfWork` 中提交。事件 payload 必须从该事务的最终行状态构造，不得在提交后另起查询。

创建 Chat 时，初始 Member 与 Conversation 由 ChatRepository 在同一 PostgreSQL 事务建立。后续普通加人、
邀请加入、受管群和服务成员增删都在同一 `PgUnitOfWork` 内更新 Member/Conversation 并写收件人事件；
提交后先失效 ChatStore 热缓存再唤醒事件分发。创建完成后不能再重复写一轮相同投影。

## 7. GroupFile

GroupFileService 通过统一 `ChatAccess` 只接受当前群成员访问，并拒绝在私聊上创建文件空间；它不再
通过“先列出用户全部会话再查包含关系”的旁路判断权限。创建文件或新版本时，服务端重新
查询 FileStore，要求 Attachment 元数据完全匹配且调用者就是该次上传者；因此不能抢占其他成员尚未
发布的上传。Repository 在一个事务中更新条目、追加不可变版本并写审计。
服务层的成员检查只用于提前返回友好错误；每个尚未命中精确收据的新建、追加版本、重命名和删除命令
都会先锁定群行，再复验群仍处于活动状态、类型仍为群聊且操作者仍是活动成员。踢人或解散一旦先提交，
已经完成上传准备但尚未落库的旧请求也必须失败，不能越过撤权边界写入文件树。

条目 revision 是所有修改的乐观锁；contentVersion 只随内容追加递增。目录非空时拒绝删除，所有活跃
条目的历史版本参与配额。`GroupFileCapacityPolicy` 固定每群 10,000 个活动条目、每个 parent 512 个
直接子条目和每个活动文件 128 个版本，字节配额默认 1 GiB 且可配置；零字节文件仍占前三类槽。
Repository 在群行锁内先识别完全相同的资源/版本重试，再执行容量准入，避免重放重复占槽；
createFolder、createFile、addVersion、rename 和 delete 五类命令的事实变更、容量台账、不可变命令收据
与审计必须位于同一事务。createFolder/createFile 的稳定 entryId 和 commandId，以及其余三类操作的
稳定 commandId 均由客户端提供；领域服务只对规范化后的不可变 payload 计算 SHA-256 指纹，同一 ID
改写 payload 必须拒绝。rename/delete 的 RPC 结果是 `Unit`，对应收据 `resultVersion = null`；收到完全
一致的 `commandId + chatId + entryId + actorUid + kind + fingerprint` 时只确认已经提交，不再次推进
revision、容量或审计。这个收据确认是一个很窄的丢响应恢复例外：只有 rename/delete 的精确重放可以在
条目后来改名、删除或操作者后来离群后继续得到 ACK；任何新命令仍按当前群和成员事实裁决。

每群活动条目数与活动版本字节数由群级 usage 行
维护，每条活动文件另存自身版本字节合计，追加和删除的容量更新不扫描历史版本，因此合法上限下的
写入复杂度不会退化为 O(历史版本数)。当前没有移动群文件条目的 RPC；若以后增加，跨 parent 移动
必须在同一群行锁内占用目标同级槽，同 parent 操作不得重复计费。

AttachmentAccess 汇总 MessageStore 和 GroupFileRepository 两类引用，再与实时群成员资格求交集，
HTTP 文件端点不感知具体业务域。

## 8. Authorization

当前只有 Document 需要资产角色到操作能力的映射，因此矩阵直接归 Document 域所有，不提前维护通用
授权内核。Document 能力分为 READ、EDIT_CONTENT、MANAGE_SPACE、MANAGE_POLICY、ARCHIVE_SPACE 和
TRANSFER_CUSTODY；未知角色没有能力。实时所有权、grant 和组织成员事实仍在 Document 的读快照或写
事务中读取和裁决。其他资产、搜索和管理控制面必须各自完成真实领域闭环，不能复用一个类型名就宣称
已完成授权。

## 9. Document

DocumentService 以 DocumentSpace 为权限根。`createdBy` 只保留不可变创建来源；可转移的 owner principal 可以是用户或组织节点；
只有 `stewardUid` 获得隐式 Owner 能力。组织持有本身不是 ACL，部门成员仍须命中显式 grant。用户 grant 和组织部门 grant
按最高角色合并，部门授权可包含下级节点。每个 list/read/history 调用都通过 `PgUnitOfWork.read` 在同一个 PostgreSQL
`REPEATABLE READ` 只读快照中读取 actor 的活动直属归属及其活动祖先链、空间授权以及最终返回的
正文/目录/历史/首页投影；组织路径由一次参数化 recursive CTE 从直属 membership 向上解析，不扫描
或返回宽树中的无关节点，也不按层发起查询；
不得先在独立事务鉴权，再从另一个事务读取受保护数据。写命令锁定空间聚合并在同一事务重算角色。
群成员资格不参与文档权限。

显式 ACL 写入使用 actor 作用域的稳定 `operationId + issuedAt` 与 `expectedPolicyRevision`。适配器按
`User(actor + USER target，uid 排序) → 任意状态 Space → grant` 固定锁序；锁内先识别完全相同的 receipt，
再为新命令校验活动 HUMAN actor、typed `MANAGE_POLICY`、活动目标与 CAS。remove 同样锁目标 User 以保持
锁序，但允许清理已停用用户的 grant；只有 upsert 要求目标仍为活动 HUMAN。真实 grant 变化推进
policyRevision，no-op 不推进，但二者都原子追加不可变 receipt。精确重放永不重执行副作用，只用当前锁内
ACL 重新投影 `effectiveRole/policyRevision`；因此旧命令跨后续 remove/regrant/ban/archive 只能确认历史提交，
不能复活或抹除权限。同 operationId 异指纹和陈旧新命令均返回 409。每 actor 最多保留 1,024 条仍在
7 天窗口内的 receipt，no-op 同样计数；精确回放先于新命令容量准入，过期命令返回 410，窗口满返回 429。

`PgUnitOfWork.read` 固定 JDBC `REPEATABLE_READ`，`PgUnitOfWork.write` 则显式固定 `READ_COMMITTED`，不依赖数据库或 role 默认值。
因此 custody 命令等待 State/行锁后，对 receipt、owner 和权限事实的下一次读取能看到前一事务提交，而只读 RPC 仍保持单一一致快照。

最近访问与最近创建的 SQL 只按 steward 和相关 grant 预筛有界候选；Repository 在同一读快照中批量携带候选空间、相关 grant、直属组织与活动祖先事实，
DocumentAccessControl 再逐项调用 `DocumentAuthorizationPolicy` 的 typed `DocumentCapability.READ` 做最终裁决。SQL 谓词不是授权来源，候选与域裁决不一致时失败关闭。
这套首页收敛不能被描述为文档搜索；当前尚未实现文档搜索。

归属交接只对当前 steward 开放。个人 owner 必须与 steward 为同一用户；组织 owner 必须是活动节点，steward 始终必须是活动 HUMAN。
任何进入写事务的 custody 命令先锁定与组织变更共享的 `OrganizationState` 全局围栏，并在围栏内先复查不可变收据；精确命中立即返回。
新命令随后按 `State → User → Space → Unit` 统一锁序固定目标用户、空间、目标组织节点和组织权限事实，再比较
`expectedCustodyRevision`、校验新 owner 容量，并原子更新归属及追加 operationId/指纹收据。精确重放在当前鉴权前完成，因此原 steward 失权、空间又交接或归档后仍返回原命令的 `DocumentCustodyTransferResult`，但不为任何新请求绕过
TRANSFER_CUSTODY 能力。新 operationId 的无变更目标在实时 ACL 与 custody CAS 锁内返回 400，不追加永久收据；已经提交的相同 operationId 精确重放仍优先命中原收据。

管理员恢复使用独立的 Document custody 控制面，不伪造普通 actor，也不复用单空间命令循环执行。盘点返回 source 当前负责/个人持有的活动空间、
跨全部空间的直接 USER grant 和包含显式目标的计划指纹；执行请求必须携带该指纹与稳定 operationId。适配器在一个事务中按
`OrganizationState → source/target User(uid) → Space(spaceId) → target Unit(unitId) → grant` 锁序重新构造计划，任何空间 owner、steward、
custodyRevision、policyRevision、目标或待撤授权变化都令 CAS 返回 409。计划同时记录每个责任空间和每个
纯直接授权空间的 policyRevision，因此 grant 角色更新、移除或重建即使不改变空间 ID 集合也会令 CAS 失效。
命中后对发生交接的空间推进 custodyRevision，
对实际删除直接 USER grant 的每个空间推进 policyRevision，删除 source 的全部直接 USER grant，并保存真实
admin principal、撤授权数量和逐空间旧/新 revision。source 可以已被 ban，但目标必须是活动 HUMAN；ban 仍不调用交接。空计划也可提交零条目
收据并精确重放，明确表示管理员审阅并执行了当时的零资产/零授权事实。

服务负责空间元数据与归档、grant 管理、文档树无环约束、含子文档节点的删除保护，以及每个节点的正文与历史。
树中没有独立文件夹类型；任一活动文档都可作为另一篇文档的父节点，且成为内节点不得丢失已有正文、身份或修订历史。
活动容量统一限制为每个 owner principal 128 个空间、每个 HUMAN 128 个活动 stewardship、每位用户跨空间最多 1,000 条直接 USER grant、
每空间 10,000 篇文档、每个 parent（包括空间根级）512 个直接子文档。创建以初始用户 owner/steward 行为并发围栏；交接复用上述 custody
围栏和写授权锁序；USER grant 写入先锁目标 User 作为跨空间容量围栏；文档创建和跨 parent 移动以 DocumentSpace 行为围栏；
相同资源 ID 的精确创建重试先复验既有资源的不可变创建指纹，同 parent 改名也不重复占用子文档槽。归档和软删除释放
活动容量。分页读取不代替容量执法；每个空间页在同一 repeatable-read 快照中最多读取
129 个 actor 自有空间 ID，超额时在返回任何页前失败关闭。
名称去除首尾空白后必须为 1..180 个非控制字符，空间名上限 120；Markdown 可为空，最大 1,000,000
字符且不能包含 NUL。为保证所有客户端都能有界解析和渲染，正文同时限制为 20,000 个物理行、
4,096 个可视内容块、64 层引用；单个表格最多 32 列、1,000 个渲染单元格。围栏代码正文不按内容块
逐行计费，但仍受总行数和总字符数约束。Repository 在事务内锁定当前节点行，并在识别 no-op 之前比较
expectedRevision。完全相同的 move/save 不写入；纯父级移动只更新 parentId、审计字段和节点聚合
revision；标题或正文实际变化才追加使用新聚合 revision 的完整内容修订。陈旧 revision 必须失败，
不能因请求目标恰好等于当前事实而成功，也不能自动 last-write-wins。

正文入口是 content-only `updateDocument`，始终保留锁内当前节点名称；标题与 parent 只由可靠
`moveNode` 改变。每条 move/rename 冻结 actor、space/node、目标 parent、规范名称、expectedRevision、
operationId 与 issuedAt。写事务先用 actor User 与任意状态 Space 建立围栏，在当前 ACL/CAS 写入节点的
同一事务追加 actor 作用域 receipt。精确重放先匹配 receipt，只返回相同 operationId 与空移动投影，
不重复 revision 或内容修订；同 ID 异指纹返回 409。每 actor 最多保留 1,024 条仍在 7 天窗口内的身份，
过期返回 410，满窗返回 429，no-op 同样保存 receipt 但不推进 revision。

同级展示顺序不作为第二个可写领域状态：Repository 的分支查询固定按不可变
`DocumentNode.createdAt ASC, nodeId ASC` 返回。移动和重命名保留 createdAt；nodeId 打破同毫秒并列。
当前没有手动 rank、拖拽排序或树 CRDT。

空间和文档创建使用客户端持久化的资源 ID 作为幂等键，并保存 actor 与规范化初始 payload 的 SHA-256
创建指纹。同一键、同一指纹的并发或重试只创建一个资源和一个首修订；同一键对应不同指纹必须失败。
空间创建的协议结果是 `DocumentSpaceCreateResult`：空间活动且原创建者仍为当前 steward 时返回当前权威
`space` 投影；后续交接使其失去 steward 身份或空间已归档时只返回相同 `spaceId` 和 `space = null`，确认
原命令已经提交但不复活旧 Owner 权限。文档创建的协议结果是 `DocumentCreateResult`：首次创建携带完整
`document`，精确已提交重放只需携带稳定 `documentId`，并可令 `document = null`。服务端以
`User(actor) → 任意状态 DocumentSpace` 作为创建命令围栏，在实时 EDIT 鉴权前只检查软删除后仍保留的
actor/space/creationFingerprint 精确收据；未精确命中的命令继续走实时 ACL，碰撞返回 409。新文档、首修订与
创建者最近访问仍在同一事务提交。文档移动首次结果返回节点和事务内解析的权威祖先路径，客户端不能
继续采用请求前缓存的路径；精确重放的空投影只确认已提交，当前路径必须另行读取。

## 10. Bot

BotService 为每个通知应用创建 `UserRole.BOT` 服务账户。账户在 Bot 聚合 PostgreSQL 事务内直接写入
高熵 uid 和随机、非 BCrypt 的不可登录 credential marker；该锁序中不执行 BCrypt，也不另开查询事务。
登录策略在选择任何真实 verifier 前拒绝 BOT/SYSTEM，并只执行 dummy BCrypt 工作，因此不存在可利用的
已知服务账号密码。应用 token 使用 256-bit 随机值，数据库只保存 SHA-256。

BotService 只依赖服务账号创建、群成员投影和消息发送三个窄端口；application 适配器再委托给
UserService、ChatService 和 MessageService。机器人领域不能直接持有这些完整服务或 ChatStore，避免
管理入口绕开统一聊天权限并降低跨领域构造和测试成本。

授权是 `(botId, chatId)` 白名单事实。grant 只接受群聊，并把服务身份加入群；revoke/disable 先撤销
发送权，再移出群。群绑定入口从 URL 取得 botId 与 chatId，正文只接收 Markdown，不能由调用方改写
目标；可选 `Idempotency-Key` Header 与 bot/chat 派生稳定 clientMsgId。消息随后进入正常
MessageService，因此幂等重试、历史、搜索、同步和部门群保留规则都与普通消息一致。

## 11. Device / Presence

DeviceService 列出当前用户设备并踢除指定 deviceId。不能踢其他用户设备，当前设备自踢应有明确
连接关闭语义。设备撤销在 PostgreSQL 事务内推进设备级 credential epoch 并失效该设备 credential；
提交后再以新 epoch 更新 ClientRegistry fence 并关闭旧连接，不能先踢连接再提交撤权。
主动登出还必须携带服务器从当前认证会话捕获的设备 epoch，并在同一事务内 compare-and-revoke；若
同一 deviceId 已由更新的登录或 refresh 推进代次，迟到的旧登出只能退休旧 session，不能删除新 pair。
客户端提交的 deviceId 或 refresh token 不能替代这个权威会话代次。

Presence 由 ClientRegistry 的连接计数派生。Registry 启动时生成规范 UUID `serverEpoch`，revision 从
0 开始；首个设备上线和末个设备下线都在修改连接索引的同一个串行 owner 命令中把 revision 加一，
并捕获 `uid + online + occurredAt + epoch + revision` 完整 transition。中间设备增减不产生变化，revision
溢出直接按不变量失败，不能回绕。

`contact.getPresenceSnapshot()` 只服务当前认证用户。ContactRepository 先给出其完整好友集合，Registry 再在
单个 owner 命令中原子读取当前 revision 和这些候选中的在线子集；不循环调用 `isOnline`，也不暴露全局
在线集合。这样 snapshot 返回 R 之后发生的 transition 必为 `> R`，先发生的变化则已经包含在 R 中。
Presence 增量仍是 `eventId = 0` 的瞬时状态，不进入长期离线事件；断线遗漏由下一次认证/重连等既定
刷新点的完整好友快照修复，同 epoch 的单条丢失不会使服务端主动补发或触发即时快照。

## 12. Admin

管理员能力与普通用户领域调用分开认证。密码重置复用普通认证的 `AuthRules` 和 `PasswordHasher`，
先在有界 CPU owner 生成 verifier，再进入 PostgreSQL。封禁与密码重置在 PostgreSQL 事务内推进用户级 credential
epoch 并失效已有 credential；事务提交后在不可取消的清理阶段更新 ClientRegistry fence、关闭所有
较旧 epoch 连接。解除封禁只恢复账号可登录状态，不回退 epoch，也不会让任何旧 token 复活。
