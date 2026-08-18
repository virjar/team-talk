# Android 客户端

## 1. 导航模型

Android 使用单 Activity、Compose NavHost 和系统返回栈。一级栏目由底部导航承载；资料、搜索、群
详情和任务通常进入全屏目的页。它不复用 Desktop 的 Window、右侧检查器或三栏布局。

```text
MainActivity
  └── Home（会话 / 通讯录 / 设置）
       └── NavHost destinations
            ├── Chat
            ├── UserProfile
            ├── GroupDetail
            ├── CreateGroup
            └── Search / Security / Devices …
```

## 2. 平台体验

- 列表项最小 72dp，头像 48dp，TopAppBar 56dp。
- 长按打开上下文菜单；没有 hover 和右键概念。
- 系统返回按 NavHost/弹层顺序退出。
- 输入法动作与 Enter 行为必须适配软键盘；不能照搬 Desktop 快捷键。
- 权限、通知、媒体选择和后台生命周期由 Android 层处理。

## 3. 与 Desktop 共享的内容

- ViewModel、Repository 与 LocalCache 数据流。
- 主题语义色、字阶、间距和头像规则。
- 消息 body renderer 的领域解释。
- 通讯录、搜索结果、资料动作等可复用内容片段。

共享组件通过参数适配尺寸与动作，不能读取 Desktop 导航对象或创建 Window。

## 4. 通讯录与搜索

Android 通讯录同样先区分组织架构与好友；组织层级和部门成员语义与 Desktop 一致，资料仍用 Android
全屏页面承载。好友列表可以保留右侧字母索引，因为竖向触控快速跳转在长列表中有价值。全局搜索从
TopAppBar 入口进入全屏结果页，分类和数据源与 Desktop 一致。

添加好友、建群和好友申请的产品归属不因平台变化：添加好友从资料发起，建群从明确成员上下文
发起，好友申请是通讯录固定入口。

## 5. 聊天与媒体

消息语义与 Desktop 一致，但媒体播放使用 Android 平台能力：图片加载、Media3/MediaPlayer、系统
文件选择和下载目录。客户端仍遵守统一附件相对路径，不直接信任消息中的任意 URL。

输入器支持多行、富文本、`@` 和附件。触屏空间不足时可以调整工具栏或使用 bottom sheet，但发送
出的 Markdown/Attachment 契约必须一致。

## 6. 生命周期

- Activity 重建不能重建用户身份或丢失持久化 token。
- ClientSession 属于用户层，不应绑定单个页面。
- 切后台可能影响通知和媒体，但普通暂停不等于登出。
- AUTH_FAILED 需要清 token 并返回登录，网络断开只触发 SDK 重连。

## 7. 验证

本地 JVM 测试覆盖共享逻辑，Android 特有行为使用真机/模拟器、ADB 和 uiautomator2 验证。涉及
服务端或跨客户端的业务结果仍以真实部署验收为准。
