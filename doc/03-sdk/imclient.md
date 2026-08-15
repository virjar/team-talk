# ImClient — TCP 连接层

> 单线程 EventLoop 驱动的连接状态机。每一处防御设计都对应一个历史 bug（见注释索引）。
> 源码：`shared/.../client/ImClient.kt`（455 行）

---

## 1. 线程模型

- **单线程 Netty EventLoop**（`NioEventLoopGroup(1)`）：所有连接状态变更串行化，无锁
- 连接层独占状态（仅 EventLoop 触碰）：`channel` / `scope` / `pendingAcks`
- 协程 scope 挂在 EventLoop 上（handshake 成功时创建），RpcClient/EventProcessor 复用
- 观察状态（任意线程读）：`state: StateFlow<ConnectionState>`、`packets: SharedFlow<IProto>`（buffer 64，满则丢帧+trace，**绝不阻塞 EventLoop**）

**doOnEventLoop**：
```
if (inEventLoop) 内联执行
else eventLoop.execute(task)
     └─ RejectedExecutionException（destroy 后）→ fault 日志 + 调用线程内联兜底执行
        （登出后登录页的迟到回调不能崩进程）
```

## 2. 状态机

```
                connect()/connectAndAuth()
  DISCONNECTED ────────────────────────────► CONNECTING
      ▲                                          │ TCP 失败
      │                                          ▼ (未destroy→scheduleReconnect)
      │                                      DISCONNECTED
      │ 服务端 MAGIC+VERSION 回显
      │                                          ▼
      │                                      CONNECTED
      │                          AuthResponse code==0   code!=0
      │                                  ▼                 ▼
      ├─ channelInactive（未destroy→重连）  AUTHENTICATED   AUTH_FAILED
      ├─ disconnect()（软，EventLoop 存活）
      └─ destroy()（永久，shutdown EventLoop）
```

## 3. connectAndAuth 原子化（防认证竞态）

```kotlin
fun connectAndAuth(auth, host, port) {
    doOnEventLoop {
        reconnectFuture?.cancel()      // ① 取消待执行的重连定时器（防与新连接竞争）
        destroyed = false
        pendingAuth = auth             // ② 先设认证包
        createAndConnect()             // ③ 再拨号
    }
}
```

**防的竞态**：调用方在协程线程构造 payload（CPU 工作），若先 connect 后赋 pendingAuth，EventLoop 可能在赋值前完成 TCP 握手 → HandshakeHandler 发不出认证包 → 永远停在 CONNECTED。同一 EventLoop 任务内"先设后拨"，TCP 回调必然排队在赋值之后（FIFO 保证）。

**重连自动重认证**：pendingAuth 跨连接存活，handshake 升级 pipeline 后自动重发。

## 4. 重连策略

- 触发：TCP 拨号失败 / channelInactive（`destroyed=true` 时不重连）
- 退避：`min(30s, 1s × 2^min(retryCount,4))` → 1s,2s,4s,8s,**16s 平台**（30s 上限实际到不了）
- `retryCount` 仅认证成功时清零
- 任何新 connect 前先 cancel `reconnectFuture`（防定时重连与用户发起的连接竞争）

## 5. 心跳

| 事件 | 触发 | 动作 |
|------|------|------|
| WRITER_IDLE 15s | 无写出 | `send(PingSignal)`（豁免认证门禁） |
| READER_IDLE 45s | 无读取（3×ping） | `ctx.close()` → channelInactive → 重连 |

45s 读超时是对 NAT 半开连接的唯一防线（TCP keepalive 检测不到对端假死）。服务端 PING → 内联回 PONG。

## 6. send() 认证门禁

```
channel == null                            → 丢弃 + trace
state != AUTHENTICATED
  且非 Auth/Ping/Pong 包                   → 丢弃 + trace   // 重连窗口内业务包会被服务端 401
else                                       → writeAndFlush
```

## 7. sendAndWaitAck（消息直发协议）

```
withContext(EventLoop scope):
  pendingAcks[clientMsgId] = Deferred
  send(message)
  withTimeout(10s) { deferred.await() }
    └─ 超时：移除条目 + 返回合成失败 ack (code=-1, "ACK timeout")   // 超时不是异常
断连：cleanupOnDisconnect 将所有 pending 以 CancellationException 完成
```

## 8. disconnect() vs destroy()

| | disconnect() | destroy() |
|---|---|---|
| 语义 | 软断（切这次连接） | 永久销毁实例 |
| EventLoop | **保留**（重登复用，省线程创建） | shutdownGracefully(0, 2s) |
| 场景 | 登出 / session.close() | 进程退出 / bot 关闭 |

`ensureEventLoop()`：destroy 后误调 connect 会重建 EventLoop（实例可复活）。

## 9. 认证回调与 token 一次性轮换

```
AuthResponse code==0:
  retryCount=0; state=AUTHENTICATED
  response.refreshToken?.let { pendingAuth = pendingAuth?.copy(refreshToken = it) }  // ★
  onAuthResult(true, uid, username, name, refreshToken, null)
```
★ 服务端 refresh token **一次一换**——刚用的已被消费。不更新 pendingAuth，下次重连拿旧 token → AUTH_FAILED → 静默掉登录（历史 bug）。

身份只经 `onAuthResult` 回调流向 UserSession（三级状态：ImClient 不持有用户身份）。

## 10. RpcClient（配套）

- `pendingRequests: Map<requestId, Deferred>`（EventLoop 内操作，无锁）；requestId 从 1 自增
- 超时 10s → 合成 `ResponsePayload(504)`（超时是值不是异常）→ `ensureSuccess()` 映射 `AppError.Timeout`
- 双通道断连清理：监听 state==DISCONNECTED 清空 pending + ImClient scope cancel
- start() 幂等（重入先 cancel 旧 job）；每连接需重新 start（createSession 负责）

## 11. 常量速查

| 常量 | 值 |
|------|----|
| 握手 | 服务端先发 `TK\x01`，客户端回显 |
| 心跳 | PING 15s / 读超时 45s |
| 重连退避 | 1/2/4/8/16s 平台 |
| ACK 超时 | 10s（合成 code=-1） |
| RPC 超时 | 10s（合成 status=504） |
| packets 缓冲 | 64 |

## 12. 防御设计 ↔ 历史 bug 对照

| # | 防御 | 防的 bug |
|---|------|---------|
| 1 | connectAndAuth 单任务原子化 | 协程线程 vs EventLoop 认证包竞态（永不认证） |
| 2 | pendingAuth token 轮换更新 | 一次一换 token → 重连掉登录 |
| 3 | send 认证门禁 | 重连窗口业务包被 401 |
| 4 | doOnEventLoop RejectedExecution 兜底 | 登出后迟到回调崩登录页 |
| 5 | disconnect 保 EventLoop | 每次登录重建线程组 |
| 6 | 新连接前 cancel reconnectFuture | 定时重连与用户连接竞争 |
| 7 | channelInactive 判 destroyed | disconnect 后二次清理/误重连 |
| 8 | ACK 超时合成返回 | 超时异常路径泄漏 pendingAcks |
| 9 | cleanupOnDisconnect 只清连接层 | 断连误清 uid（消息左右颠倒） |
