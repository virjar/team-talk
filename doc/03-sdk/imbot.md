# ImBot — 无头客户端

> SDK 的无 UI 入口：CLI / 服务器进程 / AI 机器人 / AI 员工的接入基础。
> 源码：`shared/.../bot/ImBot.kt` · 集成测试：`shared/src/commonTest/.../ImBotIntegrationTest.kt`

---

## 1. 定位

复用 SDK 完整闭环（ImClient 连接 + createSession 组装 + EventProcessor 契约消费），仅两处替换：
- **缓存**：`FakeLocalCache`（纯内存，零持久化）——服务器上跑 bot 不落盘
- **身份**：持有 UserSession + ClientSession，bot 即会话所有者（owner-driven）

## 2. 快速上手

```kotlin
// 注册即登录（用户名自动加随机后缀防冲突）
val bot = ImBot.register("im.virjar.com", 5100, "my-bot")

// 事件循环：收消息 → 处理 → 回复（AI 员工的对话骨架）
bot.messages.collect { msg ->
    if (msg.senderUid != bot.uid) {                      // 过滤自己（服务端回环）
        bot.sendText(msg.chatId, "echo: ${msg.body}")
    }
}

// 或调试式逐条取（带谓词，从启动即缓冲，不丢历史）
val next = bot.nextMessage { it.senderUid != bot.uid }

bot.shutdown()   // 级联：scope → channel → session.close → imClient.destroy
```

## 3. API

| 类别 | 方法 |
|------|------|
| 生命周期 | `register(host, port, prefix)` / `login(host, port, user, pass)` / `awaitState()` / `shutdown()` |
| 消息 | `messages: SharedFlow<Message>` / `sendText(chatId, text)` / `send(chatId, body)` / `nextMessage(timeout, predicate)` |
| 社交 | `createPersonalChat(uid)` / `listFriends()` / `acceptFriendApply(token)` / `pendingApplies()` |
| 会话 | `listConversations()` |
| 透传 | `session`（ClientSession，可直达全部 Repository） |

## 4. 三个内建设计（都踩过坑）

### ① Channel 缓冲（防丢消息）
`messageEvents` 是 SharedFlow **无 replay**——若 `nextMessage` 在消息到达后才订阅，之前全部错过。ImBot 构造即启动 collector 转入 `Channel(UNLIMITED)`，从 t=0 全量保留。

### ② 发送者回环过滤
服务端 MESSAGE_RECV 广播**全体成员含发送者**（UI 靠 LocalCache 幂等覆盖消化；bot 无此需求），必须谓词过滤：`nextMessage { it.senderUid != uid }`。

### ③ 双重认证等待
register/login 同时等 `onAuthResult 回调(deferred)` 和 `state.first { AUTHENTICATED }`——两条通路都确认后才组装 session（createSession 依赖 EventLoop scope + uid）。

## 5. 集成测试（SDK 闭环验收）

```bash
# 对 demo 服务器跑（默认跳过，属性开启）
./gradlew :shared:jvmTest -Dtk.botTest.host=im.virjar.com -Dtk.botTest.port=5100
```

三个用例构成 SDK 回归防线（真实服务器全链路）：
1. **注册即认证**：三级状态就位（uid 写入 UserSession + state=AUTHENTICATED）
2. **bot 对 bot 消息全链路**：建会话 → 发送(ACK code=0) → 对端契约解码接收 → 双向回复 → 会话列表同步
3. **已读回执链路**：markRead → READ_SYNC 推送 → 对端 peerReadSeq 推进（轮询 LocalCache 断言）

SDK 层任何回归（协议/时序/编解码）在此暴露——**这是"UI 集成不发现 SDK bug"承诺的验收测试**。

## 6. 展望（见 ROADMAP）

- CLI mainClass（`headless` 可执行入口：账号配置 + 消息桥接到 stdout/管道）
- AI 员工对话框架（消息 → LLM → 回复的循环抽象 + 限速/白名单）
- 多 bot 编排（AI 员工跨会话协作）
