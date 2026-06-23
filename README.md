# TeamTalk

> **官网**: [https://im.virjar.com](https://im.virjar.com) | **GitHub**: [https://github.com/virjar/team-talk](https://github.com/virjar/team-talk)

基于 Kotlin Multiplatform (KMP) + Jetpack Compose 的跨平台即时通讯与办公协作应用，包含完整的**自研服务端**（Ktor + Netty）和**跨平台客户端**（Android + Desktop），采用自定义二进制协议实现实时消息推送。

基于 KMP 技术将前后端开发语言收敛到 Kotlin 单一语言，开发者只需掌握一门语言即可维护整个项目。

## 项目定位

TeamTalk 的最终目标是实现一个对标钉钉、飞书的办公软件，面向中小型组织（用户规模一般不超过 1 万）。采用单体架构，几乎所有功能都可以用单机+内存的模型收敛到一个简单服务器上。无需考虑海量用户带来的系统复杂性，架构简单，对开发和运维友好。

### 项目优势

- **AI 原生项目**：本项目约 99% 的代码由 AI 编写，代码风格统一、结构清晰，天然适配 AI 辅助开发和维护。无论是部署、调试还是二次开发，都可以借助 AI 快速上手。
- **全栈 Kotlin**：前后端统一使用 Kotlin，配合详细的 `CLAUDE.md` 工程规范文件，AI 能够精准理解项目上下文并生成高质量代码。

## 功能特性

### 即时通讯

- 单聊 / 群聊（文本、图片、语音、视频、文件）
- 消息回复、转发、撤回、编辑（含「已编辑」标记）
- 富媒体消息：图片画廊、语音条、文件卡片、视频播放
- 消息已读回执、发送状态（发送中/已发送/已送达）
- 消息长按菜单（回复/编辑/撤回/转发/复制）

### 联系人与社交

- 好友管理：搜索用户、申请/接受/删除好友
- 用户资料：头像、显示名、手机号、个人简介
- 好友申请列表与红点提醒

### 群组管理

- 创建群组、邀请成员
- 群公告编辑与展示
- 群成员列表、角色管理（群主/管理员/成员）
- 群详情面板、退出/解散群组

### 会话管理

- 会话列表（按最后消息时间排序，置顶优先）
- 未读计数 Badge（会话级 + 应用图标级）
- 会话置顶/取消置顶
- 消息草稿（输入未发送 → 切换会话自动保存 → 显示[草稿]标记）
- 会话消息预览（最后一条消息摘要）

### 搜索

- 用户搜索（按用户名/显示名模糊匹配）
- 消息全文搜索（Lucene + IK 中文分词，服务端索引）

### 多设备与在线状态

- 多设备同时在线（每设备独立 token + TCP 连接）
- 设备管理（查看在线设备、踢下线）
- 在线状态实时推送（上线/下线通知）

### 文件存储

- 内嵌文件服务（RocksDB 小文件 + 文件系统大文件双层存储）
- HTTP 上传/下载（不走 TCP 协议，独立通道）
- Desktop 支持拖拽文件到聊天区自动发送（识别图片/视频/文件类型）

### 其他

- 亮色 / 暗色主题
- Desktop 系统托盘与桌面通知
- 本地优先架构（离线可查看历史消息/联系人/会话）
- 客户端日志体系（trace/fault/snapshot 分级 + HTTP 上传 + Crash 持久化）

## 技术栈

| 组件 | 版本 | 用途 |
|------|------|------|
| Kotlin | 2.3.20 | 全栈语言 |
| Compose Multiplatform | 1.10.3 | 跨平台 UI |
| Ktor | 3.4.3 | HTTP 服务端 |
| Netty | 4.1.119 | TCP 长连接 |
| Exposed | 0.61.0 | 数据库 ORM（服务端） |
| SQLDelight | 2.3.2 | 本地数据库（客户端） |
| PostgreSQL | 16 | 关系型数据 |
| RocksDB | 9.10.0 | 消息存储 + 文件存储（服务端） |
| Lucene | 9.12.0 | 全文搜索索引 |
| kotlinx.serialization | 1.8.1 | JSON 序列化 |
| kotlinx.coroutines | 1.10.2 | 异步编程 |

Android SDK：minSdk 26 / targetSdk 35 / compileSdk 36，JVM target 17。

## 项目结构

```
TeamTalk/
├── shared/        # 共享协议层 — 二进制协议定义、DTO、消息编解码、ImClient
├── server/        # 服务端 — Ktor HTTP + Netty TCP，业务逻辑与数据存储
├── app/           # 共享客户端 — ViewModel、Repository、通信层、UI 屏幕（commonMain）
│   ├── commonMain/  # Android/Desktop 共享代码（70%+）
│   ├── androidMain/ # Android 平台实现
│   └── desktopMain/ # Desktop 平台实现
├── android/       # Android 应用 — Activity 入口、Application、导航
├── desktop/       # Desktop 应用 — ComposeWindow 入口、三栏布局、测试服务
├── doc/           # 详细文档（7大模块多级目录）
├── tools/e2e/     # E2E 测试工具（TestHttpServer 客户端、TestPeer 对端脚本）
├── buildSrc/      # Gradle 构建渠道定义（BuildProfile）
├── gradle/        # secrets 文件（*.secrets，不入 Git）
└── docker-compose.yml  # PostgreSQL 开发环境
```

## 快速开始

### 前置要求

- JDK 17+
- Docker（用于 PostgreSQL）
- Android Studio（Android 开发）

### 启动开发环境

```bash
# 1. 启动基础设施
docker compose up -d

# 2. 启动服务端（HTTP 8080 / TCP 5100）
./gradlew :server:run

# 3. 启动 Desktop 客户端
./gradlew :desktop:run

# 4. 编译检查（最快验证）
./gradlew :desktop:compileKotlin
```

详细的开发环境搭建请阅读 [doc/00-overview/getting-started/develop.md](doc/00-overview/getting-started/develop.md)。

### 常用命令

```bash
./gradlew :desktop:compileKotlinDesktop  # 编译检查（最快）
./gradlew :server:run                     # 启动服务端
./gradlew :desktop:runDev                 # Desktop（dev profile）
./gradlew :desktop:runDemo                # Desktop（demo profile，含测试HTTP服务）
./gradlew :server:test                    # 运行集成测试
./gradlew deployServerDemo                # 部署服务端到 demo 服务器
```

### Desktop 多实例运行

通过自定义数据目录运行多个客户端实例：

```bash
./gradlew :desktop:run -PDATA_DIR=$HOME/.tk/user1
./gradlew :desktop:run -PDATA_DIR=$HOME/.tk/user2
```

## 架构概览

```
┌─ Android (Activity) ─┐   ┌─ Desktop (ComposeWindow) ─┐
│  TeamTalkApp          │   │  Main.kt (入口)            │
│  MainActivity         │   │  三栏布局 + 系统托盘        │
│  NavHost 导航         │   │  子窗口模式                │
└──────────┬────────────┘   └──────────┬─────────────────┘
           └──────────┬────────────────┘
                      ▼
           :app/commonMain（共享 70%+ 代码）
    ┌─────────────┼──────────────┐
    ViewModel  Repository   Client 层
    (StateFlow)            ┌────┴────┐
                  HttpLogUploader  ImClient
                  (HTTP 日志)      (TCP 长连接)
                                      │
              ┌───────────────────────┘
              ▼
    :shared（协议层 — 二进制编解码、DTO、ImClient、TkLogger）
              │
              ▼
    :server（Ktor + Netty 单体）
    ├── HTTP API（文件上传/下载、客户端日志接收）
    ├── TCP 长连接（自定义二进制协议）
    ├── Domain 层（user/auth/contact/chat/message/conversation/device/presence）
    └── PostgreSQL + RocksDB + Lucene
```

**核心设计**：
- **本地优先**：客户端所有页面从本地 SQLite 渲染，网络仅用于写操作和事件同步
- **事件快照**：NOTIFY 推送携带完整快照，客户端 upsert 天然幂等
- **离线补发**：AUTH 时携带 lastEventId，服务端补发缺失事件
- **单体架构**：单进程面向万级用户，不拆微服务

更多架构细节请阅读 [doc/00-overview/architecture.md](doc/00-overview/architecture.md)。

## 通信协议

采用自定义 TCP 二进制协议（不使用 Protobuf/JSON）：

- **帧格式**：`Magic(2B) + Length(4B) + PacketType(1B) + Payload`
- **包类型**：HANDSHAKE / AUTH / INVOKE / RESPONSE / MESSAGE / MESSAGE_ACK / NOTIFY / PING / PONG
- **RPC**：INVOKE(requestId, serviceId, methodId, payload) → RESPONSE(requestId, status, payload)
- **消息**：MESSAGE 发送 → MESSAGE_ACK 确认（clientMsgId 幂等去重 + serverSeq 分配）
- **推送**：NOTIFY 携带完整事件快照，客户端直接 upsert
- **心跳**：客户端 15s PING，45s 无数据断开重连

支持 15 种消息类型（文本/图片/语音/视频/文件/回复/转发/撤回/编辑等）。

详见 [doc/01-protocol/](doc/01-protocol/)。

## 部署

所有部署通过 Gradle 多渠道构建系统完成，Profile 是唯一配置入口：

```bash
# 首次部署（HTTP 模式）
./gradlew deployServerDemo

# 首次部署（HTTPS 模式）
./gradlew deployServerDemo -PsslCert=cert.pem -PsslKey=key.pem

# 升级（自动检测已有部署，保留数据和配置）
./gradlew deployServerDemo

# 构建并上传客户端安装包
./gradlew uploadDemoRelease
```

生产环境目录结构：

```
/opt/teamtalk/
├── bin/      # 可执行文件
├── data/     # 数据（rocksdb/ lucene-index/ logs/ client-logs/）
├── conf/     # 配置文件（env.sh 含敏感密码，权限 600）
├── static/   # 产品首页 + 客户端下载文件
└── docker-compose.yml
```

详细的部署指南请阅读 [doc/00-overview/getting-started/deploy.md](doc/00-overview/getting-started/deploy.md)。

## 数据存储

| 存储引擎 | 用途 | 位置 |
|---------|------|------|
| PostgreSQL | 用户、群组、好友、会话、设备等关系数据 | 服务端 |
| RocksDB | 消息存储（键格式: chatId + serverSeq） | 服务端 |
| RocksDB | 文件存储（小文件 BlobDB + 大文件文件系统） | 服务端 |
| Lucene | 消息全文搜索索引（IK 中文分词） | 服务端 |
| SQLite | 客户端本地数据库（SQLDelight） | 客户端 |

## 文档

完整文档位于 [doc/](doc/) 目录，按模块组织为 7 大主题：

| 文档 | 内容 |
|------|------|
| [doc/README.md](doc/README.md) | 文档总索引 |
| [doc/00-overview/architecture.md](doc/00-overview/architecture.md) | 架构总览（原则 + 系统图） |
| [doc/01-protocol/](doc/01-protocol/) | 协议设计（帧格式/RPC/认证/错误码） |
| [doc/02-server/](doc/02-server/) | 服务端架构（领域层/线程模型） |
| [doc/03-client/](doc/03-client/) | 客户端架构（本地优先/状态合并/文件树） |
| [doc/04-shared/](doc/04-shared/) | 共享 SDK（模型/协议/TkLogger） |
| [doc/05-logging/](doc/05-logging/) | 日志体系（trace/fault/HTTP上传/Crash） |
| [doc/06-testing/](doc/06-testing/) | E2E 测试（TestHttpServer/testTag/用例） |
| [doc/07-conventions/](doc/07-conventions/) | 编码规范（6大约束） |
| [doc/00-overview/architecture-comparison.md](doc/00-overview/architecture-comparison.md) | 与 Signal/Telegram 等横向对比 |
| [doc/00-overview/getting-started/develop.md](doc/00-overview/getting-started/develop.md) | 开发环境搭建 |
| [doc/00-overview/getting-started/deploy.md](doc/00-overview/getting-started/deploy.md) | 部署指南 |

## ⚠️ 项目状态

当前项目处于早期开发阶段，尚未正式发布。**代码结构和数据结构可能随时发生重大变化，不保证向后兼容。**

- 请勿将项目直接用于生产环境
- 如确实用于生产，请自行评估风险，并**谨慎升级版本**
- 升级前务必检查变更日志，数据库和协议可能有不兼容改动

## 致谢

- **[GLM](https://www.bigmodel.cn/glm-coding)（智谱大模型）**：本项目约 99% 的代码由 GLM 编写，从协议设计、服务端架构到跨平台客户端 UI。
- [TangSengDaoDao](https://github.com/TangSengDaoDao)（唐僧叨叨）：TeamTalk 早期深度参考了唐僧叨叨进行移植开发，在设计模式和业务模型上有一脉相承的关系，但技术栈和协议层已完全独立实现。
- [Signal](https://github.com/signalapp/Signal-Android) / [Telegram](https://github.com/DrKLO/Telegram)：图片气泡尺寸策略、消息同步模型等参考了这两个世界级 IM 的设计。

## License

[MIT License](LICENSE)
