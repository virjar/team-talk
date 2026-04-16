# 内容消息定义

> 承载用户可见内容的消息类型。本文档从 [01-message-model.md](./01-message-model.md) 拆分而来。
>
> PacketType 编码方案：消息 20-36（双向统一）。详见 [03-binary-encoding.md](./03-binary-encoding.md) §2.2。
>
> **Payload 字段定义**：每种 PacketType 的 payload 字段见 [03a-payload-definitions.md](./03a-payload-definitions.md)（单一真相源）。本文档仅提供 Signal/Telegram 对比分析和设计决策。

---

## 3.1 文本消息 — PacketType.TEXT(20)

**Signal**：`DataMessage.body` (string)，纯文本。格式化（加粗/斜体）通过 `StyleSpan` 在 Spannable 中表达，不走协议层。

**Telegram**：`message.message` (string)，配合 `entities: Vector<MessageEntity>` 表达格式化：

```
MessageEntity 类型:
  MessageEntityBold      — 加粗 (offset + length)
  MessageEntityItalic    — 斜体
  MessageEntityCode      — 等宽
  MessageEntityPre       — 代码块 (含 language)
  MessageEntityTextUrl   — 隐藏链接
  MessageEntityMention   — @提及
  MessageEntityBotCommand — /命令
  MessageEntitySpoiler   — 剧透/折叠
  MessageEntityCustomEmoji — 自定义表情
  MessageEntityBlockquote — 引用块
  MessageEntityStrike     — 删除线
  MessageEntityUnderline  — 下划线
```

**TeamTalk 设计**：

```
TextPayload(20):  [MessagePrefix] + text:String + mentionUids:List<String> + flags:Byte
```

> 完整字段定义见 [03a-payload-definitions.md](./03a-payload-definitions.md) §2.1。

**扩展建议**（P4 锦上添花）：
- `entities: List<TextEntity>?` — 格式化实体（参考 Telegram），支持 bold/italic/code/pre/mention/link
- `linkPreview: LinkPreview?` — 链接预览（异步抓取，通过 CMD 包更新）

**优先级**：中。纯文本 + @提及 已满足基本需求，格式化是非刚需。

---

## 3.2 图片消息 — PacketType.IMAGE(21)

**Signal**：`AttachmentPointer` 统一承载所有媒体类型，通过 `contentType` 区分。图片特有字段：`width`、`height`、`blurHash`（缩略图哈希）、`caption`（描述）。

**Telegram**：`MessageMediaPhoto`，关联 `Photo` 对象（含多尺寸 `PhotoSize`：缩略图、中图、原图），支持 `caption` + `spoiler`（模糊遮罩）、`toggled_coverage`（裁剪）。

```
Photo
  ├── id: int64
  ├── sizes: [PhotoSize]     // 多种尺寸
  │     ├── type: string     // "s"=小图, "m"=中图, "x"=大图, "w"=原图
  │     ├── width/height: int
  │     └── size: int        // 文件大小
  ├── videoSizes: [VideoSize] // 短视频缩略图（循环播放的小视频）
  └── dc_id: int             // 数据中心
```

**TeamTalk 设计**：

```
ImagePayload(21):  [MessagePrefix] + url + width + height + size + thumbnailUrl? + caption? + flags
```

> 完整字段定义见 [03a-payload-definitions.md](./03a-payload-definitions.md) §2.2。

**扩展建议**：
- `thumbnailUrl`：缩略图地址（避免加载原图）— 已纳入 03a 定义
- `caption`：图片描述（参考 Signal/Telegram 设计）— 已纳入 03a 定义

**优先级**：中。`thumbnailUrl` 是最实用的改进。

---

## 3.3 视频消息 — PacketType.VIDEO(23)

**Signal**：与图片共用 `AttachmentPointer`，`contentType = "video/mp4"`，特有字段：`width`、`height`、`caption`，无 `duration`（客户端本地解析）。

