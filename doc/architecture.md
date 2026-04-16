# 架构设计文档

> 最后更新: 2026-04-16

本文档记录 TeamTalk 的架构设计理念、技术决策和编码约定。

---

## 1. 设计理念

### 为什么选择 KMP

TeamTalk 的核心目标是让开发者用 **一门语言（Kotlin）** 维护整个项目——从服务端到 Android、Desktop、iOS 客户端。KMP + Compose Multiplatform 使这一目标成为可能：

- **shared 模块**：协议定义和编解码代码在服务端和客户端之间完全共享
- **app 模块**：跨平台共享基础设施（组件 + ViewModel + Repository + Client），不含屏幕和导航
- **平台模块**：Android 和 Desktop 各自拥有独立的屏幕代码和导航逻辑，基于共享组件组装页面

#### 关于 iOS

iOS 是项目的目标平台之一，当前未覆盖。Desktop 和 Android 同属 JVM 体系，可直接使用 Java 生态库（如 `java.net.URL`、`java.util.Properties`），而 iOS 要求纯 Kotlin 实现（不能依赖 Java API），`commonMain` 中的部分代码需要改为 Kotlin/Native 兼容的方式。未来启动 iOS 覆盖时，需要排查 `commonMain` 中的 Java API 依赖并逐一替换。

### 关于 DI 框架

**现状**：项目未使用 DI 框架，依赖通过构造函数和参数传递。

**历史背景**：TeamTalk 从 TangSengDaoDao 迁移而来，原项目基于 Android XML UI，代码质量较差、逻辑混乱。迁移到 CMP 时，主要精力放在架构重建和功能适配上，加之当时 Kotlin 版本对 DI 工具链的支持有限，因此没有引入 DI 框架。

**待讨论**：随着项目规模增长，是否引入 DI 框架（如 Koin 或 Kotlin 内建的依赖注入）是一个需要团队讨论的决策点。当前的显式依赖传递在小规模下可行，但可能导致构造参数链过长。

### 本地数据库

客户端通过本地数据库实现离线可用和即时响应，这是办公 IM 的基本能力：

- **离线可用**：断网时用户仍可查看历史消息、联系人和会话列表
- **即时展示**：冷启动时从本地数据加载渲染，后台同步服务端增量数据后刷新
- **本地计算**：未读数统计、会话排序等基于本地数据完成，减少网络依赖

**实现**：采用 `expect/actual` 模式，`commonMain` 定义 `AppDatabase` 抽象，Android 和 Desktop 均使用 SQLDelight（分别对应 `AndroidSqliteDriver` 和 `JdbcSqliteDriver`）。`UserContext` 持有 `AppDatabase` 实例，TCP 推送的消息和 HTTP API 返回的数据统一写入本地 DB，ViewModel 优先从本地 DB 读取数据驱动 UI。

### 为什么采用单体架构

目标用户规模（<1 万）不需要微服务拆分。单体架构的优势：

- 开发调试方便：一个进程包含所有功能
- 部署运维简单：一个 JAR 包 + Docker 即可
- 无分布式事务和网络延迟问题
- 通过内存缓存获得最佳性能

---

## 2. 技术决策记录

| 编号 | 决策 | 选择 | 原因 |
|------|------|------|------|
| ADR-1 | UI 框架 | Compose Multiplatform | 声明式 UI，跨平台共享，Android 原生支持 |
| ADR-2 | 导航方案 | `mutableStateOf<NavDestination>` 手动管理 | 避免 Navigation Compose 的复杂嵌套导航，Desktop 三栏布局需要独立控制 |
| ADR-3 | 状态管理 | ViewModel + StateFlow | 单数据源，响应式更新，与 Compose 集成良好 |
| ADR-4 | 网络层 | Ktor Client | Kotlin 原生，支持多平台，与协程深度集成 |
| ADR-5 | 消息推送 | 自定义 TCP 二进制协议 | 比 WebSocket 更轻量，支持帧级别的流控和确认机制 |
| ADR-6 | 服务端框架 | Ktor + Netty | Kotlin 原生，轻量，适合单体架构 |
| ADR-7 | 数据库 | PostgreSQL + RocksDB | PostgreSQL 支持时序数据、GIS、全文检索、JSON 等多种数据类型，适合单体架构——避免未来引入监控、搜索等场景时依赖额外中间件（如 Elasticsearch）。社区趋势上 PostgreSQL 已逐渐超越 MySQL。消息存储用 RocksDB（LSM Tree，写入性能优秀） |
| ADR-8 | 序列化 | kotlinx.serialization | Kotlin 编译器插件，无反射，多平台支持 |
| ADR-9 | 文件存储 | MinIO | S3 兼容，自部署，无云厂商锁定 |
| ADR-10 | 平台差异 | expect/actual | Kotlin 官方机制，编译期保证类型安全 |
| ADR-11 | UI 层共享策略 | 基础组件共享 + 屏幕/导航各平台独立 | Desktop 和 Mobile 的交互模式、屏幕尺寸、业务需求差异大，共享屏幕代码反而限制各平台优化 UX |

