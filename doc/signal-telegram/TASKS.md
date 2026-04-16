# TeamTalk 架构演进任务清单

> 最后更新: 2026-04-09（Phase 3 进行中：B3.4 已完成）
>
> 本文件管理 TeamTalk 架构演进的整体任务框架和完成状态。每个任务进入实施前，在 plan 模式中拆解为具体的代码变更步骤。

---

## 状态标记

- `[ ]` 未开始
- `[~]` 进行中
- `[x]` 已完成
- `[-]` 已取消/延期

---

## 前置知识

### 已完成的工作

| 工作 | 状态 | 说明 |
|------|------|------|
| 新二进制协议迁移 | `[x]` | PacketType(1B) + Length(4B) + Payload 替代旧 VLQ+JSON |
| Handshake 握手阶段 | `[x]` | Magic + Version + JWT 认证 |
| UnifiedTcpHandler | `[x]` | 统一 Handler 处理握手 + Packet 两阶段 |
| IProto 序列化框架 | `[x]` | VarInt/String/ByteArray/StringList 读写 |
| 30+ Payload 类型定义 | `[x]` | 内容/操作/确认/控制/订阅/系统 6 类消息 |
| Payload round-trip 测试 | `[x]` | 所有 Payload 类型序列化/反序列化测试 |
| SQLDelight 本地数据库 | `[x]` | 5 表 Schema + expect/actual 创建（B1.1） |
| Repository 双源模式 | `[x]` | LocalCache + 4 个 Repository 本地优先/网络优先策略（B1.2） |
| Lucene 全文搜索 | `[x]` | SearchIndex + PayloadTextExtractor + IK 中文分词 + 搜索 API（B1.3） |
| 消息搜索 UI | `[x]` | SearchViewModel + SearchMessagesScreen + 关键词高亮 + Desktop/Android 路由（B1.4） |

### 核心设计文档索引

| 设计领域 | 文档 |
|---------|------|
| 连接架构 | `doc/signal-telegram/design/06-connection-lifecycle.md` |
| 消息同步 | `doc/signal-telegram/design/07-message-sync-protocol.md` |
| 本地数据库 | `doc/signal-telegram/design/08-local-database.md` |
| 通知架构 | `doc/signal-telegram/design/09-notification-architecture.md` |
| 消息搜索 | `doc/signal-telegram/design/10-fulltext-search.md` |
| 文件传输 | `doc/signal-telegram/design/11-file-transfer.md` |
| 草稿与未读 | `doc/signal-telegram/design/12-draft-and-unread.md` |
| 错误码 | `doc/signal-telegram/design/13-error-codes.md` |
| 群组权限 | `doc/signal-telegram/design/14-group-permission-matrix.md` |
| 群邀请链接 | `doc/signal-telegram/design/15-group-invite-links.md` |
| 本地消息删除 | `doc/signal-telegram/design/16-message-deletion-local.md` |

---

## Phase 0：协议与基础设施对齐

> 目标：确保新协议在各层正确工作，建立后续功能所需的代码基础

### B0.1 连接层重构 — ImClient + TcpConnection 双层分离

- **状态**: `[x]`
- **设计文档**: `06-connection-lifecycle.md`
- **依赖**: 无（新协议已完成）
- **说明**: 当前 TcpClient 直接管理连接和业务逻辑，需要拆分为 TcpConnection（底层连接管理）和 ImClient（业务层，管理状态/重连/发送队列）
- **关键内容**:
  - TcpConnection：封装 Netty Channel，负责连接/断开/握手/读写
  - ImClient：持有 TcpConnection 引用，提供 reconnect() / subscribe() / send() 等业务 API
  - 指数退避重连：1s → 2s → 4s → 8s → 16s → 30s
  - 内存发送队列：最多重试 3 次
  - EventLoop 单线程调度所有状态变更

### B0.2 ConnectionManager 服务端增强 — 多设备 + 消息投递

- **状态**: `[x]`
- **设计文档**: `06-connection-lifecycle.md`
- **依赖**: 无
- **说明**: 当前 ConnectionManager 仅做 uid→连接的简单映射，需要支持多设备同时在线，以及消息向所有在线设备投递
- **关键内容**:
  - 支持同一 UID 多连接（Map<UID, List<Connection>>）
  - sendMessage 时向该 UID 所有在线设备推送 RECV
  - 连接上线时触发离线消息补拉（基于 lastSeq）
  - ConnectionInfo 记录 deviceId、deviceFlag、连接时间

### B0.3 消息同步协议 — SUBSCRIBE + 离线补拉