**Telegram**：`MessageMediaDocument` + `Document`（含 `videoAttributes`），提供丰富的元数据：

```
Document
  ├── id: int64
  ├── mimeType: string
  ├── size: int
  ├── thumbs: [PhotoSize]     // 缩略图
  ├── videoAttributes:
  │     ├── duration: int         // 秒
  │     ├── width/height: int
  │     └── thumb: PhotoSize      // 视频封面
  └── dc_id: int
```

**TeamTalk 设计**：

```
VideoPayload(23):  [MessagePrefix] + url + width + height + size + duration + coverUrl? + flags
```

> 完整字段定义见 [03a-payload-definitions.md](./03a-payload-definitions.md) §2.4。

**评估**：当前字段已足够完备，视频封面 `coverUrl` 比 Signal 做得更好。无需扩展。

---

## 3.4 语音消息 — PacketType.VOICE(22)

**Signal**：使用 `AttachmentPointer` + `flags = VOICE_MESSAGE` 标记，Opus 编码，客户端本地生成波形图。

**Telegram**：`Document` + `audioAttributes`，包含 `duration` 和 `waveform`（波形数据，uint5 编码的二进制数组），支持 2x 播放。

```
Document (voice message)
  ├── mimeType: "audio/ogg"
  ├── audioAttributes:
  │     ├── duration: int
  │     ├── waveform: bytes       // 波形数据（每个采样点 5 bit）
  │     └── flags: VOICE_MESSAGE
  └── thumbs: [PhotoSize]         // 波形图（服务端渲染）
```

**TeamTalk 设计**：

```
VoicePayload(22):  [MessagePrefix] + url + duration + size + waveform? + flags
```

> 完整字段定义见 [03a-payload-definitions.md](./03a-payload-definitions.md) §2.3。

**设计说明**：
- `waveform`：归一化波形 ByteArray [0-255]，客户端录制时生成，服务端只存不渲染
- 已纳入 03a 定义，无需额外扩展

**优先级**：高（P1 功能）。语音消息是 IM 基础消息类型。

---

## 3.5 文件消息 — PacketType.FILE(24)

**Signal**：`AttachmentPointer`，字段包含 `contentType`、`fileName`、`size`、`caption`。无特殊文件类型处理。

**Telegram**：`Document` 对象，通用文件容器：

```
Document
  ├── id: int64
  ├── fileName: string
  ├── mimeType: string
  ├── size: int
  ├── thumbs: [PhotoSize]      // 文件缩略图（如果有）
  └── attributes: [DocumentAttribute]
        ├── fileName: string
        ├── fileSize: int
        ├── mimeType: string
        ├── imageSize: int×int  // 如果是图片
        ├── animated: bool      // 如果是 GIF
        ├── duration: int       // 如果是音视频
        └── stickers: ...       // 如果是贴纸
```

**TeamTalk 设计**：

```
FilePayload(24):  [MessagePrefix] + url + fileName + fileSize + mimeType? + thumbnailUrl? + flags
```

> 完整字段定义见 [03a-payload-definitions.md](./03a-payload-definitions.md) §2.5。

**扩展说明**：
- `mimeType`：MIME 类型，用于选择预览方式和文件图标 — 已纳入 03a 定义
- `thumbnailUrl`：缩略图（图片/PDF 预览）— 已纳入 03a 定义

**优先级**：低。`mimeType` 可帮助客户端选择合适的文件图标和预览方式。

---

## 3.6 位置消息 — PacketType.LOCATION(25)

**Signal**：`DataMessage` 无独立位置消息，通过 `AttachmentPointer` 的 `contentType = "location"` 发送，字段在 `sharedContacts` 中嵌套。

**Telegram**：两种位置类型：
- `MessageMediaGeo`：静态位置（经纬度）
- `MessageMediaGeoLive`：实时位置（周期性更新，含 heading、proximityAlertRadius、period）

