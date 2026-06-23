# 开发环境搭建指南

> 面向开发者的完整本地开发环境搭建文档。

## 前置依赖

- JDK 17+
- Docker & Docker Compose（用于 PostgreSQL）
- IntelliJ IDEA（推荐）或 Android Studio
- Android SDK（构建 Android 客户端时需要，minSdk 26 / targetSdk 35）

## 一键启动

```bash
# 1. 启动基础设施（PostgreSQL 16）
docker compose up -d

# 2. 启动服务端（前台运行，HTTP 8080 + TCP 5100）
./gradlew :server:run

# 3. 启动客户端（二选一）
./gradlew :desktop:runDev                   # Desktop 客户端（dev profile）
./gradlew :android:assembleDevDebug         # Android APK（dev profile）
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
| PostgreSQL 16 | 5432 | `./data/pgdata` | 关系型数据（用户/聊天/好友/会话/设备） |

### 数据库

默认连接信息：
- URL: `jdbc:postgresql://127.0.0.1:5432/teamtalk`
- 用户: `postgres` / 密码: `postgres`

服务端启动时通过 Exposed 自动建表，无需手动初始化数据库。

### 数据目录

开发模式下数据目录位于项目根目录的 `data/`（由 Gradle 通过 `-Dteamtalk.data.root` 注入）：

```
data/
├── pgdata/                  # PostgreSQL 数据（Docker 卷）
├── rocksdb/                 # RocksDB 消息存储
├── tokenstore/              # RocksDB Token 存储
├── lucene-index/            # Lucene 全文索引
├── file-store/
│   ├── rocksdb              # 文件存储索引
│   ├── files                # 大文件存储
│   └── tmp                  # 临时文件
├── client-logs/             # 客户端上传日志
└── logs/                    # 服务端应用日志
```

## Desktop 客户端

### 基本使用

```bash
./gradlew :desktop:runDev                   # dev profile（连接 localhost）
./gradlew :desktop:runDemo                  # demo profile（连接演示站）
./gradlew :desktop:compileKotlin            # 仅编译检查（不启动，最快验证）
```

### 多实例运行

每个实例需要独立的数据目录：

```bash
# 终端 1 — 用户 A
./gradlew :desktop:run -PDATA_DIR=$HOME/.tk/user1

# 终端 2 — 用户 B
./gradlew :desktop:run -PDATA_DIR=$HOME/.tk/user2
```

### 数据目录

| 路径 | 说明 |
|------|------|
| `~/.tk/app_default/` | 默认数据目录 |
| `~/.tk/app_default/session.properties` | 登录会话（token/uid） |
| `~/.tk/app_default/*.db` | SQLDelight 本地数据库 |

### Release 构建与 ProGuard 压缩

Desktop 的 `packageRelease*` 任务启用 Compose 官方内置 ProGuard（仅压缩不混淆），删除依赖 jar 中未被引用的类，显著减小安装包体积。

```bash
# 按当前活跃 profile 构建 release 产物（profile 由 -Pprofile 参数决定，默认 dev）
./gradlew :desktop:packageReleaseDistributionForCurrentOS                        # 默认 dev profile
./gradlew :desktop:packageReleaseDistributionForCurrentOS -Pprofile=demo         # 指定 demo profile
./gradlew :desktop:packageReleaseDmg                        # 仅 macOS dmg
./gradlew :desktop:packageDistributionForCurrentOS          # 不含 ProGuard 的常规构建（开发验证用）
```

**Profile 与构建的关系（关键）**：Desktop 打包会把**活跃 profile 的服务端地址固化进产物 JVM 启动参数**（`nativeDistributions.jvmArgs`），运行时 `ServerConfig.defaultServerConfig()` 读取这些 JVM 属性。由 `-Pprofile` 参数选定单一活跃 profile，打出一个产物。这与 Android 的 per-flavor BuildConfig 注入是等价机制，对齐 V1 时代的做法。

