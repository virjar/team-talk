# 服务端线程模型

> Netty EventLoop + 协程 IOExecutor 的双线程模型，以及 GC 安全的 ImAgentFacade。

## 线程架构

```
EventLoop (Netty NioEventLoop)
  ├── 握手 + 认证 (ImAgent)
  ├── PING/PONG 处理
  ├── MESSAGE_ACK 处理
  └── 其他 → dispatch 到 IOExecutor

IOExecutor (协程调度器)
  ├── INVOKE → RpcDispatcher → Domain Service
  ├── MESSAGE → MessageDispatcher → MessageService
  └── SUBSCRIBE → SubscribeDispatcher
```

## 关键约束

- **EventLoop 线程禁止阻塞操作**：所有 I/O（数据库、文件、网络）必须调度到协程
- **IO 操作通过协程调度到 IOExecutor**：Domain Service 在协程中执行，可安全阻塞
- **协程中不直接引用 ImAgent**：通过 WeakReference 门面（ImAgentFacade）访问，防止 EventLoop 线程持有连接对象导致 GC 无法回收
- **ClientRegistry 运行在独立 Looper 线程**：串行化 uid→多设备映射的访问，避免并发问题

## ImAgentFacade（GC 安全门面）

ImAgent 是 Netty Channel 的 handler，持有 ChannelHandlerContext。协程中如果直接引用 ImAgent，会阻止 Channel 被 GC 回收（连接已关闭但协程还持有引用）。

ImAgentFacade 用 WeakReference 包装 ImAgent：
- 协程通过 ImAgentFacade 访问连接
- 连接关闭后 ImAgent 可被 GC 回收
- Facade 检测到 WeakReference 为 null 时安全跳过操作

## 采样日志体系

TCP 模块使用 Recorder + SamplingManager 替代传统日志：

- 每个连接绑定 Recorder（通过 Netty Channel AttributeKey）
- 认证前缓存 30 条日志，认证后升级到采样 Writer
- 全局最多 100 个同时采样连接
- 专用 trace Looper 线程写日志，不阻塞 EventLoop
- 懒加载 `record(Supplier<String>)` + `enable()` 短路，未采样时零开销

**相关代码**：`server/.../protocol/codec/ImAgent.kt`、`server/.../protocol/codec/ImAgentFacade.kt`、`server/.../protocol/trace/Recorder.kt`
