# 生产环境部署指南

> TeamTalk 生产环境部署与运维文档。

## 架构概览

TeamTalk 采用单体架构，所有组件运行在一台服务器上：

```
┌──────────────────────────────────────────────┐
│               生产服务器                       │
│                                              │
│  TeamTalk Server (Ktor + Netty)              │
│  ├── HTTPS API ── 端口 443（需 SSL 证书）    │
│  ├── HTTP API  ── 端口 8080（无 SSL 时默认） │
│  └── TCP 长连接 ── 端口 5100                 │
│                                              │
│  PostgreSQL ── 端口 5432 (仅内网)            │
│  MinIO ── 端口 9000 / 9001 (仅内网)          │
└──────────────────────────────────────────────┘
```

推荐配置：2 核 4G 即可支撑万级用户规模。

## 一键部署

所有部署操作通过 Gradle Profile 系统完成，Profile 是唯一配置入口：

```bash
# 首次部署（HTTP 模式）
./gradlew deployServer -PbuildProfile=demo

# 首次部署（HTTPS 模式，提供 SSL 证书）
./gradlew deployServer -PbuildProfile=demo -PsslCert=cert.pem -PsslKey=key.pem

# 升级（自动检测已有部署，保留数据和配置）
./gradlew deployServer -PbuildProfile=demo

# 升级时更新 SSL 证书
./gradlew deployServer -PbuildProfile=demo -PsslCert=new-cert.pem -PsslKey=new-cert.key

# 部署服务端 + 上传客户端安装包
./gradlew deployServer uploadRelease -PbuildProfile=demo
```

### 配置分层

```
Profile (*.properties)  →  非敏感配置，入 Git（客户端构建 + 服务端部署目标）
Secret  (*.secrets)     →  敏感密码，不入 Git，本地备份（首次部署自动生成）
远程 conf/env.sh        →  仅包含与默认值不同的项 + 敏感密码
application.conf        →  合理的默认值，生产环境多数项无需覆盖
```

### 任务说明

| 任务 | 说明 |
|------|------|
| `deployServer` | 构建并部署服务端到远程主机（首次/升级自动检测） |
| `uploadRelease` | 构建并上传客户端安装包到服务器的 `static/downloads/` |
| `buildRelease` | 构建所有产物（server + desktop + android） |

典型组合：`./gradlew deployServer uploadRelease -PbuildProfile=demo`

### 功能

- 自动构建 → 上传 → Docker 基础设施部署 → 配置生成 → 服务注册 → 启动
- 首次部署生成随机密码（数据库、JWT、MinIO），保存到本地 `.secrets` 文件和远程 `conf/env.sh`
- Secret 管理自动从远程 `conf/env.sh` 提取（升级场景）或生成新的（首次部署）
- 支持 SSL：自动将 PEM 证书转换为 PKCS12，配置 Ktor HTTPS
- 升级时自动备份旧版本，保留 `data/`、`logs/`、`conf/env.sh`、`conf/ssl/`
- 自动迁移旧版 `env.sh` 到 `conf/env.sh`
- 部署完成后自动健康检查
- 每次升级都重新注册 systemd 服务

### 前置要求

- 本地：JDK 17+、rsync、openssl（启用 SSL 时）
- 服务器：Docker + Docker Compose、JDK 17+
- SSH 免密登录

### SSL 证书

通过 `-PsslCert` 和 `-PsslKey` 参数提供 PEM 格式的证书和私钥：

```bash
./gradlew deployServer -PbuildProfile=demo -PsslCert=server.pem -PsslKey=server.key
```

自动完成：
1. 本地将 PEM 转换为 PKCS12（密码保存在 `.secrets` 文件中）
2. 上传到服务器 `$DEPLOY_PATH/conf/ssl/teamtalk.p12`（权限 600）
3. 将 SSL 配置写入 `conf/env.sh`

证书文件**不会**进入 Git 仓库，由部署者本地管理。升级时重新提供 `-PsslCert/-PsslKey` 即可更新证书。

### Profile 配置

在 `gradle/profiles/` 目录管理部署目标：

