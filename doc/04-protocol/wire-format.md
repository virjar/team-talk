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
| 10 | INVOKE | C→S | InvokePayload |
| 11 | RESPONSE | S→C | ResponsePayload |
| 12 | STREAM_ITEM | S→C | StreamItemPayload |
| 13 | STREAM_END | S→C | StreamEndPayload |
| 20 | MESSAGE | C→S | Message |
| 21 | MESSAGE_ACK | S→C | MessageAckPayload |
| 30 | NOTIFY | S→C | NotifyPayload |
| 40 | SUBSCRIBE | C→S | SubscribePayload |
| 41 | UNSUBSCRIBE | C→S | UnsubscribePayload |

顶层类型集合随协议版本固定。未知 MessageType 可以通过 body registry 的兼容策略降级，但未知
PacketType 说明帧语义本身不可信，不能静默跳过。

## 4. PacketBuffer 原语

| 类型 | 编码 |
|---|---|
| Byte | 1 byte |
| Short | 2 bytes big-endian |
| Int | 4 bytes big-endian |
| Long | 8 bytes big-endian |
| VarInt/VarLong | 7-bit continuation |
| String | `present(1B)` + `length(VarInt)` + UTF-8；null 只写 0 |
| Bytes | `present(1B)` + `length(VarInt)` + bytes；null 只写 0 |
| Extension map | `present` + `count` + 重复 String key/value |

空字符串与 null 不同：空字符串写 present=1、length=0。解码器必须在读取长度后验证剩余字节和
上层业务限制，不能把帧上限当作所有字段的唯一限制。

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
lastEventId VarLong
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

## 6. 心跳

客户端每 15 秒无写入时发送 PING；45 秒未读到数据时关闭连接，由重连策略接管。PING/PONG 是
零 payload 帧。接收到任何合法流量都能证明连接活跃，不能只把 PONG 当作读活动。

## 7. 解码安全

- 解码不完整帧时恢复 reader index，等待后续字节。
- 使用 `readSlice` 同步读完 payload，避免无释放语义的 retained slice 泄漏堆外内存。
- AUTH 前限制 frame size；认证通过后再调整 codec 上限。
- 不在 EventLoop 中执行数据库、文件和复杂模型业务。
- 任意字段数量、顺序和类型不一致都应作为契约错误处理，不尝试 JSON 式宽松猜测。
