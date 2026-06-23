# 消息类型与消息体

> TeamTalk 支持 15 种消息类型。本文记录消息模型的设计决策：为什么用 sealed interface、
> 为什么 flags 用位标记、容器模式 vs 扁平模式的权衡。

## 消息类型枚举

| Code | 类型 | 说明 | Body 类 |
|------|------|------|---------|
| 1 | TEXT | 纯文本消息 | `TextBody(text)` |
| 2 | IMAGE | 图片消息 | `ImageBody(url, width, height, size)` |
| 3 | VOICE | 语音消息 | `VoiceBody(url, duration, size)` |
| 4 | VIDEO | 视频消息 | `VideoBody(url, size)` |
| 5 | FILE | 文件消息 | `FileBody(url, fileName, size)` |
| 6 | REPLY | 回复消息（引用原消息） | `ReplyBody(text, quoteMessage)` |
| 7 | FORWARD | 转发消息 | `ForwardBody(text, originMessage)` |
| 8 | REVOKE | 撤回消息 | `RevokeBody(revokeMessage)` |
| 9 | SYSTEM | 系统消息 | `SystemBody(text)` |
| 10 | TYPING | 输入状态（非持久化） | `TypingBody(chatId)` |
| 11 | PRESENCE | 在线状态 | `PresenceBody(uid, online)` |
| 12 | READ_RECEIPT | 已读回执 | `ReadReceiptBody(chatId, readSeq)` |
| 13 | GROUP_NOTICE | 群公告 | `GroupNoticeBody(chatId, notice)` |
| 14 | CONTACT_CARD | 名片消息 | `ContactCardBody(user)` |
| 15 | LOCATION | 位置消息 | `LocationBody(lat, lng, address)` |

---

## 为什么 MessageBody 用 sealed interface

`MessageBody` 是 sealed interface，每种类型有对应 data class 实现。

**为什么 sealed 而非 open class 继承**：
1. **穷举性**：Kotlin 编译器在 `when(body)` 时强制穷举所有子类型，新增类型时编译器报错指出遗漏的分支。open class 没有 this 保证。
2. **序列化安全**：sealed interface 的所有实现必须在同一编译单元，与 `MessageBodyRegistry` 的静态注册表对齐——注册表里不存在的类型编译期可见。
3. **无运行时反射**：每个 Body 类的 companion object 实现 `IProtoReader<T>`，编解码通过编译期已知的类型分发，不需要反射或类名查找。

---

## 容器模式 vs 扁平模式

### V1 曾经的决策：扁平化

V1 设计文档 (`03-binary-encoding.md` §2.2) 曾强烈主张**每种消息类型一个独立 PacketType**（TEXT=20, IMAGE=21, REVOKE=30...），消除 MESSAGE 容器。理由是扁平化可以按类型部分解码。

### 当前实现：容器回归

当前代码回到了**容器模式**：单一 `PacketType.MESSAGE(6)` + 内部 1 字节 `messageType` 二级分发。

### 为什么回归容器

1. **可扩展性**：新增消息类型只需追加 `MessageType` 枚举 + `MessageBody` 子类 + 注册表条目——不改变 wire format，不分配新 PacketType。
2. **注册表分发**：`MessageBodyRegistry` 是 `Map<MessageType, IProtoReader>`，immutable。新增类型加一行即可，无需修改分发逻辑。
3. **统一信封**：所有消息共享同一信封字段（chatId/senderUid/timestamp/flags），容器模式让这些字段只定义一次。

### 代价

失去了按类型部分解码的能力。但实践中 Message 总是被完整解码（UI 需要所有字段），所以这个代价不实际。

---

## flags 位标记 vs 独立字段

### 设计

```kotlin
val flags: Int = 0
// bit 0: FLAG_REVOKED = 1   消息已撤回
// bit 1: FLAG_EDITED = 2    消息已编辑
// bit 2: FLAG_FORWARDED = 4 消息是转发来的
```

### 为什么用位标记而不是独立 Boolean 字段

1. **协议不膨胀**：3 个状态共享 1 个 VarInt 字段（通常 1 字节），而非 3 个 Boolean（3 字节）。未来追加 flag 不增加协议字段。
2. **不改 messageType**：撤回/编辑是消息**状态变更**，不是消息**类型变更**。一条文本消息编辑后还是 TEXT 类型，只是多了 FLAG_EDITED。用独立 messageType 表示"已编辑的文本"会导致类型组合爆炸。
3. **原子更新**：服务端 `message.flags = flags or FLAG_REVOKED` 一行代码改状态，客户端收到 NOTIFY 后检查 flags 决定渲染。

### 渲染时检查

```kotlin
if (flags and FLAG_REVOKED != 0) → 渲染居中灰色"消息已撤回"
if (flags and FLAG_EDITED != 0) → 内容后追加"(已编辑)"
if (flags and FLAG_FORWARDED != 0) → 显示转发来源标记
```

---

## MessageBody 多态编解码

### 注册表

```kotlin
object MessageBodyRegistry {
    private val readers: Map<MessageType, IProtoReader<out MessageBody>> = mapOf(
        MessageType.TEXT to TextBody,
        MessageType.IMAGE to ImageBody,
        // ...
    )

    fun decode(messageType: MessageType?, buf: PacketBuffer): MessageBody? {
        if (messageType == null) return null
        val reader = readers[messageType] ?: return null  // 未知类型返回 null
        return reader.readFrom(buf)
    }
}
```

### 优雅降级

`decode` 遇到未知 messageType 返回 `null`。旧客户端收到新消息类型时：
- 仍能渲染信封（chatId、senderUid、timestamp）
- `body = null`，内容无法显示
- 不崩溃，不丢消息

这是前向兼容的关键设计：**新增消息类型不破坏旧客户端**。

### Message 的 wire format

```
[chatId][clientMsgId][serverSeq][senderUid][messageType(1B)][timestamp][flags][hasBody(1B)][body bytes]
```

`hasBody` 是 1 字节标志（0=无 body，1=有 body）。即使已知类型，body 也可能为 null（如旧客户端收到新类型），所以需要独立的 hasBody 标志。

---

## 消息操作

通过长按（Android）/右键（Desktop）消息弹出操作菜单：

| 操作 | 条件 | flags 变化 | 实现 |
|------|------|-----------|------|
| 回复 | 任何消息 | 无 | 设置 `replyingTo`，发送时构建 ReplyBody |
| 编辑 | 自己发的文本消息 | 设置 FLAG_EDITED | 预填输入框，调用 `editMessage` |
| 撤回 | 自己发的消息 | 设置 FLAG_REVOKED | 通知群成员，渲染为系统提示 |
| 转发 | 任何消息 | 设置 FLAG_FORWARDED | 打开转发选择页 |
| 复制 | 文本消息 | 无 | 复制到剪贴板 |

---

## 图片气泡尺寸策略

参考 Signal 的 fit-inside 策略：

- 最大盒子：240dp × 320dp（竖图友好，非正方形）
- 最小边：120dp（保证可点击）
- 算法：等比缩放保留宽高比，取宽高缩放比的较小值

此前用 200×200 正方形盒子，对竖图不友好。参考 Signal 后改为长方形盒子。

**相关代码**：`shared/.../model/Message.kt`（flags 定义 + wire format）、`shared/.../body/MessageBodyRegistry.kt`（注册表）、`shared/.../body/*Body.kt`（各 Body 类）、`app/.../ui/component/MessageBodyRenderer.kt`（渲染）、`app/.../ui/screen/ChatScreen.kt`（长按菜单）
