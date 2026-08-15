# 踩坑经验集

> 每一条都是真实付过学费的。新成员**必读**；改相关代码前先查此表。
> 好消息：绝大多数坑的修复已经固化成防御代码/契约测试——此表帮你理解"为什么那里有一段看起来多余的代码"。

---

## A. 协议与契约（最痛的一类：症状在 UI，根因在协议）

| # | 坑 | 症状 | 根因 | 固化 |
|---|----|----|------|------|
| A1 | NOTIFY payload 类型两端漂移 | 客户端数据错乱/解析异常，UI 表现诡异 | emit 侧与 decode 侧各自手写 when，无编译期约束 | [NotifyContracts 契约表](../01-protocol/notify-contracts.md)：唯一事实源 + 服务端 emit 校验 + 完备性测试 |
| A2 | CONTACT_ACCEPTED/DELETED 视角反转 | 接收方好友列表 friendUid 错位 | 服务端把申请方视角的同一个 Contact 发给双方 | 每接收者构造各自视角（uid=自己, friendUid=对方） |
| A3 | 编解码字段错位 | IndexOutOfBounds → 断连 | encodePayload/withPayload 字段序不配对 | **根治（RPC IDL 化）**：手写 encodePayload/withPayload 已全部移除，双端编解码由 KSP 从 @RpcService interface 生成（Contract 单点定义+round-trip 自检）；NOTIFY 侧由契约表防线覆盖 |
| A4 | ByteArray 的 data class equals | 内容相同的 GenericPayload 判不等 | 默认 equals 比引用 | GenericPayload 重写内容 equals（所有含 ByteArray 的模型注意） |
| A5 | varint 负数截断 | 解码错乱 | LEB128 无符号约定，写负数截断成 1 字节 | 契约：varint 只传非负；有符号语义字段用位标记/拆字段 |

## B. 认证与连接（时序竞态重灾区）

| # | 坑 | 症状 | 根因 | 固化 |
|---|----|----|------|------|
| B1 | 认证包竞态 | 连上了但永远停在 CONNECTED | 协程线程赋 pendingAuth 晚于 EventLoop 握手回调 | connectAndAuth 单 EventLoop 任务"先设后拨" |
| B2 | 重连掉登录 | 断线重连后 AUTH_FAILED | refresh token 一次一换，pendingAuth 存旧 token | 认证成功即 `pendingAuth.copy(refreshToken=新)` |
| B3 | 重连定时器竞争 | 双连接/状态错乱 | channelInactive 排的重连定时器与用户新 connect 竞争 | 一切 connect 入口先 cancel reconnectFuture |
| B4 | 重连窗口业务包 401 | 发消息莫名失败 | 重连后未认证窗口期业务包先到服务端 | send() 认证门禁（Auth/Ping/Pong 豁免） |
| B5 | 断连清 uid | 消息左右颠倒 | 断开时误清用户层状态 | 三级状态隔离：cleanupOnDisconnect 只清 pendingAcks |
| B6 | 登出后 stale 全局引用 | 假 fault 上调已停 uploader | AppLog 全局 buffer/onFault 未随会话重置 | ClientSession.close() 置空三引用 |
| B7 | 登出签发新凭证（安全） | 登出反而留下有效 token | logout 误用 refreshAccessToken（删旧+发新） | logout 走 revokeRefreshToken（只删不发） |
| B8 | 客户端认证失效卡死 | UI 不回登录页 | ViewModel 自调 disconnect()（不产生 AUTH_FAILED 信号） | BaseViewModel.onAuthExpired 上抛 → session.close() 统一处理 |
| B9 | 自动登录 UI 卡未认证态 | 无法操作 | token 失效但不清除 | AuthController：AUTH_FAILED → tokenStore.clear() + 回登录页 |
| B10 | 重连重放注册包 | 掉线后 AUTH FAILED"用户名已存在"永久掉线 | pendingAuth 原样重放 register/login | 认证成功即升级 pendingAuth 为 refresh-token（authType=2，清用户名密码） |
| B11 | 重连后监听断链 | 断网恢复后 RPC 全超时/NOTIFY 全丢（app 假活） | 监听协程挂连接 scope，断线随 scope 消亡且无人重启 | RpcClient/EventProcessor 自治 watcher：state==CONNECTED 且监听死 → 新 scope 重启 |
| B12 | 离线补发游标快照过期 | 认证后增长的事件漏补发 | pendingAuth.lastEventId 是认证成功瞬间快照 | 握手重发认证包时现取 provider 最新游标 |

## C. 并发与状态（"线程安全"的错觉）