- **状态**: `[x]`
- **设计文档**: `07-message-sync-protocol.md`
- **依赖**: B0.1, B0.2
- **说明**: 实现基于 lastSeq 的消息同步机制，取代当前的全量拉取
- **关键内容**:
  - 客户端维护每个频道的 ChannelProgress（channelId, lastSeq）
  - SUBSCRIBE 包携带 lastSeq，服务端据此补拉离线消息
  - 间隙检测：客户端维护 expectedSeq，检测到不连续时自动补拉
  - 混合同步：TCP 推送（≤100 条）+ HTTP 降级（>100 条）

### B0.4 错误码体系统一

- **状态**: `[x]`
- **设计文档**: `13-error-codes.md`
- **依赖**: 无
- **说明**: 定义统一的错误码常量，替代当前零散的错误处理
- **关键内容**:
  - TCP 协议层错误码（SENDACK code, CONNACK code）
  - HTTP API 5 位模块码（1xxxx 认证、2xxxx 用户、3xxxx 联系人等）
  - 客户端错误码映射为用户友好提示

---

## Phase 1：基础加固

> 目标：让 TeamTalk 成为日常可用的 IM 工具

### B1.1 本地数据库 — SQLDelight 集成 + Schema 定义

- **状态**: `[x]`
- **提交**: `ed0acad`
- **设计文档**: `08-local-database.md`
- **依赖**: 无
- **说明**: 引入 SQLDelight 作为 KMP 兼容的本地数据库，定义 5 张核心表的 Schema
- **关键内容**:
  - 添加 SQLDelight 依赖（commonMain）
  - 定义 Schema：messages, conversations, contacts, channels, read_positions
  - expect/actual 创建数据库实例（Android SQLite + Desktop JDBC/SQLite）
  - 基础 CRUD 操作

### B1.2 本地数据库 — Repository 双源模式

- **状态**: `[x]`
- **提交**: `566f7df`
- **设计文档**: `08-local-database.md`
- **依赖**: B1.1, B0.3
- **说明**: 将现有 Repository 从纯网络模式改为"本地优先，网络同步"的双源模式
- **关键内容**:
  - ChatRepository：消息本地缓存，优先从本地读取
  - ConversationRepository：会话列表本地缓存
  - ContactRepository：联系人列表本地缓存
  - 乐观更新：发送消息先写入本地，服务端确认后更新状态
  - 增量同步策略

### B1.3 消息搜索 — 服务端 Lucene 集成

- **状态**: `[x]`
- **设计文档**: `10-fulltext-search.md`
- **依赖**: 无
- **说明**: 在服务端集成 Apache Lucene 嵌入式全文搜索
- **关键内容**:
  - 添加 Lucene 依赖
  - MessageService 写入消息时同步建索引
  - 搜索 API：`GET /api/v1/messages/search?q=xxx&channelId=xxx`
  - 权限过滤：只返回用户有权限的频道消息
  - 中文分词（IK Analyzer）

### B1.4 消息搜索 — 客户端搜索 UI

- **状态**: `[x]`
- **提交**: `b0775c1`
- **设计文档**: `10-fulltext-search.md`
- **依赖**: B1.3
- **说明**: 客户端消息搜索界面
- **关键内容**:
  - 聊天内搜索（输入关键词 → 显示匹配消息列表）
  - 全局搜索（跨所有聊天搜索）
  - 搜索结果高亮关键词
  - 点击搜索结果跳转到对应消息位置

### B1.5 Desktop 系统通知

- **状态**: `[x]`
- **提交**: `72adb70`
- **设计文档**: `09-notification-architecture.md`
- **依赖**: B0.1（需要 ImClient 消息回调）
- **说明**: Desktop 端系统托盘 + 新消息通知
- **关键内容**:
  - 系统托盘图标（在线/离线状态 + 未读 badge）
  - 新消息弹窗通知（发送者 + 消息摘要）
  - 点击通知跳转到对应聊天
  - 通知开关设置
  - 免打扰逻辑（静音会话不弹通知，@ 例外）
  - 前台检测（正在查看的聊天不弹通知）

### B1.6 草稿与未读管理

- **状态**: `[x]`
- **提交**: `10b2fc3`
- **设计文档**: `12-draft-and-unread.md`
- **依赖**: B1.1
- **说明**: 草稿持久化和未读计数管理
- **关键内容**:
  - 草稿保存：防抖 500ms 写入本地数据库
  - 未读计数公式：MAX(0, channel_last_seq - read_seq)
  - 已读回执同步：POST /api/v1/channels/{channelId}/read
  - 会话列表显示未读 badge

---

## Phase 2：功能完善

> 目标：覆盖 IM 核心功能，满足日常办公需求

