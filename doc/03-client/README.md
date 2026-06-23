# 客户端架构

> TeamTalk 客户端基于 Kotlin Multiplatform + Compose Multiplatform，
> Android 和 Desktop 共享 70%+ 的业务和 UI 代码。
> 本文记录核心架构决策及其权衡。

## 目录

- [1. 本地优先（Local-First）](#1-本地优先local-first)
- [2. 连接管理（ImClient）](#2-连接管理imclient)
- [3. 本地缓存（LocalCache）与状态合并](#3-本地缓存localcache与状态合并)
- [4. 导航架构](#4-导航架构)
- [5. 消息渲染](#5-消息渲染)
- [6. 平台适配](#6-平台适配)
- [7. ChatMediaConfig 媒体桥接](#7-chatmediaconfig-媒体桥接)

---

## 1. 本地优先（Local-First）

**核心原则：所有页面从本地 SQLite 读取数据渲染，网络仅用于写操作和事件同步。**

```
数据读取：ViewModel → LocalCache → SQLite（不请求网络）
数据写入：ViewModel → Repository → TCP INVOKE → 服务端 → NOTIFY → EventProcessor → SQLite
数据同步：TCP NOTIFY → EventProcessor → upsert SQLite → ViewModel 刷新
```

### 为什么本地优先

1. **离线可用**：网络断开时仍可查看历史消息、联系人、会话列表
2. **渲染快**：UI 直接读内存/SQLite，不等待网络
3. **状态一致**：服务端是唯一真相源，客户端通过事件同步保持最终一致

### 代价

| 代价 | 说明 | 缓解措施 |
|------|------|---------|
| **实现复杂度高** | 双写（本地+网络）+ 同步逻辑 | EventProcessor 统一处理，Repository 封装 |
| **存储占用** | 本地 SQLite 存全量数据 | 消息按 chatId 分组懒加载 |
| **一致性窗口** | 本地和服务端短暂不一致 | 最终一致（NOTIFY 推送 + lastEventId 补发） |

### 和"纯在线"模式对比

| 模式 | 本地优先 | 纯在线（REST API） |
|------|---------|------------------|
| 网络断开 | ✅ 可看历史 | ❌ 白屏 |
| 首次渲染 | ✅ 毫秒级 | ❌ 等待 HTTP |
| 实时更新 | ✅ TCP 推送 | ❌ 轮询/SSE |
| 实现复杂度 | ❌ 高 | ✅ 低 |

IM 场景适合本地优先：用户期望"发消息立即看到"、"网络差也能翻历史"。

---

## 2. 连接管理（ImClient）

### TcpConnection 一次性设计

**核心原则：TcpConnection 是一次性的**——每条 TCP 连接一个实例，断开后丢弃，永不重置/复用。

**为什么不可复用**：
1. **无残留状态**：旧 ByteBuf/定时器随实例一起被 GC。重置复用容易遗漏某个字段导致状态泄漏。
2. **无"重置"逻辑**：重置逻辑本身就是 bug 来源（经典重连 bug 都是重置不完整导致的）。
3. **简单**：新连接 = 新实例 = 干净状态，不需要任何清理代码。

### ImClient 长生命周期

`ImClient` 是业务层，拥有重连策略、发送队列、离线补发。它比任何单个 TcpConnection 活得久：

```
ImClient (长生命周期)
  ├── 连接策略（指数退避 1s→2s→4s→8s→30s）
  ├── pendingAuth（保存认证参数，重连时复用）
  ├── send() 鉴权门控
  └── TcpConnection (短生命周期，每次重连新建)
```

### 单线程 EventLoop

ImClient 使用 `NioEventLoopGroup(1)` 单线程。所有连接级状态变更在同一线程串行访问，**无锁、无原子、无竞态**。

**为什么单线程够用**：
- 客户端只有一个 TCP 连接，不存在服务端的"N 连接共享 EventLoop"问题
- EventLoop 的 I/O 和状态变更共享同一线程，无需跨线程同步
- `EventLoop.schedule()` 提供延迟任务（退避定时器），不需要额外的定时器线程

### 重连退避策略

```
1s → 2s → 4s → 8s → 16s → 30s → 30s...（封顶 30s）
```

- 首次重连无退避（立即重连）
- 网络变化事件（Android ConnectivityManager）重置 `retryCount = 0` 并立即重连——"网络刚恢复就重试，不等过时的退避定时器"

### send() 鉴权门控

```kotlin
fun send(proto: IProto) {
    if (state != AUTHENTICATED && proto !is AuthRequestPayload
        && proto !is PingSignal && proto !is PongSignal) {
        return  // 阻断非认证状态的业务消息
    }
    // ... 发送
}
```

**为什么需要门控**：重连期间（连接已建立但未认证），业务消息可能抢先发出 → 服务端拒绝。门控确保只有 PingSignal/PongSignal/AuthRequestPayload 能在非认证状态发送。

### pendingAuth 更新

token refresh 是 one-time-use（服务端删旧 token）。认证成功后必须更新 `pendingAuth`：

```kotlin
pendingAuth = pendingAuth?.copy(refreshToken = response.refreshToken)
```

否则重连时发送已消费的旧 token → AUTH_FAILED → 掉线循环。

---

## 3. 本地缓存（LocalCache）与状态合并

### 接口设计

`LocalCache` 是 commonMain 定义的接口，封装所有本地数据读写。所有方法同步返回（SQLite 读取在 IO 线程），通过 StateFlow 通知 UI。

### 状态合并策略

客户端有两个数据源：本地 LocalCache（用户操作即时反馈）+ 服务端 NOTIFY（异步状态更新）。合并时不能"整体替换"，需要字段级合并。

**mergeConversation 规则**：

| 字段 | 规则 | 理由 |
|------|------|------|
| readSeq | `maxOf(local, remote)` | 已读位置只前进不后退 |
| unreadCount | 本地已清零时不被覆盖 | 客户端有更高优先级 |
| draft | 本地非空优先 | 草稿是纯客户端状态 |

详细踩坑分析和代码见 [draft-and-unread.md](draft-and-unread.md)。

---

## 4. 导航架构

### 不共享导航

Android 和 Desktop 的导航模型根本不同，强行共享增加复杂度。

### Android：NavHost + NavController

- 底部 `NavigationBar` 常驻，Tab 切换内容
- 全屏覆盖层 push 到返回栈，系统返回键生效
- `rememberSaveable` 保留 Tab 状态

### Desktop：三栏布局 + 子窗口

```
┌─────────┬──────────────┬──────────────────┐
│ 导航栏   │ 列表面板      │ 详情面板          │
│ (固定)   │ (会话/通讯录) │ (聊天/用户详情)    │
└─────────┴──────────────┴──────────────────┘
```

- 三栏同时显示列表和详情
- 子窗口（EditProfile/CreateGroup/SearchUsers）作为独立 ComposeWindow 弹出
- `AppState.currentScreen` 枚举控制右栏和子窗口

### Compose 状态残留问题

`remember` 跨 `Window(visible=false)` 保留状态。退出后 loading/showRegister 等状态必须重置。

**修复模式**：
```kotlin
// DISCONNECTED 时重置
LaunchedEffect(connectionState) {
    if (connectionState == DISCONNECTED) {
        loginLoading = false; showRegister = false
    }
}
// SubWindow 加 key() 强制重建
key(windowScreen) { SubWindow(screen = windowScreen, ...) }
```

---

## 5. 消息渲染

详见 [01-protocol/message-types.md](../01-protocol/message-types.md)。

核心：
- `MessageBodyRenderer` 按 body 类型渲染（文本/图片/语音/文件/视频/引用/撤回）
- 图片气泡参考 Signal fit-inside 240×320dp 策略
- flags 位标记控制渲染样式（撤回/编辑/转发）

---

## 6. 平台适配

| 功能 | commonMain (expect) | Desktop (actual) | Android (actual) |
|------|---------------------|------------------|------------------|
| 服务端地址 | `ServerConfig` | JVM `-D` 系统属性 | BuildConfig |
| Token 存储 | `TokenStorage` | Properties 文件 | SharedPreferences |
| 本地数据库 | `AppDatabase` | JdbcSqliteDriver | AndroidSqliteDriver |
| 日志 | `AppLog` | LocalLogFile (FileWriter) | android.util.Log |
| 语音录制 | `VoiceRecorder` | Java Sound API | MediaRecorder |

完整文件树见 [module-structure.md](module-structure.md)。

---

## 7. ChatMediaConfig 媒体桥接

ChatPanel 原有 13 个可选参数（onPickImage/onPickFile/imageContent...），收敛为 `ChatMediaConfig` data class。

**为什么收敛**：
- 参数会随功能增长而膨胀
- 新平台接入只需构造一个配置对象，不需要逐个 lambda 配对
- Android/Desktop 各自构造 `ChatMediaConfig`，ChatPanel 只接收一个参数

```kotlin
data class ChatMediaConfig(
    val onAttachClick: (() -> Unit)? = null,
    val onPickImage: (() -> Unit)? = null,
    val onPickFile: (() -> Unit)? = null,
    val onVoiceRecord: ((Boolean) -> Unit)? = null,
    val imageContent: @Composable ((String, Modifier) -> Unit)? = null,
    val videoContent: @Composable ((String, Modifier) -> Unit)? = null,
    val onMediaClick: ((Message) -> Unit)? = null,
)
```

**相关代码**：`app/.../ui/bridge/ChatMediaConfig.kt`
