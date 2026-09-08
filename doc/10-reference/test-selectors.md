# 测试选择器参考

`testTag` 是 Desktop Compose 语义树与 Android resource id 共用的自动化契约。名称应描述业务角色，不依赖显示文案、颜色或控件层级。

源码中的 `testTag(...)` 是最终事实源；本表收录跨流程使用的稳定选择器。

## 窗口 ID

| ID | 内容 |
|---|---|
| `main` | 主窗口、聊天、全局搜索、任务工作台、群设置抽屉、用户资料弹窗、个人设置模态 |
| `documents` | 企业文档独立工作台 |
| `media-gallery` | Desktop 图片/视频媒体画廊独立窗口 |
| `sub-FriendApplies` | 好友申请 |
| `sub-SearchUsers` | 搜索用户 |
| `sub-CreateGroup` | 创建群组 |
| `sub-SearchMessages` | 搜索消息 |
| `sub-Forward` | 选择转发目标 |

任务窗口 ID 由 `sub-` 加 `SubScreen` 类名生成。新增任务页时应同步更新本表。

## 认证

| testTag | 作用 |
|---|---|
| `login.username` | 登录用户名 |
| `login.password` | 登录密码 |
| `login.submit` | 提交登录 |
| `login.gotoRegister` | 切换注册 |
| `login.close` | 关闭 Desktop 登录窗口 |
| `register.username` | 注册用户名 |
| `register.displayName` | 注册显示名 |
| `register.password` | 注册密码 |
| `register.submit` | 提交注册 |
| `register.gotoLogin` | 返回登录 |
| `auth.upgrade.dialog` / `auth.upgrade.exit` | Android / Desktop 协议不兼容强制升级表面与退出操作 |

## 应用壳与搜索

| testTag | 作用 |
|---|---|
| `main.home` | 已进入主界面 |
| `main.sessionResources.loading` | Desktop 已打开主窗口，正在 IO 构造会话平台资源 |
| `main.sessionResources.error` / `main.sessionResources.retry` | Desktop 会话平台资源构造失败表面与同窗口重试入口 |
| `status.connection` | Android 非认证连接状态横幅；断网或可重试认证失败时说明当前显示本地内容，连接和同步期间显示进度 |
| `app.titleBar` | Desktop 应用级标题栏 |
| `app.titleBar.drag.left` / `app.titleBar.drag.right` | 顶栏左右空白拖拽与双击缩放区 |
| `nav.avatar` | 当前用户入口 |
| `nav.tab.会话` / `nav.tab.通讯录` / `nav.tab.文档` / `nav.tab.任务` / `nav.tab.设置` | Desktop 一级导航 |
| `nav.会话` / `nav.通讯录` / `nav.文档` / `nav.任务` / `nav.设置` | Android 一级导航 |
| `action.search` | 搜索入口 |
| `global.search.input` | 全局搜索输入 |
| `global.search.clear` | 清空搜索 |
| `global.search.scope.{all\|messages\|people\|documents\|files\|services}` | 搜索分类 |
| `global.search.conversation.{chatId前12}` | 会话结果 |
| `global.search.user.{uid前8}` | 用户结果 |
| `global.search.message.{chatId前10}.{serverSeq}` | 消息结果 |
| `global.search.file.source.{0\|2\|3}` | 文件来源：全部、群文件、聊天附件 |
| `global.search.file.type.{0\|1\|2\|3\|4}` | 文件 MIME 分类：全部、图片、视频、音频、其他 |
| `global.search.content.{kind}.{scopeId}.{serverSeq}.{targetId}` | 文档、群文件或聊天附件结果 |
| `global.search.container.{kind}.{scopeId}.{serverSeq}.{targetId}` | 将内容搜索限定到该结果的空间或会话 |
| `global.search.container.clear.{kind}` | 清除该领域的空间/会话范围 |
| `global.search.content.{loading\|error\|retry\|more\|show-all}.{kind}` | 内容分页状态、重试、加载更多和全部结果入口 |

