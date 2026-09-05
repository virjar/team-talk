# 运行配置

## 1. 配置分层

| 层 | 文件/来源 | 内容 | 是否入库 |
|---|---|---|---|
| 构建与部署坐标 | `gradle/deployment.json` | HTTP、TCP、SSH、安装路径 | 是 |
| 部署 secret | `gradle/deployment.secrets` | 数据库、TLS 等密码 | 否 |
| 实例环境 | `/opt/teamtalk/conf/env.sh` | systemd/JVM 环境变量 | 否 |
| 服务默认值 | `server/.../application.conf` | HTTP、数据库、文件上限 | 是 |
| 客户端默认值 | BuildConfig / ServerConfig | serverUrl、TCP host/port | 构建产物 |

单一部署配置的目标是让客户端、部署任务和真实验收指向同一实例。secret 与实例运行参数仍然分层，
不能为了“单一”把密码写进公开 JSON。

## 2. deployment.json

| 字段 | 含义 | 校验 |
|---|---|---|
| `serverUrl` | 客户端 HTTP(S) 根地址 | 部署配置接受绝对 http/https URL；客户端的附加约束见下表 |
| `tcpAddress` | 客户端 IM TCP 地址 | `host:port`，端口为 1–65535；是否使用 TLS 还取决于对应层的策略 |
| `deployHost` | SSH 主机 | hostname 或 IPv4 |
| `deployPort` | SSH 端口 | 1–65535 |
| `deployUser` | SSH 用户 | 安全用户名格式 |
| `deployPath` | 远端安装目录 | 安全的非根绝对路径 |
| `sslPort` | Ktor HTTPS 监听 | HTTPS 配置时须与 serverUrl 显式/默认端口一致 |

`tcpAddress` 的端口沿部署链写入 `TCP_PORT`，再由 `TcpServer` 与健康探针共同读取；5100 只是默认值，
不是固定监听。客户端地址、运行时监听和验收目标必须在同一次配置变更中保持一致。

### 传输配置边界

服务端能监听哪些端口、SDK 接受哪些地址、部署任务能生成哪种安装，是三个不同层面的事实。
当前实现尚未把可选 HTTP 与低成本 TCP 证书部署完整打通，不能只改一个 `serverUrl` 就宣称双端可用。

| 层 | HTTP | IM TCP 与证书 | 代码入口 |
|---|---|---|---|
| 服务运行时 | 未启用 HTTPS connector 时，HTTP 监听 `0.0.0.0:KTOR_PORT`；同时配置 HTTPS 端口与可加载 keystore 时只开 HTTPS，关闭 HTTP | 默认 `0.0.0.0:5100`；配置 `SSL_KEYSTORE` 后使用 TLS 1.2/1.3，否则为明文。监听地址本身不强制 TLS | [Application](../../server/server/src/main/kotlin/com/virjar/tk/server/Application.kt)、[ServerTransportConfiguration](../../server/server/src/main/kotlin/com/virjar/tk/server/ServerTransportConfiguration.kt) |
| 当前 Android/Desktop/无头 SDK | Android、JVM 的 `canonicalHttpServerBase` 均要求远程 HTTPS；明文例外仅为 `localhost`、`127.0.0.1`、`::1`，不跟随认证请求重定向 | 非本地地址强制 TLS，使用平台 WebPKI、主机名校验和 SNI；握手失败不回退明文。TCP 的本地例外为 `localhost`、`::1` 和合法四段 `127.*` 字面地址 | [ClientTransportTls](../../client/shared/src/commonMain/kotlin/com/virjar/tk/shared/client/ClientTransportTls.kt)、[Android HTTP](../../client/shared/src/androidMain/kotlin/com/virjar/tk/shared/repository/FileRepository.android.kt)、[JVM HTTP](../../client/shared/src/jvmMain/kotlin/com/virjar/tk/shared/repository/FileRepository.desktop.kt) |
| 当前 Gradle 部署工具 | `DeploymentConfig` 接受 http/https；HTTPS 首次安装须提供成对 PEM，HTTP 安装拒绝 PEM 参数 | HTTPS 安装生成 `TCP_HOST=0.0.0.0`，HTTP 安装生成 `TCP_HOST=127.0.0.1`；仅 HTTPS 安装注入 keystore，并让 HTTPS 与 TCP 共用它 | [DeploymentConfig](../../buildSrc/src/main/kotlin/deployment/DeploymentConfig.kt)、[EnvSh](../../buildSrc/src/main/kotlin/deployment/EnvSh.kt)、[TLS 预检](../../buildSrc/src/main/kotlin/deployment/TlsDeploymentPreflight.kt) |

因此，当前远程完整客户端的既有路径仍是 HTTPS + TLS/TCP。服务运行时支持远程明文，并不表示现有
SDK 会连接它；生成一份自签 PKCS12 也不会自动让 SDK 信任它。可选 HTTP、自签 TCP 的生成与信任、
部署参数解耦归入路线图 [REL-03](../10-reference/roadmap.md)，
这里记录现有能力，不把这些尚未完成的组合写成部署承诺。