### B2.1 多设备在线完善

- **状态**: `[x]`
- **提交**: `870320e`
- **设计文档**: `06-connection-lifecycle.md`
- **依赖**: B0.2
- **说明**: 完善多设备在线体验
- **关键内容**:
  - 客户端：设备管理页面（查看在线设备、远程注销）
  - 多设备已读同步：CMD(read_sync)
  - 多设备消息状态同步

### B2.2 在线状态 & 输入状态

- **状态**: `[x]`
- **设计文档**: `01d-control-messages.md`（PRESENCE/TYPING）
- **依赖**: B0.1, B0.2
- **说明**: 用户在线状态显示和"正在输入"提示
- **关键内容**:
  - 服务端：PresenceService 上线/下线广播 PRESENCE 包给好友
  - 服务端：TYPING 独立处理（不存 DB，直接转发频道其他成员）
  - 客户端：ImClient 处理 PRESENCE/TYPING 包，UserContext 管理在线状态 StateFlow
  - 客户端：ConversationViewModel 订阅实时在线状态更新
  - 客户端：ChatViewModel 管理 typing 状态（5s 超时自动清除）
  - 客户端：ChatScreen TopAppBar 显示"正在输入..."提示
  - 客户端：输入时发送 TYPING 包（3s 防抖）

### B2.3 消息编辑

- **状态**: `[x]`
- **设计文档**: `01b-action-messages.md`（EDIT）
- **依赖**: B1.2（本地数据库）
- **说明**: 支持编辑已发送的文本消息，编辑后所有接收者看到更新后的内容并显示"edited"标记
- **关键内容**:
  - 服务端：MessageStore.updateMessagePayload() 更新 RocksDB 消息内容
  - 服务端：MessageService.editMessage() 验证发送者+消息类型，嵌入 edited_at 时间戳
  - 服务端：`PUT /api/v1/channels/{id}/messages/{seq}/edit` HTTP API
  - 服务端：MessageDeliveryService.deliverEdit() 构造 EDIT TCP 包广播给频道成员
  - 客户端：ImClient EDIT 独立处理，UserContext 多播 editListeners
  - 客户端：ChatViewModel 编辑状态管理（editingMessage + setEditingMessage + editMessage）
  - 客户端：MessageBubble 长按菜单增加 Edit 选项（仅自己的文本消息）
  - 客户端：ChatInputBar 编辑提示条（"Edit message" + 原文预览 + 取消按钮）
  - 客户端：消息内容下方显示"edited"标记（payload 包含 edited_at 时）

### B2.4 文件传输优化

- **状态**: `[x]`
- **设计文档**: `11-file-transfer.md`
- **依赖**: 无
- **说明**: 改进文件传输体验
- **关键内容**:
  - 服务端：HTTP 流式代理（隐藏 MinIO 地址，Cache-Control 86400s）
  - 服务端：文件大小限制（50MB，可配置）+ MIME 白名单校验
  - 服务端：图片缩略图自动生成（200x200 居中裁剪 JPEG）
  - 客户端：上传进度回调（Ktor onUpload，ChatState.uploadProgress）
  - 客户端：下载去重（内存缓存 Map）
  - 客户端：图片缓存（ImageCache，内存 LRU 100 条 + 磁盘 SHA256）
  - 客户端：缩略图优先加载（thumbnailUrl 非空时先加载缩略图再加载原图）

### B2.5 语音消息

- **状态**: `[x]`
- **设计文档**: `01a-content-messages.md`（VOICE）
- **依赖**: B2.4（文件上传）
- **说明**: 实现语音消息录制和播放
- **关键内容**:
  - expect/actual VoiceRecorder：Desktop（Java Sound PCM→WAV）+ Android（MediaRecorder AAC）
  - expect/actual VoicePlayer：Desktop（javax.sound.sampled SourceDataLine）+ Android（MediaPlayer）
  - 录音面板 UI（RecordingPanel，闪烁红点 + 时长 + 进度条 + 取消/发送）
  - ChatInputBar 麦克风按钮（空输入时显示 Mic 图标，点击启动录音）
  - ChatViewModel 录音状态管理（isRecording/recordingDuration/recordingAmplitude，60s 自动停止）
  - VoiceMessageContent 播放交互（播放/暂停图标 + 进度条 + 波形条 + 实时位置）
  - MessageBubble/MessageContentDispatcher 透传 voicePlayer
  - ChatRepository.sendVoiceMessage() + 乐观更新发送
  - Android RECORD_AUDIO 权限声明

### B2.6 本地消息删除

