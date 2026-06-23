# CLAUDE.md — TeamTalk

> 最后更新: 2026-06-12

## 语言

始终使用中文回复。

---

## 项目简介

TeamTalk 是基于 Kotlin Multiplatform (KMP) + Jetpack Compose 的跨平台即时通讯应用。面向中小型组织（用户规模不超过 1 万），单体架构，单机 + 内存模型。

采用统一的 TCP 二进制协议（INVOKE/RESPONSE + NOTIFY），所有 IM 核心操作走 TCP，HTTP 仅保留文件上传/下载。

详细架构设计见 [doc/00-overview/architecture.md](doc/00-overview/architecture.md)。

---

## 核心原则

### 模型确定性 > 灵活性

二进制协议的核心价值是**模型确定性**。字段增删只在大版本间变更（通过 `PROTOCOL_VERSION` 控制）。

- IM 核心概念走 TCP 二进制协议
- 方法路由用共享枚举（`serviceId` + `methodId`），编译器保障引用一致性
- 不兼容变更必须递增 `PROTOCOL_VERSION`（定义在 `shared/.../protocol/Frame.kt`）
- **JSON 虽然灵活，但灵活导致难以追踪数据关系，多版本行为兼容是灾难**

### 本地优先（Local-First）

客户端所有页面从本地 SQLite 读取数据渲染，网络仅用于写操作和事件同步。

- **ViewModel 不直接调用网络获取数据来渲染 UI**
- 数据变更通过 NOTIFY 事件推送维护本地数据
- 新增服务端数据变更必须推送对应通知
- 新增客户端数据展示必须先确认本地 DB 有对应数据

### 不要过早实现

- 归档/删除/生命周期管理等长期需求，等数据结构稳定后再做
- 实现一个功能前先问：这个功能现在有实际的调用方吗？

### 克制参数化（配置约束）

> 反例教训：Desktop 测试服务隔离曾尝试用 `-P` 参数 / per-profile BuildConfig 动态值等方案，
> 绕了一大圈。根因是「遇到问题就想加开关」的惯性思维。

**可配置参数的组合爆炸是后期灾难**：N 个布尔开关意味着 2^N 种未测组合。这些组合分散在各处
（BuildConfig / 系统属性 / 运行时参数），大多从未被测试覆盖。当 bug 只在特定组合下出现，
排查时甚至无法确认产物用了哪些参数。

约束：

- **遇到「要不要加个开关/参数」的决策时，默认不加**。优先用固定模板（profile）和确定性逻辑
- **新参数必须纳入 profile 模板体系**，不引入游离的 `-P` / `-D` 参数。固定模板保持条件分支数量少、可枚举
- **构建产物必须可溯源**：每个产物内嵌 git commit + build time + profile 名（见 Android 的 BuildConfig 已实现），
  Desktop 同步补齐。排查问题时不靠「回忆用了什么参数」，靠产物自带的构建信息
- **AI/脚本调参要留痕**：自动化脚本动态拼接构建参数时，实际参数组合必须能从产物或日志追溯
- 定期梳理存量参数开关，收敛不必要的可配置项（专项任务，待启动）

---

## 开发约束

### 代码规范

- **单文件建议不超过 500 行**：超过时优先考虑按职责拆分；不是硬性限制，仅在职责边界明显时拆分（避免无意义拆分增加导航成本）。历史上 400 行限制源于 AI context 较短，现已放宽
- **data class 全用 `val`**，`copy()` 更新状态
- **销毁操作幂等**
- **认证失效停而非重试**，向上传播事件让用户重新登录
- **不可变数据**：Compose 状态仅在 ViewModel 内持有
- **Compose nullable state**：先捕获到局部变量，禁止 if 块内使用 `!!`
- **禁止 `println` 打日志**：服务端代码必须用 SLF4J（`LoggerFactory.getLogger(Xxx::class.java)`）。`println` 输出到 stdout，不经过日志框架，无法被 logback 配置控制，且在生产环境产生不可控的 I/O。这是严重错误。CI 脚本 `scripts/check-println.sh` 会扫描拦截。**例外**：logback 初始化前必须输出的启动信息（如 Environment）用 `System.err.println`（stderr 不经过 logback，不影响日志目录解析）

### RPC Payload 编码约束

- **客户端 `encodePayload { }` 和服务端 `withPayload { }` 必须严格配对**：字段数量、顺序、类型完全一致。这是最易出 wire format 错位 bug 的地方
- **优先用已有 IProto data class**（如 `Chat`、`Message`）作为 payload，用 `ProtoCodec.encode()` / `decode()` 自动处理编解码，而非手写 `writeString()` 序列
- **新增 RPC 方法时必须加 ProtoRoundTripTest**：参考 `shared/src/commonTest/.../ProtoRoundTripTest.kt` 的 `testPayloadXxx` 测试，覆盖客户端编码→服务端解码的完整往返
- **简单 payload（1-3 个基本类型字段）**：可以用 `encodePayload { writeString(a); writeInt(b) }`，但必须在 RouteHandler 的 when 分支注释中标注字段顺序

