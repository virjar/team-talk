# 开发环境搭建指南

> 面向开发者的完整本地开发环境搭建文档。

## 前置依赖

- JDK 17+
- Docker & Docker Compose（用于 PostgreSQL）
- Android Studio / IntelliJ IDEA（推荐）
- Android SDK（构建 Android 客户端时需要）

## 一键启动

```bash
# 1. 启动基础设施（PostgreSQL 16）
docker compose up -d

# 2. 启动服务端（前台运行，端口 8080/5100）
./gradlew :server:run

# 3. 启动客户端（二选一）
./gradlew :desktop:run                  # Desktop 客户端
./gradlew :android:assembleDebug        # Android APK
```

### IDEA 单步调试

除了 Gradle 命令行启动，也可以直接在 IntelliJ IDEA 中运行 Main 函数，支持断点调试：

**服务端**：运行 `server` 模块中的 `com.virjar.tk.ApplicationKt` — `main(args: Array<String>)`

**Desktop 客户端**：运行 `desktop` 模块中的 `com.virjar.tk.MainKt` — `main() = application { ... }`

> IDEA 会自动识别 Gradle 模块结构，直接右键 Main 函数 → Run/Debug 即可。无需额外配置。

## 基础设施

### Docker Compose 服务

| 服务 | 端口 | 数据卷 | 用途 |
|------|------|--------|------|
| PostgreSQL 16 | 5432 | `~/.tk/pgdata` | 关系型数据（用户/频道/好友/会话） |

### 数据库

默认连接信息：
- URL: `jdbc:postgresql://127.0.0.1:5432/teamtalk`
- 用户: `postgres` / 密码: `postgres`

服务端启动时通过 Exposed 自动建表，无需手动初始化数据库。

## Desktop 客户端

### 基本使用

```bash
./gradlew :desktop:run                    # 默认连 localhost:8080
./gradlew :desktop:compileKotlin          # 仅编译检查（不启动）
```

### 连接远程服务器

```bash
./gradlew :desktop:run -PbuildProfile=demo
```

### 多实例运行

每个实例需要独立的数据目录，通过 `-PDATA_DIR` 指定：

```bash
# 终端 1 — 用户 A
./gradlew :desktop:run -PDATA_DIR=$HOME/.tk/user1

# 终端 2 — 用户 B
./gradlew :desktop:run -PDATA_DIR=$HOME/.tk/user2
```

同一数据目录通过文件锁（`$dataDir/.lock`）互斥，第二个实例会提示已被锁定。

### 数据目录

| 路径 | 说明 |
|------|------|
| `~/.tk/app_default/` | 默认数据目录（可通过 `-Dteamtalk.data.dir` 或 `-PDATA_DIR` 自定义） |
| `~/.tk/app_default/session.properties` | 登录会话（token/uid/userJson） |
| `~/.tk/app_default/.lock` | 文件锁（运行时自动创建，进程退出后自动释放） |

## Android 客户端

### 构建与安装

```bash
./gradlew :android:assembleDebug
# 产物: android/build/outputs/apk/
```

### 服务端地址自动检测

推荐使用 Android 模拟器进行开发。模拟器与服务端运行在同一台电脑上，通过 `10.0.2.2` 直接访问宿主机，无需额外网络配置，可以在 IDEA 中同时调试 App 和 Server 代码，实现端到端联动调试。

如果使用真机连接远程服务器，则只能调试 App 侧代码，无法做 App 与 Server 的联动调试（除非配置内网穿透将 Server 映射到本地，但这会带来复杂的网络拓扑）。因此 IP 自动检测机制的核心目的是：**让开发者零配置即可在本地完成 App + Server 的全链路调试**。

- **模拟器**：`10.0.2.2:8080`（自动映射到宿主机 localhost）
- **真机**：自动检测开发者电脑的 LAN IP 并注入 BuildConfig

### 手动指定连接地址

