# testTag 完整参考

> 所有 UI 交互**必须**通过 testTag（Android resource-id / Desktop 语义树 testTag）定位，禁止文本匹配。
> 文本可能有多个（标题+按钮同名）、可能被合并、可能随语言变化。

## 登录/注册页

| testTag | 类型 | 说明 |
|---------|------|------|
| `login.username` | TextField | 用户名输入 |
| `login.password` | TextField | 密码输入 |
| `login.submit` | Button | 登录按钮 |
| `login.gotoRegister` | TextButton | "没有账号？注册" |
| `register.username` | TextField | 注册用户名 |
| `register.displayName` | TextField | 注册显示名 |
| `register.password` | TextField | 注册密码 |
| `register.submit` | Button | 注册按钮 |
| `register.gotoLogin` | TextButton | "已有账号？登录" |

## 主界面 (HomeScreen)

| testTag | 类型 | 说明 |
|---------|------|------|
| `main.home` | Scaffold | 主界面容器（用于判断状态）|
| `nav.会话` | NavigationBarItem | 会话 Tab |
| `nav.通讯录` | NavigationBarItem | 通讯录 Tab |
| `nav.设置` | NavigationBarItem | 设置 Tab |
| `action.search` | IconButton | 搜索消息 |
| `action.createGroup` | IconButton | 发起群聊 |
| `action.addFriend` | IconButton | 添加好友/搜索用户 |
| `action.friendApplies` | IconButton | 好友申请入口 |
| `conv.item.{chatId前12}` | Row | 会话列表项 |
| `conv.pin.{chatId前12}` | IconButton | 置顶/取消置顶按钮 |
| `contact.{uid前8}` | Row | 通讯录联系人项 |

## 设置/个人资料

| testTag | 类型 | 说明 |
|---------|------|------|
| `settings.编辑资料` | ListItem | 编辑资料入口 |
| `settings.修改密码` | ListItem | 修改密码入口 |
| `settings.设备管理` | ListItem | 设备管理入口 |
| `settings.黑名单` | ListItem | 黑名单入口 |
| `settings.logout` | Row | 退出登录 |
| `profile.save` | Button | 保存资料 |
| `profile.name` | TextField | 显示名 |
| `profile.phone` | TextField | 手机号（可选）|
| `password.old` | TextField | 旧密码 |
| `password.new` | TextField | 新密码 |
| `password.confirm` | TextField | 确认密码 |
| `password.submit` | Button | 修改密码提交 |

## 搜索/用户资料

| testTag | 类型 | 说明 |
|---------|------|------|
| `search.query` | TextField | 搜索用户输入 |
| `search.submit` | Button/IconButton | 搜索提交 |
| `search.msg.query` | TextField | 搜索消息输入 |
| `search.msg.submit` | IconButton | 搜索消息提交 |
| `profile.addFriend` | Button | 添加好友 |
| `profile.sendMessage` | Button | 发消息 |
| `profile.deleteFriend` | Button | 删除好友（isFriend时）|
| `profile.applied` | Button | 已申请（disabled状态）|

## 聊天页

| testTag | 类型 | 说明 |
|---------|------|------|
| `chat.input` | TextField | 消息输入框 |
| `chat.send` | Button | 发送按钮 |
| `chat.voiceMode` | IconButton | 语音模式切换 |
| `chat.pickImage` | IconButton | 选择图片 |
| `chat.pickFile` | IconButton | 选择文件 |

## 建群/群详情

| testTag | 类型 | 说明 |
|---------|------|------|
| `group.create` | Button | 创建群组 |
| `group.name` | TextField | 群名输入 |
| `group.member.{uid前8}` | Row | 群成员勾选项 |
| `group.detail.leave` | Button | 退出/解散群组 |
| `group.detail.invite` | ListItem | 邀请成员 |
| `group.detail.inviteLinks` | ListItem | 邀请链接 |
| `member.{uid前8}` | ListItem | 成员列表项 |

## 转发

| testTag | 类型 | 说明 |
|---------|------|------|
| `forward.item.{chatId前12}` | ListItem | 转发目标会话 |
