# 架构设计文档

> 最后更新: 2026-04-26

本文档记录 TeamTalk 的架构设计理念、技术决策和编码约定。

---

## 1. 设计理念

### 全栈 Kotlin

TeamTalk 的核心目标是让开发者用 **一门语言（Kotlin）** 维护整个项目——从服务端到 Android、Desktop 客户端。KMP + Compose Multiplatform 使这一目标成为可能：

- **shared 模块**：协议定义和编解码代码在服务端和客户端之间完全共享
- **app 模块**：跨平台共享基础设施（组件 + ViewModel + Repository + Client），不含屏幕和导航
- **平台模块**：Android 和 Desktop 各自拥有独立的屏幕代码和导航逻辑，基于共享组件组装页面

> **iOS**：当前未覆盖。Desktop 和 Android 同属 JVM 体系可直接使用 Java 生态库，iOS 要求纯 Kotlin/Native 实现，未来启动时需排查 `commonMain` 中的 Java API 依赖。

### 单体架构

目标用户规模（<1 万）不需要微服务拆分：

- 开发调试方便：一个进程包含所有功能
- 部署运维简单：一个分发包 + Docker 即可
- 无分布式事务和网络延迟问题
- 通过内存缓存获得最佳性能

### 本地数据库

客户端通过本地数据库实现离线可用和即时响应——这是办公 IM 的基本能力：

- **离线可用**：断网时用户仍可查看历史消息、联系人和会话列表
- **即时展示**：冷启动时从本地数据加载渲染，后台同步服务端增量数据后刷新
- **本地计算**：未读数统计、会话排序等基于本地数据完成

实现采用 `expect/actual` 模式，`commonMain` 定义 `AppDatabase` 抽象，Android 和 Desktop 均使用 SQLDelight。`UserContext` 持有 `AppDatabase` 实例，TCP 推送和 HTTP 返回的数据统一写入本地 DB。

### 无 DI 框架

项目未使用 DI 框架，依赖通过构造函数和参数传递。当前规模下可行，随着项目增长可考虑引入 Koin 等轻量方案。

---

## 2. 技术决策

| 编号 | 决策 | 选择 | 原因 |
|------|------|------|------|
| ADR-1 | UI 框架 | Compose Multiplatform | 声明式 UI，跨平台共享，Android 原生支持 |
| ADR-2 | 导航方案 | `mutableStateOf<NavDestination>` 手动管理 | 避免 Navigation Compose 的复杂嵌套导航，Desktop 三栏布局需要独立控制 |
| ADR-3 | 状态管理 | ViewModel + StateFlow | 单数据源，响应式更新，与 Compose 集成良好 |
| ADR-4 | 网络层 | Ktor Client | Kotlin 原生，支持多平台，与协程深度集成 |
| ADR-5 | 消息推送 | 自定义 TCP 二进制协议 | 比 WebSocket 更轻量，支持帧级别的流控和确认机制 |
| ADR-6 | 服务端框架 | Ktor + Netty | Kotlin 原生，轻量，适合单体架构 |
| ADR-7 | 数据库 | PostgreSQL + RocksDB | PostgreSQL 功能丰富，适合单体架构避免引入额外中间件。消息存储用 RocksDB（LSM Tree，写入性能优秀） |
| ADR-8 | 序列化 | kotlinx.serialization | Kotlin 编译器插件，无反射，多平台支持 |
| ADR-9 | 文件存储 | MinIO | S3 兼容，自部署，无云厂商锁定 |
| ADR-10 | 平台差异 | expect/actual | Kotlin 官方机制，编译期保证类型安全 |
| ADR-11 | UI 层共享策略 | 基础组件共享 + 屏幕/导航各平台独立 | Desktop 和 Mobile 交互模式差异大，共享屏幕代码反而限制各平台优化 UX |

---

## 3. 客户端架构

### 分层

```
┌─ :app/commonMain（跨平台共享基础设施）────────────┐
│                                                     │
│  ViewModel (StateFlow)                              │
│    ChatViewModel / ConversationViewModel /          │
│    ContactsViewModel / SearchViewModel              │
│                       ▼                             │
│  Repository (数据访问封装)                            │
│    ChatRepo / ConversationRepo / ContactRepo /      │
│    ChannelRepo / UserRepo / FileRepo                │
│                       ▼                             │
│  ApiClient (HTTP)    ImClient (TCP 长连接)           │
│         │                  │                        │
│         ▼                  ▼                        │
│  LocalCache / AppDatabase (expect/actual)           │
│  ServerConfig / TokenStorage / FilePicker           │
│  Avatar / MessageBubble / ChatInputBar / ...        │
│                                                     │
│  NavDestination + MainTab + ThemeMode（共享类型）    │
└─────────────────────────────────────────────────────┘

┌─ :desktop ─────────────────────────────────────────┐
│  Main.kt → DesktopMainAppContent（三栏布局）        │
│  ui/screen/ — 20 个 Desktop 屏幕                    │
└─────────────────────────────────────────────────────┘

┌─ :android ─────────────────────────────────────────┐
│  MainActivity → App.kt → MainAppContent（全屏导航） │
│  ui/screen/ — 20 个 Android 屏幕                    │
└─────────────────────────────────────────────────────┘
```