```
GeoPoint
  ├── lat: double
  ├── lng: double
  └── accuracyRadius: int    // 精度半径（米）

GeoLive (实时位置)
  ├── period: int            // 更新周期（秒）
  ├── heading: int           // 方向角度
  └── proximityNotificationRadius: int  // 临近提醒半径
```

**TeamTalk 设计**：

```
LocationPayload(25):  [MessagePrefix] + latitude:Int + longitude:Int + title? + address? + flags
```

> 完整字段定义见 [03a-payload-definitions.md](./03a-payload-definitions.md) §2.6。

**设计说明**：
- 经纬度使用固定 4 字节 Int（原始值 x 1_000_000 取整，精度 ~0.11m），非 VarInt（因为可为负数）
- 实时位置（远期）：可扩展 `LiveLocationPayload`，包含 heading、period、expiresAt

**优先级**：低。办公 IM 中位置消息使用频率较低。

---

## 3.7 名片消息 — PacketType.CARD(26)

**Signal**：`DataMessage.contact`，`SharedContact` 结构：

```
SharedContact
  ├── name: ContactName
  │     ├── givenName: string
  │     ├── familyName: string
  │     └── middleName: string
  ├── phones: [Phone]
  │     ├── type: HOME | WORK | MOBILE | CUSTOM
  │     └── value: string
  ├── emails: [Email]
  └── avatar: AttachmentPointer  // 头像
```

**Telegram**：`MessageMediaContact`：

```
MessageMediaContact
  ├── phoneNumber: string
  ├── firstName: string
  ├── lastName: string
  ├── vcard: string          // vCard 格式的完整联系人信息
  └── userId: int            // 如果是已注册用户，关联 UID
```

**TeamTalk 设计**：

```
CardPayload(26):  [MessagePrefix] + uid:String + name:String + avatar? + phone? + flags
```

> 完整字段定义见 [03a-payload-definitions.md](./03a-payload-definitions.md) §2.7。

**设计说明**：TeamTalk 是办公 IM，名片分享的核心是「用户 UID」——接收方可以直接点击添加好友或发起聊天。不需要像 Telegram 那样支持 vCard 格式。

**优先级**：低。功能已有占位，可后续完善。

---

## 3.8 贴纸消息 — PacketType.STICKER(33)

**Signal**：`DataMessage.sticker`：

```
Sticker
  ├── packId: bytes           // 贴纸包 ID
  ├── packKey: bytes          // 贴纸包加密密钥
  ├── stickerId: int          // 贴纸包内序号
  ├── emoji: string           // 对应的 emoji（用于输入提示）
  └── data: AttachmentPointer // 贴纸图片
```

**Telegram**：`Document` + `DocumentAttributeSticker`：

```
DocumentAttributeSticker
  ├── alt: string             // 对应的 emoji
  ├── stickerset: InputStickerSet  // 贴纸包引用
  │     ├── shortName: string     // 贴纸包短名称
  │     └── id: int64
  └── mask: bool              // 是否是面具贴纸
```

**TeamTalk 设计**：

```
StickerPayload(33):  [MessagePrefix] + stickerId + packId? + url + emoji? + width + height + flags
```

> 完整字段定义见 [03a-payload-definitions.md](./03a-payload-definitions.md) §2.8。

**设计说明**：
- `url`：贴纸图片地址（webp 格式）
- `emoji`：对应的 emoji（用于输入提示）
- `packId`：贴纸包 ID（可选，支持贴纸包管理）

**优先级**：低（P4 功能）。非核心功能，但消息模型需要预留此类型的定义。

---

## 3.9 链接预览 — 附加结构而非独立消息类型

**Signal**：`DataMessage.preview[]`，附加在文本消息上：

```
Preview
  ├── url: string             // 目标链接
  ├── title: string           // 页面标题
  ├── description: string     // 页面描述
  ├── image: AttachmentPointer // 预览图
  └── date: long              // 页面发布时间
```

**Telegram**：`MessageMediaWebPage`，独立媒体类型：

