# TCP 模块编码规范

> 本文件仅约束 `server/.../tcp/` 目录下的代码。其他模块无此限制。

---

## 线程模型

TCP 模块有 4 种线程，操作必须在正确的线程上执行：

| 线程 | 用途 | 典型操作 |
|------|------|---------|
| **EventLoop** (Netty) | 网络读写、轻量分发 | PING/PONG、认证、禁言检查、Typing 转发 |
| **IOExecutor** (线程池) | 重量 IO 操作 | RocksDB 写入、PostgreSQL 查询、Lucene 索引 |
| **ClientRegistry** (Looper) | 连接状态管理 | 注册/注销连接、多设备管理、在线统计 |
| **trace/Looper** (采样日志) | 日志写入 | Recorder 采样日志 |

**规则**：
- EventLoop 绝对不能执行阻塞操作（DB 查询、文件 IO）。需要时用 `IOExecutor.execute { }` 提交到 IO 线程池
- `Channel.writeAndFlush()` 是线程安全的（Netty 内部提交到对应 EventLoop），IOExecutor 中可以直接调用
- ClientRegistry 的所有状态操作通过 `workThread.post { }` 调度，外部通过 `suspendAwait` 挂起等待
- `ThreadIOGuard` 标记 EventLoop 线程为受保护线程，防止阻塞 IO 被误调度到 EventLoop

---

## 异步模型与 GC 安全（重要约束）

TCP 入口（Netty EventLoop 触发的逻辑）的异步编程范式：

**禁止在 TCP 入口使用协程（`IOExecutor.launch`），必须使用 callback 模式（`IOExecutor.execute`）。** 协程仅允许在 HTTP 端点调用 ClientRegistry API 时使用（`suspendAwait`）。

### 原因

#### 1. 事件驱动范式一致性

Netty 是事件驱动框架，Handler 中的逻辑应保持"收到事件 → 产生动作（可异步）→ 立即返回"的模式。协程的 suspend/resume 引入隐式状态机，破坏了事件驱动语义的透明性。

#### 2. Channel 状态可靠性

协程在 IO 阻塞排队期间挂起，resume 时 TCP 连接可能已经断开。与 Go 语言不同，Kotlin 协程没有与 Netty Socket 集成的底层取消机制，resume 后无法感知 Channel 已关闭。

#### 3. GC 内存泄漏（最关键）

Netty Channel 持有 `DirectByteBuffer`（堆外内存），TCP 断开后应立即释放。但协程挂起期间的栈帧隐式捕获了 `agent → channel → DirectByteBuffer`，形成 GC 根链路：

```
Coroutine stack frame → agent (ImAgent) → channel (Netty Channel) → DirectByteBuffer (off-heap)
```

即使 Channel 已关闭、ImAgent 已从 ClientRegistry 移除，排队中的协程仍持有引用，导致堆外内存无法回收。IO 排队严重时，积压的协程会 hold 住大量已关闭 Channel 的内存。

### 正确的异步模式：execute + callback + WeakReference

```kotlin
// 进入异步前：提取原始数据，切断对 ImAgent/Channel 的强引用
val weakAgent = WeakReference(agent)
val uid = agent.uid
val channelId = message.channelId
val clientMsgNo = message.clientMsgNo

IOExecutor.execute {
    // IO 操作完成
    val stored = messageService.sendMessage(enriched)

    // 回调：检测 Channel 是否还在
    val currentAgent = weakAgent.get()
    if (currentAgent == null || !currentAgent.isActive) {
        return@execute  // 连接已断开，跳过，GC 已回收 Channel
    }
    currentAgent.send(SendAckPayload(...))
    MessageDeliveryService.deliver(stored, currentAgent)
}
```

**要点**：
- 进入异步前提取所有需要的原始数据（uid、channelId 等），供日志和业务逻辑使用
- `WeakReference<ImAgent>` 切断 GC 依赖链路——IO 排队期间 Channel 可以正常回收
- 回调时检测活跃性：`get()` 为 null 或 `!isActive` 则跳过
- 用 `execute`（Runnable callback）而非 `launch`（协程），避免协程上下文隐式捕获

---

## 架构分层

```
TcpServer (启动/停止)
 └── Netty Pipeline:
      IdleStateHandler → HandshakeHandler → Setup → PacketCodec → ImAgent
                                                         │
                                          ┌──────────────┼──────────────┐
                                          │              │              │
                                    AuthProcessor  MessageDispatcher  SubscribeDispatcher  TypingDispatcher
                                    (EventLoop)     (IOExecutor)       (IOExecutor)          (EventLoop)
```

- **ImAgent** 是连接级实例，每个 TCP 连接一个，持有 `recorder` 和 `channel`
- **Dispatcher** 是单例对象（`object`），通过 `init()` 方法注入 Service/Store 依赖
- **ClientRegistry** 管理所有在线连接，以 `uid → deviceId → ImAgent` 结构组织，同一 uid 多设备共存

---

## 日志规范

- 所有日志通过 `agent.recorder.record { "..." }` 记录，不直接使用 SLF4J
- 使用懒加载 lambda：`recorder.record { "..." }` 而非 `recorder.record("...")`，避免无采样时产生字符串拼接开销
- 日志标签使用大写方括号：`[SEND]`、`[RECV]`、`[SENDACK]`、`[SUBSCRIBE]`、`[TYPING]`、`[MUTED]`、`[EDIT]`
- Recorder 在认证前缓存日志（最多 30 条），认证后 `upgrade()` 到采样 Writer

---

## 添加新业务 Dispatcher 的步骤

1. 在 `agent/` 下新建 `object XxxDispatcher`
2. 添加 `init()` 方法注入所需 Service/Store（在 `Application.kt` 中调用）
3. 在 `ImAgent.dispatchAuthedPacket()` 的 `when` 分支中添加路由
4. 判断操作是否需要 IO：轻量操作直接 EventLoop 执行，重量操作用 `IOExecutor.execute { }` + callback + `WeakReference`
5. 日志使用 `agent.recorder.record { "[TAG] ..." }`
