# 仓库导览

## 1. Gradle 模块

模块按架构语义分三个顶层组：`protocol/`（契约与传输）、`client/`（客户端）、
`server/`（服务端）。Gradle 项目使用层级路径，例如 `:client:shared`、`:server:server`；
具体模块以根 `settings.gradle.kts` 为准：

```text
protocol/protocol        KMP wire、模型、消息体与 RPC 契约
protocol/protocol-netty  TCP 传输适配（Netty）
protocol/rpc-processor   RPC IDL KSP 处理器
client/shared            KMP 客户端 IM SDK
client/shared-testkit    E2E 测试夹具（FakeLocalCache 等），仅供测试依赖
client/richeditor        受控 fork 的 Compose 富文本编辑器
client/app               KMP 共享 UI 与 ViewModel
client/android           Android application
client/desktop           Desktop application
server/server            JVM 服务端
server/admin             React/Vite 管理后台（构建产物由 server 分发）
```


**包名约定**：所有 Kotlin 代码的顶级包为 `com.virjar.tk.<module>`（如 `com.virjar.tk.protocol`、
`com.virjar.tk.shared`、`com.virjar.tk.server`、`com.virjar.tk.app`、`com.virjar.tk.android`、
`com.virjar.tk.desktop`），使 IDE 搜索与导入可按板块直接区分。生成代码位于
`com.virjar.tk.protocol.rpc.gen`。

根 `build.gradle.kts` 读取 deployment.json、注入构建信息并注册 release/deploy 任务。
顶层其余目录：`scripts/`（含 e2e 自动化辅助）、`doc/`（文档与设计/演示资料）、
`buildSrc/`（构建与部署插件）。

## 2. protocol

```text
protocol/protocol/src/commonMain/kotlin/com/virjar/tk/protocol/
├── *.kt            帧、原语、枚举和 NotifyContracts
├── payload/        传输 payload
├── model/          User、Chat、Message、Conversation 与跨端纯规则（含 AuthRules）
├── body/           MessageBody 与共享校验策略
├── rpc/            RpcInvoker、Stub、@RpcService IDL 与中立错误
├── telemetry/      遥测事件、策略与连接 trace 身份
└── http/           HTTP 响应契约
```

本模块不得引用客户端连接、缓存、Compose 或服务端基础设施。RPC KSP 生成物也编译在这里。

## 3. shared

```text
client/shared/src/commonMain/kotlin/com/virjar/tk/shared/
├── client/         ImClient、RpcClient、Session、EventProcessor、LocalCache
├── repository/     领域 Repository、会话级 FileRepository 与 common 流式契约
├── bot/            ImBot
└── log/            TkLogger、AppLog 所有权与日志缓冲；平台源集实现本地输出
```

平台实现位于 `androidMain` 和 `jvmMain`。文件 Repository 的所有权、凭据门禁、multipart 纯规则和
`UploadSource` 位于 common，平台层只实现可关闭的 HTTP 连接与 `File.asUploadSource()`。shared 不能
import Compose 或平台应用导航。

`client/shared-testkit/src/commonMain` 存放 `FakeLocalCache` / `FakeRpcInvoker` 等跨模块测试替身。
它依赖 `shared` 的公开 SDK 边界，而 `shared`、`app` 和 `server` 只在各自的 test
配置中反向引入 testkit。不得为测试替身开放可伪造的 lease 字段或产品开关；
`LocalCache` 外部实现通过封装 owner/代次的 capability gate 签发和消费不透明 lease。

## 4. rpc-processor

处理 `@RpcService` / `@RpcMethod`，生成 Contract、Proxy 与 Stub。生成代码不手工编辑。修改生成逻辑
必须运行 processor 测试、protocol 编译和 RPC golden test。

## 5. server

