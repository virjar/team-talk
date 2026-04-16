# 二进制协议设计

> TeamTalk 基于 Netty 的协议规格：固定包头、字段顺序二进制编码（无 IDL）、零拷贝、部分解码

---

## 1. 设计决策

### 1.1 为什么不用 JSON

| 问题 | 说明 |
|------|------|
| 字段名冗余 | 每字段 5-20 B 头开销（如 `"channelId":` = 13 bytes），字段顺序编码仅需 0 B（无字段名、无 tag） |
| 双重序列化 | ReplyContent/ForwardContent 内嵌 JSON 字符串，需要二次解析 |
| 无法操作 ByteBuf | JSON 必须经过 String/ByteArray 中转，无法直接在 Netty ByteBuf 上读写 |
| 协议太宽泛，绕过类型检查 | JSON 可以用 `JSONObject.put()` 手动拼装，绕过 data class 的构造函数校验。TeamTalk 当前就存在服务端手动拼写 `JSONObject`、客户端手动拼写 `JSONObject` 的情况——消息经过多节点转发后，最终解包才发现字段缺失或类型错误。二进制协议强制所有编解码走 `IProto` 接口的单一实现（共享 jar 包对齐），中间节点无法手动组装 payload |
| 解析开销大 | JSON 反序列化是状态机逐字节处理，每个字节都需 CPU 判断状态转移和可能的回溯。二进制解析是索引直接计算——`readInt()` 一条指令完成。服务端处理大量消息包时，JSON 反序列化的 CPU 消耗和内存碎片（大量临时 String/Map 对象）是不可忽视的浪费，尤其对于只需转发或存储、不需要理解内容的场景 |

量化估算：一条纯文本消息 JSON payload ~180 bytes，等效字段顺序二进制 ~45 bytes，4x 差距。

### 1.2 为什么不用 Protobuf

Protobuf 的核心价值是跨语言互操作（`.proto` → 多语言代码生成）。TeamTalk 前后端已收敛到 Kotlin 单一语言（KMP），引入 IDL 文件 + protoc 编译器 + gradle 插件是多余的构建复杂度。

### 1.3 为什么不用 VLQ 编码长度

VLQ 在 IM 场景下省的 1 字节毫无意义。业务层一个不合理的重复请求或图片下载就消耗几 MB，包头省 1 字节没有任何收益。使用固定 4 字节长度，直接用 Netty 原生 `readInt()` / `writeInt()`，编解码更简单。

### 1.4 采用方案

**字段顺序二进制编码**：Kotlin data class 本身就是 schema，字段顺序就是编解码规则。`IProto` 接口的 `writeTo(ByteBuf)` + `create(ByteBuf)` 直接操作 Netty ByteBuf，零额外依赖，天然支持部分解码和零拷贝。

---

## 2. 包格式

### 2.1 包头

```
Packet = PacketType(1B) + Length(4B, big-endian) + Payload(bytes)

PacketType:  固定 1 字节，编码范围 0-255（语义拍平，详见 §2.2）
Length:     固定 4 字节大端序，payload 字节数
Payload:    Length 指定的字节数（field-order binary）

包头固定 5 字节
PacketDecoder 硬限制 Length ≤ 16 MB，超过直接断开连接
```

16 MB 上限的理由：
- 覆盖长文本、富文本（Markdown）等大消息场景
- 相当于一张较大的图片，运营商不会限流降速
- 超过 16 MB 的文件走 HTTP 上传，不应进入 TCP 包协议

### 2.2 PacketType — 语义拍平 + 编码范围

借鉴 Signal 的 Envelope.type 设计思路：将消息的语义类型从 payload 内部提升到 PacketType 层级。每个 PacketType 直接对应一种明确的业务语义，payload 结构因 PacketType 而异——无需再通过 MessageType 做二次分派。

**为什么拍平**：当前设计中 SEND 包承载了几乎所有业务语义（文本、图片、回复、转发、撤回、输入状态等），导致 SEND 的 payload 结构臃肿（7 个通用字段 + 嵌套 payloadBytes），编解码逻辑集中在 MessageType 分支上。拍平后，每种语义都是独立的包类型，payload 只包含该类型需要的字段，自包含、互不耦合。

**编码范围**：