内容 `kind`：文档为 `1`、群文件为 `2`、聊天附件为 `3`；文档和群文件的 `serverSeq` 为 `0`。

## 会话、联系人和资料

| testTag | 作用 |
|---|---|
| `conv.item.{chatId前12}` | 会话项 |
| `conv.saved.entry` | 已有收藏内容的“保存的消息”会话项；参与普通排序，不固定置顶 |
| `conv.empty` | 没有可见会话时的空态 |
| `conv.pin.{chatId前12}` | 置顶或取消置顶 |
| `conv.mute.{chatId前12}` | 开启或关闭当前会话免打扰 |
| `contacts.friendApplies` | 新的朋友入口 |
| `contacts.search` | 通讯录本地搜索 |
| `contact.{uid前8}` | 联系人项 |
| `contact.presence.{uid前8}` | 好友明确 ONLINE 时头像右下的在线指示；UNKNOWN/OFFLINE 时节点不存在 |
| `directory.organization` / `directory.friends` | 通讯录的组织架构与好友分区 |
| `organization.directory.initializing` | 正在从本机读取组织目录投影 |
| `organization.directory.cache-miss` | 本机尚无权威组织单位快照，不能显示成“尚未配置” |
| `organization.directory.empty` | 当前 revision 的权威组织单位快照为空 |
| `organization.directory` | 组织目录列表容器 |
| `organization.unit.{unitId前8}` | 组织节点 |
| `organization.unit.{unitId前8}.memberCount` | 组织节点直属人数 |
| `organization.member.{uid前8}` | 当前组织节点的直属成员 |
| `organization.members.loading` | 当前组织节点直属成员正在联网加载 |
| `organization.members.cache-miss` | 当前组织节点在本机尚无完整直属成员快照 |
| `organization.members.empty` | 当前组织节点的权威直属成员快照为空 |
| `organization.group.{chatId前8}` | 进入受管部门群 |
| `search.query` / `search.submit` | 用户搜索输入与提交 |
| `search.result.{uid前8}` | 用户搜索结果 |
| `profile.overlay` / `profile.dismissArea` / `profile.dialog` / `profile.close` | Desktop 主窗口内资料模态、遮罩、卡片与唯一关闭按钮 |
| `profile.addFriend` / `profile.applied` / `profile.incomingApply` | 添加、已发出及收到待处理申请的资料页状态 |
| `friendApply.loading` / `friendApply.empty` / `friendApply.loadMore` | 双向好友申请记录的加载、空态与分页 |
| `friendApply.record.{id}` | 收到或发出的单条好友申请记录 |
| `friendApply.accept.{id}` / `friendApply.reject.{id}` | 处理收到且待验证的申请 |
| `profile.sendMessage` | 发起私聊 |
| `profile.createGroup` | 从资料发起群聊 |
| `profile.deleteFriend` | 删除好友 |
| `profile.blockUser` / `profile.blockUser.confirm` | 将他人加入黑名单及二次确认 |

## 设置和账户

