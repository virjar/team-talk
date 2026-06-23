# 服务端架构

> TeamTalk 服务端是单进程应用（Ktor HTTP + Netty TCP），面向万级用户。
> 本文记录架构决策：为什么 DDD、为什么 Koin、为什么采样日志不用 SLF4J。

## 目录

- [1. 为什么单体架构](#1-为什么单体架构)
- [2. 领域驱动设计（DDD）](#2-领域驱动设计ddd)
- [3. 依赖注入：为什么 Koin 不用 Hilt/Guice](#3-依赖注入为什么-koin-不用-hiltguice)
- [4. 协议层：连接状态机与线程模型](#4-协议层连接状态机与线程模型)
- [5. 采样日志体系：为什么不用 SLF4J](#5-采样日志体系为什么不用-slf4j)
- [6. 事件同步机制](#6-事件同步机制)
- [7. 存储架构概览](#7-存储架构概览)
- [8. 启动流程](#8-启动流程)

---

## 1. 为什么单体架构

### 决策理由

1. **万级用户不需要分布式**：Netty 单线程 EventLoop 可处理 10 万+ 连接，单体在 1 万并发下完全够用。
2. **运维简单**：一个 jar + 一个 PostgreSQL + 一个 RocksDB + 一个 Lucene 索引。部署 = 复制文件 + 启动。不需要 K8s/Docker Swarm。
3. **开发效率**：跨 domain 调用是函数调用，不需要 RPC 序列化。调试时单进程断点即可。

### 与业界对比

大型 IM（Telegram/微信）用微服务拆分（接入层/逻辑层/存储层）。但它们的用户量是亿级，微服务的拆分成本（服务发现、分布式事务、链路追踪）在万级用户下远超收益。**单体是规模匹配的选择，不是能力限制。**

### 代价

- 单点故障：进程挂了全部不可用。但 IM 不是金融系统，短暂宕机可接受。
- 扩展上限：超过 10 万用户时需要重构。

---

## 2. 领域驱动设计（DDD）

### 模块划分

```
server/src/main/kotlin/com/virjar/tk/domain/
├── user/           # 用户：注册/登录/搜索/资料
├── auth/           # 认证：token 管理/设备认证
├── contact/        # 联系人：好友申请/接受/删除
├── chat/           # 聊天：创建群/群公告/成员管理
├── message/        # 消息：发送/撤回/编辑/搜索
├── conversation/   # 会话：未读数/置顶/草稿
├── device/         # 设备：多设备管理
├── presence/       # 在线状态：上线/下线/多端同步
└── health/         # 健康检查
```

### 每个领域的三层结构

```
domain/user/
├── UserService.kt       # 业务逻辑：编排 Repository + 业务规则
├── UserRepository.kt    # 数据访问：SQL 查询（Exposed）
└── UserStore.kt         # 热缓存（可选）：内存缓存高频数据
```

**为什么分三层而非两层（Service + Repository）**：
- `Store` 是可选的热缓存层（ConcurrentHashMap），针对高频读取数据（在线用户列表、群成员）。
- 不是每个领域都有 Store——只有访问频率高、变更频率低的数据才值得缓存。
- Store 不持久化，进程重启后从 Repository 重建。

### 为什么用 DDD 而非 MVC 分层

- MVC 的 Controller/Service/DAO 是技术分层（按调用方向），DDD 是业务分层（按领域边界）。
- DDD 让新增业务领域时（如未来加"频道"功能），只需新建 `domain/channel/` 目录，不触碰其他领域。
- 每个 domain 是一个内聚单元：修改用户逻辑不会意外影响消息逻辑。

---

## 3. 依赖注入：为什么 Koin 不用 Hilt/Guice

### 选型

| 方案 | 否决理由 |
|------|---------|
| Hilt/Dagger | 需要注解处理器（KAPT/KSP），增加编译时间；Android 生态绑定，服务端不自然 |
| Guice | 运行时反射，启动慢；重量级（Guice core ~1MB） |
| Spring DI | 引入整个 Spring 生态，违背极简原则 |
| **Koin** | ✅ 纯 Kotlin DSL，零反射，零注解处理器，轻量（~200KB） |

### Koin 配置

```kotlin
val serverModule = module {
    single { UserService(get(), get()) }           // 构造器注入
    single { ChatService(get(), get(), get()) }
    single { FileStore(get(), get()) }
    single { RpcDispatcher(get(), get(), ...) }    // 8 个 RouteHandler 注入
}
```

**为什么 Koin 适合这个项目**：
- 纯 Kotlin，与全栈 Kotlin 理念一致
- 零编译时开销（无 KAPT/KSP）
- DSL 声明式配置，直观可读
- 服务端是单进程，不需要分布式 DI 特性

---

## 4. 协议层：连接状态机与线程模型

### 状态机

```
CONNECTED → AUTHENTICATED → DISCONNECTED
```

只有 3 个状态。详见 [01-protocol/README.md §4](../01-protocol/README.md#4-包类型与连接状态机)。

### 线程模型：EventLoop vs IOExecutor

```
EventLoop (Netty NioEventLoop)
  ├── PING/PONG 处理（纳秒级）
  ├── 数据提取 + 协程启动
  └── 非阻塞操作

IOExecutor (协程调度器)
  ├── AUTH 认证（DB 查询）
  ├── INVOKE 业务处理（DB + RocksDB）
  └── MESSAGE 消息处理（存储 + 推送）
```

**为什么必须分线程**：Netty 的契约是"永远不要阻塞 EventLoop"。EventLoop 被 N 个连接共享，一个 DB 写阻塞 EventLoop 就会卡住所有连接。

**ImAgentFacade（WeakReference 门面）**：协程通过 WeakReference 访问 ImAgent。连接断开后 ImAgent 可被 GC 回收，协程恢复时检测到 null 抛 `AgentDisposedException` 静默吞掉。解决"异步工作比连接活得更久"的问题，不需要显式取消管道。

详细线程模型文档见 [threading.md](threading.md)。

---

## 5. 采样日志体系：为什么不用 SLF4J

### 问题背景

TCP 层有三个特殊挑战，传统 SLF4J 无法应对：

1. **Netty 线程上下文丢失**：Netty EventLoop 为多个连接服务，传统基于线程变量的日志上下文（MDC）失去意义。一个 EventLoop 片段推动某个业务流程的一小步，频繁切换线程。
2. **海量流量**：一个线程可能处理上百万连接，完整打印日志数量巨大。但随机采样会丢失完整上下文——某个流程没有完整 trace。
3. **日志写入阻塞**：EventLoop 处理大量连接时，即使写日志的时间也会被放大为百万级影响。

### 解决方案：Recorder + SamplingManager

**Recorder**：每个 TCP 连接绑定一个 Recorder（通过 Netty Channel AttributeKey）。

- **认证前**：缓存最近 30 条日志（内存），不写文件。如果连接在认证前断开，这些日志用于排查握手/认证问题。
- **认证后**：升级到采样 Writer（`RealWriter`）。被采样的连接获得完整 trace；未被采样的连接用 `NopWriter`（零开销）。

**SamplingManager**：全局采样控制。

- 最多 100 个同时采样的连接（`MAX_SAMPLE = 100`）。
- 专用 trace Looper 线程写日志，不阻塞 EventLoop。
- 懒加载 `record(Supplier<String>)` + `enable()` 短路：未采样时日志消息不拼接、不产生字符串碎片。

**为什么不用 SLF4J**：
- SLF4J 的 MDC 基于线程变量，Netty 多连接共享 EventLoop 时 MDC 串台。
- SLF4J 没有"按用户采样"的天然能力——需要大量自定义。
- SLF4J 写日志在调用线程同步执行，可能阻塞 EventLoop。
- Recorder 的懒加载 Supplier 在未采样时零开销（不拼接字符串），SLF4J 即使 `isDebugEnabled` 为 false 也会评估参数。

---

## 6. 事件同步机制

### 服务端事件发射

数据变更时通过 `SyncEventService` 发射事件：

1. 写入 `sync_events` 表（持久化，7 天过期）
2. 通过 TCP NOTIFY 实时推送给在线客户端
3. 离线客户端下次上线时补发

### 事件快照原则

NOTIFY 推送**完整当前快照**而非增量变更。客户端直接 upsert，天然幂等。

### 双层同步

| 层 | 序列号 | 覆盖 | 补发方式 |
|----|--------|------|---------|
| 事件层 | 全局 `eventId` | 所有事件 | AUTH 时 `lastEventId` 补发 |
| 消息层 | per-chat `serverSeq` | 消息历史 | `SUBSCRIBE(lastSeq)` 按会话补拉 |

详见 [01-protocol/README.md §7](../01-protocol/README.md#7-事件推送notify与离线补发)。

---

## 7. 存储架构概览

| 存储 | 用途 | 设计文档 |
|------|------|---------|
| PostgreSQL | 关系数据（用户/群组/好友/会话/设备） | 本文档 |
| RocksDB (MessageStore) | 消息存储（chatId+seq key） | [file-storage.md](file-storage.md) |
| RocksDB (FileStore) | 文件存储（BlobDB + 文件系统三层） | [file-storage.md](file-storage.md) |
| Lucene | 全文搜索（IK 中文分词） | [fulltext-search.md](fulltext-search.md) |

文件存储的完整设计决策（为什么不用 MinIO、三层架构、RocksDB 调优）见 [file-storage.md](file-storage.md)。

---

## 8. 启动流程

```kotlin
fun main() {
    // 0. Environment 先于 logback（LOG_DIR 依赖 Environment）
    System.setProperty("LOG_DIR", Environment.logsDir.absolutePath)

    // 0.5 注入 TkLogger（shared 模块日志通过 SLF4J 输出）
    TkLoggerFactory.install { name -> Slf4jTkLogger(LoggerFactory.getLogger(name)) }

    // 1. 启动 Ktor（HTTP + TCP）
    startServer()
}
```

**关键约束**：Environment 必须先于 logback 初始化（logback 的 LOG_DIR 依赖 Environment 设置的系统属性）。Environment 内部用 `System.err.println` 而非 SLF4J，避免循环依赖。

**相关代码**：`server/.../Application.kt`、`server/.../env/Environment.kt`、`server/.../di/ServerModule.kt`