### 所有者驱动（Owner-Driven Model）

> **这是最核心的架构约束。所有者驱动让逻辑简单、编译器帮忙约束数据流动，避免状态驱动的竞态和垃圾代码。**

**原则：每个对象有且仅有一个所有者，所有者销毁时其拥有的对象全部销毁。数据只能从所有者流向被拥有者，不能反向流动。**

#### 三级状态隔离

| 层级 | 所有者 | 持有内容 | 销毁时机 |
|------|--------|----------|----------|
| **App 全局** | 进程 | `ServerConfig`、`TokenStore`、登录窗口 | 进程退出 |
| **用户层** | `UserSession` | `uid`/`refreshToken`/用户身份、`ClientSession`、主窗口、ViewModel | AUTH_FAILED 或登出 |
| **连接层** | `ImClient` | TCP socket、`pendingAcks` | TCP 断开（自动重连） |

- **TCP 断开不清用户层**：`ImClient.cleanupOnDisconnect()` 只清连接层状态（pendingAcks），不清 uid/refreshToken（它们在 `UserSession` 里）
- **用户身份不可变绑定**：认证参数在 `ImClient` 构造时通过 `connectAndAuth` 原子设置，运行时不可修改

#### 窗口/UI 所有者关系

- **登录窗口**（app 全局）：独立于用户层，未登录时显示
- **主窗口**（用户层）：绑定 `UserSession`，`session != null` 时存在；登出 → session=null → 窗口自然销毁
- **禁止用布尔状态控制窗口内容切换**（如 `if (isLoggedIn) 主面板 else 登录页`）——这是状态驱动，违反所有者隔离

#### 认证原子化

- `connectAndAuth(auth, host, port)`：pendingAuth 设置 + connect 合并为**单次 EventLoop 任务**
- 禁止分两步调（先 `login()` 再 `connect()`）——协程线程的 CPU 工作会插入打乱 EventLoop 确定性

#### 编译器约束

所有者驱动让编译器帮忙约束非法数据流动：
- `ClientSession` 持有 `UserSession` → 所有依赖 `ClientSession` 的代码自动获得用户身份，无需全局传递
- `UserSession` 的字段 `private set` → 外部只读，只有认证回调能写
- 主窗口的 `session` 参数非空 → 窗口内代码不可能在用户未登录时执行

### android / desktop 共享边界

**核心原则：android 和 desktop 是两个独立应用，只共享零散 UI 片段，不共享导航/布局/交互模式。**

手机和 PC 的交互范式根本不同，不要为了"复用"强行抽象共用层：

| 维度 | 手机（Android） | 桌面（Desktop） |
|------|----------------|----------------|
| 屏幕 | 单屏，空间有限 | 大屏，可分栏 |
| 交互 | 手势为主，页面跳转 | 鼠标为主，弹窗/多选/popup menu |
| 导航 | 全屏页面跳转 + 返回栈 | 多面板布局，临时页面用弹窗 |

因此：
- **commonMain 只放共享的 UI 片段**（单个 `@Composable` Screen、`MessageBodyRenderer`、ViewModel、Repository、协议层），**不放导航逻辑**
- **导航各平台独立实现**：Android 用 `androidx.navigation:navigation-compose`（NavHost + 类型安全路由 + 返回栈 + 系统返回键）；Desktop 自行设计
- **`AppDataState`（commonMain）持有纯数据/业务状态**（repos / ViewModels / 屏幕数据），导航状态由各平台管理。Android 用 `AppDataState` + `NavController`；Desktop 暂用 `AppState`（含导航字段，后续独立重构）
- 不要引入"跨平台导航库"再次共享导航——这是过度抽象

### 客户端 SDK 前置校验

认证参数（用户名/密码）在客户端 SDK（`ImClient`）发送前用 `AuthRules` 校验，非法参数直接抛 `IllegalArgumentException`（带中文原因），不发到服务端被静默拒绝。规则与服务端 `UserService` 共用 `AuthRules` 常量，保证一致。

### 持久化登录态

IM 基本体验：**除非被踢/token 失效，重启 app 直达主界面，不闪登录页**。
- 认证成功后 `UserSession.refreshToken` 持久化到平台存储（Android: `TokenStore` / Desktop: `DesktopTokenStore`）
- 启动时读 token → `connectAndAuth`（原子化）自动登录（authType=2 refresh-token）
- AUTH_FAILED / 主动登出时清除 token + `UserSession.onAuthFailed()` 清空用户身份
- 自动登录中显示 loading，不闪登录页

