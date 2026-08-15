# IM SDK（shared 模块）

> SDK 完整闭环：连接 → 认证 → RPC → 事件同步 → 本地缓存 → 无头入口。零 UI 依赖，纯 JVM 可测。
> 分册：[imclient.md](imclient.md)（连接层）· [local-cache.md](local-cache.md)（数据层）· [imbot.md](imbot.md)（无头）
> UI 消费模式见本页末尾。

---

## 1. 组成

```
shared/
├── protocol/     帧编解码 + PacketBuffer + NotifyContracts + 全部枚举   [wire-format](../01-protocol/wire-format.md)
├── model/ body/  传输模型（IProto）+ 15 种消息体
├── client/
│   ├── ImClient        TCP 连接层（状态机/重连/心跳/ACK）      [imclient.md](imclient.md)
│   ├── RpcClient       INVOKE/RESPONSE（requestId→Deferred）
│   ├── EventProcessor  NOTIFY 消费（契约解码→LocalCache→游标）
│   ├── LocalCache(Impl) 本地 SQLite（StateFlow + 消息窗口 LRU） [local-cache.md](local-cache.md)
│   ├── UserSession     用户层状态（uid/token，独立于 TCP）
│   ├── ClientSession   会话容器 + createSession 组装 + close 级联
│   ├── MessageSender   fun interface（隔离消息直发这一个非 RPC 操作）
│   ├── ServerConfig/TokenStore/Platform  平台抽象
│   └── CrashDumper/HttpLogUploader      崩溃与日志上传          [日志](../06-logging/README.md)
├── repository/   7 个 Repository（RPC + 本地缓存写入）          [rpc-methods §10](../01-protocol/rpc-methods.md)
├── bot/          ImBot 无头客户端                                [imbot.md](imbot.md)
├── testing/      FakeLocalCache / FakeRpcInvoker（公开测试工具）
└── util/         AppLog / LogBuffer / HttpUtil
```

## 2. 会话组装（createSession）与级联销毁

```kotlin
// 认证成功后调用一次（需要 uid + EventLoop scope 就绪）
createSession(imClient, userSession, createCache /*平台DB工厂*/, deviceId)
  ① cache = createCache(uid)                        // 按 uid 分库
  ② rpcClient + conversationRepo（先建：EventProcessor 依赖它）
  ③ eventProcessor(onConversationsDirty = { conversationRepo.listConversations() })
  ④ messageSender = { imClient.sendAndWaitAck(it) }
  ⑤ AppLog 缓冲注入（trace 2000 / fault 500）
  ⑥ rpcClient.start(); eventProcessor.start()        // 需已连接
  ⑦ crashDumper + httpLogUploader.start(); AppLog.onFault = uploader::trigger
  ⑧ return ClientSession(imClient, userSession, cache, rpcClient, ep, uploader, 7 repos)

session.close()  // 级联顺序（owner-driven）
  httpLogUploader.stop()          // 含 runBlocking 最终 flush
  rpcClient.stop(); eventProcessor.stop()
  eventProcessor.onContactChanged = null
  imClient.disconnect()           // 软断（EventLoop 存活供重登复用）
  AppLog.traceBuffer/faultBuffer/onFault = null   // 防全局单例 stale 引用
```

## 3. EventProcessor

- 监听 `imClient.packets`，每个 NotifyPayload 切 `Dispatchers.IO` 处理（DB 永不阻塞 EventLoop）
- **游标语义**：处理成功才推进 `lastEventId`（at-least-once；失败事件靠服务端补发重试；防死循环 = 消息 seq 兜底 + 事件 7 天 TTL）
- **契约解码**：统一 `decodePayload<T>` 从 [NotifyContracts](../01-protocol/notify-contracts.md) 取 reader
- 分发表：见 [契约表 §3](../01-protocol/notify-contracts.md#3-契约表18-登记--1-豁免)
- 输出流：`messageEvents`（buffer 64，无头入口）、`typingEvents`（buffer 8）
- 回调：`onConversationsDirty`（CHAT_CREATED → 重拉会话，否则被拉入群不显示）、`onContactChanged`（红点刷新）
- 根循环兜底：CancellationException 原样抛；其他异常 fault 日志兜住（单条事件错误不搞垮监听）

## 4. Repository 模式（本地优先）

```kotlin
suspend fun getHistory(chatId, fromSeq, limit): Outcome<List<Message>> = outcome {
    val response = rpc.invoke(MessageRpcContract.SERVICE, MessageRpcContract.M_GET_HISTORY, payload)
    response.ensureSuccess()          // 0=OK / 401→AuthExpired / 504→Timeout / 其他→Business
    ProtoCodec.decodeList(Message, data).also { it.forEach(localCache::insertMessage) }  // 写缓存
}
```

- 全部返回 `Outcome<T>`；错误分类见 [wire-format §8](../01-protocol/wire-format.md#8-错误分层双端约定)
- 读方法写缓存（UI 观察 DB）；写方法大多不碰缓存，收敛交给 NOTIFY（单一写入路径）
- 消息发送是唯一非 RPC 写（独立 ACK 协议），经 `MessageSender` 隔离

## 5. UI 消费模式（app 模块，简要）

```
rememberAuthController（Compose 认证包装，Auto-login → createSession → AUTH_FAILED 清token回登录页）
 └─ AppDataState(session)
     ├─ ViewModels（Conversation/Contact/Chat）collect LocalCache StateFlows
     ├─ ChatViewModel：乐观发送（insert SENDING → ack → updateMessage(SENT) → markRead）
     │   onAuthExpired = { session.close() }   // VM 不自断连接（owner-driven）
     └─ 子页面 action 统一封装（kickDevice/createGroup/... 含错误处理+刷新）
```

## 6. SDK 测试闭环（不启动 UI）

| 层 | 测试 | 位置 |
|----|------|------|
| 编解码 | ProtoRoundTripTest（模型/消息体往返） | shared commonTest |
| 契约 | NotifyContractTest（完备性+round-trip） | shared commonTest |
| Repository | MessageRepositoryTest 等（FakeRpcInvoker 脚本化响应） | shared commonTest |
| ViewModel | ConversationViewModelTest（FakeLocalCache，纯 JVM） | app commonTest |
| 全链路 | **ImBot 对 bot**（对真实服务器） | shared commonTest（`-Dtk.botTest.host` 开关） |
| 服务端 e2e | TestPeer / RemoteDemoE2e（`-Dtk.e2e.remote`） | server test |

详见 [06-testing](../07-testing/README.md)。