```
PacketType（1 字节，0-255）:

  0         Reserved

  1-9       连接控制
              DISCONNECT(1)  PING(2)  PONG(3)

  10-19     会话管理
              SUBSCRIBE(10)  UNSUBSCRIBE(11)

  20-49     消息（双向统一）
              TEXT(20)    IMAGE(21)   VOICE(22)   VIDEO(23)
              FILE(24)    LOCATION(25) CARD(26)
              REPLY(27)   FORWARD(28)  MERGE_FORWARD(29)
              REVOKE(30)  EDIT(31)    TYPING(32)
              STICKER(33) REACTION(34)  INTERACTIVE(35)  RICH(36)

  80-89     确认应答
              SENDACK(80)  RECVACK(81)

  90-99     系统消息（Server → Client）
              CHANNEL_CREATED(90)     CHANNEL_UPDATED(91)
              CHANNEL_DELETED(92)     MEMBER_ADDED(93)
              MEMBER_REMOVED(94)      MEMBER_MUTED(95)
              MEMBER_UNMUTED(96)      MEMBER_ROLE_CHANGED(97)
              CHANNEL_ANNOUNCEMENT(98)

  100-109   命令与控制（Server → Client 推送）
              CMD(100)  ACK(101)  PRESENCE(102)

  110-255   保留 / 自定义
```

**双向统一**：消息类型（20-36）上行和下行共用同一 PacketType。客户端发送时服务端字段（messageId/senderUid/serverSeq/timestamp）为 null/0，服务端填充后直接转发同一结构。

### 2.2.1 握手包（CONNECT / CONNACK）

CONNECT/CONNACK 不在 PacketType 枚举中。握手是每条 TCP 连接的固定流程——客户端必然先发握手包，服务端必然先回响应包——这个顺序是隐含的，不需要用 PacketType 字节标记。把握手从包中独立出来，PacketType 编码空间全部留给包语义，握手包也可以塞更多校验逻辑（Magic 快速排雷、版本协商等）。

完整握手流程设计及理由详见 [01-protocol-comparison.md](../analysis/01-protocol-comparison.md) §7.4，此处仅列出格式概要：

```
握手请求（Client → Server）:
  Magic(7B)        协议魔数，不匹配立即 close
  Version(1B)      协议版本，不支持立即 close
  tokenLen(2B)     JWT token 长度
  token(N B)       JWT token（鉴权）
  uidLen(2B)       UID 长度
  uid(N B)         用户 UID
  deviceIdLen(2B)  设备 ID 长度
  deviceId(N B)    设备唯一标识
  deviceName       设备名称（String，同上编解码）
  deviceModel      设备型号（String）
  deviceFlag(1B)   0=app, 1=web, 2=pc
  clientVersion    客户端版本（String）

握手响应（Server → Client）:
  Magic(7B)        服务端魔数
  Version(1B)      服务端协议版本
  Code(1B)         状态码: 0=成功, 1=认证失败, 2=版本不支持, 3=维护中
  reasonLen(2B)    原因描述长度
  reason(N B)      人类可读的状态描述
  serverVersion    服务端版本号（String，Code=0 时）
  serverTimestamp  服务端时间戳（VarInt，Code=0 时）
```

握手成功（Code=0）后，连接进入包交换阶段，后续所有数据由 PacketDecoder 处理。

### 2.3 PacketDecoder

```kotlin
class PacketDecoder : ByteToMessageDecoder() {
    companion object {
        private const val HEADER_SIZE = 5  // Type(1) + Length(4)
        private const val MAX_PAYLOAD_SIZE = 16 * 1024 * 1024  // 16 MB
    }

    override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
        if (buf.readableBytes() < HEADER_SIZE) return

        buf.markReaderIndex()
        val type = buf.readByte()
        val length = buf.readInt()

        if (length > MAX_PAYLOAD_SIZE) {
            ctx.close()  // 超限直接断开
            return
        }

        if (buf.readableBytes() < length) {
            buf.resetReaderIndex()
            return
        }

        val payloadBuf = buf.readRetainedSlice(length)  // 零拷贝
        out.add(Packet(type, payloadBuf))
    }
}
```

**要点**：
- 包头固定 5 字节，`readByte()` + `readInt()` 即可，无自定义编解码
- `readRetainedSlice(length)` 创建 ByteBuf 视图，不拷贝数据
- Packet 持有 ByteBuf 引用，消费完成后释放