| profile | serverUrl | 产物内固化地址 |
|---------|-----------|---------------|
| dev | `http://localhost:8080` | localhost:8080 |
| demo | `https://im.virjar.com` | im.virjar.com:5100 |
| production | `https://your-server.com` | your-server.com |

> 若打包时不固化 profile，产物会 fallback 到 `ServerConfig` 默认值 `localhost:8080`，连不上任何真实服务器。

**配置位置**：`desktop/build.gradle.kts`
- profile 固化：`compose.desktop.application.nativeDistributions.jvmArgs`（从 `activeProfile` 注入）
- ProGuard：`compose.desktop.application.buildTypes.release.proguard` 块
- keep 规则：`desktop/desktop-proguard.pro`

**ProGuard 策略**：`isEnabled=true` / `obfuscate=false` / `optimize=false`。仅删除死代码，保留堆栈可读性，规避反射/序列化风险（CLAUDE.md「克制参数化」原则）。

**体积基准**（macOS x64，Compose 1.10.3，2026-06-19，全优化）：

| 指标 | 无优化 | 全优化后 | 降幅 |
|------|--------|---------|------|
| dmg 安装包 | 132M | **61M** | **-54%** |
| 解压后 app | 172M | **95M** | **-45%** |
| runtime modules | 44M | 29M | -34% |
| sqlite-jdbc jar | 14M | 858K | -94% |
| runtime fonts | 9M | 0 | -100% |

**优化措施**：
1. **ProGuard 仅压缩不混淆**（最小 keep 规则）：Material3 4.3M→1.2M，删除未引用依赖
2. **sqlite-jdbc native 平台裁剪**：胖 jar 含全平台 24 个 native 库（14M），运行时只用当前平台 1 个（~1.2M），打包时按 `os.name`/`os.arch` 剔除其余
3. **runtime 字体移除**：JBR 自带 43 个编程字体（9M，FiraCode/JetBrainsMono 等），IM 客户端用系统字体
4. **jlink --compress=1**：重新生成 runtime modules（字符串共享压缩），44M→29M。用 compress=1 而非 compress=2：compress=2 的 ZIP 压缩让 modules 变成不可压缩数据，dmg 再压效率反降；compress=1 保持可压缩性，dmg 最终更小（61M vs 66M）

> 注：`libjvm.dylib`（17M，JVM 核心）、`libskiko.dylib`（21M，Skia 渲染引擎）是不可压缩的运行时必需部分。

**ProGuard 规则维护原则**：最小 keep，报错驱动补 keep。只 keep 真正的反射/序列化/SPI 入口，避免 `-keep class xxx.** { *; }` 整包保留（会让压缩形同虚设）。依赖类被删后，运行时若报 `NoClassDefFoundError` 再针对性补 keep。当前已确认的整包保留项（实测报错驱动）：
- `ch.qos.logback.**`：Appender 通过 logback.xml 类名字符串间接引用
- `org.slf4j.**`：logback Logger 方法签名依赖
- `io.netty.**`：内部 shaded jctools 队列互引紧密

**已踩过的坑**（修改规则时注意）：
- 不要用 `-dontpreverify`：Compose 字节码分支多，JVM 9+ 强制要求 StackMapTable，否则 `VerifyError`
- logback 必须**整包 `-keep`**，不能用 `allowshrinking`：其 Appender 通过 `logback.xml` 类名字符串间接引用，静态分析看不到，会被误删导致日志丢失
- netty 内部 shaded jctools 队列互引紧密，整包 `-keep` 最稳
- **sqlite-jdbc 的 JNI 回调类需精确 keep**：native 库（libsqlitejdbc）加载时通过 JNI `FindClass` 注册 `Function`/`Collation`/`BusyHandler`/`ProgressHandler`/`DB`/`NativeDB` 及其内部类（可从 native 库符号表 `strings libsqlitejdbc.dylib | grep org/sqlite` 提取完整列表），ProGuard 静态分析看不到 JNI 回调，会误删导致 `NoClassDefFoundError`/`NoSuchMethodError`。报错驱动逐个补，用 `$*` 保留内部类。
- Compose 1.10.3 的 `proguard.maxHeapSize` DSL 有 bug（拼成非法的 `-Xmx:{value}`），不要设置该项

