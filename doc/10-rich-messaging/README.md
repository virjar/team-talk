# 富消息课题调研 — Markdown 录入/渲染、@提及、控制指令、交互卡片

> 2026-08 调研。目标：消息录入升级为富文本，支持 @、控制指令，并为未来卡片交互铺路。
> 当前结论：**渲染自研（JetBrains parser + Compose）；录入使用仓内 fork 的 WYSIWYG 编辑器；所有新文字消息统一走 RICH_TEXT/Markdown；TextBody 只读兼容；卡片走独立消息类型。**

## 0.1 录入方案升级：fork 源码 WYSIWYG（2026-08-17）

一期定的"Slack 式源码+语法高亮"输入升级为**真 WYSIWYG**：fork `compose-rich-editor`
（MohamedRejeb，Apache 2.0）源码进主仓库 `richeditor/` 模块（治理规范见其 FORK.md：
`// [TT]` 标注 + 改动登记表 + 上游同步策略；基线 commit 与主项目同 CMP 1.10.3 线）。

- 输入区：`BasicRichTextEditor`（minLines=3 所见即所得，粗体实时渲染）
- 工具栏 B/I/S/代码 → `toggleSpanStyle` 直改样式（不再是语法符号包裹）
- Enter 换行；桌面 Cmd/Ctrl+Enter 发送；Cmd/Ctrl+B/I 切换格式
- 工具栏代码使用 `toggleCodeSpan()`，确保发送时序列化为反引号，而不是只改等宽字体
- 发送 `toMarkdown()` → RICH_TEXT；普通文本也是 Markdown 的自然子集，不再降级为 TEXT
- 草稿双向 `setMarkdown/toMarkdown`；表情 `insertAtCaret`（[TT] 定制）
- 待做（[TT] 后续定制）：mention 富 span（编辑器内显示 @名 胶囊替代链接语法原文）、
  代码样式 span 的 markdown 序列化

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

## 0.2 媒体消息体系（2026-08-17 落地）

**服务端缩略图管线**（上传时生成，用户确认的技术路线）：
- 图片：纯 Java2D（ImageIO 读头尺寸 + Graphics2D 等比缩放 max 边 480 输出 jpg）——零依赖零进程
- 视频：bytedeco javacv JNI（FFmpegFrameGrabber 抓首帧 + lengthInTime/imageWidth 元数据），
  native 内嵌 jar 平台裁剪（linux-x86_64 + macosx 双架构），版本锁定；
  对比否决的 ProcessBuilder 方案：无部署耦合/无版本漂移/无进程输出解析暗坑（用户挑战后修正）
- 接入点 FileRoutes.upload：源临时文件在 store 之前消费（F24：store 会 move 临时文件）
- 响应 UploadResponse 扩展 thumbPath/thumbUrl/width/height/durationSec（encodeDefaults 显式输出）

**协议**：ImageBody 尾部可选 thumbnailUrl（旧端读到旧布局即止；新端读剩余字节——新旧互操作
契约测试锁定 ×3）；VideoBody 原生已有 thumbnailUrl/duration 字段。

**客户端媒体缓存体系（桌面）**：
- DesktopMediaCache（SQLite media-cache.db）：url→local_path/kind/size/downloaded_at，
  500MB 配额按日期 LRU 清理（启动执行），并发下载同 url 去重
- 渲染链：气泡以缩略图为数据源（CachedImageContent 缓存感知：命中本地解码/未命中默认下载）；
  画廊原图按需加载（大进度覆盖层）；视频画廊 ensureDownloaded 后播本地文件
- 发送端 uploadWithMeta：body 带服务端宽高/时长/缩略图（准确度优于本地解码）

**实测（Linux 部署）**：图片 800×600→thumbUrl+尺寸 ✓；视频 960×540/5s/首帧缩略图 ✓（JNI）；
桌面收图→气泡缩略图默认下载→SQLite 落库 ✓（视觉验证留人工）。

## 1. 生态调研

### 1.1 渲染库（初选 mikepenz，实测弃用 → 自研，见 §0）

