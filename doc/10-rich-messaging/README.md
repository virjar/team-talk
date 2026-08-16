# 富消息课题调研 — Markdown 录入/渲染、@提及、控制指令、交互卡片

> 2026-08 调研。目标：消息录入升级为富文本，支持 @、控制指令，并为未来卡片交互铺路。
> 结论先行：**渲染自研（JetBrains parser `org.jetbrains:markdown` + AnnotatedString 渲染层，`ui/component/rich/MarkdownText.kt`）；录入走 Slack/Discord 模式（语法高亮输入 + 补全，不做 WYSIWYG 编辑器）；wire 新增 RICH_TEXT 消息类型（markdown + mentions 侧信道 + plainText）；卡片走独立消息类型不塞 markdown。**

## 0. 选型变更记录（一期实施后）

**放弃 mikepenz/multiplatform-markdown-renderer**（2026-08 实测，F17）：
- 其 0.40.x JVM 字节码为 **Java 21（class 65）**，本项目桌面运行时 JBR 17 只认 class 61 →
  编译通过但**运行期** `UnsupportedClassVersionError`（渲染首条 markdown 消息时崩溃）。
  0.x 库的 toolchain 演进不可控，长期被绑架。
- 纯 Text 渲染方案无 inlineContent，mention 胶囊/卡片受限。

**改为自研渲染层**（已落地，13 个单测锁定）：
- 只依赖 `org.jetbrains:markdown`（JetBrains 官方 parser，纯 Kotlin 无传递依赖，`0.7.3`）
- AST → 块模型（MdBlock）→ 行内模型（MdSpan）→ AnnotatedString/Compose 组件
- 行为保证：纯文本零变形 / 未闭合语法保序不丢字 / 标记 token 不泄漏（列表项 `- ` 与段落内孤立 `**` 语义区分）
- 支持：段落/粗体/斜体/删除线/行内代码/链接/`mention://` 胶囊/代码块/标题/列表/引用
- 颜色全部取气泡 LocalContentColor（蓝/灰气泡自适应），代码块底色 contentColor 12% 叠层

## 1. 生态调研

### 1.1 渲染库（初选 mikepenz，实测弃用 → 自研，见 §0）