### 2.4 PacketEncoder

```kotlin
class PacketEncoder : MessageToByteEncoder<Packet>() {
    override fun encode(ctx: ChannelHandlerContext, packet: Packet, out: ByteBuf) {
        out.writeByte(packet.type.code.toInt())
        out.writeInt(packet.payloadBuf.readableBytes())
        out.writeBytes(packet.payloadBuf)
    }
}
```

可以用 `CompositeByteBuf` 合并包头和负载，完全避免拷贝：

```kotlin
fun composePacket(type: PacketType, payloadBuf: ByteBuf): ByteBuf {
    val headerBuf = Unpooled.buffer(5)
    headerBuf.writeByte(type.code.toInt())
    headerBuf.writeInt(payloadBuf.readableBytes())
    return Unpooled.wrappedBuffer(headerBuf, payloadBuf)  // 零拷贝合并
}
```

### 2.5 Packet 数据结构

```kotlin
data class Packet(
    val type: PacketType,
    val payloadBuf: ByteBuf,    // Netty ByteBuf，引用计数管理
) : ReferenceCounted by payloadBuf  // 委托引用计数
```

---

## 3. 字段顺序二进制编码

### 3.1 IProto 接口

```kotlin
/**
 * 所有需要二进制序列化的消息都实现此接口。
 * 字段按固定顺序读写，无需字段名或 tag。
 */
interface IProto {
    fun writeTo(buf: ByteBuf)
    fun getSize(): Int  // 预估大小，用于 ByteBuf 分配

    companion object {
        fun writeString(buf: ByteBuf, value: String?) {
            if (value == null) {
                buf.writeShort(-1)
                return
            }
            val bytes = value.toByteArray(Charsets.UTF_8)
            buf.writeShort(bytes.size)
            buf.writeBytes(bytes)
        }

        fun readString(buf: ByteBuf): String? {
            val len = buf.readShort().toInt()
            if (len < 0) return null
            val bytes = ByteArray(len)
            buf.readBytes(bytes)
            return String(bytes, Charsets.UTF_8)
        }

        fun writeStringList(buf: ByteBuf, list: List<String>) {
            buf.writeShort(list.size)
            list.forEach { writeString(buf, it) }
        }

        fun readStringList(buf: ByteBuf): List<String> {
            val count = buf.readShort().toInt()
            return (0 until count).map { readString(buf)!! }
        }

        fun writeVarInt(buf: ByteBuf, value: Long) {
            var v = value
            while (v > 0x7F) {
                buf.writeByte((v and 0x7F).toInt() or 0x80)
                v = v shr 7
            }
            buf.writeByte(v.toInt())
        }

        fun readVarInt(buf: ByteBuf): Long {
            var result = 0L
            var shift = 0
            var b: Int
            do {
                b = buf.readByte().toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                shift += 7
            } while (b and 0x80 != 0)
            return result
        }

        fun writeByteArray(buf: ByteBuf, value: ByteArray?) {
            if (value == null) {
                buf.writeInt(-1)
                return
            }
            buf.writeInt(value.size)
            buf.writeBytes(value)
        }

        fun readByteArray(buf: ByteBuf): ByteArray? {
            val len = buf.readInt()
            if (len < 0) return null
            val bytes = ByteArray(len)
            buf.readBytes(bytes)
            return bytes
        }
    }
}
```

### 3.2 编码示例：TextPayload

拍平后，每种 PacketType 对应独立的 IProto 实现，字段自包含，无需 `messageType` 和 `payloadBytes` 中间层。

