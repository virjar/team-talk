# 私有化部署

TeamTalk 的部署目标是：独立 clone 可以通过一份本机部署配置和一组私密凭据，构建自己的客户端、
部署自己的服务端并对同一目标运行验收。私有客户端具有自己的安装身份和名称，可以与公版在同一台
电脑或 Android 设备分别安装、运行和保存资料。

本页是完整参考；急用时可先看下面的总览按顺序走，每一步都有对应章节展开。不懂技术但有
AI 助手（如 WorkBuddy）的部署者，从[AI 辅助部署](ai-assisted-deployment.md)进入——
它会从买服务器开始带你走完本页全部环节；试用后转向正式运营的加固清单见
[从试用到生产](trial-to-production.md)。

```text
① 准备服务器（Linux + JDK21 + Docker）      → 本文 §1
② 配置部署坐标与自签证书                      → 本文 §2
③ 提供 Secret 并首次部署                      → 本文 §3-4（约 10-30 分钟，含构建）
④ 首次进入管理后台并修改管理员密码            → 本文 §5（重要，勿跳过）
⑤ 发布客户端安装包到下载首页                  → 本文 §8
⑥ 用户访问 http://<服务器IP>/ 下载客户端，自行注册即用
```

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
HTTP 注册中心发布，不代表这些 Linux 服务端运维任务也已移除 Unix 工具依赖。

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

`buildSrc/deployment-local/` 是私有部署的本机状态目录：私有配置 `Deployment.kt`、部署凭据
`deployment.secrets`、TCP TLS 材料 `tcp-tls/` 与 OEM 推送 `vendor/` SDK 都在其中。不同团队对仓库的
唯一差异就是该目录的内容，交接或备份部署时整体拷贝这一个目录即可。公版 clone 的生成状态
（凭据、TLS 材料）不落在这里，而是与公版配置同放在 `buildSrc/deployment/` 下（除
`Deployment.kt` 外全部被 Git 忽略）。

先生成证书，再编写引用它的 local 配置，避免 Gradle 配置阶段读取尚不存在的文件。证书位于被 Git
忽略的 `buildSrc/deployment-local/tcp-tls/certificate.pem`，私钥位于同目录 `private-key.pem`；重复运行
任务只校验并保留已有材料，不自动换证或覆盖密钥。也可以使用管理员已有且 SAN 匹配 TCP 主机/IP
的证书和私钥，放入同一目录。

从旧布局迁移：已有私有 clone 若把材料放在 `gradle/tcp-tls/` 与 `gradle/deployment.secrets`，同步本
版本后把两者移动到 `buildSrc/deployment-local/` 下的同名位置，并在自己的 `Deployment.kt` 中改用
`tcpTlsCertificateFile(rootDir)`。升级部署以远端 `env.sh` 为权威回写凭据，本地旧 secrets 文件缺失
不会重置任何密码。

随后编辑 `buildSrc/deployment-local/Deployment.kt`，把公版坐标和身份换成自己的值。整个 local 目录
被 Git 忽略，不用建私有分支或提交；更新源码时保留本地配置与签名材料。目录里没有 `Deployment.kt`
时仍按公版配置编译，公版 clone 的生成状态（凭据、TLS 材料）放在 `buildSrc/deployment/` 下，
不会误切私有配置。

