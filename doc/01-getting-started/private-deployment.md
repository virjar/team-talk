# 私有化部署

TeamTalk 的部署目标是：独立 clone 可以通过一份本机部署配置和一组私密凭据，构建自己的客户端、
部署自己的服务端并对同一目标运行验收。私有客户端具有自己的安装身份和名称，可以与公版在同一台
电脑或 Android 设备分别安装、运行和保存资料。

执行构建/部署的机器需要 JDK 21。Gradle 自动下载固定版本的 Node.js 及随包 npm，并由
`:server:admin` 在隔离的 `server/admin/build` 工作区中按锁文件构建管理后台；首次构建需要访问
Node.js 分发站点和 npm 包仓库。常规构建和运行已构建服务端都不需要全局安装 Node.js。
构建产物与服务端分发的衔接见[部署与升级](../07-operations/deployment.md#1-部署任务)。

## 1. 准备服务器

推荐基线：

- Linux x86_64 或 arm64
- JDK 21
- Docker 与 Docker Compose
- 客户端可以访问的服务器 IP；已有域名也可使用
- 对客户端开放配置的 HTTP(S) 端口与 `tcpAddress` 的 TLS/TCP 端口（默认 5100）
- 具备 SSH/rsync 权限的部署账号

Linux 服务器处理视频缩略图时，还需安装 FFmpeg JNI 所依赖的系统运行库；JNI 本身随服务端 JAR
提供，无需实际声卡或运行音频服务。Ubuntu 24.04 的包清单与缺库定位见
[Linux 媒体测试环境](../09-testing/local-tests.md#linux-媒体测试环境)，运行媒体验收的 Linux 机器也需准备。

服务端安装和运维仍由管理员本机的 SSH、rsync、OpenSSL 完成。Windows 支持统一产物构建与客户端
SFTP 发布，不代表这些 Linux 服务端运维任务也已移除 Unix 工具依赖。

低门槛路径采用 HTTP 站点与自签 TLS/TCP：Gradle 生成 TCP 证书，客户端内嵌公共证书并校验服务器 IP，
无需先准备域名或购买 HTTPS 证书。已有域名和受信任证书时也可以使用 HTTPS + TLS/TCP，组合规则见
[传输配置边界](../07-operations/configuration.md#传输配置边界)。

单组织万级用户是当前架构边界。容量规划需结合在线连接数、消息速率、附件大小、DIAGNOSTIC 命中连接数、每连接 trace 事件率和独立索引预算，不应
只按注册用户数估算。

## 2. 配置部署坐标与客户端身份

主仓库用于公版产品开发，提交的 `buildSrc/deployment/Deployment.kt` 始终指向 `im.virjar.com`。私有构建和
部署在另一个 clone 完成，例如：

```bash
git clone https://github.com/virjar/team-talk.git team-talk-private
cd team-talk-private

# 203.0.113.10 是文档占位；替换为自己的实际服务器 IP。
./gradlew generateTcpTlsCertificate -PtcpCertificateHost=203.0.113.10
mkdir -p buildSrc/deployment-local
cp buildSrc/deployment/Deployment.kt buildSrc/deployment-local/Deployment.kt
```

先生成证书，再编写引用它的 local 配置，避免 Gradle 配置阶段读取尚不存在的文件。证书位于被 Git
忽略的 `gradle/tcp-tls/certificate.pem`，私钥位于同目录 `private-key.pem`；重复运行任务只校验并保留
已有材料，不自动换证或覆盖密钥。也可以使用管理员已有且 SAN 匹配 TCP 主机/IP 的证书和私钥。

随后编辑 `buildSrc/deployment-local/Deployment.kt`，把公版坐标和身份换成自己的值。整个 local 目录
被 Git 忽略，不用建私有分支或提交；更新源码时保留本地配置与签名材料。`buildSrc` 只编译这一套配置，
根构建调用 `deploymentConfiguration(rootDir)` 获取对象；语法、类型或构造器校验失败会停止构建。
配置按 `deployment { server { ... }; deploy { ... }; client { ... } }` 分章节，可用同目录 Kotlin
辅助函数拆分。创建 local 目录后重新同步 Gradle，让 IDE 更新源码目录。完整示例见
[运行配置](../07-operations/configuration.md#客户端发行身份)。按需设置：

- `server.http.url`：必填的客户端 HTTP(S) 根地址，不带 `/api`；IP 部署可直接配置 `http://<服务器IP>`。
- `server.tcp`：`host` 默认跟随 HTTP URL 主机，`port` 默认 5100；端口会成为实例的 `TCP_PORT`。
- `deploy.ssh`：`host` 默认跟随 HTTP URL 主机，`port` 默认 22，`user` 默认 `root`；SSH 入口不同再单独设置。
- `deploy.directory`：远端安装根目录，默认 `/opt/teamtalk`。必须是已经规范化的非根 POSIX 绝对路径；
  每个路径段都必须是普通安全名称，`.`、`..`、重复分隔符和尾随 `/` 会在任何远端操作前被拒绝。
- `server.tcp.tls.certificateFile`：指向公共 `certificate.pem` 的 `File`，不要指向私钥；构建时读取并验证，
  文件不存在或不可读时直接失败。
- `client.identity`：私有客户端的稳定应用标识、显示名称和英文安装名称；与公版共存时三者一起配置，
  完整示例和派生规则见[客户端发行身份](../07-operations/configuration.md#客户端发行身份)。

HTTPS 监听端口从 `server.http.url` 自动推导，不再单独填写 `sslPort`；HTTP 模式不启用 HTTPS connector。
首次分发前选定 `client.identity.applicationId`、`client.identity.desktopName` 和持续使用的签名，后续升级保持它们
稳定；显示名称可以调整。新私有版首次安装后独立登录，Android 沙箱与 Desktop 数据根均独立，
无需让每位用户手动指定工作目录，也不把公版数据复制过去。私有 clone 继续从同一套 Gradle 任务出包；
展示版本与安装序号仍只在根 `gradle.properties` 维护，不能通过本地部署配置临时改写版本。

HTTP 地址、TCP 地址和 SSH 主机可以不同，分别用 `server.tcp.host`、`deploy.ssh.host` 覆写后两者。
附件消息只存服务端相对路径；客户端用最终 `serverUrl`
解析为下载地址，因此构建客户端前必须确认它指向自己的实例。
TCP 证书的 SAN 必须包含实际连接的 IP 或主机名。HTTP 模式不校验 HTTPS 证书；选择 HTTPS 模式时，
HTTP 与 TCP 共用证书，该证书还必须覆盖 HTTP 主机名并被 HTTP 客户端信任。私有 TCP 证书配置只改变
TCP TrustStore，不把自签证书装进操作系统全局信任。部署会从坐标生成 `TCP_PORT`。

## 3. 提供 Secret

服务端运行凭据放在不提交的 `gradle/deployment.secrets` 中；服务器部署不交给发行 CI。至少包括：

- SSH 私钥或等价认证材料
- PostgreSQL 口令
- 管理后台凭据
- TLS keystore/证书所需密码

自动生成的 `SSL_KEYSTORE_PASSWORD` 和 `SSL_PRIVATE_KEY_PASSWORD` 使用同一个值，以便 Java
PKCS12 同时加载 keystore 和私钥。如果手工管理 `deployment.secrets`，两项也必须保持一致；
部署工具发现已有值不一致时会在修改远端前拒绝，不会自动改写现有实例的密码。

部署工具会把数据库、管理后台和 TLS 的全部实际凭据持久化到 `deployment.secrets`；即使文件原本
存在，后续补生成的字段也会原子写回。该文件必须是 owner-only 的普通文件，符号链接目标会被拒绝。
远端 `env.sh` 使用不会展开 `$`、反引号、反斜线或引号的 POSIX 字面量编码。

不要把真实 secret 写入 Markdown、任何部署 Kotlin 源码、Gradle 命令历史或构建产物名称。
本机覆写不入 Git，但解析结果会进入发行配置快照，不能把它当作秘密存储。

## 4. 首次部署

在已经准备好 local 配置和 TCP 证书的私有 clone 中执行：

```bash
./gradlew deployServer \
  -PsslCert=gradle/tcp-tls/certificate.pem \
  -PsslKey=gradle/tcp-tls/private-key.pem
```

两个路径可以是仓库根目录下的相对路径，也可以是仓库外的绝对路径；不要使用未展开的 `~`。
证书应是 X.509 PEM（有颁发链时包含完整链），私钥为可由 OpenSSL 非交互读取的 PEM。`sslCert` 和
`sslKey` 必须成对传入；空值、单边参数、目录或不存在的文件都会被拒绝。HTTP + TLS/TCP 的首次安装
同样提供这两个参数：生成的 PKCS12 只保护 TCP，HTTP 站点继续使用明文。选择 HTTPS 时该 keystore
同时用于 HTTPS 与 TCP，HTTP 客户端的信任仍按[传输配置边界](../07-operations/configuration.md#传输配置边界)判断。

部署工具会先在本机将 PEM 转为临时 `teamtalk.p12`，再以服务端实际密码读取私钥，然后才
允许创建目录、上传、停服或覆盖。参数、文件、PEM 转换或 PKCS12 校验失败时，目标实例不会被
修改；为了判断首次部署或升级，任务仍可能执行只读 SSH 探测。原始 PEM 私钥不会上传，本机临时
PKCS12 会在上传或部署失败后清理。

任务会构建服务端分发包、创建远端目录、上传静态和可执行文件并配置运行服务。远端典型结构：

```text
/opt/teamtalk/
├── bin/                 启动脚本和服务端分发
├── conf/                env.sh、TLS 等私密配置
├── data/                RocksDB、Lucene 消息索引与7日遥测日志、文件和服务端日志
├── static/              首页与客户端安装包
└── docker-compose.yml   PostgreSQL
```

`data/`、`conf/env.sh`、`conf/ssl/`、运行日志和 `static/downloads/` 是实例状态。升级会用
`rsync --delete` 清除旧分发文件和过期 jar，但这些运行态路径始终由锚定的 exclude 规则保护；同步失败
会终止部署，不能用仍可启动的旧进程冒充升级成功。

## 5. 验收

先检查健康状态，再跑业务验收：

```bash
curl http://203.0.113.10/health
./gradlew :server:server:acceptanceTest
```

验收目标同样使用部署配置函数返回的对象，因此不会出现“部署到 A、测试 B”的配置漂移。失败时不要
只看 HTTP 200；部署任务本身还要求总体状态和 postgres、rocksdb、lucene、sync-event-dispatcher、
message-projection、managed-chat-projection、client-telemetry、file-storage、tcp 固定 9 项全部为 `UP`。随后应结合验收报告、服务端 trace、
客户端 fault 和目标实例数据判断。
上面的 IP 仍是文档占位，实际使用自己的 `serverUrl`。TLS 部署的 `tcp` 健康项会以 keystore 当前叶证书
作为唯一信任锚，对实际监听端口执行真实 TLS 握手（通配 bind 从本机 loopback 回连）；它不是只检查
socket 可连接。客户端另行使用系统 WebPKI 或配置的单证书 TrustStore，并严格校验主机/IP，
因此健康为 `UP` 不能代替外部客户端证书与 SAN 验收。

## 6. 发布客户端

新的私有应用如果只需要以当前版本提供首批测试安装包，使用[首次私有分发](../07-operations/releasing.md#保持当前版本的首次私有安装包分发)：
先登记并提交独立协议契约，再在私有 clone 运行 `release -PreleaseMode=private-first -PreleaseTargets=site`。
这保留根版本和公版发行记录，适用于空下载入口；不能用来覆盖已有私有安装包。

先在根版本配置中命名本次发行，提交人工发布说明与协议发行快照，并准备持续使用的客户端签名。
没有 GitHub 也遵守这些本地事实，具体步骤见[统一发行流程](../07-operations/releasing.md)。

```bash
./gradlew release
./gradlew release -PreleaseTargets=site
```

第一条命令只密封本地发行目录；第二条复用该目录并通过 JVM SFTP 发布两端下载入口，需提前提供
`TEAMTALK_RELEASE_SSH_KEY` 与 `TEAMTALK_RELEASE_KNOWN_HOSTS` 文件路径。Windows 使用 `.\gradlew.bat`
同名任务，本机不需要 rsync/scp。站点任务不会部署 Server ZIP 或重启实例。
这两类交付都允许 local 覆写；GitHub 发布会拒绝存在 local 覆写的构建。源码、版本、协议快照和人工
发布说明仍须匹配并已提交，不能把 Git 忽略的部署配置当作跳过发行校验的入口。

客户端内嵌构建时的服务坐标和完整 build identity。Gradle 管理 Conveyor 工具，在单个构建机生成三平台
Desktop 站点，同时构建 Android APK 与 Server ZIP。签名与平台边界见
[Desktop 制品构建](../07-operations/desktop-cross-build.md)；交叉构建成功不能替代目标平台安装检查。
私有版从自己的服务站点提供 Android APK 下载，Desktop Conveyor 更新源从同一 `serverUrl` 推导，
不再维护单独的更新站点配置。Android 当前提示协议升级，由用户从站点下载安装包。
首次交付至少检查公版与私有版同时安装、分别登录、重启后资料保留、通知与附件打开正确应用；
已有私有版升级时再检查安装身份和签名连续、升级后资料保留。Windows 安装与更新须在 Windows 实际验证。
少量内测可按[开发者预览版指南](developer-preview.md)先收敛目标平台与短路径验收。

## 7. 升级与回滚

当前项目未承诺数据结构向后兼容。升级前必须：

1. 阅读目标提交的数据库、协议和缓存变更。
2. 备份 PostgreSQL、`data/` 和 `conf/`。
3. 在测试实例部署并执行真实业务验收。
4. 再升级正式实例并观察健康、认证、消息、附件与日志。

升级任务会在停服和覆盖文件前，只读比较目标实例的 `data/data-epoch`、`data/dataset-id`、PostgreSQL
`schema_metadata` 与本次构建的 `ServerDataEpoch.CURRENT_EPOCH`，并要求 PostgreSQL/local dataset
identity 完全相同。任一值缺失、不可读或不一致都会
直接拒绝升级；先恢复数据库可用性、匹配的完整数据集或实现经评审的迁移。普通部署不允许清空
PostgreSQL 与 `data/` 来绕过预检，破坏性重建需要独立授权。

HTTP + TLS/TCP 和 HTTPS 升级均遵循以下 TLS 参数规则：

- 两个参数都不传：保留现有 `conf/ssl/teamtalk.p12`；部署会在停服前只读确认它非空且可读。
- 两个参数成对传入：先在本机完成上述预检，再部署指定证书；普通升级继续使用原证书与私钥。
- 只传一个、传空值，或首次启用 TLS 时既无新证书也无可复用 PKCS12：在任何远端变更前拒绝。

生成任务不自动轮换已有证书。需要更换证书时，先安排客户端公共证书信任的迁移与回退，不能直接
重签服务器后让已安装的客户端失去连接。

仅有明确实例与资料范围授权的独立重建任务才可清理对应测试数据；内测与生产资料都不依赖清库升级。详细运行
步骤见[部署与升级](../07-operations/deployment.md)。
某次首次接管私有服务器时允许清理旧安装，不代表后续可随意清理该节点。邀请长期使用者后，普通升级
仍须保留资料；未来重建需要重新明确实例、数据范围和恢复方案。