```
WebPage
  ├── url: string
  ├── displayUrl: string
  ├── type: string            // "article" | "photo" | "video" | ...
  ├── siteName: string
  ├── title: string
  ├── description: string
  ├── photo: Photo
  ├── embedUrl: string        // 嵌入式视频 URL
  ├── embedType: string
  └── duration: int
```

**建议**：链接预览不作为独立消息类型，也不在 TextPayload 的二进制 payload 中编码。而是：
1. 客户端发送文本消息后，服务端异步抓取链接元数据
2. 通过 CMD 包推送 `link_preview` 命令给客户端
3. 客户端在 UI 层将链接预览附加到对应消息上

> 链接预览字段不在 [03a-payload-definitions.md](./03a-payload-definitions.md) 中定义，因为它不走二进制 payload，而走 CMD JSON payload。

**设计理由**：链接预览是异步生成的附属信息，不应阻塞消息发送流程。

**优先级**：低。

---

## 3.10 交互式消息 — PacketType.INTERACTIVE(35)

**Signal**：无机器人/交互式消息体系。Signal 定位隐私通讯，不支持 Bot。

**Telegram**：Bot 是 Telegram 生态的核心组成部分：

```
InlineKeyboardMarkup
  ├── inlineKeyboard: [[InlineKeyboardButton]]
  │     ├── text: string            // 按钮文字
  │     ├── url: string?            // 点击打开 URL
  │     ├── callbackData: string?   // 点击触发回调（≤64 bytes）
  │     ├── switchInlineQuery: string? // 切换到内联模式
  │     └── pay: bool               // 支付按钮
  └── Bot 通过 Bot API 发送，用户按钮点击触发 callback_query
```

Telegram Bot 还支持：Web App（内嵌小程序）、Inline Mode（@bot query 在任意聊天中调用）、Game（小游戏）。

**钉钉/飞书**（办公 IM 参考）：机器人消息以"卡片消息"形式呈现，支持 Markdown 正文 + 按钮组 + 表单输入，是办公场景中使用频率最高的 Bot 交互方式。

**TeamTalk 设计**：

采用**虚拟用户 + 独立 PacketType** 方案：

- **Bot 作为虚拟用户**：用户表增加 `userType` 字段（0=普通用户, 1=机器人），机器人拥有独立 UID，可出现在好友列表和群成员中。与机器人的对话即 1:1 频道（channelType=1），与普通用户聊天无差别。
- **交互式消息独立 PacketType**：机器人可发送包含按钮、卡片的交互式消息，使用 INTERACTIVE(35) 承载。

> 完整字段定义见 [03a-payload-definitions.md](./03a-payload-definitions.md) §2.9。

**设计理由**：

1. **为什么 Bot 不是独立的 PacketType 体系**：Bot 发送的大部分消息（文本通知、图片、文件）与普通消息无差别，复用 TEXT/IMAGE/FILE 等现有类型即可。只有需要交互按钮的卡片消息才使用 INTERACTIVE。
2. **为什么交互式卡片是独立 PacketType 而非 TEXT 扩展**：交互式卡片的数据结构（标题 + 正文 + 按钮列表 + 回调）与任何现有 payload 都不兼容，强行塞入 TEXT 的 flags/扩展字段会导致解析逻辑复杂化。
3. **为什么按钮点击走 CMD 而非新 PacketType**：按钮点击是低频操作，回调数据结构多变（不同 Bot 定义不同的 callback schema），CMD 的 JSON payload 天然适合这种灵活性。

**优先级**：中（P2-P3）。办公 IM 的机器人生态是核心竞争力之一，但需要先完成基础功能（本地数据库、通知等）后再推进。

---

## 3.11 富文本消息 — PacketType.RICH(36)

> 这是用户消息的终极形态——文字、图片、@提醒自由穿插组合。当前阶段记录设计意图，不立即实现。