| testTag | 作用 |
|---|---|
| `settings.overlay` / `settings.dismissArea` / `settings.dialog` / `settings.close` | 个人设置模态容器、遮罩与关闭按钮 |
| `settings.{入口标题}` | 设置功能入口，例如 `settings.编辑资料`（入口行 = 图标 + 标题 + 描述） |
| `settings.avatar.edit` | 设置头部的头像；点击进入编辑资料（头像更换入口） |
| `settings.appearance.{SYSTEM\|LIGHT\|DARK}` | 外观内联分段选择器，点击立即切换主题 |
| `settings.logout` | 退出登录（红色描边卡片） |
| `settings.logout.confirm` / `settings.logout.cancel` | 退出二次确认框 |
| `profile.name` / `profile.phone` | 编辑资料字段；权威资料到达前不会把临时空值保存回服务端 |
| `profile.save.error` | 资料保存的具体失败原因，包括手机号格式与占用冲突 |
| `profile.remark.edit` / `profile.remark.input` | 双端好友资料的备注入口与输入框 |
| `profile.remark.save` / `profile.remark.cancel` / `profile.remark.error` | 备注保存、取消及失败反馈 |
| `chat.composer.images` / `chat.composer.image.1` / `chat.composer.image.remove.1` | 发送前图片卡片、正文顺序编号及对应移除动作 |
| `profile.loading` | 冷启动尚在读取权威个人资料，保存按钮保持不可用；本地已有 revision 时不出现 |
| `profile.avatar.preview` / `profile.avatar.status` | 当前或待保存头像，以及自动居中裁剪/上传状态 |
| `profile.avatar.pick` / `profile.avatar.remove` | 系统图片选择与清除头像入口 |
| `profile.avatar.error` | 图片处理或上传失败的页内反馈；无错误时节点不存在 |
| `profile.save` | 保存资料 |
| `password.old` / `password.new` / `password.confirm` | 密码字段 |
| `password.submit` | 修改密码 |

## 聊天与临时层

| testTag | 作用 |
|---|---|
| `chat.input` | 富文本消息输入 |
| `chat.input.hint` | 空输入提示 |
| `chat.typing` | 当前会话其他成员的 3 秒瞬时输入状态；断线、对方新消息或离页后节点消失 |
| `chat.composer` | 消息编辑器完整容器 |
| `chat.header.back` / `chat.header.title` | Android 聊天返回入口与单行标题 |
| `chat.group.detail` | Android 群聊详情显式入口 |
| `chat.history.loadMore` | 手动加载更早消息 |
| `chat.history.loading` | 更早消息加载中 |
| `chat.composer.mode.{visual|source|preview}` | 可视编辑、Markdown 源码与气泡预览 |
| `chat.input.source` / `chat.input.source.hint` | Markdown 源码输入与空提示 |
| `chat.preview` | 与最终消息气泡同源的预览 |
| `chat.message.seq.{serverSeq}` / `chat.message.seq.{serverSeq}.body` | 已 ACK 消息及其正文 |
| `chat.message.focused.{serverSeq}` | 搜索结果定位完成后的短时高亮消息行；撤回目标同样使用该标签 |
| `chat.message.seq.{serverSeq}.media.{file|image|voice|video}` | 已 ACK 文件、图片、语音或视频内容；标签直接挂在可操作媒体卡上 |
| `chat.message.client.{clientMsgId前12}.media.{file|image|voice|video}` | 尚未取得服务端序号的上传、排队或失败媒体内容 |
| `chat.message.failed.{clientMsgId前12}` | 客户端发送失败且仍保留在消息流中的状态提示 |
| `chat.failed.recover.{clientMsgId前12}` / `chat.failed.recovery.context` | 进入失败消息编辑并以新身份重发，以及输入区恢复上下文 |
| `chat.failed.discard.{clientMsgId前12}` | 发起显式丢弃一条终态失败消息 |
| `chat.failed.discard.{dialog|confirm|cancel}` | 丢弃失败消息的确认框、确认与取消动作 |
| `chat.send` | 发送消息 |
| `chat.draft.conflict` | 完整草稿的跨设备冲突或同步失败提示 |
| `chat.draft.keepLocal` / `chat.draft.useRemote` | 明确保留本机草稿或使用其他设备草稿 |
| `chat.draft.assetsUnavailable` | 远端草稿资产不可用，保留输入并阻止提交 |
| `chat.emoji` | 表情入口 |
| `chat.fmt.{bold|italic|strike|code|link|bullets|numbered|more}` | 消息输入的轻量格式工具与窄屏更多菜单 |
| `chat.composer.format.close` | 关闭窄屏已展开的更多格式区 |
| `rich.link.{text|url|confirm|remove}` | 富文本链接的显示文字、地址、确认与移除操作 |
| `chat.voiceMode` / `chat.voice.record` | 语音模式切换与按住录制区域 |
| `chat.attach` / `chat.attach.panel` | 附件入口与图片、视频、文件选择面板 |
| `chat.attach.{image|video|file|paste}` | 附件面板中的图片、视频、普通文件和二进制剪贴板粘贴入口 |
| `chat.asset.pending.{assetId}` / `chat.asset.progress.{assetId}` | 聊天 Markdown 上下文资产有界滚动列表中的准备、上传与进度状态 |
| `chat.asset.cancel.{assetId}` | 取消仍在准备或上传的聊天资产；先删除正文引用，再取消平台任务 |
| `chat.asset.retry.{assetId}` / `chat.asset.remove.{assetId}` | 上传失败行同时存在的就地重试与移除入口；重试保留原正文位置和资产身份，移除先删除正文引用 |
| `rich.asset.{image|file}.{assetId}` | 当前消息或文档 manifest 已准入的嵌入图片/文件；同一渲染选择器跨 Desktop 与 Android 复用 |
| `chat.settings` | 打开群设置 |
| `chat.inspector` | 右侧群设置抽屉 |
| `chat.inspector.dismissArea` | 抽屉外部关闭区 |
| `chat.inspector.close` | 抽屉关闭按钮 |
| `attachment.preview.dialog` / `attachment.preview.close` | Desktop 文本附件预览弹窗与关闭 |
| `attachment.preview.{loading|text|markdown|error|tooLarge|unsupportedCharset}` | TXT/Markdown 附件预览状态与正文 |
| `attachment.preview.retry` / `attachment.preview.external` | 预览重试与改用系统应用打开 |
| `text.attachment.external.error` | Android 使用系统应用打开失败的可见错误 |

