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

一次登录发生在具体设备上。每个设备拥有独立 token 和 TCP 连接。用户可以多设备同时在线，也可
查看或踢除设备。最后一个设备下线时才代表用户整体离线。

### Token

TeamTalk 使用服务端保存的随机 token，不使用 JWT。access token 用于当前访问，refresh token
一次性轮换。删除 token 可以立即让设备失效。

## 2. 组织目录

### OrganizationUnit

OrganizationUnit 是单组织内的层级节点。一个实例只有一个根节点，其他节点通过 `parentId` 组成树；
`leaderUid` 表示负责人，`sortOrder` 只影响同级展示顺序。节点移动必须阻止自引用和循环。

### OrganizationMember

OrganizationMember 表示用户在一个组织节点中的直接归属，附带职位与是否主部门。用户可以兼任多个
部门，但最多只有一个主部门。组织归属与好友关系互不替代：前者由管理员治理，后者是用户社交关系。

### DepartmentGroup

节点启用部门群后，`unitId` 同时作为稳定 `chatId`。该群成员来自节点及全部后代的组织成员，再并入
获得显式授权的机器人。组织目录是成员事实源，普通群管理接口不能退群、加人、踢人、解散或改名；
服务启动和组织变更都会幂等收敛群成员。

## 3. 社交关系

### ContactApply

好友申请是带方向和状态的请求。申请被接受后，双方分别获得自己视角的 Contact；被拒绝或过期的
申请不形成关系。

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

消息体使用封闭类型表达富文本、图片、语音、视频、文件、系统和交互卡片等内容。普通文本默认是
Markdown 权威源的富文本消息；旧 `TextBody` 仅作为兼容概念，不应继续扩展。

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
静默覆盖。所有历史版本计入当前活跃条目的配额，默认值可由私有部署配置。

群文件不是 Message 的派生列表：聊天附件不会自动进入共享空间，删除消息也不会删除群文件。未来消息
可以引用 GroupFileEntry，但权威名称、版本和权限仍由群文件领域维护。

## 7. 自动化应用

### AutomationBot

AutomationBot 是管理员创建的单向通知应用，关联一个不可密码登录的机器人 User。应用 token 仅在
创建或轮换时显示一次，服务端只保存 SHA-256；每个可发送群必须有独立 grant。`idempotencyKey`
与 bot/chat 共同决定稳定 `clientMsgId`，调用重试不会产生第二条消息。

AutomationBot 与 ImBot 不同：前者适合构建、监控、审批等外部系统主动通知，最小权限且无需常驻
TCP；后者是完整无头客户端，适合需要接收消息和参与双向交互的自动化。

## 8. 事件与本地状态

### Notify

服务端数据变更产生面向用户的事件。大多数事件先持久化，再实时推送；离线设备认证后按
`lastEventId` 补发。事件携带完整快照，使客户端 upsert 保持幂等。

### LocalCache

客户端本地缓存是 UI 的读取源，不是远端权限事实。事件处理成功后先更新本地数据，再推进事件
游标；失败时保留游标以便重试。

## 8. 关系图

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
User 1 ──* Notify
AutomationBot 1 ──1 User(service identity)
AutomationBot * ──* Chat  通过 explicit grant
```

理解这些边界是修改业务的前提：Chat 与 Conversation、远端事实与本地缓存、附件路径与 HTTP URL
不可互换。
