# TCP 模块编码规范

> 本文件约束 `server/.../protocol/` 目录下的代码。

---

## 线程模型

TCP 模块有 3 种线程，操作必须在正确的线程上执行：

| 线程 | 用途 | 典型操作 |
|------|------|---------|
| **EventLoop** (Netty) | 网络读写、轻量分发 | PING/PONG、DISCONNECT、数据提取、协程启动 |
| **IOExecutor** (线程池) | 重量 IO 操作 | Service/Repository 调用、消息存储、DB 查询 |
| **ClientRegistry** (Looper) | 连接状态管理 | 注册/注销连接、多设备管理、在线统计 |

**规则**：
- **EventLoop 绝对不能执行阻塞操作**（DB 查询、文件 IO、runBlocking）。所有需要 IO 的业务逻辑通过 `ioExecutor.launchWithAgent` 启动协程
- `Channel.writeAndFlush()` 是线程安全的（Netty 内部提交到对应 EventLoop），IOExecutor 中可以直接调用
- ClientRegistry 的所有状态操作通过 Looper 线程序列化，外部通过 `suspend` 方法挂起等待

---

## GC 安全约束（重要）

### ImAgentFacade 门面模式

**`ImAgentFacade`** 是 ImAgent 的安全门面，通过 `WeakReference` 持有 agent。

GC 安全原理：
- 协程挂起期间，如果 TCP 连接断开，GC 可以正常回收 ImAgent 和 Netty Channel（包括 DirectByteBuffer 堆外内存）
- 协程 resume 时，`facade.send()` 检查 agent 存活状态，已销毁则抛 `AgentDisposedException`（CancellationException 子类），协程自动取消

### 正确的异步模式

```kotlin
private fun handleMessage(msg: Message) {
    // EventLoop：启动协程，IO 在 IOExecutor 线程池执行
    ioExecutor.launchWithAgent(this) { facade ->
        // 协程中：facade 通过 WeakReference 安全访问 agent
        val serverSeq = messageService.sendMessage(facade.uid, msg)
        facade.send(MessageAckPayload(...))
    }
}
```

**要点**：
- EventLoop 只做数据提取和协程启动，所有 IO 操作在 `launchWithAgent` 协程中执行
- **永远不要**在协程中直接捕获 `this`（ImAgent）—— 所有 agent 操作通过 `facade` 的门面方法
- **禁止使用 `runBlocking`**——它会阻塞 EventLoop

### 禁止的模式

```kotlin
// ❌ 错误：runBlocking 阻塞 EventLoop
override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
    runBlocking { authService.handleAuth(payload) }  // 阻塞整个 EventLoop！
}

// ❌ 错误：直接捕获 ImAgent 引用
ioExecutor.scope.launch {
    val result = service.doWork()
    this@ImAgent.write(result)  // ImAgent 被协程栈帧强引用，无法 GC！
}
```

---

## 架构分层

```
TcpServer (启动/停止，持有 IOExecutor)
 └── Netty Pipeline:
      IdleStateHandler → HandshakeHandler → PacketCodec → ImAgent
```

- **ImAgent** 是连接级实例，每个 TCP 连接一个
- **EventLoop** 只处理 PING/PONG/DISCONNECT/SUBSCRIBE
- **auth/RPC/message** 通过 `IOExecutor.launchWithAgent` dispatch 到 IO 线程池
- **ClientRegistry** 管理 uid → deviceId → ImAgent 映射，Looper 线程序列化访问
