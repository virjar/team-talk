# 部署与升级

## 1. 部署任务

根项目提供：

| 任务 | 结果 |
|---|---|
| `verifyRelease` | 架构门禁、统一版本和 clean source identity 校验 |
| `:server:server:buildServerDist` / `:server:server:installDist` | 构建 Admin 后生成带 identity manifest 的服务端分发 |
| `deployServer` | clean 工作树本地构建并部署服务端 |
| `deployStagedServer -PSERVER_DIST_DIR=...` | 只部署 CI 已构建服务端，不重新构建 |
| `deployServerResetData -Pteamtalk.resetDeployConfirm=<host>:<deployPath>` | **破坏性**：在精确确认的既有完整安装上，以空服务端数据部署 |
| `releaseAndroid` | APK 构建并上传下载目录 |
| `releaseClients` | 双端一键同版发布（desktop + apk，常规发版入口） |
| `buildRelease` | 服务端、Desktop 当前平台与 Android 发布产物 |
| `releaseDesktop` | Conveyor 三平台安装包构建 + 更新站点上传 |
| `writeDesktopSiteManifest` | 为已构建 Conveyor site 写入 release identity |
| `uploadDesktopSite -PDESKTOP_SITE_DIR=...` | 只上传已构建站点，不运行 Conveyor |
| `uploadClientArtifacts` | CI 上传已分平台构建的产物 |

统一展示版本与安装构建计数的事实源是 `gradle.properties` 中的 `teamtalk.releaseVersion` 与
`teamtalk.releaseBuildNumber`。Android `versionCode` 为构建计数加一，满足平台正整数约束，
不再由展示字符串推导；tag release 必须使用精确的 `v<releaseVersion>`。Server、SDK、Android、
Desktop Compose 与 Conveyor 使用同一展示版本。二进制协议另按 major/minor 递增；`verifyRelease`
检查已提交的 wire 基线。零号切换、数据保留及后续发版步骤见[版本机制](../04-protocol/versioning.md)。

Server 构建通过锁定的 `server/admin/package-lock.json` 执行 Admin build，输出只进入 `server/server/build`；仓库中的
历史 `server/admin/dist` 已删除并由根 `.gitignore` 忽略，不再是 Server 输入；`checkArchitecture` 会拒绝重新
跟踪该目录。Conveyor 在 Linux job 交叉生成三平台站点，producer 完成后再写入 identity manifest。

## 2. 首次部署

