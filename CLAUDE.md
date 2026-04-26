# CLAUDE.md — TeamTalk

> 最后更新: 2026-04-25

## 语言

始终使用中文回复。

---

## 项目简介

TeamTalk 是一个基于 Kotlin Multiplatform (KMP) + Jetpack Compose 的跨平台即时通讯与办公协作应用，包含完整的**自研服务端**（Ktor + Netty）和**跨平台客户端**（Android + Desktop），采用自定义二进制协议实现实时消息推送。基于 KMP 技术将前后端开发语言收敛到 Kotlin 单一语言，开发者只需掌握一门语言即可维护整个项目。

### 项目定位

TeamTalk 的最终目标是实现一个对标钉钉、飞书的办公软件，面向中小型组织（用户规模一般不超过 1 万）。采用单体架构，几乎所有功能都可以用单机+内存的模型收敛到一个简单服务器上。无需考虑海量用户带来的系统复杂性，架构简单，对开发和运维友好——开发调试方便、部署运维轻松。

**所有开发策略和技术决策都应基于这个目标制定。**

> TeamTalk 早期深度参考了 [TangSengDaoDao](https://github.com/TangSengDaoDao)（唐僧叨叨）进行移植开发，在设计模式和业务模型（用户、频道、消息、会话、好友关系）上有一脉相承的关系，但技术栈和协议层已完全独立实现。

---

## 目录结构

```
TeamTalk/
├── build.gradle.kts          # 根项目：版本声明 + Profile 加载 + 发布任务
├── settings.gradle.kts       # 5 模块声明 (shared/server/app/android/desktop)
├── docker-compose.yml        # PostgreSQL 16 + MinIO
├── gradle/profiles/          # 构建环境配置（dev/demo/production）+ secrets（不入 Git）
├── doc/                      # 详细文档（按需读取）
│   ├── architecture.md           # 架构设计理念、技术决策记录
│   ├── develop.md                # 开发环境搭建指南
│   ├── deploy.md                 # 生产环境部署指南
│   └── signal-telegram/          # 竞品分析文档（已归档）
│       ├── analysis/             # Signal/Telegram 协议与架构对比
│       ├── design/               # TeamTalk 设计规范（消息模型/编码/安全等）
│       └── TASKS.md              # 架构演进任务清单
│
├── shared/                   # 共享协议层 (:shared)
│   └── src/commonMain/.../tk/
│       ├── dto/                    # 数据传输对象（7 个 Dto 文件）
│       │   ├── ApiError.kt             # API 错误响应
│       │   ├── AuthDtos.kt             # 认证 DTO
│       │   ├── ChannelDtos.kt          # 频道 DTO
│       │   ├── ContactDtos.kt          # 联系人 DTO
│       │   ├── ConversationDtos.kt     # 会话 DTO
│       │   ├── DeviceDtos.kt           # 设备 DTO
│       │   └── MessageDtos.kt          # 消息 DTO
│       └── protocol/               # 协议层
│           ├── ChannelType.kt          # 频道类型枚举（PERSONAL/GROUP）
│           ├── ErrorCode.kt            # 错误码定义（SendAck / HTTP 各模块）
│           ├── Handshake.kt            # 握手常量（MAGIC/VERSION/超时/状态码）
│           ├── HandshakeHandler.kt     # 服务端握手 Handler（MAGIC 验证 + 慢攻击防御）
│           ├── IProto.kt               # 二进制序列化接口（VarInt/字符串/字节数组 + RawProto）
│           ├── PacketCodec.kt          # Netty ByteToMessageCodec（Packet 编解码合一）
│           ├── PacketType.kt           # 包类型枚举（语义拍平编码）
│           ├── TkLogger.kt             # 日志接口
│           └── payload/                # 各类型载荷实现
│               ├── AckPayloads.kt          # SENDACK / RECVACK
│               ├── ActionPayloads.kt       # REPLY / FORWARD / MERGE_FORWARD / REVOKE / EDIT / TYPING / REACTION
│               ├── AuthPayloads.kt         # AuthRequestPayload / AuthResponsePayload
│               ├── ContentPayloads.kt      # 17 种消息体（Text/Image/Voice/Video/File 等）
│               ├── ControlPayloads.kt      # CmdPayload / AckPayload / PresencePayload
│               ├── MessageBody.kt          # MessageBody 接口 + MessageBodyCreator
│               ├── MessageHeader.kt        # MessageHeader（9 个共享字段）
│               ├── MessagePayload.kt       # Message（Header + Body 组合）+ Signal 对象
│               ├── SignalPayloads.kt       # DisconnectSignal / PingSignal / PongSignal
│               ├── SubscribePayload.kt     # SubscribePayload / UnsubscribePayload
│               └── SystemPayloads.kt       # 8 种系统事件（频道/成员变更）
│   └── src/commonTest/            # 协议编解码单元测试
│
├── server/                   # 服务端 (:server) — Ktor + Netty
│   └── src/main/
│       ├── kotlin/.../api/         # REST API 路由（7 模块）
│       │   ├── ApiError.kt             # 统一错误响应
│       │   ├── AuthRoutes.kt           # /api/v1/auth/* + /api/v1/users/*
│       │   ├── ChannelRoutes.kt        # /api/v1/channels/*
│       │   ├── ContactRoutes.kt        # /api/v1/contacts/* + /api/v1/blacklist/*
│       │   ├── ConversationRoutes.kt   # /api/v1/conversations/*
│       │   ├── DeviceRoutes.kt         # /api/v1/devices/*
│       │   ├── FileRoutes.kt           # /api/v1/files/*
│       │   └── MessageRoutes.kt        # /api/v1/channels/{id}/messages + /api/v1/messages/search
│       ├── kotlin/.../db/          # 数据访问层（PostgreSQL + RocksDB）
│       │   ├── Tables.kt               # 表定义（10 张表）
│       │   ├── DatabaseFactory.kt      # HikariCP 连接池初始化
│       │   ├── GuardedDataSource.kt    # 受保护数据源（ThreadIOGuard 集成）
│       │   ├── UserDao.kt / ChannelDao.kt / FriendDao.kt / ...
│       │   ├── MessageStore.kt         # RocksDB 消息存储
│       │   ├── ConversationDao.kt / TokenDao.kt / DeviceDao.kt
│       │   └── InviteLinkDao.kt        # 群邀请链接
│       ├── kotlin/.../env/         # 环境配置
│       │   ├── Environment.kt          # 运行环境检测（开发/生产）
│       │   ├── ClassPreloader.kt       # 类预加载优化
│       │   └── ThreadIOGuard.kt        # EventLoop 线程保护
│       ├── kotlin/.../looper/      # 单线程事件循环器
│       │   └── Looper.kt               # 通用 Looper 实现（ClientRegistry/trace 共用）
│       ├── kotlin/.../s3/          # MinIO S3 客户端
│       │   ├── S3Client.kt             # S3 文件上传下载
│       │   ├── S3Handlers.kt           # S3 操作处理器
│       │   └── AwsV4Signer.kt          # AWS V4 签名算法
│       ├── kotlin/.../service/     # 业务服务层（12 个 Service）
│       │   ├── UserService / TokenService / ChannelService
│       │   ├── FriendService / MessageService / MessageDeliveryService
│       │   ├── ConversationService / FileService / DeviceService
│       │   ├── PresenceService / SearchIndex (Lucene)
│       │   └── PayloadTextExtractor
│       ├── kotlin/.../store/       # 状态缓存（内存缓存 + DB 回退）
│       │   ├── UserStore.kt            # 用户信息缓存
│       │   ├── ChannelStore.kt         # 频道信息缓存
│       │   ├── ContactStore.kt         # 好友关系缓存
│       │   ├── ConversationStore.kt    # 会话状态缓存
│       │   ├── DeviceStore.kt          # 设备信息缓存
│       │   └── InviteLinkStore.kt      # 邀请链接缓存
│       ├── kotlin/.../tcp/         # TCP 长连接（Netty，端口 5100）
│       │   ├── TcpServer.kt            # TCP 服务启动/停止 + Pipeline 组装
│       │   ├── ImAgent.kt              # 连接级处理器（包分发 + 认证状态管理）
│       │   ├── ClientRegistry.kt       # 连接注册中心（uid→多设备映射 + 在线统计）
│       │   ├── IOExecutor.kt           # IO 线程池（重量操作脱离 EventLoop）
│       │   ├── agent/                  # 业务 Dispatcher（单例对象）
│       │   │   ├── AuthProcessor.kt        # 认证处理（JWT 验签 + 注册）
│       │   │   ├── MessageDispatcher.kt    # 消息处理（禁言检查 → 存储 → 投递）
│       │   │   ├── SubscribeDispatcher.kt  # 订阅处理（离线消息补拉）
│       │   │   └── TypingDispatcher.kt     # 输入状态转发
│       │   ├── trace/                  # 链路追踪
│       │   │   ├── Recorder.kt             # 连接级日志记录器（采样 + 懒加载）
│       │   │   └── TraceLogWriter.kt       # 采样管理 + 日志写入
│       │   └── CLAUDE.md               # TCP 模块编码规范（线程模型/异步范式/GC 安全）
│       ├── resources/application.conf
│       └── resources/logback.xml
│   ├── src/dist/bin/              # 分发脚本（teamtalk.sh / teamtalk-stop.sh）
│   └── src/test/                  # 测试
│       ├── integration/               # HTTP API 集成测试
│       ├── tcp/                       # TCP 协议集成测试
│       └── unit/                      # 单元测试
│
├── app/                      # 共享基础设施模块 (:app) — Compose Multiplatform
│   └── src/
│       ├── commonMain/            # 跨平台共享代码（不含屏幕和导航）
│       │   ├── client/               # 客户端通信层
│       │   │   ├── ApiClient.kt          # HTTP 客户端（token 管理/会话恢复/错误处理）
│       │   │   ├── ImClient.kt           # TCP 长连接管理（重连/发送队列/ACK）
│       │   │   ├── TcpConnection.kt      # 单次 TCP 连接（连接+认证+心跳+数据IO）
│       │   │   ├── ClientHandshakeHandler.kt  # 客户端握手 Handler
│       │   │   ├── ServerConfig.kt       # 服务端地址配置 (expect/actual)
│       │   │   ├── UserContext.kt        # 用户上下文（ApiClient+ImClient+Repositories 整合）
│       │   │   ├── LocalUserContext.kt   # CompositionLocal 提供用户上下文
│       │   │   └── AuthDtos.kt          # 客户端认证 DTO
│       │   ├── storage/              # TokenStorage (expect/actual) — token 持久化
│       │   ├── database/             # AppDatabase (expect/actual) — 本地数据库
│       │   ├── audio/                # VoiceRecorder/VoicePlayer (expect/actual) — 语音录制/播放
│       │   ├── repository/           # 6 个 Repository
│       │   │   ├── ChatRepository / ConversationRepository / ContactRepository
│       │   │   └── ChannelRepository / UserRepository / FileRepository
│       │   ├── viewmodel/            # 4 个 ViewModel
│       │   │   ├── ChatViewModel.kt       # 聊天（消息发送/撤回/回复/转发/编辑/语音/图片/文件）
│       │   │   ├── ConversationViewModel.kt  # 会话列表/已读/草稿/置顶/静音
│       │   │   ├── ContactsViewModel.kt   # 联系人/搜索/好友申请
│       │   │   └── SearchViewModel.kt     # 消息搜索
│       │   ├── navigation/           # NavDestination + MainTab（共享类型定义）
│       │   │   └── AppNavigation.kt
│       │   ├── ui/component/         # UI 基础组件
│       │   │   ├── Avatar.kt / OnlineIndicator.kt / ConfirmDialog.kt
│       │   │   ├── QrCodeDialog.kt / FilePicker.kt
│       │   │   ├── chat/             # 聊天组件
│       │   │   │   ├── MessageBubble.kt / ChatInputBar.kt / RecordingPanel.kt
│       │   │   │   ├── MessageContentDispatcher.kt / BasicMessageRenderers.kt / RichMessageRenderers.kt
│       │   │   │   ├── ImageViewerDialog.kt / VideoPlayerDialog.kt
│       │   │   │   ├── TimeSeparator.kt / InvalidContentFallback.kt
│       │   │   │   └── conversation/ConversationItem.kt
│       │   │   └── conversation/     # 会话组件（ConversationItem）
│       │   ├── ui/theme/             # Material 3 主题（亮色/暗色）
│       │   │   └── Theme.kt
│       │   └── util/                 # 工具类
│       │       ├── AppLog.kt / ClipboardHelper.kt / FormatUtils.kt
│       │       ├── ImageCache.kt / ImageDecoder.kt / QrCodeGenerator.kt
│       │       ├── FileSaver.kt / UrlBuilder.kt / ErrorMessageMapper.kt
│       ├── androidMain/            # Android actual 实现
│       └── desktopMain/            # Desktop actual 实现
│
├── android/                  # Android 应用 (:android)
│   └── src/main/.../
│       ├── AndroidAppState.kt       # Android 状态管理
│       ├── MainActivity.kt          # Activity 入口（初始化数据库/Token/剪贴板）
│       ├── App.kt                   # Android 入口（导航 + Session 恢复）
│       ├── MainAppContent.kt        # Android 导航中心
│       ├── navigation/
│       │   └── MainScreen.kt        # 底部 Tab 导航
│       └── ui/screen/               # 20 个 Android 屏幕（可独立演化）
│           ├── LoginScreen.kt / RegisterScreen.kt
│           ├── ConversationListScreen.kt / ChatScreen.kt
│           ├── ContactsScreen.kt / MeScreen.kt
│           ├── UserProfileScreen.kt / SearchUsersScreen.kt
│           ├── FriendAppliesScreen.kt / CreateGroupScreen.kt
│           ├── GroupDetailScreen.kt / GroupMembersScreen.kt
│           ├── InviteMembersScreen.kt / InviteLinksScreen.kt
│           ├── EditProfileScreen.kt / ChangePasswordScreen.kt
│           ├── DeviceManagementScreen.kt / BlacklistScreen.kt
│           └── SearchMessagesScreen.kt / ForwardScreen.kt
│
└── desktop/                  # Desktop 应用 (:desktop)
    └── src/main/kotlin/.../
        ├── DesktopAppState.kt       # Desktop 状态管理（CompositionLocal + 三栏状态）
        ├── Main.kt                  # main() 入口 + 文件锁 + 双窗口管理
        ├── FileLocker.kt            # 文件锁（防多实例数据冲突）
        ├── DesktopMainAppContent.kt # 三栏布局中心
        ├── DesktopSidebar.kt        # 侧边栏（6 个菜单项：聊天/联系人/文档/会议/日历/待办）
        ├── DesktopListPanel.kt      # 列表面板（320dp）
        ├── DesktopDetailPanel.kt    # 详情面板（自适应宽度，聊天+覆盖层）
        ├── DesktopOverlayPanel.kt   # 覆盖层面板
        ├── DesktopUserProfileScreen.kt
        ├── tray/                    # 系统托盘
        │   ├── AppTray.kt               # 系统托盘（连接状态 + 未读数）
        │   └── DesktopNotificationManager.kt  # 桌面通知管理器
        └── ui/screen/               # 20 个 Desktop 屏幕（与 Android 对应）
```

---

## 技术栈

| 组件 | 版本 | 用途 |
|------|------|------|
| Kotlin | 2.3.20 | 全栈语言 |
| Compose Multiplatform | 1.10.0 | 跨平台 UI |
| AGP | 8.9.2 | Android 构建 |
| Ktor | 3.4.3 | HTTP 客户端 + 服务端 |
| Netty | 4.1.119 | TCP 长连接（shared + server） |
| Exposed | 0.61.0 | 数据库 ORM（服务端） |
| SQLDelight | 2.3.2 | 本地数据库（客户端） |
| PostgreSQL | 16 (Docker) | 关系型数据 |
| RocksDB | 9.10.0 | 消息存储（服务端） |
| MinIO | latest (Docker) | 对象存储 |
| Lucene | 9.12.0 | 全文搜索索引（服务端） |
| kotlinx.serialization | 1.8.1 | JSON 序列化 |
| kotlinx.coroutines | 1.10.2 | 异步编程 |
| Logback | 1.5.18 | 日志（服务端 + Desktop） |
| compose-media-player | 0.8.7 | 视频播放（客户端） |

Android SDK：minSdk 26 / targetSdk 35 / compileSdk 36，JVM target 17。

---

## 架构设计

### 客户端架构

```
Android (Activity)              Desktop (ComposeWindow)
  App.kt (全屏导航)               Main.kt (双窗口：登录+主应用)
  MainAppContent.kt              DesktopMainAppContent.kt (三栏布局)
  AndroidAppState                DesktopAppState (CompositionLocal)
  ui/screen/ (独立)              ui/screen/ (独立)
       └──────────┬──────────────────────┘
                  ▼
        :app/commonMain（共享基础设施）
  ┌──────────┼──────────┐
  ViewModel ← Repository ← ApiClient (HTTP) + ImClient (TCP)
       │                           │
  UI Component           TokenStorage / AppDatabase (expect/actual)
```

> **架构决策**：Desktop 和 Android 各自拥有独立的屏幕代码和导航逻辑，`:app/commonMain` 仅保留共享的基础设施（组件 + ViewModel + Repository + Client）。详见 [doc/architecture.md](doc/architecture.md)。

**状态管理**：

- **Android**：`AndroidAppState` 管理全局状态，通过 `LocalUserContext` CompositionLocal 向下传递
- **Desktop**：`DesktopAppState` 管理三栏布局状态（selectedTab/selectedChat/overlayDestination/themeMode），通过 `LocalDesktopState` CompositionLocal 向下传递
- **共享**：`UserContext` 整合 ApiClient + ImClient + Repositories，作为跨平台用户会话核心

**导航**：

- Android：`mutableStateOf<NavDestination>` 全屏单页面导航
- Desktop：三栏布局（Sidebar + ListPanel + DetailPanel）+ 覆盖层导航

```
NavDestination (sealed class)
 ├── Login / Register
 ├── Main (initialTab)
 ├── Chat (channelId + channelType + channelName + readSeq + scrollToSeq + otherUid)
 ├── SearchUsers → UserProfile (uid + backTo)
 ├── FriendApplies
 ├── CreateGroup
 ├── GroupDetail (channelId + channelType) → GroupMembers / InviteMembers / InviteLinks
 ├── EditProfile / ChangePassword / Blacklist
 ├── Me / DeviceManagement
 ├── SearchMessages (channelId? + channelName)
 ├── Forward (payload: Message)
 └── JoinByLink (token)
```

**ViewModel**：

```
ViewModel ─── 页面级状态（StateFlow）
  ├── ChatViewModel: 消息列表/发送/撤回/回复/转发/编辑/图片/文件/语音（乐观更新）
  ├── ConversationViewModel: 会话列表/已读/草稿/置顶/静音
  ├── ContactsViewModel: 联系人/搜索/好友申请
  └── SearchViewModel: 消息搜索

Repository ─── 数据访问封装（本地优先，网络同步）
  ├── ChatRepository / ConversationRepository / ContactRepository
  └── ChannelRepository / UserRepository / FileRepository

UserContext ─── 跨平台用户会话核心
  ├── ApiClient: HTTP 通信（token 自动恢复/401 回调）
  ├── ImClient: TCP 长连接管理（自动重连 1s→30s / 发送队列 / ACK 超时重试）
  ├── TcpConnection: 单次 TCP 连接（连接+认证+心跳+数据IO）
  └── 6 个 Repository 实例
```

### 平台差异 (expect/actual)

| 功能 | commonMain (expect) | Desktop (actual) | Android (actual) |
|------|---------------------|------------------|------------------|
| 服务端地址 | `ServerConfig` | JVM `-D` 系统属性 | BuildConfig + 模拟器检测 |
| Token 存储 | `TokenStorage` | Properties 文件 (`~/.tk/`) | SharedPreferences |
| 本地数据库 | `AppDatabase` | SQLDelight (JdbcSqliteDriver) | SQLDelight (AndroidSqliteDriver) |
| 文件选择 | `FilePicker` | JFileChooser | Activity Result |
| 文件保存 | `FileSaver` | JFileChooser 保存对话框 | MediaStore/SAF |
| 日志 | `AppLog` | SLF4J + Logback | android.util.Log |
| 剪贴板 | `ClipboardHelper` | AWT Toolkit | Platform Clipboard |
| 语音录制 | `VoiceRecorder` | Java Sound API | MediaRecorder |
| 语音播放 | `VoicePlayer` | Java Sound API | MediaPlayer |

### Desktop 特有功能

- **数据目录**：默认 `~/.tk/app_default/`，通过 `-Dteamtalk.data.dir` 或 Gradle `-PDATA_DIR` 自定义
- **文件锁**：`FileLocker` 在 `$dataDir/.lock` 上获取 `FileLock`，同一数据目录只能运行一个实例
- **Session 文件**：`$dataDir/session.properties`（token/uid/userJson）
- **系统托盘**：`AppTray` 最小化到托盘，显示连接状态和未读数，右键菜单
- **桌面通知**：`DesktopNotificationManager` 收到消息时弹出系统通知
- **三栏布局**：Sidebar(72dp) + ListPanel(320dp) + DetailPanel(自适应)
- **双窗口**：登录窗口独立，登录后切换到主窗口
- **主题切换**：支持亮色/暗色/跟随系统

### TCP 协议

自定义二进制协议，分两个阶段：

#### 1. 握手阶段（服务端发起）

```
Server → Client: MAGIC("TKPROTO" 7B) + VERSION(1B)
Client → Server: MAGIC("TKPROTO" 7B) + VERSION(1B) + AUTH 包
Server → Client: AUTH_RESP 包（PacketType.AUTH_RESP 编码）
```

流程：
1. TCP 连接建立后，服务端主动发送 `MAGIC_WITH_VERSION(8 bytes)`
2. 客户端验证 MAGIC+VERSION，然后发送 `MAGIC + VERSION + AUTH 包`（PacketType.AUTH + AuthRequestPayload）
3. 服务端验证 MAGIC+VERSION，进入 PacketCodec 解码 AUTH 包，ImAgent 处理认证
4. 认证成功后 ImAgent 回复 AUTH_RESP，Pipeline 升级进入数据阶段

状态码（CONNACK）：OK(0) / AUTH_FAILED(1) / VERSION_UNSUPPORTED(2) / SERVER_MAINTENANCE(3) / DEVICE_BANNED(4) / TOO_MANY_CONNECTIONS(5)

安全机制：
- 慢攻击防御：握手必须在 30s 内完成，超时直接断开
- 空闲超时：服务端 60s 无数据断开，客户端 30s 发送 PING

#### 2. 数据阶段（Packet）

```
Packet = PacketType(1B) + Length(4B) + Payload(Length bytes)
```

- **PacketType 采用语义拍平编码**：每种类型直接对应一种业务语义，无 SEND/RECV 中间层
- **消息结构**：`Message = MessageHeader(9 共享字段) + MessageBody(类型特有内容)`
- **消息流**：客户端发送消息包（TEXT/IMAGE/...）→ 服务端 SENDACK + 投递 RECV 包给接收者 → 接收者 RECVACK
- **心跳**：客户端写空闲 30s 触发 PING，服务端读空闲 60s 断开
- **编码**：字符串使用 VarInt 长度前缀 + UTF-8（null 编码为 0xFF），整数使用 VarInt 可变长度编码，字节数组使用 4 字节长度前缀
- **IO 分发**：PING/PONG/DISCONNECT/RECVACK 在 Netty EventLoop 直接处理，其他重量操作 dispatch 到 IOExecutor

#### PacketType 编码范围

| 范围 | 类型 | 说明 |
|------|------|------|
| 1-5 | 连接控制 | AUTH(1), AUTH_RESP(2), DISCONNECT(3), PING(4), PONG(5) |
| 10-11 | 会话管理 | SUBSCRIBE(10), UNSUBSCRIBE(11) |
| 20-36 | 消息（双向统一） | TEXT(20), IMAGE(21), VOICE(22), VIDEO(23), FILE(24), LOCATION(25), CARD(26), REPLY(27), FORWARD(28), MERGE_FORWARD(29), REVOKE(30), EDIT(31), TYPING(32), STICKER(33), REACTION(34), INTERACTIVE(35), RICH(36) |
| 80-81 | 确认应答 | SENDACK(80), RECVACK(81) |
| 90-98 | 系统消息 (S→C) | CHANNEL_CREATED(90), CHANNEL_UPDATED(91), CHANNEL_DELETED(92), MEMBER_ADDED(93), MEMBER_REMOVED(94), MEMBER_MUTED(95), MEMBER_UNMUTED(96), MEMBER_ROLE_CHANGED(97), CHANNEL_ANNOUNCEMENT(98) |
| 100-102 | 命令与控制 (S→C) | CMD(100), ACK(101), PRESENCE(102) |

### 服务端架构

```
Application.kt (Ktor)
 ├── HikariCP → PostgreSQL (关系数据：用户/频道/好友/会话/设备/邀请链接)
 ├── RocksDB (消息存储，键格式: channelIdLength + channelId + seq)
 ├── Lucene (全文搜索索引，IK 中文分词)
 ├── Store 层 (内存缓存 + DB 回退，缓存用户/频道/成员/会话/设备/邀请链接数据)
 ├── Looper (单线程事件循环，ClientRegistry/trace 共用)
 ├── S3Client (MinIO 文件存储)
 ├── TcpServer (Netty, 端口 5100)
 │    └── Pipeline: IdleStateHandler → HandshakeHandler → Setup → PacketCodec → ImAgent
 │         ├── ImAgent — 包分发（EventLoop 轻量操作 vs IOExecutor 重量操作）
 │         ├── ClientRegistry — uid→多设备映射（Looper 线程序列化访问）
 │         ├── agent/AuthProcessor — 认证（纯 CPU，EventLoop 同步执行）
 │         ├── agent/MessageDispatcher — 消息处理（禁言检查 + IOExecutor 异步存储）
 │         ├── agent/SubscribeDispatcher — 订阅（IOExecutor 异步拉消息）
 │         ├── agent/TypingDispatcher — 输入状态（EventLoop 直接转发）
 │         └── trace/Recorder — 采样日志（独立 Looper 线程写入）
 ├── JWT 认证
 ├── ThreadIOGuard (EventLoop 线程保护，防止阻塞 IO)
 └── API 路由:
      ├── AuthRoutes    — /api/v1/auth/* + /api/v1/users/*
      ├── MessageRoutes — /api/v1/channels/{id}/messages + /api/v1/messages/search
      ├── ChannelRoutes — /api/v1/channels/*
      ├── ContactRoutes — /api/v1/contacts/* + /api/v1/blacklist/*
      ├── ConversationRoutes — /api/v1/conversations/*
      ├── DeviceRoutes  — /api/v1/devices/*
      └── FileRoutes    — /api/v1/files/*
```

### 数据库表

| 表名 | 说明 |
|------|------|
| Users | 用户（uid/username/name/phone/avatar/sex/shortNo/status/role） |
| Devices | 设备（uid/deviceId/deviceName/deviceModel/deviceFlag/lastLogin） |
| Tokens | 刷新令牌（uid/refreshToken/deviceFlag/expiresAt） |
| Channels | 频道（channelId/channelType/name/avatar/creator/notice/maxSeq/mutedAll） |
| ChannelMembers | 频道成员（channelId/uid/role/nickname/status） |
| ChannelMemberMutes | 成员禁言（channelId/uid/operatorUid/expiresAt） |
| Conversations | 会话（uid/channelId/unreadCount/readSeq/isMuted/isPinned/draft） |
| Friends | 好友关系（uid/friendUid/remark/status） |
| FriendApplies | 好友申请（fromUid/toUid/token/remark/status） |
| GroupInviteLinks | 群邀请链接（token/channelId/maxUses/useCount/expiresAt/revokedAt） |

### 消息存储格式

消息通过 `Message` 类的 `writeTo/writeFrom` 进行二进制序列化，存储格式为 `[headerLen(2)][header bytes][body bytes]`。MessageHeader 包含 9 个共享字段（channelId/clientMsgNo/clientSeq/messageId/senderUid/channelType/serverSeq/timestamp/flags），MessageBody 按不同 PacketType 有各自的字段结构。存储在 RocksDB 中，HTTP API 返回时解码为 JSON。

---

## 构建与运行

详细的开发环境搭建指南见 [doc/develop.md](doc/develop.md)。

### Gradle Profile 系统

所有环境差异（服务器地址、TCP 主机、部署配置等）通过 Profile 文件管理，位于 `gradle/profiles/` 目录：

```bash
gradle/profiles/
├── dev.properties           # 本地开发（默认）
├── demo.properties          # 官方演示站（im.virjar.com）
├── production.properties    # 生产模板（用户复制后修改）
└── *.secrets                # 敏感密码（自动生成，不入 Git）
```

```bash
# 默认 dev profile
./gradlew :desktop:run

# 指定 profile
./gradlew :desktop:run -PbuildProfile=demo

# 构建所有产物
./gradlew buildRelease -PbuildProfile=demo

# 构建 + 上传到服务器
./gradlew uploadRelease -PbuildProfile=demo

# 部署服务端（首次/升级自动检测）
./gradlew deployServer -PbuildProfile=demo

# 部署服务端 + SSL 证书
./gradlew deployServer -PbuildProfile=demo -PsslCert=cert.pem -PsslKey=key.pem

# 部署 + 上传客户端
./gradlew deployServer uploadRelease -PbuildProfile=demo

# 用户自定义生产环境
cp gradle/profiles/production.properties gradle/profiles/my-company.properties
# 编辑 my-company.properties 后：
./gradlew deployServer uploadRelease -PbuildProfile=my-company
```

版本号在 `build.gradle.kts` 的 `extra["packageVersion"]` 中管理。

### 快速启动

```bash
docker compose up -d                                    # PostgreSQL + MinIO
./gradlew :server:run                                   # 服务端 (8080/5100)
./gradlew :desktop:run                                  # Desktop 客户端（dev profile）
./gradlew :desktop:run -PbuildProfile=demo              # Desktop 客户端（demo profile）
./gradlew :desktop:compileKotlin                        # 仅编译检查（最快验证）
./gradlew :server:test                                  # 集成测试
```

### 关键路径

| 内容 | 路径 |
|------|------|
| Desktop 数据目录 | `~/.tk/app_default/`（可通过 `-PDATA_DIR` 自定义） |
| Desktop 会话文件 | `~/.tk/app_default/session.properties` |
| 服务端配置 | `server/src/main/resources/application.conf` |
| 服务端日志 | `~/.tk/logs/teamtalk.log`（开发模式） |
| 基础设施数据 | `~/.tk/pgdata`（PostgreSQL）、`~/.tk/miniodata`（MinIO） |

### 开发模式日志目录（排查问题必读）

开发模式下所有组件的日志统一写入数据目录下的 `logs/` 子目录，数据目录默认为 `~/.tk/app_default/`，可通过 `-Dteamtalk.data.dir=<path>` 自定义。

| 组件 | 日志文件 | 说明 |
|------|---------|------|
| Server | `$dataDir/logs/teamtalk.log` | 服务端全量日志（HTTP + TCP），同时输出到控制台 |
| Desktop | `$dataDir/logs/app.log` | 客户端日志（网络、UI、TCP 连接、消息收发） |

多实例场景：通过 `-Dteamtalk.data.dir` 为每个实例指定不同数据目录，日志自动隔离，互不串扰。

排查问题时的典型操作：

```bash
# 实时查看服务端日志
tail -f ~/.tk/logs/teamtalk.log

# 实时查看客户端日志
tail -f ~/.tk/app_default/logs/app.log

# 自定义数据目录启动 Desktop（日志写入对应目录）
./gradlew :desktop:run -PDATA_DIR=$HOME/.tk/user2
# 日志位于 ~/.tk/user2/logs/app.log
```

---

## 部署

详细的部署指南见 [doc/deploy.md](doc/deploy.md)。所有部署通过 Gradle Profile 系统完成。

```bash
# 首次部署（HTTP 模式）
./gradlew deployServer -PbuildProfile=demo

# 首次部署（HTTPS 模式，提供 SSL 证书）
./gradlew deployServer -PbuildProfile=demo -PsslCert=server.pem -PsslKey=server.key

# 升级（自动检测已有部署，备份 → 上传 → 重启）
./gradlew deployServer -PbuildProfile=demo

# 部署 + 上传客户端
./gradlew deployServer uploadRelease -PbuildProfile=demo
```

生产环境目录结构：

```
/opt/teamtalk/
├── bin/              # Server 可执行文件
├── data/             # 数据目录（rocksdb/ lucene-index/ logs/）
├── conf/             # 配置文件
│   ├── env.sh        # 环境变量（权限 600，仅含与默认值不同的项 + 敏感密码）
│   ├── application.conf
│   ├── logback.xml
│   └── ssl/          # SSL 证书（PKCS12，权限 600）
├── static/           # 首页 + 客户端下载文件
│   ├── index.html
│   └── downloads/
└── docker-compose.yml
```

---

## 开发工作流

详细的架构设计理念、技术决策记录和编码约定见 [doc/architecture.md](doc/architecture.md)。

### Git 分支工作流

项目已发布到 GitHub，所有代码变更必须遵循分支工作流，**严禁直接在 main 分支上提交**。

**流程：**

1. **创建分支** — 每次任务开始前，从最新的 main 创建工作分支。分支命名规则：
   - `feat/<功能描述>` — 新功能（如 `feat/group-mute`）
   - `fix/<问题描述>` — Bug 修复（如 `fix/message-order`）
   - `refactor/<描述>` — 重构（如 `refactor/message-store`）
   - `chore/<描述>` — 杂项（如 `chore/update-deps`）
2. **开发提交** — 所有开发过程中的提交在工作分支上进行，提交信息遵循 `类型: 描述` 格式
3. **合并前审查** — 合并到 main 前必须执行：
   - `git diff main...HEAD` 审查所有变更，确认没有不合理的文件变动（如误提交的配置文件、IDE 文件、日志文件等）
   - 确认变更范围与任务目标一致，没有夹带无关修改
4. **合并到 main** — 需经用户确认后方可执行合并操作

**注意事项：**
- 开发过程中可以随时提交，不要求每次提交都完美
- 合并前重点审查最终 diff，而非单个提交
- 如果发现不合理的变动，先在工作分支上修正，再合并

### 代码修改后必做

1. `./gradlew :desktop:compileKotlin` 编译通过（最快验证）
2. 如涉及服务端：`./gradlew :server:test` 集成测试通过
3. 功能完成后提交代码

### 代码风格

- Kotlin + 100% Jetpack Compose UI，无 DI 框架
- 本地数据库：Android 和 Desktop 均使用 SQLDelight（共享 `AppDatabase` expect/actual）
- `expect/actual` 模式处理平台差异
- ViewModel 使用 `StateFlow` 暴露状态，Repository 封装 API 调用
- 消息发送采用乐观更新（optimistic update）
- 状态管理使用 `AppState` 类 + `CompositionLocal` 向下传递

### TCP 模块编码规范

TCP 模块（`server/.../tcp/`）有独立的编码规范，涉及线程模型、异步范式（禁止协程、使用 callback + WeakReference）、GC 安全约束等。修改 TCP 相关代码前**必须**阅读：[`server/src/main/kotlin/com/virjar/tk/tcp/CLAUDE.md`](server/src/main/kotlin/com/virjar/tk/tcp/CLAUDE.md)

### 关键文件快速索引

| 需求 | 文件 |
|------|------|
| 添加新页面 | `app/.../navigation/AppNavigation.kt`（NavDestination 子类）+ `desktop/.../ui/screen/` 和 `android/.../ui/screen/` 各新建 Screen |
| 添加新消息类型 | `shared/.../protocol/PacketType.kt` + `shared/.../protocol/payload/ContentPayloads.kt` + `app/.../ui/component/chat/MessageContentDispatcher.kt` |
| 修改 API 接口 | `app/.../client/ApiClient.kt` + `shared/.../dto/` + 对应 Repository |
| 修改服务端 API | `server/.../api/` + `server/.../service/` + `server/.../store/` |
| 修改 TCP 协议 | `shared/.../protocol/` + `server/.../tcp/` + `app/.../client/TcpConnection.kt` + `app/.../client/ImClient.kt` |
| 修改主题 | `app/.../ui/theme/Theme.kt` |
| 添加平台特定功能 | `app/src/androidMain/` 或 `app/src/desktopMain/` 的 `expect` 实现 |
| 添加共享 UI 组件 | `app/.../ui/component/`（Avatar、MessageBubble 等基础组件） |
| 修改服务端缓存 | `server/.../store/`（内存缓存 + DB 回退） |
| 修改文件存储 | `server/.../s3/`（MinIO S3 客户端） |
| 修改产品首页 | `server/src/main/resources/static/index.html` |
| 修改部署流程 | `build.gradle.kts`（deployServer 任务） + `doc/deploy.md` |
| 修改构建 Profile | `gradle/profiles/*.properties` + `gradle/profiles/*.secrets` |
| 修改发布配置 | `build.gradle.kts`（根项目：Profile 加载 + deployServer/buildRelease/uploadRelease） |
