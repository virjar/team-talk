# 领域服务

## 1. User / Auth

### 注册

服务端再次执行 AuthRules，检查用户名/手机号唯一性，生成短 uid，并用 BCrypt 保存密码哈希。uid
冲突重试必须有上限和安全随机源。

### 登录与 refresh

未知用户和密码错误使用相同外部错误。认证在 PostgreSQL 事务内创建或更新 Device，签发随机
access/refresh token，但只保存 SHA-256；密码登录或注册签发时，同一用户同一设备只保留最新
credential pair。签发记录同时捕获 Users 与 Devices 的 `credentialEpoch`，后续 TCP 和 HTTP 校验都
要求账号、设备、凭据三者状态与 epoch 一致。refresh token 成功使用后在同一事务轮换完整
access/refresh pair，旧 access 也立即失效，避免同设备凭据记录无界增长。每次 pair rotation 都严格
推进设备 credential epoch，并把“数据库 mutation + ClientRegistry fence”作为不可被请求取消拆开的
终态编排；不能出现已提交新凭据、旧 TCP 却继续有写权限的窗口。

认证成功本身不注册实时连接。客户端事件投影就绪后按持久游标显式分页同步；服务端最终在用户
delivery gate 内二次查空、发送 `SYNC_READY` 并激活实时连接。ClientRegistry 激活时还要检查已提交的
credential fence，旧 epoch 会话不能重新进入在线集合；同设备的新登录会立即替换旧连接。

用户自助改密与管理员重置一样，在锁定 User 行的事务内更新密码、推进用户 credential epoch 并删除
全部凭据。发起自助改密的精确连接在提交后立即从认证/实时集合移除并进入终态，只为写完成功 RPC
响应而短暂保留 channel，响应 flush 后关闭；其他旧连接由同一 fence 关闭。会话例外使用 Netty 完整
channel id，而不是 deviceId 或用于日志的短 id，避免误保留同设备并发连接。

### 资料更新

只能更新允许的个人字段；uid、用户名等身份键不能由普通资料接口覆盖。更新后向用户所有设备发送
USER_UPDATED。

## 2. Contact

- `apply`：不能申请自己、不存在的用户、服务身份、已有好友或被策略禁止的用户。同方向 pending 在
  锁定双方 User 行的事务中复用且不重复通知；反方向 pending 保留给接收者处理，不能再创建镜像申请。
- `accept`：验证申请接收者和 token，在事务中形成双方关系。
- `reject`：只改变申请状态，不创建关系。
- `listPendingApplies` 只返回收到且待处理的最新 100 条；双向历史使用有界分页
  `listApplyRecords`，资料页的两人 pending 状态使用 `getPendingApply` 精确查询，不能从历史首屏推断。
- 处理 token 只属于收到申请的一方；`apply` 响应、发出记录和已处理记录不得回显 token。
- `delete`：删除双方关系，并分别发送双方视角的 CONTACT_DELETED。
- 备注属于关系所有者，不更新对方 User。
- 黑名单在建立关系和发送交互时参与权限判断。

Contact 事件的 payload 视角必须明确；服务端不能把 A→B 的 Contact 原样发给 B。

## 3. Organization

OrganizationService 维护单根无环目录、用户多部门归属和唯一主部门。负责人必须是有效用户，并在
启用部门群时自动成为直属成员。删除负责人归属前必须先变更负责人。

部门群以 OrganizationUnit.groupChatId 是否存在为启用状态。收敛算法计算当前节点及全部后代成员，
再合并 RequiredChatParticipants（当前为获授权机器人），调用 ChatService 的受管群管理入口补齐成员、
移除多余成员并维护 Conversation。普通群 RPC 遇到受管群时拒绝成员或生命周期变更。

跨表流程采用“先组织事实、后幂等投影”：节点移动和成员变更提交后立即收敛，应用启动再重放全部
启用节点。单个失败会记录 unitId，不阻止其他部门恢复。

## 4. Chat / Member

### 私聊

创建私聊前验证两人关系，并以排序后的用户对作为数据库唯一键。并发创建会收敛到同一 Chat，
成员与双方 Conversation 在同一事务内建立。

### 群聊

创建者成为群主，初始成员去重并验证存在。创建 Chat、Member 与各成员 Conversation 后再发送
CHAT_CREATED。

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
建立成员与 Conversation 并消费一次额度。重复加入只补齐 Conversation，不重复计数；缓存和事件只在事务提交后更新。

聊天是否存在、是否群聊、成员资格以及管理员/群主阈值统一由 `ChatAccess` 判断。Chat、Message、
GroupFile 和 Bot 不得各自复制角色数字和成员错误分支；禁言、黑名单、受管群等操作专属规则仍由
对应领域服务负责。

## 5. Message

发送顺序：

