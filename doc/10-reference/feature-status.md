# 功能状态

TeamTalk 当前处于发布前开发阶段。本文回答“现在究竟能做什么”，避免把规划中的 UI、已有数据结构和真正完成的业务闭环混为一谈。

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
| 数字协议版本与兼容窗口 | 可用 | 协议 0.0 独立于统一展示版本 0.0.0；TCP 先协商再 AUTH，服务端最低版本可提高；旧客户端横幅提示或强制拒绝工作区 |
| 协议生命周期与构建检查 | 可用 | since/removed 注解、生成版本窗和提交的 wire 签名清单；同 major 新增编号、退役留墓碑。手写 codec 语义仍须审阅和 golden 检查 |
| 数据升级机制 | 部分 | 客户端 minor 走 SQLDelight 事务迁移，major 在启动前清本安装数据并重新登录；PostgreSQL 已有从 0 起的顺序迁移台账，现有资料保留；跨存储迁移与完整备份恢复仍需逐项补齐 |

项目仍不保证兼容，未来可能有破坏性变更；准确规则与尚须业务适配的边界见[版本机制](../04-protocol/versioning.md)。

## 核心业务

| 领域 | 状态 | 当前边界 |
| --- | --- | --- |
| 注册、登录、凭证恢复、退出 | 可用 | 双端提交有等待反馈，失败可原地重试，Desktop 注册失败保留表单；支持多设备 token；持久凭据可离线恢复本地会话，认证终态失败停止自动重连。Desktop 的 connect completion 固定跨一轮 event-loop 处理，避免同步完成在认证 lease 安装前重入；确定性回归、shared 全测和真实 Desktop 登录已通过 |
| 用户资料 | 可用 | 姓名、手机号等读写和 USER_UPDATED 已接通 |
| 好友申请、接受、删除、备注、黑名单 | 可用 | 服务端是关系与权限事实源；每人发出/收到 pending 各有 100 条事务硬边界，待处理视图完整；接受/拒绝使用本地持久 operationId/issuedAt 与服务端原子结果收据覆盖 7 天内丢响应重试，收据每 actor 最多 1,024 条且不淘汰未过期身份；双向终态历史至多保留最近 1,000 条并支持游标分页，两人 pending 可精确查询 |
| 私聊、群聊和群成员管理 | 可用 | 建群和邀请链接创建使用客户端稳定 operationId 与服务端持久收据覆盖丢响应重试；GUI 在 RPC 前按 deployment + uid 持久化冻结命令，可跨进程恢复；邀请回执 7 天内每创建者最多 256 条、不淘汰未过期身份且重放重新校验当前 admin，包含角色、禁言、邀请链接、转让群主 |
| 组织架构与成员归属 | 可用 | 单组织树、多部门归属与受管群已接通；目录按 revision 分页收敛，支持离线旧投影，权限仍由服务端裁决。 细节见下方同名说明。 |
| 受管部门群 | 可用 | 节点子树是成员事实源；组织变更和启动恢复自动收敛，拒绝手工成员修改 |
| 会话列表、草稿、置顶、静音、已读 | 可用 | 会话设置多设备同步；普通草稿与已读支持本地 outbox 和恢复。富资产草稿仍有独立边界。 细节见下方同名说明。 |
| 文本消息 | 可用 | 统一使用 `RICH_TEXT` / Markdown，只有一条 wire 分支 |
| 消息提交身份与崩溃边界 | 可用 | 复合消息身份幂等；RocksDB 原子记录消息与待投影操作，Lucene/PG 投影补齐后成功 ACK。见[数据与同步](../03-architecture/data-and-sync.md)。 细节见下方同名说明。 |
| 编辑、撤回、回复、转发 | 可用 | 消息修订与引用链路已接通；回复作者正文支持与普通富文本相同的 canonical 图片/文件 sidecar、上传屏障、认证渲染和历史重放 |
| 消息搜索与结果定位 | 可用 | Desktop 与 Android 都以 `chatId + serverSeq` 消费结果；目标不驻留时只加载一页有界目标历史，按精确 seq 滚动并短时高亮；无权限、撤回或不存在目标以同一安全状态降级 |
| 断线队列、重连和离线事件补偿 | 可用 | 发送与多类可靠命令可持久恢复；checkpoint 加 tail 恢复当前投影，保留本地可靠事实，不承诺永久历史回调。 细节见下方同名说明。 |

<details>
<summary>组织架构与成员归属：实现边界与验证范围</summary>