---

## 3. 客户端分层

```
┌─ :app/commonMain（跨平台共享基础设施）────────────┐
│                                                     │
│  ┌─────────────────────────────────────────────┐   │
│  │  ViewModel (StateFlow)                      │   │
│  │  ChatViewModel / ConversationViewModel /    │   │
│  │  ContactsViewModel / SearchViewModel        │   │
│  └────────────────────┬────────────────────────┘   │
│                       ▼                             │
│  ┌─────────────────────────────────────────────┐   │
│  │  Repository (数据访问封装)                    │   │
│  │  ChatRepo / ConversationRepo / ContactRepo  │   │
│  │  ChannelRepo / UserRepo / FileRepo          │   │
│  └────────────────────┬────────────────────────┘   │
│                       ▼                             │
│  ┌──────────────┐  ┌──────────────┐                │
│  │  ApiClient   │  │  ImClient    │                │
│  │  (HTTP)      │  │  (TCP 长连接) │                │
│  └──────────────┘  └──────────────┘                │
│         │                  │                        │
│         ▼                  ▼                        │
│  ┌─────────────────────────────────────────────┐   │
│  │  LocalCache / AppDatabase (expect/actual)    │   │
│  │  消息 / 会话 / 联系人 / 频道本地持久化         │   │
│  │  Desktop: SQLDelight / Android: SQLDelight    │   │
│  └─────────────────────────────────────────────┘   │
│                                                     │
│  ┌─────────────────────────────────────────────┐   │
│  │  expect/actual 平台实现                      │   │
│  │  ServerConfig / TokenStorage / FilePicker   │   │
│  └─────────────────────────────────────────────┘   │
│                                                     │
│  ┌─────────────────────────────────────────────┐   │
│  │  UI 组件（共享基础组件）                      │   │
│  │  Avatar / MessageBubble / ChatInputBar /    │   │
│  │  ConversationItem / ImageViewer / ...       │   │
│  └─────────────────────────────────────────────┘   │
│                                                     │
│  NavDestination + MainTab（共享类型定义）            │
│  ThemeMode（共享主题枚举）                           │
└─────────────────────────────────────────────────────┘

┌─ :desktop（Desktop 独立 UI）───────────────────────┐
│  Main.kt → DesktopMainAppContent（三栏布局）        │
│  DesktopOverlayPanel / DesktopChatPanel / ...       │
│  ui/screen/ — 20 个 Desktop 屏幕（可独立演化）      │
└─────────────────────────────────────────────────────┘

┌─ :android（Android 独立 UI）───────────────────────┐
│  MainActivity → App.kt → MainAppContent（全屏导航） │
│  navigation/MainScreen.kt（底部 Tab）               │
│  ui/screen/ — 20 个 Android 屏幕（可独立演化）      │
└─────────────────────────────────────────────────────┘
```

### 数据流向

- **UI → ViewModel**：用户操作触发 ViewModel 方法调用（如 `sendMessage`）
- **ViewModel → Repository**：ViewModel 调用 Repository 获取/修改数据
- **Repository → ApiClient/ImClient**：Repository 将业务请求转换为 HTTP 或 TCP 调用
- **ApiClient/ImClient → 服务端**：实际网络 I/O
- **反向**：服务端推送 → ImClient → messageListeners → ViewModel 更新 StateFlow → Compose 重组

---

## 4. Desktop vs Mobile：UI 层分离策略

### 架构决策：基础组件共享，屏幕/导航各平台独立

> **ADR-11**：Desktop 和 Android 的屏幕代码和导航逻辑分别放在各自平台模块（`:desktop` 和 `:android`），`:app/commonMain` 仅保留共享的基础设施（组件 + ViewModel + Repository + Client）。

#### 为什么要分离

早期设计中 Desktop 和 Android 共享所有屏幕代码（放在 `:app/commonMain/ui/screen/`），但随着功能演进，两个平台的差异越来越大：

| 维度 | Desktop | Android |
|------|---------|---------|
| **交互方式** | 鼠标 + 键盘，悬停、右键菜单、快捷键 | 触控，手势滑动 |
| **屏幕尺寸** | 超大屏，三栏同屏 | 小屏，全屏单页面 |
| **布局模式** | 多面板并行展示，复杂弹窗 | 简单全屏导航，少用弹窗 |
| **链接处理** | 系统浏览器 | 内嵌 WebView |
| **登录方式** | 被扫码登录 | 扫码登录 Desktop |

共享屏幕代码意味着任何一方的 UX 优化都会受限于另一方的约束，两个平台都无法做到最佳体验。

