# 服务端架构对比分析

> Signal Server vs Telegram (推断) vs TeamTalk Server

---

## 1. 架构总览对比

| 维度 | Signal Server | Telegram (推断) | TeamTalk |
|------|--------------|-----------------|----------|
| **语言** | Java (Dropwizard) | C++/Go (推断) | Kotlin (Ktor + Netty) |
| **部署模式** | 微服务（多个独立服务） | 分布式多 DC | 单体应用 |
| **数据库** | DynamoDB + Redis | 自定义分布式存储 | PostgreSQL + RocksDB |
| **消息存储** | 有限期存储（30天 TTL） | 云端永久存储 | RocksDB（按频道存储） |
| **文件存储** | S3 + GCS | 自建分布式存储 | MinIO |
| **推送** | FCM + APNs | 自建推送系统 | 无 |
| **分布式** | 天然微服务 | 多数据中心 | 单机 |
| **监控** | OpenTelemetry + Micrometer | 自建监控 | 基础日志 |
| **消息队列** | Redis Pub/Sub | 自定义队列 | 内存队列 |

---

## 2. Signal Server 架构详解

### 2.1 模块划分

```
Signal-Server/
├── service/              # 主服务（消息路由、API）
├── websocket-entities/   # WebSocket 实体定义
├── websocket-resources/  # WebSocket 资源库
├── redis-dispatch/       # Redis 消息分发
└── sample-config/       # 示例配置
```

### 2.2 核心服务组件
- **消息接收服务**：接收客户端消息，持久化到 DynamoDB，投递到 Redis 队列
- **消息分发服务**：从 Redis 队列读取消息，通过 WebSocket 推送给在线用户
- **推送服务**：集成 APNs (iOS) + FCM (Android)，向离线用户推送通知
- **账户服务**：用户注册、设备管理、密钥管理（预密钥上传/下载）
- **群组服务**：群组创建、成员管理、Sender Key 分发
- **存储服务**：附件上传下载（S3/GCS 直传）、配置文件同步
- **ZK 安全服务**：零知识证明保护元数据（群组成员验证）

- **垃圾过滤服务**：可插件的垃圾信息过滤器（spam-filter 子模块）

- **调度服务**：节能推送调度、批量通知合并、时区感知

### 2.3 消息投递架构
```
Client ──WebSocket──> Signal Server
                            │
                            ├── 持久化到 DynamoDB（30天 TTL）
                            ├── Redis Pub/Sub 广播
                            ├── WebSocket 推送给在线接收方
                            └── FCM/APNs 推送给离线接收方
```

**可靠性保证**：
- 消息持久化后才确认投递
- 推送失败时重试机制（指数退避）
- 消息回执确保端到端交付
- 在线用户通过心跳检测连接状态

### 2.4 数据库设计
- **DynamoDB**：主要存储（accounts, messages, profiles, ecKeys 等）
- **Redis**：缓存 + 消息分发（Pub/Sub）
- 消息表带 TTL（30天自动清理）
- 按设备 ID 分片，支持多设备

### 2.5 API 设计
```
/v1/
├── auth/                    # 认证
├── messages/                # 消息
├── accounts/                # 账户
├── devices/                 # 设备管理
├── profiles/                # 用户资料
├── attachments/             # 附件
├── groups/                  # 群组
└── websocket/               # WebSocket 端点
```

支持 REST + gRPC 双协议。

---

## 3. TeamTalk 当前架构

### 3.1 模块划分
```
server/
├── api/         # 6 个 Ktor 路由模块
├── service/     # 7 个 Service
├── db/          # DAO 层（PostgreSQL + RocksDB）
├── tcp/         # Netty TCP 服务（端口 5100）
└── module/      # Ktor 模块注册
```

### 3.2 数据库设计
- **PostgreSQL**：用户、频道、会话、好友、联系人（关系型数据）
- **RocksDB**：消息存储（按频道存储，键格式: channelIdLength + channelId + seq）
- **MinIO**：文件存储（图片、视频、文件）

---

## 4. 差距分析与改进建议

### 4.1 短期可改进（不改变架构）
| 项目 | 优先级 | 说明 |
|------|--------|------|
| 消息搜索 | **高** | 添加全文搜索能力（Apache Lucene 嵌入式，详见 [10-fulltext-search.md](../design/10-fulltext-search.md)） |
| Desktop 推送通知 | **高** | 础于系统托盘的通知，收到消息时提醒用户 |
| 文件上传优化 | 中 | 分片上传、断点续传、进度显示、大小限制 |
| 消息同步增强 | 中 | 支持消息已读回执同步、多设备消息状态同步 |

### 4.2 中期考虑（需架构调整）
| 项目 | 优先级 | 说明 |
|------|--------|------|
| Android 推送 | 高 | 集成 FCM，实现离线推送 |
| 服务端缩略图 | 中 | 上传图片时生成缩略图，列表/预览使用不同尺寸 |
| 消息队列持久化 | 中 | 当前消息投递在内存中，服务重启可能丢失在线投递 |
| 已读状态同步 | 中 | 多设备间的已读/未读状态同步 |

### 4.3 不建议采用的
| 项目 | 原因 |
|------|------|
| 微服务拆分 | 中小型组织 IM，单机部署，微服务增加运维复杂度 |
| DynamoDB | 自建部署不适合使用 AWS 托管服务 |
| CDN | 中小规模场景不需要 CDN 加速 |
| 多数据中心 | 超出目标规模 |

### 4.4 推荐的服务端演进路径
```
Phase 1（当前 → MVP 稳定）
  ├── Desktop 系统通知
  ├── 消息搜索（Apache Lucene 嵌入式，详见 10-fulltext-search.md）
  └── 文件上传优化（大小限制 + MIME 校验）

Phase 2（功能完善）
  ├── Android FCM 推送
  ├── 服务端缩略图生成
  ├── 消息队列持久化（简单的本地文件队列）
  └── 已读回执多设备同步
```
