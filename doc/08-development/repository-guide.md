# 仓库导览

## 1. Gradle 模块

```text
protocol        KMP wire、模型、消息体与 RPC 契约
shared          KMP 客户端 IM SDK
richeditor      受控 fork 的 Compose 富文本编辑器
rpc-processor   RPC IDL KSP 处理器
server          JVM 服务端
app             KMP 共享 UI 与 ViewModel
android         Android application
desktop         Desktop application
```

根 `build.gradle.kts` 读取 deployment.json、注入构建信息并注册 release/deploy 任务。

## 2. protocol

```text
protocol/src/commonMain/kotlin/com/virjar/tk/
├── protocol/       帧、原语、枚举和 NotifyContracts
├── protocol/payload/
├── model/          User、Chat、Message、Conversation …
├── body/           MessageBody 与共享校验策略
├── rpc/def/        @RpcService IDL
├── rpc/            RpcInvoker、Stub 与中立错误
├── auth/           两端一致的认证纯规则
└── http/           HTTP 响应契约
```

本模块不得引用客户端连接、缓存、Compose 或服务端基础设施。RPC KSP 生成物也编译在这里。

## 3. shared

```text
shared/src/commonMain/kotlin/com/virjar/tk/
├── client/         ImClient、RpcClient、Session、EventProcessor、LocalCache
├── repository/     领域 Repository、会话级 FileRepository 与 common 流式契约
├── bot/            ImBot
├── testing/        FakeLocalCache/FakeRpcInvoker
├── log/            TkLogger
└── util/           AppLog、HTTP 等
```

平台实现位于 `androidMain` 和 `jvmMain`。文件 Repository 的所有权、凭据门禁、multipart 纯规则和
`UploadSource` 位于 common，平台层只实现可关闭的 HTTP 连接与 `File.asUploadSource()`。shared 不能
import Compose 或平台应用导航。

## 4. rpc-processor

处理 `@RpcService` / `@RpcMethod`，生成 Contract、Proxy 与 Stub。生成代码不手工编辑。修改生成逻辑
必须运行 processor 测试、protocol 编译和 RPC golden test。

## 5. server

```text
server/src/main/kotlin/com/virjar/tk/
├── Application.kt
├── api/                 HTTP routes
├── protocol/            TCP、codec adapter、dispatcher、trace
├── application/         跨域编排与连接生命周期协调
├── domain/              业务规则、Store 与持久化/事件/搜索端口
├── infra/db/            PostgreSQL schema
├── infra/db/repository/ Exposed 端口实现
├── infra/storage/       Message/File/ClientLog stores
├── infra/search/        Lucene
├── infra/sync/          ClientRegistry/SyncEventService
├── env/                 data root
└── di/                  Koin module
```

业务规则和外部能力端口进 domain；协议 adapter 只做上下文和错误映射；跨域协调进 application；
存储与连接实现进 infra。`LayerBoundaryTest` 会阻止 domain 反向依赖 infra 或 RPC Stub。

## 6. app

```text
app/src/commonMain/kotlin/com/virjar/tk/
├── ui/screen/           可复用页面内容
├── ui/component/        头像、气泡、输入、富文本等
├── ui/theme/            Tokens/AppTheme
├── viewmodel/           StateFlow 与业务动作
├── navigation/          会话组合根与页面数据分发，不拥有平台导航栈
├── navigation/feature/  按账户、群组、发现等业务能力组织的状态与用例
└── client/              Compose 认证包装
```

UI 不直接引用服务端实现，不自己解码 wire，不绕过 Repository 维护远端事实。新增页面动作进入对应
feature controller；`AppDataState` 只做组装、生命周期和分发，不作为所有客户端业务的默认容器。

## 7. platform shells

- `desktop/.../Main.kt`：应用与窗口入口。
- `desktop/.../MainAppContent.kt`：桌面壳、三栏与容器分流。
- `desktop/.../test/TestHttpServer.kt`：内置测试接口。
- `android/.../MainActivity.kt`：Android 入口。
- `android/.../TeamTalkApp.kt` / `HomeScreen.kt`：Android 壳和导航。

平台特有媒体、文件、通知、token 和系统集成留在对应模块。

## 8. 管理与部署

- `admin/`：React/Vite 管理后台。
- `buildSrc/src/main/kotlin/deployment/`：配置校验、secret、远端 provisioning、上传。
- `.github/workflows/`：CI、本地安全网、真实验收与分平台发布。
- `tools/e2e/`：客户端自动化辅助工具。

## 9. 测试位置

| 范围 | 位置 |
|---|---|
| 协议契约 | `protocol/src/commonTest` |
| 客户端 SDK | `shared/src/commonTest` |
| 共享 UI/ViewModel | `app/src/commonTest` / `desktopTest` |
| 服务端 | `server/src/test` |
| Desktop 壳 | `desktop/src/desktopTest` |
| 真实部署验收 | server acceptance test source set |

测试怎么选见[本地测试](../09-testing/local-tests.md)。
