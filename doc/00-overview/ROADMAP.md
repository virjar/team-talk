# TeamTalk 开发路线图

> 更新: 2026-06-21

---

## 待修复

| 编号 | 问题 | 根因 | 状态 |
|------|------|------|------|
| **F13** | 长按消息弹出系统文本选择菜单而非 App Popup | `MessageBodyRenderer` 的 `Text` 默认可被系统选择，拦截了 `combinedClickable.onLongClick` | 待修复 |
| **T14** | 删除好友功能未接入 UI | UserProfileScreen 的 deleteFriend 按钮待接入 ViewModel | 待实现 |
| **F15** | Desktop Profile 无源码级隔离 | Desktop 通过 JVM 参数注入 Profile（非 Android 的 productFlavors），Profile 间共享编译产物 | 待实现：为 Desktop 注册独立的 buildConfigField + 打包任务 |

---

## 近期已完成

| 内容 | 说明 |
|------|------|
| 客户端日志体系重构 | trace/fault/snapshot分级 + HTTP上传 + Crash持久化 + 移除logback（详见 [05-logging/](05-logging/)） |
| TkLogger 日志抽象 | shared模块日志通过注入模式采集，server=SLF4J/client=AppLog |
| ChatMediaConfig 参数收敛 | ChatPanel 13个lambda → 1个data class |
| LocalCache 合并策略 | mergeConversation 字段级合并（unreadCount/readSeq/draft） |
| Desktop 富媒体发送 | 文件/图片/视频选择 + 拖拽文件 + 语音录制 |
| Desktop 图片气泡 fit-inside | 参考 Signal 240×320dp 策略 |
| 三栏导航 bug 修复 | openChat 清除 currentScreen |
| 草稿/红点 bug 修复 | 空草稿转null + unreadCount 合并保护 |
| 心跳优化 | PING 15s / readerIdle 45s |
| CreateGroup 窗口路由修复 | key(windowScreen) 强制重建 SubWindow |
| ChangePassword 交互改进 | loading动画 + 成功提示 |
| DropdownMenu Popup 语义可见 | TestHttpServer 遍历所有 semantics owners |
| 文档体系深化 | 7大模块多级目录文档（详见 [README.md](README.md)） |
| Android TeamTalkApp | Application 初始化从 Activity 迁移 |
| CI println 检查脚本 | scripts/check-println.sh |
| Android R8 代码压缩 | 25MB → 9.1MB (-64%) |
| Desktop E2E 全流程 | 33 PASS / 1 SKIP |
| 固定签名证书 | teamtalk-dev.jks 对齐 V1 |
| TCP 重连掉登录修复 | pendingAuth 更新 refreshToken + send 门禁 |