```kotlin
data class TextPayload(
    // --- 客户端填写 ---
    val channelId: String,
    val clientMsgNo: String,
    val clientSeq: Long,
    // --- 服务端填充（上行 null/0）---
    val messageId: String?,          // 上行 null，服务端填充
    val senderUid: String?,          // 上行 null，服务端填充
    val channelType: Int,            // 服务端根据 channelId 查库填充，不信任客户端
    val serverSeq: Long,             // 上行 0
    val timestamp: Long,             // 上行 0
    val text: String,
    val mentionUids: List<String>,
    val flags: Int,
) : IProto {
    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, channelId)                // String: [len:2B] [bytes]
        IProto.writeString(buf, clientMsgNo)               // String: [len:2B] [bytes]
        IProto.writeVarInt(buf, clientSeq)                 // VarInt: 1-9B
        IProto.writeString(buf, messageId)                 // String?: [len:2B] [bytes] (null → -1)
        IProto.writeString(buf, senderUid)                 // String?:
        buf.writeByte(channelType)                         // Byte: 1B
        IProto.writeVarInt(buf, serverSeq)                 // VarInt:
        IProto.writeVarInt(buf, timestamp)                 // VarInt:
        IProto.writeString(buf, text)                      // String: [len:2B] [bytes]
        IProto.writeStringList(buf, mentionUids)           // List: [count:2B] [items]
        buf.writeByte(flags)                               // Byte: 1B
    }

    override fun getSize(): Int = channelId.toByteArray(Charsets.UTF_8).size + 2 +
            clientMsgNo.toByteArray(Charsets.UTF_8).size + 2 + 9 +
            (messageId?.toByteArray(Charsets.UTF_8)?.size?.plus(2) ?: 2) +
            (senderUid?.toByteArray(Charsets.UTF_8)?.size?.plus(2) ?: 2) + 1 +
            9 + 9 +
            text.toByteArray(Charsets.UTF_8).size + 2 +
            mentionUids.sumOf { it.toByteArray(Charsets.UTF_8).size + 2 } + 2 + 1

    companion object {
        fun create(buf: ByteBuf): TextPayload {
            val channelId = IProto.readString(buf)!!
            val clientMsgNo = IProto.readString(buf)!!
            val clientSeq = IProto.readVarInt(buf)
            val messageId = IProto.readString(buf)          // null if upstream
            val senderUid = IProto.readString(buf)          // null if upstream
            val channelType = buf.readByte().toInt()
            val serverSeq = IProto.readVarInt(buf)
            val timestamp = IProto.readVarInt(buf)
            val text = IProto.readString(buf)!!
            val mentionUids = IProto.readStringList(buf)
            val flags = buf.readByte().toInt()
            return TextPayload(channelId, clientMsgNo, clientSeq,
                messageId, senderUid, channelType, serverSeq, timestamp,
                text, mentionUids, flags)
        }
    }
}
```

### 3.3 完整 Payload 定义

其他 PacketType 的完整 payload 字段定义见 [03a-payload-definitions.md](./03a-payload-definitions.md)（单一真相源）。下文仅保留 ImagePayload 作为编码模式的参考实现。

```kotlin
// === 图片消息（与文本消息的字段完全不同，体现类型特化） ===
data class ImagePayload(
    // --- 客户端填写 ---
    val channelId: String,
    val clientMsgNo: String,
    val clientSeq: Long,
    // --- 服务端填充（上行 null/0）---
    val messageId: String?,      // null if upstream
    val senderUid: String?,      // null if upstream
    val channelType: Int,        // 服务端根据 channelId 查库填充
    val serverSeq: Long,         // 0 if upstream
    val timestamp: Long,         // 0 if upstream
    val url: String,
    val width: Int,
    val height: Int,
    val size: Long,
    val flags: Int,
) : IProto {
    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, channelId)
        IProto.writeString(buf, clientMsgNo)
        IProto.writeVarInt(buf, clientSeq)
        IProto.writeString(buf, messageId)      // null → len=-1
        IProto.writeString(buf, senderUid)      // null → len=-1
        buf.writeByte(channelType)
        IProto.writeVarInt(buf, serverSeq)      // 0 → 1B
        IProto.writeVarInt(buf, timestamp)      // 0 → 1B
        IProto.writeString(buf, url)
        IProto.writeVarInt(buf, width.toLong())
        IProto.writeVarInt(buf, height.toLong())
        IProto.writeVarInt(buf, size)
        buf.writeByte(flags)
    }
    // create() 与 writeTo 对称，省略
}
```

**与拍平前的对比**：
- **拍平前**：`SendPayload` 是一个通用结构（7 个字段 + `payloadBytes`），`messageType` 决定如何二次解码 `payloadBytes`。编解码分两层：先解码 SendPayload，再根据 messageType 解码 payloadBytes
- **拍平后**：每种类型有独立的 data class，字段自包含，一次编解码完成。无 `payloadBytes` 中间层，无 `messageType` 二次分派

