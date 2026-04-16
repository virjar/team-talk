# 聊天 UI 架构对比分析

> Signal / Telegram / TeamTalk 消息渲染、输入、操作的架构设计对比

---

## 1. 核心问题

IM 聊天界面是整个应用中复杂度最高的部分，集中体现在：

| 复杂度维度 | 说明 |
|-----------|------|
| **消息类型多样性** | 文本、图片、语音、视频、文件、位置、名片、回复、转发、合并转发、系统通知、撤回……每种类型有独立的渲染逻辑和交互行为 |
| **输入复合性** | 输入框不仅要处理文本，还要管理回复预览、编辑模式、附件选择、语音录制、表情/贴纸、@提及、格式化等子状态 |
| **操作多样性** | 长按菜单、多选、回复、编辑、转发、复制、删除、撤回——这些操作需要与消息类型组合，形成矩阵 |
| **平台差异** | Desktop 的鼠标（精确点击、右键菜单、拖拽、键盘快捷键、多选快捷键）vs Mobile 的触摸（长按、滑动手势、全屏占满） |
| **数据结构影响** | 消息的数据模型设计直接决定渲染分发、操作可用性、输入预览的实现方式 |

以下分析 Signal Android、Telegram Android、Telegram Desktop 三个客户端如何解决这些问题，并总结对 TeamTalk 的启发。

---

## 2. Signal Android 架构分析

### 2.1 总体架构

Signal Android 的核心聊天界面采用 **传统 View 系统 + PagingMappingAdapter** 的架构，尚未将 Compose 引入主聊天界面（Compose 仅用于媒体发送、注册等外围模块）。

```
ConversationFragment.kt (控制器，200KB+)
    ↓
ConversationAdapterV2 (适配器，基于 PagingMappingAdapter)
    ↓ registerFactory()
V2ConversationItemViewHolder<Model> (ViewHolder 基类)
    ├── V2ConversationItemTextOnlyViewHolder  — 文本消息
    └── V2ConversationItemMediaViewHolder     — 媒体消息
```

### 2.2 消息类型分发——数据模型驱动

Signal 的消息类型分发采用 **数据模型分类 + 工厂注册** 的模式：

```
MappingModel (接口)
 ├── ThreadHeader              — 会话头部
 ├── ConversationUpdate        — 系统消息（"XX 加入了群聊"）
 ├── OutgoingTextOnly          — 发送的纯文本
 ├── IncomingTextOnly          — 接收的纯文本
 ├── OutgoingMedia             — 发送的媒体消息
 └── IncomingMedia             — 接收的媒体消息
```

**关键设计**：Signal 将"方向"（发送/接收）和"内容类型"（文本/媒体）作为两个独立的分类维度，组合出 4 种数据模型。适配器通过 `registerFactory` 将每种模型映射到对应的 ViewHolder：

```kotlin
registerFactory(OutgoingTextOnly::class.java) { parent ->
    val view = CachedInflater.from(parent.context)
        .inflate(R.layout.v2_conversation_item_text_only_outgoing, parent, false)
    V2ConversationItemTextOnlyViewHolder(binding, this)
}
```

**优点**：类型安全（编译时检查）、易扩展（新增类型只需添加 Model + Factory）、RecyclerView 缓存优化（不同类型使用不同的 ViewHolder 池）。

### 2.3 气泡渲染——组合模式

气泡内部的渲染采用 **组合模式**，将不同功能拆分为独立的组件：

```
ConversationItemBodyBubble (核心容器，LinearLayout)
 ├── QuoteView               — 引用/回复预览
 ├── [媒体内容区域]           — 图片/视频/音频/文件
 ├── [文本内容]               — 消息正文
 ├── LinkPreviewView          — 链接预览
 ├── SharedContactView        — 联系人名片
 ├── DocumentView             — 文件附件
 ├── AudioView                — 音频播放器
 └── ConversationItemFooter   — 底部（时间、状态）
```

**关键委托类**：
- `V2ConversationItemShape` — 处理气泡形状和圆角
- `V2ConversationItemTheme` — 处理颜色和主题
- `V2ConversationItemLayout` — 基础布局

