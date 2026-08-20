# 测试选择器参考

`testTag` 是 Desktop Compose 语义树与 Android resource id 共用的自动化契约。名称应描述业务角色，不依赖显示文案、颜色或控件层级。

源码中的 `testTag(...)` 是最终事实源；本表收录跨流程使用的稳定选择器。

## 窗口 ID

| ID | 内容 |
|---|---|
| `main` | 主窗口、聊天、全局搜索、群设置抽屉、用户资料弹窗 |
| `sub-Devices` | 设备管理 |
| `sub-Blacklist` | 黑名单 |
| `sub-EditProfile` | 编辑资料 |
| `sub-ChangePassword` | 修改密码 |
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
| `auth.upgrade.dialog` / `auth.upgrade.exit` | Android 协议不兼容强制升级弹窗与退出操作 |

## 应用壳与搜索

| testTag | 作用 |
|---|---|
| `main.home` | 已进入主界面 |
| `app.titleBar` | Desktop 应用级标题栏 |
| `app.titleBar.drag.left` / `app.titleBar.drag.right` | 顶栏左右空白拖拽与双击缩放区 |
| `nav.avatar` | 当前用户入口 |
| `nav.tab.会话` / `nav.tab.通讯录` / `nav.tab.文档` / `nav.tab.设置` | Desktop 一级导航 |
| `nav.会话` / `nav.通讯录` / `nav.文档` / `nav.设置` | Android 一级导航 |
| `action.search` | 搜索入口 |
| `global.search.input` | 全局搜索输入 |
| `global.search.clear` | 清空搜索 |
| `global.search.scope.{all|messages|people|files|services}` | 搜索分类 |
| `global.search.conversation.{chatId前12}` | 会话结果 |
| `global.search.user.{uid前8}` | 用户结果 |
| `global.search.message.{chatId前10}.{serverSeq}` | 消息结果 |

## 会话、联系人和资料

| testTag | 作用 |
|---|---|
| `conv.item.{chatId前12}` | 会话项 |
| `conv.pin.{chatId前12}` | 置顶或取消置顶 |
| `contacts.friendApplies` | 新的朋友入口 |
| `contacts.search` | 通讯录本地搜索 |
| `contact.{uid前8}` | 联系人项 |
| `organization.unit.{unitId前8}` | 组织节点 |
| `organization.unit.{unitId前8}.memberCount` | 组织节点直属人数 |
| `organization.member.{uid前8}` | 当前组织节点的直属成员 |
| `organization.group.{chatId前8}` | 进入受管部门群 |
| `search.query` / `search.submit` | 用户搜索输入与提交 |
| `search.result.{uid前8}` | 用户搜索结果 |
| `profile.dialog` / `profile.close` | Desktop 资料弹窗与关闭 |
| `profile.addFriend` / `profile.applied` | 申请好友状态 |
| `profile.sendMessage` | 发起私聊 |
| `profile.createGroup` | 从资料发起群聊 |
| `profile.deleteFriend` | 删除好友 |
| `profile.blockUser` / `profile.blockUser.confirm` | 将他人加入黑名单及二次确认 |

## 设置和账户

| testTag | 作用 |
|---|---|
| `settings.{入口标题}` | 设置功能入口，例如 `settings.编辑资料` |
| `settings.logout` | 退出登录 |
| `profile.name` / `profile.phone` | 编辑资料字段 |
| `profile.save` | 保存资料 |
| `password.old` / `password.new` / `password.confirm` | 密码字段 |
| `password.submit` | 修改密码 |

## 聊天与临时层

| testTag | 作用 |
|---|---|
| `chat.input` | 富文本消息输入 |
| `chat.input.hint` | 空输入提示 |
| `chat.composer` | 消息编辑器完整容器 |
| `chat.history.loadMore` | 手动加载更早消息 |
| `chat.history.loading` | 更早消息加载中 |
| `chat.composer.mode.{visual|source|preview}` | 可视编辑、Markdown 源码与气泡预览 |
| `chat.input.source` / `chat.input.source.hint` | Markdown 源码输入与空提示 |
| `chat.preview` | 与最终消息气泡同源的预览 |
| `chat.message.seq.{serverSeq}` / `chat.message.seq.{serverSeq}.body` | 已 ACK 消息及其正文 |
| `chat.message.failed.{clientMsgId前12}` | 客户端发送失败且仍保留在消息流中的状态提示 |
| `chat.send` | 发送消息 |
| `chat.emoji` | 表情入口 |
| `chat.fmt.{bold|italic|strike|code|link|bullets|numbered|more}` | 消息输入的轻量格式工具与窄屏更多菜单 |
| `rich.link.{text|url|confirm|remove}` | 富文本链接的显示文字、地址、确认与移除操作 |
| `chat.voiceMode` | 语音模式 |
| `chat.attach` | 附件入口 |
| `chat.settings` | 打开群设置 |
| `chat.inspector` | 右侧群设置抽屉 |
| `chat.inspector.dismissArea` | 抽屉外部关闭区 |
| `chat.inspector.close` | 抽屉关闭按钮 |
| `attachment.preview.dialog` / `attachment.preview.close` | Desktop 文本附件预览弹窗与关闭 |
| `attachment.preview.{loading|text|markdown|error|tooLarge|unsupportedCharset}` | TXT/Markdown 附件预览状态与正文 |
| `attachment.preview.retry` / `attachment.preview.external` | 预览重试与改用系统应用打开 |