```properties
# gradle/profiles/demo.properties
serverUrl=https://im.virjar.com
tcpHost=im.virjar.com
tcpPort=5100
showAdvancedSettings=true
buildProfile=demo

# 部署目标
deploy.host=im.virjar.com
deploy.user=root
deploy.path=/opt/teamtalk

# SSL
server.ssl.enabled=true
server.ssl.port=443
```

创建自定义环境：
```bash
cp gradle/profiles/production.properties gradle/profiles/my-company.properties
# 编辑 my-company.properties 后：
./gradlew deployServer -PbuildProfile=my-company
```

## 构建分发产物

```bash
./gradlew :server:buildServerDist
```

产物目录结构：

```
server/build/install/teamtalk-server/
├── bin/
│   ├── server                # Gradle 生成的标准启动脚本
│   ├── teamtalk.sh           # 启动脚本（支持 conf/env.sh 和 env.sh 双路径）
│   └── teamtalk-stop.sh      # 停止脚本
├── lib/                      # 依赖 JAR（非 fat JAR）
├── conf/
│   ├── application.conf      # 服务端配置（开发默认值）
│   ├── env.sh                # 生产环境变量（仅含与默认值不同的项）
│   ├── ssl/                  # SSL 证书（PKCS12）
│   └── logback.xml           # 日志配置
├── static/
│   ├── index.html            # 产品首页
│   └── downloads/            # 客户端安装包
├── data/                     # RocksDB 消息存储（运行时生成）
└── logs/                     # 日志文件（运行时生成）
```

## 配置参考

### 配置原则

`application.conf` 只包含开发默认值（HTTP 8080、无 SSL、默认密码）。生产配置通过 `conf/env.sh` 环境变量覆盖，不修改配置文件。

`conf/env.sh` 仅包含两类内容：
1. **与 application.conf 默认值不同的配置**（DATABASE_USER、SSL 相关）
2. **敏感密码**（DATABASE_PASSWORD、JWT_SECRET、MINIO 凭证）

未列出的配置使用 application.conf 默认值。

### 配置项

| 配置项 | 默认值 | 环境变量 | 说明 |
|--------|--------|----------|------|
| HTTP 端口 | `8080` | `KTOR_PORT` | REST API 端口 |
| HTTPS 端口 | _(无)_ | `KTOR_SSL_PORT` | SSL 启用时需要 |
| SSL 证书库 | _(无)_ | `SSL_KEYSTORE` | PKCS12 文件路径 |
| SSL 证书库密码 | _(无)_ | `SSL_KEYSTORE_PASSWORD` | — |
| SSL 私钥密码 | _(无)_ | `SSL_PRIVATE_KEY_PASSWORD` | — |
| TCP 端口 | `5100` | `TCP_PORT` | 长连接推送端口 |
| 数据库用户 | `postgres` | `DATABASE_USER` | 生产环境使用 `teamtalk` |
| 数据库密码 | `postgres` | `DATABASE_PASSWORD` | 首次部署自动生成 |
| JWT 密钥 | 内置默认值 | `JWT_SECRET` | 首次部署自动生成（至少 256 位） |
| MinIO 端点 | `http://127.0.0.1:9000` | `MINIO_ENDPOINT` | — |
| MinIO AccessKey | `minioadmin` | `MINIO_ACCESS_KEY` | 首次部署自动生成 |
| MinIO SecretKey | `minioadmin` | `MINIO_SECRET_KEY` | 首次部署自动生成 |
| 文件大小上限 | `52428800`（50MB） | `FILE_MAX_SIZE_BYTES` | — |

### conf/env.sh 示例

`deployServer` 自动生成的 `/opt/teamtalk/conf/env.sh`（权限 600）：

```bash
# TeamTalk 运行配置 - profile: demo
# 自动生成于 2026-04-25
# 权限 600，请勿提交到版本控制
# 修改后执行: systemctl restart teamtalk
#
# 仅包含与 application.conf 默认值不同的项目和敏感密码
# 未列出的配置使用 application.conf 默认值:
#   httpPort=8080, tcpPort=5100, database=127.0.0.1:5432/teamtalk
#   minio=127.0.0.1:9000, minio.bucket=teamtalk

# ── 数据库 ──
DATABASE_USER="teamtalk"
DATABASE_PASSWORD="<auto-generated>"

# ── 认证 ──
JWT_SECRET="<auto-generated-256bit>"

# ── MinIO ──
MINIO_ACCESS_KEY="<auto-generated>"
MINIO_SECRET_KEY="<auto-generated>"

# ── SSL ──
KTOR_SSL_PORT=443
SSL_KEYSTORE="/opt/teamtalk/conf/ssl/teamtalk.p12"
SSL_KEYSTORE_PASSWORD="<auto-generated>"
SSL_PRIVATE_KEY_PASSWORD="<auto-generated>"
```