从旧布局迁移（公版 clone）：曾按旧约定把 `deployment.secrets` / `tcp-tls/` 存放在
`buildSrc/deployment-local/` 的公版 clone，把它们移动到 `buildSrc/deployment/` 下的同名位置并删除
空的 deployment-local 目录；升级部署以远端 `env.sh` 为权威回写凭据，移动文件不重置任何密码。`buildSrc` 只编译这一套配置，
根构建调用 `deploymentConfiguration(rootDir)` 获取对象；语法、类型或构造器校验失败会停止构建。
配置按 `deployment { server { ... }; deploy { ... }; client { ... } }` 分章节，可用同目录 Kotlin
辅助函数拆分。创建 local 目录后重新同步 Gradle，让 IDE 更新源码目录。完整示例见
[运行配置](../07-operations/configuration.md#客户端发行身份)。按需设置：

- `server.http.url`：必填的客户端 HTTP(S) 根地址，不带 `/api`；IP 部署可直接配置 `http://<服务器IP>`。
- `server.tcp`：`host` 默认跟随 HTTP URL 主机，`port` 默认 5100；端口会成为实例的 `TCP_PORT`。
- `deploy.ssh`：`host` 默认跟随 HTTP URL 主机，`port` 默认 22，`user` 默认 `root`；SSH 入口不同再单独设置。
- `deploy.directory`：远端安装根目录，默认 `/opt/teamtalk`。必须是已经规范化的非根 POSIX 绝对路径；
  每个路径段都必须是普通安全名称，`.`、`..`、重复分隔符和尾随 `/` 会在任何远端操作前被拒绝。
- `server.tcp.tls.certificateFile`：指向公共 `certificate.pem` 的 `File`，推荐用 `tcpTlsCertificateFile(rootDir)`
  引用 local 目录内的证书，不要指向私钥；构建时读取并验证，文件不存在或不可读时直接失败。
- `client.identity`：私有客户端的稳定应用标识、最终 Android 包名、显示名称和英文安装名称；与公版共存时一起配置，
  完整示例和派生规则见[客户端发行身份](../07-operations/configuration.md#客户端发行身份)。

HTTPS 监听端口从 `server.http.url` 自动推导，不再单独填写 `sslPort`；HTTP 模式不启用 HTTPS connector。
首次分发前选定 `client.identity.applicationId`、`client.identity.androidApplicationId`、`client.identity.desktopName`
和持续使用的签名，后续升级保持它们
稳定；显示名称可以调整。新私有版首次安装后独立登录，Android 沙箱与 Desktop 数据根均独立，
无需让每位用户手动指定工作目录，也不把公版数据复制过去。私有 clone 继续从同一套 Gradle 任务出包；
展示版本与安装序号仍只在根 `gradle.properties` 维护，不能通过本地部署配置临时改写版本。
新部署可显式选择不带 `.android` 后缀的 Android 包名；已有配置不填新字段仍使用历史派生包名。
已分发应用不能通过去掉后缀进行升级，补填时应使用原完整包名。

HTTP 地址、TCP 地址和 SSH 主机可以不同，分别用 `server.tcp.host`、`deploy.ssh.host` 覆写后两者。
附件消息只存服务端相对路径；客户端用最终 `serverUrl`
解析为下载地址，因此构建客户端前必须确认它指向自己的实例。
TCP 证书的 SAN 必须包含实际连接的 IP 或主机名。HTTP 模式不校验 HTTPS 证书；选择 HTTPS 模式时，
HTTP 与 TCP 共用证书，该证书还必须覆盖 HTTP 主机名并被 HTTP 客户端信任。私有 TCP 证书配置只改变
TCP TrustStore，不把自签证书装进操作系统全局信任。部署会从坐标生成 `TCP_PORT`。

## 3. 提供 Secret

服务端运行凭据放在不提交的 `buildSrc/deployment-local/deployment.secrets` 中，与私有配置同目录；
服务器部署不交给发行 CI。至少包括：

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
  -PsslCert=buildSrc/deployment-local/tcp-tls/certificate.pem \
  -PsslKey=buildSrc/deployment-local/tcp-tls/private-key.pem
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

## 5. 首次进入管理后台（部署后立即做）

部署完成后，管理后台已经随服务端一起就绪，但不会主动通知你——这一节是很多新部署者漏掉的关键步骤。

1. **找到管理员凭据**：打开本地 `buildSrc/deployment-local/deployment.secrets`，其中 `ADMIN_USER`
   和 `ADMIN_PASSWORD` 是首次部署时自动生成的随机凭据（同值也写在服务器
   `$deployPath/conf/env.sh`，文件权限 600）。
2. **登录**：浏览器打开 `http://<服务器IP>/admin`（HTTPS 部署则为 `https://<服务器IP>/admin`），
   用上述凭据登录。会话 12 小时有效。
3. **立即修改管理员密码**：进入左侧「管理安全」页，先验证当前密码再设置新密码（6-72 字符，
   新旧不同）。修改后所有管理员会话失效。默认密码是随机生成的，但它在本地文件里明文保存过，
   改掉它是一元成本的稳妥习惯。

管理台页面地图（左侧菜单）：

| 页面 | 用途 |
|---|---|
| Dashboard | 运行指标与版本信息总览 |
| 用户 | 查询用户、封禁/解封、重置密码、查看离职资产 |
| 组织架构 | 单组织树与受管部门群 |
| 通知机器人 | 创建群通知机器人（Webhook 入站） |
| 消息 / 日志 / 群组 | 消息审计、服务端日志、群管理 |
| 管理安全 | 改密码、会话吊销、操作审计 |
| 客户端发布 | 管理各平台客户端发布通道（stable/preview/snapshot） |
| 服务号 | 欢迎语模板与全员广播 |
| 系统设置 | 功能开关（如文档导出） |

## 6. 用户如何开始使用

- 客户端安装包发布后（见[发布客户端](#8-发布客户端)），用户浏览器打开 `http://<服务器IP>/`
  即可看到下载首页，提供 macOS（双架构）/Windows/Linux/Android 安装包。
- **注册是开放的**：任何能访问服务器地址的人都可以在客户端内直接注册账号开始使用，没有邀请码
  或审批环节。公网部署请自行评估暴露面：测试期建议用防火墙限制来源 IP，或使用非公开地址。
- 私有客户端已内置服务器地址，用户无需任何配置，装好即用。

## 7. 验收

先检查目标实例健康状态：

```bash
curl http://203.0.113.10/health
```

真人使用的实例随后核对下载入口并复验约定的用户流程，不自动注册验收账号。
`./gradlew :server:server:acceptanceTest` 在独立测试实例执行；实例选择与测试资料处理见
[测试数据归属](../09-testing/deployment-acceptance.md#测试数据归属与真人内测实例)。
验收目标同样使用部署配置函数返回的对象，因此不会出现“部署到 A、测试 B”的配置漂移。失败时不要
只看 HTTP 200；部署任务要求总体状态及所有关键组件全部为 `UP`，包括后台维护任务的存活状态。
关键维护任务意外退出时 `/health` 返回 `DOWN` / HTTP 503，具体组件见[健康检查](../07-operations/observability.md)。
随后应结合验收报告、服务端 trace、客户端 fault 和目标实例数据判断。
上面的 IP 仍是文档占位，实际使用自己的 `serverUrl`。TLS 部署的 `tcp` 健康项会以 keystore 当前叶证书
作为唯一信任锚，对实际监听端口执行真实 TLS 握手（通配 bind 从本机 loopback 回连）；它不是只检查
socket 可连接。客户端另行使用系统 WebPKI 或配置的单证书 TrustStore，并严格校验主机/IP，
因此健康为 `UP` 不能代替外部客户端证书与 SAN 验收。

## 8. 发布客户端

新的私有应用如果只需要以当前版本提供首批测试安装包，使用[首次私有分发](../07-operations/releasing.md#保持当前版本的首次私有安装包分发)：
审阅并提交源码，在私有 clone 运行 `release -PreleaseMode=private-first -PreleaseTargets=site`。
这保留根版本和公版发行记录，不冻结待发布 minor，适用于独立安装身份和空下载入口；已有私有包的手动更新走 snapshot。

正式产品发行时，在根版本配置中设置发行版本，提交人工发布说明与协议发行快照，并准备持续使用的客户端签名。
没有 GitHub 也遵守这些本地事实，具体步骤见[统一发行流程](../07-operations/releasing.md)。

```bash
./gradlew release
./gradlew release -PreleaseTargets=site
```

第一条命令只密封本地发行目录；第二条复用该目录，通过 HTTP 发布注册中心上传各平台客户端。
服务端需要配置 `CLIENT_RELEASE_PUBLISH_TOKEN`，构建机通过 `TEAMTALK_CLIENT_RELEASE_TOKEN` 提供同一令牌。
Windows 使用 `.\gradlew.bat` 同名任务，客户端发布不需要 SSH、rsync 或 scp。
站点任务不会部署 Server ZIP 或重启实例，详见[客户端发布与更新](../07-operations/client-releases.md)。
这两类交付都允许 local 覆写；GitHub 发布会拒绝存在 local 覆写的构建。源码、版本、协议快照和人工
发布说明仍须匹配并已提交，不能把 Git 忽略的部署配置当作跳过发行校验的入口。

客户端内嵌构建时的服务坐标和完整 build identity。Gradle 在单个构建机生成 macOS 双架构、Windows 与
Linux 的桌面壳、负载和安装包，同时构建 Android APK、Server 与 Headless ZIP。签名与平台边界见
[Desktop 制品构建](../07-operations/desktop-cross-build.md)；交叉构建成功不能替代目标平台安装检查。
私有版的首页下载区、Android APK 与 Desktop 更新注册中心均来自配置的 `serverUrl`，
无需单独配置更新站点。Desktop 使用应用内文件级更新；Android 检查更新后由用户下载安装 APK。
从旧 Conveyor 客户端升级需手动安装新完整包一次，原身份与资料保留边界见
[客户端迁移](../07-operations/client-releases.md#7-迁移说明从-conveyor)。
首次交付至少检查公版与私有版同时安装、分别登录、重启后资料保留、通知与附件打开正确应用；
已有私有版升级时再检查安装身份和签名连续、升级后资料保留。Windows 安装与更新须在 Windows 实际验证。
少量内测可按[开发者预览版指南](developer-preview.md)先收敛目标平台与短路径验收。

## 9. 升级与回滚

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
重建授权只适用于明确的实例和资料范围；后续普通升级仍保留资料，每次破坏性重建独立核对范围与恢复方案。