| 库 | 版本/状态 | KMP 目标 | 扩展能力 | 结论 |
|----|----------|---------|---------|------|
| [mikepenz/multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer) | **0.40.1（2026-04，86 个版本持续发版）** Kotlin 2.2/CMP 1.8 | Android/iOS/**Desktop**/Web | `markdownComponents()` 覆盖任意元素渲染；`markdownAnnotator{}` 拦截 AST 节点（LINK→mention 胶囊）；`-m3`/`-coil3`（与本项目 coil 3.4 对齐）/`-code` 语法高亮模块 | ❌ 已弃用（字节码 F17，见 §0） |
| [halilibo/compose-richtext](https://github.com/halilozercan/compose-richtext) | 1.0.0-alpha03（2025-07），pre-1.0 API 会漂移 | KMP 全目标 | richtext-commonmark 自定义 block | 未采用 |

### 1.2 编辑器（不选用，原因如下）

| 库 | 状态 | 致命限制 |
|----|------|---------|
| [MohamedRejeb/compose-rich-editor](https://github.com/MohamedRejeb/compose-rich-editor) | 活跃（2025-06 更新），KMP | **不支持 inline content**——@胶囊无法嵌入编辑区；iOS markdown 渲染有未修 issue |
| klibs compose-markdown-editor | 早期 | JVM 向，WYSIWYG 不成熟 |

**平台级事实**：Compose `TextField` 不支持 `inlineContent`（KMP 所有编辑器库均未解决）。而 IM 的 @ 胶囊、卡片恰恰需要 inline。→ 自研 WYSIWYG 编辑器 = 大投入低收益。

### 1.3 主流 IM 参照

- **Discord/Slack 桌面**：输入框是**纯文本 + 语法高亮**（`**bold**` 高亮、@名字着色），补全弹层；发送后渲染富文本。不是 WYSIWYG。
- **飞书**：自研 contenteditable（Web 技术栈），KMP 不可复制。
- **Telegram**：输入纯文本，实体（bold/mention）作为**侧信道偏移量列表**传输——与我们 mentions 侧信道设计同构。

## 2. 录入方案（拍板：Slack 模式）

输入框仍是 `TextField`，升级四件事：

1. **语法高亮**：`TextFieldValue` → 解析 markdown 语法段 → `VisualTransformation`/`AnnotatedString` SpanStyle（`**粗体**` 显示为粗体、@名字显示蓝色胶囊底）——纯样式可行，不需要 inlineContent
2. **@ 补全**：监听光标前 `@xxx` 前缀 → 弹出成员补全层（Popup）→ 选中替换为 `@显示名 ` 并高亮；esc/失焦关闭
3. **/ 指令补全**：行首 `/` 触发命令补全层；命令注册表（本地指令：`/shrug` `/markdown`；透传指令：其余原样发送，未来 bot/服务端解析）
4. **工具栏**：B/I/删除线/行内代码/链接 五键（选中文字包裹语法；桌面 Ctrl+B/I 快捷键），加"markdown 帮助"入口

不引入 compose-rich-editor——它的核心卖点（HTML/富文本状态树导出）我们不需要，且改不了 inline 限制。

## 3. 协议方案（拍板：新增 RICH_TEXT 类型，不扩展 TextBody）

**为什么不能给 TextBody 加字段**：PacketBuffer 顺序读写，老客户端按旧布局读新字段会错位（wire 演进规则：只增类型不改结构）。

```
MessageType.RICH_TEXT = 新 code（协议演进策略 01-protocol §9，走新增枚举）
RichTextBody {
    markdown:  String            // 源文本，@ 用标准链接语法：@[显示名](mention://uid)
    mentions:  List<Mention>     // 侧信道：{ uid, displayName, offset, length }
    plainText: String            // 剥离语法的纯文本（搜索/会话预览/通知/旧端 fallback 用）
}
```

- **@ 内联语法选 `mention://` 链接**：任何 markdown 工具都能解析显示为链接，不破坏通用性；侧信道偏移供 UI 精确渲染胶囊（不靠再 parse）
- **plainText 由发送端生成**（写 `MarkdownText.strip()`），服务端 Lucene 索引与会话列表预览直接用它——markdown 语法符号不进搜索索引
- **旧客户端兼容**：不认识 RICH_TEXT 的 body 解码为 null → 显示 `[富文本]` 预览（MessageType 预览映射已有 fallback 机制）
- 安全：渲染器不渲染原始 HTML（默认关）；外链图片走 coil3 白名单域（im.virjar.com）；链接点击桌面走 browse 前确认

## 4. 卡片方案（拍板：独立消息类型，不塞 markdown）

**为什么不塞 markdown**（fenced block 或 HTML）：结构化交互（按钮/表单/回调）需要 schema 校验和事件回传，markdown 字符串会退化成字符串协议。

```
一期（静态卡片）：INTERACTIVE_CARD 类型 + CardPayload（JSON：title/sections/actions[]）
    渲染：mikepenz 的 markdownComponents 扩展 + 自定义 Compose 卡片（复用文件卡视觉语言）
二期（交互回调）：action 点击 → 新 RPC cardAction(cardMsgId, actionId, value)
    服务端路由到发送方 bot（AI 员工消费）→ 回执新消息。Slack Block Kit 同构。
```

bot/AI 员工发卡片 = `ImBot.sendCard(chatId, CardPayload)`（无头 IM 战略的自然延伸）。

## 5. 分期

| 期 | 内容 | 协议影响 | 状态 |
|----|------|---------|------|
| 一期 | 渲染集成：TextBody 直接按 markdown 渲染（自研渲染层）；输入工具栏+语法高亮 | **零**（纯客户端） | ✅ 渲染完成（工具栏/高亮待做） |
| 二期 | RICH_TEXT wire 类型 + mentions 侧信道 + plainText 搜索/预览链路 + 契约测试 + 输入区重构（表情/附件宫格/格式键） | 新消息类型（非破坏） | ✅（@ 补全弹层三期做） |
| 三期 | @ 补全弹层 + / 指令补全 + 静态卡片 + ImBot.sendCard | 新消息类型 | 📋 |
| 四期 | 交互回调 RPC + AI 员工卡片区 | 新 RPC（契约表登记） | 📋 |

## 6. 风险

| 风险 | 对策 |
|------|------|
| 长消息每帧重 parse AST | 渲染层 remember(content) 缓存 parse 结果；超长消息（>4KB）截断渲染 |
| markdown 注入（外链图片/恶意链接） | 图片域白名单；链接点击确认；不渲染原始 HTML |
| 输入高亮与 @ 补全抢光标 | 高亮走 VisualTransformation（不改语义文本），补全层独立 Popup 不夺焦点 |
| 渲染库 API 变动（0.x 版本） | 锁版本 + 包装 `RichMessageText(annotations)` 单点封装，不裸用库 API |
