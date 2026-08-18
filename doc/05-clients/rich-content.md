# 富文本与媒体

## 1. 单一文本模型

所有新文字消息使用 `RICH_TEXT`。Markdown 是持久化和传输的权威源，WYSIWYG 编辑器只是它的
交互表示；`plainText` 与 mentions 每次由 Markdown 重建。

旧 `TEXT/TextBody` 仅兼容读取。这样发送、编辑、搜索、通知预览和 SDK 不需要维护两条文字路径。

## 2. 编辑器

仓库内 `richeditor/` 是 compose-rich-editor 的受控 fork，治理说明见 `richeditor/FORK.md`。输入区使用
`BasicRichTextEditor`：

- 粗体、斜体、删除线和代码直接修改富文本状态。
- `toMarkdown()` 生成消息源；草稿使用 Markdown 双向恢复。
- Desktop Enter 换行，Cmd/Ctrl+Enter 发送。
- 表情插入当前光标并恢复焦点。
- 编辑已有文字消息时重新载入 Markdown，而不是纯文本预览。

fork 的 TeamTalk 修改必须以 `[TT]` 注释和登记表维护，方便以后与上游比较。

## 3. Markdown 渲染

渲染层使用 JetBrains Markdown parser，转换为内部块和行内模型，再映射 Compose：

- 段落、标题、列表、引用和代码块。
- 粗体、斜体、删除线、行内代码与链接。
- `mention://uid` 链接渲染为可点击提及。
- 未闭合或未知语法保留可读文本，不丢字。
- 不渲染原始 HTML。

渲染结果按 content 缓存，不能在每次 Compose 重组中重复解析。颜色来自当前气泡 contentColor，
确保进入浅蓝/灰色气泡时仍有正确对比。

## 4. 提及

用户选择候选后写入：

```markdown
@[显示名](mention://uid)
```

编辑器可以视觉折叠为 `@显示名`，但底层 Markdown 不变。服务端会从 Markdown 重新构建 mentions，
不信任调用方提供的侧信道列表。当前实现尚未校验 mention uid 是否属于会话；补齐该约束后，非法
mention 应拒绝或按明确规则降级，而不能直接触发通知语义。

群聊候选来自群成员；私聊候选来自对方/联系人。`@` 前缀需要行首或空白边界，避免邮箱误触。

## 5. 斜杠指令

行首 `/` 触发本地命令补全。`/shrug`、`/todo`、`/code` 等本地命令在发送前展开；未注册命令保持
原文。服务端或 bot 指令需要独立权限、路由和结果模型，不能让客户端随意执行字符串命令。

## 6. 交互卡片

INTERACTIVE_CARD 是独立消息类型。卡片使用结构化 schema 表达标题、区块和 action；Markdown
只负责可读文本。action 回调属于服务端 RPC/机器人能力，必须校验消息、用户和动作权限。

## 7. 媒体处理

### 上传

图片与视频先通过 HTTP 上传。服务端在存储源文件前生成缩略图和媒体元数据：图片使用 Java2D，
视频使用内嵌 FFmpeg/JavaCV。响应提供 path、缩略图 path、尺寸和时长。

### 气泡

- 图片/贴纸：媒体贴边，气泡本身裁剪圆角。
- 视频：16:9 预览、播放按钮和时长角标。
- 语音：播放/暂停、确定性波形和当前/总时长。
- 文件：扩展名色块、名称、大小、下载进度和重试。
- 回复：引用竖线、发送者和截断内容，再显示正文。

### 缓存

Desktop 媒体缓存记录 URL、local path、类型、大小和下载时间，按容量做 LRU；同 URL 并发下载去重。
缩略图可自动缓存，原图/视频按用户动作加载。Android 使用平台缓存与播放器，但遵守相同附件身份。

## 8. 安全

- 附件 URL 只能解析 TeamTalk 文件端点。
- 链接点击交给平台安全打开策略。
- 外链图片和原始 HTML 默认不渲染。
- 卡片 action 和编辑/撤回必须由服务端重新校验；mention 目前只重建派生字段，成员校验仍待补齐。
- 超长 Markdown 应限制渲染成本，同时保留原始消息可查看。
