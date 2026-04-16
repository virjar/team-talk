# Signal / Telegram 对比技术文档索引

> **文档状态：已归档**
>
> 本目录包含 TeamTalk 项目早期的竞品调研和技术设计文档。通过对比 Signal、Telegram 两个成熟 IM 产品的协议、架构和功能，为 TeamTalk 制定技术路线。所有文档中的规划任务已实施完毕（或已明确取消），本文档集不再维护，仅供未来开发时参考。
>
> **已完成**：Phase 0（协议对齐）4 项、Phase 1（基础加固）6 项、Phase 2（功能完善）6 项、Phase 3（体验优化）4 项，共 20 项任务。
> **已取消**：B3.5 TLS 传输加密、B3.6 Android 推送通知。
> **任务清单**：见 [TASKS.md](TASKS.md)。
>
> Signal / Telegram / TeamTalk 三方对比分析 → TeamTalk 设计规范

---

## 背景

TeamTalk 的 IM 基本骨架已经搭建完成——注册登录、1:1 聊天、群组、好友管理、文件/图片/视频消息等核心功能可用。但在日常使用中暴露出一些基础层面的缺失：每次打开聊天页都从服务器全量拉取消息、收到消息无系统通知、TCP 连接明文传输等。

为明确下一步的演进方向，我们选取了两个成熟的 IM 开源项目进行深度分析：

- **Signal**（signalapp）— 安全性最高的 IM，默认 E2EE，客户端和服务端均开源。分析参考了 `signalapp` 仓库中的 Android 客户端（Kotlin + Compose）、Desktop 客户端（TypeScript + React/Electron）和服务端（Java/Dropwizard）。
- **Telegram**（telegram）— 功能最丰富的 IM，拥有自研协议（MTProto）、极致的客户端性能优化。分析参考了 `telegram` 仓库中的 Android 客户端源码和 MTProto 协议文档。Telegram 服务端未开源，服务端部分仅根据客户端行为和协议规范进行推断。

本组文档从 **协议设计、架构对比、功能规划** 三个维度逐层对比 Signal、Telegram 与 TeamTalk，目标是：

1. **找准差距**：明确 TeamTalk 相比成熟产品缺少了什么、哪些是基础层面的缺失、哪些是功能性的缺失
2. **避免踩坑**：借鉴成熟项目的架构决策和实现经验，减少试错成本
3. **明确路线**：根据 TeamTalk 的定位（中小型组织办公 IM，单体架构，<1 万用户），制定切实可行的渐进演进计划

---

## 架构决策框架

本组文档中的所有技术决策，都遵循以下两级评估框架。

### 第一约束：单体 + 开发者友好（不可妥协）

TeamTalk 面向中小型组织（用户规模 ≤ 1 万），定位是办公协作软件。这意味着：

- **必须单体架构**：一个进程、一个代码库，`./gradlew :server:run` 即可启动全部服务
- **不引入外部中间件**：没有 Kafka、没有 ZooKeeper、没有独立缓存集群。基础设施只有 PostgreSQL + MinIO（都是必需的持久化组件）
- **开发者调试上手友好**：新人 clone 仓库后，`docker compose up` + `./gradlew :server:run` + `./gradlew :desktop:run` 三步就能跑起来全链路。不需要理解分布式协调、不需要搭建微服务网格

这是所有决策的硬边界。任何违反第一约束的方案，无论技术上多优雅，都不予采纳。

### 第二层：性能优先（隐藏复杂度，鼓励引入）

满足第一约束之后，TeamTalk 并不放弃性能要求。判断标准：

> 如果一项技术决策带来了**可观的性能提升**，且其复杂度被**封装在 TeamTalk 内部**（开发者无需理解即可使用），那么这项决策是值得鼓励的。

典型例子：

| 决策 | 带来的复杂度 | 带来的性能提升 | 为什么值得 |
|------|-------------|---------------|-----------|
| field-order binary 替代 JSON | 需要手写 `IProto` 序列化 | 解析速度 5-10x，带宽节省 30-50% | 开发者只需写 Kotlin data class + `IProto` 接口，编码细节隐藏在 `PacketCodec` 中 |
| Netty ByteBuf 零拷贝 | 需要 `retain`/`release` 生命周期管理 | 消除大消息的内存拷贝 | 开发者接触不到 ByteBuf，由 `PacketDecoder`/`PacketEncoder` 自动处理 |
| RocksDB 消息存储 | 需要管理 native 实例生命周期 | 写入吞吐量远超 PostgreSQL，天然有序 | 开发者通过 `MessageService` 调用，不直接操作 RocksDB API |

反例：引入 Redis 做缓存。虽然能提升读取性能，但增加了外部中间件依赖，违反第一约束。

### 分析原则

基于上述框架，对比 Signal / Telegram 时的核心原则是：**借鉴不照搬**。Signal 和 Telegram 面向数亿用户，它们的很多设计（微服务、多数据中心、自研加密协议）对 TeamTalk 是过度设计。每项建议都需通过上述两级框架过滤。

---

## 文档导航

> AI 辅助阅读提示：每篇文档限制 20K 以内，避免上下文溢出影响推理效果。
>
> **目录结构**：`design/` 为 TeamTalk 的设计规范（开发时需遵守），`analysis/` 为 Signal/Telegram 对比分析（提供决策背景）。

### 一、方案设计（`design/` — TeamTalk 的设计规范，开发时需遵守）

**协议与消息模型**：