## 媒体画廊

| testTag | 作用 |
|---|---|
| `media.gallery.root` | 图片/视频画廊根节点 |
| `media.gallery.page.{从0开始的页序号}` / `media.gallery.page.current` | 指定媒体页与当前页；用于断言翻页后只有一个当前页 |
| `media.gallery.image` | 当前图片页；Desktop 截图验收完整适配、留边及相邻页不串画 |
| `media.gallery.pageCounter` | 当前页码与媒体总数 |
| `media.gallery.previous` / `media.gallery.next` | Desktop 上一项与下一项媒体 |
| `media.gallery.fullscreen` / `media.gallery.close` | 画廊级全屏切换与关闭 |
| `media.gallery.video` / `media.gallery.video.surface` | Desktop 当前视频页与已就绪的视频画面 |
| `media.gallery.video.loading` / `media.gallery.video.error` | 视频完整下载或本地播放器准备中的加载态与失败态 |
| `media.gallery.video.downloadProgress` | Desktop / Android 视频完整下载进度；播放器就绪前用于确认本地优先状态 |
| `media.gallery.video.retry` | Desktop / Android 视频下载或本地播放器准备失败后的重试 |
| `media.gallery.video.controls` | Desktop 视频播放控制区 |
| `media.gallery.video.progress` | Desktop 视频播放进度与拖动定位 |
| `media.gallery.video.playPause` / `media.gallery.video.time` | Desktop 播放/暂停与当前时间/总时长 |
| `media.gallery.video.fullscreen` | Desktop 视频控制区内的全屏切换 |

## 群组、成员和转发

| testTag | 作用 |
|---|---|
| `group.name` / `group.create` | 群名与创建提交 |
| `group.member.{uid前8}` | 建群候选或群详情成员；群详情中点击查看资料，有管理权限时长按打开成员菜单 |
| `group.detail.content` | Desktop / Android 群设置正文的唯一滚动列表，包含功能入口、成员与末尾危险操作 |
| `group.detail.editNotice` | 有编辑权限时显示的群公告编辑入口 |
| `group.detail.invite` | 邀请成员 |
| `group.detail.inviteLinks` | 邀请链接 |
| `invite.search` / `invite.submit` | 邀请成员搜索与提交；提交按钮在无人可选或请求进行中禁用 |
| `invite.candidate.{uid前8}` / `invite.selected.{uid前8}` | 可邀请候选和已选人选；成员关系变化后同步排除已入群的人选 |
| `invite.hint.idle` / `invite.hint.empty` | 未搜索提示与无匹配/无可邀请好友的空状态 |
| `group.detail.leave` | 退出或解散群 |
| `member.{uid前8}` | 成员管理列表项 |
| `forward.item.{chatId前12}` | 转发目标 |
| `search.msg.query` / `search.msg.submit` | 消息搜索 |
| `search.msg.result.{chatId前12}.{serverSeq}` | 消息搜索结果 |