单组织树、用户多部门/唯一主部门及节点/关系硬容量已落地；终端读固定走 `OrganizationRpc` 二进制 revision-fenced 分页，管理写走独立 HTTP 控制面。单位与直属成员投影都持久 known + revision，并由单调 requiredRevision 区分冷缓存、权威空与 stale nonempty；旧行离线可见但不作权限权威。提交后 `ORGANIZATION_CHANGED(61)` 只向 SYNC_READY 连接瞬时提示，认证恢复的全量 RPC 负责断线兜底；节点持有活动文档空间时归档失败，必须先显式交接资产

</details>

<details>
<summary>会话列表、草稿、置顶、静音、已读：实现边界与验证范围</summary>

Desktop 右键与 Android 长按会话菜单均可切换免打扰，真实双端已验证同账号状态实时互见；普通 Markdown 草稿与已读先落本地持久 outbox，断网立即生效，认证恢复后跨设备镜像；同一 uid 两个设备加一个对端的远端矩阵已覆盖并发编辑/撤回、已读高低水位、草稿、置顶、静音、只暂停一个客户端后的重放，以及精确重启 TeamTalk 服务后的自动重连，最终 Message、Conversation、本地历史和权威状态一致且认证只增加一次。含内部资产 URI 的聊天草稿因无 sidecar 草稿契约而只保留当前会话，不向 SQLite/跨设备流写入裸 URI；权威全量投影以 16 条有界 keyset 页收齐后原子替换，其余服务端事实由事件收敛

</details>

<details>
<summary>消息提交身份与崩溃边界：实现边界与验证范围</summary>

MESSAGE、MESSAGE_ACK、服务端、SQLite 与 Lucene 统一使用 `chatId + clientMsgId` 复合身份；MessageStore 把 chat 高水位、消息、幂等索引、revision、附件索引与 CREATE outbox 放入同一 sync-WAL 批，PG `Chat.maxSeq` 只在 receipt/Conversation/事件事务中连续推进。批前失败不消耗 seq，PG 回滚、排队命令竞态和并发无空洞已有确定性测试；发布制品自动化分别在 Rocks 权威批后/任何投影前、PG 投影提交后/outbox 删除前、outbox 删除返回后/网络 ACK 前精确 `SIGKILL` TeamTalk 主进程。三个窗口都验证首发只失去 transport、不产生假 ACK，原双端各只重新认证一次；重试前历史、搜索、消息事件、本地缓存和 Conversation 已唯一收敛，同 identity 重试返回原 seq，下一消息序号连续，markRead 后未读归零

</details>

<details>
<summary>断线队列、重连和离线事件补偿：实现边界与验证范围</summary>

本地缓存当前 epoch 持久化有界 outgoing/命令 outbox、草稿/已读、组织与文档投影、完整 User/个人会话头像描述符、personal peer uid、User/peer revision 及 Reply 资产 sidecar；群文件可靠命令 outbox 已扩展到 rename/delete，文档 move/rename durable outbox 已落地并移除文档节点 position。已建立本地关系或精确观察的用户投影与 checkpoint 身份元组按 revision CAS，迟到 RPC、durable/transient 事件或组织嵌入快照不能回退姓名/头像。尚无关系的瞬时 User 提示只用 256 项 session LRU 桥接首次加载，溢出可丢，后续关系 RPC、资料查询或重连刷新负责恢复。当前协议在游标低于服务端保留 floor 时，继续使用 `SyncRpc` 收齐 User/Contact/Chat/Conversation checkpoint，按 expected dataset + cursor CAS 一次 SQLite 安装并从 `baseEventId` 拉 tail；各 section 页不共享跨 RPC MVCC snapshot。Bot inbox 不回收未 ACK 行，但 at-least-once 只覆盖仍在服务端保留窗内或已进本地 inbox 的事件；超长离线只恢复当前投影/可查消息历史，不补已压缩的历史 delivery/编辑/撤回回调

</details>


## 附件与富内容