```text
server/server/src/main/kotlin/com/virjar/tk/server/
├── Application.kt
├── api/                 HTTP routes
├── protocol/            TCP、codec adapter、dispatcher、trace
├── application/         跨域编排与连接生命周期协调
├── domain/              业务规则、Store 与持久化/事件/搜索端口
├── infra/db/            PostgreSQL schema
├── infra/db/repository/ Exposed 端口实现
├── infra/storage/       Message/File stores
├── infra/search/        Lucene
├── infra/sync/          ClientRegistry/SyncEventService
├── env/                 data root
└── di/                  Koin module
```

业务规则和外部能力端口进 domain；协议 adapter 只做上下文和错误映射；跨域协调进 application；
存储与连接实现进 infra。`checkArchitecture` 集中检查 domain 反向依赖 infra 或 RPC Stub 等分层违规。

`application/PresenceCoordinator.kt` 与 `BotDomainAdapters.kt` 直接描述协调职责，不再各套一层
单文件子目录。设备登录信息的查询端口归 `domain/auth/DeviceRepository.kt`，与认证生命周期同址；
它直接返回 `Device`，数据库映射留在 Exposed 实现。`domain/chat/ChatAccess.kt` 是具体的成员/角色
规则，只有访问持久化快照的 `ChatAccessSource` 保留为外层端口。

## 6. app

```text
client/app/src/commonMain/kotlin/com/virjar/tk/app/
├── ui/screen/           可复用页面内容
├── ui/component/        头像、气泡、输入、富文本及消息预览等
├── ui/theme/            Tokens/AppTheme
├── viewmodel/           StateFlow 与业务动作
├── navigation/          会话组合根与页面数据分发，不拥有平台导航栈
├── navigation/feature/  按账户、群组、发现等业务能力组织的状态与用例
└── client/              Compose 认证包装
```

UI 不直接引用服务端实现，不自己解码 wire，不绕过 Repository 维护远端事实。新增页面动作进入对应
feature controller；`AppDataState` 只做组装、生命周期和分发，不作为所有客户端业务的默认容器。

## 7. platform shells

- `client/desktop/.../TeamTalkMain.kt`：应用与窗口入口。
- `client/desktop/.../MainAppContent.kt`：桌面壳、三栏与容器分流。
- `client/desktop/.../DesktopKeepAwake.kt`：桌面连接存续期间的平台防休眠。
- `client/desktop/.../test/TestHttpServer.kt`：内置测试接口。
- `client/android/.../MainActivity.kt`：Android Activity 与主题组合根。
- `client/android/.../AndroidAppDataStateHolder.kt`：跨 Activity 重建保留的账号级 UI 状态所有者。
- `client/android/.../AndroidMainNavigation.kt` / `AndroidMainMediaRoutes.kt`：Android 认证组合与平台导航。
- `client/android/.../TeamTalkApp.kt` / `HomeScreen.kt`：Android 进程入口与首页壳。

平台特有媒体、文件、通知、token 和系统集成留在对应模块。

## 8. 管理与部署

- `server/admin/`：React/Vite 管理后台。
- `buildSrc/src/main/kotlin/deployment/`：配置校验、secret、远端 provisioning、上传。
- `buildSrc/src/main/kotlin/ArchitectureCheckTask.kt`：架构检查任务，与其它根层 Gradle 任务并列。
- `.github/workflows/`：CI、本地安全网、真实验收与分平台发布。
- `scripts/e2e/`：客户端自动化辅助工具。

## 9. 测试位置

| 范围 | 位置 |
|---|---|
| 协议契约 | `protocol/protocol/src/commonTest` |
| 客户端 SDK | `client/shared/src/commonTest` / `jvmTest` |
| E2E 夹具 | `client/shared-testkit/src/commonMain`（仅 FakeLocalCache/FakeRpcInvoker，永不作为产品依赖） |
| 共享 UI | `client/app/src/desktopTest`（仅状态机回归） |
| 服务端集成/e2e | `server/server/src/test`（integration / e2e） |
| 真实部署验收 | `server/server/src/test/.../e2e/RemoteAcceptanceTest` |
| Desktop 壳 | `client/desktop/src/desktopTest`（仅资源生命周期与持久化） |
| 真实部署验收 | server acceptance test source set |

测试怎么选见[本地测试](../09-testing/local-tests.md)。
