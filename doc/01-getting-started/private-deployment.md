# 私有化部署

TeamTalk 的部署目标是：一个 fork 可以通过一份公开坐标配置和一组私密凭据，构建自己的客户端、
部署自己的服务端并对同一目标运行验收。

执行构建/部署的机器需要 JDK 17。Gradle 自动下载固定版本的 Node.js 及随包 npm，并由
`:server:admin` 在隔离的 `server/admin/build` 工作区中按锁文件构建管理后台；首次构建需要访问
Node.js 分发站点和 npm 包仓库。常规构建和运行已构建服务端都不需要全局安装 Node.js。
构建产物与服务端分发的衔接见[部署与升级](../07-operations/deployment.md#1-部署任务)。

## 1. 准备服务器

推荐基线：

- Linux x86_64 或 arm64
- JDK 17
- Docker 与 Docker Compose
- 可用域名和 TLS 证书
- 对客户端开放 HTTPS 端口与 `tcpAddress` 配置的 TLS/TCP 端口（默认 5100）
- 具备 SSH/rsync 权限的部署账号

这里采用当前远程客户端已经接通的 HTTPS + TLS/TCP 安装路径。服务运行时允许明文，但 SDK 和部署
工具仍有各自限制；选择其他组合前先看[传输配置边界](../07-operations/configuration.md#传输配置边界)。

单组织万级用户是当前架构边界。容量规划需结合在线连接数、消息速率、附件大小、DIAGNOSTIC 命中连接数、每连接 trace 事件率和独立索引预算，不应
只按注册用户数估算。

## 2. 配置部署坐标

修改 `gradle/deployment.json`：

- `serverUrl`：客户端访问的 HTTP(S) 根地址，不带 `/api`；本节的远程客户端示例使用 HTTPS。
- `tcpAddress`：客户端 IM TCP 地址，格式为 `host:port`；端口会成为实例的 `TCP_PORT`。
- `deployHost` / `deployPort` / `deployUser`：Gradle 部署任务使用的 SSH 目标。
- `deployPath`：远端安装根目录，默认 `/opt/teamtalk`。必须是已经规范化的非根 POSIX 绝对路径；
  每个路径段都必须是普通安全名称，`.`、`..`、重复分隔符和尾随 `/` 会在任何远端操作前被拒绝。
- `sslPort`：服务端 HTTPS 监听端口。

HTTP 域名、TCP 地址和 SSH 主机可以不同。附件消息只存服务端相对路径；客户端用 `serverUrl`
解析为下载地址，因此构建客户端前必须确认它指向自己的实例。
该 HTTPS 安装中，HTTP 与 TCP 共用证书。证书必须覆盖客户端用于 hostname 校验与 SNI 的 TCP 主机名；
若 HTTP 与 TCP 使用不同主机名，两者都必须在证书标识范围内。部署会从坐标生成 `TCP_PORT`；
客户端校验、明文安装和监听地址的区别见上述配置对照表。

## 3. 提供 Secret

敏感值放在不提交的 `gradle/deployment.secrets` 或 CI Secret 中。至少包括：

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

不要把真实 secret 写入 Markdown、`deployment.json`、Gradle 命令历史或构建产物名称。

## 4. 首次部署

当 `serverUrl` 使用 HTTPS 时，首次部署必须同时提供证书链和私钥；生成的同一份 PKCS12 同时保护
HTTPS 与公网 IM TCP：

```bash
./gradlew deployServer \
  -PsslCert=/secure/teamtalk/fullchain.pem \
  -PsslKey=/secure/teamtalk/privkey.pem
```

两个路径可以是仓库根目录下的相对路径，也可以是仓库外的绝对路径；不要使用未展开的 `~`。
证书应是包含完整链的 PEM，私钥为可由 OpenSSL 非交互读取的 PEM。`sslCert` 和
`sslKey` 必须成对传入；空值、单边参数、目录或不存在的文件都会被拒绝。当前部署工具接受
`http://` 配置，但不接受它同时携带这两个 TLS 参数；这类安装尚不能直接供现有远程 SDK 完整使用，
原因见[传输配置边界](../07-operations/configuration.md#传输配置边界)。

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
curl https://im.example.com/health
./gradlew :server:server:acceptanceTest
```

验收目标同样读取 `deployment.json`，因此不会出现“部署到 A、测试 B”的 profile 漂移。失败时不要
只看 HTTP 200；部署任务本身还要求总体状态和 postgres、rocksdb、lucene、sync-event-dispatcher、
message-projection、managed-chat-projection、client-telemetry、file-storage、tcp 固定 9 项全部为 `UP`。随后应结合验收报告、服务端 trace、
客户端 fault 和目标实例数据判断。
TLS 部署的 `tcp` 健康项会以 keystore 当前叶证书作为唯一信任锚，对实际监听端口执行真实 TLS
握手（通配 bind 从本机 loopback 回连）；它不是只检查 socket 可连接。客户端仍独立使用系统 WebPKI
和严格 hostname/SNI 校验，因此健康为 `UP` 不能代替外部客户端证书链与主机名验收。

## 6. 发布客户端

```bash
./gradlew buildRelease
./gradlew releaseClients
```

客户端会内嵌构建时的服务坐标、完整 build identity 和 build time。Desktop 发布使用 Conveyor
在单个构建机交叉生成三平台站点；Android 独立生成 APK，任务见 `.github/workflows/release.yml`，
完整操作见[Desktop 制品构建](../07-operations/desktop-cross-build.md)。交叉构建成功不能替代目标平台
安装检查。少量内测可按[开发者预览版指南](developer-preview.md)先收敛目标平台与短路径验收。

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

HTTPS 升级的 TLS 参数规则为：

- 两个参数都不传：保留现有 `conf/ssl/teamtalk.p12`；部署会在停服前只读确认它非空且可读。
- 两个参数成对传入：先在本机完成上述预检，然后轮换远端证书。
- 只传一个、传空值，或从 HTTP 切换到 HTTPS 时既无新证书也无可复用 PKCS12：在任何远端变更前拒绝。

仅有明确实例与资料范围授权的独立重建任务才可清理对应测试数据；内测与生产资料都不依赖清库升级。详细运行
步骤见[部署与升级](../07-operations/deployment.md)。