通过 Gradle Profile 系统管理不同环境的服务器地址，详见 [Gradle Profile 系统](#gradle-profile-系统)。默认 `dev` profile 连接 `localhost`，切换到 `demo` profile 连接演示站：

```bash
# 连接演示站
./gradlew :android:assembleDebug -PbuildProfile=demo

# 自定义环境：创建 gradle/profiles/my-server.properties 后
./gradlew :android:assembleDebug -PbuildProfile=my-server
```

配置优先级（从高到低）：
1. Gradle `-P` 参数
2. `local.properties` 文件
3. 自动检测

## 服务端

### 开发模式

```bash
./gradlew :server:run        # 前台运行（Ctrl+C 停止）
./gradlew :server:test       # 运行集成测试
```

### 配置

配置文件：`server/src/main/resources/application.conf`（HOCON 格式）

所有配置项均支持环境变量覆盖，优先级：环境变量 > 配置文件默认值。

| 配置项 | 默认值 | 环境变量 |
|--------|--------|----------|
| HTTP 端口 | `8080` | `KTOR_PORT` |
| HTTPS 端口 | _(无)_ | `KTOR_SSL_PORT` |
| SSL 证书库 | _(无)_ | `SSL_KEYSTORE` |
| SSL 证书库密码 | _(无)_ | `SSL_KEYSTORE_PASSWORD` |
| SSL 私钥密码 | _(无)_ | `SSL_PRIVATE_KEY_PASSWORD` |
| TCP 端口 | `5100` | `TCP_PORT` |
| 数据库 URL | `jdbc:postgresql://127.0.0.1:5432/teamtalk` | `DATABASE_JDBC_URL` |
| 数据库用户 | `postgres` | `DATABASE_USER` |
| 数据库密码 | `postgres` | `DATABASE_PASSWORD` |
| JWT 密钥 | 内置默认值 | `JWT_SECRET` |
| 文件大小上限 | `52428800`（50MB） | `FILE_MAX_SIZE_BYTES` |

RocksDB 和 Lucene 的数据目录由 `Environment` 类管理，路径为 `$dataRoot/rocksdb/` 和 `$dataRoot/lucene-index/`，不通过配置文件设置。

### 日志

- **控制台**：实时输出（开发模式）
- **文件**：开发模式下日志写入数据目录的 `logs/` 子目录（参见 CLAUDE.md"开发模式日志目录"章节）
- **配置**：`server/src/main/resources/logback.xml`

### 生产部署

详见 [deploy.md](deploy.md)。

## Gradle Profile 系统

所有环境差异（服务器地址、TCP 主机、部署配置等）通过 Profile 文件管理，位于 `gradle/profiles/` 目录：

```bash
gradle/profiles/
├── dev.properties           # 本地开发（默认）
├── demo.properties          # 官方演示站（im.virjar.com）
├── production.properties    # 生产模板（用户复制后修改）
└── *.secrets                # 敏感密码（自动生成，不入 Git）
```

服务端地址通过 Profile 在构建时注入到客户端，`ServerConfig` 采用 `expect/actual` 模式：

| 平台 | 实现文件 | 策略 |
|------|----------|------|
| commonMain | `ServerConfig.kt` | expect 声明 + 数据类 |
| Desktop | `ServerConfig.desktop.kt` | 构建时从 Profile 注入，默认 `localhost:8080` |
| Android | `ServerConfig.android.kt` | 构建时从 Profile 注入 BuildConfig + 模拟器自动检测 `10.0.2.2` |

```bash
# 默认 dev profile（连接 localhost）
./gradlew :desktop:run

# 指定 demo profile（连接演示站）
./gradlew :desktop:run -PbuildProfile=demo

# 自定义环境
cp gradle/profiles/production.properties gradle/profiles/my-company.properties
# 编辑 my-company.properties 后：
./gradlew :desktop:run -PbuildProfile=my-company
```

## 常见问题

### PostgreSQL 连接失败

```bash
# 检查 Docker 容器状态
docker compose ps

# 查看日志
docker compose logs postgres

# 重启
docker compose restart postgres
```

### Desktop 编译报错

```bash
# 清理缓存重新编译
./gradlew clean :desktop:compileKotlin
```

### 端口被占用

```bash
# 查看占用端口的进程
lsof -i :8080   # HTTP
lsof -i :5100   # TCP
lsof -i :5432   # PostgreSQL
```