| 文件 | 内容 |
|------|------|
| [01-message-model.md](design/01-message-model.md) | 消息模型骨架：基础结构 + Channel + 标志位 + 生命周期 |
| [01a-content-messages.md](design/01a-content-messages.md) | 内容消息设计：TEXT/IMAGE/VOICE/VIDEO/FILE/STICKER/INTERACTIVE/RICH（payload 定义见 03a §2） |
| [01b-action-messages.md](design/01b-action-messages.md) | 操作消息设计：REPLY/FORWARD/MERGE_FORWARD/REVOKE/EDIT/REACTION（payload 定义见 03a §3） |
| [01c-system-messages.md](design/01c-system-messages.md) | 系统消息设计：频道生命周期 + 成员变更 + 群设置（payload 定义见 03a §4） |
| [01d-control-messages.md](design/01d-control-messages.md) | 控制消息设计：TYPING/CMD/ACK/PRESENCE（payload 定义见 03a §5-6） |
| [03-binary-encoding.md](design/03-binary-encoding.md) | 二进制编码设计：包格式、field-order 编码、IProto 接口、Netty 零拷贝优化 |
| [03a-payload-definitions.md](design/03a-payload-definitions.md) | **Payload 单一真相源**：所有 PacketType 的 payload 字段定义 |

**架构决策与演进路线**：

| 文件 | 内容 |
|------|------|
| [04-security.md](design/04-security.md) | 安全定位：办公 IM 不做 E2EE，TLS 传输加密 + OS 底层安全 |
| [05-evolution-roadmap.md](design/05-evolution-roadmap.md) | 演进路线图：3 Phase 渐进演进计划 |
| [06-connection-lifecycle.md](design/06-connection-lifecycle.md) | 连接架构：ImClient + TcpConnection 两层分离 + EventLoop 单线程调度 + 自动重连 + 离线消息补拉 + 发送队列 |
| [07-message-sync-protocol.md](design/07-message-sync-protocol.md) | 消息同步协议：初始同步、增量同步、间隙检测、多设备已读同步 |
| [08-local-database.md](design/08-local-database.md) | 本地数据库设计：SQLDelight 选型、5 表 Schema、Repository 双源模式、增量同步 |
| [09-notification-architecture.md](design/09-notification-architecture.md) | 通知架构：ComposeNativeTray Desktop 托盘 + Android FCM + 通知过滤策略 |
| [10-fulltext-search.md](design/10-fulltext-search.md) | ADR：PostgreSQL tsvector vs Apache Lucene → 选择 Lucene |
| [11-file-transfer.md](design/11-file-transfer.md) | 文件传输 + 存储架构：上传/下载、Server 流式代理（隐藏 MinIO）、进度反馈、自动下载 |
| [12-draft-and-unread.md](design/12-draft-and-unread.md) | 草稿与未读管理：草稿持久化、未读计数、Badge 显示 |
| [13-error-codes.md](design/13-error-codes.md) | 错误码体系：TCP 协议码 + HTTP API 5 位模块码 + CMD 推送错误 |
| [14-group-permission-matrix.md](design/14-group-permission-matrix.md) | 群组权限矩阵：三级角色（OWNER/ADMIN/MEMBER）+ 权限矩阵 + 禁言设计 |
| [15-group-invite-links.md](design/15-group-invite-links.md) | 群邀请链接：Base62 token URL、过期/次数限制、可选审批流程、二维码 |
| [16-message-deletion-local.md](design/16-message-deletion-local.md) | 本地消息删除："仅删除我本地"，纯客户端操作，不同步 |

### 二、对比分析（`analysis/` — Signal/Telegram 的架构分析，提供决策背景）

| 文件 | 内容 |
|------|------|
| [01-protocol-comparison.md](analysis/01-protocol-comparison.md) | 协议层对比：包格式、序列化（Protobuf/TL/JSON/field-order binary）、负载建模、消息操作、扩展性 |
| [02-server-architecture.md](analysis/02-server-architecture.md) | 服务端架构对比：微服务 vs 分布式 vs 单体 |
| [03-client-architecture.md](analysis/03-client-architecture.md) | 客户端架构对比：Redux/MVVM、本地数据库、网络层 |
| [04-chat-ui-architecture.md](analysis/04-chat-ui-architecture.md) | 聊天 UI 对比：消息渲染、输入面板、操作架构 |
| [05-feature-gap.md](analysis/05-feature-gap.md) | 功能矩阵：功能逐项对比 + 优先级排序（E2EE 已标记为不适用） |

---

## 核心发现

| 差距 | 说明 |
|------|------|
| 无本地数据库 | 每次打开聊天页都从服务器全量拉取消息，无离线查看能力 |
| 无系统通知 | 收到消息无任何提醒，作为 IM 工具不可接受 |
| TCP 明文传输 | 当前 TCP 连接没有任何加密（计划引入 TLS，不需要 E2EE） |

---

## 演进路线图摘要

| Phase | 主题 | 核心内容 |
|------|------|------|
| **Phase 1** | 基础加固 | 本地数据库 + Desktop 通知 + 消息搜索（Lucene） |
| **Phase 2** | 功能完善 | 多设备 + 语音消息 + 在线状态 + 文件优化 + 消息编辑 + Android 推送 |
| **Phase 3** | 体验优化 | 搜索增强 + 群组增强 + 性能优化 |

详见 [05-evolution-roadmap.md](design/05-evolution-roadmap.md)。
