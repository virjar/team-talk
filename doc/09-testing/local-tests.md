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
- LocalCache 的真实 SQLite 读写、重启和并发边界；
- 复杂格式解析、溢出或截断等无法由正常业务样例覆盖的输入边界；
- 服务端真实业务入口的权限结果，不重复对简单角色比较或错误码映射做独立单测；
- 有真实故障依据、且从 UI 难以精确控制时序的状态回归。

### LocalCache clean-close checkpoint

提交 `35a700ec` 将 LocalCache 的 clean close 收敛为单一维护动作：`CacheUseGate` 先拒绝新访问并等待已经
准入的 SQL 离开，resident 释放后执行一次 `PRAGMA wal_checkpoint(PASSIVE)`，最后无条件关闭 driver。
提交 `a15d3c5a` 继续固定失败优先级；确定性 JVM 测试覆盖：

- 活动 SQL 尚未离开 gate 时 close 已经开始，但 checkpoint 不得提前进入；排空后严格按 checkpoint、driver
  close 顺序各执行一次，重复 close 不重复维护；
- 普通 checkpoint 异常只作诊断，若 driver close 同时失败则 close failure 必须外抛；checkpoint 的
  `CancellationException` 或 fatal failure 保持 primary，close failure 作为 suppressed，任何路径都实际调用 close；
- xerial SQLite 真实临时文件启用 WAL，以独立 reader transaction 钉住旧 snapshot，再写入草稿和 outgoing
  message。PASSIVE checkpoint 必须有界返回并留下未推进完的 frame；释放 reader、创建新 driver 后，草稿与
  outbox 精确恢复且 `quick_check=ok`。

该切片不运行 `VACUUM`、`optimize`、离线 compaction 或跨 namespace 扫描，也不把 Desktop 当前
`journal_mode=delete` 的 UI 验收冒充 WAL 覆盖。两次提交后的完整门禁共 213 个 Gradle task 成功。

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