#### 分离策略

- **`:app/commonMain` 保留**：ViewModel、Repository、ApiClient、TcpClient、UI 基础组件（Avatar、MessageBubble、ChatInputBar、ConversationItem 等）、NavDestination 类型定义、ThemeMode、expect/actual 平台适配
- **各平台独立拥有**：屏幕（Screen）、导航逻辑、入口 Composable

分离后各平台的屏幕代码**初始为相同副本**，后续按需各自演化。平台可根据自身特点自由调整布局、交互和业务逻辑，不受另一平台约束。

### Desktop：三栏布局

Desktop 采用三栏式布局（类似 Slack/Discord），由 `DesktopMainAppContent.kt` 组合：

```
┌──────────┬──────────────────┬──────────────────────┐
│ Sidebar  │   List Panel     │   Detail Panel       │
│ (72px)   │   (320px)        │   (剩余空间)          │
│          │                  │                       │
│ 🟢 Chats │  会话列表        │   聊天界面 / 空状态    │
│ 👤 Contacts│ 联系人列表     │   或 Overlay 面板      │
│ 📄 Docs  │                  │                       │
│ 🏠 Meet  │  (6个菜单项,     │                       │
│ 📅 Cal   │   前2个可用)     │                       │
│ ✅ Todo  │                  │                       │
└──────────┴──────────────────┴──────────────────────┘
```

- **Sidebar**：固定图标导航，切换 List Panel 内容
- **List Panel**：显示会话列表或联系人列表
- **Detail Panel**：显示聊天内容，或 Overlay 面板（设置、群详情等）

### Android：全屏导航

Android 采用全屏页面导航，每次只显示一个页面。`App.kt` 是路由分发点，通过 `NavDestination` sealed class 管理页面栈。

### 后续演化方向

分离完成后各平台可以独立推进的优化方向：

- **Desktop**：右键菜单、键盘快捷键、拖拽操作、多窗口、悬停预览、大屏信息密度优化
- **Android**：手势导航、WebView 内嵌、扫码功能、Material Design 3 适配、小屏布局优化

---

## 5. 数据流

### 消息发送流程

```
用户输入 → ChatScreen.onSendMessage(text)
         → scope.launch { chatVm.sendMessage(text) }
         → ChatViewModel.sendMessage(text)
           ├── 乐观更新：立即将消息添加到本地列表（状态：sending）
           └── chatRepo.sendTextMessage(channelId, channelType, text)
               → UserContext.sendMessage() → ImClient.enqueueMessage()
                 → TCP 发送 TEXT 包（PacketType.TEXT）
                   → 服务端 MessageHandler 收到（IOExecutor 协程）
                     → MessageService.sendMessage() 存储到 RocksDB
                     → SENDACK 返回发送者
                     → MessageDeliveryService 投递 RECV 给频道成员
                       → 接收者 ImClient 收到 RECV
                         → UserContext.onMessageReceived() 写入本地 DB
                           → messageListeners 多播
                             → ChatViewModel 刷新消息列表
```

### HTTP API 调用流程

```
Repository 方法
  → ctx.httpClient.get/post/put/delete(path) { ... }
    → Ktor HttpClient 请求
      → 服务端 Ktor 路由处理
        → Service 业务逻辑
          → Store → Database 查询/更新
    → 反序列化 JSON 响应
  → Repository 返回数据对象
```

### 会话管理

```
应用启动 → App.kt
  → ApiClient.restoreSession()  // 从 TokenStorage 恢复 token
  → if token 有效:
      → ImClient.connect()      // 建立 TCP 长连接
      → ConversationViewModel.refresh()  // 拉取会话列表
      → ImClient 持续监听 RECV 帧
        → messageListeners 多播
          → ConversationViewModel 更新未读数
          → ChatViewModel 刷新消息列表（如果正在查看对应会话）
```

---

## 6. 编码约定

### 文件组织

- **一个文件一个职责**：每个文件聚焦一个功能，文件名与主要类/函数名一致
- **文件大小限制**：单文件不超过 400 行，超过时按功能拆分
- **包结构**：按功能分层（`client`、`repository`、`viewmodel`、`ui/screen`、`ui/component`）

### 命名规范

- **Screen**：`XxxScreen` composable 函数（如 `ChatScreen`、`GroupDetailScreen`）
- **ViewModel**：`XxxViewModel` 类，暴露 `state: StateFlow<XxxState>`
- **Repository**：`XxxRepository` 类，方法名反映业务操作（如 `getMessages`、`sendMessage`）
- **DTO**：`XxxDto` data class，对应服务端 JSON 响应
- **Composable 组件**：`XxxContent`、`XxxBar`、`XxxItem` 等

### 不可变数据