Shape 和 Theme 作为独立的委托从 ViewHolder 中抽离，使得气泡的视觉表现可以在不修改渲染逻辑的情况下独立调整。

### 2.4 消息操作——MultiselectPart 系统

Signal 的消息操作设计了一个 **可操作部件（MultiselectPart）** 系统，将一条消息拆分为多个可独立操作的部分：

```kotlin
sealed class MultiselectPart {
    data class Message(val conversationMessage)     — 整条消息
    data class Text(val conversationMessage)         — 文本部分
    data class Attachments(val conversationMessage)  — 附件部分
    data class Update(val conversationMessage)       — 更新部分
    data class CollapsedHead(val conversationMessage) — 折叠头部
}
```

这意味着用户可以单独选择一条消息中的文本部分或附件部分进行转发，而不仅仅是整条消息。

**事件分发机制**：
```kotlin
binding.body.setOnClickListener {
    if (conversationContext.selectedItems.isEmpty()) {
        passthroughClickListener.onClick(it)       // 正常点击
    } else {
        conversationContext.clickListener.onItemClick(
            getMultiselectPartForLatestTouch()      // 多选模式
        )
    }
}
```

通过 `GestureDetector` 同时支持单击、双击、长按三种操作。

### 2.5 输入面板——InputPanel 组合架构

Signal 的 `InputPanel`（34KB）是一个组合了多个子组件的 `ConstraintLayout`：

```
InputPanel (ConstraintLayout)
 ├── StickerSuggestion       — 贴纸建议列表
 ├── QuoteView               — 回复预览
 ├── LinkPreviewView         — 链接预览
 ├── EmojiToggle             — 表情/贴纸/GIF 切换
 ├── ComposeText             — 文本输入框
 │    ├── MentionRendererDelegate   — @提及渲染
 │    ├── MentionValidatorWatcher   — 提及验证
 │    ├── ComposeTextStyleWatcher   — 样式监听
 │    └── MessageStyler             — 格式化（粗体/斜体/删除线/等宽/剧透）
 ├── QuickCameraToggle       — 快速拍照
 ├── AnimatingToggle         — 按钮组切换动画
 │    ├── SendButton             — 发送按钮
 │    └── MicrophoneRecorderView — 语音录制
 └── VoiceNoteDraftView      — 语音草稿
```

**状态切换机制**：使用 `AnimatingToggle` 实现发送按钮和语音录制按钮之间的动画切换。当输入框有文本时显示发送按钮，无文本时显示麦克风按钮。

**编辑模式**：
```java
enterEditMessageMode(message) {
    composeText.setText(message.displayBody)    // 填充原文
    setQuote(message.quote)                     // 恢复引用
    updateEditModeUi()                          // 更新 UI 状态
    updateEditModeThumbnail()                   // 显示编辑预览
}
```

---

## 3. Telegram Android 架构分析

### 3.1 总体架构——巨型 Cell 模式

Telegram Android 采用了与 Signal 完全不同的策略：**单一巨型 Cell 处理所有消息类型**。

```
ChatActivity (主界面，2.5MB)
    ↓ 内部类
ChatActivityAdapter (适配器)
    ↓
ChatMessageCell (7000+ 行，统一渲染所有消息类型)
ChatActionCell (系统消息)
ChatUnreadCell (未读分隔)
ChatLoadingCell (加载状态)
```

### 3.2 消息类型分发——内部条件分支

Telegram 不通过不同的 ViewHolder 分发消息类型，而是在 `ChatMessageCell` 内部通过 `MessageObject.type` 进行条件分支渲染：

```java
// MessageObject.java 中定义了 40+ 种消息类型常量
public static final int TYPE_TEXT = 0;
public static final int TYPE_PHOTO = 1;
public static final int TYPE_VOICE = 2;
public static final int TYPE_VIDEO = 3;
// ...

// ChatMessageCell.java 内部根据 type 切换渲染逻辑
if (messageObject.type == TYPE_PHOTO) { /* 图片渲染 */ }
else if (messageObject.type == TYPE_VIDEO) { /* 视频渲染 */ }
else if (messageObject.type == TYPE_VOICE) { /* 语音渲染 */ }
// ...
```

**优点**：Cell 复用效率极高（所有消息共享同一个 View 类型，无需类型切换时的 inflate 开销）、性能优化集中。