### 日志配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| 日志目录 | `data/logs` | 由 `Environment.kt` 自动检测 |
| 日志文件 | `teamtalk.log` | 主日志文件 |
| 滚动策略 | 50MB/文件，保留 30 天，总量上限 1GB | 按大小+时间滚动 |
| 日志级别 | `INFO` | 可在 `conf/logback.xml` 中调整 |

## 数据目录

| 路径 | 说明 | 备份建议 |
|------|------|----------|
| `data/rocksdb/` | RocksDB 消息存储 | 定期备份 |
| `data/lucene-index/` | Lucene 全文索引 | 可重建 |
| `data/pgdata/` | PostgreSQL 数据 | 定期备份 |
| `data/minio/` | MinIO 对象存储 | 定期备份 |
| `data/logs/` | 应用日志 | 可归档后清理 |
| `conf/` | 配置文件 | 首次部署后备份 |
| `conf/ssl/` | SSL 证书（PKCS12） | 证书更新时备份 |
| `conf/env.sh` | 环境变量（含密码） | **务必备份** |

## 系统服务（systemd）

`deployServer` 自动注册 systemd 服务：

```bash
# 查看状态
systemctl status teamtalk

# 重启（修改 conf/env.sh 后）
systemctl restart teamtalk

# 查看日志
journalctl -u teamtalk -f
```

## 客户端发布

### 方式一：GitHub Actions 多平台构建（推荐）

由于 Kotlin Multiplatform 不支持交叉编译（例如无法在 macOS 上构建 Windows/msi 或 Linux/deb），通过 GitHub Actions 在各平台原生 runner 上构建是获取全平台客户端的最简单方式。

项目已提供完整的 GitHub Actions workflow（`.github/workflows/release.yml`），包含以下构建任务：

| Job | Runner | 产物 |
|-----|--------|------|
| Server | `ubuntu-latest` | 服务端分发包 |
| Desktop (Linux) | `ubuntu-latest` | `.deb` |
| Desktop (Windows) | `windows-latest` | `.msi` |
| Desktop (macOS) | `macos-latest` | `.dmg` |
| Android | `ubuntu-latest` | `.apk` |

#### 触发构建

1. 进入 GitHub 仓库 → **Actions** → **Build & Release**
2. 点击 **Run workflow**
3. 填写 `build_profile`（对应 `gradle/profiles/` 下的 profile 名称，如 `demo`）
4. 等待构建完成，在 workflow run 页面下载 **Artifacts**

所有产物以 artifact 形式提供，保留 7 天。

#### 启用自动部署（可选）

构建完成后可自动将客户端安装包上传到服务器。需要在仓库中配置 SSH 密钥：

**1. 生成专用 SSH 密钥：**

```bash
ssh-keygen -t ed25519 -C "github-actions@teamtalk" -f teamtalk-deploy-key -N ""
```

**2. 将公钥添加到服务器：**

```bash
ssh root@your-server "mkdir -p ~/.ssh && echo '$(cat teamtalk-deploy-key.pub)' >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"
```

**3. 配置 GitHub Secret：**

进入仓库 **Settings → Secrets and variables → Actions**，添加 **Repository secret**：

| 名称 | 值 |
|------|-----|
| `DEPLOY_SSH_KEY` | 上一步生成的私钥完整内容（包含 BEGIN/END 行） |

**4. 触发 workflow 时自动部署：**

当通过 **Run workflow** 手动触发时，构建完成后会自动通过 SSH 将客户端安装包上传到 profile 中 `deploy.host` / `deploy.path` 对应的 `/static/downloads/` 目录。

