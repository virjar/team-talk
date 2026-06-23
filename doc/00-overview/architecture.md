# TeamTalk — 架构总览

> 快速了解全貌的入口文档。详细设计见各模块子文档。

---

## 设计原则

| 原则 | 说明 |
|------|------|
| **模型确定性 > 灵活性** | 二进制协议字段增删只在大版本间变更，编译器保障引用 |
| **单一模型消除映射层** | 同一个 data class 既是传输模型又是领域模型 |
| **状态一致性优先** | 服务端是唯一真相源，客户端本地缓存以服务端 seq 为准 |
| **本地优先** | 客户端所有页面从本地 SQLite 渲染，网络仅用于写操作和同步 |
| **单体架构** | 单进程面向万级用户，不拆微服务 |
| **全栈 Kotlin** | KMP + Compose Multiplatform，一门语言维护整个项目 |
| **事件快照** | NOTIFY 推送携带完整快照，客户端 upsert 天然幂等 |
| **离线补发** | AUTH 时携带 lastEventId，服务端补发缺失事件 |
| **不可变数据** | data class 全用 val，copy() 更新 |
| **乐观更新** | 消息发送 optimistic update |
| **认证失效停而非重试** | token 过期停止重连，向上传播让用户重新登录 |

---

## 系统架构

```
┌──────────────────────────────────────────────────────────┐
│                      TeamTalk 系统                        │
│                                                           │
│  ┌──────────────┐        ┌───────────────────────────┐  │
│  │   Android    │        │        Desktop            │  │
│  │  Compose MP  │        │    Compose MP             │  │
│  └──────┬───────┘        └───────────┬───────────────┘  │
│         │        共享 app/commonMain    │                  │
│         │     (UI + VM + Repo + Cache) │                  │
│         └───────────┬─────────────────┘                  │
│                     │ TCP 二进制协议                      │
│         ┌───────────▼─────────────────┐                  │
│         │       shared 模块            │                  │
│         │  (协议 + 模型 + ImClient)    │                  │
│         └───────────┬─────────────────┘                  │
│                     │                                    │
│  ┌──────────────────▼────────────────────────────────┐  │
│  │              TeamTalk Server                       │  │
│  │  Ktor(HTTP) + Netty(TCP)                          │  │
│  │  domain: user/auth/contact/chat/message/...       │  │
│  │  storage: PostgreSQL + RocksDB + Lucene           │  │
│  └───────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────┘
```

---

## 技术栈

| 层级 | 技术 |
|------|------|
| 客户端 UI | Compose Multiplatform（Android + Desktop） |
| 客户端存储 | SQLDelight（SQLite） |
| 共享协议 | 自研 TCP 二进制（ProtoCodec） |
| 服务端 HTTP | Ktor |
| 服务端 TCP | Netty |
| 服务端存储 | PostgreSQL (Exposed) + RocksDB + Lucene |
| 语言 | 全栈 Kotlin |

---

## 模块文档索引

深入每个模块的详细设计：

| 模块 | 文档 | 核心内容 |
|------|------|---------|
| 协议 | [01-protocol/](01-protocol/) | 帧格式、RPC、NOTIFY、心跳、编解码、认证、错误码 |
| 服务端 | [02-server/](02-server/) | 领域层 DDD、协议层状态机、线程模型、存储设计 |
| 客户端 | [03-client/](03-client/) | 本地优先、连接管理、状态合并、导航、消息渲染 |
| 共享 SDK | [04-shared/](04-shared/) | 数据模型、协议枚举、ImClient、TkLogger |
| 日志 | [05-logging/](05-logging/) | trace/fault/snapshot 分级、HTTP 上传、Crash 持久化 |
| 测试 | [06-testing/](06-testing/) | AI 驱动测试、TestHttpServer、testTag 参考、测试用例 |
| 规范 | [07-conventions/](07-conventions/) | 编码约束、RPC 配对、状态管理规则 |

## 其他文档

| 文档 | 内容 |
|------|------|
| [architecture-comparison.md](architecture-comparison.md) | 与 Signal/Telegram/WuKongIM/OpenIM 横向对比 |
| [develop.md](develop.md) | 开发环境搭建 |
| [deploy.md](deploy.md) | 部署指南 |
| [ROADMAP.md](ROADMAP.md) | 功能路线图 |
