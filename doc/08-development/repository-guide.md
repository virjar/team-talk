# 仓库导览

## 1. Gradle 模块

```text
shared          KMP IM SDK 与协议
richeditor      受控 fork 的 Compose 富文本编辑器
rpc-processor   RPC IDL KSP 处理器
server          JVM 服务端
app             KMP 共享 UI 与 ViewModel
android         Android application
desktop         Desktop application
```

根 `build.gradle.kts` 读取 deployment.json、注入构建信息并注册 release/deploy 任务。

## 2. shared

```text
shared/src/commonMain/kotlin/com/virjar/tk/
├── protocol/       PacketCodec、原语、枚举、NotifyContracts
├── protocol/payload/
├── rpc/def/        @RpcService IDL
├── model/          User、Chat、Message、Conversation …
├── body/           MessageBody 与校验策略
├── client/         ImClient、RpcClient、Session、EventProcessor、LocalCache
├── repository/     领域 Repository 和 FileRepository expect
├── bot/            ImBot
├── testing/        FakeLocalCache/FakeRpcInvoker
├── log/            TkLogger
└── util/           AppLog、HTTP 等
```

平台实现位于 `androidMain` 和 `jvmMain`。shared 不能 import Compose 或平台应用导航。

## 3. rpc-processor

处理 `@RpcService` / `@RpcMethod`，生成 Contract、Proxy 与 Stub。生成代码不手工编辑。修改生成逻辑
必须运行 processor 测试、shared 编译和 RPC golden test。

## 4. server

```text
server/src/main/kotlin/com/virjar/tk/
├── Application.kt
├── api/                 HTTP routes
├── protocol/            TCP、codec adapter、dispatcher、trace
├── domain/              user/auth/contact/chat/message/conversation/device/...
├── infra/db/            PostgreSQL schema/repository support
├── infra/storage/       Message/File/Token/ClientLog stores
├── infra/search/        Lucene
├── infra/sync/          ClientRegistry/SyncEventService
├── env/                 data root
└── di/                  Koin module
```

业务规则进 domain；协议 adapter 只做上下文和错误映射；存储细节进 infra。

## 5. app

```text
app/src/commonMain/kotlin/com/virjar/tk/
├── ui/screen/           可复用页面内容
├── ui/component/        头像、气泡、输入、富文本等
├── ui/theme/            Tokens/AppTheme
├── viewmodel/           StateFlow 与业务动作
├── navigation/          平台无关数据状态，不拥有平台导航栈
└── client/              Compose 认证包装
```

UI 不直接引用服务端实现，不自己解码 wire，不绕过 Repository 维护远端事实。

## 6. platform shells

- `desktop/.../Main.kt`：应用与窗口入口。
- `desktop/.../MainAppContent.kt`：桌面壳、三栏与容器分流。
- `desktop/.../test/TestHttpServer.kt`：内置测试接口。
- `android/.../MainActivity.kt`：Android 入口。
- `android/.../TeamTalkApp.kt` / `HomeScreen.kt`：Android 壳和导航。

平台特有媒体、文件、通知、token 和系统集成留在对应模块。

## 7. 管理与部署

- `admin/`：React/Vite 管理后台。
- `buildSrc/src/main/kotlin/deployment/`：配置校验、secret、远端 provisioning、上传。
- `.github/workflows/`：CI、本地安全网、真实验收与分平台发布。
- `tools/e2e/`：客户端自动化辅助工具。

## 8. 测试位置

| 范围 | 位置 |
|---|---|
| 协议/SDK | `shared/src/commonTest` |
| 共享 UI/ViewModel | `app/src/commonTest` / `desktopTest` |
| 服务端 | `server/src/test` |
| Desktop 壳 | `desktop/src/desktopTest` |
| 真实部署验收 | server acceptance test source set |

测试怎么选见[本地测试](../09-testing/local-tests.md)。