**Signal**：`DataMessage` 同时包含 `body`（文本）和 `attachments[]`（多个附件）。文字和媒体在同一消息中共存，但渲染是文字在上、图片在下，**不支持图文穿插**。

**Telegram**：单消息 = 单媒体 + 一段 caption。多图通过 album（多条消息视觉分组）实现，每张图各自独立 caption。**不支持图文穿插**。

**钉钉/飞书**（办公 IM 参考）：富文本消息采用**分段模型**，消息由有序 segment 列表组成：

```
钉钉 RichTextMessage:
  └── content: [
        { type: "text", text: "V1 方案：" },
        { type: "image", url: "..." },
        { type: "text", text: "V2 方案：" },
        { type: "image", url: "..." },
        { type: "text", text: "对比看看 " },
        { type: "at", uid: "xxx", name: "李四" }
      ]
```

钉钉/飞书中这是最自然的消息形态——用户在输入面板中随意输入文字、粘贴图片、@某人，发送后渲染为图文穿插的一条消息。

**TeamTalk 设计**：

采用**分段模型**——消息 = Segment 有序列表。每个 Segment 是一个类型化的内容片段，客户端按顺序渲染。

```
Segment (union type，首字节 segmentType 决定):
  ├── TEXT(0):   text:String                     // 普通文字
  ├── IMAGE(1):  url + width + height + size + thumbnailUrl?  // 内联图片
  ├── AT(2):     uid + name                      // @提醒（渲染为高亮可点击）
  ├── VIDEO(3):  url + width + height + size + duration + coverUrl?  // 远期
  ├── FILE(4):   url + fileName + fileSize + mimeType?         // 远期
  └── LINK(5):   url + title? + description? + imageUrl?       // 远期
```

> 完整 payload 定义见 [03a-payload-definitions.md](./03a-payload-definitions.md) §2.10。

**渲染规则**：

- **内联段**（TEXT、AT）：不换行，连续内联段拼接为一行文字
- **块级段**（IMAGE、VIDEO、FILE、LINK）：独占一行，上下有间距

示例：`[TEXT("V1：")][IMAGE(url)][TEXT("V2：")][IMAGE(url)][TEXT("对比 @")][AT(uid,"李四")]` 渲染为：

```
V1：
[图片1]
V2：
[图片2]
对比 @李四
```

**演进路线**：

| 阶段 | 策略 | 说明 |
|------|------|------|
| **短期（Route A）** | RICH 作为补充类型 | 简单消息仍用 TEXT/IMAGE 等独立 PacketType。当用户混合多种内容时（如输入面板中文字+图片+@提醒穿插），自动使用 RICH(36) |
| **长期（Route B）** | RICH 统一所有用户消息 | 所有用户消息都是 segment 列表。纯文本 = `[TEXT("ok")]`，纯图片 = `[IMAGE(url)]`。TEXT/IMAGE 等 PacketType 可保留为轻量优化或废弃 |

Route A → Route B 的迁移不涉及协议层兼容（PacketType 编码不变），仅涉及客户端发送逻辑的切换（"何时发 RICH"的策略变化）。当前原型阶段直接实现 Route A 即可。

**设计理由**：

1. **为什么是分段模型而非 Markdown**：Markdown 是纯文本+渲染约定，无法精确表达图片尺寸、@提醒的 UID 映射等结构化数据。分段模型在二进制编码中更自然，且渲染无歧义。
2. **为什么 Segment 用 union type 而非独立子消息**：图文穿插需要在同一个消息气泡内渲染。如果拆成多条独立消息，无法保证原子性和视觉连贯性。
3. **为什么 mentionUids 也在顶层**：AT 段嵌在 segments 内部，服务端需要扫描所有 segments 才能提取被提醒的用户。顶层 mentionUids 是冗余但实用的汇总字段，服务端直接据此发通知。

**优先级**：中（P2）。这是办公 IM 输入体验的核心能力，但需要先完成基础功能。初始实现只需 TEXT(0) + IMAGE(1) + AT(2) 三种 segment 类型。