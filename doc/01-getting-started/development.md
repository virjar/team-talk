# 开发环境

## 1. 前置条件

| 工具 | 要求 | 用途 |
|---|---|---|
| JDK | 17 | Gradle、服务端和 Desktop |
| Docker | 可运行 Compose | 本地 PostgreSQL |
| Android Studio | 当前稳定版 | Android 构建、模拟器和真机调试 |
| Git | 可读取提交 ID | 构建信息和发布溯源 |

Gradle Wrapper 会下载固定版本的 Gradle；管理后台的 Gradle 模块自动下载固定版本的 Node.js 及随包 npm。
常规构建不需要全局安装 Gradle、Node.js 或 npm。第一次构建需要访问 Maven Central、Google Maven、
Gradle 与 Node.js 分发站点，以及 npm 包仓库。

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

## 3. 开发回路

### 3.1 业务与客户端开发：连接配置服务器

这是默认回路。它能覆盖真实 TLS、文件服务、持久化、跨客户端和事件同步：

```bash
./gradlew :client:desktop:run
```

Android：

```bash
./gradlew :client:android:assembleDebug
```

APK 位于 `client/android/build/outputs/apk/debug/`。真机安装和 UI 自动化需要本机配置 Android SDK。

### 3.2 服务端内部调试：本地运行

```bash
docker compose up -d
./gradlew :server:server:run
```

本地 PostgreSQL 默认数据库为 `teamtalk`，开发口令来自 `docker-compose.yml`。服务端数据默认写入
仓库的 `data/`；该目录是运行数据，不应提交。

持久化 epoch 以源码 `ServerDataEpoch.CURRENT_EPOCH` 为事实源，不随展示或协议版本重新编号。
普通开发升级保留 PostgreSQL、`data/` 和 dataset；已知 PostgreSQL 改动执行事务迁移。
缺少布局标记、混合数据集或存在旧 RocksDB TokenStore 等未知历史布局时，启动会拒绝，须保留资料并
提供明确迁移或恢复方案，不能把“开发环境”当作默认清库授权。
当前运维值和切换要求见[运行配置](../07-operations/configuration.md)、[部署与升级](../07-operations/deployment.md)，
存储拒绝规则见[持久化](../06-server/persistence.md)。

服务端调试适合领域逻辑、存储、协议分发和迁移问题。涉及多端行为时，最终仍需在配置部署上执行
真实业务验收。

### 3.3 管理后台开发

从仓库根目录检查 TypeScript 并构建生产静态资源：

```bash
./gradlew :server:admin:check
```

`:server:admin:build` 使用同一构建链。Node.js、npm 安装工作区与构建产物均由 Gradle 管理，SPA 输出为
`server/admin/build/dist/`；服务端资源处理、分发和 `:server:server:check` 都依赖这条链。
不需要提前运行 npm，也不能用本地残留的 `server/admin/dist/` 代替源码构建。

需要 Vite 热更新时，可自行安装符合 `server/admin/package.json` 中 `engines.node` 范围的 Node.js，
然后在前端目录运行：

```bash
cd server/admin
npm ci
npm run dev
```

该开发回路的 `node_modules/` 与 Gradle 构建工作区分开；交付前仍运行 `:server:admin:check`。
构建依赖图和版本维护规则见[依赖维护](../08-development/dependency-maintenance.md#管理后台的构建链)。

## 4. 常用任务

```bash
# 最快的 Desktop 编译反馈
./gradlew :client:desktop:compileKotlinDesktop

# 协议、模型、Repository 与 SDK 测试
./gradlew :protocol:protocol:jvmTest :protocol:protocol-netty:jvmTest :client:shared:jvmTest :client:shared-testkit:compileKotlinJvm

# 服务端确定性测试
./gradlew :server:server:test

# 共享 UI 与 Desktop 测试
./gradlew :client:app:desktopTest :client:desktop:desktopTest

# 真实部署业务验收
./gradlew :server:server:acceptanceTest

# Android Debug 与 Desktop 当前平台安装包
./gradlew :client:android:assembleDebug
./gradlew :client:desktop:packageReleaseDistributionForCurrentOS
```

不要用 `pkill -f gradle` 作为日常停止方式；它可能杀死同目录下无关构建。运行中的
`:client:desktop:run` 或 `:server:server:run` 应在对应终端发送 `Ctrl-C`。

## 5. 数据与日志

### 服务端

```text
data/
├── rocksdb/
├── lucene-index/
├── client-telemetry-index/
├── connection-trace-index/
├── file-store/
└── logs/
```

客户端遥测的设备画像、诊断策略和审计保存在 PostgreSQL；7 日事件与幂等收据直接保存在
`client-telemetry-index/`。这份日志索引可在损坏时整体清空，但不能从 PostgreSQL 恢复。
服务端连接诊断另写入同样可清空的 `connection-trace-index/`，不与消息或客户端遥测索引共用目录。

### Desktop

Desktop 的 SQLite、token、device-id、crash pending 和日志位于平台用户应用数据目录：macOS 为
`~/Library/Application Support/TeamTalk`，Windows 为 `%LOCALAPPDATA%\TeamTalk`，Linux 为
`${XDG_DATA_HOME:-~/.local/share}/teamtalk`。目录安全和旧安装目录复制规则见
[Desktop 私有数据目录](../05-clients/desktop.md#11-私有数据目录)。开发运行也使用平台默认路径；只有显式
设置绝对 `-Dteamtalk.data.dir=<path>` 才使用独立 profile，不再自动写仓库 `data/desktop`。

## 6. 修改后的验证顺序

1. 运行受影响模块的编译或单元测试。
2. 如果改了协议、Repository、领域服务或同步逻辑，运行 `:protocol:protocol:jvmTest`、
   `:protocol:protocol-netty:jvmTest`、`:client:shared:jvmTest`、`:client:shared-testkit:compileKotlinJvm` 和 `:server:server:test`。
3. 如果改了业务流程，运行 `:server:server:acceptanceTest`。
4. 如果改了 Desktop UI，启动应用并通过内置 HTTP 测试服务操作和截图。
5. 检查 `git diff --check`，确认文档与代码一起更新。

完整测试分层见[测试策略](../09-testing/README.md)。