## 群组、成员和转发

| testTag | 作用 |
|---|---|
| `group.name` / `group.create` | 群名与创建提交 |
| `group.member.{uid前8}` | 建群候选或群详情成员 |
| `group.detail.invite` | 邀请成员 |
| `group.detail.inviteLinks` | 邀请链接 |
| `group.detail.leave` | 退出或解散群 |
| `member.{uid前8}` | 成员管理列表项 |
| `forward.item.{chatId前12}` | 转发目标 |
| `search.msg.query` / `search.msg.submit` | 消息搜索 |
| `search.msg.result.{chatId前12}.{serverSeq}` | 消息搜索结果 |

## 群机器人

| testTag | 作用 |
|---|---|
| `group.detail.bots` | 从群设置进入通知机器人管理 |
| `group.bots.screen` / `group.bots.close` | 通知机器人页面与 Desktop 关闭操作 |
| `group.bots.add` / `group.bots.empty` / `group.bots.loading` | 所有成员可用的添加入口、空状态与加载状态 |
| `group.bots.error` / `group.bots.retry` | 加载或操作错误与重试 |
| `group.bots.create.name` / `group.bots.create.confirm` | 新机器人名称与创建提交 |
| `group.bots.credentials` / `group.bots.credentials.copyExample` / `group.bots.credentials.saved` | 一次性凭据弹窗、复制完整调用示例与已安全保存确认 |
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
| `documents.space.workspace` / `documents.space.back` / `documents.space.overview` | 空间工作区、从目录返回首页与空间概览 |
| `documents.space.settings` / `documents.space.settings.dialog` | 空间信息与授权管理 |
| `documents.folder.create` / `documents.document.create` | 新建目录或文档 |
| `documents.tree` / `documents.tree.root` | 紧凑目录树与空间根节点 |
| `documents.node.{nodeId前8}` / `documents.tree.toggle.{nodeId前8}` | 目录节点与文件夹展开/折叠 |
| `documents.tab.{tabId前12}` | 已打开的跨空间文档标签 |
| `documents.editor.title` / `documents.editor.body` / `documents.editor.blocks` | 标题、文档编辑器与无损块级画布 |
| `documents.editor.back` | Android 单文档编辑器返回空间目录；离开前同步捕获最后一拍草稿 |
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
| `documents.editor.history` / `documents.revisions.dialog` | 打开版本历史及其弹窗 |
| `documents.document.more` | 文档级更多操作 |
| `documents.revision.{revision}` / `documents.revision.restore` | 指定修订及恢复 |
| `documents.document.delete` / `documents.document.delete.confirm` | 删除入口与确认 |
| `documents.tab.discard.confirm` | Desktop 未保存标签关闭保护 |
| `documents.mobile.discard.{dialog|confirm|cancel}` | Android 返回目录或切换文档时的未保存确认 |
| `documents.detach` / `documents.detached.placeholder` / `documents.detached.bringBack` | 拉出独立窗口、主窗口承接态与收回操作 |
| `main.error.snackbar` | Android 会话级错误提示，包含文档删除等操作失败 |

## 命名规则

- 静态控件使用 `领域.角色`，例如 `chat.send`。
- 动态列表项追加稳定业务 ID 的短前缀，不使用列表序号。
- 容器、打开按钮和关闭路径分别命名，便于断言临时层生命周期。
- 不把中文文案写入新选择器；现有 `nav.tab.*`、`settings.*` 是兼容保留，后续统一改名需同步更新验收代码。
- 新增关键交互时，代码、本文和相关验收场景应在同一变更中更新。