1. 校验 sender 是当前认证用户且属于 Chat。
2. 规范化 MessageBody 与 Attachment。
3. 校验群禁言、消息类型和业务限制。
4. 在 FileStore 验证全部附件。
5. 按 clientMsgId 查询幂等结果。
6. 分配 serverSeq，原子写入 MessageStore、幂等索引、附件反向索引、revision 和 CREATE operation。
7. Lucene 按 revision 幂等提交；PostgreSQL receipt、Conversation 和同步事件在同一 UoW 提交。
8. 投影全部成功后清除 outbox，再返回 ACK。

若第 7 步中断，相同 `clientMsgId` 重试或服务端重启会继续补齐投影，不能只返回旧 seq。EDIT/REVOKE
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

创建 Chat 时，初始 Member 与 Conversation 由 ChatRepository 在同一 PostgreSQL 事务建立。后续加人、
邀请加入以及受管群收敛使用 `ensureConversations` 补齐新增成员；创建完成后不能再重复写一轮相同投影。

## 7. GroupFile

GroupFileService 通过统一 `ChatAccess` 只接受当前群成员访问，并拒绝在私聊上创建文件空间；它不再
通过“先列出用户全部会话再查包含关系”的旁路判断权限。创建文件或新版本时，服务端重新
查询 FileStore，要求 Attachment 元数据完全匹配且调用者就是该次上传者；因此不能抢占其他成员尚未
发布的上传。Repository 在一个事务中更新条目、追加不可变版本并写审计。
服务层的成员检查只用于提前返回友好错误；每个创建、追加版本、重命名和删除事务都会先锁定群行，
再复验群仍处于活动状态、类型仍为群聊且操作者仍是活动成员。踢人或解散一旦先提交，已经完成上传
准备但尚未落库的旧请求也必须失败，不能越过撤权边界写入文件树。

条目 revision 是所有修改的乐观锁；contentVersion 只随内容追加递增。目录非空时拒绝删除，所有活跃
条目的历史版本参与配额。AttachmentAccess 汇总 MessageStore 和 GroupFileRepository 两类引用，再与
实时群成员资格求交集，HTTP 文件端点不感知具体业务域。

## 8. Document

DocumentService 以 DocumentSpace 为权限根。所有者是创建者；用户授权和组织部门授权按最高角色合并，
部门授权可包含下级节点。每个 list/read/write/history 调用都从 OrganizationRepository 读取当前成员
归属并重算角色，群成员资格不参与文档权限。

服务负责空间元数据与归档、grant 管理、目录树无环约束、非空目录删除保护，以及文档正文与历史。
名称去除首尾空白后必须为 1..180 个非控制字符，空间名上限 120；Markdown 可为空，最大 1,000,000
字符且不能包含 NUL。为保证所有客户端都能有界解析和渲染，正文同时限制为 20,000 个物理行、
4,096 个可视内容块、64 层引用；单个表格最多 32 列、1,000 个渲染单元格。围栏代码正文不按内容块
逐行计费，但仍受总行数和总字符数约束。Repository 在事务内锁定当前节点行，比较 expectedRevision，更新当前快照并为
文档追加下一个完整修订。陈旧 revision 必须失败，不能自动 last-write-wins。

## 9. Bot

BotService 为每个通知应用创建 UserRole.BOT 服务账户。该账户的随机密码不返回，UserService 登录路径
也显式拒绝 BOT/SYSTEM。应用 token 使用 256-bit 随机值，数据库只保存 SHA-256。

BotService 只依赖服务账号创建、群成员投影和消息发送三个窄端口；application 适配器再委托给
UserService、ChatService 和 MessageService。机器人领域不能直接持有这些完整服务或 ChatStore，避免
管理入口绕开统一聊天权限并降低跨领域构造和测试成本。

授权是 `(botId, chatId)` 白名单事实。grant 只接受群聊，并把服务身份加入群；revoke/disable 先撤销
发送权，再移出群。群绑定入口从 URL 取得 botId 与 chatId，正文只接收 Markdown，不能由调用方改写
目标；可选 `Idempotency-Key` Header 与 bot/chat 派生稳定 clientMsgId。消息随后进入正常
MessageService，因此幂等重试、历史、搜索、同步和部门群保留规则都与普通消息一致。

## 10. Device / Presence

DeviceService 列出当前用户设备并踢除指定 deviceId。不能踢其他用户设备，当前设备自踢应有明确
连接关闭语义。设备撤销在 PostgreSQL 事务内推进设备级 credential epoch 并失效该设备 credential；
提交后再以新 epoch 更新 ClientRegistry fence 并关闭旧连接，不能先踢连接再提交撤权。

Presence 由 ClientRegistry 的连接计数派生：首个设备上线广播 online，最后一个设备下线广播
offline。它是瞬时状态，不进入长期离线事件。

## 11. Admin

管理员能力与普通用户领域调用分开认证。封禁与密码重置在 PostgreSQL 事务内推进用户级 credential
epoch 并失效已有 credential；事务提交后在不可取消的清理阶段更新 ClientRegistry fence、关闭所有
较旧 epoch 连接。解除封禁只恢复账号可登录状态，不回退 epoch，也不会让任何旧 token 复活。