- 所有 data class 使用 `val` 属性
- 状态更新通过创建新对象（`copy()`）而非修改现有对象
- Compose 状态使用 `mutableStateOf` / `mutableStateListOf`，仅在 ViewModel 内部持有

### 错误处理

- Repository 层捕获网络异常，转换为业务异常
- ViewModel 层捕获异常，更新 error 状态供 UI 展示
- UI 层通过 `try/catch` 包裹用户操作，展示 Snackbar 或 error 文本
- 使用 `AppLog` 记录异常上下文

### 平台差异处理

- `expect/actual` 仅用于必须依赖平台 API 的功能（文件 I/O、系统属性、存储）
- 平台无关的逻辑放在 `commonMain`
- 屏幕和导航代码放在各平台模块（`:desktop`、`:android`），不放在 `commonMain`
- Desktop 特有的 UI 布局代码放在 `desktop` 模块
- 共享 UI 组件（Avatar、MessageBubble 等）放在 `commonMain` 的 `ui/component/`

---

## 7. 编程范式与约束

> 本节总结项目中反复出现的编程范式和硬性约束，供新功能设计和代码审查时作为参考。
> 每条规则都来自实际踩坑经验，违反这些规则会导致 NPE、竞态、内存泄漏等问题。

### 7.1 Compose nullable state 必须智能捕获

**规则**：在 Compose 中判断 nullable `MutableState` 后，**必须**先捕获到局部变量再使用，禁止在 `if` 块内使用 `!!`。

**反例**（NPE）：
```kotlin
if (userContext != null) {
    val state by userContext!!.connectionState.collectAsState()  // 💥 NPE
    SomeComposable(userContext!!)
}
```

**正例**：
```kotlin
val ctx = userContext  // 智能捕获到非空局部变量
if (ctx != null) {
    val state by ctx.connectionState.collectAsState()  // ✅ 安全
    SomeComposable(ctx)
}
```

**原因**：`MutableState` 的值可以在 Compose recomposition 的任何时刻被外部线程修改（如 Netty EventLoop、协程）。`if` 判断和 `!!` 取值不在同一个原子操作中，中间可能插入一次 recomposition，导致 `!!` 时值已变为 `null`。捕获到 `val` 后，Kotlin 智能转换保证 `ctx` 在整个块内非空。

**适用范围**：所有在 Compose 中访问 `MutableState<T?>` 的场景——包括 `userContext`、`navDestination`、平台入口中的任何 nullable 状态。

### 7.2 跨层事件传播：回调优于共享状态

**规则**：底层（TCP/HTTP）需要通知顶层（UI/导航）时，使用**回调链**而非共享可变状态。

**模式**：
```
TcpConnection (Netty) → ImClient → UserContext → 平台入口(Main.kt / App.kt)
```

每层通过接口或回调向上传播事件，上层决定如何响应：
- `ImClient` 实现 `ConnectionListener`，在 `onAuthFailed` 中通知 `ImStateListener`
- `UserContext` 实现 `ImStateListener`，在 `onAuthFailed` 中触发 `onForceLogout`
- 平台入口设置 `onForceLogout`，执行 `destroy()` + 置空 `userContext`

**为什么不用共享状态**：底层不知道上层的导航逻辑（Desktop 是 `userContext = null`，Android 还需要 `navDestination = NavDestination.Login`），共享状态无法表达这种差异。

**约束**：
- 回调可能从任意线程调用（Netty EventLoop、HTTP 协程），回调内部的 `MutableState` 赋值依赖 Compose 的线程安全保证（`mutableStateOf` 赋值是线程安全的）
- 回调可能被多次触发（TCP auth fail + HTTP 401 同时发生），后续操作（`destroy()`、`userContext = null`）必须**幂等**

### 7.3 Netty 线程模型约束

**规则**：`ImClient` 内部所有状态操作必须在同一个 `EventLoop` 上执行，禁止在外部线程直接访问内部状态。

**实现**：所有 public API 通过 `doOnMainThread()` 分发到 `EventLoop`：
```kotlin
fun subscribe(channelId: String, lastSeq: Long) {
    doOnMainThread {  // 确保在 EventLoop 上执行
        val conn = currentConnection ?: return@doOnMainThread
        conn.sendProto(SubscribePayload(channelId, lastSeq))
    }
}
```

**服务端对应**：`ImAgent` 根据包类型决定在 Netty EventLoop（轻量操作如 PING/PONG/RECVACK）还是 `IOExecutor`（重量操作如消息处理、数据库写入）上执行。

**设计新功能时的检查清单**：
- 新增的 `ImClient` 方法是否通过 `doOnMainThread` 分发？
- 回调（`stateListener.onXxx`）是在 EventLoop 上调用的，listener 实现是否处理了线程切换？
- 新增的服务端 handler 是否正确选择了 EventLoop vs IOExecutor？

### 7.4 销毁操作必须幂等

