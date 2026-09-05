# TCP 协议目录协作约定

本文件作用于 `server/server/src/main/kotlin/com/virjar/tk/server/protocol/` 及其子目录，并补充仓库根
`AGENTS.md`。协议与服务端设计分别见 `doc/04-protocol/` 和 `doc/03-architecture/server-runtime.md`。

## 线程边界

| 执行上下文 | 职责 |
|---|---|
| Netty EventLoop | 网络读写、PING/PONG、轻量解码和协程启动 |
| IOExecutor | Service、Repository、消息存储和数据库调用 |
| ClientRegistry Looper | 连接注册、注销、多设备映射和在线统计 |

- EventLoop 禁止数据库、文件 IO、`runBlocking` 和其他阻塞工作。
- boss EventLoop 在 worker 注册前同时取得总连接与未认证连接 lease；认证只归还后者，总连接 lease
  必须保留到 channel close，停止后两层计数都必须归零。
- auth、RPC 和 message 业务通过 IOExecutor 调度；普通消息与用户 RPC 命令使用
  `launchSerialWithAgent` 保持用户命令顺序，其他连接任务使用 `launchWithAgent`。
- ClientRegistry 状态只在自身 Looper 中串行修改；外部通过 suspend API 访问。
- `Channel.writeAndFlush()` 可以从 IOExecutor 调用，由 Netty 投递到对应 EventLoop。

## 连接生命周期与 GC 安全

- 每个 TCP 连接拥有一个 `ImAgent`，连接销毁后不得被后台协程强引用。
- 异步任务只通过 `ImAgentFacade` 访问连接；Facade 用 WeakReference 检查 agent 是否仍存活，并携带
  不回指 agent/channel 的连接任务 lease。`channelInactive` 必须终止该 lease。
- 协程中禁止捕获 `this@ImAgent`、Channel 或其他连接级强引用。
- 连接已销毁时，Facade 抛出的 `AgentDisposedException` 属于正常取消路径，不应转化为业务失败重试。
- IOExecutor 必须在入队和出队时检查 lease，并在独立请求子任务中桥接取消；不得取消长期 worker。

推荐模式：

```kotlin
private fun handleMessage(message: Message) {
    val messages = messageService // 只捕获领域服务，不让异步任务持有 ImAgent。
    ioExecutor.launchSerialWithAgent("user-command:$uid", this) { facade ->
        val seq = messages.sendMessage(facade.uid, message)
        facade.send(MessageAckPayload(/* ... */))
    }
}
```

## Pipeline 与职责

```text
TLS 握手阶段：SslHandler → TlsHandshakeGateHandler
握手成功后：  SslHandler → IdleStateHandler → PacketCodec → ImAgent
明文配置：                IdleStateHandler → PacketCodec → ImAgent
```

- `TcpServer` 在 TLS 握手成功后安装协议处理器并移除 `TlsHandshakeGateHandler`；明文配置
  模式直接安装协议处理器。
- 运行时按传输配置选择管线，不能把当前 SDK 的地址限制当成服务端的绑定限制。三层支持范围以
  [传输配置边界](../../../../../../../../../../doc/07-operations/configuration.md#传输配置边界)为准；
  修改其中一层时须核对其余两层，TLS 分支仍须保持握手前不处理 AUTH、失败不回退明文。
- `PacketCodec` 位于独立 `protocol/protocol-netty` 模块，负责帧编解码、方向与长度限制。
- `connection/` 放置 `ImAgent` 及其认证、输入提示和连接遥测协作者；它们管理连接状态、消息分发
  与响应，不是编解码器。NEGOTIATE 在 AUTH 前决定版本窗口；直接 AUTH 的旧客户端收到明确升级
  拒绝。格式损坏只关闭连接，不能伪装成版本拒绝或清理数据。
- `rpc/` 和 `dispatcher/` 处理 INVOKE；普通 MESSAGE 由 `ImAgent.handleMessage` 直接调度领域服务。
- 权限、幂等、附件和持久化校验必须进入领域服务，不能散落在 handler。

修改本目录时至少验证协议 round-trip、认证门禁、连接关闭/取消路径，以及相关服务端测试；新增阻塞
调用前必须明确证明它不会运行在 EventLoop。
