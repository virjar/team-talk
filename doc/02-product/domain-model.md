# 领域模型

本章描述产品概念及它们之间的稳定关系。字段级传输格式属于[协议](../04-protocol/README.md)，
数据库表属于[持久化](../06-server/persistence.md)。

## 1. 身份与设备

### User

用户是组织内的长期身份，包含 `uid`、用户名、显示名、手机号、头像和简介等资料。`uid` 是所有
关系和消息引用的稳定键；显示名和备注是展示信息，不能用于权限判断。

用户身份分为人类用户、机器人服务身份和系统身份。只有人类用户可以使用客户端密码登录。机器人
虽然复用 User/Member/Message 的展示与发送链路，但只能通过独立应用凭据调用受控入口。

### Device

一次登录发生在具体设备上。每个设备拥有独立 credential epoch、credential 和 TCP 连接；同一设备
使用密码、注册或 refresh 流程重新认证时，严格推进设备 credential epoch，只保留一个 access 和
一个 refresh credential，并替换旧连接。用户可以多设备
同时在线，也可查看或踢除设备。最后一个设备下线时才代表用户整体离线。

### Token

TeamTalk 使用服务端签发的随机 token，不使用 JWT。access token 用于当前访问，refresh token
是设备级稳定 bearer，首次签发后保持固定 90 天绝对期限；每次 refresh 只签发新 access，并推进设备
credential epoch，使此前 access 与连接立即失效。相同 refresh 请求可安全重试，解决服务端已提交但
响应丢失后客户端永久掉线的问题；密码登录或注册会替换该设备的完整 credential pair。服务端只保存
token 的 SHA-256，不能回读明文。User 与 Device 分别维护 credential epoch：封禁、管理员重置密码或
用户自助改密推进用户 epoch，设备撤销推进设备 epoch，任何旧代际 token 随即失效。解除封禁不会回退
epoch，也不会恢复旧 token。

## 2. 组织目录

### OrganizationUnit

OrganizationUnit 是单组织内的层级节点。一个实例只有一个根节点，其他节点通过 `parentId` 组成树；
`leaderUid` 表示负责人，`sortOrder` 只影响同级展示顺序。目录读取投影携带 `directMemberCount`，表示
直接归属于该节点的用户数，不包含下级节点；递归人数只有在明确请求子树成员时计算。节点移动必须阻止
自引用和循环。根节点深度为 1，任一活动路径最多 64 个节点；创建、移动、投影与每一个目录/成员页都对
完整活动树失败关闭。活动节点最多 10,000 个，活动与归档身份合计最多 20,000 个；这些是写入准入边界，
读取不能用 `LIMIT` 静默隐藏越界事实。节点归档不释放永久身份槽位。

### OrganizationMember

OrganizationMember 表示用户在一个组织节点中的直接归属，附带职位与是否主部门。用户可以兼任多个
部门，但最多只有一个主部门。单个用户最多直接归属 32 个节点，单节点最多 10,000 条直属关系，整个
组织最多 100,000 条关系；任一上限都在事务提交前明确拒绝。组织归属与好友关系互不替代：前者由
管理员治理，后者是用户社交关系。

### DepartmentGroup

节点启用部门群后，`unitId` 同时作为稳定 `chatId`。该群成员来自节点及全部后代的组织成员，再并入
获得显式授权的机器人。组织目录是成员事实源，普通群管理接口不能退群、加人、踢人、解散或改名；
服务启动和组织变更都会幂等收敛群成员。
每个曾启用部门群的节点保留正或负投影围栏，合计最多 20,000 条；禁用或归档不自动删除该围栏。

## 3. 社交关系

### ContactApply

好友申请是带方向和状态的请求。申请被接受后，双方分别获得自己视角的 Contact；被拒绝或过期的
申请不形成关系。同一发起人与接收人最多存在一条待处理申请。申请记录以当前用户视角区分“收到”和“发出”；只有收到且待处理的记录携带可执行
接受/拒绝的处理凭据，发件人不能取得该凭据。