### 测试策略

- **重集成测试，轻单元测试**：每个 RPC 方法都有集成测试覆盖
- 单元测试仅用于不依赖基础设施的纯计算逻辑
- **不要为测试写代码**：只被单元测试使用的生产代码应该删除
- **E2E 测试文档**：[doc/06-testing/](doc/06-testing/)（Android + Desktop）

### 依赖注入

服务端和客户端统一使用 **Koin**（纯 Kotlin，无注解处理器，无代码生成）。

### TCP 线程安全

- ImClient 是纯连接层（三级状态的第三级），**不持有用户身份**（uid/refreshToken 在 UserSession 中）
- 使用 `NioEventLoopGroup(1)` 单线程，所有连接级状态（pendingAcks）串行访问
- 完全事件驱动：connect/channelRead/channelInactive/userEventTriggered，无阻塞等待
- 认证结果通过 `onAuthResult` 回调传给 UserSession，不自己存
- 认证原子化：`connectAndAuth` 合并 pendingAuth 设置 + connect 为单次 EventLoop 任务
- 心跳：IdleStateHandler(writerIdle=30s → PING, readerIdle=90s → 关闭重连)
- 重连：指数退避 1s→2s→4s→8s→30s，保存认证参数自动重认证

### 服务端日志（Recorder 采样体系）

服务端 TCP 模块使用 **Recorder + SamplingManager** 替代 slf4j 直连日志：
- `Recorder` 绑定到 Netty Channel AttributeKey，认证前缓存 30 条，认证后 `upgrade(uid, deviceId)` 切换到采样 Writer
- 全局最多 100 个同时采样连接，保证被采样用户有完整 trace
- 懒加载 `record(Supplier<String>)` + `enable()` 短路，未采样时零开销
- 专用 trace Looper 线程写日志，不阻塞 EventLoop
- 协程中通过 `facade.recorder` 记录日志（GC 安全，agent 回收后仍可用）
- 日志标签：`[AUTH]` `[SEND]` `[SENDACK]` `[RPC]` `[SUBSCRIBE]` `[TYPING]` `[KICK]` `[IDLE]` `[CLOSE]`

### 客户端日志收集

客户端日志通过 TCP 长连接 RPC 通道上传到服务端：
- `AppLog`：跨平台（Android logcat / Desktop SLF4J），同时写入内存缓冲区
- `LogBuffer`：环形缓冲区（500 条），线程安全
- `LogUploader`：认证后启动，60s 定时或 400 条定量触发，GZIP 压缩后上传
- 服务端 `ClientLogStore`：按 `$dataRoot/client-logs/{uid}/{deviceId}/{date}.log` 存储
- 协议：`ServiceId.CLIENT_LOG(8)` + `ClientLogMethod.UPLOAD(1)`

---

## 目录结构

```
team-talk/
├── shared/                    # 共享协议层（客户端和服务端共用）
│   └── src/commonMain/
│       ├── model/             # 传输模型（User, Chat, Message, Contact...）
│       └── protocol/          # PacketType + MessageType + NotifyType + RpcMethod + IProto + 编解码
│
├── server/                    # 服务端（Ktor + Netty）
│   └── src/main/kotlin/
│       ├── Application.kt     # Ktor 启动 + Koin 配置
│       ├── domain/            # 领域层（user/ contact/ chat/ message/ conversation/ auth/）
│       │   ├── XxxService.kt  # 业务逻辑
│       │   └── XxxRepository.kt # 数据访问
│       ├── protocol/          # 协议处理（TcpServer, ImAgent, RpcDispatcher, Recorder 采样日志）
│       ├── infra/             # 基础设施（db/, cache/, storage/, search/）
│       └── di/                # Koin 模块定义
│
├── app/                       # 客户端共享基础设施
│   └── src/
│       ├── commonMain/
│       │   ├── client/        # ImClient + RpcClient + EventProcessor + LogUploader + ClientSession
│       │   ├── util/          # AppLog + LogBuffer
│       │   ├── repository/    # Repository 层
│       │   ├── viewmodel/     # ViewModel（StateFlow + 增量更新）
│       │   └── database/      # SQLDelight 本地数据库
│       ├── androidMain/
│       └── desktopMain/
│
├── android/                   # Android 应用（屏幕 + 导航）
├── desktop/                   # Desktop 应用（屏幕 + 导航）
└── doc/                       # 架构文档
```

---

## 关键文件快速索引