**规则**：`destroy()` / `disconnect()` / `close()` 等清理方法必须可安全多次调用。

**原因**：在跨层回调场景下，同一个对象的 `destroy()` 可能被多个路径触发：
- 用户主动登出
- TCP 认证失败回调
- HTTP 401 回调
- Session 过期检查

这些事件可能几乎同时发生，或在单次 destroy 过程中再次触发。

**实现要点**：
- `ImClient.disconnect()`：`destroyed` 标志位 + `currentConnection?.close()`（`?.` 对 null 安全）
- `UserContext.destroy()`：`disconnectTcp()`（幂等）+ `scope.cancel()`（幂等）+ `tokenStorage.clear()`（幂等）+ `list.clear()`（幂等）
- 不要在 destroy 中抛异常或断言"未销毁"

### 7.5 认证失效的策略：停而非重试

**规则**：认证失败（token 过期 / 服务端 secret 变化）时**停止重连**，向上传播事件，由上层决定处理方式。不要在底层无限重试。

**原因**：认证失败是不可恢复的——无论重试多少次，过期的 token 或变化的 secret 都不会自动恢复。无限重试只会产生无意义的网络请求和日志噪音。

**适用场景**：
- TCP 握手返回 auth failed → `ImClient` 停止重连，通知 `UserContext`
- HTTP API 返回 401 → `ApiClient` 拦截，通知 `UserContext`
- `UserContext` 触发 `onForceLogout` → 清除 session → 回到登录页

**对比可恢复错误**：
- 网络断开 / TCP 连接中断 → **应该重连**（指数退避）
- 服务端暂时不可用（503）→ **应该重试**（HTTP 层）
- 认证失败（401 / TCP auth reject）→ **不应重试**，向上传播

### 7.6 Compose 窗口生命周期与状态变更的竞态

**规则**：将 `userContext` 置为 `null` 后，Compose 的主窗口不会立即消失——当前 recomposition cycle 中的窗口内容仍会执行。必须确保窗口内容不依赖"状态不变"的假设。

**具体表现**：
1. `forceLogout` 回调将 `userContext` 设为 `null`
2. Compose 调度下一次 recomposition
3. 在 recomposition 执行前，主窗口的 composition 仍存在，可能还在读取 `userContext` 的属性
4. 下一次 recomposition 时 `if (userContext != null)` 为 false，主窗口被移除，登录窗口被创建

**这意味着**：
- 窗口内的 composable **不能假设** `userContext` 在整个 composition 生命周期内不变
- 必须通过 7.1 的智能捕获模式避免竞态
- `onLogout` 回调中 `ctx.destroy()` 后 `userContext = null` 的顺序是正确的——先清理资源，再触发 UI 变更

### 7.7 TCP 协议层的包类型分发策略

**规则**：新增 PacketType 时，需要同时在 `ImClient.handleProto()` 和服务端对应的 Handler 中添加处理分支。

**决策流程**：
1. 在 `shared/.../protocol/PacketType.kt` 中定义新类型
2. 在 `shared/.../protocol/payload/` 中定义对应的 `IProto` 实现
3. **客户端**：在 `ImClient.handleProto()` 中添加分支（消息类走 `handleMessageProto`，其他走独立处理）
4. **服务端**：在 `ImAgent.dispatchAuthedPacket()` 的 `when` 分支中添加路由，重量操作用 `IOExecutor.execute`
5. 如果需要通知 UI，在 `ImStateListener` 中添加默认方法，然后在 `UserContext` 中实现

**编码范围约定**（参见 CLAUDE.md PacketType 编码范围表）：
- 1-9：连接控制
- 10-19：会话管理
- 20-36：消息（双向统一）
- 80-81：确认应答
- 90-98：系统消息（S→C）
- 100-102：命令与控制（S→C）

新增类型时在对应范围内找空位，不要跨范围占用。

---

## 8. 服务端编程范式与约束

> 本节总结服务端的分层架构、数据模型约束和并发策略。服务端采用**单体 + 内存优先**架构，
> 所有设计决策都基于"用户规模 <1 万、单机部署"这个前提。

### 8.1 四层架构与严格的数据流方向

```
┌─────────────────────────────────────────────────────────┐
│ API 层 (api/)       HTTP 路由，参数校验，JWT 认证         │
│ TCP Handler 层      Netty pipeline 中的各 Handler         │
├─────────────────────────────────────────────────────────┤
│ Service 层 (service/)  业务逻辑，权限校验，多 Store 协调   │
├─────────────────────────────────────────────────────────┤
│ Store 层 (store/)      全量内存缓存 + DB 读写              │
├─────────────────────────────────────────────────────────┤
│ DAO 层 (db/)           Exposed SQL / RocksDB 原始操作      │
└─────────────────────────────────────────────────────────┘
```

**硬性约束**：