**缺点**：单文件 7000+ 行极难维护、扩展新消息类型需要修改核心文件、测试困难。

### 3.3 输入面板——同样巨型

Telegram 的 `ChatActivityEnterView`（8000+ 行）同样采用巨型单文件模式，将所有输入功能集中在一个类中：

```
ChatActivityEnterView (FrameLayout)
 ├── EditTextCaption           — 文本输入框
 ├── ImageView attachButton    — 附件按钮
 ├── ImageView sendButton      — 发送按钮
 ├── ImageView micButton       — 语音按钮
 ├── FrameLayout attachLayout  — 附件面板
 └── ... 特殊功能按钮
```

### 3.4 消息操作

Telegram 的消息操作通过 `ChatActivity.createMenu()` 方法集中管理：

```java
createMenu(View v, boolean single, float x, float y) {
    MessageObject message = ((ChatMessageCell) v).getMessageObject();
    ArrayList<Integer> options = new ArrayList<>();
    if (message.canEditMessage()) options.add(OPTION_EDIT);
    if (message.canReply()) options.add(OPTION_REPLY);
    // ... 根据消息类型和权限动态构建菜单项
    showMenu(options, x, y);
}
```

多选模式通过 ActionMode + `selectedMessagesIds` 集合实现。

---

## 4. Telegram Desktop 架构分析

### 4.1 消息类型——继承体系

Telegram Desktop（Qt/C++）采用了经典的面向对象继承体系：

```
HistoryItem (基类)
 ├── HistoryMessage (普通消息)
 │    ├── HistoryText      — 文本消息
 │    ├── HistoryPhoto     — 图片消息
 │    ├── HistoryFile      — 文件消息（音频/视频/文档）
 │    ├── HistorySticker   — 贴纸消息
 │    ├── HistoryGame      — 游戏消息
 │    ├── HistoryPoll      — 投票消息
 │    ├── HistoryWebPage   — 网页预览消息
 │    └── ... (通过 Media 指针多态)
 └── HistoryService (系统消息)
```

**关键设计**：HistoryMessage 通过 `Media*` 指针实现多态，不同的 Media 子类负责各自的渲染逻辑。

### 4.2 气泡渲染——精细控制

```cpp
struct BubbleRounding {
    BubbleCornerRounding topLeft : 2;     // 2 bit per corner
    BubbleCornerRounding topRight : 2;
    BubbleCornerRounding bottomLeft : 2;
    BubbleCornerRounding bottomRight : 2;
};

enum class BubbleCornerRounding {
    None,   // 直角（连续同方向消息之间）
    Tail,   // 尾巴指向发送者（第一条消息）
    Small,  // 小圆角
    Large   // 大圆角
};
```

每个角独立控制，4 bit 描述一个气泡的完整圆角状态。这种精细控制使得消息气泡的视觉连续性极佳。

### 4.3 桌面端特有交互

| 功能 | 实现方式 |
|------|---------|
| **右键菜单** | `ContextMenuRequest` 结构体携带 `pointState`、`selectedItems`、`selectedText` 等 |
| **文本选择** | 精确到字符级，支持拖选、双击选词、三击选行 |
| **多选** | Ctrl+点击（非连续）、Shift+点击（连续） |
| **文件拖拽** | `handleDragEnter()` / `handleDrop()` 直接拖入输入框 |
| **键盘快捷键** | Ctrl+Enter 发送、Ctrl+K 链接、Ctrl+Shift+M 表情 |
| **三栏布局** | 用户可拖拽分割线调整栏宽，最小宽度保护 |

---

## 5. 架构模式对比

### 5.1 消息渲染策略对比

| 维度 | Signal Android | Telegram Android | Telegram Desktop |
|------|---------------|------------------|------------------|
| **分发策略** | 数据模型分类 + 工厂注册 | 单一巨型 Cell 内部分支 | OOP 继承 + 虚函数多态 |
| **ViewHolder 数量** | 4+ 种（按方向×类型） | 1 种（ChatMessageCell） | 1 种 Element + 多种 Media |
| **代码组织** | 类型导向，多文件 | 功能导向，单文件 | 类型导向，多文件 |
| **单文件大小** | 200-500 行/文件 | 7000+ 行 | 200-800 行/文件 |
| **扩展新类型** | 新增 Model + Factory | 修改核心 Cell | 新增 Media 子类 |
| **可测试性** | 良好 | 困难 | 良好 |
| **渲染性能** | 优（类型级复用） | 极优（全局复用） | 良好 |