### Contact

联系人关系按用户视角保存。`uid` 表示关系所有者，`friendUid` 表示对方，备注也属于所有者。
删除好友必须更新双方关系并向双方发送各自视角的事件。

## 4. 会话与成员

### Chat

Chat 是消息所属的逻辑容器，分为私聊和群聊。`chatId` 是消息、会话和成员资格的共同关联键。

- 私聊由确定的两名成员组成。
- 群聊具有名称、群主、角色、禁言和邀请等扩展属性。

### Member

Member 表示用户在群内的资格和角色。角色至少包含群主、管理员和普通成员。所有消息发送、历史
读取、附件引用和群管理操作都必须基于服务端成员校验。

### Conversation

Conversation 是“某个用户对某个 Chat 的收件箱视图”，不是 Chat 本身。它包含：

- `lastSeq`：会话中已知最新消息序列。
- `readSeq`：该用户已经读到的位置，只能单调增加。
- `unreadCount`：由 `lastSeq - readSeq` 计算得到。
- 置顶、免打扰、草稿和最后消息摘要等用户视角状态。

每个用户每个 Chat 有一条 Conversation。建群或加人时必须先确保这些行存在，否则已读和未读
无法跨设备同步。

## 5. 消息与内容

### Message

Message 是会话中的不可变身份加可演进状态：

- `clientMsgId` 由客户端生成，用于发送幂等。
- `serverSeq` 由服务端按 Chat 单调分配，用于排序、历史和已读水位。
- `senderUid`、`chatId` 和 `messageType` 决定归属与解释方式。
- 编辑、撤回和转发状态通过服务端更新后的完整消息快照传播。

### MessageBody

消息体使用封闭类型表达富文本、图片、语音、视频、文件、系统和交互卡片等内容。普通文字只使用
Markdown 权威源的 `RichTextBody`。

### Attachment

Attachment 只描述 TeamTalk 服务端中的文件：规范化相对路径、名称、媒体类型、大小和可选摘要。
它不是任意 HTTP URL。客户端需要展示或下载时，用部署的 `serverUrl` 解析成自身端点 URL。

### GroupFileEntry / GroupFileVersion

GroupFileEntry 是群共享文件空间中的稳定逻辑对象，通过 `parentId` 组成目录树。目录和文件共享名称、
创建者、更新时间与乐观锁 revision；文件另外指向当前 Attachment，并记录独立的 contentVersion。

GroupFileVersion 是不可变的内容版本。上传只是创建 FileStore 对象，只有群成员把自己上传的附件发布为
GroupFileVersion 后，它才获得群文件身份和成员下载权限。重命名不会制造内容版本，替换内容不会覆盖
历史版本。聊天消息引用和群文件引用是两个独立事实源，但下载都经过统一引用 ACL。

## 6. 群文件空间

群文件空间只存在于群 Chat 下，当前群成员可以读取和协作。所有读写都实时校验成员资格；退出或被移除
后，未被其他有权 Chat 引用的文件立即不可下载。目录只能在清空后删除，修改携带 revision 防止多端
静默覆盖。活动容量统一限制为每个群 10,000 个条目、每个 parent（包括根级）512 个直接子条目、
每个活动文件 128 个不可变版本；当前活动条目的全部历史版本还共享默认 1 GiB、可由私有部署配置的
字节配额。零字节文件仍占条目、同级和版本槽，不能绕过这些基数边界。软删除释放该条目及其版本的
活动容量和下载引用，但物理版本继续保留，等待明确的保留与回收策略。

创建目录/文件由客户端同时指定稳定 `entryId` 与 `commandId`；追加版本、重命名和删除也指定稳定
`commandId` 并冻结首次提交的 `expectedRevision`。客户端的创建意图以 `chat + parent + 规范名称`
占用一个槽，因而目录和文件不能在同一位置并发抢占同名身份；追加版本、重命名和删除则共享
`chat + entry` 的 per-entry mutation 槽，一个条目结果未定时不接纳第二个不可变操作。