1. **API 层和 TCP Handler 层只能调用 Service**，禁止直接访问 Store 或 DAO
2. **Service 层只能通过 Store 访问数据**，禁止直接调用 DAO（MessageStore 除外，因为它是 RocksDB 的封装）
3. **Store 层封装所有内存缓存逻辑**，对上层暴露领域语义的方法，不暴露缓存实现细节
4. **DAO 层是 Store 的私有实现细节**，只在 Store 内部使用

**数据流方向**：

```
HTTP/TCP 请求 → Service → Store → DAO → PostgreSQL/RocksDB
                                    ↑
                               内存缓存（读命中时直接返回）
```

**反向数据流（TCP 推送）**：

```
Store 数据变更 → Service 构造推送 payload → MessageDeliveryService → ClientRegistry → ImAgent → Netty Channel
```

### 8.2 为什么内存优先

**前提**：TeamTalk 定位为面向中小型组织（<1 万用户）的办公 IM，采用单体架构单机部署。

**在这个规模下**：
- 1 万用户 × 每用户约 1KB 数据 ≈ 10MB 内存，完全可控
- 关系数据（用户、频道、好友、会话、邀请链接）总量有限，启动时全量加载到内存
- 消息数据量虽大，但使用 RocksDB（LSM Tree）单独存储，不进入内存缓存

**内存优先的收益**：
- **读操作零延迟**：所有查询走 `ConcurrentHashMap`，无 SQL 解析、无磁盘 IO、无连接池开销
- **无缓存一致性难题**：不存在"缓存过期""缓存击穿"问题，因为缓存是唯一读源
- **简化代码**：不需要 LRU/LFU 淘汰策略、不需要缓存预热、不需要分布式缓存协议

**不可内存优先的数据**：
- **消息内容**：存储在 RocksDB，按需读取（消息量大且通常只访问最近的）
- **搜索索引**：Lucene 磁盘索引，启动时加载段文件
- **文件数据**：MinIO 对象存储

### 8.3 Store 层的三种读写模式

Store 层是内存优先架构的核心。每个 Store（UserStore、ChannelStore、ConversationStore、ContactStore、DeviceStore、InviteLinkStore）遵循相同的三种读写模式：

#### 模式一：读操作（纯内存，非 suspend）

```kotlin
// UserStore.kt — 零 DB 访问
fun findByUid(uid: String): UserRow? = byUid[uid]
fun findByUsername(username: String): UserRow? = byUsername[username]
fun search(query: String, limit: Int = 20): List<UserRow> { ... }
```

```kotlin
// ChannelStore.kt — 禁言检查也是纯内存
fun isMember(channelId: String, uid: String): Boolean = memberRoles[channelId]?.containsKey(uid) == true
fun isMutedAll(channelId: String): Boolean = channelMutedAll[channelId] ?: false
fun getMemberUids(channelId: String): List<String> = memberUids[channelId]?.toList() ?: emptyList()
```

**约束**：
- 读方法**不能**是 `suspend`，因为不涉及任何 IO
- 读方法**不能**访问数据库，返回值直接从内存 Map 取
- 返回 null 表示未找到，不要抛异常

#### 模式二：写操作（先 DB 后内存，suspend）

```kotlin
// UserStore.kt — 同步写 DB + 同步更新内存
suspend fun create(...): UserRow {
    val user = UserDao.create(...)      // 1. 先写 DB（suspend，可能失败）
    indexUser(user)                      // 2. 写入成功后更新内存（同步）
    return user
}

suspend fun updateProfile(uid: String, name: String?, avatar: String?, sex: Int?) {
    UserDao.updateProfile(uid, name, avatar, sex)  // 1. DB 先行
    val existing = byUid[uid] ?: return
    val updated = existing.copy(...)                // 2. copy 新对象（不可变）
    indexUser(updated)                              // 3. 替换内存引用
}
```

**约束**：
- 写方法**必须**是 `suspend`（因为 DB 操作是 IO）
- **DB 先行**：先写数据库，再更新内存。如果 DB 写入失败，抛异常，内存不被污染
- **不可变更新**：使用 `data class.copy()` 创建新对象替换内存中的旧引用，不要修改旧对象的属性
- 这意味着 DB 写入失败时，内存和 DB 仍然一致（都是旧值）

#### 模式三：高频计数器（内存先行，异步 DB，非 suspend）

```kotlin
// ChannelStore.kt — 消息序号递增
fun incrementMaxSeq(channelId: String): Long {
    val seq = channelMaxSeq.getOrPut(channelId) { AtomicLong(0) }
    val newSeq = seq.incrementAndGet()     // 1. 内存原子递增（纳秒级）
    ioScope.launch {                        // 2. 异步持久化到 DB（毫秒级）
        try {
            ChannelDao.setMaxSeq(channelId, newSeq)
        } catch (e: Exception) {
            logger.warn("Failed to persist maxSeq for channel {}: {}", channelId, e.message)
        }
    }
    return newSeq                           // 3. 立即返回，不等 DB
}
```

