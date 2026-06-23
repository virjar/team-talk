# 协议设计

> TeamTalk 使用自研 TCP 二进制协议，不使用 Protobuf 或 JSON。
> 本文完整描述协议的帧格式、包类型、编解码、RPC、消息传输、事件推送、心跳，
> 以及每个设计决策背后的权衡和代价。

## 目录

- [1. 为什么不用 Protobuf / JSON](#1-为什么不用-protobuf--json)
- [2. 帧格式与魔数设计](#2-帧格式与魔数设计)
- [3. 原始类型编解码（PacketBuffer）](#3-原始类型编解码packetbuffer)
- [4. 包类型与连接状态机](#4-包类型与连接状态机)
- [5. RPC 机制（INVOKE/RESPONSE）](#5-rpc-机制invokeresponse)
- [6. 消息传输（MESSAGE/MESSAGE_ACK）](#6-消息传输messagemessage_ack)
- [7. 事件推送（NOTIFY）与离线补发](#7-事件推送notify与离线补发)
- [8. 心跳机制](#8-心跳机制)
- [9. 协议演进策略](#9-协议演进策略)

---

## 1. 为什么不用 Protobuf / JSON

### 为什么不用 JSON（五条理由）

1. **字段名冗余**：JSON 为每个字段支付 5-20 字节的名称开销（`"channelId":` = 13 字节），二进制为 0。实测一条纯文本消息 JSON ≈180 字节 vs 二进制 ≈45 字节，**4 倍差距**。
2. **双重序列化**：JSON 中嵌套的 JSON 字符串字段需要两次解析。
3. **无法直接操作 ByteBuf**：JSON 必须经过 String/ByteArray 中转，Netty 原生 ByteBuf 无法直接解析。
4. **类型安全绕过**：JSON 允许手写 `JSONObject.put()` 绕过 data class 构造函数，经过多层转发后才发现字段缺失。二进制强制所有编解码通过 `IProto` 接口，中间节点物理上无法手工拼装 payload。**这是正确性论证，不是性能论证。**
5. **解析成本**：JSON 是状态机（每字节一次 CPU 分支），二进制是索引运算（`readInt()` 一条指令）。

### 为什么不用 Protobuf

Protobuf 的价值是跨语言互操作（`.proto` → 多语言代码生成）。TeamTalk 是纯 Kotlin（KMP shared 模块），IDL + protoc + gradle 插件是纯粹的构建复杂度，零收益。**Kotlin data class 本身就是 schema。**

### 选择的代价

RPC payload 字段配对完全靠人工保证。历史上出过 3 次 wire format 错位 bug。缓解措施：`ProtoRoundTripTest` 覆盖所有 RPC 方法的编解码往返测试；新增 RPC 必须加测试。

---

## 2. 帧格式与魔数设计

### 帧结构

```
┌──────────┬──────────┬──────────┬──────────┬─────────────────┐
│ Magic(2) │ Version(1)│ Type(1)  │ Length(4)│ Payload(变长)    │
│ 0x54 0x4B│ 0x01     │          │ BigEndian│                 │
│ ASCII"TK"│          │          │          │                 │
└──────────┴──────────┴──────────┴──────────┴─────────────────┘
  固定 8 字节帧头                    最大 16MB payload
```

### 魔数：`0x54 0x4B`（ASCII "TK"）

**为什么每帧都带魔数（不只是握手帧）**：一旦 TCP 流错位一个字节，后续所有读取都是垃圾。每帧魔数让 `PacketCodec` 立即检测到失步（抛 `CorruptedFrameException`），而不是让编解码器愉快地解析垃圾直到 `IndexOutOfBoundsException`。

**为什么用 ASCII 可打印字符**：hex dump 中 `54 4B` 直接读作 "TK"，便于调试。不会和帧长度字节混淆。

### 版本号：`PROTOCOL_VERSION = 1`

每帧都校验版本号。版本不匹配 → `CorruptedFrameException`。握手阶段服务端主动发送 3 字节（Magic + Version），客户端回显确认。

**为什么不做版本协商**：TeamTalk 明确拒绝渐进式协商。不兼容的客户端直接硬拒绝。版本变更只在大版本间发生，协商机制（如 TLS ALPN）是不必要的复杂度。

### Length：为什么 4 字节（不是 2 或 8）

- **2 字节（64KB）**：不够。中等长度消息和图片缩略图会超过 64KB，被迫走 HTTP，违背二进制 IM 协议的初衷。
- **8 字节**：浪费。4 字节已允许 4GB，应用层上限是 16MB，8 字节多出 4 字节无意义。
- **VLQ 变长编码**：被明确拒绝。"IM 场景省 1 字节无意义，一个冗余请求或图片下载就消耗兆字节。固定 4 字节直接映射 Netty 原生 `readInt()`/`writeInt()`，无需自定义编解码器。"

**为什么 16MB**：(a) 覆盖最长的单条 TCP 消息，(b) 不触发运营商流量整形阈值，(c) 防御恶意大包攻击。大文件走 HTTP 上传，不进 TCP 协议。

---

## 3. 原始类型编解码（PacketBuffer）

### VarInt：只在 Int/Long 和计数上用，不在固定字段上用

VarInt 用 Protobuf/LEB128 方案（每字节 7 数据位 + 1 续位标志）。

**为什么用 VarInt**：IM 数据中小值占主导（`messageType` 1-15、`serviceId` 1-8、`lastEventId` 初始很小），1 字节编码代替 4/8 字节。

**为什么帧长度不用 VarInt**：帧边界必须机器恒定以匹配 Netty `readInt()`。payload 内部的可变宽度字段用 VarInt，因为 `PacketBuffer` 已处理续位循环。

**已知健壮性缺口**：`readVarInt` 无上限检查和最大迭代次数限制。恶意全 `0x80` 字节流会循环到缓冲区耗尽。最终被 `IndexOutOfBoundsException → FatalCodecException → 连接关闭` 兜底，安全但不够优雅（Protobuf 参考实现限制 10 字节）。

### String/Bytes 的 null 标志 + 长度模式

```
null:  [0x00]
非null: [0x01] [VarInt length] [UTF-8 bytes]
```

**为什么不只用长度前缀**：V1 用 `writeShort(-1)` 哨兵表示 null，但负数哨兵只在固定宽度有符号长度上可行。切换到 VarInt（无符号/非负）后无法表示 -1，所以分离出独立的 1 字节存在标志。**这是 VarInt 决策的直接后果。**

### 为什么 Big-Endian

遵循网络字节序惯例（RFC 1700）。TCP 序列号、IP 头、DNS 长度都是 Big-Endian。TeamTalk 遵循惯例以便 packet capture 直接可读（`54 4B 01 0A 00 00 00 14` = magic TK, version 1, type 10, length 20）。不用 Little-Endian 因为 VarInt 和 UTF-8 与字节序无关，少量 `writeInt`/`writeLong` 的字节交换成本可忽略。

---

## 4. 包类型与连接状态机

### 包类型

```kotlin
enum class PacketType(val id: Int) {
    HANDSHAKE(0),       // 已弃用（握手在 PacketType 之外处理）
    AUTH(2),            // 认证请求
    AUTH_RESPONSE(3),   // 认证响应
    INVOKE(4),          // RPC 请求
    RESPONSE(5),        // RPC 响应
    MESSAGE(6),         // 聊天消息
    MESSAGE_ACK(7),     // 消息确认
    NOTIFY(8),          // 事件推送
    PING(9),            // 心跳
    PONG(10),           // 心跳响应
}
```

### 为什么握手和认证分开

1. **运行在不同的 Netty Handler 中**：`HandshakeHandler` 是临时的（完成后自移除），`ImAgent` 是持久的。合并意味着一个 Handler 做两个生命周期不同的事。
2. **建立不同的东西**：握手建立"你说的是我的协议吗"（Magic + Version），认证建立"你是谁、是否允许"（凭证 + Token）。混淆意味着版本不匹配看起来像认证失败，误导调试和重连逻辑。
3. **协议编码空间**：握手在 PacketType 之外处理（3 字节裸字节，不经 PacketCodec），为 PacketType 节省了枚举值。

### 连接状态机（服务端 ImAgent）

```
CONNECTED → AUTHENTICATED → DISCONNECTED
```

只有 3 个状态，没有 `AUTHENTICATING` 或 `RECONNECTING`：

- **认证中折叠到 CONNECTED**：服务端异步处理 AUTH，状态保持 CONNECTED 直到认证成功原子切换为 AUTHENTICATED。认证失败时连接最终被关闭或超时。
- **原子状态切换**：`_state` 是 `AtomicReference`，只有认证成功分支内才 `.set(AUTHENTICATED)`。结合每个 Handler 的 `state != AUTHENTICATED` 门控，保证 `clientRegistry.register()` 完成前不会有业务包被处理。

### 为什么 AUTH 不走 RPC

AUTH 有独立的 `PacketType.AUTH`，不走 `INVOKE`。因为 RPC 分发要求 `state == AUTHENTICATED`，认证前不能 RPC。**这和 TLS 有独立的握手记录类型同理。**

---

## 5. RPC 机制（INVOKE/RESPONSE）

### 两级分发（serviceId + methodId）

```
INVOKE { requestId, serviceId, methodId, payload }
                    │            │
                    ▼            ▼
           Service 路由     Method 路由
           (8个服务)        (每服务1-19个方法)
```

**为什么两级而不是扁平方法表**：
1. **命名空间防冲突**：每个服务有自己的方法 ID 空间，`ChatMethod.GET(3)` 和 `UserMethod.SEARCH(3)` 共存无冲突。
2. **Handler 隔离**：每个服务是独立的 class，有自己的依赖注入。依赖注入图就是分发表。
3. **与 V1 "扁平化"哲学的反转**：消息类型扁平化（每种类型一个 PacketType）是因为它们形态异构；RPC 方法命名空间化是因为它们形态同构（都是 请求字节→响应字节），命名空间有助组织。

### 异常三级分流

| 异常 | 含义 | 处理 | 连接 |
|------|------|------|------|
| `IllegalArgumentException` | 业务校验（参数错误） | 返回 status=400 | 保持 |
| `IndexOutOfBoundsException` | 编解码错位 | 抛 `FatalCodecException` | **关闭** |
| 其他 `Exception` | 内部错误（DB/NPE） | 返回 status=500 | 保持 |

**编解码错位必须关闭连接**：一旦 payload 被错误解析，下一个帧的长度前缀读到的是 payload 字节，所有后续帧都是垃圾。TCP 没有重新同步机制，关闭是唯一安全操作。

### encodePayload / withPayload

简单的 RPC 参数（1-3 个字段）用内联 lambda 编解码，不定义 data class：

```kotlin
// 客户端
val payload = ProtoCodec.encodePayload {
    writeString(chatId)      // 字段 1
    writeString(name)        // 字段 2
}

// 服务端（必须严格配对！）
ProtoCodec.withPayload(payload) {
    val chatId = readString()!!   // 字段 1
    val name = readString()       // 字段 2
}
```

复杂的传输结构（AuthRequestPayload、Message、User）用命名 data class + IProto 接口。**分界线：简单内联，复杂命名。**

---

## 6. 消息传输（MESSAGE/MESSAGE_ACK）

### 为什么消息不走 RPC

- 消息的 ACK 只需 `clientMsgId → serverSeq` 映射，不需要完整 RPC 响应框架
- 消息发送是高频操作，独立轻量包类型减少协议开销
- `clientMsgId` 幂等去重允许客户端安全重发（网络抖动时）

### clientMsgId 幂等

```
发送流程:
1. 客户端生成 clientMsgId (UUID)
2. 本地写入 SendStatus=SENDING（乐观更新）
3. MESSAGE 包发送
4. 服务端: clientMsgId 去重 → 分配 serverSeq → 存储 → MESSAGE_ACK
5. 客户端收到 ACK: 更新 serverSeq, SendStatus=SENT
```

`clientMsgId` 去重在服务端 MessageStore 用独立 key 索引：`[0x01][clientMsgId] → chatId + seq`。`0x01` 前缀手动分隔 keyspace，避免与 `[chatId][8B seq]` 索引冲突。

### serverSeq 用 BigEndian 8 字节

消息存储 key 格式 `[chatId][8B seq BigEndian]`。BigEndian 使字节字典序等于数字序，支持 RocksDB 范围扫描（seek/seekForPrev 翻页）。

---

## 7. 事件推送（NOTIFY）与离线补发

### 事件快照原则

NOTIFY 推送**完整当前快照**而非增量变更。客户端直接 upsert，天然幂等——即使重复投递也不产生错误数据。

### 双层同步模型

| 层 | 序列号 | 覆盖范围 | 补发方式 |
|----|--------|---------|---------|
| 消息层 | per-chat `serverSeq` | 消息历史 | `SUBSCRIBE(lastSeq)` 按会话补拉 |
| 事件层 | 全局 `lastEventId` | 所有事件（消息+联系人+会话+在线状态...） | AUTH 时携带 `lastEventId`，服务端补发缺失事件 |

**为什么用 per-chat seq 而非全局 seq**（对比 Telegram 全局 pts）：per-chat seq 更直观（每个会话独立递增）、更容错（一个会话的 gap 不阻塞其他会话）、更易实现（存储天然按 chatId 分区）、匹配万级用户规模。

### NOTIFY 载荷是通用信封

```kotlin
NotifyPayload(eventId, notifyType, opaquePayloadBytes)
```

notifyType 是路由键，payload bytes 按 notifyType 解码。这让服务端推送新 notify 类型无需改变 NotifyPayload 的 wire format。

---

## 8. 心跳机制

```
客户端: IdleStateHandler(readerIdle=45s, writerIdle=15s)
  writerIdle 15s → 发 PingSignal
  readerIdle 45s → 关闭连接 + 触发重连

服务端: IdleStateHandler(readerIdle=45s, writerIdle=0)
  readerIdle 45s → 关闭连接
```

### 3 倍规则

`readerIdle = PING_INTERVAL × 3`。容忍 1-2 次丢失 PONG 不触发误断连。此前用 30s/90s，发现僵死连接发现太慢后缩短。

### 非对称设计

客户端发起心跳（15s PING），服务端只监听（45s readerIdle）。服务端不主动发心跳——因为客户端的 PING 足以保持连接活跃，服务端不需要额外的心跳开销。

### 踩坑经验

详见 [authentication.md](authentication.md) 的 pendingAuth 更新和 send() 鉴权门控。

---

## 9. 协议演进策略

### 版本控制

握手阶段交换 `PROTOCOL_VERSION`。不兼容则拒绝连接。版本变更只在大版本间发生。

### 兼容性策略

| 版本跨度 | 策略 |
|---------|------|
| 同版本内 | 二进制严格不变；少量兼容逻辑通过通用扩展消化 |
| 小版本间 | 允许少量兼容逻辑（收敛到通用扩展） |
| 大版本之间 | **直接阻断**，不维护跨大版本兼容 |

### 前向兼容（append + version）

新字段追加在末尾；旧客户端读已知字段后停止（忽略尾部字节）；握手 Version 让服务端决定发送哪些字段。不需要字段标签（不像 Protobuf 的 field number）。**限制：只对追加变更有效，删除/重排字段需要递增 PROTOCOL_VERSION。**

### 新消息类型的优雅降级

`MessageBodyRegistry.decode` 遇到未知 messageType 返回 `body=null`。旧客户端收到新消息类型时仍能渲染信封（chatId、发送者、时间戳），只是无法显示内容。

### 通用扩展接口（Escape Hatch）

二进制协议的优点是**模型确定性**，但纯固定协议在开发新需求时会僵化——增加字段或修改模型需要不兼容的大版本升级。TeamTalk 采用「**固定二进制 + 通用扩展**」的混合策略，在同一版本内保持动态平衡。

#### 设计目标

- **同一版本内**：二进制协议严格不变，已固化的枚举（ServiceId/NotifyType/MessageType）不可改动
- **新需求覆盖不了时**：通过通用扩展通信框架承载，不侵入已有枚举
- **大版本升级时**：把累积的通用扩展收敛回二进制，固化为新的枚举值

#### 三个扩展入口

在现有三种通信模型中各预留一个 `GENERIC(99)` 入口：

| 扩展点 | 通信模型 | 枚举入口 | 方向 | 路由方式 |
|--------|---------|---------|------|---------|
| **RPC 扩展** | INVOKE/RESPONSE | `ServiceId.GENERIC(99)` | C→S→C | methodId = ExtensionType.code |
| **推送扩展** | NOTIFY | `NotifyType.GENERIC(99)` | S→C | payload = GenericPayload(extensionType + data) |
| **消息扩展** | MESSAGE | `MessageType.GENERIC(99)` | C→S/S→C | body = GenericPayload(extensionType + data) |

#### ExtensionType 枚举（跨三种通信模型共用）

```kotlin
enum class ExtensionType(val code: Int) {
    // 预留扩展类型，随需求追加
    // 每个类型在 GenericRpcRegistry / GenericDispatcher 中注册处理器
    ;
}
```

新增扩展只需：1) 在 ExtensionType 追加枚举值；2) 实现处理器；3) 注册到注册表。不改 dispatch 逻辑。

#### 路由注册表

三种通信模型各有独立的注册表：

| 注册表 | 位置 | 用途 |
|--------|------|------|
| `GenericRpcRegistry` | 服务端 | RPC 扩展按 extensionType 分发到 GenericRpcHandler |
| `GenericDispatcher.notifyHandlers` | 客户端 | 推送扩展按 extensionType 分发到 GenericNotifyHandler |
| `GenericDispatcher.messageHandlers` | 客户端 | 消息扩展按 extensionType 分发到 GenericMessageHandler |

注册表模式（类似 MessageBodyRegistry）：新增扩展不改 dispatch 逻辑。未注册的扩展静默忽略（前向兼容）。

#### 演进循环

```
版本 N（固定二进制 + 少量通用扩展）
    │
    │ 新需求 → GENERIC(99) 入口承载（不改枚举）
    │ 通用扩展语义累积
    │
    ▼ 大版本升级（不兼容，阻断旧版）
版本 N+1
    收敛通用扩展 → 固化为新的枚举值
    二进制更稳定，通用扩展归零（或减少）
```

通过这个循环，二进制协议和通用扩展保持动态平衡——二进制随版本演进越来越稳定，通用扩展作为「候选区」为新版本提供收敛素材。

#### 为什么不用 Protobuf 的 optional + tag

- Protobuf 的 `optional` 字段 + tag 号提供前向兼容，但代价是**模型确定性下降**（任何字段都可能缺失，代码到处判空）
- TeamTalk 选择固定二进制 + 通用扩展的组合：已确定的核心枚举享受强类型 + 高性能，不确定的部分用 GENERIC(99) 兜底，**不引入额外的 schema 工具链**

#### 为什么不直接给每个模型加 extras 字段

给每个模型（User/Message/Chat）都加 `extras: Map<String, String>` 会退化成 JSON——丧失类型安全，每个模型的 wire format 都变得不确定。通用扩展是**通信模型级别**的逃生通道，不侵入已有模型。

---

#### 为什么不用 Protobuf 的 optional + tag

- Protobuf 的 `optional` 字段 + tag 号提供前向兼容，但代价是**模型确定性下降**（任何字段都可能缺失，代码到处判空）
- TeamTalk 选择手写二进制 + 通用扩展的组合：已确定的核心模型享受强类型 + 高性能，不确定的部分用通用扩展兜底，**不引入额外的 schema 工具链**

---

**相关代码**：`shared/.../protocol/Frame.kt`、`shared/.../protocol/PacketBuffer.kt`、`shared/.../protocol/ProtoCodec.kt`、`shared/.../protocol/PacketCodec.kt`、`server/.../protocol/codec/ImAgent.kt`、`server/.../protocol/dispatcher/RpcDispatcher.kt`