### 3.4 操作/控制消息 Payload 示例

操作消息和控制消息同样遵循 IProto 接口，只是字段更精简。

```kotlin
// === 撤回（PacketType.REVOKE=30，双向统一，极简 payload） ===
data class RevokePayload(
    // --- 客户端填写 ---
    val channelId: String,
    val clientMsgNo: String,
    val clientSeq: Long,
    // --- 服务端填充（上行 null/0）---
    val messageId: String?,           // 上行 null，服务端填充
    val senderUid: String?,          // 上行 null，服务端填充
    val channelType: Int,            // 服务端根据 channelId 查库填充
    val serverSeq: Long,
    val timestamp: Long,
    val targetMessageId: String,     // 要撤回的消息 ID
    val flags: Int,
) : IProto {
    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, channelId)
        IProto.writeString(buf, clientMsgNo)
        IProto.writeVarInt(buf, clientSeq)
        IProto.writeString(buf, messageId)
        IProto.writeString(buf, senderUid)
        buf.writeByte(channelType)
        IProto.writeVarInt(buf, serverSeq)
        IProto.writeVarInt(buf, timestamp)
        IProto.writeString(buf, targetMessageId)
        buf.writeByte(flags)
    }
    // create() 省略
}

// === 表情回应（PacketType.REACTION=34，双向统一，不持久化） ===
data class ReactionPayload(
    // --- 客户端填写 ---
    val channelId: String,
    val clientMsgNo: String,
    val clientSeq: Long,
    // --- 服务端填充（上行 null/0）---
    val messageId: String?,
    val senderUid: String?,
    val channelType: Int,            // 服务端根据 channelId 查库填充
    val serverSeq: Long,
    val timestamp: Long,
    val targetMessageId: String,     // 目标消息 ID
    val emoji: String,               // emoji 字符
    val remove: Boolean,             // true=取消回应
    val flags: Int,
) : IProto {
    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, channelId)
        IProto.writeString(buf, clientMsgNo)
        IProto.writeVarInt(buf, clientSeq)
        IProto.writeString(buf, messageId)
        IProto.writeString(buf, senderUid)
        buf.writeByte(channelType)
        IProto.writeVarInt(buf, serverSeq)
        IProto.writeVarInt(buf, timestamp)
        IProto.writeString(buf, targetMessageId)
        IProto.writeString(buf, emoji)
        buf.writeBoolean(remove)
        buf.writeByte(flags)
    }
    // create() 省略
}

// === 在线状态（PacketType.PRESENCE=102，服务端推送，不持久化） ===
data class PresencePayload(
    val uid: String,                 // 状态变更的用户
    val status: Byte,                // 0=offline, 1=online
    val lastSeenAt: Long,            // 最后在线时间（offline 时有值）
) : IProto {
    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, uid)
        buf.writeByte(status.toInt())
        IProto.writeVarInt(buf, lastSeenAt)
    }
    // create() 省略
}

// === 输入状态（PacketType.TYPING=32，双向统一，不持久化） ===
data class TypingPayload(
    // --- 客户端填写 ---
    val channelId: String,
    val clientMsgNo: String,
    val clientSeq: Long,
    // --- 服务端填充（上行 null/0）---
    val messageId: String?,
    val senderUid: String?,
    val channelType: Int,            // 服务端根据 channelId 查库填充
    val serverSeq: Long,
    val timestamp: Long,
    val action: Byte,                // 0=text, 1=voice, 2=image, 3=file
    val flags: Int,
) : IProto {
    override fun writeTo(buf: ByteBuf) {
        IProto.writeString(buf, channelId)
        IProto.writeString(buf, clientMsgNo)
        IProto.writeVarInt(buf, clientSeq)
        IProto.writeString(buf, messageId)
        IProto.writeString(buf, senderUid)
        buf.writeByte(channelType)
        IProto.writeVarInt(buf, serverSeq)
        IProto.writeVarInt(buf, timestamp)
        buf.writeByte(action.toInt())
        buf.writeByte(flags)
    }
    // create() 省略
}

// === 系统消息：成员加入（PacketType.MEMBER_ADDED=93） ===
data class MemberAddedPayload(
    val channelId: String,
    val channelType: Int,
    val memberUid: String,
    val memberName: String,
    val inviterUid: String,
) : IProto { /* ... */ }
```

