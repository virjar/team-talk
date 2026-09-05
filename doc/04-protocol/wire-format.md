# Wire Format

## 1. 字节序与整数

固定宽度 `Short`、`Int`、`Long` 由纯 Kotlin `PacketBuffer` 明确按大端序编码。非负计数、ID 和时间等可变范围
整数使用无符号风格 VarInt/VarLong，每个字节低 7 位保存数据，高位表示后续字节。

当前 VarInt 实现面向非负值；不要直接用它传输需要 ZigZag 的负数。

## 2. Frame

每一帧格式为：

```text
+------------+------------------+-----------------------+
| TYPE 1 byte| LENGTH 4 bytes BE| PAYLOAD LENGTH bytes  |
+------------+------------------+-----------------------+
```

没有帧级 magic。连接先使用 NEGOTIATE 协商；AUTH payload 的四字节序言只校验固定格式：

```text
0x54 ('T')  0x4B ('K')  0x00  0x01
```

未认证连接的 payload 上限是 4 KiB，认证成功后提升为 16 MiB。这样可以在允许正常业务帧的同时，
阻止未认证连接通过声明超大 LENGTH 放大累积缓冲。

`LENGTH < 0`、超过当前上限或未知 TYPE 都属于损坏/跨版本帧，服务端应关闭连接。

`:protocol` 只处理有界 `ByteArray` payload；`:protocol-netty` 独占 ByteBuf、帧累积和连接方向校验，
在传输边界复制一次 payload。引用计数对象不会进入契约、领域或缓存代码。

## 3. PacketType

| code | 类型 | 方向 | payload |
|---:|---|---|---|
| 1 | AUTH | C→S | AuthRequestPayload |
| 2 | AUTH_RESP | S→C | AuthResponsePayload |
| 3 | DISCONNECT | 双向 | 空 |
| 4 | PING | 双向 | 空 |
| 5 | PONG | 双向 | 空 |
| 6 | SYNC_REQUEST | C→S | SyncRequestPayload |
| 7 | SYNC_BATCH | S→C | SyncBatchPayload |
| 8 | SYNC_READY | S→C | SyncReadyPayload（空结构） |
| 9 | SYNC_RESET | S→C | SyncResetPayload（当前 datasetId） |
| 10 | INVOKE | C→S | InvokePayload |
| 11 | RESPONSE | S→C | ResponsePayload |
| 12 | STREAM_ITEM | S→C | StreamItemPayload（保留；当前不可用） |
| 13 | STREAM_END | S→C | StreamEndPayload（保留；当前不可用） |
| 14 | NEGOTIATE | C→S | ProtocolNegotiateRequestPayload，固定 bootstrap |
| 15 | NEGOTIATE_RESP | S→C | ProtocolNegotiateResponsePayload |
| 20 | MESSAGE | C→S | Message |
| 21 | MESSAGE_ACK | S→C | MessageAckPayload |
| 30 | NOTIFY | S→C | NotifyPayload |
| 31 | CONNECTION_TRACE_CONTEXT | S→C | ConnectionTraceContextPayload（瞬时连接诊断启停） |

顶层类型集合与 MessageType 都随协议版本固定。未知类型没有可证明的 body 长度和语义，必须
拒绝整帧并断开连接，不能静默跳过或猜测降级。

STREAM_ITEM/STREAM_END 目前只锁定编号和 codec，不具备发送、聚合、背压、取消与超时状态机。它们
不是当前大列表同步方案；事件同步使用 SYNC_REQUEST 的有界批次，历史消息使用 RPC 分页。

## 4. PacketBuffer 原语

| 类型 | 编码 |
|---|---|
| Byte | 1 byte |
| Short | 2 bytes big-endian |
| Int | 4 bytes big-endian |
| Long | 8 bytes big-endian |
| VarInt/VarLong | 非负整数的最短 7-bit continuation 编码 |
| Boolean / presence | 单字节 0 或 1；其他值非法 |
| String | `present(1B)` + `length(VarInt)` + 严格 UTF-8；null 只写 0 |
| Bytes | `present(1B)` + `length(VarInt)` + bytes；null 只写 0 |

空字符串与 null 不同：空字符串写 present=1、length=0。解码器必须在读取长度后验证剩余字节和
上层业务限制，拒绝非最短 VarInt、非法 UTF-8、非 0/1 布尔值和必填 String 的 null marker，
不能把帧上限当作所有字段的唯一限制。

## 5. 认证 payload

TCP/TLS 就绪后的首帧是 NEGOTIATE。版本协商信封固定，字段顺序如下：

```text
NEGOTIATE:
  major VarInt + minimumMinor VarInt + currentMinor VarInt
  clientReleaseVersion String
NEGOTIATE_RESP:
  code VarInt
  serverMajor VarInt + serverMinimumMinor VarInt + serverCurrentMinor VarInt
  hasNegotiatedVersion Boolean
  negotiatedMajor VarInt + negotiatedMinor VarInt  // has=true 时
  serverReleaseVersion String
```

