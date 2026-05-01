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

- 单聊 / 群聊（文本、图片、语音、视频、文件）
- 消息回复、转发、合并转发、撤回、编辑
- 好友管理与联系人搜索
- 群组管理（成员管理、禁言、邀请链接）
- 会话管理（已读/未读、置顶、静音、草稿）
- 全文消息搜索（Lucene + IK 中文分词）
- 多设备管理与在线状态
- 文件存储（内嵌 RocksDB + 文件系统）
- 系统托盘与桌面通知（Desktop）
- 亮色 / 暗色主题

## 技术栈

| 组件 | 版本 | 用途 |
|------|------|------|
| Kotlin | 2.3.20 | 全栈语言 |
| Compose Multiplatform | 1.10.0 | 跨平台 UI |
| Ktor | 3.1.3 | HTTP 客户端 + 服务端 |
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
├── shared/        # 共享协议层 — 二进制协议定义、DTO、消息编解码
├── server/        # 服务端 — Ktor HTTP + Netty TCP，业务逻辑与数据存储
├── app/           # 共享客户端基础设施 — ViewModel、Repository、通信层、UI 组件
├── android/       # Android 应用 — Activity 入口、屏幕、导航
├── desktop/       # Desktop 应用 — ComposeWindow 入口、三栏布局、系统托盘
├── doc/           # 详细文档
│   ├── architecture.md    # 架构设计理念与技术决策
│   ├── develop.md         # 开发环境搭建指南
│   └── deploy.md          # 生产环境部署指南
├── gradle/profiles/       # 构建环境配置（dev/demo/production）+ secrets（不入 Git）
└── docker-compose.yml     # PostgreSQL 开发环境
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

# 4. 运行集成测试
./gradlew :server:test
```

详细的开发环境搭建指南请阅读 [doc/develop.md](doc/develop.md)。

### 常用命令

```bash
./gradlew :desktop:compileKotlin    # 编译检查（最快验证）
./gradlew :server:run               # 启动服务端
./gradlew :desktop:run              # 启动 Desktop 客户端
./gradlew :server:test              # 运行集成测试
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
│  App.kt (全屏导航)     │   │  Main.kt (双窗口)          │
│  ui/screen/ (20 屏幕) │   │  三栏布局 + 系统托盘        │
└──────────┬────────────┘   └──────────┬─────────────────┘
           └──────────┬────────────────┘
                      ▼
           :app/commonMain（共享基础设施）
    ┌─────────────┼──────────────┐
    ViewModel  Repository   Client 层
    (StateFlow)            ┌────┴────┐
                       ApiClient   ImClient
                       (HTTP)      (TCP 长连接)
                                      │
              ┌───────────────────────┘
              ▼
    :shared（协议层 — 二进制编解码、DTO 定义）
              │
              ▼
    :server（Ktor + Netty）
    ├── REST API（认证/消息/频道/联系人/会话/设备/文件）
    ├── TCP 长连接（Netty，自定义二进制协议）
    ├── PostgreSQL + RocksDB + Lucene
    └── 内嵌文件存储（RocksDB + 文件系统）
```

**核心设计决策**：Desktop 和 Android 各自拥有独立的屏幕代码和导航逻辑，`:app/commonMain` 仅保留共享的基础设施（组件 + ViewModel + Repository + Client）。

更多架构细节请阅读 [doc/architecture.md](doc/architecture.md)。

### 通信协议

采用自定义二进制协议，包含握手阶段和数据阶段：

- **握手**：服务端发送 MAGIC + VERSION，客户端验证后发送认证包
- **数据包**：`PacketType(1B) + Length(4B) + Payload`
- **消息流**：客户端发送 → 服务端 SENDACK → 投递给接收者 → 接收者 RECVACK
- **心跳**：客户端 30s PING，服务端 60s 无数据断开
- **安全**：握手超时 30s 防慢攻击，JWT 认证

支持 17 种消息类型（文本/图片/语音/视频/文件/位置/名片/回复/转发/合并转发/撤回/编辑/输入状态/表情贴纸/表情回应/交互式消息/富文本），以及 8 种系统事件。

## 部署

所有部署通过 Gradle Profile 系统完成，Profile 是唯一配置入口：

```bash
# 首次部署（HTTP 模式）
./gradlew deployServer -PbuildProfile=demo

# 首次部署（HTTPS 模式，提供 SSL 证书）
./gradlew deployServer -PbuildProfile=demo -PsslCert=cert.pem -PsslKey=key.pem

# 升级（自动检测已有部署，保留数据和配置）
./gradlew deployServer -PbuildProfile=demo

# 构建并上传客户端安装包到服务器
./gradlew uploadRelease -PbuildProfile=demo

# 部署服务端 + 上传客户端
./gradlew deployServer uploadRelease -PbuildProfile=demo
```

生产环境目录结构：

```
/opt/teamtalk/
├── bin/      # 可执行文件
├── data/     # 数据（rocksdb/ lucene-index/ logs/）
├── conf/     # 配置文件（env.sh 含敏感密码，权限 600）
├── static/   # 产品首页 + 客户端下载文件
└── docker-compose.yml
```

详细的部署指南请阅读 [doc/deploy.md](doc/deploy.md)。

## 数据存储

| 存储引擎 | 用途 |
|---------|------|
| PostgreSQL | 用户、频道、好友、会话、设备、邀请链接等关系数据 |
| RocksDB | 消息存储（键格式: channelIdLength + channelId + seq） |
| RocksDB | 文件存储（BlobDB + 文件系统分层） |
| Lucene | 消息全文搜索索引（IK 中文分词） |
| SQLite | 客户端本地数据库（SQLDelight） |

## 文档

| 文档 | 说明 |
|------|------|
| [doc/develop.md](doc/develop.md) | 开发环境搭建指南 |
| [doc/architecture.md](doc/architecture.md) | 架构设计理念与技术决策记录 |
| [doc/deploy.md](doc/deploy.md) | 生产环境部署指南 |

## 致谢

- **[GLM](https://www.bigmodel.cn/glm-coding)（智谱大模型）**：本项目约 99% 的代码由 GLM-5.1 编写，从协议设计、服务端架构到跨平台客户端 UI，GLM 贯穿了整个开发流程。
- [TangSengDaoDao](https://github.com/TangSengDaoDao)（唐僧叨叨）：TeamTalk 早期深度参考了唐僧叨叨进行移植开发，在设计模式和业务模型（用户、频道、消息、会话、好友关系）上有一脉相承的关系，但技术栈和协议层已完全独立实现。

## License

本项目仅供学习交流使用。