## 3. 服务端环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `KTOR_PORT` | 8080 | 未启用 HTTPS connector 时的明文 HTTP 端口，绑定 `0.0.0.0` |
| `KTOR_SSL_PORT` | 未启用 | 与可加载 keystore 一起启用 HTTPS connector；启用后关闭 HTTP |
| `TCP_HOST` | `0.0.0.0` | 直接运行的 IM TCP 默认地址；部署工具会按上表覆盖 |
| `TCP_PORT` | 5100 | IM TCP 监听端口；部署值来自 `tcpAddress` |
| `MINIMUM_PROTOCOL_MINOR` | 构建的最低 minor | 同协议 major 下的客户端下限；只能提高至当前 minor，启动时校验；普通升级保留显式值并在停服前按目标产物窗口预检；详见[版本机制](../04-protocol/versioning.md) |
| `SSL_KEYSTORE` | 无 | PKCS12 路径；配置后启用 TCP TLS，也供 HTTPS connector 使用 |
| `SSL_KEYSTORE_PASSWORD` | 无 | keystore 密码 |
| `SSL_PRIVATE_KEY_PASSWORD` | 无 | 私钥密码 |
| `DATABASE_PASSWORD` | 必填部署值 | PostgreSQL 用户密码 |
| `FILE_MAX_SIZE_BYTES` | 157286400 | HTTP 单文件上限 |
| `TEAMTALK_FILE_STORE_QUOTA_BYTES` | 10737418240 | 普通附件 FileStore 全局持久容量硬上限；系统属性 `teamtalk.fileStore.quotaBytes` 优先 |
| `TEAMTALK_UNREFERENCED_ATTACHMENT_TTL_HOURS` | 168 | 上传成功但没有当前用户头像、消息、活动群文件或活动文档修订引用的对象租约；必须为 1–8760 的整数，过期后由小时级有界扫描回收 |
| `TEAMTALK_GROUP_FILE_QUOTA_BYTES` | 1073741824 | 每个群共享文件空间配额；系统属性 `teamtalk.groupFile.quotaBytes` 优先 |
| `ADMIN_USER` | 无（管理登录关闭） | 管理后台用户名；必须与密码同时显式配置 |
| `ADMIN_PASSWORD` | 无（管理登录关闭） | 管理后台密码；必须与用户名同时显式配置 |
| `TEAMTALK_AUTH_GUARD_WINDOW_SECONDS` | 10 | 认证尝试计数窗口；必须为 1–86400 的整数 |
| `TEAMTALK_AUTH_GUARD_COOLDOWN_SECONDS` | 30 | 认证维度超限后的冷却时间；必须为 1–86400 的整数 |
| `TEAMTALK_AUTH_GUARD_GLOBAL_ATTEMPTS` | 1024 | 单窗口全部认证尝试硬上限；必须为 1–1000000 的整数 |
| `TEAMTALK_AUTH_GUARD_MAX_CONCURRENT` | 16 | 同时在途的 TCP/管理认证硬上限；必须为 1–128 的整数 |
| `TEAMTALK_AUTH_GUARD_MAX_SOURCES` | 4096 | 驻留的来源/认证操作计数桶上限；必须为 1–1000000 的整数 |
| `TEAMTALK_AUTH_GUARD_MAX_ACCOUNTS` | 16384 | 驻留的账号指纹/认证操作计数桶上限；必须为 1–1000000 的整数 |
| `TEAMTALK_SYNC_EVENT_RETENTION_DAYS` | 30 | 持久同步事件保留天数；必须为 1–3650 的整数，超期后只压缩已完成进程内推送尝试的连续前缀 |
| `LOG_DIR` | 平台默认 | logback 输出目录 |

在 `conf/env.sh` 中配置最低版本时，使用单独一行 `MINIMUM_PROTOCOL_MINOR=数字`，例如
`MINIMUM_PROTOCOL_MINOR=0`；不使用引号、`export`、前导零或行尾注释。首次部署省略该项，使用构建
默认下限。普通升级只读取这一个非敏感赋值，不执行 `env.sh`；重复项和非规范内容会在停服前拒绝。
已有显式值会写回新 `env.sh`，不会因部署重新生成配置而丢失。

升级预检的窗口来自待部署服务端产物 `teamtalk-release.properties` 中的 `protocolMajor`、
`minimumProtocolMinor` 和 `protocolMinor`，包括 CI 的 staged 包，不取当前源码值代替。显式下限低于
目标构建下限或高于目标当前 minor 时，升级会在上传和停服前中止；先按计划修改或移除该显式配置，
再重试。缺少窗口字段的历史产物必须重新构建，不能跳过预检后依赖启动回滚。