群详情成员采用惰性布局，屏外成员不保证存在于当前语义树。定位成员或末尾操作前，先滚动
`group.detail.content` 使目标进入视口；不要把语义树中暂未出现的成员判为数据丢失。

## 群机器人

| testTag | 作用 |
|---|---|
| `group.detail.bots` | 从群设置进入通知机器人管理 |
| `group.bots.screen` / `group.bots.close` | 通知机器人页面与 Desktop 关闭操作 |
| `group.bots.add` / `group.bots.empty` / `group.bots.loading` | 所有成员可用的添加入口、空状态与加载状态 |
| `group.bots.error` / `group.bots.retry` | 加载或操作错误与重试 |
| `group.bots.create.name` / `group.bots.create.confirm` | 新机器人名称与创建提交 |
| `group.bots.credentials` / `group.bots.credentials.copyExample` / `group.bots.credentials.saved` | 一次性凭据弹窗、复制群绑定 URL 的完整调用示例与已安全保存确认 |
| `group.bot.{botId前8}` | 当前群中的机器人列表项 |
| `group.bot.{botId前8}.more` / `group.bot.{botId前8}.copyUrl` | 机器人操作菜单与复制 TeamTalk 入站通知 URL |
| `group.bot.rotate.confirm` / `group.bot.remove.confirm` | 凭据轮换与移除二次确认 |

## 群文件

| testTag | 作用 |
|---|---|
| `group.detail.files` | 从群设置进入群文件 |
| `group.files.upload` / `group.files.createFolder` | 上传文件与新建目录入口 |
| `group.files.createFolder.{dialog|input|confirm|cancel}` | 新建目录弹窗及其操作 |
| `group.files.entry.{entryId前8}` | 文件或目录项 |
| `group.files.entry.{entryId前8}.more` | 条目更多操作 |
| `group.files.rename.{dialog|input|confirm|cancel}` | 重命名弹窗及其操作 |
| `group.files.delete.{dialog|confirm|cancel}` | 删除确认弹窗及其操作 |
| `group.files.versions.{dialog|upload|close}` | 版本记录弹窗及其操作 |
| `group.files.version.{version}.open` | 打开指定历史版本 |

## 企业文档

