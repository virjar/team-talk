# 客户端架构对比分析

> Signal Client vs Telegram Client vs TeamTalk Client

---

## 1. 架构模式对比

| 维度 | Signal | Telegram | TeamTalk |
|------|--------|----------|----------|
| **Android** | Kotlin + Compose（部分传统 View） | Java/Kotlin（自定义 MVC） | Kotlin + Compose |
| **Desktop** | TypeScript + React（Electron） | C++/Qt | Kotlin + Compose（JVM） |
| **iOS** | Swift/ObjC | Swift/ObjC | 未覆盖 |
| **代码共享** | libsignal（Rust 跨平台） | C++ 核心共享 | KMP commonMain |
| **状态管理** | Redux（Desktop）/ MVVM（Android） | 自定义 MVC | ViewModel + StateFlow |
| **本地数据库** | SQLCipher（30+ 表） | 自定义 SQLite C API 封装 | **无** |
| **离线支持** | 完全离线可用 | 完全离线可用 | **不支持** |
| **网络层** | WebSocket + OkHttp | MTProto ConnectionManager | OkHttp + 自定义 TCP |
| **消息同步** | 乐观更新 + 冲突解决 | 增量消息同步（服务端驱动） | **全量拉取** |
| **离线处理** | 本地队列 + 草稿 | 本地队列 + 定时发送 | 内存队列 + 草稿 |
| **渲染** | LazyColumn / react-virtualized | RecyclerView + ViewHolder | LazyColumn |
| **图片缓存** | 三级缓存（内存 + 磁盘 + 网络） | 三级缓存 + 动态压缩 | **网络下载** |
| **文件下载** | 离线线程池 + 优先级队列 | FileLoader（优先级 + 分片） | 简单下载 |
| **推送** | FCM + APNs | 自建推送系统 | **无** |

---

## 2. Signal 客户端架构详解

### 2.1 Android 端（Kotlin）

**模块划分**：
```
Signal-Android/
├── app/           # 主应用（UI + ViewModel）
├── libsignal/     # 加密核心（Rust FFI）
├── core-util/     # 通用工具
├── database/      # SQLCipher 数据库层
└── dependencies/  # 依赖管理
```

**关键设计**：
- **数据库层**：30+ 张表，覆盖消息、会话、联系人、群组、密钥等所有数据
- **Repository 模式**：数据库访问封装在 Repository 中，ViewModel 不直接操作数据库
- **MediatorLiveData**：观察数据库变化，自动更新 UI
- **消息同步**：`StorageSyncSyncHelper` 负责上传/下载远程更改，增量同步解决冲突

### 2.2 Desktop 端（TypeScript + React）

**状态管理（Redux）**：
```
Store
├── conversations: ConversationType[]    # 会话列表
├── messages: { [conversationId]: MessageType[] }  # 消息缓存
├── user: UserType                       # 当前用户
├── search: SearchStateType              # 搜索状态
└── network: NetworkStateType            # 网络状态
```

**关键设计**：
- **react-virtualized**：高效渲染大量消息列表
- **IndexedDB**：本地数据持久化
- **libsignal-client**（Rust WASM）：加密操作
- **WebSocket**：实时消息推送

---

## 3. Telegram 客户端架构详解

### 3.1 核心架构

**模块划分**：
```
Telegram/
├── TMessagesProj/         # Android 主项目
│   ├── src/main/java/
│   │   ├── MessagesController/    # 消息逻辑控制中心
│   │   ├── ConnectionsManager/    # MTProto 连接管理
│   │   ├── FileLoader/           # 文件下载管理
│   │   ├── FileUploadOperation/  # 文件上传管理
│   │   ├── NotificationCenter/   # 事件总线
│   │   ├── MessageObject/        # 消息数据模型
│   │   └── ImageLoader/          # 图片加载（三级缓存）
│   └── src/main/cpp/
│       ├── sqlite3.c             # 内嵌 SQLite
│       └── NativeLoader          # JNI 层
├── TMessagesProj_App/      # 应用层
├── TMessagesProj_Camera/   # 相机模块
└── TMessagesProj_Sticker/  # 贴纸模块
```

### 3.2 数据库设计

Telegram 使用自定义的 SQLite C API 封装，直接内嵌 `sqlite3.c` 源码：

- **消息表**：按聊天分表存储，支持快速查询
- **用户表**：缓存所有联系人信息
- **文件表**：记录已下载文件的本地路径和状态
- **加密聊天表**：独立的 Secret Chat 数据存储

### 3.3 网络层

```
ConnectionsManager（单例）
├── 管理多个 MTProto 连接
├── 数据中心（DC）切换
├── 请求队列 + 优先级
├── 自动重连 + 超时重试
├── 消息去重（msg_id）
└── 网络状态监听
```

### 3.4 文件管理

```
FileLoader
├── 三级缓存策略
│   ├── 内存缓存（Bitmap）
│   ├── 磁盘缓存（文件）
│   └── 网络下载
├── 优先级队列（UI 可见 > 后台预加载）
├── 分片下载 + 断点续传
├── 缩略图自动生成
└── 并发控制（4-8 个线程）
```

---

## 4. TeamTalk 客户端当前架构

### 4.1 模块划分

