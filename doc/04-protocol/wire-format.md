# Wire Format

## 1. 字节序与整数

固定宽度 `Short`、`Int`、`Long` 使用 Netty ByteBuf 默认的大端序。非负计数、ID 和时间等可变范围
整数使用无符号风格 VarInt/VarLong，每个字节低 7 位保存数据，高位表示后续字节。

当前 VarInt 实现面向非负值；不要直接用它传输需要 ZigZag 的负数。

## 2. Frame

每一帧格式为：

```text
+------------+------------------+-----------------------+
| TYPE 1 byte| LENGTH 4 bytes BE| PAYLOAD LENGTH bytes  |
+------------+------------------+-----------------------+
```

没有帧级 magic。连接身份和协议版本在首个 AUTH payload 的四字节序言中校验：

```text
0x54 ('T')  0x4B ('K')  PROTOCOL_VERSION  0x01
```

未认证连接的 payload 上限是 4 KiB，认证成功后提升为 16 MiB。这样可以在允许正常业务帧的同时，
阻止未认证连接通过声明超大 LENGTH 放大累积缓冲。

`LENGTH < 0`、超过当前上限或未知 TYPE 都属于损坏/跨版本帧，服务端应关闭连接。

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
| 9 | SYNC_RESET | S→C | SyncResetPayload（空结构） |
| 10 | INVOKE | C→S | InvokePayload |
| 11 | RESPONSE | S→C | ResponsePayload |
| 12 | STREAM_ITEM | S→C | StreamItemPayload（保留；当前不可用） |
| 13 | STREAM_END | S→C | StreamEndPayload（保留；当前不可用） |
| 20 | MESSAGE | C→S | Message |
| 21 | MESSAGE_ACK | S→C | MessageAckPayload |
| 30 | NOTIFY | S→C | NotifyPayload |

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

AUTH 是客户端连接后的首个业务帧。序言之后字段顺序为：

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
```

认证语义见[认证与错误](authentication-and-errors.md)。

认证只确认身份，不夹带事件游标。拥有 LocalCache 的事件消费者启动后发送：

```text
SYNC_REQUEST: lastEventId VarLong
SYNC_BATCH:   count VarInt + count × NotifyPayload
SYNC_READY:   empty
SYNC_RESET:   empty
```

每批最多 64 条，并同时受 16 MiB frame wire 大小约束。客户端必须逐条完成投影并把最后一条
`eventId` 单调写入本地 `sync_cursor`，之后才请求下一批。服务端第一次查询为空后，在与该用户
事件持久化/推送相同的门闩内二次查询；仍为空才先写 `SYNC_READY`、注册实时连接并开放 NOTIFY。
因此历史同步不依赖进程内大缓冲，也不存在“查空到注册”之间的丢事件窗口。

如果单条合法 NOTIFY 恰好能占满 16 MiB、仅因批次数字段无法再装入 SYNC_BATCH，服务端在同步阶段
可直接发送这一条持久 NOTIFY；客户端落库后仍以新的游标发送下一次 SYNC_REQUEST。

首个 `lastEventId` 只能是 `0`，或当前认证 uid 的 `1..lastSeq`；后续请求还必须严格递增。eventId
只在 uid 内有意义，不同账号可以出现相同数字。遇到越过本账号水位或已损坏的游标时，服务端不激活实时连接，而是在同一已认证连接
发送一次 `SYNC_RESET`，并允许下一条 `SYNC_REQUEST(0)`。客户端必须原子清空服务器投影和游标后
从 0 重放；清理失败、重置期间收到其他同步包或同连接第二次 RESET 都关闭连接，绝不把非法游标的
空结果误当作 `SYNC_READY`。

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