TeamTalk 的部署任务生成单密码 PKCS12，因此 HTTPS 实例的
`SSL_KEYSTORE_PASSWORD` 与 `SSL_PRIVATE_KEY_PASSWORD` 必须相同；不一致时部署预检会在修改远端前失败。
启用 TLS 后，TCP 健康探针以 keystore 当前叶证书作为唯一信任锚执行真实握手；明文模式只检查 socket
连通性。客户端的信任规则独立于服务自检，因此本机健康成功不能代替真实客户端连接验收。

认证门禁还按操作使用固定的安全预算：登录、注册、refresh、管理登录分别限制操作、直连来源和规范化
账号指纹；refresh 的预算高于 BCrypt 路径，避免重连潮汐挤占密码验证。来源只取服务器 connector 看到的
直接 socket peer，不读取 `Forwarded` / `X-Forwarded-For`。进程门禁默认最多允许 16 个认证任务在途；
TCP 入口还会取该配置与实际 `IOExecutor` worker 半数中的较小值，始终保留至少一半 worker 给已认证业务。
配置非法时服务启动直接失败。来源/账号表满且没有过期
桶可回收时拒绝新 key，不通过淘汰活动桶放宽限速。

## 4. JVM 系统属性

- `teamtalk.data.root`：显式指定数据根目录，Gradle 本地运行使用仓库 `data/`。
- `config.file`：服务端配置文件路径。
- `logback.configurationFile`：logback 配置路径。

生产启动脚本 `bin/teamtalk.sh` 从安装根解析 conf 和 data，不应依赖执行用户的当前目录。

## 5. 数据目录

```text
data/
├── data-epoch               本地持久化格式门禁
├── dataset-id               与 PostgreSQL 共享的 canonical dataset 身份
├── pgdata/                  PostgreSQL volume（部署模式）
├── rocksdb/                 MessageStore
├── lucene-index/            可重建消息索引
├── client-telemetry-index/  7日可丢失客户端遥测日志
├── connection-trace-index/  7日可丢失服务端连接诊断轨迹
├── file-store/rocksdb/      文件元数据、小对象与 uploads 上传事务日志
├── file-store/files/        大对象
├── file-store/tmp/          临时上传
└── logs/                    服务端日志
```

`credentials` 与用户、设备一起属于 PostgreSQL 备份边界；不要再创建、挂载或恢复历史
`data/tokenstore`。客户端遥测设备资料、诊断策略和审计属于 PostgreSQL；事件与幂等收据只存在
`client-telemetry-index`；服务端连接轨迹独立位于 `connection-trace-index`。两者损坏时允许清空但无法恢复。
当前 schema/data epoch 以 `ServerDataEpoch.CURRENT_EPOCH` 为事实源，保留现有值，不随协议零号基线重编号。
存储基线包含：表情回应的 `message_reactions` 行级权威表（`(chat_id, server_seq, emoji, uid)`
主键，聚合计数由服务端派生，消息撤回在同一投影事务清空该消息全部回应）；Document move/rename
的有限 `document_node_move_commands` 收据表；Users
`avatar_path/avatar_name/avatar_content_type/avatar_size` 完整头像四元组、全空或全非空约束和 path 索引，
对外 User 事实的正数单调 `revision`，以及 FileStore RocksDB 的 `uploads` column family；文件 metadata
记录可空的 upload transaction key、attempt token 和对象序号，
使主文件与可选缩略图归属同一次上传。`uploads` 以 `(uid, canonical uploadId)` 为身份持久
`STARTED` / `COMPLETED` 记录，完成态保存可精确重放的完整上传收据。`ReplyBody.assets` 使用
内嵌资产清单，未知的旧无清单格式不能直接解码。普通升级保留数据；PostgreSQL 在当前 epoch 内通过
`schema_migrations` 执行有序迁移，零号迁移放宽遥测协议 ID 约束。其他历史布局必须提供明确迁移或
恢复方案，不能把旧数据库或局部存储目录拼接到新实例，也不能默认清空它们。

修改路径前必须评估备份、systemd 工作目录、容器 volume 和应用 Environment 的共同影响。

## 6. 客户端配置

Desktop 与 Android 的默认服务器在构建时生成。ServerConfig 的 JVM 系统属性覆盖主要用于开发或
无头入口，不能作为最终用户 profile 系统。排查客户端连错服务器时，查看设置页的 commit/build time
和构建配置，不要只看当前仓库文件。
客户端的 HTTP 与 TCP 限制见[传输配置边界](#传输配置边界)。TCP 需要 TLS 时，handshake 成功前
不发布 `CONNECTED`、不发送 AUTH。

Desktop 的默认数据根是当前用户的平台 app-data 目录：macOS 为
`~/Library/Application Support/TeamTalk`，Windows 为 `%LOCALAPPDATA%\TeamTalk`，Linux 为
`${XDG_DATA_HOME:-~/.local/share}/teamtalk`。`teamtalk.data.dir` 只是 Desktop 的显式、绝对路径覆盖；
其父目录必须已存在且通过当前用户的安全检查。开发模式也不会默认回退到仓库
`data/`；如确需隔离 profile，必须显式传入该覆盖。迁移和冲突处理详见
[桌面端客户端](../05-clients/desktop.md)。