下面流程中的 TLS 分支由 `serverUrl` 是否使用 HTTPS 决定。HTTP 安装、SDK 地址校验和运行时支持的
范围并不相同，部署前按[传输配置边界](configuration.md#传输配置边界)核对；当前工具尚无独立于
HTTP scheme 的 TCP 自签证书生成与客户端信任流程。

`deployServer` 的首次流程：

1. 校验 deployment.json 安全格式；deployPath 必须是规范化的非根 POSIX 绝对路径，拒绝 `.`、`..`、
   重复分隔符和尾随 `/`。
2. `deployServer` 构建服务端分发；`deployStagedServer` 校验 staged artifact 的类型、版本和完整 build
   identity，且没有任何构建依赖。
3. 提供 TLS 参数时，校验它们成对且指向普通文件；取得以规范 deployPath 派生的远端独占锁后，才读取目标状态。
   只有完全空目标可进入首次部署；已有分发、env、compose、systemd unit、data epoch、dataset identity
   任一缺失的半安装
   状态一律失败，不会误生成新密码覆盖残留实例。
4. 生成或加载本地 deployment.secrets；首次 HTTPS 必须有证书与私钥，并在本地转换、
   读取 PKCS12 成功后才继续。HTTP 安装拒绝证书参数，不配置 TLS keystore。
5. 创建 data、conf、ssl、static 等远端目录，上传分发文件、docker-compose、env.sh；HTTPS 安装另上传 TLS keystore。
6. 启动 PostgreSQL并确认数据库用户。
7. 注册/更新 systemd 服务。
8. 启动 TeamTalk；只有 `/health` 返回 HTTP 200、总体 `UP`、固定 9 个必需组件全部为 `UP`，且
   `buildIdentity` 与 staged server manifest 精确相同后才输出部署完成。

首次自动生成的数据库、管理后台和 TLS secret 会完整写入本地不入库文件，并以 owner-only、拒绝
符号链接、同目录临时文件原子替换的方式保存；远端 env.sh 同样以 mode 600 原子发布。env.sh 中的
值使用 POSIX 单引号编码，`$`、反引号、反斜线及引号不会被 shell 二次求值。secret 仍应另外纳入
组织密码管理。

## 3. 升级

升级只接受远端 env.sh 中五个完整、唯一、非空且使用规范单引号编码的既有 secret；升级路径绝不生成、
补齐或猜测密码。读取结果超过有界输出、重复、缺失或畸形均失败。数据库密码只经受限 stdin 交给
远端 `psql`，不会进入本机、SSH、Docker 或远端进程 argv。HTTPS 不传新证书时会用权威密码真实打开
现有 `conf/ssl/teamtalk.p12` 的 `mykey` 私钥条目；成对传入时则先在本地完成 PEM 转换与 PKCS12
私钥校验。随后在任何停服或覆盖动作前，只读比较远端 data marker、
PostgreSQL schema marker 与当前源码声明的服务端 epoch。
任一 marker 缺失、不可读或不一致都会 fail-fast，部署工具不会自动删除数据。

预检通过后，升级以同一个远端独占锁执行完整事务边界：先上传到唯一 `.release-<uuid>` 目录并验证
可执行入口、server jar 与精确 build identity，再把当前分发、env、TLS、compose 和 systemd unit 快照到
唯一 `.rollback-<uuid>`；运行数据、日志和客户端下载不复制。随后只通过 systemd 停止服务，并确认该
unit 的 MainPID 为 0 且 cgroup 不再活动，才把已验证分发发布到 live 目录。新实例必须通过固定 9 项
健康契约和精确新 build identity 后才提交并删除回滚快照。停服后的任何配置、启动或健康失败都会
恢复旧分发、env、TLS、compose 与 unit，并以切换前捕获的 HTTP/HTTPS 端口和旧 build identity 验证
旧实例重新健康；若回滚健康无法证明，则保留快照并明确失败，不会宣称部署完成。

运行态路径必须排除覆盖/删除：

- `data/`
- `conf/env.sh`
- `conf/ssl/`
- 实例日志
- 实例生成的 docker 数据
- `static/downloads/` 中独立发布的客户端产物

分发同步使用 `rsync --delete`，因此已经从新分发移除的旧 jar、脚本和静态资源不会残留在运行
classpath；上述运行态路径使用锚定 exclude 保留。升级和空数据部署的 staging 上传把未完成文件保留在
目标 staging 目录内；SSH 传输、rsync 数据流、连接超时或前次远端上传仍在收尾时，会在同一个部署租约
和 staging 路径内有界续传。其他 rsync 非零退出立即使部署失败，不继续注册或启动服务。

所有 SSH、mkdir、rsync/scp、Docker、systemd、curl 和本地打包进程都有总超时并检查退出码；只读
探测只接受声明过的退出码，连接失败不能伪装成“不存在”。只有残留进程与未发布临时文件清理属于
显式 best-effort，失败会警告但不覆盖原始错误。客户端发布要求唯一、非空的必需产物，缺失、空文件、
选择歧义或零上传都会失败，不能打印成功。

部署事务的 `.rollback-<uuid>` 只覆盖本次二进制/配置切换，不包含 PostgreSQL、RocksDB、文件对象或
客户端下载，不能替代数据备份；它只在新版本健康或旧版本恢复健康后清理。远端独占锁覆盖状态探测、
上传、切换、回滚和健康检查，防止两个发布任务交错写同一实例。独占 owner 锁由一条长生命周期 SSH
会话持有；本地 Gradle 正常退出、被 SIGKILL 或网络断开都会关闭控制通道，并由内核自动释放 flock，
不依赖 JVM `finally` 删除目录。锁文件本身可以保留，但只有内核锁状态代表所有权，不需要也不得按
文件存在时间宽泛清理。

每条远端命令和每次服务端 rsync 上传还会在完整执行期独占 operation drain 锁，并核对当前部署
代次和 owner 锁。新控制器取得 owner 后必须先独占探测 drain；若上一次控制器崩溃时已有上传或命令
仍在收尾，本次部署会立即失败并要求稍后重试。尚未抵达远端的旧命令则会被代次 fencing 拒绝。因此
控制连接失效不会留下永久互斥锁，也不会为了消除永久锁而允许孤儿操作与下一次部署重叠。
rsync 的远端入口在 READY 前原子发布为本代唯一、仅 owner 可读的脚本，并只通过 `/bin/sh` 加无
特殊字符路径调用；这既兼容会拆分 `--rsync-path` 的旧客户端和不可执行的锁目录，也保证延迟抵达的
上一代上传不能借用新的租约。

### 破坏性空数据部署

默认 `im.virjar.com` 的无条件清空授权已撤销。下面的命令是独立的破坏性维护入口，不是处理升级失败的
默认办法；必须先取得本次针对确切实例和资料范围的明确授权，并记录备份/恢复或有意放弃数据的决定。

预发布 epoch 或持久化格式不兼容时，不要手工先删除 PostgreSQL 或 `data/` 再运行 `deployServer`：手工
删除一侧会形成必须拒绝的半安装，删除两侧 marker 也会丢失“这是哪个完整安装”的安全证据。只对已明确
允许丢弃全部服务端数据的实例使用独立任务，并让任务自己在部署租约内完成双侧重建：

```bash
./gradlew deployServerResetData \
  '-Pteamtalk.resetDeployConfirm=<已明确授权的主机>:<部署目录>'
```

确认值不是通用口令，必须逐字符等于当前 `gradle/deployment.json` 的
`<deployHost>:<deployPath>`；不 trim、不接受 SSH user/port、别名主机、尾随 `/` 或相邻目录。任务还会在
远端拒绝 symlink/非同一 physical path，只接受分发、`conf/env.sh`、compose、systemd unit、data epoch、
dataset identity 都存在的完整既有安装。空目标和半安装必须人工查明来源，不能把本任务当首次部署或
清残留工具。

同一个 owner lease 和 operation fencing 覆盖以下完整顺序：

1. 读取既有 secret/TLS 并检查精确目标；把新分发上传到唯一 staging 目录，校验入口、server jar 和完整
   build identity。完成这些步骤前不停止任何服务。
2. 快照旧二进制、env、TLS、compose 和 systemd unit（**不包含任何 server data**），然后严格停止
   TeamTalk，执行该安装的 `docker compose down --remove-orphans`。
3. 只对精确 canonical `<deployPath>/data` 执行一次递归删除；删除前再次核对 deploy root 与 data 都不是
   symlink 且 physical path 精确相同。该目录同时包含 PostgreSQL bind mount、RocksDB、Lucene、客户端
   遥测索引、FileStore、临时附件和日志，随后按当前布局从空目录重建。
4. 发布已验证分发，原子更新 `conf/env.sh`；未提供新 PEM 时保留并预检现有 TLS，提供成对 PEM 时更新
   keystore；更新 compose 后启动 PostgreSQL并等待 `pg_isready`，配置数据库角色，再注册/启动 systemd。
5. 只有固定健康组件全部 `UP` 且 `/health.buildIdentity` 精确等于新 artifact identity 才提交成功。所有旧
   token、账户、组织、消息、文档、附件和其他服务端数据均永久失效，客户端必须清缓存并重新注册/登录。

staging 或快照阶段失败不会开始清数据。停服后但删除前失败会恢复旧发布并验证旧 identity；一旦删除已经
开始，失败恢复只能再次清空同一个 data 目标，并尝试让旧二进制在**新的空数据集**上恢复健康。成功消息
也只能声明“旧发布 + 空数据”健康，绝不声明旧数据已恢复；若健康无法证明，则保留不含数据的 release
快照供诊断并失败关闭。因此该任务不是备份/恢复方案，也不得用于需要保留数据的正式实例。

### 数据代际（epoch）与重置纪律

服务端 schema/data epoch 以 `ServerDataEpoch.CURRENT_EPOCH` 为唯一事实源，不在文档复制数值或维护
逐代历史台账。空库的 `schema_metadata` 生成一个 canonical datasetId，并在任何本地持久化
store 打开前原子写入 `data/dataset-id`。启动与升级预检都要求两侧身份相等；只恢复 PostgreSQL 或只
恢复 `data/` 会失败关闭。完整重建、备份恢复或时间点恢复在重新开放流量前必须在停服状态生成一个
新的 canonical UUID，并用同一个值更新 `schema_metadata.dataset_id` 与原子替换 `data/dataset-id`。
中途崩溃只会留下拒绝启动的 mismatch，可用同一个新值重做两侧，不得在运行中轮换或让启动自动猜测。
客户端据此拒绝把旧数字游标和本地可靠操作拼入新事实源。

空实例按当前定义建库；已有 epoch 内的 PostgreSQL 变化通过 `schema_migrations` 顺序迁移。
零号迁移仅扩大遥测协议 ID 约束，保留现有记录与 dataset；执行和回滚边界见
[持久化规则](../06-server/persistence.md#postgresql-有序迁移)。版本下限另从目标服务端产物清单核对，
升级保留远端显式 `MINIMUM_PROTOCOL_MINOR`，越界在停服前拒绝。

未知历史布局、其他存储格式或未来跨 epoch 变化必须先提供专门迁移与恢复方案，不能默认清空旧实例。
部署的二进制回滚也不代替数据库回退：后续每条迁移都须说明旧服务端能否继续读取升级后的数据，
否则必须准备恢复路径再部署。
