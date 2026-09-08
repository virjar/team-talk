# 功能状态

TeamTalk 当前发行版本为 0.0.1，处于开发者预览阶段。本文描述当前源码能力，包含待发行 protocol 0.2 的文档变更、评论与内容搜索；已发布 0.0.1 的范围以[发行说明](../07-operations/releases/0.0.1.md)为准。本文区分规划、实现与实际验证范围。

少量技术内测的范围和最小分发检查见[开发者预览版指南](../01-getting-started/developer-preview.md)，
本页不把候选包构建或轻量冒烟等同于正式发布。

表格用于快速判断边界；较长的实现说明折叠在各表下方。这里的“可用”不代表已通过正式发布制品的全部门禁。

状态定义：

- **可用**：主要链路已落地，并有对应测试或实际验收入口；
- **部分**：契约或局部界面存在，但关键闭环仍缺失；
- **计划**：产品方向明确，尚未形成可依赖契约；
- **边界外**：当前架构有意不承担。

## 版本与兼容

| 能力 | 状态 | 当前边界 |
|---|---|---|
| 数字协议版本与兼容窗口 | 可用 | 源码待发行协议 0.2、最低支持 0.0，正式 0.0.1 冻结协议 0.1；TCP 先协商再 AUTH，服务端最低版本可提高；旧客户端横幅提示或强制拒绝工作区。协议 0.1 新增携带账号范围的封禁帧，保留原认证响应布局 |
| 协议生命周期与构建检查 | 可用 | since/removed 注解、生成版本窗和提交的 wire 签名清单；同 major 新增编号、退役留墓碑。手写 codec 语义仍须审阅和 golden 检查 |
| 数据升级机制 | 部分 | 客户端 minor 走 SQLDelight 事务迁移，major 在启动前清本安装数据并重新登录；PostgreSQL 已有从 0 起的顺序迁移台账，现有资料保留；跨存储迁移与完整备份恢复仍需逐项补齐 |

对外尚不承诺稳定版兼容；普通升级保留资料，同一 major 已发行契约冻结。准确规则与尚须业务适配的边界见[版本机制](../04-protocol/versioning.md)。

## 核心业务

| 领域 | 状态 | 当前边界 |
| --- | --- | --- |
| 注册、登录、凭证恢复、退出 | 可用 | 双端提交有等待反馈，失败可原地重试，Desktop 注册失败保留表单；支持多设备 token，持久凭据可离线恢复本地会话，认证终态失败停止自动重连。连接与会话所有权见[客户端与 SDK](../03-architecture/client-and-sdk.md) |
| 账号封禁与本地资料清理 | 可用 | 服务端权威封禁，双端按精确账号清理并在启动时续清；HTTP 401 先经 refresh 重验，密码错误不触发删除。只清理本安装内该 deployment/dataset/uid 的资料和匹配凭据，保留其他账号、安装配置及系统导出文件。见下方实现边界 |
| 用户资料 | 可用 | 姓名、手机号等读写和 USER_UPDATED 已接通；手机号入口为大陆 11 位，统一存储格式并兼容已有 +86 数据，格式/占用失败给出明确反馈 |
| 好友申请、接受、删除、备注、黑名单 | 可用 | 双端资料页可设置/清空私人备注，多处展示统一备注优先，CONTACT_UPDATED 同步本人设备；服务端是关系与权限事实源；每人发出/收到 pending 各有 100 条事务硬边界，待处理视图完整；接受/拒绝使用本地持久 operationId/issuedAt 与服务端原子结果收据覆盖 7 天内丢响应重试，收据每 actor 最多 1,024 条且不淘汰未过期身份；双向终态历史至多保留最近 1,000 条并支持游标分页，两人 pending 可精确查询 |
| 私聊、群聊和群成员管理 | 可用 | 建群和邀请链接创建使用客户端稳定 operationId 与服务端持久收据覆盖丢响应重试；GUI 在 RPC 前按 deployment + uid 持久化冻结命令，可跨进程恢复；邀请回执 7 天内每创建者最多 256 条、不淘汰未过期身份且重放重新校验当前 admin，包含角色、禁言、邀请链接、转让群主 |
| 组织架构与成员归属 | 可用 | 单组织树、多部门归属与受管群已接通；目录按 revision 分页收敛，支持离线旧投影，权限仍由服务端裁决。细节见下方同名说明。 |
| 受管部门群 | 可用 | 节点子树是成员事实源；组织变更和启动恢复自动收敛，拒绝手工成员修改 |
| 会话列表、草稿、置顶、静音、已读 | 可用 | 会话设置多设备同步；普通草稿与已读支持本地 outbox 和恢复。富资产草稿仍有独立边界。细节见下方同名说明。 |
| 文本消息 | 可用 | 统一使用 `RICH_TEXT` / Markdown，只有一条 wire 分支 |
| 消息提交身份与崩溃边界 | 可用 | 复合消息身份幂等；RocksDB 原子记录消息与待投影操作，Lucene/PG 投影补齐后成功 ACK。见[数据与同步](../03-architecture/data-and-sync.md)。细节见下方同名说明。 |
| 编辑、撤回、回复、转发 | 可用 | 消息修订与引用链路已接通；回复作者正文支持与普通富文本相同的 canonical 图片/文件 sidecar、上传屏障、认证渲染和历史重放 |
| 消息搜索与结果定位 | 可用 | Desktop 与 Android 都以 `chatId + serverSeq` 消费结果；目标不驻留时只加载一页有界目标历史，按精确 seq 滚动并短时高亮；无权限、撤回或不存在目标以同一安全状态降级 |
| 断线队列、重连和离线事件补偿 | 可用 | 发送与多类可靠命令可持久恢复；checkpoint 加 tail 恢复当前投影，保留本地可靠事实，不承诺永久历史回调。细节见下方同名说明。 |

