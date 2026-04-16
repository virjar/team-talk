# 连接架构设计与离线消息队列

> TeamTalk TCP 连接架构设计（ImClient + TcpConnection 两层分离 + EventLoop 单线程调度）、自动重连策略、离线消息补拉、发送队列

---

## 1. 设计目标

| 目标 | 说明 |
|------|------|
| 自动重连 | 网络波动时透明重连，用户无感知。断线后消息发送自动排队，重连后自动发出 |
| 离线消息不丢失 | 断线期间的消息在重连后完整补拉，不留间隙 |
| 连接状态可见 | UI 显示连接状态（连接中/已连接/重连中/离线），用户可判断消息是否能送达 |
| 并发安全 | 所有状态变更在 Netty EventLoop 单线程上执行，无锁、无原子类、无竞态 |
| 保持简单 | 内存发送队列 + 简单指数退避，不引入持久化任务框架。符合 TeamTalk 单体架构定位 |

---

## 2. 连接架构设计

### 2.1 两层分离：ImClient + TcpConnection

将连接管理拆为两层，职责分离：

| 层 | 类 | 生命周期 | 职责 |
|---|---|---|---|
| 业务层 | `ImClient` | 用户会话生命周期（登录创建，登出/踢下线销毁） | 重连策略、发送队列、离线补拉触发、状态回调 |
| 连接层 | `TcpConnection` | 短生命周期（单次 TCP 连接） | TCP 连接、握手、心跳、数据 I/O |

**核心原则：TcpConnection 是一次性的。** 每次连接创建新实例，断线即丢弃，永不复用。好处：

1. **无残留状态**：旧连接的 ByteBuf、定时器、回调引用随实例一起释放，不会污染新连接
2. **无需"重置"逻辑**：不需要在同一个对象上清理状态再重连，避免遗漏清理步骤导致的 bug

```kotlin
class ImClient(
    private val serverHost: String,
    private val serverPort: Int,
    private val stateListener: ImStateListener,
) {
    private val mainLoop: EventLoop = workerGroup.next()
    private var currentConnection: TcpConnection? = null
    private var retryCount = 0
    private var destroyed = false
    private var connecting = false

    fun connect() {
        doOnMainThread {
            if (destroyed || connecting) return@doOnMainThread
            connecting = true
            TcpConnection(serverHost, serverPort, mainLoop, object : ConnectionListener {
                override fun onConnectionConnected(conn: TcpConnection) {
                    connecting = false
                    retryCount = 0
                    currentConnection = conn
                    stateListener.onConnected()
                    triggerOfflineCatchup()
                }
                override fun onConnectionDisconnected(conn: TcpConnection) {
                    connecting = false
                    if (currentConnection === conn) currentConnection = null
                    if (!destroyed) scheduleReconnect()
                }
            })
        }
    }

    fun disconnect() {
        doOnMainThread {
            destroyed = true
            currentConnection?.close()
        }
    }
}
```

### 2.2 TcpConnection 状态（简化的线性生命周期）

TcpConnection 没有重连逻辑，只有单向的生命周期：

```
  ┌──────────────┐
  │  CONNECTING  │  构造时立即发起 TCP 连接 + 握手
  └──────┬───────┘
         │ 握手成功 (CONNACK code=0)
         ▼
  ┌──────────────┐
  │  CONNECTED   │  心跳 + 数据收发
  └──────┬───────┘
         │ TCP 断开 / PING 超时 / 握手失败
         ▼
  ┌──────────────┐
  │    CLOSED    │  通知 ImClient，实例结束
  └──────────────┘
```

断线后 TcpConnection 通知 ImClient，由 ImClient 决定是否创建新连接。TcpConnection 本身无循环、无重置。