## Android 客户端

### 构建与安装

```bash
./gradlew :android:assembleDevDebug
# 产物: android/build/outputs/apk/dev/debug/
```

### 服务端地址

Android 客户端通过 BuildConfig 注入服务端地址，由 Gradle Product Flavors 管理：

- **dev profile**：自动检测开发机局域网 IP，无需手动配置
  - 当 Profile 中 `serverUrl` 包含 `localhost` 或 `127.0.0.1` 时，Gradle 构建时自动替换为开发机 LAN IP
  - 模拟器和真机都能通过局域网 IP 访问开发机上的服务端

```bash
# 连接本地开发服务器（自动检测 LAN IP）
./gradlew :android:assembleDevDebug

# 连接演示站
./gradlew :android:assembleDemoDebug
```

## 服务端

### 开发模式

```bash
./gradlew :server:run        # 前台运行（Ctrl+C 停止）
./gradlew :server:test       # 运行集成测试（需要 PostgreSQL）
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
| 数据库 URL | `jdbc:postgresql://127.0.0.1:5432/teamtalk` | `DATABASE_JDBC_URL` |
| 数据库用户 | `postgres` | `DATABASE_USER` |
| 数据库密码 | `postgres` | `DATABASE_PASSWORD` |
| 文件大小上限 | `157286400`（150MB） | `FILE_MAX_SIZE_BYTES` |

### 日志

- **控制台**：实时输出（开发模式）
- **文件**：写入 `data/logs/teamtalk.log`（主日志）+ `data/logs/traces/trace.log`（TCP 连接采样日志）
- **配置**：`server/src/main/resources/logback.xml`

### TCP 日志体系

服务端 TCP 模块使用 **Recorder + SamplingManager** 采样日志体系：
- 每个连接绑定 `Recorder`（通过 Netty Channel AttributeKey），认证前缓存 30 条，认证后升级到采样 Writer
- 全局最多 100 个同时采样连接，保证被采样用户有完整 trace
- 专用 trace Looper 线程写日志，不阻塞 EventLoop

### 集成测试

```bash
RUN_INTEGRATION_TESTS=true ./gradlew :server:test
```

集成测试通过 `testApplication` 启动完整 Ktor 应用，每个 RPC 方法都有覆盖。测试使用内嵌 PostgreSQL，无需外部数据库。

### 生产部署

详见 [deploy.md](deploy.md)。

## Gradle 多渠道构建系统

项目采用多渠道构建体系，Profile 通过 `gradle/profiles/` 目录下的 JSON 文件定义。每个 Profile 拥有独立的 BuildConfig（Android）和 JVM 系统属性（Desktop）。

```json
// gradle/profiles/demo.json
{
  "name": "demo",
  "serverUrl": "https://im.virjar.com",
  "tcpAddress": "im.virjar.com:5100",
  "allowCustomServer": true,
  "deploy": { "host": "im.virjar.com" },
  "ssl": { "port": 443 }
}
```

详细的 Profile 体系说明见 [build-system.md](../build-system.md)。

Secret 文件（数据库密码、JWT 密钥等）存放在 `gradle/profiles/*.secrets`，不入 Git，首次部署自动生成。

服务端地址通过 Profile 在构建时注入到客户端，`ServerConfig` 采用统一入口 + 平台注入模式：

| 平台 | 注入方式 |
|------|----------|
| Desktop | Gradle `run<Profile>` 任务设置 JVM 系统属性，`defaultServerConfig()` 读取 |
| Android | Gradle Product Flavors 生成各自的 BuildConfig，`MainActivity` 中注入 |

```bash
# ── Desktop ──
./gradlew :desktop:runDev                     # dev profile（连接 localhost）
./gradlew :desktop:runDemo                    # demo profile（连接演示站）

# ── Android ──
./gradlew :android:assembleDevDebug           # dev profile APK
./gradlew :android:assembleDemoRelease        # demo profile APK
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
