# 开发环境

## 1. 前置条件

| 工具 | 要求 | 用途 |
|---|---|---|
| JDK | 17 | Gradle、服务端和 Desktop |
| Docker | 可运行 Compose | 本地 PostgreSQL |
| Android Studio | 当前稳定版 | Android 构建、模拟器和真机调试 |
| Git | 可读取提交 ID | 构建信息和发布溯源 |

Gradle Wrapper 会下载固定版本的 Gradle；不需要全局安装 Gradle。第一次构建需要访问 Maven
Central、Google Maven 和 Gradle 分发站点。

## 2. 仓库配置

`gradle/deployment.json` 是会进入版本库的非敏感配置：

```json
{
  "serverUrl": "https://im.example.com",
  "tcpAddress": "im.example.com:5100",
  "deployHost": "im.example.com",
  "deployPort": 22,
  "deployUser": "root",
  "deployPath": "/opt/teamtalk",
  "sslPort": 443
}
```

字段含义见[运行配置](../07-operations/configuration.md)。部署口令、数据库密码和 SSH 私钥不能写入
该文件；敏感配置由本地 `gradle/deployment.secrets` 或 CI Secret 提供。

## 3. 两种开发回路

### 3.1 业务与客户端开发：连接配置服务器

这是默认回路。它能覆盖真实 TLS、文件服务、持久化、跨客户端和事件同步：

```bash
./gradlew :desktop:run
```

Android：

```bash
./gradlew :android:assembleDebug
```

APK 位于 `android/build/outputs/apk/debug/`。真机安装和 UI 自动化需要本机配置 Android SDK。

### 3.2 服务端内部调试：本地运行

```bash
docker compose up -d
./gradlew :server:run
```

本地 PostgreSQL 默认数据库为 `teamtalk`，开发口令来自 `docker-compose.yml`。服务端数据默认写入
仓库的 `data/`；该目录是运行数据，不应提交。

服务端调试适合领域逻辑、存储、协议分发和迁移问题。涉及多端行为时，最终仍需在配置部署上执行
真实业务验收。

## 4. 常用任务

```bash
# 最快的 Desktop 编译反馈
./gradlew :desktop:compileKotlinDesktop

# 协议、模型、Repository 与 SDK 测试
./gradlew :shared:jvmTest

# 服务端确定性测试
./gradlew :server:test

# 共享 UI 与 Desktop 测试
./gradlew :app:desktopTest :desktop:desktopTest

# 真实部署业务验收
./gradlew :server:acceptanceTest

# Android Debug 与 Desktop 当前平台安装包
./gradlew :android:assembleDebug
./gradlew :desktop:packageReleaseDistributionForCurrentOS
```

不要用 `pkill -f gradle` 作为日常停止方式；它可能杀死同目录下无关构建。运行中的
`:desktop:run` 或 `:server:run` 应在对应终端发送 `Ctrl-C`。

## 5. 数据与日志

### 服务端

```text
data/
├── rocksdb/
├── tokenstore/
├── lucene-index/
├── file-store/
├── client-logs/
└── logs/
```

### Desktop

Desktop 的 SQLite、token 和日志位于平台应用数据目录。具体路径由平台实现决定；排障时优先查看
应用启动日志中输出的 resolved path，而不是假设固定用户目录。

## 6. 修改后的验证顺序

1. 运行受影响模块的编译或单元测试。
2. 如果改了协议、Repository、领域服务或同步逻辑，运行 `:shared:jvmTest` 和 `:server:test`。
3. 如果改了业务流程，运行 `:server:acceptanceTest`。
4. 如果改了 Desktop UI，启动应用并通过内置 HTTP 测试服务操作和截图。
5. 检查 `git diff --check`，确认文档与代码一起更新。

完整测试分层见[测试策略](../09-testing/README.md)。