| 能力 | 状态 | 当前边界 |
| --- | --- | --- |
| TeamTalk 文件上传与下载 | 可用 | 带稳定 identity 的可重复流式上传、长度校验与收据恢复已实现；下载鉴权和缓存边界见[文件存储](../06-server/file-storage.md)。 细节见下方同名说明。 |
| 附件存储治理 | 部分 | FileStore 已有全局/上传者硬配额、未引用租约和安全 GC；尚缺账号/组织维度可查询、可审计的配额、归属、保留期及账号生命周期策略 |
| 附件发送与读取安全校验 | 可用 | 成功 ACK 前校验主文件、缩略图和元数据；上传者只对未业务绑定 staging 对象有预览/提交旁路，绑定后下载必须通过当前消息/群文件/文档 ACL；文档新资产只接受本人 staging 或同一文档历史已知资产，不接受任意已授权业务资产重绑；引用提交/撤销与物理回收通过固定容量的单实例分片跨存储围栏及可恢复 tombstone 收敛 |
| 普通文件下载策略 | 可用 | 小文件静默下载，大文件点击后下载，气泡展示传输状态 |
| 图片、语音、视频与缩略图 | 部分 | 完整下载后在有界账号缓存播放，双端离线缓存已有验证；同批正式发布制品、安装包和部分平台门禁仍未完成。 细节见下方同名说明。 |
| Markdown 富文本、mention、代码块 | 可用 | 输入和展示以 `RICH_TEXT` 为主；跨端渲染需持续回归 |
| Markdown 上下文图片/文件 | 部分 | 聊天/回复/文档共用 sidecar 与上传屏障，同进程取消、重试和双端导入已落地；聊天富资产跨进程 spool/outbox 未完成。 细节见下方同名说明。 |
| 交互卡片 | 部分 | 发送、协议和基础渲染存在，动作回调、权限与业务路由尚未产品化 |
| 表情回应 | 可用 | 双端回应操作、行级事件与聚合计数已实现；范围快照、实时增量和恢复共享本地回应投影。 细节见下方同名说明。 |

<details>
<summary>TeamTalk 文件上传与下载：实现边界与验证范围</summary>

HTTP 端点与 IM 服务同一部署；消息保存相对路径；两端按附件声明大小和 512 MiB 绝对上限做精确流式下载校验。上传使用 canonical `uploadId` 和有限期 `issuedAt`，在读取正文前按 `Content-Length` 预留 uid/global 字节与对象槽；FileStore 持久 `STARTED` / `COMPLETED` 收据，使同 identity 在响应丢失或服务重启后返回原 descriptor，改写 payload 返回 `409`。未引用上传默认 7 天后经有界扫描回收。大于 32 MiB 的文件系统层已用产品 SDK、另一成员流式下载、服务重启、精确重放和再次下载形成真实门禁。Desktop/Android 当前应用代码也已通过冷缓存完整下载后才创建播放器、目标 TeamTalk 服务停止后的双端离线缓存命中及无 partial 残留门禁；未经重建的同批正式 release artifacts 复验仍归 REL-05，GUI 跨进程附件 outbox 仍属 CLIENT-04

</details>

<details>
<summary>图片、语音、视频与缩略图：实现边界与验证范围</summary>

Desktop 与 Android 共享消息模型；所有媒体先经认证下载、精确大小校验和原子发布进入账号/部署/dataset 隔离目录，同一物理媒体根的所有目录共享有界 LRU、字节/条目预留和消费者租约；缓存跨页面复用且命中时可离线使用，旧账号不再各自占有一份配额。视频播放器只读取有租约的本地文件，不以服务器 URL 或 loopback Range 在线拉流。2026-09-01 当前应用代码已用 Desktop 5,286,805 字节与小米真机 23,303,457 字节视频证明下载进度阶段无播放器、完整发布后 seek/暂停/全屏可用，并在只停止目标 TeamTalk 服务、保持宿主机和手机网络不变时完成双端离线缓存命中；缓存目录无 `.part` / `.partial`。Android 视频、语音与缩略图已有原子租约。Intel macOS（x86_64）本地媒体原生覆盖也已通过 8 轮视频、32 次创建后立即销毁和 4 轮纯音频生命周期测试；真实 Desktop 的横屏/竖屏视频上传、播放/暂停/seek、全屏尺寸链和 12 次交替切换始终单 FD，各 4 次开关后归零。arm64 目前仅完成构建与结构校验，尚未在 Apple Silicon 实机重复该门禁；首个正式签名、公证发布物也尚未完成

</details>

<details>
<summary>Markdown 上下文图片/文件：实现边界与验证范围</summary>

协议已引入 scope-local URI + canonical sidecar，覆盖普通消息、回复作者正文和文档；服务端统一校验、索引引用并提供认证下载，客户端复用同一上传屏障、渲染与画廊链路。聊天 Desktop 支持 picker/drop/binary paste，Android 支持 picker/clipboard；文档 Desktop 支持 picker/drop/binary paste，Android 支持 picker、显式粘贴和物理键盘粘贴。Chat/Document 的有界待处理列表支持当前进程取消、失败重试和移除，稳定保留 `jobId`、`assetId` 与上传 identity；迟到结果不会复活已删除引用。既有双端真机门禁覆盖聊天/文档导入、取消、失败重试、保存/发送和重进，Android 另覆盖 64 MiB 上传时的凭据轮换；回复图片+文件已完成 Android→Desktop 与 Desktop→Android 双向发送、历史重进、画廊/文件预览和本地缓存重复命中验收，但该轮未做断网测试。2026-09-02 聊天和文档可视编辑器均已在 Desktop 与小米 Android 通过中间选区连续插入图片+文件、保持可视模式、canonical 源码顺序、提交及历史重进门禁；文档另覆盖 READY/重绑/预览切页不丢尾字或引用。小米 MIUI 的“picker 打开期间 Activity 重建”框架崩溃仍需另一设备补证。尚不支持跨进程/跨设备富资产聊天草稿、持久本地源文件与有界 spool/outbox、断网续传和 Android 文档拖放