<details>
<summary>账号封禁与本地资料清理：实现边界与验收入口</summary>

密码登录仅在密码验证通过后返回封禁账号身份，refresh 响应须匹配已有凭据的 deployment 与 uid；
无身份字段的兼容封禁拒绝只能使用 refresh 已证明的精确范围，不能按用户名推测账号。
清理先持久化 marker、关闭 UI/writer/媒体/SDK 数据库，再清理账号数据库族及隔离副本、草稿、媒体、
遥测与匹配凭据，全部完成后才移除 marker。中断或失败保留 marker，双端下次进程启动先续清；
Android 清理失败的退出会终止本应用进程，使重新打开必经 Application 恢复。
系统导出副本和服务器资产不属于本地清理范围。

实现与测试入口见[客户端与 SDK](../03-architecture/client-and-sdk.md#211-账号封禁与本地资料清理)，
发行制品的在线封禁、错误密码、启动续清和身份隔离按[REL-05](roadmap.md#rel-05--发布物晋级门禁)复验。

</details>

<details>
<summary>组织架构与成员归属：实现边界与验收入口</summary>

单组织树、用户多部门/唯一主部门及节点/关系硬容量已落地；终端读固定走 `OrganizationRpc` 二进制 revision-fenced 分页，管理写走独立 HTTP 控制面。单位与直属成员投影都持久 known + revision，并由单调 requiredRevision 区分冷缓存、权威空与 stale nonempty；旧行离线可见但不作权限权威。提交后 `ORGANIZATION_CHANGED(61)` 只向 SYNC_READY 连接瞬时提示，认证恢复的全量 RPC 负责断线兜底；节点持有活动文档空间时归档失败，必须先显式交接资产

</details>

<details>
<summary>会话列表、草稿、置顶、静音、已读：实现边界与验收入口</summary>

Desktop 右键与 Android 长按会话菜单均可切换免打扰。普通 Markdown 草稿与已读先落本地持久 outbox，
断网立即生效，认证恢复后同步到同账号其他设备；打开中的输入框消费远端草稿更新与清空，同时保留回复和
消息编辑上下文。含内部资产 URI 的未发送聊天草稿因无 sidecar 草稿契约而只保留当前会话，
不向 SQLite 或跨设备同步写入裸 URI。权威会话全量投影按有界 keyset 页收齐后原子替换，
其余服务端事实由事件收敛。实现与恢复顺序见[数据与同步](../03-architecture/data-and-sync.md)。

</details>

<details>
<summary>消息提交身份与崩溃边界：实现边界与验收入口</summary>

MESSAGE、MESSAGE_ACK、服务端、SQLite 与 Lucene 统一使用 `chatId + clientMsgId` 复合身份。
MessageStore 把 chat 高水位、消息、幂等索引、revision、附件索引与 CREATE outbox 放入同一 sync-WAL 批，
PostgreSQL 的 `Chat.maxSeq` 只在 receipt/Conversation/事件事务中连续推进。权威批前失败不消耗 seq，
相同 identity 重试返回原 seq；成功 ACK 依赖持久投影完成。
各持久边界的进程死亡和恢复断言见[消息持久边界验收](../09-testing/deployment-acceptance.md#消息持久边界进程死亡)。

</details>

<details>
<summary>断线队列、重连和离线事件补偿：实现边界与验收入口</summary>

本地缓存当前 epoch 持久化有界 outgoing/命令 outbox、草稿/已读、组织与文档投影、完整 User/个人会话头像描述符、personal peer uid、User/peer revision 及 Reply 资产 sidecar；群文件五类变更以及文档 move/rename 使用持久命令 outbox；文档节点按不可变创建坐标排序。已建立本地关系或精确观察的用户投影与 checkpoint 身份元组按 revision CAS，迟到 RPC、durable/transient 事件或组织嵌入快照不能回退姓名/头像。尚无关系的瞬时 User 提示只用 256 项 session LRU 桥接首次加载，溢出可丢，后续关系 RPC、资料查询或重连刷新负责恢复。当前协议在游标低于服务端保留 floor 时，继续使用 `SyncRpc` 收齐 User/Contact/Chat/Conversation checkpoint，按 expected dataset + cursor CAS 一次 SQLite 安装并从 `baseEventId` 拉 tail；各 section 页不共享跨 RPC MVCC snapshot。Bot inbox 不回收未 ACK 行，但 at-least-once 只覆盖仍在服务端保留窗内或已进本地 inbox 的事件；超长离线只恢复当前投影/可查消息历史，不补已压缩的历史 delivery/编辑/撤回回调

</details>


## 附件与富内容

| 能力 | 状态 | 当前边界 |
| --- | --- | --- |
| TeamTalk 文件上传与下载 | 可用 | 带稳定 identity 的可重复流式上传、长度校验与收据恢复已实现；下载鉴权和缓存边界见[文件存储](../06-server/file-storage.md)。细节见下方同名说明。 |
| 附件存储治理 | 部分 | FileStore 已有全局/上传者硬配额、未引用租约和安全 GC；尚缺账号/组织维度可查询、可审计的配额、归属、保留期及账号生命周期策略 |
| 附件发送与读取安全校验 | 可用 | 成功 ACK 前校验主文件、缩略图和元数据；上传者只对未业务绑定 staging 对象有预览/提交旁路，绑定后下载必须通过当前消息/群文件/文档 ACL；文档新资产只接受本人 staging 或同一文档历史已知资产，不接受任意已授权业务资产重绑；引用提交/撤销与物理回收通过固定容量的单实例分片跨存储围栏及可恢复 tombstone 收敛 |
| 普通文件下载策略 | 可用 | 小文件静默下载，大文件点击后下载，气泡展示传输状态 |
| 图片、语音、视频与缩略图 | 部分 | 完整下载后在有界账号缓存播放，支持离线缓存命中；平台覆盖与系统信任签名、公证边界见下方说明 |
| Markdown 富文本、mention、代码块 | 可用 | 输入和展示以 `RICH_TEXT` 为主；跨端渲染需持续回归 |
| Markdown 上下文图片/文件 | 部分 | 聊天/回复/文档共用 sidecar 与上传屏障，同进程取消、重试和双端导入已落地；聊天富资产跨进程 spool/outbox 未完成。细节见下方同名说明。 |
| 交互卡片 | 部分 | 发送、协议和基础渲染存在，动作回调、权限与业务路由尚未产品化 |
| 表情回应 | 可用 | 双端回应操作、行级事件与聚合计数已实现；范围快照、实时增量和恢复共享本地回应投影。细节见下方同名说明。 |

<details>
<summary>TeamTalk 文件上传与下载：实现边界与验收入口</summary>

HTTP 端点与 IM 服务同一部署，消息保存相对路径。两端按附件声明大小和 512 MiB 绝对上限做精确流式下载校验。
上传使用 canonical `uploadId` 和有限期 `issuedAt`，在读取正文前按 `Content-Length` 预留 uid/global
字节与对象槽；FileStore 持久 `STARTED` / `COMPLETED` 收据，使相同 identity 在响应丢失或服务重启后
返回原 descriptor，改写 payload 返回 `409`。未引用上传默认 7 天后经有界扫描回收。
大小对象容量、重启与重放入口见[部署验收](../09-testing/deployment-acceptance.md#附件容量基线入口)；
GUI 跨进程附件 outbox 仍属[可恢复富资产创作](roadmap.md#client-04--可恢复富资产创作)。

</details>

<details>
<summary>图片、语音、视频与缩略图：实现边界与验收入口</summary>

Desktop 与 Android 共享消息模型。媒体经认证完整下载、精确大小校验和原子发布后进入账号/部署/dataset
隔离目录；同一物理媒体根的所有目录共享有界 LRU、字节/条目预留和消费者租约。缓存跨页面复用，
命中时可离线读取。视频播放器只读取有租约的本地文件，下载阶段不创建播放器；Android 视频、语音与
缩略图均使用原子租约。验收覆盖下载/播放器互斥、seek/暂停/全屏、离线命中和释放后无 partial/FD 残留。
Apple Silicon 原生媒体的完整实机矩阵和系统信任签名、公证仍有待覆盖；不能从 x86_64 或开发构建结果
推导全部平台的发行包均已验收。操作步骤见[本地优先媒体门禁](../09-testing/deployment-acceptance.md#desktopandroid-本地优先媒体门禁)。

</details>

<details>
<summary>Markdown 上下文图片/文件：实现边界与验收入口</summary>

scope-local URI + canonical sidecar 覆盖普通消息、回复作者正文和文档；服务端统一校验与索引引用，
客户端复用上传屏障、认证渲染和画廊链路。聊天 Desktop 支持 picker/drop/binary paste，Android 支持
picker/clipboard；文档 Desktop 支持 picker/drop/binary paste，Android 支持 picker、显式粘贴和物理键盘粘贴。
Chat/Document 的有界待处理列表支持当前进程取消、失败重试和移除，稳定保留 `jobId`、`assetId` 与上传 identity；
迟到结果不会复活已删除引用。可视编辑器按当前选区连续插入图片/文件并保留正文顺序。

尚不支持跨进程/跨设备富资产聊天草稿、持久本地源文件与有界 spool/outbox、断网续传和 Android 文档拖放。
Android 系统 picker 打开期间 Activity 重建的设备覆盖仍需补齐。真实导入、保存/发送、重进与回复双向互发
按[附件验收](../09-testing/deployment-acceptance.md#聊天可视光标内嵌资产双端门禁)核对。

</details>

<details>
<summary>表情回应：实现边界与验收入口</summary>

范围读取以整个请求区间替换，空响应会清理旧回应；飞行期间的 delta 会使迟到快照失效，checkpoint 原子清理可回拉回应投影，重新认证后补当前窗口。边界见[数据与同步](../03-architecture/data-and-sync.md#表情回应完整区间快照与实时增量)。

服务端 `message_reactions` 行级权威表提供 row-keyed 幂等增删、成员与撤回校验、每用户每消息 12 个不同 emoji 上限和聚合计数；`MESSAGE_REACTION` 事件实时/离线补发行级 delta，`listReactions` 返回权威快照，消息撤回在同一事务清空回应。双端气泡 chips（emoji + 计数 + 本人高亮）点击切换；长按/右键菜单快捷栏与 chips 行尾的"＋"可展开完整表情选择器（与输入区表情面板共用 192 个候选），选择器为"确保已添加"语义，取消走 chips 切换。实时增量、完整快照与离线恢复均写入同一本地回应投影

</details>


## 客户端体验

| 能力 | 状态 | 当前边界 |
| --- | --- | --- |
| Desktop 应用壳与三栏布局 | 可用 | 应用级标题栏、全局搜索、聊天主体和临时右侧检查器分别管理；macOS 关闭隐藏后点击 Dock 可恢复并聚焦主窗口。Windows 支持本地安装身份、中文托盘菜单与 MSIX 数据目录，打包包含 Skiko 原生库；启动失败可复制诊断详情。Linux、DPI/多屏组合及发行包验收按实际交付范围执行 |
| 用户资料 | 可用 | Desktop 使用模态弹窗；Android 使用页面导航 |
| 群设置 | 可用 | Desktop 从聊天栏打开右侧抽屉，点击外部或关闭按钮收回；Android 使用详情页。两端固定页头，正文与成员列表统一滚动，成员逐项惰性布局，退出或解散操作位于末尾并避让底部安全区；设置组件共用明暗主题令牌 |
| 富文本输入 | 可用 | 文档支持标题、撤销重做、链接、列表与缩进，并以可视块编辑引用、代码围栏和 GFM 表格；未知扩展仅局部保留源码；聊天与文档的图片/文件都可在当前可视选区后连续插入且不切换模式。文档顶层正文按字符选区精确插入，引用、代码、表格等结构块按相邻块边界插入 |
| 明暗主题 | 可用 | 共享令牌，平台持久化主题选择 |
| 真实头像 | 可用 | 用户头像使用强类型 FileStore `Attachment`；双端系统 picker 自动居中裁成不超过 512×512 的方形 PNG，经认证媒体缓存展示，支持上传、替换、清除、进度和失败回落。替换或清除使旧头像引用失效，缓存命中可离线显示；群头像不在当前范围内 |
| 在线状态 | 可用 | 服务端使用有序 epoch/revision，客户端通过认证/联系人快照刷新和会话内 reducer 收敛，双端好友头像显示 ONLINE 圆点。断线立即回到 UNKNOWN，重连从新快照恢复，不持久化旧 ONLINE；离线页面仍保留本地联系人 |
| 输入中状态 | 可用 | 只反映当前前台输入，瞬时发送与超时清理已接通；不写消息历史、outbox 或持久事件。细节见下方同名说明。 |
| 全局消息与用户搜索 | 可用 | 本地会话、联系人和远程消息/用户聚合 |
| 文档全文搜索 | 可用 | 检索当前可读文档的标题与 Markdown 正文，分词后的查询词需全部命中，可跨标题和正文满足；支持限定空间、有界分页及权威打开。细节与验证范围见下方内容搜索说明。 |
| 文件搜索 | 可用 | 群文件与聊天主附件按不区分大小写的字面文件名片段检索，支持来源、MIME 类型、范围筛选和分页；当前成员权限与对象身份在查询、打开和下载时复验。细节与验证范围见下方内容搜索说明。 |
| 服务搜索 | 边界外 | 当前只有受控通知机器人入口；第二个命名外部应用出现并证明需要统一发现前，不建设应用/服务注册表或全局服务搜索 |
| 客户端资源换代与首帧 | 可用 | Desktop 在 IO 创建媒体与草稿候选、Main 组装导航；Android 完整组装后发布。失败、取消和会话替换均由单一 owner 清理或交接，详见下方说明 |
| 本地缓存生命周期 | 部分 | 消息/媒体有界回收与损坏隔离已落地；离线 SQLite compaction、隔离库的显式诊断与恢复工具仍缺。细节见下方同名说明。 |
| Android 专项体验 | 部分 | 核心业务及文档图片/文件的系统 picker、二进制粘贴和物理键盘粘贴可用；系统 picker/权限/返回路径、最低无障碍语义和发行制品真机矩阵仍须按目标设备覆盖 |
| Android 后台 Push 与系统通知 | 部分 | 已接进程存活且系统允许后台联网时的新未读通知、Android 13 权限申请及通知点击回到会话；前台/静音/历史同步不提醒，退出清理。尚无设备 endpoint、国产 Provider、服务端 wake outbox 和后台网络受限/进程回收后的唤醒闭环，详见[Android 通知](../05-clients/android.md#消息通知的当前范围) |
| 保存的消息 | 可用 | 每用户唯一私有会话，支持消息副本、稳定命令幂等、历史/搜索及多设备同步；首次收藏前不占会话列表，有内容后按普通会话排序，支持用户主动置顶/取消。系统服务身份不等于对端用户账号。服务端入口为 SavedMessageIntegrationTest 与 RemoteAcceptanceTest 的 saved messages 场景；图形端复用 ConversationListScreen 与现有聊天页面 |

<details>
<summary>内容搜索：实现边界与验收入口</summary>

文档与群文件共享可重建资产索引，聊天附件复用消息索引；查询以当前领域权限筛选候选，并回读权威对象
校验 revision、状态与身份。客户端只在当前搜索页有界驻留摘要，收到相关变更后退役旧结果与游标；空页仍可携带继续游标。
打开结果使用领域 RPC 重新读取，文件按既有认证下载流程完整下载后预览，聊天附件可定位原消息。
搜索覆盖当前文档标题与正文、群文件名和聊天主附件文件名，不搜索评论、历史正文、OCR 或文件二进制内容，缩略图与表情不产生附件命中。

服务端真实存储、协议、SDK 和 App 回归覆盖权限变化、编辑/删除/撤回、重复投影、旧索引恢复、分页和迟到响应。
Desktop 客户端与 Android 模拟器已验证多词全文查询、文档打开与跨页加载，文件来源/MIME/范围筛选、
TXT 完整下载预览及聊天附件定位；Android 返回预览前页面保留筛选，文档撤权后结果自动清空、恢复权限后自动重现，
其他仍有权限的账号结果保持可用。物理 Android 设备的搜索场景尚未覆盖。
实现边界见[内容与资产搜索](../06-server/search-and-admin.md#7-内容与资产搜索)，复验入口见[场景目录](../09-testing/scenario-catalog.md#f-会话与客户端体验)。

</details>

<details>
<summary>输入中状态：实现边界与验收入口</summary>

TYPING 以 `eventId = 0` 瞬时直发，不持久化、不补发且过载可丢；Desktop 活动窗口与 Android resumed 聊天页只在真实正文变化时尝试发送，成功准入后执行 2 秒 leading throttle。接收展示每次续期 3 秒，并在断线、对方新消息进入当前投影、离开或销毁聊天时清除；保留的 ViewModel 不会在返回会话时复活旧信号。双向输入、节流、后台停发、TTL、离页清理和重连不回放由[客户端场景](../09-testing/scenario-catalog.md#f-会话与客户端体验)定义验收范围

</details>

<details>
<summary>客户端资源换代与首帧：实现边界与验收入口</summary>

ClientSession、SQLite 与 Repository 图在 IO 构造，回到 Main 复验 owner 后发布。Desktop 媒体扫描和
平台资源图采用同一候选交接，加载、失败和就绪复用一个原生窗口。未绑定候选失败时销毁 UI、保留草稿并
封存 writer，绑定登记与关闭互斥；Android 通知资源组装失败不发布半成品，也不关闭进程共享 writer。

Android 文件卡的 `cacheDir` 读取、命中探测与缓存文件打开，以及视频/文本预览缓存根均在 Main 外执行。
文件卡以 `Checking → Idle / Done`、单飞探测和代际/关闭复验承载缓存状态，自动下载等待明确 miss。
草稿恢复记住持久层明确返回的空 owner，避免空工作区重复删除 SharedPreferences。
所有权图与失败恢复见[客户端与 SDK](../03-architecture/client-and-sdk.md)和[Desktop](../05-clients/desktop.md)。

</details>

<details>
<summary>本地缓存生命周期：实现边界与验收入口</summary>

媒体身份目录按 dataset/deployment/uid 隔离，Desktop `media_e2` 根和 Android app cache 根对合法 namespace
共享 512 MiB/4,096 条目 LRU。可回拉权威消息按 chat 保留最新 2,048 条/64 MiB，不删 `serverSeq=0`、
稳定失败或非 SUCCESS outgoing 引用行。Android 在首次、非正常关闭和每 7 天执行完整性检查，Desktop GUI
每次打开先完整检查；损坏时隔离精确账号 namespace，并为可回拉投影创建替代库。headless JVM 保留含可靠
inbox/outbox 的原库并明确失败。

LocalCache 在 gate 排空后的 clean close 只做一次非阻塞 `PRAGMA wal_checkpoint(PASSIVE)`，异常不泄漏 driver，
保持 fatal/close failure 优先级。小版本用 SQLDelight 迁移保留资料；安装大版本由启动 owner 持久记录并
执行重置，低版本拒绝降级打开。仍缺旧 namespace 保留/回收策略、离线 compaction，以及隔离库显式诊断、
恢复或放弃工具。测试入口见[LocalCache clean-close](../09-testing/local-tests.md#localcache-clean-close-checkpoint)。

</details>


## 服务端与运维

| 能力 | 状态 | 当前边界 |
| --- | --- | --- |
| TCP 长连接、RPC、事件同步 | 可用 | 持久事件保留窗口、checkpoint + tail、重连与客户端投影已接通；普通事件依赖分步幂等重放。细节见下方同名说明。 |
| Document 权限矩阵 | 可用 | Document 域内使用 typed role/capability 矩阵，最终裁决位于服务端当前读快照或写事务；当前没有第二个同构资产域，因此不维护通用授权内核或跨域 ACL 存储 |
| PostgreSQL、RocksDB、Lucene 组合存储 | 可用 | 权威消息、关系/事件和搜索投影职责分开，跨存储有持久恢复路径。见[持久化](../06-server/persistence.md)。细节见下方同名说明。 |
| 单实例容量基线 | 部分 | 连接、消息、搜索与大小附件具备可重复容量验收入口；固定参考硬件、慢数据库、磁盘压力、长期 soak 和发布级 SLO 未完成 |
| 后台维护与健康检查 | 可用 | `MaintenanceRuntime` 统一持有定期维护任务；关键任务意外停止使 `/health` 返回 `DOWN` / HTTP 503。任务归属与诊断见[可观测性](../07-operations/observability.md) |
| 管理后台 | 可用 | 用户、群、消息、日志、组织与机器人管理；单实例管理员凭据持久化、主动轮换、会话吊销/服务端退出和有界必要审计，支持显式受控恢复。真实 PostgreSQL/HTTP 回归覆盖轮换、重启、拒绝分类和审计失败；浏览器验证登录、凭据表单、会话与审计展示、退出，未覆盖浏览器内密码轮换。没有多管理员角色或长期审计归档，见[搜索与管理](../06-server/search-and-admin.md#5-管理后台)。 |
| 管理台构建输入 | 可用 | Git 只保留管理台源码、依赖清单与锁文件；node_modules/dist 不参与源码跟踪，Server 在隔离 build 工作区构建。`checkArchitecture` 拒绝重新跟踪产物。 |
| 统一发行工具链 | 部分 | 根版本、人工说明和冻结协议快照进入 Gradle 校验；`release` 密封 Android、三平台 Desktop 站点与 Server ZIP，可向本地、SFTP 站点和 GitHub 交付；服务器仍人工部署。私有首次分发可用 `private-first`；后续手动 `snapshot` 不改根展示版本或构建号，Android 保持 code 手动覆盖，Desktop revision 从完整 first-parent 历史自动计算，仅向 local/site 交付并复用冻结契约。内测收据按类型、版本和 Desktop revision 保留，同展示版本的后续 snapshot 须从已分发源码的后代构建，禁止相同修订换包或倒退。CI 要求展示版本与根构建号一起推进才正式发行，不自动刷 snapshot。Windows 使用同一任务，完整跨平台安装与更新验收仍需按参与平台执行，见[发行流程](../07-operations/releasing.md)。 |
| 公版与私有客户端共存 | 可用 | `DeploymentConfig.client` 统一生成 Android 安装 ID、Desktop 安装身份与名称；主仓库默认公版，私有独立 clone 使用 Git 忽略的完整 local 配置目录，随 `buildSrc` 编译。双端数据、登录与主题独立，Desktop 单实例锁跟随数据目录。私有站点提供 Android APK，Desktop Conveyor 更新源从各自 `serverUrl` 推导；Android 暂无自动下载安装。默认公版保留原身份与目录。层级 `DeploymentDsl` 构造 `DeploymentConfig` 供双端 Gradle 使用；目标平台安装、通知跳转与连续升级仍须按发行实际验收，见[客户端发行身份](../07-operations/configuration.md#客户端发行身份)。 |
| 客户端结构化遥测与定向诊断 | 可用 | 有界客户端遥测、设备策略与定向诊断已接通；诊断数据不作为消息可靠事实。细节见下方同名说明。 |
| 私有化部署参数 | 可用 | Kotlin 配置统一生成客户端、部署和验收坐标；HTTP 与 TCP TLS 独立，支持 IP + HTTP + 自签 TCP 证书。Gradle 生成并复用证书，客户端使用专用证书信任并验证 SAN；普通升级保留数据、证书与私钥。配置与 TLS 测试入口为 `TcpTlsCertificatesTest`、`TlsDeploymentPreflightTest` 与 `ClientTransportTlsTest`。具体见[传输配置边界](../07-operations/configuration.md#传输配置边界)；目标实例和同批发行制品仍须实际验收，证书及其他 secret 轮换归 REL-03，迁移、备份与完整发行验收仍按发布基线执行。 |
| 数据库迁移、备份与恢复 | 部分 | 已有 PostgreSQL 顺序迁移、客户端 minor 迁移及部署 epoch/dataset 预检；完整备份、跨存储恢复与演练流程仍需闭合，见[部署与升级](../07-operations/deployment.md)。 |
| 高可用与水平扩展 | 边界外 | 当前明确以单实例私有化部署为边界；只有容量、可用性和运维指标满足进入条件后才立多节点 ADR，不在当前执行队列 |

<details>
<summary>TCP 长连接、RPC、事件同步：实现边界与验收入口</summary>

当前协议使用 connection-bound `SyncRpc` checkpoint + tail；`sync_events` 默认保留 30 天，只删已完成
进程内推送尝试且过期的连续前缀，lease/gate 保护 replay/checkpoint cursor，delete 与 `compactedThrough`
原子提交。超长离线恢复当前权威投影并拉 tail，不补已压缩的历史回调。
加速保留期与进程重启的核对方法见[同步事件保留验收](../09-testing/deployment-acceptance.md#同步事件保留的加速验收)。

</details>

<details>
<summary>PostgreSQL、RocksDB、Lucene 组合存储：实现边界与验收入口</summary>

MessageStore 原子拥有 chat 消息序号与权威消息，PostgreSQL 保存连续派生水位/Conversation/事件、完整用户头像四元组、User revision 及 Document move/rename 有限收据，FileStore RocksDB 通过 `uploads` CF 与对象 ownership metadata 持久上传 attempt 和完整收据，Lucene 在启动时按有界权威 cursor 做全量字段审计并可用 side 目录原子重建；满足当前单实例测试部署，生产迁移和通用运维工具尚不完整

</details>

<details>
<summary>单实例容量基线：实现边界与验收入口</summary>

显式 Gradle 任务覆盖连接 ramp/稳态/目标重连、消息稳态/突发与背压恢复、首屏消息/用户搜索，
以及小附件和大于 32 MiB 的文件系统层上传下载。报告包含构建和实例身份、负载参数、延迟与资源用量，
并核对消息身份/序号、附件 descriptor/哈希、重复请求和清理后的恢复结果。

这些入口提供可重复量测方法，不代表万级在线或发布级 SLO 承诺；仍缺后台维护、长期 soak、慢 PostgreSQL、
磁盘压力和固定参考硬件。参数与断言见[部署验收](../09-testing/deployment-acceptance.md#消息容量基线入口)，
剩余工作见[REL-07](roadmap.md#rel-07--单实例容量slo-与过载恢复门禁)。

</details>

<details>
<summary>客户端结构化遥测与定向诊断：实现边界与验收入口</summary>

7 日事件直接进入可丢失的本机 Lucene；PostgreSQL 只保存设备画像、策略和审计，按 uid/deviceId/phone 定向开启最多 24 小时诊断；办公 `ACTION` 在 `DIAGNOSTIC` 准入时按真实业务事实结束。事件/字节/批次和客户端 registry 均有硬边界；跨旧 namespace 回收只支持经验收的本地持久文件系统，并要求安全目录句柄、稳定 file key 和目录 force。受支持的 Windows 本地 profile 仅维护当前身份；网络盘、FUSE 和语义未知 provider 整体不受支持，不承诺其保留或持久化语义。定向诊断连接由服务端签发五字段上下文，客户端事件在创建时冻结并上传；管理端以精确 event record id、Bearer 固化的 uid/deviceId 和五字段联查同代 `Recorder` 轨迹，重连、过期、超额和启停均失败关闭且不反压 IM。客户端回传的上下文只是非权威关联提示，不是事件真实性或因果证据

</details>


## 自动化与开放能力

| 能力 | 状态 | 当前边界 |
| --- | --- | --- |
| ImBot 无头 SDK | 可用 | 与客户端共享协议、仓储和附件校验 |
| 受控通知机器人 | 可用 | 群内创建、不可密码登录的服务身份、客户端生成并持久恢复的一次性凭据、服务端原子幂等管理收据、[群绑定入站 URL](../05-clients/notification-bots.md)、显式群授权及可选幂等 Markdown HTTP 发送 |
| `tt-agent` / `tt-cli` | 可用 | 本地 REST 守护进程与命令行已覆盖核心收发和联系人/群组操作；安全边界见[无头客户端](../05-clients/headless.md) |
| MCP 适配 | 部分 | 基础工具映射存在；权限隔离、审计、部署体验和稳定版本尚未产品化 |
| 出站 Webhook 与通用应用平台 | 边界外 | 当前只有受控通知机器人的入站发送 URL 与进程内限速；第二个命名外部应用提出安装、撤销或出站订阅需求前，不预建应用注册表、跨应用配额或回调平台 |

## 办公协作

| 能力 | 状态 | 当前边界 |
| --- | --- | --- |
| 群共享文件空间 | 部分 | 目录、版本、权限、配额、五命令 outbox/receipt、持久变更投影和文件名搜索已落地；历史/收据治理、离职资产接入未完成。细节见下方同名说明。 |
| 企业文档 | 部分 | 多空间、权限、树、正文/修订、离线投影、可靠移动、持久变更事件、文档级评论与全文搜索已实现；图形化资产交接仍缺。Desktop 客户端与 Android 模拟器已验证文档创建、搜索和打开；细节见下方同名说明。 |
| 文档协作更新 | 可用 | `DOCUMENT_CHANGED` 与写入同事务，SDK 先失效再推进游标；工作台按空间刷新有界驻留内容并保留脏草稿。权限撤销、reset、重启与漏提示由 PostgreSQL/SQLite/App 回归覆盖；Android 模拟器保存后，Desktop 脏草稿保留、远端修订提示、409 双选展示及采用服务器版本已验证。不提供 CRDT 或全空间预取。 |
| 文档评论 | 可用 | 同文档回复、作者编辑、作者/空间管理员删除、独立修订与墓碑；已提交意图进入持久队列，403/409 保留到显式处理，普通关闭保留缓存。未提交输入只在当前工作台会话保留；协议、真实 PostgreSQL、SQLite 恢复已覆盖；Desktop 客户端与 Android 模拟器验证创建、同文档回复、编辑、删除墓碑的双端自动同步；服务与 Desktop 重启后，离线评论保留原身份和正文，恢复连接自动补发、清理 pending 并同步到 Android 模拟器，服务端同 ID 只保存一条。物理真机评论未覆盖；场景见[验收目录](../09-testing/scenario-catalog.md)。 |
| 类型化办公对象引用 | 可用 | 消息可引用 Document 与群文件；发送与打开按服务端当前权限裁决，冻结预览不等于对象授权。细节见下方同名说明。 |
| 待办与任务 | 计划 | 尚无任务责任人、状态、截止时间、提醒和消息引用领域 |
| 日历与会议 | 计划 | 尚无日历事件、参会人、时区、重复规则、会议状态和提醒领域 |

<details>
<summary>群共享文件空间：实现边界与验收入口</summary>

五类命令精确重放不会追加新的 GROUP_FILE_CHANGED；rename/delete 只确认原 Unit 收据，条目删除或成员退出后仍可确认原命令。创建与追加版本仍需读取当前条目，完整撤权/删除后的确认与收据回收语义继续归 CONTENT-03。

独立目录、不可变版本、成员 ACL、乐观锁、1 GiB 默认字节配额，以及每群 10,000 个活动条目、每 parent 512 个直接子条目、每文件 128 个活动版本的事务级硬边界、O(1) 容量台账、五类变更的稳定命令收据、客户端跨进程有界 mutation outbox、基础审计、引用安全回收和双端入口已完成；rename/delete 的丢响应恢复会复用精确 commandId，五类命令的客户端可重试失败均显示 PENDING，发布/追加版本的高价值 ACTION 另记录 QUEUED，后台 ACK 刷新相关目录、版本或面包屑，后台 REJECTED 给出明确提示并刷新当前页。文件名搜索使用当前群权限和活动文件条目；历史行/字节总预算和管理查询尚未完成。`GROUP_FILE_CHANGED` 驱动行级实时投影与离线 stale 展示，双端完整断网恢复按 REL-05 的交付范围验收

</details>

<details>
<summary>企业文档：实现边界与验收入口</summary>

两级资产首页/空间工作区、多空间 ACL、文档可同时承载正文与子文档的紧凑懒加载文档树、Markdown 无损块级编辑、不可变修订、持久化小字段与游标分页历史、干净文档的懒加载“移动到…”、409 保存冲突双选择恢复、按标签/编辑世代竞态防护、历史恢复、Desktop 跨空间多标签与独立窗口、Android 单文档前台已完成。RPC 19 以一条有界递归查询返回最多 129 个 root→target 节点；SDK 持久化 partial spine 而不伪造完整分支，客户端的普通打开、跨空间标签、草稿恢复、关闭替补和工作区刷新统一使用缓存 spine + 至多一次远端 spine。当前代码已把同级顺序收敛为不可变 `(createdAt, nodeId)`，无手动 rank/CRDT；method 11 为 content-only，move/rename 由 method 12 的稳定 operationId + issuedAt 独占。服务端 7 天、每 actor 1,024 条有限 receipt 与本地缓存当前 epoch 的每节点单槽/最多 256 条 durable outbox 已落地，精确重放空投影 ACK 会在当前正文或 path spine 收敛后才清命令；协议、服务端、SDK 和持久化确定性测试已覆盖 wire、并发、过期、跨重启和改名不换序。离线 move/rename 先持久排队，进程重启后恢复，并在网络恢复后按原 identity 收敛。服务端/协议/SDK 已将不可变 createdBy、用户/组织 owner principal、唯一人类 steward 与 grant 分离，提供 RPC 18 的 custodyRevision CAS 与不可变收据；管理 HTTP 控制面可盘点并原子交接已 ban steward 的全部 DocumentSpace。LocalCache 只持久化有界空间/首页/分支/partial spine/干净正文投影；空间 403、根分支 404、完整终页 omission 和 `effectiveRole = NONE` 清理干净投影，网络失败保留缓存，脏草稿转为可强杀恢复的本地孤儿。持久 `DOCUMENT_CHANGED` 使驻留内容按空间自动对账；评论使用独立 revision 与 SQLite 待发送队列，撤权清理评论投影而保留未确认意图。当前标题与正文支持按空间限定的全文搜索；其余真实缺口是图形化交接和 GroupFile 离职资产接入。客户端保持有界工作集，不做全空间预取；正文并发边界仍是 expectedRevision + 409，节点级 ACL 与 CRDT 暂不进入执行队列

</details>

<details>
<summary>类型化办公对象引用：实现边界与验收入口</summary>

消息以 `OFFICE_REF(17)` 引用指向 Document 与群共享文件：MessageBody 只保存引用与服务端发送时重建的预览快照（title/subtitle 权威覆盖客户端声明），不承载权威内容。发送时 `OfficeRefResolver` 直接调用办公领域读入口，校验对象存在与发送者读权限，断链/无权/删除/归档在 ACK 前拒绝；打开时经 `DocumentRpc.getDocument` / `GroupFileRpc.getEntry(8)` 重校验当前权限，撤权/删除后冻结快照仍可读并给"内容不可访问或已被删除"降级；转发只复制冻结快照不重建不扩权。双端附件面板提供"文档"（最近文档选择器）与"群文件"（仅群聊，当前群根目录）入口，并支持引用卡片渲染与点击导航

</details>


办公引用发送与普通聊天共用持久发送队列，支持服务不可用时本地排队、进程重启恢复与服务恢复后的唯一发送。
候选对应文档在发送前被删除时保留明确失败气泡，后续正常消息仍可发送；候选列表本身仍需在线加载。

## 明确的架构边界

- 附件不使用第三方对象存储 URL 作为消息事实；外部 HTTP URL 只用于 SDK 接入展示或部署地址解析。
- 当前不是端到端加密系统。服务端需要解析消息元数据以完成存储、搜索、审核和同步；E2E 加密不是现阶段“漏做的按钮”。
- 当前按单实例私有化部署设计。多节点扩展必须先补连接路由、序号分配、事件日志和文件共享方案，不能仅增加副本数。
- 项目对外仍不保证版本兼容，内部从开发者预览开始有条件维护兼容；普通升级保留已有资料，同一协议
  major 的已发行契约冻结。破坏性数据重建需要独立说明范围与影响，不能以开发者预览为由默认清空内测资料。