| 库 | 版本/状态 | KMP 目标 | 扩展能力 | 结论 |
|----|----------|---------|---------|------|
| [mikepenz/multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer) | **0.40.1（2026-04，86 个版本持续发版）** Kotlin 2.2/CMP 1.8 | Android/iOS/**Desktop**/Web | `markdownComponents()` 覆盖任意元素渲染；`markdownAnnotator{}` 拦截 AST 节点（LINK→mention 胶囊）；`-m3`/`-coil3`（与本项目 coil 3.4 对齐）/`-code` 语法高亮模块 | ❌ 已弃用（字节码 F17，见 §0） |
| [halilibo/compose-richtext](https://github.com/halilozercan/compose-richtext) | 1.0.0-alpha03（2025-07），pre-1.0 API 会漂移 | KMP 全目标 | richtext-commonmark 自定义 block | 未采用 |

### 1.2 编辑器（初期结论与后续调整）

| 库 | 状态 | 致命限制 |
|----|------|---------|
| [MohamedRejeb/compose-rich-editor](https://github.com/MohamedRejeb/compose-rich-editor) | 活跃，KMP；已 fork 到 `richeditor/` | 上游不支持可编辑 inline content；通过仓内源码治理解决 IM 键盘与序列化问题 |
| klibs compose-markdown-editor | 早期 | JVM 向，WYSIWYG 不成熟 |

**调整原因**：项目后来接受了仓内 fork 的维护成本，采用其富文本状态树实现 WYSIWYG；
mention 仍以 Markdown 链接语法表达，不把交互卡片塞进编辑器。

### 1.3 主流 IM 参照

- **Discord/Slack 桌面**：输入框是**纯文本 + 语法高亮**（`**bold**` 高亮、@名字着色），补全弹层；发送后渲染富文本。不是 WYSIWYG。
- **飞书**：自研 contenteditable（Web 技术栈），KMP 不可复制。
- **Telegram**：输入纯文本，实体（bold/mention）作为**侧信道偏移量列表**传输——与我们 mentions 侧信道设计同构。

## 2. 录入方案（当前：WYSIWYG + Markdown 权威源）

输入框使用 `BasicRichTextEditor`，编辑状态最终只导出 Markdown：

1. **WYSIWYG 行内格式**：B/I/删除线/行内代码直接修改富文本状态，`toMarkdown()` 编码为通用语法
2. **@ 补全**：监听光标前 `@xxx` 前缀，候选写入 `@[显示名](mention://uid)`
3. **/ 指令补全**：行首 `/` 触发命令补全层；命令注册表（本地指令：`/shrug` `/markdown`；透传指令：其余原样发送，未来 bot/服务端解析）
4. **多行与快捷键**：Enter 换行；Cmd/Ctrl+Enter 发送；Cmd/Ctrl+B/I 切换格式；发送按钮始终可用

## 3. 协议方案（RICH_TEXT 是默认文字类型，TextBody 废弃）

`TEXT(code=1)/TextBody` 只保留历史消息解码，不允许新发送。PacketBuffer 顺序读写，不能原地扩展旧结构；统一到已有的 RICH_TEXT 后，所有客户端、SDK、编辑和搜索只维护一套文字契约。

```
MessageType.RICH_TEXT = 新 code（协议演进策略 01-protocol §9，走新增枚举）
RichTextBody {
    markdown:  String            // 源文本，@ 用标准链接语法：@[显示名](mention://uid)
    mentions:  List<Mention>     // 侧信道：{ uid, displayName, offset, length }
    plainText: String            // 剥离语法的纯文本（搜索/会话预览/通知/旧端 fallback 用）
}
```

- **@ 内联语法选 `mention://` 链接**：任何 markdown 工具都能解析显示为链接，不破坏通用性；侧信道偏移供 UI 精确渲染胶囊（不靠再 parse）
- **plainText/mentions 是派生字段**：发送端生成，服务端落库前再从 markdown 权威源重建，Lucene 与会话预览不信任客户端声明
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
| 一期 | 渲染集成：旧 TextBody 兼容渲染；输入工具栏 | **零**（纯客户端） | ✅ |
| 二期 | RICH_TEXT wire 类型 + mentions 侧信道 + plainText 搜索/预览链路 + 契约测试 + 输入区重构（表情/附件宫格/格式键） | 新消息类型（非破坏） | ✅（@ 补全弹层三期做） |
| 三期 | @ 补全（VisualTransformation 折叠显示）+ / 指令（补全+展开）+ INTERACTIVE_CARD 静态卡片 + ImBot.sendCard/sendRichText | 新消息类型(17) | ✅ |
| 四期 | 卡片交互回调 RPC（cardAction）+ AI 员工卡片区 + 服务端 / 指令路由 | 新 RPC（契约表登记） | 📋 |
| 五期 | 所有新文字统一 RICH_TEXT；TextBody 废弃；Enter 多行与格式按钮修复；服务端重建派生字段 | 发送契约收敛（code 1 只读） | ✅ |

## 6. 风险

| 风险 | 对策 |
|------|------|
| 长消息每帧重 parse AST | 渲染层 remember(content) 缓存 parse 结果；超长消息（>4KB）截断渲染 |
| markdown 注入（外链图片/恶意链接） | 图片域白名单；链接点击确认；不渲染原始 HTML |
| 输入高亮与 @ 补全抢光标 | 高亮走 VisualTransformation（不改语义文本），补全层独立 Popup 不夺焦点 |
| 渲染库 API 变动（0.x 版本） | 锁版本 + 包装 `RichMessageText(annotations)` 单点封装，不裸用库 API |