</details>

<details>
<summary>表情回应：实现边界与验证范围</summary>

范围读取以整个请求区间替换，空响应会清理旧回应；飞行期间的 delta 会使迟到快照失效，checkpoint 原子清理可回拉回应投影，重新认证后补当前窗口。边界见[数据与同步](../03-architecture/data-and-sync.md#表情回应完整区间快照与实时增量)。

服务端 `message_reactions` 行级权威表提供 row-keyed 幂等增删、成员与撤回校验、每用户每消息 12 个不同 emoji 上限和聚合计数；`MESSAGE_REACTION` 事件实时/离线补发行级 delta，`listReactions` 返回权威快照，消息撤回在同一事务清空回应。双端气泡 chips（emoji + 计数 + 本人高亮）点击切换；长按/右键菜单快捷栏与 chips 行尾的"＋"可展开完整表情选择器（与输入区表情面板共用 192 个候选），选择器为"确保已添加"语义，取消走 chips 切换；Desktop 与小米 Android 已完成实时互见、取消收敛、重复点击幂等、picker 选任意 emoji 和飞行模式断网恢复收敛验收

</details>


## 客户端体验

| 能力 | 状态 | 当前边界 |
| --- | --- | --- |
| Desktop 应用壳与三栏布局 | 可用 | 应用级标题栏、全局搜索、聊天主体和临时右侧检查器已分层 |
| 用户资料 | 可用 | Desktop 使用模态弹窗；Android 使用页面导航 |
| 群设置 | 可用 | Desktop 从聊天栏打开右侧抽屉，点击外部或关闭按钮收回 |
| 富文本输入 | 可用 | 文档支持标题、撤销重做、链接、列表与缩进，并以可视块编辑引用、代码围栏和 GFM 表格；未知扩展仅局部保留源码；聊天与文档的图片/文件都可在当前可视选区后连续插入且不切换模式。文档顶层正文按字符选区精确插入，引用、代码、表格等结构块按相邻块边界插入 |
| 明暗主题 | 可用 | 共享令牌，平台持久化主题选择 |
| 真实头像 | 可用 | 用户头像使用强类型 FileStore `Attachment`；Desktop 与 Android 通过各自系统 picker 自动居中裁成不超过 512×512 的方形 PNG，经认证媒体缓存展示，支持上传、替换、清除、进度和失败回落。同批制品已完成双账号双向修改与跨端展示、替换/清除后的缓存失效和占位回落、Android 应用级断网时的本地缓存命中；当前头像可认证下载，旧头像在替换或清除后不再凭头像引用授权。群头像不在当前范围内 |
| 在线状态 | 可用 | 在线状态的代际有序契约已引入，服务端原子 epoch/revision、认证/联系人/换代快照刷新、会话内乱序 reducer 与双端好友头像 ONLINE 圆点均已实现。同一制品的 Desktop 与 Android 已通过双向真实互见、单端进程范围断网与重连验收；断线立即回到 UNKNOWN，重连从新快照恢复，不持久化或展示旧 ONLINE，离线页面仍保留可用的本地联系人 |
| 输入中状态 | 可用 | 只反映当前前台输入，瞬时发送与超时清理已接通；不写消息历史、outbox 或持久事件。 细节见下方同名说明。 |
| 全局消息与用户搜索 | 可用 | 本地会话、联系人和远程消息/用户聚合 |
| 文件搜索 | 计划 | 缺附件索引、ACL 过滤和类型筛选；UI 只保留明确占位 |
| 服务搜索 | 边界外 | 当前只有受控通知机器人入口；第二个命名外部应用出现并证明需要统一发现前，不建设应用/服务注册表或全局服务搜索 |
| 客户端资源换代与首帧 | 部分 | 已将主要媒体资源构造移出 Main，并按 owner 复验后发布；其他平台入口及完整草稿资源交接仍需逐项验证。 细节见下方同名说明。 |
| 本地缓存生命周期 | 部分 | 消息/媒体有界回收与损坏隔离已落地；离线 SQLite compaction、隔离库的显式诊断与恢复工具仍缺。 细节见下方同名说明。 |
| Android 专项体验 | 部分 | 核心业务可用；富资产文档二进制粘贴已通过小米真机的二进制/纯文本剪贴板、上传、保存、强停重开和预览门禁。其他剩余验收边界是系统 picker/权限/返回路径、最低无障碍语义和发布制品真机 P0 矩阵 |
| Android 后台 Push 与系统通知 | 部分 | 已接进程存活且系统允许后台联网时的新未读通知、Android 13 权限申请及通知点击回到会话；前台/静音/历史同步不提醒，退出清理。尚无设备 endpoint、国产 Provider、服务端 wake outbox 和后台网络受限/进程回收后的唤醒闭环，详见[Android 通知](../05-clients/android.md#消息通知的当前范围) |
| 保存的消息 | 可用 | 每用户唯一私有会话，支持消息副本、稳定命令幂等、历史/搜索及多设备同步；首次收藏前不占会话列表，有内容后按普通会话排序，支持用户主动置顶/取消。系统服务身份不等于对端用户账号。服务端入口为 SavedMessageIntegrationTest 与 RemoteAcceptanceTest 的 saved messages 场景；图形端复用 ConversationListScreen 与现有聊天页面 |

<details>
<summary>输入中状态：实现边界与验证范围</summary>

TYPING 以 `eventId = 0` 瞬时直发，不持久化、不补发且过载可丢；Desktop 活动窗口与 Android resumed 聊天页只在真实正文变化时尝试发送，成功准入后执行 2 秒 leading throttle。接收展示每次续期 3 秒，并在断线、对方新消息进入当前投影、离开或销毁聊天时清除；保留的 ViewModel 不会在返回会话时复活旧信号。同批制品已完成 Desktop↔Android 双向真实输入、2 秒节流、Desktop 失焦与 Android 后台停发、TTL、消息到达和离页清理，以及 Android 应用级断网重连不回放的交叉验收

</details>

<details>
<summary>客户端资源换代与首帧：实现边界与验证范围</summary>

ClientSession、SQLite 与 Repository 图已在 IO 构造并回到 Main 复验 owner 后发布；Desktop 媒体扫描和平台资源图已使用同一候选交接，加载/失败/就绪复用一个原生窗口，并通过 50,000 条隔离媒体大库与构造失败重试的真实 UI 门禁。Android 文件卡的 `cacheDir` 读取、命中探测与缓存文件打开，以及视频/文本预览缓存根已移出 Main；文件卡以 `Checking → Idle / Done`、单飞探测、代际/关闭复验承载缓存状态，自动下载等待明确 miss。草稿恢复记住持久层明确返回的空 owner，避免空工作区重复删除 SharedPreferences。小米真机清日志后进入聊天文件卡、已加载文档图片和冷启动文档首页均为 0 条 StrictMode 磁盘违规。尚缺双端草稿 owner 的完整资源交接，以及其他尚未逐入口量测的平台资源和磁盘工作的完整迁入

</details>

<details>
<summary>本地缓存生命周期：实现边界与验证范围</summary>

媒体身份目录按 dataset/deployment/uid 隔离，但 Desktop `media_e2` 根和 Android app cache 根已分别对所有合法 namespace 实施全局 512 MiB/4,096 条目 LRU；可回拉权威消息按 chat 保留最新 2,048 条/64 MiB，不删 `serverSeq=0`、稳定失败或非 SUCCESS outgoing 引用行。Android 在首次、非正常关闭和每 7 天执行完整性检查，Desktop GUI 每次打开均先完整检查；确认损坏时只隔离一份精确 deployment + dataset + uid 账号 namespace，并为可回拉投影创建干净替代库。headless JVM 保留含可靠 inbox/outbox 的原库并明确失败。Desktop 与小米真机已通过 SQLite 首页损坏、唯一隔离保留、静默认证及会话/消息重建的真实门禁，Android 同时命中系统原生 `SQLITE_NOTADB` callback。提交 `35a700ec` 与 `a15d3c5a` 已使 LocalCache 在 gate 排空后的 clean close 只做一次非阻塞 `PRAGMA wal_checkpoint(PASSIVE)`，checkpoint 异常不泄漏 driver 且保持既有 fatal/close failure 优先级；真实文件 pinned-reader 测试证明有界返回并可重开草稿/outbox。零号基线已移除按 epoch 自动删除旧库的入口，小版本使用 SQLDelight 迁移保留数据，安装大版本由启动 owner 持久记录并执行重置，低版本拒绝降级打开。仍缺跨旧 namespace 保留/回收策略、离线 compaction 及隔离库显式诊断、恢复/放弃工具

</details>


### 打开中的聊天草稿

2026-09-04 已修复输入框不消费后续外部草稿及清空的问题。Desktop 与小米 Android 的同账号真实 UI
已双向覆盖更新、主动清空、发送后清空、离开期间另一端清空后返回，以及回复/消息编辑上下文保留。
导航恢复不会把保留的回复或编辑正文重新发布为普通草稿；Desktop 还覆盖了输入后约 217 ms 离开、
另一端清空后返回无旧正文回写，小米通过同进程真实 Activity 重建后的回复保留和继续同步；多行
Markdown 源码也已验证跨端原文保留、导航恢复和清空。共享 UI 的局部回归只固定未加载、本机输入、
异步写入与导航恢复的易错组合，不把同步模型测试视为 Compose 发送、最后帧调用顺序或 Activity
生命周期的自动化覆盖。会话队列/镜像恢复另由 SDK 验证；富资产草稿的跨进程/跨设备恢复边界没有扩大。

## 服务端与运维

| 能力 | 状态 | 当前边界 |
| --- | --- | --- |
| TCP 长连接、RPC、事件同步 | 可用 | 持久事件保留窗口、checkpoint + tail、重连与客户端投影已接通；普通事件依赖分步幂等重放。 细节见下方同名说明。 |
| Document 权限矩阵 | 可用 | Document 域内使用 typed role/capability 矩阵，最终裁决位于服务端当前读快照或写事务；当前没有第二个同构资产域，因此不维护通用授权内核或跨域 ACL 存储 |
| PostgreSQL、RocksDB、Lucene 组合存储 | 可用 | 权威消息、关系/事件和搜索投影职责分开，跨存储有持久恢复路径。见[持久化](../06-server/persistence.md)。 细节见下方同名说明。 |
| 单实例容量基线 | 部分 | 连接、消息、搜索与大小附件已有基线；固定硬件、慢数据库、磁盘压力、长期 soak 和发布级 SLO 未完成。 细节见下方同名说明。 |
| 管理后台 | 部分 | 用户、群、消息、日志、组织架构和通知机器人可管理；认证、审计和权限模型仍是测试环境级别 |
| 管理台构建输入 | 可用 | Git 只保留管理台源码、依赖清单与锁文件；node_modules/dist 不参与源码跟踪，Server 在隔离 build 工作区构建。`checkArchitecture` 拒绝重新跟踪产物。 |
| 统一发行工具链 | 部分 | 根版本、人工说明和冻结协议快照进入 Gradle 校验；`release` 密封 Android、三平台 Desktop 站点与 Server ZIP，可向本地、SFTP 站点和 GitHub 交付；服务器仍人工部署。Windows 使用同一任务，完整跨平台安装与更新验收仍需按参与平台执行，见[发行流程](../07-operations/releasing.md)。 |
| 客户端结构化遥测与定向诊断 | 可用 | 有界客户端遥测、设备策略与定向诊断已接通；诊断数据不作为消息可靠事实。 细节见下方同名说明。 |
| 私有化部署参数 | 部分 | HTTPS + TLS/TCP 的远程部署链已接通，上传续传、配置与健康探针已有入口；可选 HTTP + 自签 TCP 的运行时、SDK 与部署工具组合尚未闭合，见[传输配置边界](../07-operations/configuration.md#传输配置边界)。迁移、备份与管理治理另列发布基线。 |
| 数据库迁移、备份与恢复 | 部分 | 已有 PostgreSQL 顺序迁移、客户端 minor 迁移及部署 epoch/dataset 预检；完整备份、跨存储恢复与演练流程仍需闭合，见[部署与升级](../07-operations/deployment.md)。 |
| 高可用与水平扩展 | 边界外 | 当前明确以单实例私有化部署为边界；只有容量、可用性和运维指标满足进入条件后才立多节点 ADR，不在当前执行队列 |

<details>
<summary>TCP 长连接、RPC、事件同步：实现边界与验证范围</summary>

当前协议使用 connection-bound `SyncRpc` checkpoint + tail；`sync_events` 默认保留 30 天，只删已完成进程内推送尝试且过期的连续前缀，lease/gate 保护 replay/checkpoint cursor，delete + `compactedThrough` 原子。真实部署双端门禁已通过：Desktop 从 cursor 12 经 floor/base 15、tail 16–18 收敛到本地 18；Android 在同一 dataset 从 cursor 28 经 floor/base 32、tail 33–35 和随后到达的已读事件 36 收敛到本地 36；compactor 已物理删行，第二次服务重启后 floor 15 仍持久。验收只把目标 uid/序列范围的 `created_at` 回拨 31 天以加速默认保留边界，不冒充真实墙钟运行 30 天；连接与认证硬边界继续生效

</details>

<details>
<summary>PostgreSQL、RocksDB、Lucene 组合存储：实现边界与验证范围</summary>

MessageStore 原子拥有 chat 消息序号与权威消息，PostgreSQL 保存连续派生水位/Conversation/事件、完整用户头像四元组、User revision 及 Document move/rename 有限收据，FileStore RocksDB 通过 `uploads` CF 与对象 ownership metadata 持久上传 attempt 和完整收据，Lucene 在启动时按有界权威 cursor 做全量字段审计并可用 side 目录原子重建；满足当前单实例测试部署，生产迁移和通用运维工具尚不完整

</details>

<details>
<summary>单实例容量基线：实现边界与验证范围</summary>

消息门禁已用 4 个独立发送用户和同一接收账号的两个设备覆盖稳态/突发提交、明确 `503` 背压、原 identity 全量恢复、ACK/通知/历史序号一致及超过单个 64 事件页的离线积压追平。独立连接门禁在未扩大服务端认证槽的同一小型部署上连续两轮通过 64/64 建连、60 秒稳态、16/16 transport-scoped 突发重连和 48/48 对照稳定；目标认证各精确 `+1`，重连最慢约 2.98 秒，FD 158→222→158。首屏搜索门禁以 4 个用户、16 个共享群和 256 条消息连续两轮完成 200/200 稳态消息搜索、100/100 消息+用户 UI burst、完整可见性/顺序/隔离矩阵和新消息投影恢复；稳态 p95 约 106–108 ms，UI 周期 p95 约 134–140 ms。小对象附件门禁再以 2 个用户和 512 KiB 对象连续两轮通过 36/36 SDK HTTP 上传、GroupFile RPC 引用、36/36 另一成员鉴权下载、逐对象 descriptor/长度/SHA-256 和 36/36 引用清理，稳定上传 identity 另有重启后精确重放与改 payload `409` 门禁。大于 32 MiB 的文件系统层门禁以 33,619,968 字节对象通过产品 SDK 上传、另一成员重启前后两次流式下载和 SHA-256、精确服务重启、两个会话各一次重连认证及同 identity 重放不增对象。五类容量门禁均产出机器可读报告并保持目标健康，独立的 Desktop/Android 本地优先媒体门禁也已通过当前应用代码验收；尚缺后台维护、长时间 soak、慢 PostgreSQL/磁盘压力、固定参考硬件和正式 SLO 阈值

</details>

<details>
<summary>客户端结构化遥测与定向诊断：实现边界与验证范围</summary>

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
| 群共享文件空间 | 部分 | 目录、版本、权限、配额、五命令 outbox/receipt 和持久变更投影已落地；历史/收据治理、搜索、离职资产接入未完成。 细节见下方同名说明。 |
| 企业文档 | 部分 | 多空间、权限、树、正文/修订、离线投影与可靠移动已实现；变更事件、评论、搜索和图形化资产交接仍缺。 细节见下方同名说明。 |
| 类型化办公对象引用 | 可用 | 消息可引用 Document 与群文件；发送与打开按服务端当前权限裁决，冻结预览不等于对象授权。 细节见下方同名说明。 |
| 待办与任务 | 计划 | 尚无任务责任人、状态、截止时间、提醒和消息引用领域 |
| 日历与会议 | 计划 | 尚无日历事件、参会人、时区、重复规则、会议状态和提醒领域 |

<details>
<summary>群共享文件空间：实现边界与验证范围</summary>

五类命令精确重放不会追加新的 GROUP_FILE_CHANGED；rename/delete 只确认原 Unit 收据，条目删除或成员退出后仍可确认原命令。创建与追加版本仍需读取当前条目，完整撤权/删除后的确认与收据回收语义继续归 CONTENT-03。

独立目录、不可变版本、成员 ACL、乐观锁、1 GiB 默认字节配额，以及每群 10,000 个活动条目、每 parent 512 个直接子条目、每文件 128 个活动版本的事务级硬边界、O(1) 容量台账、五类变更的稳定命令收据、客户端跨进程有界 mutation outbox、基础审计、引用安全回收和双端入口已完成；rename/delete 的丢响应恢复会复用精确 commandId，五类命令的客户端可重试失败均显示 PENDING，发布/追加版本的高价值 ACTION 另记录 QUEUED，后台 ACK 刷新相关目录、版本或面包屑，后台 REJECTED 给出明确提示并刷新当前页。历史行/字节总预算、文件搜索和管理查询尚未完成；实时变更事件与列表离线投影已落地（GROUP_FILE_CHANGED 行级投影、实时收敛与 stale 横幅真机验收，Document 域事件与双端完整断网恢复验收仍待 CONTENT-01 剩余段）

</details>

<details>
<summary>企业文档：实现边界与验证范围</summary>

两级资产首页/空间工作区、多空间 ACL、文档可同时承载正文与子文档的紧凑懒加载文档树、Markdown 无损块级编辑、不可变修订、持久化小字段与游标分页历史、干净文档的懒加载“移动到…”、409 保存冲突双选择恢复、按标签/编辑世代竞态防护、历史恢复、Desktop 跨空间多标签与独立窗口、Android 单文档前台已完成。RPC 19 以一条有界递归查询返回最多 129 个 root→target 节点；SDK 持久化 partial spine 而不伪造完整分支，客户端的普通打开、跨空间标签、草稿恢复、关闭替补和工作区刷新统一使用缓存 spine + 至多一次远端 spine。2026-09-02 已用真实 128 层路径在 Desktop 与小米 Android 完成在线、进程重启和只停止 TeamTalk 服务后的离线首页、正文与树定位验收。当前代码已把同级顺序收敛为不可变 `(createdAt, nodeId)`，无手动 rank/CRDT；method 11 为 content-only，move/rename 由 method 12 的稳定 operationId + issuedAt 独占。服务端 7 天、每 actor 1,024 条有限 receipt 与本地缓存当前 epoch 的每节点单槽/最多 256 条 durable outbox 已落地，精确重放空投影 ACK 会在当前正文或 path spine 收敛后才清命令；协议、服务端、SDK 和持久化确定性测试已覆盖 wire、并发、过期、跨重启和改名不换序。当前 Desktop 与 Android 开发制品还通过了稳定创建顺序、只停止 TeamTalk 服务后的离线 move/rename 持久排队、进程重启恢复、服务恢复收敛及再次离线读取；真实 UI 证明的是服务不可用窗口的跨进程重放，ACK 丢失后的 exact receipt replay 由确定性测试覆盖。服务端/协议/SDK 已将不可变 createdBy、用户/组织 owner principal、唯一人类 steward 与 grant 分离，提供 RPC 18 的 custodyRevision CAS 与不可变收据；管理 HTTP 控制面可盘点并原子交接已 ban steward 的全部 DocumentSpace。LocalCache 只持久化有界空间/首页/分支/partial spine/干净正文投影；空间 403、根分支 404、完整终页 omission 和 `effectiveRole = NONE` 清理干净投影，网络失败保留缓存，脏草稿转为可强杀恢复的本地孤儿。其余真实缺口是变更事件、评论、搜索、图形化交接和 GroupFile 离职资产接入。客户端保持有界工作集，不做全空间预取；正文并发边界仍是 expectedRevision + 409，节点级 ACL 与 CRDT 暂不进入执行队列

</details>

<details>
<summary>类型化办公对象引用：实现边界与验证范围</summary>

消息以 `OFFICE_REF(17)` 引用指向 Document 与群共享文件：MessageBody 只保存引用与服务端发送时重建的预览快照（title/subtitle 权威覆盖客户端声明），不承载权威内容。发送时 `OfficeRefResolver` 直接调用办公领域读入口，校验对象存在与发送者读权限，断链/无权/删除/归档在 ACK 前拒绝；打开时经 `DocumentRpc.getDocument` / `GroupFileRpc.getEntry(8)` 重校验当前权限，撤权/删除后冻结快照仍可读并给"内容不可访问或已被删除"降级；转发只复制冻结快照不重建不扩权。双端附件面板"文档"（最近文档选择器）与"群文件"（仅群聊，当前群根目录）入口、引用卡片渲染与点击导航已通过 Desktop 自动化与小米 Android 真机验收（发送/互见/打开/降级）

</details>


办公引用发送与普通聊天共用持久发送队列，不另走直接等待 ACK 的支路。Desktop 与 Android 已验证：
候选加载后服务不可用时本地排队、应用进程重启后恢复、服务恢复后各自唯一发送；候选对应文档在发送前
被删除时保留明确失败气泡，后续正常消息仍可发送。候选列表本身仍需在线加载，不等于已有离线引用选择器。

## 明确的架构边界

- 附件不使用第三方对象存储 URL 作为消息事实；外部 HTTP URL 只用于 SDK 接入展示或部署地址解析。
- 当前不是端到端加密系统。服务端需要解析消息元数据以完成存储、搜索、审核和同步；E2E 加密不是现阶段“漏做的按钮”。
- 当前按单实例私有化部署设计。多节点扩展必须先补连接路由、序号分配、事件日志和文件共享方案，不能仅增加副本数。
- 项目对外仍不保证版本兼容，内部从开发者预览开始有条件维护兼容；普通升级保留已有资料，同一协议
  major 的已发行契约冻结。破坏性数据重建需要独立说明范围与影响，不能以未正式发布为由默认清空内测资料。