**为什么这里可以内存先行**：
- 消息序号是单调递增的，`AtomicLong` 保证了即使并发也不会重复
- DB 持久化失败只影响服务重启后的恢复精度，不影响当前消息的正确性
- 这个操作在消息发送热路径上，每次消息发送都会调用，必须是纳秒级

**约束**：
- 只有**高频 + 可容忍短暂不一致**的场景才用这种模式（目前只有 maxSeq）
- 异步 DB 写入**必须** try-catch，失败只记 warn 日志，不能影响主流程
- `ioScope` 使用 `SupervisorJob`，一个异步写入失败不会取消后续写入

### 8.4 Store 的内存数据结构选型

| 数据特征 | 选型 | 原因 |
|----------|------|------|
| uid→用户、channelId→频道 | `ConcurrentHashMap<String, Row>` | 一对一映射，高频读 |
| 频道成员列表 | `ConcurrentHashMap<String, MutableList<String>>` + `CopyOnWriteArrayList` | 一对多，读多写少 |
| 频道成员角色 | `ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>` | 一对多，需要 uid→role 快速查找 |
| 用户会话列表 | `ConcurrentHashMap<String, CopyOnWriteArrayList<ConversationRow>>` | 一对多，遍历多于修改 |
| 频道序号计数器 | `ConcurrentHashMap<String, AtomicLong>` | 原子递增 |
| 禁言集合 | `ConcurrentHashMap<String, MutableSet<String>>` + `ConcurrentHashMap.newKeySet()` | 集合操作，contains 高频 |

**选型原则**：
- **读远多于写**时用 `CopyOnWriteArrayList`（会话列表、成员列表），避免读时加锁
- **需要原子递增**时用 `AtomicLong`（序号计数器）
- **一对一映射**直接 `ConcurrentHashMap`，简单高效
- **不要用** `synchronized` 块或 `ReentrantLock`，JUC 并发容器已经足够

### 8.5 Service 层的职责边界

Service 是业务逻辑的核心承载层。它的职责和边界：

**必须做的事**：
- 权限校验（`requireMember`、`requireRole`）
- 多 Store 协调（如 `ChannelService.createGroup` 协调 `ChannelStore` + `ConversationStore`）
- 响应对象构造（`ChannelRow` → `ChannelResponse`）
- 触发副作用（系统消息广播、搜索索引更新）

**禁止做的事**：
- 直接调用 DAO（必须通过 Store）
- 直接操作 `ConcurrentHashMap`（缓存更新由 Store 负责）
- 在构造函数参数中接收 DAO（依赖 Store，不依赖 DAO）

**典型模式**：

```kotlin
class ChannelService(
    private val channelStore: ChannelStore,          // 只依赖 Store
    private val conversationStore: ConversationStore,
    private val inviteLinkStore: InviteLinkStore,
) {
    suspend fun createGroup(ownerUid: String, req: CreateGroupRequest): ChannelRow {
        // 1. 业务校验 + Store 调用
        val channel = channelStore.create(...)
        channelStore.addMember(...)

        // 2. 跨 Store 协调
        conversationStore.createOrUpdate(ownerUid, channelId, 2)

        // 3. 副作用
        logger.info("Group created: {} by {}", channelId, ownerUid)
        return channel
    }
}
```

### 8.6 TCP Handler 层：EventLoop 与 IOExecutor 的分界

```
Netty EventLoop（轻量，不阻塞）
  ├── ImAgent: PING → PONG, DISCONNECT, RECVACK
  ├── ImAgent: AuthRequestPayload → AuthProcessor（纯内存 Token 验证）
  └── ImAgent: TypingBody → TypingDispatcher（EventLoop 直接转发）

IOExecutor callback（重量，可阻塞）
  ├── ImAgent: Message → MessageDispatcher（消息存储 + 投递）
  ├── ImAgent: SubscribePayload → SubscribeDispatcher（消息同步）
  └── 任何涉及 DB 操作的逻辑
```

**规则**：

1. **EventLoop 上禁止阻塞操作**：任何 DB 读写、文件 IO、重量计算都必须 dispatch 到 `IOExecutor`
2. **纯内存的读操作可以在 EventLoop 上执行**：如 `channelStore.isMutedAll()`、`channelStore.isMember()`
3. **dispatch 方式**：`IOExecutor.execute { ... }`（callback + WeakReference 模式），**禁止在 TCP 入口使用协程**
4. **Netty Channel 写入是线程安全的**：`ctx.writeAndFlush()` 在 IOExecutor 线程中调用是安全的，Netty 内部会提交到对应的 EventLoop

**设计新 Dispatcher 时的决策流程**：

