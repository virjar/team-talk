# 共享 SDK（shared 模块）

> shared 模块是客户端和服务端共用的核心 SDK，包含数据模型、协议枚举、ImClient 和日志抽象。
> 零 UI 依赖，零平台依赖（纯 commonMain）。

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 数据模型（model）](#2-数据模型model)
- [3. 消息体（body）](#3-消息体body)
- [4. 协议枚举（protocol）](#4-协议枚举protocol)
- [5. ImClient 连接客户端](#5-imclient-连接客户端)
- [6. TkLogger 日志抽象](#6-tklogger-日志抽象)

---

## 1. 模块定位

```
shared/src/commonMain/kotlin/com/virjar/tk/
├── model/         # 数据模型（User/Message/Chat/Conversation/...）
├── body/          # 消息体子类型（TextBody/ImageBody/VoiceBody/...）
├── protocol/      # 协议枚举 + ProtoCodec 编解码
│   └── payload/   # 包载荷结构（InvokePayload/ResponsePayload/...）
├── client/        # ImClient（TCP 客户端，供 app 模块使用）
└── log/           # TkLogger 日志抽象接口
```

### 共享关系

```
     ┌─────────┐
     │  shared  │  ← 协议 + 模型 + ImClient + TkLogger
     └────┬─────┘
          │
    ┌─────┴──────┐
    │            │
┌───▼───┐  ┌────▼─────┐
│  app   │  │  server  │
│(client)│  │          │
└────────┘  └──────────┘
```

- **app 模块**：使用 shared 的 ImClient（TCP 客户端），注入 AppLogTkLogger
- **server 模块**：使用 shared 的协议枚举和数据模型，注入 Slf4jTkLogger

---

## 2. 数据模型（model）

所有传输模型都是 Kotlin data class，实现 `IProto` 接口：

```kotlin
data class User(
    val uid: String,
    val username: String,
    val name: String,
    val avatar: String?,
    val phone: String?,
    val sex: Int,
    val role: Int,
    val status: Int,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(uid)
        buf.writeString(username)
        buf.writeString(name)
        buf.writeString(avatar)
        buf.writeString(phone)
        buf.writeVarInt(sex)
        buf.writeVarInt(role)
        buf.writeVarInt(status)
    }

    companion object : IProtoReader<User> {
        override fun readFrom(buf: PacketBuffer) = User(
            uid = buf.readString()!!,
            username = buf.readString()!!,
            name = buf.readString()!!,
            avatar = buf.readString(),
            phone = buf.readString(),
            sex = buf.readVarInt(),
            role = buf.readVarInt(),
            status = buf.readVarInt(),
        )
    }
}
```

### 核心模型清单

| 模型 | 说明 | 关键字段 |
|------|------|---------|
| `User` | 用户 | uid, username, name, avatar, phone, sex, role, status |
| `Message` | 消息 | chatId, clientMsgId, serverSeq, senderUid, messageType, body |
| `Chat` | 聊天/群组 | chatId, chatType, name, avatar, notice, creatorUid |
| `Conversation` | 会话 | chatId, chatName, lastSeq, readSeq, unreadCount, isPinned, draft |
| `Contact` | 联系人 | uid, friendUid, remark, status |
| `GroupMember` | 群成员 | chatId, uid, role, nickname, status |
| `Device` | 设备 | deviceId, deviceName, deviceModel, lastLogin |

---

## 3. 消息体（body）

`MessageBody` 是 sealed interface，每种消息类型有对应实现：

```kotlin
sealed interface MessageBody

data class TextBody(val text: String) : MessageBody
data class ImageBody(val url: String, val width: Int, val height: Int, val size: Long) : MessageBody
data class VoiceBody(val url: String, val duration: Int, val size: Long) : MessageBody
data class FileBody(val url: String, val fileName: String, val size: Long) : MessageBody
data class VideoBody(val url: String, val size: Long) : MessageBody
data class ReplyBody(val text: String, val quoteMessage: ...) : MessageBody
data class RevokeBody(val revokeMessage: ...) : MessageBody
```

通过 `MessageBodyRegistry` 按 messageType 注册编解码器，支持多态编解码。

---

## 4. 协议枚举（protocol）

```kotlin
enum class ServiceId(val id: Int) {
    AUTH, USER, CONTACT, CHAT, MESSAGE, CONVERSATION, DEVICE, CLIENT_LOG
}

enum class UserMethod(val id: Int) { REGISTER, LOGIN, SEARCH, GET_PROFILE, ... }
enum class ChatMethod(val id: Int) { CREATE, GET, UPDATE, GET_MEMBERS, ... }
enum class MessageMethod(val id: Int) { SEND, GET_HISTORY, REVOKE, EDIT, ... }
// ...

enum class NotifyType(val id: Int) {
    MESSAGE_RECV, MESSAGE_REVOKE, CONVERSATION_UPDATED, CONTACT_UPDATED, ...
}
```

### ProtoCodec

编解码核心，详见 [01-protocol/README.md](../01-protocol/README.md#8-编解码protocodec)。

---

## 5. ImClient 连接客户端

ImClient 是客户端使用的 TCP 连接管理器（服务端不用它，服务端用 Netty 原生 pipeline）。

详细文档见 [03-client/README.md 第四章](../03-client/README.md#4-连接管理imclient)。

---

## 6. TkLogger 日志抽象

shared 模块的日志通过 TkLogger 接口抽象，由宿主注入实现：

```kotlin
// shared/.../log/TkLogger.kt
interface TkLogger {
    fun trace(msg: String)
    fun fault(msg: String, t: Throwable? = null)
}

object TkLoggerFactory {
    fun install(provider: (String) -> TkLogger)
    fun get(name: String): TkLogger
}
```

### 注入方式

```kotlin
// Server 启动时
TkLoggerFactory.install { name -> Slf4jTkLogger(LoggerFactory.getLogger(name)) }

// Client（Desktop/Android）启动时
TkLoggerFactory.install { name -> AppLogTkLogger(name) }
```

### 为什么用注入而非 expect/actual

- Server 和 Client 的注入逻辑在各自模块，shared/commonMain 零平台依赖
- 编译期不需要 actual 声明，更干净
- 未注入时返回 NoopLogger（静默丢弃），安全

详细日志体系文档见 [05-logging/](../05-logging/)。