```kotlin
class TcpConnection(
    host: String, port: Int,
    private val mainLoop: EventLoop,
    private val listener: ConnectionListener,
) : SimpleChannelInboundHandler<Packet>() {

    private val channel: Channel = Bootstrap()
        .group(mainLoop)
        .channel(NioSocketChannel::class.java)
        .handler(/* pipeline: HandshakeDecoder + PacketDecoder + this */)
        .connect(host, port)
        .addListener { if (!it.isSuccess) invokeOnDisconnected() }
        .channel()

    private var connectedInvoked = false
    private var disconnectedInvoked = false

    init {
        channel.closeFuture().addListener { invokeOnDisconnected() }
    }

    fun close() { channel.close() }
    fun isActive(): Boolean = channel.isActive
    fun send(packet: Packet) { channel.writeAndFlush(packet) }

    // 防重复回调（§2.5）
    private fun invokeOnConnected() { /* ... */ }
    private fun invokeOnDisconnected() { /* ... */ }
}
```

### 2.3 连接状态（UI 可见）

ImClient 对外暴露简化的连接状态，供 UI 显示：

```kotlin
enum class ConnectionState {
    DISCONNECTED,   // 未连接 / 用户主动断开
    CONNECTING,     // TCP 连接中 + 握手
    CONNECTED,      // 已认证，可收发消息
    RECONNECTING,   // 重连中（包含退避等待）
}
```

状态转换规则：

| 当前状态 | 触发条件 | 目标状态 | 动作 |
|---------|---------|---------|------|
| DISCONNECTED | `connect()` 调用 | CONNECTING | 创建 TcpConnection |
| CONNECTING | 握手成功 | CONNECTED | 归零 retryCount，触发离线补拉 |
| CONNECTING | 握手失败 | RECONNECTING | 启动退避计时器 |
| CONNECTED | PING 超时/TCP 断开 | RECONNECTING | TcpConnection 被丢弃，退避重连 |
| CONNECTED | `disconnect()` 调用 | DISCONNECTED | 关闭 TcpConnection |
| RECONNECTING | 退避到期 | CONNECTING | 创建新 TcpConnection |
| RECONNECTING | `disconnect()` 调用 | DISCONNECTED | 取消退避计时器 |

### 2.4 EventLoop 单线程调度

所有状态变更操作收敛到同一个 Netty EventLoop 上执行。单线程无锁、无原子类、无竞态。

```kotlin
fun doOnMainThread(task: () -> Unit) {
    if (mainLoop.inEventLoop()) {
        task()
    } else {
        mainLoop.execute(task)
    }
}
```

**为什么复用 Netty EventLoop 而不是独立线程**：

- TcpConnection 的数据收发已在 EventLoop 上，状态变更也在同一线程，无跨线程同步问题
- 无额外线程开销——不需要单独开辟一个调度线程
- `EventLoop.schedule()` 天然支持延迟任务（退避计时器），无需额外的协程或定时器

**必须在 EventLoop 上执行的操作**：

| 操作 | 说明 |
|------|------|
| `connect()` / `disconnect()` | 连接生命周期 |
| `retryCount` / `connecting` / `destroyed` 读写 | 状态标记 |
| `currentConnection` 赋值 | 连接引用 |
| `onConnected` / `onDisconnected` 回调 | 状态通知 |
| `scheduleReconnect()` | 退避计时器 |
| `flushPendingQueue()` | 发送队列刷新 |

**业务处理不阻塞 EventLoop**：消息的数据库写入、UI 通知等耗时操作通过 `launch(Dispatchers.IO)` 卸载，但状态判断（是否已连接、是否需要重连）必须在 EventLoop 上。

### 2.5 防重复回调

TCP 连接的失败和关闭可能几乎同时触发（如连接超时后紧接着收到 RST），导致 `onDisconnected` 被调用两次。使用一次性守卫防止重复通知：

```kotlin
private var connectedInvoked = false
private var disconnectedInvoked = false

private fun invokeOnConnected() {
    if (connectedInvoked) return
    doOnMainThread {
        connectedInvoked = true
        listener.onConnectionConnected(this@TcpConnection)
    }
}

private fun invokeOnDisconnected() {
    if (disconnectedInvoked) return
    doOnMainThread {
        disconnectedInvoked = true
        listener.onConnectionDisconnected(this@TcpConnection)
    }
}
```

守卫标志的读写都在 `doOnMainThread` 内，即在同一个 EventLoop 线程上，无需 volatile 或同步。

---

## 3. 重连策略

### 3.1 指数退避