各 PacketType 的完整 payload 字段定义见 [03a-payload-definitions.md](./03a-payload-definitions.md)。

### 3.4 可选字段处理

```kotlin
// String: null → len = -1
IProto.writeString(buf, value)  // null → buf.writeShort(-1)

// Int: 使用 VarInt，0 表示默认值/不存在
// List: count = 0 表示空列表
IProto.writeStringList(buf, list)  // 空列表 → buf.writeShort(0)
```

### 3.5 前向兼容性

新增字段时：
1. **在末尾追加**：旧客户端读到已知字段后停止，忽略后续字节
2. **版本协商**：握手包中的 Version 字段可用于服务端决定返回哪些字段
3. **不需要 tag**：Protobuf 用 field number 处理兼容性，字段顺序用"追加 + 版本"处理

### 3.6 与 Protobuf 方案的对比

| 维度 | Protobuf | 字段顺序编码 |
|------|---------|------------|
| 外部依赖 | protoc + protobuf-kotlin | 无 |
| 构建复杂度 | 需配置 gradle 插件 | 无 |
| Schema 定义 | .proto 文件 | Kotlin data class 本身 |
| 代码生成 | 需要 | 不需要 |
| 字段标识 | tag (field_number + wire_type) | 位置顺序 |
| 可选字段 | optional 关键字 | 哨兵值 (null=-1) |
| 前向兼容 | 新 field number | 末尾追加 + 版本协商 |
| 跨语言支持 | 原生支持 | 不支持（Kotlin only） |
| 调试可读性 | 需要 protoc --decode | 需要 hex dump 工具 |
| KMP 兼容性 | 需要额外的多平台配置 | 原生 Kotlin，天然兼容 |

---

## 4. 部分解码

字段顺序二进制天然支持部分解码——按顺序读取需要的字段，跳过不需要的。拍平后，PacketType 已包含语义信息，部分解码更简洁。

**公共前缀与部分解码**：
- 所有**消息 Payload**（20-36）共享 `[MessagePrefix]`（见 [03a](./03a-payload-definitions.md) §1.2），以 `channelId` 作为第一个字段，路由时只需读第一个字段
- 控制/系统消息的公共前缀各不相同，见 [03a](./03a-payload-definitions.md) §4-6

- 系统消息（90-98）以 `channelId` 开头
- PRESENCE(102) 以 `uid` 开头
- CMD(100) 以 `cmdType` 开头

PacketType 直接驱动解码，无需二次分派：

```kotlin
when (packet.type) {
    PacketType.PING -> handlePing(ctx)
    PacketType.PONG -> handlePong()
    PacketType.SENDACK -> {
        val ack = SendAckPayload.create(packet.payloadBuf)
        handleSendAck(ack)
    }
    PacketType.TEXT -> {
        val payload = TextPayload.create(packet.payloadBuf)
        handleTextMessage(payload)
    }
    PacketType.TYPING -> {
        // TYPING 只需要 channelId，部分解码即可
        val channelId = IProto.readString(packet.payloadBuf)!!
        broadcastTyping(channelId)
    }
    PacketType.REVOKE -> {
        val payload = RevokePayload.create(packet.payloadBuf)
        handleRevoke(payload)
    }
    PacketType.REACTION -> {
        val payload = ReactionPayload.create(packet.payloadBuf)
        handleReaction(payload)
    }
    PacketType.PRESENCE -> {
        val payload = PresencePayload.create(packet.payloadBuf)
        handlePresence(payload)
    }
    PacketType.MEMBER_ADDED -> {
        val payload = MemberAddedPayload.create(packet.payloadBuf)
        handleMemberAdded(payload)
    }
    else -> packet.release()
}
```

**与拍平前的对比**：拍平前需要先解码 RECV 包提取 `messageType`，再根据 `messageType` 二次分派。拍平后 `packet.type` 就是语义类型，解码路径只有一层。

---

## 5. 内存管理与异步处理

### 5.1 引用计数

Packet 持有 `ByteBuf` 引用，通过 `ReferenceCounted` 委托管理生命周期。消费完成后必须释放。

### 5.2 异步处理