服务端为五类 mutation 都在同一业务事务中保存认证操作者、操作类型和规范不可变 payload 指纹的收据。
相同标识搭配不同名称、位置、Attachment 或 `expectedRevision` 会冲突，不能重复制造条目、版本或变更。
创建与追加版本的重放仍核对具体条目/版本事实；重命名和删除在 wire 上只返回 `Unit`，因此只有精确命中
`commandId + actor + kind + fingerprint` 的收据才可作为终态 ACK。这是两个无结果载荷 RPC 的窄例外，不表示
条目当前仍存在，也不为新操作提供准入。

群文件不是 Message 的派生列表：聊天附件不会自动进入共享空间，删除消息也不会删除群文件。未来消息
可以引用 GroupFileEntry，但权威名称、版本和权限仍由群文件领域维护。

## 7. 企业文档

### DocumentSpace / DocumentSpaceGrant

DocumentSpace 是企业文档的一级权限根，不归 Chat 拥有，也不自动镜像 OrganizationUnit。`createdBy`
只保留不可变的创建来源；`ownerPrincipalType + ownerPrincipalId` 是可转移的用户或组织资产归属；
`stewardUid` 是唯一获得隐式 owner 角色的活动普通用户。个人持有时 owner 与 steward 必须是同一用户；
组织持有时仍须显式指定 steward，普通组织成员不因资产归属自动获得访问权。

DocumentSpaceGrant 可以把 viewer、editor 或 admin 角色授予具体用户或组织部门，部门授权可选择包含
下级部门。一个用户命中多条授权时取最高角色。`custodyRevision` 是独立于文档节点 revision 的归属乐观锁；
归属交接使用稳定 operationId 和不可变收据防止未知结果重复执行。

`DocumentSpaceCreateResult` 把“稳定创建命令已经提交”和“创建响应能安全携带的当前空间投影”分开表达。
`spaceId` 始终确认精确创建身份；只有空间活动且创建者仍是当前 steward 时 `space` 才存在，并携带当前
owner、steward、custodyRevision 与角色。创建者交接后不再是 steward，或空间归档后的精确重放返回
`space = null`，不能从历史创建事实推导当前 Owner 权限；创建者若还命中显式 grant，其实际角色由后续
授权读取重新计算。

服务端每次访问都根据实时 OrganizationMember 关系计算有效角色，不复制部门成员名单。空间可以覆盖
大部门、跨部门项目或任意团队，不要求和自动部门群一一对应。

### DocumentNode / Document / DocumentRevision

DocumentNode 是空间文档树的有界摘要投影。树中没有独立文件夹类型；每个节点都对应一篇可打开、可修订且可继续
拥有子文档的 Document。DocumentNode 携带 `nodeId`、`spaceId`、`parentId`、`hasChildren`、名称、有界 `excerpt`、revision 与审计身份；
`hasChildren` 只表示当前是否有活动子文档，用于决定是否显示懒加载展开入口，不是节点类型。
`nodeId` 与完整对象的 `documentId` 是同一稳定身份。Document 是该节点的 Markdown 当前快照；文档树和首页列表
不读取或返回完整正文。同级节点不持有可变 position：所有投影都按不可变 `(createdAt, nodeId)` 升序，
改名和移动不会重写创建身份。当前没有手动排序或文档树 CRDT。

Document 返回服务端解析的 `ancestorIds`，表示从空间根到直接父文档的有序文档 ID，不包含
文档自身；根级文档为空。该字段是当前父子关系的派生定位信息，不是独立的可写入事实；客户端用它
定位深层文档并按路径懒加载文档树。

DocumentHomeItem 是跨空间首页投影，包含空间、标题、有界摘要、创建人以及创建/更新/当前用户访问
时间。用户访问时间来自 `(uid, documentId)` 唯一的服务端事实，不能由客户端用更新时间推断。