```
重试间隔：1s → 2s → 4s → 8s → 16s → 30s → 30s → ...
最大间隔：30s
```

```kotlin
fun nextRetryDelay(retryCount: Int): Long {
    val delay = min(30_000L, 1000L * (1L shl min(retryCount, 4)))
    // delay: 1000, 2000, 4000, 8000, 16000, 30000, 30000, ...
    return delay
}
```

首次重连不退避（TcpConnection 断线后 ImClient 立即创建新连接），第二次起开始指数退避。连接成功后 `retryCount` 归零。

退避计时器通过 `EventLoop.schedule()` 调度，回调天然在 EventLoop 线程上执行：

```kotlin
private fun scheduleReconnect() {
    val delay = nextRetryDelay(retryCount)
    retryCount++
    mainLoop.schedule({ connect() }, delay, TimeUnit.MILLISECONDS)
}
```

### 3.2 网络变化监听

| 平台 | 机制 | 说明 |
|------|------|------|
| Android | `ConnectivityManager.NetworkCallback` | 网络恢复时立即触发重连（跳过退避） |
| Desktop | `java.net.NetworkInterface` 轮询 / `System.getProperty("networkaddress.cache.ttl")` | 检测到网络接口变化时触发重连 |

网络变化触发重连时，重置 `retryCount = 0`，使用最短延迟。

### 3.3 用户操作触发

用户主动操作（发消息、刷新会话列表）时，如果处于 RECONNECTING 状态，立即触发重连，跳过退避等待。

### 3.4 后台策略

| 状态 | 心跳间隔 | 重连策略 |
|------|---------|---------|
| 前台 | 30s | 正常指数退避，最大 30s |
| 后台 | 60s | 降低重连频率，最大 60s 间隔 |

后台检测：Android 通过 `ProcessLifecycleOwner`，Desktop 通过窗口焦点事件。

---

## 4. 离线消息补拉

### 4.1 客户端持久化 lastSeq

每个频道的最后消费 seq 持久化到本地存储（SQLDelight）：

```kotlin
// 频道消费进度表
data class ChannelProgress(
    val channelId: String,
    val channelType: Int,
    val lastSeq: Long,        // 最后已确认的 serverSeq
    val updatedAt: Long,      // 最后更新时间
)
```

RECVACK 成功后更新 `lastSeq`。此表是离线补拉的关键依据。

### 4.2 重连后的补拉流程

```
重连成功 (CONNECTED)
    │
    ├─ 1. 查询所有已订阅频道的 ChannelProgress
    │
    ├─ 2. 对每个频道发送 SUBSCRIBE(lastSeq)
    │      SUBSCRIBE payload: { channelId, channelType, lastSeq }
    │
    ├─ 3. 服务端收到 SUBSCRIBE(lastSeq) 后：
    │      ├─ lastSeq 之后有消息 → 批量推送（write-without-flush + batch flush）
    │      ├─ lastSeq 之后无消息 → 不推送
    │      └─ lastSeq 太旧（>7天前）→ 返回截断标记 + 最新的 100 条
    │
    └─ 4. 客户端收到截断标记后：
           ├─ 本地标记该频道有消息间隙
           └─ 用户上滑加载更多时通过 HTTP API 拉取历史消息
```

### 4.3 服务端批量推送

服务端在推送离线消息时，使用 write-without-flush + batch flush 模式（见 [03-binary-encoding.md](./03-binary-encoding.md) §7）：

```kotlin
fun pushOfflineMessages(ctx: ChannelHandlerContext, messages: List<MessageRecord>) {
    messages.forEach { msg ->
        val packet = msg.toPacket()
        ctx.write(packet)        // 写入缓冲区，不立即发送
    }
    ctx.flush()                   // 一次性发送所有缓冲数据
}
```

### 4.4 离线时间过长的处理

| 条件 | 服务端行为 | 客户端行为 |
|------|----------|----------|
| 离线 ≤ 7 天 | 推送 lastSeq 之后的所有消息 | 正常接收，无间隙 |
| 离线 > 7 天 | 返回截断标记 + 最新 100 条 | 标记间隙，上滑时 HTTP 拉取历史 |

