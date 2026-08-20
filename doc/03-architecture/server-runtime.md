# 服务端运行时

## 1. 进程组成

TeamTalk 服务端是一个 JVM 进程，包含：

- Ktor/Netty HTTP(S) 服务：文件、健康、静态资源、日志和管理 API。
- Netty TCP 服务：连接、认证、RPC、消息、通知和心跳。
- 领域服务：用户、联系人、群、消息、会话、设备、在线状态和管理。
- 基础设施：PostgreSQL、RocksDB、Lucene、文件分层存储和事件同步。

它们由 Koin 组装为单例；每个 TCP 连接拥有独立 `ImAgent`。

## 2. 启动顺序

```text
resolve Environment/dataRoot
  → initialize logging
  → connect PostgreSQL and create/migrate tables
  → open RocksDB stores and Lucene index
  → build Koin domain graph
  → recover pending message projections
  → start TCP server
  → start HTTP(S) server
  → expose health status
```

存储初始化或 message operation outbox 恢复失败时应阻止实例进入可用状态。`/health` 只有在
PostgreSQL、RocksDB、Lucene、message-projection readiness、文件存储和 TCP 均可用时返回成功。

## 3. TCP 管线

```text
SocketChannel
  → idle/heartbeat handler
  → frame decoder/encoder
  → ImAgent（连接状态机）
       ├── AUTH
       ├── INVOKE → RpcDispatcher → generated Stub → domain service
       ├── MESSAGE → MessageService
       ├── SYNC_REQUEST → SyncEventReader
       └── NOTIFY outbound
```

EventLoop 不执行数据库和慢业务。ImAgent 把工作提交到协议执行器，完成后再回到 channel 写响应。
连接状态仍由 EventLoop 串行维护，避免锁和跨线程 channel 生命周期竞态。

## 4. ClientRegistry

注册表结构是 `uid → deviceId → ImAgent`。它负责：

- 向某个用户全部在线设备推送。
- 同设备重复登录时替换旧连接。
- 用户最后一个连接关闭时触发离线状态。
- 为 SyncEventService 提供实时投递目标。

领域服务不能长期持有 ImAgent；异步任务只持有 GC 安全的 facade 或 uid/deviceId，再通过注册表定位
当前连接。

## 5. 领域与基础设施边界

领域服务负责业务不变量和事件目标，只依赖领域端口；`infra` 中的 Exposed/RocksDB/Lucene/
连接注册表适配器实现这些端口。典型写操作顺序：

1. 校验调用者、成员和参数。
2. 在权威存储提交状态；消息同时提交投影 outbox。
3. 按 revision 提交 Lucene；在一个 PostgreSQL UoW 中提交 receipt、Conversation 和完整事件快照。
4. PostgreSQL commit 后唤醒 dispatcher，最后精确清除对应 revision 的 Rocks outbox operation。
5. 返回 RPC 或消息 ACK。

不能先向客户端报告成功再异步做关键校验。例如文件消息必须在 ACK 前确认附件存在；群消息必须在
分配序列前确认发送者是成员。

## 6. 线程与协程规则

- Netty EventLoop 只处理连接状态与帧调度。
- 阻塞 JDBC、RocksDB、Lucene 和文件 IO 必须离开 EventLoop。
- 连接 trace 使用专用 Looper/Writer，未采样连接不构造昂贵日志字符串。
- 领域协程发生异常时必须映射为协议错误并完成 pending request，不能静默挂起。
- Presence/Typing 是短暂状态，只走瞬时事件；业务实体事件必须进入持久化同步队列。

## 7. 单体边界

单体不是“所有代码互相调用”。模块内部仍通过领域服务、Repository、事件同步和共享 Contract 保持
边界。只有当实际容量或组织协作证明单进程是瓶颈时，才考虑拆分；提前引入消息队列、分布式锁和
服务发现会破坏当前的确定性与可部署性。