Desktop 和 Android 各自拥有独立的屏幕代码和导航逻辑，`:app/commonMain` 仅保留共享基础设施。

### 数据流向

- **UI → ViewModel**：用户操作触发 ViewModel 方法调用（如 `sendMessage`）
- **ViewModel → Repository**：ViewModel 调用 Repository 获取/修改数据
- **Repository → ApiClient/ImClient**：Repository 将业务请求转换为 HTTP 或 TCP 调用
- **反向**：服务端推送 → ImClient → messageListeners → ViewModel 更新 StateFlow → Compose 重组

### Desktop 三栏布局

```
┌──────────┬──────────────────┬──────────────────────┐
│ Sidebar  │   List Panel     │   Detail Panel       │
│ (72dp)   │   (320dp)        │   (自适应宽度)        │
│          │                  │                       │
│ Chats    │  会话列表        │   聊天 / Overlay 面板  │
│ Contacts │  联系人列表      │                       │
│ (6 菜单) │                  │                       │
└──────────┴──────────────────┴──────────────────────┘
```

- **Sidebar**：固定图标导航，切换 List Panel 内容
- **List Panel**：显示会话列表或联系人列表
- **Detail Panel**：显示聊天内容，或 Overlay 面板（设置、群详情等）

### Android 全屏导航

Android 采用全屏页面导航，每次只显示一个页面。`App.kt` 是路由分发点，通过 `NavDestination` sealed class 管理页面栈。

---

## 4. 服务端架构

### 四层架构

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

1. **API 层和 TCP Handler 只能调用 Service**，禁止直接访问 Store 或 DAO
2. **Service 层只能通过 Store 访问数据**，禁止直接调用 DAO（MessageStore 除外）
3. **Store 层封装所有内存缓存逻辑**，对上层暴露领域语义的方法
4. **DAO 层是 Store 的私有实现细节**

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

### 内存优先

基于"用户规模 <1 万、单机部署"的前提，关系数据（用户、频道、好友、会话等）启动时全量加载到内存（约 10MB），读操作走 `ConcurrentHashMap` 零延迟。消息内容和搜索索引不进入内存缓存，分别由 RocksDB 和 Lucene 管理。

### Store 层的三种读写模式

#### 读操作（纯内存，非 suspend）

返回值直接从内存 Map 取，不访问数据库，不抛异常（未找到返回 null）。

```kotlin
fun findByUid(uid: String): UserRow? = byUid[uid]
fun isMember(channelId: String, uid: String): Boolean = memberRoles[channelId]?.containsKey(uid) == true
```

#### 写操作（先 DB 后内存，suspend）

先写数据库，成功后更新内存。DB 失败则抛异常，内存不被污染。使用 `data class.copy()` 创建新对象替换引用。

```kotlin
suspend fun create(...): UserRow {
    val user = UserDao.create(...)      // 1. 先写 DB
    indexUser(user)                      // 2. 成功后更新内存
    return user
}
```

#### 高频计数器（内存先行，异步 DB，非 suspend）

仅用于 `maxSeq` 等高频 + 可容忍短暂不一致的场景。`AtomicLong` 原子递增后立即返回，异步持久化到 DB（必须 try-catch）。

```kotlin
fun incrementMaxSeq(channelId: String): Long {
    val newSeq = seq.incrementAndGet()     // 内存原子递增
    ioScope.launch { ChannelDao.setMaxSeq(channelId, newSeq) }  // 异步 DB
    return newSeq
}
```

### Service 层的职责

- 权限校验（`requireMember`、`requireRole`）
- 多 Store 协调
- 响应对象构造
- 触发副作用（系统消息广播、搜索索引更新）

Service 只依赖 Store，不直接调用 DAO，不直接操作内存缓存。

### TCP 线程模型

```
Netty EventLoop（轻量，不阻塞）
  ├── PING → PONG, DISCONNECT, RECVACK
  ├── AuthProcessor（纯内存 Token 验证）
  └── TypingDispatcher（直接转发）

IOExecutor（重量，可阻塞）
  ├── MessageDispatcher（消息存储 + 投递）
  ├── SubscribeDispatcher（消息同步）
  └── 任何涉及 DB 的操作
```