```
ImAgent 收到包
  ├── 操作是否纯内存（查 Store / 构造响应）？
  │     └── 是 → 直接在 EventLoop 处理
  └── 操作涉及 DB / 文件 IO / 重量计算？
        └── 是 → IOExecutor.execute { ... } + WeakReference
```

### 8.7 启动加载与数据一致性

**启动流程**：

```
1. DatabaseFactory.init()     — HikariCP 连接池
2. MessageStore.start()        — RocksDB 打开 + seq 计数器加载
3. 各 Store.loadAll()          — 从 PostgreSQL 全量加载到内存
   ├── UserStore.loadAll()
   ├── ChannelStore.loadAll()  (频道 + 成员 + 禁言)
   ├── ContactStore.loadAll()
   ├── ConversationStore.loadAll()
   ├── DeviceStore.loadAll()
   └── InviteLinkStore.loadAll()
4. SearchIndex.init()          — Lucene 索引打开（可选全量重建）
5. TcpServer.start()           — 开始接受连接
6. Ktor HTTP server.start()    — 开始接受 HTTP 请求
```

**约束**：
- TCP 和 HTTP 服务**必须在所有 Store 加载完成后才启动**，否则请求可能读到不完整的内存数据
- `loadAll()` 是阻塞调用，在 Ktor Application 启动前同步执行
- RocksDB 的 seq 计数器通过全表扫描加载，数据量大时注意启动耗时

### 8.8 写入路径上的错误容忍策略

服务端在写入路径上有三种错误容忍级别：

| 级别 | 行为 | 典型场景 |
|------|------|---------|
| **关键**：DB 写入失败 → 回滚整个操作，返回错误 | 用户注册、群组创建、好友申请 |
| **重要**：DB 写入成功，副作用失败 → 记日志，不回滚 | 消息已存储但搜索索引更新失败、会话未读数更新失败 |
| **非关键**：内存先行，DB 异步持久化失败 → 记 warn，不影响返回 | maxSeq 递增 |

```kotlin
// 关键路径：DB 失败直接抛异常
suspend fun create(...): UserRow {
    val user = UserDao.create(...)  // 失败 → 异常传播到上层
    indexUser(user)
    return user
}

// 重要但可容忍：副作用失败不影响主流程
suspend fun sendMessage(...): MessageRecord {
    val stored = messageStore.storeMessage(record)  // 关键：必须成功
    try {
        channelStore.incrementMaxSeq(channelId)     // 重要
        conversationStore.createOrUpdate(...)        // 重要
    } catch (e: Exception) {
        logger.warn("Failed to update stats: {}", e.message)  // 不回滚消息存储
    }
    try {
        searchIndex?.indexMessage(stored)            // 非关键
    } catch (e: Exception) {
        logger.warn("Failed to index: {}", e.message)
    }
    return stored
}
```

**设计新功能时**：明确每个写入操作属于哪个容忍级别，不要把所有操作都放在同一个 try-catch 中。

### 8.9 不可变数据对象

**规则**：所有 `Row` / `Record` data class 使用 `val` 属性。Store 更新缓存时通过 `copy()` 创建新对象替换引用。

```kotlin
// ChannelStore.kt
suspend fun update(channelId: String, name: String?, ...) {
    ChannelDao.update(channelId, name, ...)
    val existing = channels[channelId] ?: return
    val updated = existing.copy(          // 新对象
        name = name ?: existing.name,
        avatar = avatar ?: existing.avatar,
    )
    channels[channelId] = updated         // 替换引用
}
```

**为什么不用可变属性**：
- 多个线程可能同时持有旧引用（Service 层正在构造响应），替换引用保证所有新请求看到新值，旧请求看到一致的旧值
- `ConcurrentHashMap` 的 `put` 是原子的，替换引用操作天然线程安全
- 不可变对象消除了"读了一半被写"的问题

### 8.10 新增功能时的分层检查清单

当需要在服务端添加新功能时，按以下清单逐层实现：

```
□ DAO 层：在 Tables.kt 中定义新表（如果需要），在对应 XxxDao 中添加 SQL 方法
□ Store 层：在对应 Store 中添加内存缓存字段 + loadAll + 读方法 + 写方法
□ Service 层：在对应 Service 中添加业务方法（含权限校验、多 Store 协调、副作用）
□ API 层：在对应 Routes 中添加 HTTP 路由（或新增 Routes 文件）
□ TCP 层（如果需要实时推送）：
  □ shared/protocol/ 中定义新 PacketType 和 Payload
  □ 新增或修改 Dispatcher（`server/tcp/agent/` 下新建 object）
  □ ImAgent.dispatchAuthedPacket() 中添加路由
  □ Dispatcher 中判断 EventLoop vs IOExecutor
  □ MessageDeliveryService 中添加推送逻辑（如需广播）
```