> deploy job 的目标服务器从 profile 中读取 `deploy.host`、`deploy.user`、`deploy.path`，如果 profile 未定义 `deploy.host` 则从 `serverUrl` 中提取主机名作为回退。无需修改 workflow 文件即可适配不同环境。

#### Fork 仓库使用

Fork 仓库可以直接复用上游的 workflow，只需：

1. 确保 fork 仓库的 **Actions** 页面已启用（GitHub 默认禁用 fork 的 Actions）
2. 创建自己的 profile 文件（如 `gradle/profiles/my-company.properties`）
3. 如需自动部署，按上述步骤配置 `DEPLOY_SSH_KEY` secret
4. 触发 **Build & Release** workflow，填入自己的 profile 名称

### 方式二：本地构建并上传

在本地开发机上直接构建当前平台的产物，然后上传到服务器：

```bash
# 构建所有产物并上传到服务器
./gradlew uploadRelease -PbuildProfile=demo

# 仅部署服务端
./gradlew deployServer -PbuildProfile=demo

# 部署服务端 + 上传客户端
./gradlew deployServer uploadRelease -PbuildProfile=demo
```

> **注意**：`uploadRelease` 只能构建当前平台的 desktop 产物（macOS 上只能构建 .dmg，Linux 上只能构建 .deb）。如需全平台客户端，请使用方式一。

构建产物上传到服务器后，用户可通过首页 `https://im.example.com/` 下载。

### Android 签名

Android APK 签名采用**开发/生产二级回退**机制：

- **开发/演示环境**：内置 `android/teamtalk-dev.jks`（已提交到 Git），clone 后直接构建即可产出签名 APK，无需任何配置
- **生产环境**：在 `local.properties` 中配置自定义签名密钥，构建时自动使用

```bash
# 开发环境 — 零配置，直接构建
./gradlew :android:assembleRelease -PbuildProfile=demo

# 生产环境 — 生成自己的签名密钥
keytool -genkey -v -keystore teamtalk-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias teamtalk
```

```properties
# local.properties（生产环境配置）
release.storeFile=teamtalk-release.jks
release.storePassword=<your-password>
release.keyAlias=teamtalk
release.keyPassword=<your-password>
```

GitHub Actions CI 无需配置 secrets，自动使用内置 dev keystore 签名。

## 备份与恢复

### PostgreSQL

```bash
# 备份
docker exec teamtalk-postgres-1 pg_dump -U teamtalk teamtalk > backup_$(date +%Y%m%d).sql

# 恢复
cat backup_20260403.sql | docker exec -i teamtalk-postgres-1 psql -U teamtalk teamtalk
```

### RocksDB 消息数据

```bash
# 停止服务后直接打包
/opt/teamtalk/bin/teamtalk-stop.sh
tar czf rocksdb_$(date +%Y%m%d).tar.gz /opt/teamtalk/data/rocksdb/
```

## 升级

```bash
# 一键升级（保留数据和配置）
./gradlew deployServer -PbuildProfile=demo

# 升级并更新 SSL 证书
./gradlew deployServer -PbuildProfile=demo -PsslCert=new-cert.pem -PsslKey=new-cert.key

# 升级并上传新客户端安装包
./gradlew deployServer uploadRelease -PbuildProfile=demo
```

## Secret 管理

Secret 文件（`gradle/profiles/<name>.secrets`）存储敏感密码，不入 Git：

```properties
# TeamTalk Secrets - profile: demo
# 此文件包含敏感信息，已加入 .gitignore

# Database
DATABASE_PASSWORD=xxx

# Auth
JWT_SECRET=xxx

# MinIO
MINIO_ACCESS_KEY=xxx
MINIO_SECRET_KEY=xxx

# SSL
SSL_KEYSTORE_PASSWORD=xxx
SSL_PRIVATE_KEY_PASSWORD=xxx
```

Secret 管理流程：
1. 首次部署：自动生成随机密码，保存到本地 `.secrets` 文件
2. 升级部署：从本地 `.secrets` 加载（如果存在）
3. 本地无 `.secrets`：从远程 `conf/env.sh` 提取敏感值重建本地文件
4. 丢失 `.secrets`：重新运行 `deployServer`，会从远程提取