| testTag | 作用 |
|---|---|
| `nav.tab.文档` / `documents.workspace` | 一级文档入口与工作台根节点 |
| `documents.home` / `documents.home.spaces` / `documents.home.recents` | 文档资产首页、空间索引与最近列表 |
| `documents.home.recent.{documentId前8}` / `documents.home.created.{documentId前8}` | 首页最近访问与最近创建文档 |
| `documents.space.create` / `documents.space.create.dialog` | 新建空间入口与弹窗 |
| `documents.space.{spaceId前8}` | 空间列表项 |
| `documents.space.workspace` / `documents.space.back` / `documents.space.overview` | 空间工作区、从文档树返回首页与空间概览 |
| `documents.space.settings` / `documents.space.settings.dialog` | 空间信息与授权管理 |
| `documents.document.create` / `documents.node.{nodeId前8}.createChild` | 新建根级文档，或在指定文档下新建子文档 |
| `documents.tree` | 紧凑文档树容器；空间根只是根级文档的创建上下文，不是业务节点 |
| `documents.node.{nodeId前8}` | 文档树行；无论是叶节点还是内节点，点击标题都打开正文 |
| `documents.tree.toggle.{nodeId前8}` | 只展开/折叠指定文档的子文档，不代替打开正文 |
| `documents.tab.{tabId前12}` | 已打开的跨空间文档标签 |
| `documents.editor.title` / `documents.editor.body` / `documents.editor.blocks` | 标题、文档编辑器与无损块级画布 |
| `documents.editor.back` | Android 单文档编辑器返回空间文档树；离开前同步捕获最后一拍草稿 |
| `documents.editor.source` / `documents.editor.source.body` | Markdown 源码模式及其无损正文输入 |
| `documents.editor.format.{undo|redo|heading|bold|italic|strike|code|link|bullets|numbered|indent|outdent}` | 文档格式工具栏 |
| `documents.editor.format.heading.{0..6}` | 正文及 H1–H6 段落样式 |
| `documents.editor.block.{rich|quote|code|table|insert}` | 在活动光标位置插入正文、引用、代码块或表格；窄屏使用插入菜单 |
| `documents.editor.block.bottom.{rich|quote|code|table}` | 在文档末尾追加内容块 |
| `documents.editor.{rich|quote|code|table|raw}.*` | 各类可视内容块；未建模扩展只使用局部 raw 卡片 |
| `documents.editor.table.header.{blockKey}.{column}` / `documents.editor.table.row.{row}.{blockKey}.{column}` | 表头与正文单元格；聚焦后成为行列结构操作锚点 |
| `documents.editor.table.header.*.align.{column}` | 循环切换表格列的默认、居中、右对齐和左对齐 |
| `documents.editor.table.actions.{blockKey}` | 可横向滚动的表格行列操作栏，保证窄屏四项操作都可达 |
| `documents.editor.table.{addRow|addColumn}.{blockKey}` | 在聚焦单元格下方插入行、右侧插入列；未聚焦时追加到末尾 |
| `documents.editor.table.{removeRow|removeColumn}.{blockKey}` | 删除当前行或当前列；表头不能删行，且至少保留一列 |
| `documents.editor.table.limit.{blockKey}` | 行列新增命中 32 列或 1000 单元格（含表头）上限时的辅助说明 |
| `documents.editor.preview` / `documents.editor.save` | 编辑预览切换与保存 |
| `documents.editor.structurePending` | move/rename 已持久录取但尚未取得可收敛结果；正文草稿保留并等待后台恢复 |
| `documents.asset.pick.{image|file}` | 在当前文档上下文选择并上传嵌入图片或文件 |
| `documents.asset.paste` | Android 可编辑且非预览的文档中导入剪贴板的首个二进制 URI 资源；纯文本不创建资产 |
| `documents.asset.pending.{assetId}` / `documents.asset.progress.{assetId}` | 文档上下文资产有界滚动列表中的准备、上传与进度状态 |
| `documents.asset.cancel.{assetId}` | 取消仍在准备或上传的文档资产；先发布删除引用后的草稿，再取消平台任务 |
| `documents.asset.retry.{assetId}` / `documents.asset.remove.{assetId}` | 上传失败行同时存在的就地重试与移除入口；重试保留原正文位置和资产身份，移除先发布删除引用后的草稿 |
| `documents.asset.error` | 上传未完成或 Markdown 引用与 sidecar 不一致时的保存阻断说明 |
| `documents.editor.history` / `documents.revisions.dialog` | 打开版本历史及其弹窗 |
| `documents.revisions.preview.back` | 从修订正文预览返回版本列表；按钮同时提供“返回版本列表”无障碍语义 |
| `documents.document.more` | 文档级更多操作 |
| `documents.revision.{revision}` / `documents.revision.restore` | 指定修订及恢复 |
| `documents.document.delete` / `documents.document.delete.confirm` | 删除入口与确认 |
| `documents.tab.discard.confirm` | Desktop 未保存标签关闭保护 |
| `documents.mobile.discard.{dialog|confirm|cancel}` | Android 返回文档树或切换文档时的未保存确认 |
| `documents.detach` / `documents.detached.placeholder` / `documents.detached.bringBack` | 拉出独立窗口、主窗口承接态与收回操作 |
| `main.error.snackbar` | Android 会话级错误提示，包含文档删除等操作失败 |