7 天阈值基于 TeamTalk 的目标规模（<1 万用户）。在此规模下，7 天的消息量不会对服务端内存和网络造成压力。

---

## 5. 消息发送队列

### 5.1 内存发送队列

```kotlin
data class PendingMessage(
    val clientSeq: Long,
    val clientMsgNo: String,
    val channelId: String,
    val channelType: Int,
    val payload: Any,           // IProto payload 对象
    val retryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

class SendQueue {
    private val queue = ArrayDeque<PendingMessage>()  // 所有操作在 EventLoop 上，无需并发容器
    private val maxRetry = 3

    fun enqueue(msg: PendingMessage) { queue.add(msg) }

    fun dequeue(): PendingMessage? = queue.poll()

    fun requeue(msg: PendingMessage): Boolean {
        if (msg.retryCount >= maxRetry) return false
        queue.add(msg.copy(retryCount = msg.retryCount + 1))
        return true
    }
}
```

### 5.2 发送失败处理

| 场景 | 处理 |
|------|------|
| SENDACK 正常返回 | 从队列移除，UI 显示已发送 |
| SENDACK 超时（5s） | 重发（最多 3 次），UI 显示发送中 |
| 超过 3 次重发 | 标记为发送失败，UI 显示红色感叹号 |
| 网络断开 | 消息留在队列中，重连后自动重发 |

### 5.3 网络恢复后自动重发

重连成功并完成离线补拉后，在 EventLoop 上依次发送队列中的待发消息：

```kotlin
fun flushPendingQueue() {
    // 在 EventLoop 上执行，无需挂起函数
    while (true) {
        val msg = sendQueue.dequeue() ?: break
        try {
            sendMessage(msg)       // channel.writeAndFlush，非阻塞
        } catch (e: Exception) {
            if (!sendQueue.requeue(msg)) {
                notifySendFailed(msg.clientMsgNo)
            }
        }
    }
}
```

---

## 6. 与 Signal / Telegram 的对比

| 维度 | Signal | Telegram | TeamTalk |
|------|--------|----------|----------|
| 发送队列 | JobManager + SQLite 持久化 | 内存队列 + DC 切换 | 内存队列（EventLoop 单线程） |
| 重连策略 | 指数退避 + 网络监听 | ConnectionsManager + DC 自动切换 | 指数退避 + EventLoop 调度 |
| 离线消息 | 本地数据库 + 服务端拉取 | 本地数据库 + MTProto 差量同步 | lastSeq + SUBSCRIBE 补拉 |
| 消息可靠性 | 持久化队列，进程崩溃不丢失 | 内存队列，重启后从服务端恢复 | 内存队列，重启后从服务端恢复 |
| 并发模型 | JobManager 串行队列 | DC 线程池 + 回调 | EventLoop 单线程，无锁 |
| 实现复杂度 | 高（JobManager 框架级） | 高（DC 切换 + 加密层集成） | 低（EventLoop 单线程，符合中小规模定位） |

**为什么 TeamTalk 不采用 Signal 的持久化队列**：

Signal 的 JobManager 是一个通用任务调度框架，支持依赖、去重、持久化。对 TeamTalk 的目标规模（<1 万用户），进程崩溃时丢失的发送队列可通过服务端查询 `clientMsgNo` 对账恢复，不需要引入持久化队列的复杂度。如果未来需要进程崩溃恢复，可以在此基础上增加轻量级对账机制。

---

## 7. 实现优先级

| Phase | 内容 | 依赖 |
|-------|------|------|
| **Phase 1** | ImClient + TcpConnection 两层架构 + EventLoop 单线程调度 + 自动重连（指数退避） + 离线消息补拉（SUBSCRIBE + lastSeq） | TcpClient 重构 |
| **Phase 2** | 发送队列（内存队列 + 失败重发 + UI 状态） + 网络变化监听 | Phase 1 |
| **Phase 3** | 后台优化（降低心跳频率 + 降低重连频率） + lastSeq 持久化到 SQLDelight | Phase 2 |

Phase 1 是核心：建立状态机和自动重连后，TCP 连接的稳定性会有质的提升，离线补拉保证消息不丢失。Phase 2 完善发送体验，Phase 3 做省电优化。