```
app/src/commonMain/        # 跨平台共享代码
├── client/
│   ├── ApiClient.kt       # HTTP API 客户端
│   └── TcpClient.kt       # TCP 长连接客户端
├── repository/            # 6 个 Repository（纯网络，无本地数据库）
├── viewmodel/             # 3 个 ViewModel（Chat / Conversation / Contacts）
├── navigation/            # NavDestination + MainTab
├── ui/component/          # UI 基础组件
├── ui/theme/              # Material 3 主题
└── util/                  # 工具类
```

### 4.2 数据流

```
Screen (Compose)
  └── ViewModel (StateFlow)
        └── Repository
              ├── ApiClient（HTTP REST API）
              └── TcpClient（自定义二进制帧协议）
```

### 4.3 当前限制

| 限制 | 说明 |
|------|------|
| 无本地数据库 | 每次打开聊天页都从服务器拉取全量消息 |
| 无离线能力 | 无网络时应用不可用 |
| 无消息缓存 | 切换聊天页后之前加载的消息丢失 |
| 无推送通知 | 后台收不到任何消息提醒 |
| 全量拉取 | 消息列表没有增量同步机制 |
| 图片无缓存 | 每次都重新从服务器下载图片 |

---

## 5. 关键差距分析

### 5.1 本地数据库（最高优先级）

**Signal 做法**：
- SQLCipher 加密数据库，30+ 张表
- Repository 模式封装数据访问
- 本地优先，网络同步在后台进行
- 完全支持离线使用

**Telegram 做法**：
- 内嵌 SQLite C API，极致性能
- 按聊天分表存储消息
- FileLoader 管理所有文件缓存
- 三级缓存策略

**TeamTalk 差距**：
- 没有任何本地持久化（除 token）
- 每次操作都是网络请求
- 用户感知到明显的延迟

**建议方案**：
- 引入 SQLDelight（KMP 兼容）
- 核心表：messages、conversations、contacts、channels、drafts
- Repository 层改为双源策略（本地优先，网络同步）

### 5.2 消息同步（高优先级）

**Signal 做法**：
- 乐观更新：发送消息立即写入本地数据库，UI 立即更新
- 冲突解决：服务端返回确认后校对本地数据
- `StorageSyncSyncHelper` 管理增量同步

**Telegram 做法**：
- 服务端驱动的增量同步
- 每个 DC 独立的消息序列号
- 差量消息拉取（基于 gap 检测）

**TeamTalk 差距**：
- 无增量同步，每次全量拉取
- 无离线消息补拉机制

**建议方案**：
- 基于现有 `afterSeq` 实现增量拉取
- 客户端记录最后消费的 seq 号
- 上线后自动补拉离线期间的消息

### 5.3 图片缓存（中优先级）

**Signal / Telegram 共同做法**：
- 三级缓存：内存 → 磁盘 → 网络
- 缩略图预加载，原图按需加载
- LRU 淘汰策略

**TeamTalk 差距**：
- 无缓存，每次都从网络加载
- 无缩略图机制，加载原图浪费带宽

**建议方案**：
- 短期：内存缓存（HashMap + LRU）
- 中期：磁盘缓存 + 缩略图（服务端生成）

### 5.4 文件管理（中优先级）

**Signal 做法**：
- 临时上传凭证直传对象存储（S3/GCS）
- 离线上传队列
- 进度反馈 + 断点续传

**Telegram 做法**：
- FileUploadOperation：分片上传 + 断点续传
- FileLoader：优先级队列 + 并发控制
- 文件预加载（根据使用模式预测）

**TeamTalk 现状**：
- MinIO 直传基本可用
- 缺少进度反馈和断点续传
- 缺少文件管理器

**建议方案**：
- 短期：上传进度条 + 大小限制 + MIME 校验
- 中期：分片上传 + 断点续传 + 文件管理器

---

## 6. 不建议采用的设计

| 设计 | 来源 | 不采用原因 |
|------|------|-----------|
| 自定义 SQLite C 封装 | Telegram | KMP 项目使用 SQLDelight 即可 |
| Redux 状态管理 | Signal Desktop | Compose 已有 StateFlow，无需 Redux |
| libsignal Rust FFI | Signal | 引入 Rust 工具链增加构建复杂度 |
| MTProto 连接管理 | Telegram | 协议过重，与 Kotlin 生态不契合 |
| react-virtualized | Signal Desktop | Compose LazyColumn 已够用 |
| 多数据中心（DC） | Telegram | 中小型组织不需要 |

---

## 7. 推荐的客户端演进路径

### Phase 1：基础加固

| 项目 | 方案 |
|------|------|
| 本地数据库 | SQLDelight（KMP 兼容），核心 5 张表 |
| 消息本地缓存 | 拉取后写入本地，优先从本地读取 |
| 会话列表缓存 | 本地缓存 + 增量同步 |
| Desktop 通知 | JVM SystemTray + TrayIcon |

### Phase 2：功能完善

| 项目 | 方案 |
|------|------|
| 增量消息同步 | 基于 afterSeq 补拉离线消息 |
| 图片内存缓存 | LRU 缓存 + 缩略图 |
| 文件管理 | 上传进度 + 断点续传 |
| 语音消息 | 平台原生录音 API |

### Phase 3：体验优化

| 项目 | 方案 |
|------|------|
| 图片三级缓存 | 内存 + 磁盘 + 网络 |
| 文件管理器 | 统一管理下载文件 |
| 消息搜索 | 客户端 SQLite FTS + 服务端 API |
| Android 推送 | FCM 集成 |