| # | 坑 | 症状 | 根因 | 固化 |
|---|----|----|------|------|
| C1 | StateFlow 读改写丢更新 | 通知与 UI 并发写时数据丢失 | `flow.value = flow.value.filter{}` 非原子；误以为 StateFlow 线程安全就够 | stateLock：所有复合更新持锁（[local-cache §2](../03-sdk/local-cache.md#2-statelock--stateflow-读改写纪律)） |
| C2 | SharedFlow 无 replay 丢消息 | bot 晚订阅错过全部历史消息 | SharedFlow emit 只达已订阅者 | ImBot 构造即收集入 UNLIMITED Channel |
| C3 | 邀请链接超发 | useCount 超 maxUses | 读-改-写自增非原子 | SQL `UPDATE SET use_count=use_count+1` |
| C4 | EventLoop 被阻塞 | 心跳超时/全线卡顿 | DB/IO 在 EventLoop 执行 | EventProcessor 切 Dispatchers.IO；服务端重活切 IOExecutor |
| C5 | 协程泄漏 | 会话重建后旧监听仍活 | start() 不幂等 / VM 重建不销毁 | start 先 cancel 旧 job；prepareChat 先 destroy 旧 VM |
| C6 | Netty channel GC 泄漏 | 直接内存涨 | 协程强持 ImAgent | 服务端 ImAgentFacade WeakReference + AgentDisposedException |

## D. 数据一致性（多设备/离线）

| # | 坑 | 症状 | 根因 | 固化 |
|---|----|----|------|------|
| D1 | 换设备全未读 | 新登录所有会话红点 | markRead 在会话行不存在时静默 no-op，readSeq 丢失 | markRead 行不存在则 INSERT；readSeq 只增不减取 max |
| D2 | 空聊天已读丢失 | 建群即读，换设备仍未读 | 会话行只在首条消息时创建 | 建群/加人/邀请时 ensureConversations 预创建 |
| D3 | ✓✓ 回执换设备消失 | 已读回执重置 | peerReadSeq 不持久化 | conversations.peer_read_seq 列 + markRead 级联更新其他成员 |
| D4 | 红点复活 | 已读后旧通知到达又亮 | 客户端盲信 remote 覆盖本地水位线 | mergeConversation：readSeq/peerReadSeq 取 max；unreadCount 服务端权威 |
| D5 | 好友红点接受后不消失 | 红点计数滞留 | accept action 未刷新 pendingApplyCount | acceptFriendApply/rejectFriendApply 成功后刷新 |
| D6 | 被拉群不显示会话 | 群聊缺席 | 本地无 Conversation 行且无触发 | CHAT_CREATED → onConversationsDirty 重拉会话列表 |
| D7 | 自己发消息红点不消 | 红点滞留 | 自己的操作不触发给自己的通知 | 本地即时 markConversationRead / 发送成功推进 readSeq |
| D8 | 事件毒丸死循环 | 一条坏事件反复重试 | 游标推进策略缺失 | 成功才推进 + 消息 seq 兜底 + 事件 7 天 TTL |
| D9 | 消息重复显示 | 自己发的消息出现两条 | 服务端 MESSAGE_RECV 含发送者回环 | MessageWindow 按 clientMsgId 幂等覆盖 |

## E. 服务端

| # | 坑 | 囑状 | 根因 | 固化 |
|---|----|----|------|------|
| E1 | N+1 查询 | 好友列表卡 | listFriends 逐个查 user | 批量/join（改造中，见 ROADMAP） |
| E2 | maxSeq 回退重启复用 | seq 冲突 | 内存自增异步落库，崩溃丢增量 | updateMaxSeq 带 `max_seq < seq` 保护；重启从 DB 加载 |
| E3 | HTTP 阻塞 EventLoop | 全站卡死 | receiveStream 阻塞读 | call.receive<ByteArray>() |
| E4 | 中文错误串跨端 | 无法程序化处理 | 错误只有 message 无 code | 错误分层码（400/401/500/504）；i18n 列入 ROADMAP |

## F. UI / E2E 测试（工具链坑）

| # | 坑 | 对策 |
|---|----|------|
| F1 | Compose 文本节点不可点（uiautomator2） | 点击父级 clickable 容器（tools/e2e 已封装） |
| F2 | MIUI 安全键盘拦截输入 | 安全键盘关闭 + fastinput IME（剪贴板通道），二者缺一不可 |
| F3 | Desktop 图标按钮无 text | 语义树不含 contentDescription → 坐标/bounds 中心点击 |
| F4 | Desktop 多窗口语义树错位 | 子窗口操作必须带 `window=EnumName`；验证 ESC 关闭后语义树清空 |
| F5 | SetText 返回 200 但未生效 | 必须 readBack editableText 验证（已知 Compose Desktop 问题） |
| F6 | 固定脚本测试必败 | 每步 dump 状态再决策；异步 recomposition 加等待重试 |
| F7 | 主线程 runBlocking | 存草稿等操作改 fire-and-forget scope |
| F9 | 握手层三字节交换从未校验版本且是认证竞态温床 | v3 删除：首帧 AUTH 即序言（帧头 magic+version 首帧校验），FFAC6B1 竞态类别根除 |
| F8 | "文档与代码矛盾"错误裁决为删代码 | 2026-08 误删 GENERIC(99)：它是协议演进策略（01-protocol §9）的 wire 级预留入口，空枚举是刻意候选区；删除破坏前向兼容（fromCode 抛异常→游标卡死）。遇到矛盾先判断哪边是设计意图——wire 协议的空位预留 ≠ 应用层过早实现 |

---

## 修复索引（坑 → 代码位置）

- 契约表：`shared/.../protocol/NotifyContracts.kt` + `NotifyContractTest`
- ImClient 防御 9 式：`shared/.../client/ImClient.kt`（每处带注释）+ [imclient.md §12](../03-sdk/imclient.md#12-防御设计--历史-bug-对照)
- stateLock：`shared/.../client/LocalCacheImpl.kt` 头部注释
- 会话级联：`shared/.../client/ClientSession.kt`
- readSeq 体系：`server/.../conversation/ConversationService.kt` + [database.md conversations 表](../02-server/database.md#conversations--每用户每会话的收件箱状态多设备同步核心)
