# 本地测试

本地验证包括真实数据库/网络栈的模块集成与少量单元测试，不等于给内部实现逐个写测试。
取舍遵循[轻单元、重集成原则](README.md#取舍原则轻单元重集成与端到端)，也不替代真实部署和客户端验收。

## 常用入口

```bash
# SDK、协议与公共业务逻辑
./gradlew :protocol:protocol:jvmTest
./gradlew :protocol:protocol-netty:jvmTest
./gradlew :client:shared:jvmTest
# 测试夹具只检查编译，不为 Fake 编写自测
./gradlew :client:shared-testkit:compileKotlinJvm

# 管理后台：锁文件安装、TypeScript 检查与生产构建
./gradlew :server:admin:check

# 服务端领域、持久化与进程内集成测试
./gradlew :server:server:test

# 客户端共享逻辑与 Desktop 目标测试
./gradlew :client:app:desktopTest :client:desktop:desktopTest

# 生产代码日志规范
bash scripts/check-println.sh
```

管理后台缺失源码导入、类型错误和静态资源打包失败都会使 `:server:admin:check` 失败；
`:server:server:check` 也依赖同一构建链。Gradle 自动管理 Node.js 和 npm，详细路径见
[管理后台构建链](../08-development/dependency-maintenance.md#管理后台的构建链)。该检查不替代浏览器交互验收。

开发中可以只运行受影响模块；准备交付时应扩大到相邻边界。例如修改消息体既影响 `protocol`
编解码，也影响服务端校验和客户端渲染，不能只跑一个 UI 测试。
`shared-testkit` 用普通 main 源集发布测试替身，但只允许被其他模块的 test 源集依赖；
产品编译不包含 `com.virjar.tk.testing`。

容量报告中的百分位、吞吐舍入、失败分类与原子 JSON 发布属于确定性纯规则，由普通 `:server:server:test`
覆盖；真正连接远端并产生负载的类位于独立 `capacityTest` source set，普通测试不会编译或发现它。
显式远端入口和参数见[真实部署验收](deployment-acceptance.md#消息容量基线入口)中的消息、连接、搜索
与附件容量章节。

`:server:server:test` 需要一个已经存在的 PostgreSQL 数据库，但不会创建数据库，也不会清空或修改其
`public` schema。每个进程内集成/E2E 环境会创建随机 `tt_test_*` schema，并通过 JDBC
`currentSchema` 只在该 schema 建表；正常关闭和启动失败都会执行 `DROP SCHEMA ... CASCADE`。
默认连接是本机 `jdbc:postgresql://localhost:5432/teamtalk`、当前系统用户名和空密码，也可显式设置：

```bash
TK_TEST_PG_JDBC=jdbc:postgresql://localhost:5432/teamtalk_test \
TK_TEST_PG_USER=teamtalk_test \
TK_TEST_PG_PASSWORD=your-test-password \
./gradlew :server:server:test
```

测试数据库账号只需要连接目标数据库以及创建、删除 schema 的权限。每个测试环境创建并绑定自己的
`Database + HikariDataSource`，因此同一测试 JVM 可以同时启动多个环境，关闭一个环境只注销和关闭
它自己的池；实例隔离测试会同时创建两个 `TestEnvironment` 并在关闭其中一个后继续使用另一个。
完整服务端测试仍固定单 fork 并关闭 JUnit 全局并行，以限制 PostgreSQL 连接和 RocksDB/Lucene 原生资源
占用；这是资源预算，不是数据库正确性所需的进程级互斥。

HTTP 空闲约束可用 `./gradlew :server:server:test --tests '*ProtectedHttpIdleTimeoutTest'` 单独验证，
不连接数据库。测试启动真实 loopback Ktor/Netty：通过小 socket 缓冲区和节流读取下载静态文件，
核对超过空闲窗口仍完整返回；另发送未完成的请求体并停止上传，确认连接关闭且 HTTP 调用退出。

### 文档协作与管理安全的定向回归

```bash
./gradlew :protocol:protocol:jvmTest --tests 'com.virjar.tk.protocol.DocumentModelTest'
./gradlew :server:server:test --tests '*DocumentChangeEventIntegrationTest' --tests '*DocumentCommentIntegrationTest' --tests '*AdminSecurityIntegrationTest'
./gradlew :client:shared:jvmTest --tests '*DocumentChangeProjectionIntegrationTest' --tests '*DocumentCommentRecoveryIntegrationTest'
./gradlew :client:app:desktopTest --tests '*DocumentWorkspaceStateTest'
```

服务端用随机 PostgreSQL schema 验证文档/评论与事件的同事务关系、实时权限、稳定身份重放、修订冲突、
分页和删除墓碑；管理安全使用真实 PostgreSQL 与 HTTP 路由验证轮换、会话吊销、重启恢复、审计拒绝
分类与存储故障。SQLite 回归验证评论创建/编辑/删除意图跨关闭重开、未知结果原字节重放、403/409
保留到显式 retry/discard、reset/撤权不删除 pending、关闭或失效后的晚到结果不复活缓存。
协议测试覆盖回复和墓碑往返、4,000 UTF-16 单元正文、100 条分页及截断/超量解码拒绝。
这些入口验证模块契约与持久恢复；真实双端评论交互、跨端可见性和管理台浏览器行为按
[场景目录](scenario-catalog.md)另外执行，不以构建通过代替界面验收。

### 任务协作的定向回归

```bash
./gradlew :protocol:protocol:jvmTest --tests '*TaskModelTest'
./gradlew :server:server:test --tests '*TaskIntegrationTest'
./gradlew :client:shared:jvmTest --tests '*TaskRecoveryIntegrationTest'
./gradlew :client:app:desktopTest --tests '*TaskFeatureTest' --tests '*TaskEditorTest'
```

协议回归验证任务、命令、审计、提醒和独立 TaskRef 的往返与有界解码。服务端使用随机 PostgreSQL
schema 验证参与者权限、上下文关联、CAS、稳定身份重放、并发提交，以及任务、审计、回执和事件的
原子性；截止扫描覆盖重启、重新设定提醒与完成竞态。SQLite 回归验证已保存命令的跨进程恢复、拒绝
保留、投影失效、提醒已读/已展示以及迁移保留已有评论意图。

App 回归通过真实 TaskRepository 与可控 RPC 检查外部引用冷启动、创建未确认不误读 404、撤权不循环
刷新、脏表单保留与失败处理、分页恢复和迟到读取；日期输入覆盖时区、夏令时缺失时刻与原截止精度。
这些测试不证明平台系统通知或真实双端 UI 已通过，仍需执行
[任务协作场景](scenario-catalog.md#i-任务协作)。

### 无头分发与 MCP 的定向回归

```bash
./gradlew :client:shared:jvmTest --tests '*HeadlessBundleInstallerIntegrationTest' --tests '*HeadlessConfigurationIntegrationTest'
./gradlew :client:shared:jvmTest --tests '*AgentMcpAccessTest' --tests '*AgentMcpHttpTest' --tests '*CliMainTest' --tests '*AgentApiTest'
./gradlew -p buildSrc test --tests '*HeadlessDistributionTest'
./gradlew :client:shared:verifyHeadlessDist :client:shared:headlessDistZip
```

安装器回归使用临时分发目录、真实文件锁、子进程和 shell launcher，检查移动路径、调用工作目录、
不可变版本升级、同时运行的进程租约、损坏/未知文件拒绝、暂存恢复与外部数据保留。配置回归使用专用
私有目录，验证保存端点、重启读取、错实例拒绝、离线 doctor 和 token 导出，不能操作已有用户 dataDir。
分发回归检查 ZIP、manifest、逐文件摘要和离开源码的 launcher；`--version` 不依赖 Java 或在线 agent。

MCP 回归通过真实 loopback HTTP 检查管理员与 scoped token 分离、工具和会话范围、直接 REST 绕过拒绝、
授权持久化与撤销、等待中撤销、限速及审计落盘失败。发送仍需区分持久入队与服务器 ACK，未知结果复用
原 `clientMsgId`。模块检查不代替真实 MCP 客户端与 TeamTalk 对端验收，完整操作规范见
[无头分发与授权验收](deployment-acceptance.md#无头分发与授权验收)。

## Linux 媒体测试环境

服务端缩略图测试与 TestPeer 音频元数据读取会加载 JavaCV/FFmpeg JNI。FFmpeg 原生库随 Maven JAR
提供，但仍依赖系统动态库；Ubuntu 24.04 的服务端测试与验收 runner 使用：

```bash
sudo apt-get update
sudo apt-get install -y --no-install-recommends \
  libasound2t64 libpulse0 libva-drm2 libva2 libxcb1 libxcb-shm0
```

这些是运行库依赖，不要求实际声卡、运行音频服务或安装系统 `ffmpeg` 命令。其他发行版按本机包名
提供相同动态库。出现 `libpulse.so.0` 等缺失或 `FFmpegFrameGrabber.tryLoad()` 失败时，先查看首次
加载异常及最内层 cause，再对 Bytedeco 缓存中实际加载的 `.so` 执行 `ldd`，检查 `not found`。
JavaCV 会缓存首次加载异常，后续 `tryLoad()` 可能只是重抛；补齐系统库后重新启动测试 JVM，不能将
后续堆栈中的不同加载入口误判为多个业务故障。JavaCV 与 native 版本对应关系见
[依赖维护](../08-development/dependency-maintenance.md#按兼容关系成组升级)。

## 版本与数据兼容的定向验证

协议修改先检查清单与生成器，再验证真实 TCP 握手和磁盘 SQLite 迁移。常用选择器：

```bash
./gradlew :protocol:rpc-processor:test :protocol:protocol:jvmTest \
  :protocol:protocol-netty:jvmTest :protocol:protocol:verifyProtocolBaseline
./gradlew :client:shared:jvmTest --tests '*ImClientProtocolVersionTest' \
  --tests '*ClientDataVersionTest' --tests '*JvmLocalCacheMigrationTest' \
  --tests '*LocalCacheSchemaEpochTest' --tests '*JvmLocalCacheRecoveryTest'
./gradlew :server:server:test --tests '*ServerProtocolNegotiationTest' \
  --tests '*ServerProtocolConfigurationTest' --tests '*ProtocolEventProjectionTest' \
  --tests '*RpcDispatcherConflictTest'
```

这些检查分别覆盖：协商先于凭据、兼容窗口与强制拒绝、协议墓碑、新旧事件投影、旧库首次认领、
小版本迁移失败回滚、大版本重置与降级保留。磁盘 JDBC 的连接生命周期与内存库不同，迁移回归必须
包含真实临时文件。界面仍需在实际客户端核对升级横幅、强制弹窗和工作区拦截；不要求为版本改动跑完整 UI 场景库。

## 应优先放在本地的测试

下列是需要确定性验证的业务风险与契约，不是“每个类都配一份单测”的清单。已由真实模块集成覆盖的
字段、简单校验或转发，不再向下补重复测试。

- wire header、payload 编解码和协议版本拒绝规则；
- RPC ID、Notify payload 与生成代码一致性；
- Document method 11/12 的 wire 分工：content update 不能携带 title，move/rename 必须冻结
  expectedRevision、operationId 与 issuedAt；精确重放 ACK 允许空移动投影；
- Document 同级 `(createdAt, nodeId)` 全序在协议模型、服务端 SQL、LocalCache 重启和测试投影中一致，
  改名不改变顺序；
- Document move/rename 的服务端收据覆盖首次/精确重放、同 ID 异 payload、8 路并发、7 天过期与
  维护回收；客户端覆盖每节点单槽、256 条 durable outbox、跨进程原 identity、迟到 ACK generation，
  以及空投影 ACK 在本地收敛成功前不清 outbox；
- 消息发送状态机、重连、补发、去重和游标推进；
- MessageStore 高水位、消息、幂等索引与 CREATE outbox 的原子批；PG 投影回滚、提交后重放和排队命令
  必须保持 serverSeq 连续、事件唯一及未读精确；
- `SyncRpc` 的 ID/golden、checkpoint 多页去重/游标前进、expected dataset + cursor CAS 原子安装；
- `PresencePayload`、`FriendPresenceSnapshot` 与 `ContactRpc.getPresenceSnapshot` 的 wire、边界和
  权限契约，Registry 快照/首末设备 transition 的串行 revision，以及客户端 reducer、会话刷新、
  TYPING 发送节流和接收 TTL；
- `User.avatar/ProfilePatch.avatar/Conversation.chatAvatar/CardBody.targetAvatar` 完整 Attachment
  round-trip、URL/MIME/大小拒绝、`Unchanged` 与 `Set(null)`；users 头像四列 all-or-none、User revision、当前头像
  引用查询、USER_UPDATED 本人/活动好友 fanout，以及个人会话 `peerUid` 与客户端规范 User 优先展示；
- `sync_events` 回收只删已完成进程内推送尝试且过期的连续前缀，lease/gate 不得跨过正在 replay/checkpoint 的游标；
- `ReplyBody.assets` canonical round-trip、有界集合、正文/清单闭包，以及服务端主件/缩略图引用、搜索与撤回释放；
- 内容搜索 wire、有界分页、领域权限、文件类型筛选和权威打开；`ContentAssetSearchIntegrationTest`
  覆盖文档/群文件投影与事务恢复，`MessageAttachmentSearchIntegrationTest` 使用真实 PG、消息和
  FileStore，`SearchIndexAttachmentTest` 覆盖消息修订、墓碑与旧索引重建；SDK 回归覆盖失效和迟到结果；
- LocalCache 的真实 SQLite 读写、重启和并发边界；
- 复杂格式解析、溢出或截断等无法由正常业务样例覆盖的输入边界；
- 服务端真实业务入口的权限结果，不重复对简单角色比较或错误码映射做独立单测；
- 有真实故障依据、且从 UI 难以精确控制时序的状态回归。

### LocalCache 隔离与只读诊断

```bash
./gradlew :client:shared:jvmTest --tests '*LocalCacheDiagnosticsIntegrationTest' --tests '*JvmLocalCacheRecoveryTest' --tests '*ChatAssetUploadRecoveryIntegrationTest'
./gradlew :client:shared:testDebugUnitTest --tests '*AndroidLocalCacheQuarantineTest'
```

使用临时目录与真实 SQLite 数据库族验证，不打开已有用户资料。诊断回归应检查 JVM 安装布局、Android
导出布局、隔离副本、WAL 中尚未 checkpoint 的可靠事实，以及诊断前后原文件和生命周期 marker 不变。
坏库、缺表、未知更新 schema、容量边界和复制期间源变化必须给出 UNKNOWN；输出只含白名单聚合和
固定分类，不含消息、草稿、命令正文或 token。独立文档草稿/操作与 spool 保持未检查，不能把空计数
解释为可安全删除。稳定窗口检查不代替在线原子快照，人工执行入口见
[本地资料诊断](../05-clients/headless.md)。

附件恢复回归应保留同账号隔离副本和冻结源，重新创建替代库并启动协调器，验证未知旧引用不会触发
孤儿源删除，重新打开后保护仍有效；其他账号及无隔离副本的正常回收保持原有语义。不得用删除真实
隔离库来完成验证，也不能把配额限制绕过为无界保留。上述入口不验证资料救援、放弃或 compaction。

### 隔离资料保全与校验

```bash
./gradlew :client:shared:jvmTest --tests '*LocalCacheArchiveIntegrationTest'
```

当前自动化使用临时 JVM 安装根和 Android 应用导出布局，覆盖隔离库与替代库的原始字节、精确 owner
范围、附件源与文档临时文件保留、缺失范围记录、源文件不变，以及归档迁移位置后独立校验。
拒绝用例覆盖已有或源根内输出、源锁占用或缺失、链接、缺失/不完整清单、归档文件增删改与越界路径；
Android 用例另检查非目录 scope 的存在标记与文件清单一致。

容量上限、复制期间并发源变化、复制中断或磁盘满，以及这些故障后的未完成输出保留，属于后续故障
注入验收场景，不由上述自动化或基础 CLI 验收证明。
实际 CLI 验收仅使用本任务创建的资料或保留一致性的副本，不以校验成功宣称救援导入、原始导出一致性
或用户资料可删除。归档中的测试正文和命令凭据不得进入日志或提交。

### 隔离副本显式放弃

```bash
./gradlew :client:shared:jvmTest --tests '*LocalCacheQuarantineDispositionIntegrationTest'
```

仅使用专用临时安装根、Android 导出布局与归档，核对显式确认清单摘要后只删除选定隔离文件；归档、
替代库、共享 spool 和独立文档资料不变。归档后共享资料的正常增删改不应阻断操作；隔离内容发生变化、
出现未知文件、归档损坏、选错路径/布局/摘要或 JVM 锁不可用时拒绝删除。

续跑场景核对隔离文件已删除一部分、主库已删但空目录仍在，以及全部已删除时使用同一归档重试；
这些静态残留夹具不等于进程终止或断电注入。容量极限、并发修改、磁盘故障与断电持久性仍需专用故障
验收，不能由定向测试入口或基础 CLI smoke 推定覆盖。Android 操作限于导出副本，不证明手机容量回收。
人工入口与后续正常附件源回收的影响见[无头客户端](../05-clients/headless.md)。

### 账号 namespace 处置

```bash
./gradlew :client:shared:jvmTest --tests '*LocalCacheNamespaceIntegrationTest' --tests '*HeadlessConfigurationIntegrationTest'
```

namespace 集成夹具覆盖真实 SQLite 的完整 owner、多 epoch 与隔离、只有独立草稿的账号、邻居保留、
归档用途隔离、锁和摘要拒绝、归档或源增改拒绝、部分删除续跑、headless 当前凭据保护及 Android 导出布局。
CLI 参数夹具检查缺少明确 owner 时拒绝且不创建目录；完整制品命令仍须用下面的 CLI 方式复验。

仅使用专用临时 JVM 安装根、Android 停止进程后的完整导出夹具与新归档。按部署指纹、datasetId、uid
显式选择 owner，核对同一 owner 的多 epoch 数据库族、隔离副本、附件源及独立文档草稿一同保全；
其他 owner、登录凭据、媒体、telemetry 和未知 legacy 保留，Android 共享文档 preferences 只归档不删除。
当前凭据仍引用目标或损坏、无法确认时应拒绝放弃，headless 同部署与 uid 的其他 dataset 也受保护。

真实 CLI 验收从生成的 `headless/bin/tt-agent` 执行导出、校验及摘要确认放弃，核对输出不含正文或 token、
未选中的 agent 数据目录不被创建、原始归档与非目标文件不变。需覆盖 owner/布局/摘要不匹配、缺失确认、
锁占用、归档增删改，以及 format 1 隔离归档与 format 2 namespace 归档互不授权删除。

验收结论区分原字节保全、显式资料放弃与可执行救援。校验成功不能宣称导入或重放完成，Android 导出
夹具不能代替手机端删除与恢复；容量极限、实时并发、磁盘故障及断电持久性另需专用故障验证。
操作示例见[账号 namespace 保全与放弃](../05-clients/headless.md#账号-namespace-保全与放弃)。

### 单聊天草稿救援

```bash
./gradlew :client:shared:jvmTest --tests '*LocalCacheChatDraftRescueIntegrationTest' \
  --tests '*ChatDraftSyncRecoveryIntegrationTest'
```

使用真实临时 SQLite 和私有源文件，检查 format 1/2 归档及 Android 来源到同 owner JVM 目标的单聊天
导入；预览不安装草稿，提交使用新本机 revision，保留归档、其他聊天和独立资料。重启后权威读取服务器
草稿，再分别检查保留本机和使用其他设备的选择；带源 READY 任务转为失败，只有显式重试才继续原上传
identity。已有同步恢复测试负责 CAS、未知结果与 ACK 后清稿的普通链路。

拒绝场景覆盖目标非空/可靠工作、预览后 composer 时钟变化、源待确认变更或消费依赖、归档/源摘要变化、
owner 不匹配、根锁占用及目标 schema 不支持。以定向测试实际断言为自动化覆盖范围，不能据此宣称所有
损坏形态、磁盘故障或中断窗口均可恢复。

真实 Desktop 验收须使用专用安装根和归档，先退出客户端执行打包 CLI 预览及确认导入，再打开同账号
目标会话；确认草稿与模式/回复/附件对应、未自动发送、冲突选择和手动附件重试可用。最终只主动发送
一次，检查 ACK、草稿清空及重启无重复；不以 SQLite 断言代替界面验收，也不对用户原库制造故障。
命令和限制见[单聊天草稿救援](../05-clients/headless.md#单聊天草稿救援)。

### 单条 outgoing 救援

```bash
./gradlew :client:shared:jvmTest --tests '*LocalCacheOutgoingRescueIntegrationTest' \
  --tests '*HeadlessConfigurationIntegrationTest'
```

使用真实临时 SQLite 与归档，核对原 payload/fingerprint/消息身份续发、权威编辑快照回流、终态失败
保持停止、附件源仅在明确失败后用于修复，以及精确消费关联不清除新远端版本。拒绝场景包含未知草稿
变更或后继稿、两个目标时钟变化、已有权威身份、SUCCESS 回执及非规范 payload。
CLI 回归核对消息选择、三项确认及非法参数拒绝，不创建无关目录。其他来源布局与容量、锁、摘要和
损坏形态的验收分别记录，以测试实际断言为自动化覆盖范围。

真实 Desktop 验收在专用安装根执行打包 CLI 预览和导入；确认活跃消息在认证后自动续发原 clientMsgId，
取得 ACK 后只出现一条消息，重启不重复；终态失败保持可见且不自动发送。原 ACK 丢失场景应返回原
serverSeq，首次发送附件失效场景应进入明确失败并保留恢复入口。导入成功与消息送达分别判定，不对
用户原库制造故障，不把定向用例扩大成通用损坏修复或断电保证。
命令与边界见[单条 outgoing 救援](../05-clients/headless.md#单条-outgoing-救援)。

### 独立文档单标签救援

```bash
./gradlew :client:desktop:desktopTest --tests '*DesktopDocumentDraftRescueIntegrationTest' \
  :client:app:desktopTest --tests '*DocumentDraftRescueTest'
```

使用任务专属临时安装根、真实 SQLite 与 Desktop 文档记录存储，检查 format 1/2 JVM 归档中单个
未保存标签的列举、预览与导入。列举不解析或输出标签正文，也不承诺可恢复；维护入口不初始化默认
资料目录或网络。预览不能安装记录或启动登录；导入保留原 tab/document/recovery 身份、旧
saved 基线与 revision、本机正文和完整 sidecar，记录写入完成后才发布 manifest。核对原资料、归档和
其他 owner 字节不变，以及目标状态摘要变化后旧确认失效。

拒绝场景应涵盖源记录退役、非法或缺失正文/资产清单、旧文档 schema、manifest 待确认操作，以及
归档内任一数据库中的待确认移动/改名；空替代库不能掩盖隔离库中的命令。无主库、孤立 sidecar、旧
SQLite schema、dataset 不符、依赖查询失败、Android 来源和非空目标均须保持资料不变。

真实 Desktop 验收使用任务账号：保存 revision 1 后留下本机修改，另一客户端提交 revision 2，再从
归档救到空文档 namespace。打开工作台应显示原本稿且不自动保存；显式保存得到冲突，选择保留本稿
后仍须再次保存。已有缓存不代表已重取最新正文，验收需核对权威结果；另检查删除或撤权后本稿保留、
原地写入受拒，以及关闭重开不丢稿。未完成附件上传和 Android 应用内导入不由这些测试覆盖。

既有工作区恢复和冲突回归位于 `DocumentWorkspaceOfflineRestartTest`、`DocumentWorkspaceStateTest`，
服务端 CAS 与幂等边界由 `DocumentIntegrationTest`、`DocumentNodeMoveReliabilityIntegrationTest` 检查。
命令见 [Desktop 独立文档草稿救援](../05-clients/desktop.md#独立文档草稿救援)。

### 可靠文档创建救援

定向入口沿用 `DocumentDraftRescueTest`、`DesktopDocumentDraftRescueIntegrationTest` 与
`DocumentWorkspaceOfflineRestartTest`，分别检查配对记录校验、离线导入，以及重启后冻结请求重放与
后继草稿保留。

使用真实 Desktop 文档记录存储与临时 SQLite，构造单个 creating 标签、冻结创建请求及在请求之后
产生的后继草稿。列举、预览和确认导入使用任务专属 JVM 归档与同 owner 的空目标 namespace；
检查原标签/文档 ID、冻结正文/资产、本机后继修改均保持，两个记录安装完成后才由 manifest 发布。
操作前后的原归档、隔离资料、邻居 owner 和目标无关事实保持不变，命令不初始化默认资料目录或网络。

拒绝场景应包含多条创建、标签/命令身份或位置不匹配、墓碑、非法 JSON、缺失或未完成上传的资产描述符、空间
创建/删除/归档，以及归档任一数据库或目标库中的待确认移动/改名；非空目标和过期目标摘要不被覆盖。
普通无 pending 草稿与可靠创建入口不能互换；每个结论按实际测试断言或独立验收证据记录，不宣称
通用文档操作救援、Android 来源或磁盘故障已覆盖。

真实客户端验收分别构造“请求未到达服务器”和“服务器已接受但 ACK 丢失”。确认导入后在当前可见
空间观察原请求重放，服务端只能有同一个文档 ID；已接受请求的无正文 ACK 只绑定身份，不回滚服务器
后续正文。后继草稿保持 dirty 且不自动保存，用户再次保存时仍接受 revision/权限检查。另核对空间
撤权/归档和首次附件失效时待办与草稿保留，不把导入成功当作业务完成。
命令见[Desktop 可靠文档创建救援](../05-clients/desktop.md#可靠文档创建救援)。

### 空间创建与文档树救援

沿用文档救援与工作区重启夹具，在任务专属 JVM 安装根构造唯一冻结空间请求、同空间普通草稿和
多层 creating 文档，其中祖先保留已准入创建命令，叶节点同时包含已准入命令及尚无命令的草稿。
列举返回空间命令标识，
预览不写目标；确认导入后核对原空间/文档 ID、冻结 payload、后继草稿与全部同空间依赖保持一致。
其他空间普通草稿不带入，原归档、源资料与其他 owner 不变；记录安装完成后才发布 manifest。

拒绝场景应涵盖多空间创建、其他空间 creating 标签或文档命令、未准入父/祖先、非规范 UUID、超过
128 项的祖先链、重复或自身 ID、非法 parent、创建依赖图中的环、删除/归档、任一源库或目标库的待确认移动、
损坏或缺失记录，以及非空目标、owner/schema 不符和
过期目标摘要。不把普通草稿入口或单文档创建入口的拒绝当作可删除依赖的理由。
另检查无待确认空间请求、仅有多个 creating 文档的普通空间归档仍被此入口拒绝，应使用下方独立
文档树入口，不能从单文档入口绕过唯一命令限制。

真实 Desktop 验收分别检查空间尚未创建、空间已被接受但 ACK 丢失，以及接受后改名、归档或失权。
启动按当前可见 ID 收尾；需要重放时，取得当前空间投影后只发送依赖已满足的原命令，父文档确认
持久收尾并发布有效投影后，只续发依赖刚确认祖先且其余依赖已满足的既有命令。检查父/子/孙的请求
顺序、原 ID/payload 和后继草稿保持，
以及父失败、未准入祖先、空投影时当次不会越过依赖发送后代；后续刷新或显式保存仍由服务端裁决。
远端已有父节点的引用可保留，但服务端当前父链或权限变化仍能拒绝首次创建。已接受空间不回滚当前
名称，衔接不改变用户随后选择的导航；无命令叶草稿与后继修改不自动保存，失败保留待办，不把导入
成功等同于业务完成。覆盖结论按实际测试断言和独立验收证据记录。
另构造父文档已接受后被其他客户端移动、旧 ACK 尚未收尾时创建子文档的归档，核对父子不同时刻的
合法路径原样保留，不因旧路径前缀不同被拒绝；重放仍使用各自原请求，由服务端当前父链裁决。
再构造 P 创建仍 pending、其下已有远端中间父 B、C 在 B 下等待 P 的场景：P 获得有效确认后，C
应沿原命令续发，不因直接父 B 无 pending 而遗漏；同时检查无关失败兄弟不被这次确认重新触发。
命令见[Desktop 空间创建与文档树救援](../05-clients/desktop.md#空间创建与文档树救援)。

### 已有空间文档树创建救援

使用真实 Desktop 文档记录与临时 SQLite，构造无任何待确认空间创建或删除/归档意图的归档：选中
空间包含父/子/孙冻结创建、无命令叶草稿、普通脏稿和请求之后的修改；其他空间另有独立 creating
工作。列举应返回可选择的空间，预览不写目标，确认导入只恢复选中空间全部未退役草稿与原命令。
核对原 ID、冻结 payload、路径与后继稿保持，其他空间留源，原归档、源库和邻居 owner 不变。

拒绝场景应包括全局身份重复、邻居草稿正文/资产或冻结内容损坏、任意待确认空间创建/删除/归档、选中空间无 live 冻结
命令、选中链指向归档内已知其他空间节点、未准入祖先或有环依赖、源/目标待确认移动、旧 schema、
owner 不符、非空目标 namespace 与过期摘要。不能通过空间选择跳过坏冻结命令，也不能因目标某个
空间为空而合并已有目标资料。

真实客户端验收在已有可用空间内观察父/子/孙沿原身份及依赖顺序重放，不发送空间创建请求；祖先
确认后仅衔接相关且其余依赖满足的已有命令。后继修改和无命令叶草稿不自动保存；权限、父链或附件
失效导致失败时保留待办与本稿。保留旧单文档和待创建空间入口的拒绝回归，覆盖范围以实际测试断言
和验收证据为准，不把独立入口完成当作通用文档恢复。
命令见[Desktop 已有空间文档树创建救援](../05-clients/desktop.md#已有空间文档树创建救援)。

### JVM 离线单库压缩

```bash
./gradlew :client:shared:jvmTest --tests '*LocalCacheCompactionIntegrationTest'
```

使用专用临时安装根与真实 SQLite，先写入可靠事实并制造可回收空闲页，再调用单库压缩；核对消息、
草稿、outbox/命令、Bot inbox、水位和 schema 保留，`quick_check` 正常，压缩前后页数与字节摘要准确。
同时核对独立文档草稿、spool、其他账号与安装 marker 未被清理。

拒绝场景应覆盖已持有安装锁、外部 SQLite 连接占用、隔离库或同账号隔离副本、损坏库、非当前
schema/epoch、非法或链接路径、安装 major 不兼容/重置中。实际磁盘耗尽与进程中断需在专用存储环境补验。
仅在本任务创建的库或一致副本
上执行 CLI smoke；不能用用户正在使用的原库验收。此入口不覆盖 Android 原地压缩、救援或放弃。

### 会话内数据库整理

```bash
./gradlew :client:shared:jvmTest --tests '*LocalCacheStorageCompactionIntegrationTest'
```

该入口用真实临时 SQLite 与工厂创建的 LocalCache 检查 `compactStorage()`：整理前后逐表、逐列与 BLOB
保持一致，草稿、附件任务、outgoing、可靠命令、Bot inbox、时钟及自增身份保留；同一 owner 继续读写，
关闭重开后可靠事实仍在。独立文档资料、spool 与其他账号文件不应改动。
门控夹具覆盖整理等待已准入读取、后续读取等待整理、close 等待同一 driver，以及普通维护失败后继续使用。
不支持的存储、schema 变化、完整性失败、现存事务与隔离副本拒绝维护。

```bash
./gradlew :client:android:testDebugUnitTest --tests '*AndroidStorageMaintenanceOwnerTest'
```

Android 维护 owner 的定向夹具检查：未确认旧会话退役时不打开维护库、观察者离开不等待或取消阻塞维护、
重复准入与提前返回被拒绝、普通失败可重试、句柄关闭失败继续阻止新认证。它不运行 Activity 或真实 Android
SQLite 驱动。

Android 的数据库族与逻辑库分别受 64 MiB 整理上限约束，JVM 保持 512 MiB；平台验收需分别检查超限
明确拒绝、未执行 `VACUUM` 且可靠资料保留。该限制用于收窄 Android 原生临时库内存占用，不能据此认定
所有设备和内存压力组合均已覆盖。

Android 必须另用实际应用在“设置 → 本地存储”操作，检查整理中、成功前后大小、失败提示及重复点击；
进入维护后先确认会话关闭、凭据保留，再开始整理；维护期间主线程继续响应，切换后台、Activity 重建或
结束后重开保留同一任务，不能提前打开账号库。完成后“返回应用”无需重新输入密码，草稿与待发身份不变，
继续聊天并重启复验。普通失败可重试或返回；会话或维护句柄关闭失败只能明确退出进程后重开。
只用专用验收资料，不在用户原库注入故障。JVM 驱动测试不代替 Android 原生连接池与文件系统验证；
容量极限、实际磁盘耗尽和断电恢复需专用故障验收，不能由上述入口推定覆盖。

### LocalCache clean-close checkpoint

LocalCache 的 clean close 只有一个维护动作：`CacheUseGate` 先拒绝新访问并等待已经
准入的 SQL 离开，resident 释放后执行一次 `PRAGMA wal_checkpoint(PASSIVE)`，最后无条件关闭 driver。
失败优先级与执行顺序由确定性 JVM 测试覆盖：

- 活动 SQL 尚未离开 gate 时 close 已经开始，但 checkpoint 不得提前进入；排空后严格按 checkpoint、driver
  close 顺序各执行一次，重复 close 不重复维护；
- 普通 checkpoint 异常只作诊断，若 driver close 同时失败则 close failure 必须外抛；checkpoint 的
  `CancellationException` 或 fatal failure 保持 primary，close failure 作为 suppressed，任何路径都实际调用 close；
- xerial SQLite 真实临时文件启用 WAL，以独立 reader transaction 钉住旧 snapshot，再写入草稿和 outgoing
  message。PASSIVE checkpoint 必须有界返回并留下未推进完的 frame；释放 reader、创建新 driver 后，草稿与
  outbox 精确恢复且 `quick_check=ok`。

clean close 不运行 `VACUUM`、`optimize`、离线 compaction 或跨 namespace 扫描，也不把 Desktop 当前
`journal_mode=delete` 的 UI 验收冒充 WAL 覆盖。

Desktop 草稿生命周期优先用纯 JVM 门控测试覆盖，不要求构造真实 `ClientSession`：泛型 binding
registry 验证 Compose detach 后仍能交付原 reason，owner gate 验证普通 disposal 与 reasoned
retirement 并发时 discard 单调胜出，以及导航、平台资源和 session 各自只完成一次。私有临时目录测试
另覆盖 deployment + uid 隔离、较大单记录、原子删除幂等性和新 persistence 实例恢复。

文档本地投影优先用可控 Deferred 与临时 SQLite 做确定性回归，但不在客户端复制服务端权限状态机。
至少固定以下边界：

- 每次空间、分支、正文和 mutation RPC 都由服务端按当前事实授权；仓储的单一 mutex 必须使远端结果与缓存写入/清理保持同一顺序，工作台的普通 latest-request gate 只防止旧页面覆盖新页面；
- 空间 403 或根分支 404 清理整个空间的干净首页、分支和正文投影；子文档 404 或删除只清理目标文档，不能误伤兄弟文档的保存；
- 只有从首游标开始并抵达完整终页的空间扫描才能按 omission 清理缺失空间；局部分页不能从缺失项推断撤权；
- 网络异常、超时和 5xx 均保留已有干净缓存，使断网读取继续可用；服务端明确 403/404 后不能继续把旧干净行当作可用投影；
- 清理发生前同步捕获活动编辑帧；干净投影删除，脏/新建草稿保留为路径未解析的本地孤儿，不能因刷新或关闭标签丢失；
- 权限恢复后只由新的完整空间/分支/正文快照重建投影，不从任何客户端权限影子状态恢复旧行；
- 正向 policy mutation ACK 只触发相应列表/工作区刷新，不直接拼接 owner、role 或 policyRevision；`effectiveRole = NONE` 立即走同一投影清理路径；
- 创建已在服务端提交但返回投影为空时，客户端仍按稳定 ID 完成 create outbox、保留最新草稿且不重复创建。

这些用例验证缓存保留、明确终态清理、草稿保护和命令可靠性。真实部署验收继续证明服务端逐请求授权、
交接回执与跨账号可见性，两层证据不能互相代替。

群文件可靠命令以协议、临时 SQLite 和 PostgreSQL 进程内集成测试形成确定性门禁：

- createFolder、createFile、addVersion、rename、delete 五类命令都必须先持久化稳定 identity，再做第一次
  RPC；网络、超时、408、429、5xx 和截断成功响应保留原 generation，重启后仍以相同 entryId/commandId
  重放，不能分配第二个身份；
- 前台提交与后台恢复共用单一发送 mutex；前台在等待已在发送的 worker 前就固定持久
  generation，worker-first 时仍重放同一 commandId，不能在旧行清理后制造第二代。直接 ACK 只清理对应 generation；确定性 400/404/409/422
  清理该 generation，若发生在后台重放则向页面发布一次 REJECTED completion；401/403 等会话失败不冒充
  业务终态，也不能删除待确认命令；
- `ACTION` 遥测区分 `STARTED → QUEUED` 与 `STARTED → SUCCEEDED`，持久排队显示经过审核的非错误提示，
  后台拒绝再显示明确失败；目录进入、返回、祖先改名和删除恢复不能短暂复用上一层陈旧列表；
- session 反馈 FIFO 覆盖等值连续通知、PENDING/REJECTED 交错、多宿主互斥租用和取消后接续；
  旧租约的迟到 complete 不得删除已经转交给新宿主的事件；
- 服务端五类命令均断言收据、条目/版本、usage 与审计同事务提交。rename/delete 还要覆盖重启重放、
  多路并发精确投递、冲突 payload，以及首次提交后操作者离群时只有原 `Unit` 收据仍可确认；新命令继续
  按当前成员事实拒绝。

这些测试保护确定性状态机和事务边界；真实 Desktop/Android 的离线页面、进程重启、联网恢复与用户提示
仍必须由部署验收独立证明。服务端已经提交但 ACK 丢失后的 exact receipt replay、同 identity 不重复推进
revision 由上述确定性夹具证明；真实 UI 的停服场景只证明首发前服务不可用时 durable outbox 能跨进程保留
并在服务恢复后收敛，不能把两类故障窗口互相替代。

## 不应只靠本地测试证明的行为

- PostgreSQL、RocksDB、Lucene 和真实独立进程生命周期的协作（进程内组合测试仍属于本地安全网）；
- 上传后的附件是否能从正式文件端点读取；
- 两个账户之间的实时通知、离线补偿和已读同步；
- 过期 cursor → checkpoint → tail 属于真实部署回归；首次 Desktop/Android 门禁已经通过，涉及同步
  或回收的后续改动仍须按部署验收中的 31 天 `created_at` 加速流程复验；
- Desktop 窗口层级、弹窗、抽屉、拖放和下载动画；
- Android 系统权限、键盘、媒体选择和后台恢复；
- 部署脚本、systemd、反向代理和外部访问地址。

这些行为应进入[部署验收](deployment-acceptance.md)或客户端验收。

## 测试设计准则

### 验证公开契约

测试应从模块公开边界观察结果。不要把内部实现细节写成断言，否则一次合理重构会产生大量无意义失败。

### 控制时间与并发

异步测试使用明确的状态等待和有限超时，不使用固定长时间休眠。涉及重连、心跳或延迟任务时，测试应能说明等待的状态和失败原因。

### 固定边界，而不是固定样例

按失败风险挑选边界：协议截断、分页遗漏、重复提交、事务回滚等需要稳定覆盖；简单字段的空值、默认值
和映射不机械穷举。能在同一业务集成场景观察的结果不再分拆到每个内部 helper。

### 测试数据可隔离

测试账号、chatId、clientMsgId 和临时文件必须可区分，避免并行或重跑时相互污染。远程验收使用独立前缀；本地持久化测试使用临时目录。

## 失败定位顺序

1. 先看最小失败测试及其异常，不先扩大重跑范围。
2. 判断失败属于契约、领域逻辑、持久化还是环境依赖。
3. 若本地通过而部署验收失败，核对部署版本、配置与服务日志。
4. 若协议验收通过而客户端失败，核对本地缓存、语义树与 UI 状态。
5. 修复后优先补强现有集成/E2E 场景；只有上层难以稳定触发或定位时才增加最小局部回归，再恢复完整验收。