### 5.2 输入面板策略对比

| 维度 | Signal | Telegram Android | Telegram Desktop |
|------|--------|------------------|------------------|
| **代码量** | 34KB（InputPanel） | 8000+ 行（EnterView） | 分散在多个 helper |
| **子组件管理** | AnimatingToggle 组合 | 内部字段管理 | Qt Layout 管理 |
| **状态切换** | 动画过渡 | 条件分支 | 信号槽 |
| **富文本** | Mention + Style + Spoiler | 简单格式化 | Markdown + 拼写检查 |
| **桌面端特有** | — | — | 拖拽上传、快捷键 |

### 5.3 设计哲学差异

| | Signal | Telegram |
|---|--------|----------|
| **哲学** | 模块化、可维护、类型安全 | 统一性、极致性能、集中控制 |
| **核心理念** | 组合优于继承 | 全局掌控、减少对象创建 |
| **适用场景** | 团队协作、持续迭代 | 快速开发、性能敏感 |
| **技术债务** | 低 | 高（巨型文件） |

---

## 6. 对 TeamTalk 的启发

### 6.1 当前状态

TeamTalk 的消息渲染和输入架构（基于 Compose Multiplatform）：

```
MessageBubble (171行)
 ├── MessageContentRenderer (when 分发，140行)
 │    ├── BasicMessageRenderers (文本/图片/语音/撤回/系统，168行)
 │    └── RichMessageRenderers (文件/视频/回复/转发，229行)
 └── 操作菜单 (DropdownMenu)

ChatInputBar (202行)
 ├── 回复预览栏
 ├── 表情选择器
 ├── 附件按钮 + 文本框 + 发送按钮
```

**当前问题**：
1. `when` 硬编码分发，每增加类型需改多处
2. 气泡和操作耦合在一起（MessageBubble 既管渲染又管操作菜单）
3. 输入面板功能单一（无语音录制、无 @提及、无格式化）
4. Desktop 和 Android 共享相同的交互模型（无右键菜单、无拖拽等桌面端增强）

### 6.2 推荐架构：Signal 式分层 + Compose 声明式

**为什么不学 Telegram 的巨型 Cell 模式？**

Telegram 的巨型 Cell 是历史遗留的技术债务。ChatMessageCell 7000+ 行、ChatActivity 2.5MB 的代码量在现代工程实践中是不可接受的。Telegram 团队自身也在逐步拆分重构。TeamTalk 从零开始，没有历史包袱，应采用更清晰的架构。

**推荐参考 Signal 的分层模式，但用 Compose 声明式重新实现：**

#### A. 消息数据模型——密封类驱动

```kotlin
// 定义消息展示模型（不是协议层的数据模型）
sealed class ChatItem {
    data class TextMessage(...) : ChatItem()
    data class ImageMessage(...) : ChatItem()
    data class FileMessage(...) : ChatItem()
    data class ReplyMessage(...) : ChatItem()
    data class SystemNotice(...) : ChatItem()
    data class TimeSeparator(...) : ChatItem()
    // 新增类型只需添加子类
}
```

#### B. 渲染分发——Composable 函数映射

```kotlin
// 不用 when，用 Map<类型, 渲染函数> 实现 O(1) 分发
// 或者用 when（Kotlin 密封类 + when 有编译时穷举检查，足够好）
@Composable
fun MessageContent(item: ChatItem) {
    when (item) {
        is ChatItem.TextMessage -> TextMessageContent(item)
        is ChatItem.ImageMessage -> ImageMessageContent(item)
        is ChatItem.FileMessage -> FileMessageContent(item)
        // 编译器会提示你是否遗漏了新类型
    }
}
```

Kotlin 密封类 + `when` 的组合比 Signal 的 `registerFactory` 更简洁，因为 Compose 的 `@Composable` 函数天然是无副作用的"视图工厂"，不需要额外的工厂模式。