Document 的 revision 是标题、正文、父级和删除状态共享的节点聚合版本。DocumentRevision 是创建以及
标题或 Markdown 正文实际变化时产生的不可变完整快照；纯父级移动只推进节点聚合版本，所以内容修订号
可以有空洞。完全相同的当前版本写入不推进版本，但服务端始终先比较 `expectedRevision`，陈旧请求即使
目标值恰好等于当前事实也必须冲突。更新、移动和删除都必须提交当前 `expectedRevision`；服务端只允许
一个调用获得下一个聚合版本，其余调用收到冲突并保留本地草稿。恢复旧版本以旧内容再创建一个新
revision，不回退或删除历史。`updateDocument` 只接收正文及资产清单；标题/父级由 `moveNode` 独占，后者
携带稳定 `operationId + issuedAt`，并以 `DocumentMoveCommandResult` 区分首次提交投影与精确重放确认。

完整产品边界见[企业文档](documents.md)。

## 8. 自动化应用

### AutomationBot

AutomationBot 是当前群成员可以从群设置创建、系统管理员也可以在后台下发的单向通知应用，关联一个
不可密码登录的机器人 User。群成员创建的机器人固定属于当前群：创建者拥有凭据轮换和移除权，群主
和群管理员拥有本群移除权，但不能读取或轮换其他创建者的 token；系统下发机器人只由后台治理。
应用 token 仅在创建或轮换时显示一次，服务端只保存 SHA-256；每个可发送群必须有独立 grant。
页面给出的入站 URL 同时绑定 bot 和目标群，请求正文只接收 Markdown。调用方需要自动重试时，可以
提供 1–120 字符的 `Idempotency-Key` Header；它与 bot/chat 共同决定稳定 `clientMsgId`，相同正文的
重试不会产生第二条消息。

AutomationBot 与 ImBot 不同：前者适合构建、监控、审批等外部系统主动通知，最小权限且无需常驻
TCP；后者是完整无头客户端，适合需要接收消息和参与双向交互的自动化。

## 9. 事件与本地状态

### Notify

服务端数据变更产生面向用户的事件。大多数事件先持久化，再实时推送；设备完成身份认证后，由已
就绪的本地缓存按持久化 `lastEventId` 显式分页同步，收到 `SYNC_READY` 才开放实时推送。事件携带
完整快照，使客户端 upsert 保持幂等。
组织目录变更是明确例外：`ORGANIZATION_CHANGED` 只携带 revision 并瞬时直发，客户端持久化
requiredRevision 后仍以二进制分页 RPC 读取事实，认证恢复全量对账承担漏提示补偿。

### LocalCache

客户端本地缓存是 UI 的读取源，不是远端权限事实。事件处理成功后先更新本地数据，再推进事件
游标；失败时保留游标以便重试。

## 10. 关系图

```text
User 1 ──* Device
OrganizationUnit 1 ──* OrganizationUnit(child)
User * ──* OrganizationUnit  通过 OrganizationMember
OrganizationUnit 0..1 ──1 Chat(department group)
User 1 ──* Contact(owner) *──1 User(friend)
User * ──* Chat      通过 Member
User 1 ──* Conversation *──1 Chat
Chat 1 ──* Message
Message 0 ──* Attachment
Chat 1 ──* GroupFileEntry
GroupFileEntry 0..1 ──* GroupFileVersion ──1 Attachment
DocumentSpace 1 ──* DocumentSpaceGrant ── User / OrganizationUnit
DocumentSpace 1 ──1 owner principal ── User / OrganizationUnit
DocumentSpace 1 ──1 steward ── User(HUMAN)
DocumentSpace 1 ──* DocumentNode(document summary)
Document(document node, optional children) 1 ──* DocumentRevision
User 1 ──* Notify
AutomationBot 1 ──1 User(service identity)
AutomationBot * ──* Chat  通过 explicit grant
```

理解这些边界是修改业务的前提：Chat 与 Conversation、远端事实与本地缓存、附件路径与 HTTP URL
不可互换。