- **状态**: `[x]`
- **设计文档**: `16-message-deletion-local.md`
- **依赖**: B1.1（本地数据库）
- **说明**: 纯客户端的"仅删除我本地"功能
- **关键内容**:
  - 消息表增加 is_local_deleted 字段
  - 长按菜单增加"删除（仅本地）"
  - 增量同步时不覆盖本地删除标记
  - 引用已删除消息后的显示处理

---

## Phase 3：体验优化

> 目标：打磨体验，追求品质

### B3.1 群组权限矩阵

- **状态**: `[x]`
- **设计文档**: `14-group-permission-matrix.md`
- **依赖**: 无
- **说明**: 三级角色（OWNER/ADMIN/MEMBER）+ 权限矩阵
- **关键内容**:
  - 角色存储：channel_members.role 改为整数编码
  - 权限检查统一封装：requireRole(channelId, uid, minRole)
  - 单人禁言 + 全员禁言
  - 系统消息通知角色变更

### B3.2 群邀请链接

- **状态**: `[x]`
- **设计文档**: `15-group-invite-links.md`
- **依赖**: B3.1（权限检查）
- **说明**: 生成群邀请链接，支持过期/次数限制（不做审批流程）
- **关键内容**:
  - 服务端：group_invite_links 表 + InviteLinkDao + 5 个 API 路由
  - Token 生成（8 字符 Base62），每群上限 5 条有效链接
  - 客户端：InviteLinksScreen（创建/列出/撤销链接 + 二维码分享）
  - 加入时广播 MEMBER_ADDED 系统消息
  - 错误码：NOT_FOUND/EXPIRED/FULL/LIMIT/ALREADY_MEMBER

### B3.3 消息搜索增强

- **状态**: `[x]`
- **设计文档**: `10-fulltext-search.md`
- **依赖**: B1.3, B1.4
- **说明**: 增强搜索体验
- **关键内容**:
  - 聊天内搜索：上下文导航，跳转到搜索结果位置
  - 按日期/发送者筛选
  - 搜索结果上下文预览

### B3.4 性能优化

- **状态**: `[x]`
- **设计文档**: `03-client-architecture.md`
- **依赖**: B1.2（本地数据库）
- **说明**: 性能优化
- **关键内容**:
  - 消息列表分页加载（LazyColumn + 本地数据库分页）— 已实现 before_seq API + 本地 DB 分页
  - 图片懒加载 + 三级缓存（内存 → 磁盘 → 网络）— 已添加磁盘缓存 200MB 容量管理
  - 大文件断点续传 — 延期（复杂度高，中小组织场景价值有限）

### B3.5 TLS 传输加密

- **状态**: `[-]`
- **设计文档**: `04-security.md`
- **依赖**: 无
- **说明**: TCP 连接 TLS 加密
- **关键内容**:
  - 服务端：Netty SslHandler
  - 客户端：Netty SslHandler
  - 证书管理（自签名 / Let's Encrypt）

### B3.6 Android 推送通知

- **状态**: `[-]`
- **设计文档**: `09-notification-architecture.md`
- **依赖**: B1.5（通知架构基础）
- **说明**: Android 端推送通知
- **关键内容**:
  - FCM 集成
  - NotificationChannel 配置
  - 离线消息推送唤醒

---

## 任务依赖关系

```
B0.1 ImClient/TcpConnection ──→ B0.3 消息同步协议
     │                              │
     ↓                              ↓
B0.2 ConnectionManager增强 ──→ B1.2 Repository双源
                                    │
B0.4 错误码体系                    ↓
                                B1.6 草稿与未读
B1.1 SQLDelight Schema ────→ B1.2 ──→ B2.6 本地删除
     │                          │
     ↓                          ↓
B1.3 Lucene搜索 ──→ B1.4 搜索UI  B2.3 消息编辑
                         │
                         ↓
B0.1 ──→ B1.5 Desktop通知    B2.4 文件传输优化 ──→ B2.5 语音消息

B0.2 ──→ B2.1 多设备 ──→ B2.2 在线状态

B1.2 ──→ B3.4 性能优化
B1.3 ──→ B3.3 搜索增强
B3.1 ──→ B3.2 群邀请链接
```

---

## 实施策略

1. **每个 Batch 进入实施前**，进入 plan 模式详细拆解为代码变更步骤
2. **每个 Batch 完成后**，更新本文件状态并提交代码
3. **优先级排序**：Phase 0 → Phase 1 → Phase 2 → Phase 3
4. **Phase 内部顺序**：按编号顺序执行，注意依赖关系
5. **可以并行**：无依赖关系的 Batch 可以并行开发（如 B0.4 和 B1.1）
6. **每个 Batch 完成标准**：编译通过 + 测试通过 + 代码提交