#### C. 气泡组件——组合拆分

```
MessageBubble (布局容器)
 ├── ReplyPreview    — 回复预览（独立 Composable）
 ├── MessageContent  — 内容分发（独立 Composable）
 ├── LinkPreview     — 链接预览（独立 Composable）
 └── MessageFooter   — 时间 + 状态（独立 Composable）

BubbleTheme   — 气泡颜色/背景（独立，可替换）
BubbleShape   — 气泡形状/圆角（独立，可替换）
```

将"布局结构"和"视觉样式"分离，借鉴 Signal 的 Shape/Theme 委托思想。

#### D. 消息操作——平台感知

```kotlin
// 共享操作定义
sealed class MessageAction {
    data class Reply(val message: MessageDto) : MessageAction()
    data class Forward(val message: MessageDto) : MessageAction()
    data class Edit(val message: MessageDto) : MessageAction()
    // ...
}

// Android: 长按 → BottomSheet / Dialog
// Desktop: 右键 → ContextMenu
@Composable
expect fun MessageActionsMenu(
    actions: List<MessageAction>,
    onAction: (MessageAction) -> Unit,
)
```

#### E. 输入面板——状态机驱动

```kotlin
sealed class InputMode {
    data object Normal : InputMode()
    data class Replying(val message: MessageDto) : InputMode()
    data class Editing(val message: MessageDto) : InputMode()
}

@Composable
fun ChatInputPanel(mode: InputMode, ...) {
    when (mode) {
        is InputMode.Normal -> { /* 默认输入栏 */ }
        is InputMode.Replying -> { ReplyPreview(mode.message) + 输入栏 }
        is InputMode.Editing -> { EditBanner(mode.message) + 输入栏 }
    }
}
```

#### F. Desktop 增强——expect/actual 分离

```
commonMain:  MessageBubble（渲染 + 操作定义）
desktopMain: MessageBubble + 右键菜单 + 文本选择 + 拖拽
androidMain: MessageBubble + 长按菜单 + 触摸手势
```

Desktop 的增强不修改 commonMain 的组件，而是通过 `expect/actual` 或包装器模式叠加平台特性。

### 6.3 实施优先级建议

| 优先级 | 改进项 | 理由 |
|--------|--------|------|
| **P1** | 消息展示模型（sealed class）替换 when 硬编码 | 架构基础，影响后续所有消息类型开发 |
| **P1** | 气泡组件拆分（内容/样式/操作分离） | 降低单文件复杂度，提高可维护性 |
| **P2** | InputMode 状态机 | 回复/编辑模式的可扩展基础 |
| **P2** | Desktop 右键菜单 + 文本选择 | 桌面端基本体验 |
| **P3** | @提及 + 格式化输入 | 富文本输入 |
| **P3** | 语音录制交互 | 媒体消息完善 |
| **P4** | 桌面端拖拽上传 + 快捷键 | 效率增强 |

---

## 7. 关键结论

1. **Signal 的模块化架构更适合 TeamTalk**：Compose 的声明式特性 + Kotlin 密封类天然适配 Signal 的类型驱动分发模式，且比 Signal 原有的 `registerFactory` 更简洁。

2. **Telegram 的巨型 Cell 模式不应效仿**：虽然性能极致，但 7000+ 行的单文件在团队协作和长期维护上是灾难。Compose 的重组机制已经解决了大部分性能问题，不需要通过巨型 Cell 牺牲可维护性。

3. **消息数据模型是架构的基石**：Signal 将方向（发送/接收）和内容类型作为分类维度，Telegram Desktop 用 OOP 继承，TeamTalk 应使用 Kotlin 密封类——它提供了编译时类型安全 + `when` 穷举检查，是三者中最适合 Compose 的方案。

4. **平台差异通过 expect/actual 或包装器处理**：不是在 commonMain 中用 `if (isDesktop)` 判断，而是让平台层叠加各自的交互增强（右键菜单、拖拽、快捷键）。

5. **气泡的视觉表现应独立于内容渲染**：借鉴 Signal 的 Shape/Theme 委托和 Telegram Desktop 的 BubbleRounding 设计，让气泡的形状、颜色、圆角可以独立调整，不受消息内容影响。
