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
| `serverUrl` | 客户端 HTTP(S) 根地址 | 绝对 http/https URL |
| `tcpAddress` | 客户端 TCP 地址 | `host:port` |
| `deployHost` | SSH 主机 | hostname 或 IPv4 |
| `deployPort` | SSH 端口 | 1–65535 |
| `deployUser` | SSH 用户 | 安全用户名格式 |
| `deployPath` | 远端安装目录 | 安全的非根绝对路径 |
| `sslPort` | Ktor HTTPS 监听 | 与 serverUrl 显式/默认端口一致 |

**当前限制**：服务端 `TcpServer` 运行时仍固定监听 5100；虽然配置解析允许其他 tcpAddress 端口且
部署脚本会生成 `TCP_PORT`，服务端尚未读取它。私有部署在修复该缺口前必须使用 5100。该项也记录在
[功能状态](../10-reference/feature-status.md)。

## 3. 服务端环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `KTOR_PORT` | 8080 | HTTP 端口 |
| `KTOR_SSL_PORT` | 未启用 | HTTPS 端口 |
| `SSL_KEYSTORE` | 无 | PKCS12 路径；与 SSL 端口同时配置 |
| `SSL_KEYSTORE_PASSWORD` | 无 | keystore 密码 |
| `SSL_PRIVATE_KEY_PASSWORD` | 无 | 私钥密码 |
| `DATABASE_PASSWORD` | 必填部署值 | PostgreSQL 用户密码 |
| `FILE_MAX_SIZE_BYTES` | 157286400 | HTTP 单文件上限 |
| `TEAMTALK_GROUP_FILE_QUOTA_BYTES` | 1073741824 | 每个群共享文件空间配额；系统属性 `teamtalk.groupFile.quotaBytes` 优先 |
| `ADMIN_USER` | `admin` | 管理后台用户名 |
| `ADMIN_PASSWORD` | `admin-change-me` | 管理后台密码；部署必须修改 |
| `LOG_DIR` | 平台默认 | logback 输出目录 |

`JWT_SECRET` 仍由旧部署脚本生成，但当前认证使用随机 token store，不应把它解释为 JWT 签名契约。

## 4. JVM 系统属性

- `teamtalk.data.root`：显式指定数据根目录，Gradle 本地运行使用仓库 `data/`。
- `config.file`：服务端配置文件路径。
- `logback.configurationFile`：logback 配置路径。

生产启动脚本 `bin/teamtalk.sh` 从安装根解析 conf 和 data，不应依赖执行用户的当前目录。

## 5. 数据目录

```text
data/
├── pgdata/                  PostgreSQL volume（部署模式）
├── rocksdb/                 MessageStore
├── tokenstore/              token KV
├── lucene-index/            可重建索引
├── file-store/rocksdb/      文件元数据/小对象
├── file-store/files/        大对象
├── file-store/tmp/          临时上传
├── client-logs/             客户端上传日志
└── logs/                    服务端日志
```

修改路径前必须评估备份、systemd 工作目录、容器 volume 和应用 Environment 的共同影响。

## 6. 客户端配置

Desktop 与 Android 的默认服务器在构建时生成。ServerConfig 的 JVM 系统属性覆盖主要用于开发或
无头入口，不能作为最终用户 profile 系统。排查客户端连错服务器时，查看设置页的 commit/build time
和构建配置，不要只看当前仓库文件。
