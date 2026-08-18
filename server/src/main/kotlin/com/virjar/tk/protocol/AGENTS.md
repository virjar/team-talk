# TCP 协议目录协作约定

本文件作用于 `server/src/main/kotlin/com/virjar/tk/protocol/` 及其子目录，并补充仓库根
`AGENTS.md`。协议与服务端设计分别见 `doc/04-protocol/` 和 `doc/03-architecture/server-runtime.md`。

## 线程边界

| 执行上下文 | 职责 |
|---|---|
| Netty EventLoop | 网络读写、PING/PONG、轻量解码和协程启动 |
| IOExecutor | Service、Repository、消息存储和数据库调用 |
| ClientRegistry Looper | 连接注册、注销、多设备映射和在线统计 |

- EventLoop 禁止数据库、文件 IO、`runBlocking` 和其他阻塞工作。
- auth、RPC 和 message 业务通过 `IOExecutor.launchWithAgent` 调度。
- ClientRegistry 状态只在自身 Looper 中串行修改；外部通过 suspend API 访问。
- `Channel.writeAndFlush()` 可以从 IOExecutor 调用，由 Netty 投递到对应 EventLoop。

## 连接生命周期与 GC 安全

- 每个 TCP 连接拥有一个 `ImAgent`，连接销毁后不得被后台协程强引用。
- 异步任务只通过 `ImAgentFacade` 访问连接；Facade 用 WeakReference 检查 agent 是否仍存活。
- 协程中禁止捕获 `this@ImAgent`、Channel 或其他连接级强引用。
- 连接已销毁时，Facade 抛出的 `AgentDisposedException` 属于正常取消路径，不应转化为业务失败重试。

推荐模式：

```kotlin
private fun handleMessage(message: Message) {
    ioExecutor.launchWithAgent(this) { facade ->
        val seq = messageService.sendMessage(facade.uid, message)
        facade.send(MessageAckPayload(/* ... */))
    }
}
```

## Pipeline 与职责

```text
IdleStateHandler → HandshakeHandler → PacketCodec → ImAgent
```

- HandshakeHandler 只负责认证前导和版本门禁。
- PacketCodec 只负责帧编解码与长度限制。
- ImAgent 负责连接级分发，不承载领域规则。
- 权限、幂等、附件和持久化校验必须进入领域服务，不能散落在 handler。

修改本目录时至少验证协议 round-trip、认证门禁、连接关闭/取消路径，以及相关服务端测试；新增阻塞
调用前必须明确证明它不会运行在 EventLoop。