成功 code=0 必须携带唯一协商版本；1=major 不匹配，2=客户端过旧，3=服务端过旧，拒绝时不能携带
协商结果。展示版本为有界三段数字字符串，不参与数字窗口比较。客户端核对整个结果符合原提议后
才发送 AUTH；未协商的旧客户端直接 AUTH 会收到“版本不支持”，不会执行认证业务。
详细算法见[版本协商](versioning.md#一次连接怎样协商)。

AUTH 的固定序言之后字段顺序为：

```text
authType VarInt
username String?
password String?
name String?
refreshToken String?
deviceId String
deviceName String?
deviceModel String?
deviceFlag VarInt
correlationId String
connectionGeneration VarLong
```

AUTH_RESP：

```text
code VarInt
reason String?
uid String?
username String?
name String?
accessToken String?
refreshToken String?
expiresIn VarLong
datasetId String?
hasConnectionTraceContext Boolean
connectionTraceContext ConnectionTraceContext?  // has=true 时
```

`datasetId` 的可空性只服务于失败分支：`code=0` 时必须存在且必须是 lowercase canonical UUID，
`code!=0` 时必须缺省。任一交叉状态都属于损坏的 AUTH_RESP，codec 不得把它交给认证状态机。
每次物理连接写 AUTH 前都要生成新 `correlationId`，并写入同一 ImClient/进程生命周期内严格递增的本地
`connectionGeneration`；自动重连不得复用上一条连接的两个字段。进程重启后 generation 可从 1 重新开始，
跨重启隔离依赖每条连接全新的 safe `correlationId`。只有服务端诊断策略命中时，
成功 AUTH_RESP 才携带 `ConnectionTraceContext(correlationId, traceId, sessionId,
connectionGeneration, policyRevision, expiresAtEpochMs)`。失败响应禁止携带该上下文。

存续期内的诊断策略变更使用顶层 31 号帧：

```text
correlationId String
connectionGeneration VarLong
policyRevision VarLong
enabled Boolean
context ConnectionTraceContext?  // enabled=true 时
```

它不带 `eventId`、不保存、不补发，断线时客户端必须立即清除。字段与当前 AUTH 代际不匹配或
policyRevision 未严格前进时不得覆盖当前上下文。

认证语义见[认证与错误](authentication-and-errors.md)。

认证只确认身份，不夹带事件游标。拥有 LocalCache 的事件消费者启动后发送：

```text
SYNC_REQUEST: lastEventId VarLong + datasetId String
SYNC_BATCH:   count VarInt + count × NotifyPayload
SYNC_READY:   empty
SYNC_RESET:   datasetId String
```

每批最多 64 条，并同时受 16 MiB frame wire 大小约束。客户端必须逐条完成投影并把最后一条
`eventId` 与 AUTH 确认的 `datasetId` 原子写入本地 `sync_state`，之后才请求下一批。服务端第一次查询为空后，在与该用户
事件持久化/推送相同的门闩内二次查询；仍为空才先写 `SYNC_READY`、注册实时连接并开放 NOTIFY。
因此历史同步不依赖进程内大缓冲，也不存在“查空到注册”之间的丢事件窗口。

如果单条合法 NOTIFY 恰好能占满 16 MiB、仅因批次数字段无法再装入 SYNC_BATCH，服务端在同步阶段
可直接发送这一条持久 NOTIFY；客户端落库后仍以新的游标发送下一次 SYNC_REQUEST。

每条请求的 `datasetId` 必须等于 AUTH_RESP 中的当前权威值。首个 `lastEventId` 必须位于该 dataset 中
当前认证 uid 的已保留范围 `compactedThrough..lastSeq`；`0` 只在 `compactedThrough = 0` 时有效。
同一次未发生 RESET 的增量同步序列内，后续请求还必须严格递增。eventId 只在 dataset + uid 内有意义，
不同账号或服务端重建前后可以出现相同数字。
dataset 不匹配、游标低于压缩 floor、越过本账号水位或游标损坏时，服务端不激活实时
连接，而是在同一已认证连接发送一次携带当前 `datasetId` 的 `SYNC_RESET`。

收到 RESET 后客户端保持 `SYNCHRONIZING`，通过二进制 `SyncRpc` 方法 1–4 收齐当前
User、Contact、Chat 和 Conversation checkpoint。这些 keyset 页不共享一个跨请求 MVCC
snapshot；header 的 `baseEventId` 是安装完成后的 tail 起点。客户端只能在本地
`datasetId + cursor` 仍等于开始加载时预期值时，用一个 SQLite 事务替换紧凑服务器投影并把
cursor 设为 `baseEventId`，再立即发送 `SYNC_REQUEST(baseEventId, datasetId)`。收集/安装失败、
重置期间收到其他同步包或同连接第二次 RESET 都关闭连接，绝不把非法游标的空结果
误当作 `SYNC_READY`。

## 6. 心跳

客户端每 15 秒无写入时发送 PING；45 秒未读到数据时关闭连接，由重连策略接管。PING/PONG 是
零 payload 帧。接收到任何合法流量都能证明连接活跃，不能只把 PONG 当作读活动。

## 7. 解码安全

- 解码不完整帧时恢复 reader index，等待后续字节。
- 使用 `readSlice` 同步读完 payload，避免无释放语义的 retained slice 泄漏堆外内存。
- AUTH 前限制 frame size；客户端 codec 在成功 AUTH_RESP 的 decode 当下立即调整上限，保证同一次
  TCP read 中紧随其后的大 SYNC_BATCH 不会仍按 4 KiB 拒绝。下游 handler 再设置一次只作镜像兜底。
- 不在 EventLoop 中执行数据库、文件和复杂模型业务。
- 任意字段数量、顺序和类型不一致都应作为契约错误处理，不尝试 JSON 式宽松猜测。