EventLoop 上禁止阻塞操作，纯内存的读操作（查 Store）可以在 EventLoop 上执行。dispatch 方式为 `IOExecutor.execute { ... }`（callback + WeakReference），**禁止在 TCP 入口使用协程**。

### 启动流程

```
1. DatabaseFactory.init()     — HikariCP 连接池
2. MessageStore.start()       — RocksDB 打开 + seq 计数器加载
3. 各 Store.loadAll()         — PostgreSQL 全量加载到内存
4. SearchIndex.init()         — Lucene 索引打开
5. TcpServer.start()          — 开始接受连接
6. Ktor HTTP server.start()   — 开始接受 HTTP 请求
```

TCP 和 HTTP 服务必须在所有 Store 加载完成后才启动。

### 写入路径上的错误容忍

| 级别 | 行为 | 典型场景 |
|------|------|---------|
| **关键** | DB 写入失败 → 回滚，返回错误 | 用户注册、群组创建 |
| **重要** | DB 写入成功，副作用失败 → 记日志，不回滚 | 搜索索引更新失败、会话未读数更新失败 |
| **非关键** | 内存先行，DB 异步持久化失败 → 记 warn | maxSeq 递增 |

---

## 5. 数据流

### 消息发送

```
用户输入 → ChatViewModel.sendMessage(text)
  ├── 乐观更新：立即添加到本地列表（状态：sending）
  └── chatRepo.sendTextMessage() → ImClient.enqueueMessage()
        → TCP 发送 TEXT 包
          → 服务端 MessageDispatcher（IOExecutor）
            → MessageService 存储到 RocksDB
            → SENDACK 返回发送者
            → MessageDeliveryService 投递 RECV 给频道成员
              → 接收者 ImClient.onMessageReceived()
                → 写入本地 DB → messageListeners 多播
                  → ChatViewModel 刷新消息列表
```

### 会话恢复

```
应用启动 → ApiClient.restoreSession()
  → if token 有效:
      → ImClient.connect()      // TCP 长连接
      → ConversationViewModel.refresh()
      → ImClient 持续监听 RECV
        → ConversationViewModel 更新未读数
        → ChatViewModel 刷新消息列表
```

---

## 6. 编码约定

### 文件与命名

- 一个文件一个职责，单文件不超过 400 行
- Screen：`XxxScreen` composable 函数
- ViewModel：`XxxViewModel`，暴露 `state: StateFlow<XxxState>`
- Repository：`XxxRepository`，方法名反映业务操作
- DTO：`XxxDto` data class
- Composable 组件：`XxxContent`、`XxxBar`、`XxxItem`

### 不可变数据

所有 data class 使用 `val` 属性，状态更新通过 `copy()` 创建新对象。Compose 状态使用 `mutableStateOf` / `mutableStateListOf`，仅在 ViewModel 内部持有。

### 平台差异

- `expect/actual` 仅用于依赖平台 API 的功能（文件 I/O、存储等）
- 屏幕和导航代码放在各平台模块，不放在 `commonMain`
- 共享 UI 组件放在 `commonMain` 的 `ui/component/`

### Compose nullable state

在 Compose 中访问 nullable `MutableState` 时，必须先捕获到局部变量：

```kotlin
val ctx = userContext   // 智能捕获
if (ctx != null) {
    ctx.connectionState.collectAsState()  // 安全
}
```

禁止在 `if` 块内使用 `!!`——recomposition 期间状态可能被外部线程修改，`if` 和 `!!` 不在同一原子操作中。

### 销毁操作幂等

`destroy()` / `disconnect()` 等清理方法必须可安全多次调用。在跨层回调场景下，同一对象可能被多个路径触发销毁（用户登出、TCP 认证失败、HTTP 401 等）。

### 认证失效：停而非重试

认证失败（token 过期、secret 变化）时停止重连，向上传播事件。网络断开、服务暂时不可用等可恢复错误应重试（指数退避）。

### TCP 包类型分发

新增 PacketType 时：
1. `shared/protocol/PacketType.kt` 中定义新类型（在对应编码范围内找空位）
2. `shared/protocol/payload/` 中定义 `IProto` 实现
3. 客户端：`ImClient.handleProto()` 添加分支
4. 服务端：`ImAgent.dispatchAuthedPacket()` 添加路由，重量操作用 `IOExecutor.execute`
5. 需要通知 UI 时在 `ImStateListener` 添加默认方法

### 新增服务端功能检查清单

```
□ DAO：Tables.kt 定义表 + XxxDao 添加 SQL 方法
□ Store：内存缓存字段 + loadAll + 读写方法
□ Service：业务方法（权限校验 + 多 Store 协调 + 副作用）
□ API：对应 Routes 添加 HTTP 路由
□ TCP（如需实时推送）：定义 PacketType/Payload → 新增 Dispatcher → ImAgent 添加路由
```
