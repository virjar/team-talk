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

## 应用壳与搜索

| testTag | 作用 |
|---|---|
| `main.home` | 已进入主界面 |
| `app.titleBar` | Desktop 应用级标题栏 |
| `nav.avatar` | 当前用户入口 |
| `nav.tab.会话` / `nav.tab.通讯录` / `nav.tab.设置` | 一级导航 |
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
| `search.query` / `search.submit` | 用户搜索输入与提交 |
| `search.result.{uid前8}` | 用户搜索结果 |
| `profile.dialog` / `profile.close` | Desktop 资料弹窗与关闭 |
| `profile.addFriend` / `profile.applied` | 申请好友状态 |
| `profile.sendMessage` | 发起私聊 |
| `profile.createGroup` | 从资料发起群聊 |
| `profile.deleteFriend` | 删除好友 |

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
| `chat.send` | 发送消息 |
| `chat.emoji` | 表情入口 |
| `chat.voiceMode` | 语音模式 |
| `chat.attach` | 附件入口 |
| `chat.settings` | 打开群设置 |
| `chat.inspector` | 右侧群设置抽屉 |
| `chat.inspector.dismissArea` | 抽屉外部关闭区 |
| `chat.inspector.close` | 抽屉关闭按钮 |

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
| `documents.space.create` / `documents.space.create.dialog` | 新建空间入口与弹窗 |
| `documents.space.{spaceId前8}` | 空间列表项 |
| `documents.space.settings` / `documents.space.settings.dialog` | 空间信息与授权管理 |
| `documents.folder.create` / `documents.document.create` | 新建目录或文档 |
| `documents.node.{nodeId前8}` | 当前目录节点 |
| `documents.tab.{tabId前12}` | 已打开的跨空间文档标签 |
| `documents.editor.title` / `documents.editor.body` | 标题与 Markdown 富文本编辑器 |
| `documents.editor.preview` / `documents.editor.save` | 编辑预览切换与保存 |
| `documents.editor.history` / `documents.revisions.dialog` | 打开版本历史及其弹窗 |
| `documents.revision.{revision}` / `documents.revision.restore` | 指定修订及恢复 |
| `documents.document.delete` / `documents.document.delete.confirm` | 删除入口与确认 |
| `documents.tab.discard.confirm` | 未保存标签关闭保护 |
| `documents.detach` / `documents.detached.placeholder` / `documents.detached.bringBack` | 拉出独立窗口、主窗口承接态与收回操作 |

## 命名规则

- 静态控件使用 `领域.角色`，例如 `chat.send`。
- 动态列表项追加稳定业务 ID 的短前缀，不使用列表序号。
- 容器、打开按钮和关闭路径分别命名，便于断言临时层生命周期。
- 不把中文文案写入新选择器；现有 `nav.tab.*`、`settings.*` 是兼容保留，后续统一改名需同步更新验收代码。
- 新增关键交互时，代码、本文和相关验收场景应在同一变更中更新。