| 需求 | 文件 |
|------|------|
| 添加新 RPC 方法 | `shared/.../protocol/RpcMethod.kt`（加枚举）+ 服务端 Handler + 客户端 RpcClient |
| 添加新消息类型 | `shared/.../protocol/MessageType.kt`（加枚举）+ `shared/.../body/`（加 Body 类）|
| 添加新通知类型 | `shared/.../protocol/NotifyType.kt`（加枚举）+ 客户端 EventProcessor |
| 添加新传输模型 | `shared/.../model/`（加 data class 实现 IProto）|
| 修改服务端业务 | `server/.../domain/` 对应领域的 Service |
| 修改客户端展示 | `app/.../viewmodel/` + 对应平台 `ui/screen/` |
| 修改服务端 TCP 日志 | `server/.../protocol/trace/Recorder.kt` + `ImAgent` 中的 `recorder.record` |
| 修改客户端日志 | `app/.../util/AppLog.kt` + `app/.../client/LogUploader.kt` |
| 不兼容变更 | 递增 `shared/.../protocol/Frame.kt` 中的 `PROTOCOL_VERSION` |
| 添加集成测试 | `server/src/test/` |

---

## 数据模型规则

- **传输模型**：shared 模块定义，手写 `IProto` 编解码（不用代码生成），服务端和客户端共用
- **服务端内部**：直接使用传输模型，数据库映射通过 Repository 内部方法处理
- **客户端本地**：只要满足传输协议即可，内部存储方式自由选择

---

## 认证体系

随机 token + RocksDB 存储（非 JWT）。Token 是纯随机数不可猜测，踢设备 = 删除 token 记录立即生效。

---

## 构建与运行

```bash
docker compose up -d                                    # PostgreSQL
./gradlew :server:run                                   # 服务端
./gradlew :desktop:run                                  # Desktop 客户端
./gradlew :desktop:compileKotlin                        # 仅编译检查（最快验证）
./gradlew :server:test                                  # 集成测试（默认运行，使用 Embedded PostgreSQL）
./gradlew :server:test -PskipTests                      # 本地快速跳过测试
./gradlew :server:test -Dtk.e2e.remote=true             # 远程 demo E2E（连真实 im.virjar.com，默认关闭）
./gradlew :shared:jvmTest                               # shared 模块单测（协议编解码 round-trip）
./gradlew :android:assembleDemoDebug                     # Android demo APK（连 im.virjar.com）
./gradlew :desktop:packageReleaseDistributionForCurrentOS # Desktop release 产物（含 ProGuard 压缩，体积 -36%）
```

### 部署约束

- **`teamtalk.sh` 必须在构建产物中**：`server/src/main/resources/bin/teamtalk.sh` 通过 `distributions` 打包到 `bin/`，systemd 通过它启动（加载 env.sh + 设 JAVA_OPTS）。手动 rsync 部署时**必须排除运行态文件**（`--exclude data/ logs/ docker-compose.yml conf/env.sh conf/ssl/`），否则 `--delete` 会误删这些
- **服务端启动入口**：`Application.kt` 用 `embeddedServer(Netty, environment, configure, module)` 显式配置 HTTP(8080) + HTTPS(443, sslConnector) connectors，**从环境变量读取**（KTOR_PORT/KTOR_SSL_PORT/SSL_KEYSTORE/SSL_KEYSTORE_PASSWORD/SSL_PRIVATE_KEY_PASSWORD，与 env.sh 一致）。不要用 `embeddedServer(Netty, module=...)` 单参重载——它不读 conf、不支持 SSL
- **demo E2E 测试**：`RemoteDemoE2eTest` 连真实 demo 服务器（`@EnabledIfSystemProperty("tk.e2e.remote")` 默认关闭），`./gradlew :server:test -Dtk.e2e.remote=true` 启用。测试账号用户名前缀 `zd-`（≤50 字符，避开 UserService 长度校验）

## CI/CD

- **CI**（`.github/workflows/ci.yml`）：push/PR 自动编译 + 服务端测试
- **Release**（`.github/workflows/release.yml`）：手动触发，多平台并行构建（Server/Windows/Linux/macOS/Android）
- Desktop 交叉编译：Windows(msi) 在 windows-latest，Linux(deb) 在 ubuntu-latest，macOS(dmg) 在 macos-latest（arm64 + x86_64）

## Git 工作流

- 禁止直接在 main 提交，使用 `feat/`、`fix/` 分支，squash 合并
- 每个 Phase 里程碑在 main 上提交并打 tag（`phase-0`、`phase-1`...）

## 技术栈

Kotlin 2.3.20 / Compose Multiplatform 1.10.3 / Ktor 3.4.3 / Netty 4.1.119 / Exposed 0.61.0 / SQLDelight 2.3.2 / PostgreSQL 16 / RocksDB 9.10.0 / Lucene 9.12.0 / Koin / kotlinx.coroutines 1.10.2