```kotlin
class TcpHandler : SimpleChannelInboundHandler<Packet>() {
    override fun channelRead0(ctx: ChannelHandlerContext, packet: Packet) {
        packet.retain()  // I/O 线程 retain

        scope.launch(Dispatchers.IO) {
            try {
                handleMessage(packet)  // 异步处理
            } finally {
                packet.release()       // 业务完成后释放
            }
        }
    }
}
```

将业务处理从 I/O 线程卸载到协程，避免阻塞 Netty EventLoop。

---

## 6. 协议迁移：旧协议 → 新协议

> 当前代码使用旧协议（§6.1），本文档描述的是新协议（§6.2）。迁移是一次性切换，不兼容旧客户端。

### 6.1 旧协议（当前代码）

```
Frame = Version(1B) + FrameType(1B) + RemainingLength(VLQ) + Payload(bytes)
```

- **Version**: 1 字节，当前固定为 1
- **FrameType**: 1 字节枚举，12 种（CONNECT=1, CONNACK=2, ..., CMD=12）
- **RemainingLength**: VLQ 可变长度编码（与 MQTT 相同），每字节 7 位数据 + 1 位继续标记
- **Payload**: JSON 字符串（UTF-8），由 `kotlinx.serialization` 编解码
- **消息模型**: SEND(4) 承载所有业务语义，`MessageType` 枚举做二次分派
- **编解码**: `FrameCodec` 基于 VLQ，payload 用 `Frame.decodePayload<T>()` 做 JSON 反序列化

**旧协议的 FrameType 枚举**：

```
CONNECT(1)  CONNACK(2)  DISCONNECT(3)
SEND(4)     SENDACK(5)  RECV(6)      RECVACK(7)
PING(8)     PONG(9)
SUBSCRIBE(10)  UNSUBSCRIBE(11)  CMD(12)
```

### 6.2 新协议（本文档描述）

```
Packet = PacketType(1B) + Length(4B, big-endian) + Payload(bytes)
```

- **PacketType**: 1 字节，语义拍平，编码范围 0-255
- **Length**: 固定 4 字节大端序，上限 16 MB
- **Payload**: field-order binary，由 `IProto` 接口编解码
- **消息模型**: 无 SEND/RECV 容器，PacketType 直接表达业务语义
- **握手**: CONNECT/CONNACK 独立为握手包（§2.2.1），不走 PacketType

**新协议的 PacketType 编码范围**：

```
0          Reserved
1-9        连接控制 (DISCONNECT, PING, PONG)
10-19      会话管理 (SUBSCRIBE, UNSUBSCRIBE)
20-36      消息（双向统一，TEXT/IMAGE/.../RICH）
80-81      确认应答 (SENDACK, RECVACK)
90-98      系统消息 (CHANNEL_CREATED, ..., CHANNEL_ANNOUNCEMENT)
100-102    控制命令 (CMD, ACK, PRESENCE)
110-255    保留
```

### 6.3 迁移映射

| 旧 FrameType | 新 PacketType | 变化说明 |
|---|---|---|
| CONNECT(1) | 握手包 | 独立为握手协议，不走 PacketType |
| CONNACK(2) | 握手响应 | 同上 |
| DISCONNECT(3) | DISCONNECT(1) | 编码值变更 |
| SEND(4) | TEXT(20)/IMAGE(21)/.../RICH(36) | 拆分为独立语义类型 |
| SENDACK(5) | SENDACK(80) | 编码值变更 |
| RECV(6) | TEXT(20)/IMAGE(21)/...（双向统一） | 不再有独立的 RECV 包类型 |
| RECVACK(7) | RECVACK(81) | 编码值变更 |
| PING(8) | PING(2) | 编码值变更 |
| PONG(9) | PONG(3) | 编码值变更 |
| SUBSCRIBE(10) | SUBSCRIBE(10) | 编码值不变 |
| UNSUBSCRIBE(11) | UNSUBSCRIBE(11) | 编码值不变 |
| CMD(12) | CMD(100) | 编码值变更 |
| （无） | EDIT(31) | 新增 |
| （无） | TYPING(32) | 新增 |
| （无） | REACTION(34) | 新增 |
| （无） | PRESENCE(102) | 新增 |
| （无） | 系统消息 90-98 | 新增 |

### 6.4 迁移策略