## 任务工作台与引用

| testTag | 作用 |
|---|---|
| `task.workspace` / `task.list` / `task.detail.{taskId}` | 共享工作台、列表和指定任务详情 |
| `task.view.{assigned\|created}` / `task.row.{taskId}` / `task.more` | 两种列表、任务项和继续分页 |
| `task.refresh` / `task.new` / `task.back` / `task.detail.refresh` | 列表刷新、新建、返回列表和详情刷新 |
| `task.stale` / `task.empty` / `task.list.{loading\|error}` / `task.detail.{loading\|error}` | 本地状态、权威空列表与加载/失败反馈 |
| `task.title` / `task.description` / `task.status` / `task.assignee` / `task.deadline` / `task.context` | 详情当前标题、描述、状态、执行人、截止和背景关联 |
| `task.edit` / `task.status.{1\|2\|3\|4}` / `task.cancel.confirm` | 编辑、目标状态与取消确认；1 待处理、2 进行中、3 完成、4 取消，权限不允许的按钮不存在 |
| `task.editor.{title\|description\|assignee\|date\|time}` | 表单字段；日期与时间使用本地时区 |
| `task.editor.{save\|cancel\|error\|remoteChanged}` / `task.editor.deadline.clear` | 保存、退出编辑、错误、远端新修订提示及清除截止 |
| `task.editor.context.{0\|1\|2}` / `task.editor.context.choose` | 无关联、群聊、部门，以及具体对象选择 |
| `task.assignee.query` / `task.assignee.self` / `task.assignee.{uid}` | 执行人搜索、分配自己及候选成员 |
| `task.context.query` / `task.context.{contextId}` | 背景对象筛选和选择 |
| `task.audit.{revision}` / `task.audit.more` | 审计记录及继续分页 |
| `task.pending.{taskId}` / `task.pending.{retry\|discard\|intent}.{taskId}` | 待发送状态、重试、放弃入口和原内容查看 |
| `task.pending.intent.content` / `task.pending.discard.confirm` | 可选择复制的原意图与显式放弃确认 |
| `task.reminder.{taskId}` / `task.reminder.{open\|seen}.{taskId}` | 到期提醒、打开任务和标记已读 |
| `task.share` / `task.share.query` / `task.share.chat.{chatId}` / `task.share.error` | 从详情分享到会话、筛选、目标和失败 |
| `chat.attach.task` / `task.reference.picker` / `task.reference.view.{1\|2}` / `task.reference.more` | 聊天附件入口、引用选择器、分配/创建列表和分页 |
| `chat.taskref.pick.{taskId前12}` / `chat.taskref.{taskId前12}` | 候选选择和消息中的任务引用卡片 |
| `task.notice` | 本机保存或分享进入发送队列后的反馈，不代表服务端已确认 |

任务工作台使用完整业务 ID；聊天任务卡片沿用消息组件的 12 字符前缀。同一任务的 pending 可能同时
出现在 Desktop 左侧列表和右侧详情，操作前限定到对应容器。惰性列表需先滚动到目标，不以屏外节点
暂未出现在语义树中判断数据丢失。

## 命名规则

- 静态控件使用 `领域.角色`，例如 `chat.send`。
- 动态列表项追加稳定业务 ID 的短前缀，不使用列表序号。
- 容器、打开按钮和关闭路径分别命名，便于断言临时层生命周期。
- 不把中文文案写入新选择器；现有 `nav.tab.*`、`settings.*` 是兼容保留，后续统一改名需同步更新验收代码。
- 新增关键交互时，代码、本文和相关验收场景应在同一变更中更新。