迁移为一次性切换，客户端和服务端同步升级。具体步骤：

1. **服务端先行部署**：新服务端同时支持新旧两种协议（通过 Magic 字节区分：旧客户端首字节是 Version=1，新客户端首字节是 Magic 的第一个字节）
2. **客户端强制升级**：新客户端仅支持新协议，旧版本客户端提示升级
3. **过渡期结束后**：服务端移除旧协议支持

过渡期的双协议支持仅通过 `TcpServer` 的 pipeline 配置实现，无需维护两套业务逻辑——新旧协议的差异仅在帧编解码层，业务处理逻辑不变。

---

## 7. 总结

| 设计点 | 方案 | 理由 |

| 设计点 | 方案 | 理由 |
|--------|------|------|
| 包头 | Type(1B) + Length(4B)，固定 5 字节 | Netty 原生 readByte/readInt，无自定义编解码 |
| payload 上限 | 16 MB | 覆盖长文本/富文本，大文件走 HTTP |
| PacketType | 语义拍平 + 编码范围（消息 20-36 双向统一，ACK 80-81，系统 90-98，控制 100-102） | 消除 SEND/RECV 的"万能容器"反模式，每种语义自包含 |
| 负载编码 | 字段顺序二进制（IProto） | Kotlin 单语言无需 IDL，data class 即 schema |
| 零拷贝 | readRetainedSlice + CompositeByteBuf | 减少 GC 压力和内存拷贝 |
| 部分解码 | 按字段顺序读取前 N 个字段 | 控制包无需完整解码 |
| 异步处理 | retain + 协程 + release | 避免阻塞 I/O 线程 |

**核心原则**：
1. **Kotlin 即 Schema**：不需要 IDL 文件，data class 本身定义编解码规则
2. **包头极简**：固定 5 字节，Netty 原生 API 即可处理
3. **握手与包分离**：握手包有独立格式，包交换阶段从 PacketType 开始
4. **语义拍平**：PacketType 直接表达业务语义（TEXT/IMAGE/REVOKE...），无 SEND/RECV 中间层，无 MessageType 二次分派
5. **安全上限**：16 MB payload 硬限制，防止恶意大包攻击

---

## 8. ADR：不采用 Container 批量打包

### 决策

TeamTalk 不采用 MTProto 风格的 Container（msg_container）批量打包协议。

### 背景

MTProto 定义了 `msg_container#73f1f8dc` 结构，允许将多条消息合并为一个 TCP 帧发送，减少小包数量。

### 理由

1. **TCP 本身支持批量写入**：Netty 的 `Channel.write()` 不会立即发送数据到网络，数据缓存在 ChannelOutboundBuffer 中。调用多次 `write()` 后，最后一次 `flush()` 将缓冲区中的所有数据合并为一个 TCP 段发送。TCP 协议栈本身就有 Nagle 算法处理小包合并（或通过 `TCP_NODELAY` 禁用 Nagle 后手动控制 flush 时机）。

2. **MTProto 的 Container 有特殊动机**：MTProto 每条消息都有独立的加密外层（`auth_key_id` + `msg_key` + 加密体），批量打包可以分摊加密开销。TeamTalk 使用 TLS 传输加密（由操作系统/Netty SslHandler 在连接级别处理），没有每包加密开销，因此不需要在应用层做批量打包。

3. **TeamTalk 包头已足够小**：固定 5 字节包头（PacketType 1B + Length 4B），多个小包连续写入的开销极低。相比之下 MTProto 的加密外层每条消息需要约 32 字节，批量打包才有实际收益。

4. **TCP 粘包/拆包已由 Netty 处理**：`PacketDecoder`（`ByteToMessageDecoder` 子类）天然处理了 TCP 粘包和拆包问题，不需要应用层 Container 来管理帧边界。

### 实现建议

服务端在需要连续发送多个包时（如用户上线后批量推送离线消息），应使用：

```kotlin
fun flushBatch(ctx: ChannelHandlerContext, packets: List<Packet>) {
    packets.forEach { packet ->
        ctx.write(packet)    // 写入缓冲区，不立即发送
    }
    ctx.flush()              // 一次性发送所有缓冲数据
}
```

这种 write-without-flush + batch flush 的模式已经实现了小包合并的效果，无需引入额外的 Container 协议层。
